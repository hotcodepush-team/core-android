package com.hotcodepush.core

/** What the device knows when it evaluates a channel index. */
data class DeviceInfo(
    /** The sequence of the index the device has already evaluated; an older index is ignored. */
    val appliedIndexSequence: Int?,
    val attributes: Map<String, String>,
    val binaryBuild: String,
    val binaryVersion: String,
    /** The floor from the resource file: no release created before it is applied. */
    val builtAt: Long,
    /** The running release, or `null` for the embedded bundle; only its id and number matter here. */
    val currentRelease: Release?,
    val deviceId: String,
    val failedBundleIds: List<String>,
    val fingerprint: String?,
    val osVersion: String,
    /** The server time of the last acknowledged report, for the spending cap. */
    val reportedAt: Long?,
)

data class Skip(val reason: SkippedReason, val condition: ConditionType? = null)

/** The per-release verdict, the explanation behind the outcome and the probe's output. */
data class ReleaseVerdict(val release: IndexRelease, val isEligible: Boolean, val reason: SkippedReason? = null, val condition: ConditionType? = null)

/**
 * The outcome for the device. On `SKIPPED` with `RELEASE_REVOKED`, the release is the one the device resolves to —
 * `null` for the embedded bundle; on every other `SKIPPED` it is the newest release the device will not take.
 */
sealed class Evaluation {
    data class UpToDate(val release: IndexRelease?) : Evaluation()
    data class Available(val release: IndexRelease, val isMandatory: Boolean) : Evaluation()
    data class Skipped(val release: IndexRelease?, val reason: SkippedReason, val condition: ConditionType? = null) : Evaluation()
}

/** The outcome with the verdicts behind it, newest release first; an index the device does not evaluate — older than the applied one, or capped — leaves them empty. */
data class IndexEvaluation(val outcome: Evaluation, val verdicts: List<ReleaseVerdict>)

/** The device protocol's evaluation, the same rules as `@hotcodepush/protocol`'s, pinned by its fixture suite. */
object Evaluator {
    private const val EMBEDDED_RELEASE_NUMBER = 0

    fun evaluate(index: ChannelIndex, device: DeviceInfo): Evaluation = evaluation(index, device).outcome

    fun evaluation(index: ChannelIndex, device: DeviceInfo): IndexEvaluation {
        val currentIndexRelease = device.currentRelease?.let { current -> index.releases.firstOrNull { it.id == current.id } }
        val applied = device.appliedIndexSequence
        if (applied != null && index.sequence < applied) return IndexEvaluation(Evaluation.UpToDate(currentIndexRelease), emptyList())
        if (isDeviceBeyondCap(index, device)) return IndexEvaluation(Evaluation.Skipped(null, SkippedReason.SPENDING_CAP_REACHED), emptyList())
        val verdicts = index.releases.sortedByDescending { it.number }.map { verdict(it, index, device) }
        return IndexEvaluation(outcome(verdicts, index, device, currentIndexRelease), verdicts)
    }

    private fun outcome(verdicts: List<ReleaseVerdict>, index: ChannelIndex, device: DeviceInfo, currentIndexRelease: IndexRelease?): Evaluation {
        val currentNumber = device.currentRelease?.number ?: EMBEDDED_RELEASE_NUMBER
        val isCurrentRevoked = device.currentRelease?.let { isRevoked(it.id, index) } ?: false
        val newerVerdict = verdicts.firstOrNull { it.release.number > currentNumber && it.reason != SkippedReason.RELEASE_REVOKED }
        val newerEligible = verdicts.firstOrNull { it.isEligible && it.release.number > currentNumber }
        val olderEligible = verdicts.firstOrNull { it.isEligible && it.release.number < currentNumber }
        if (index.isPaused) {
            if (isCurrentRevoked) return Evaluation.Skipped(olderEligible?.release, SkippedReason.RELEASE_REVOKED)
            if (newerVerdict != null) return Evaluation.Skipped(newerVerdict.release, SkippedReason.CHANNEL_PAUSED)
            return Evaluation.UpToDate(currentIndexRelease)
        }
        if (newerEligible != null) return Evaluation.Available(newerEligible.release, isMandatoryTransitively(newerEligible.release, currentNumber, verdicts))
        if (isCurrentRevoked) return Evaluation.Skipped(olderEligible?.release, SkippedReason.RELEASE_REVOKED)
        if (newerVerdict?.reason != null) return Evaluation.Skipped(newerVerdict.release, newerVerdict.reason, newerVerdict.condition)
        return Evaluation.UpToDate(currentIndexRelease)
    }

    fun verdict(release: IndexRelease, index: ChannelIndex, device: DeviceInfo): ReleaseVerdict {
        if (isRevoked(release.id, index)) return ReleaseVerdict(release, false, SkippedReason.RELEASE_REVOKED)
        if (release.createdAt < device.builtAt) return ReleaseVerdict(release, false, SkippedReason.RELEASE_OLDER_THAN_BINARY)
        if (release.bundleId in device.failedBundleIds) return ReleaseVerdict(release, false, SkippedReason.BUNDLE_FAILED_BEFORE)
        release.conditions.firstOrNull { !isSatisfied(it, device) }?.let { failed ->
            val type = failed.type ?: return ReleaseVerdict(release, false, SkippedReason.CONDITION_UNSUPPORTED)
            val reason = if (type == ConditionType.ATTRIBUTE || type == ConditionType.DEVICE) SkippedReason.DEVICE_NOT_TARGETED else SkippedReason.DEVICE_INCOMPATIBLE
            return ReleaseVerdict(release, false, reason, type)
        }
        if (Hashing.rolloutBucket(device.deviceId, release.id) >= release.rollout) return ReleaseVerdict(release, false, SkippedReason.DEVICE_NOT_IN_ROLLOUT)
        return ReleaseVerdict(release, true)
    }

    /** Whether the device satisfies the condition; an unknown type never does. */
    fun isSatisfied(condition: Condition, device: DeviceInfo): Boolean = when (condition) {
        is Condition.Attribute -> device.attributes[condition.key]?.let { Hashing.attributeHash(condition.key, it) == condition.valueSha256 } ?: false
        is Condition.Binary -> resolveBinaryVersion(device)?.let { VersionRange.isVersionInRange(it, condition.range) == true } ?: false
        is Condition.Device -> Hashing.deviceIdHash(device.deviceId) in condition.hashedIds
        is Condition.Fingerprint -> device.fingerprint != null && device.fingerprint == condition.hash
        is Condition.Os -> VersionRange.parseVersion(device.osVersion)?.let { VersionRange.isVersionInRange(it, condition.range) == true } ?: false
        is Condition.Unknown -> false
    }

    /** The binary version with the build number as its fourth component, when both are numbers. */
    internal fun resolveBinaryVersion(device: DeviceInfo): List<Int>? {
        val version = VersionRange.parseVersion(device.binaryVersion) ?: return null
        val build = VersionRange.parseVersion(device.binaryBuild)
        return if (build != null && build.size == 1) version + build else version
    }

    internal fun isDeviceBeyondCap(index: ChannelIndex, device: DeviceInfo): Boolean {
        val cappedAt = index.cappedAt ?: return false
        val reportedAt = device.reportedAt ?: return true
        return reportedAt >= cappedAt
    }

    /** A release is mandatory for the device when it or any release it skipped over is. */
    internal fun isMandatoryTransitively(target: IndexRelease, currentNumber: Int, verdicts: List<ReleaseVerdict>): Boolean =
        verdicts.any { it.release.isMandatory && it.release.number > currentNumber && it.release.number <= target.number }

    internal fun isRevoked(id: String, index: ChannelIndex): Boolean = id in index.revokedReleaseIds
}
