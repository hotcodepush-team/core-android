package com.hotcodepush.core

/** What the device knows when it evaluates a channel index. */
data class DeviceInfo(
    val attributes: Map<String, String>,
    val binaryBuild: String,
    val binaryVersion: String,
    /** The floor from the resource file: no release created before it is applied. */
    val builtAt: Long,
    /** The running release, or `null` for the embedded bundle. */
    val currentRelease: Release?,
    val deviceId: String,
    val failedBundleIds: List<String>,
    val fingerprint: String?,
    val osVersion: String,
    /** The server time of the last acknowledged report, for the spending cap. */
    val reportedAt: Long?,
)

/** The per-release verdict, the explanation behind the outcome and the probe's output. */
data class ReleaseVerdict(val release: IndexRelease, val isEligible: Boolean, val reason: SkippedReason? = null, val conditionType: ConditionType? = null)

/**
 * The outcome for the device. On `skipped` with `release_revoked`, the release is the one the device resolves to —
 * `null` for the embedded bundle; on every other `skipped` it is the newest release the device will not take.
 */
sealed class Evaluation {
    data class UpToDate(val release: IndexRelease?) : Evaluation()
    data class Available(val release: IndexRelease, val isMandatory: Boolean) : Evaluation()
    data class Skipped(val release: IndexRelease?, val reason: SkippedReason, val conditionType: ConditionType? = null) : Evaluation()
}

/** The outcome with the verdicts behind it, newest release first; a capped index leaves them empty. */
data class IndexEvaluation(val outcome: Evaluation, val verdicts: List<ReleaseVerdict>)

/** The device protocol's evaluation, the same rules as `@hotcodepush/protocol`'s, pinned by its fixture suite. */
object Evaluator {
    private const val EMBEDDED_RELEASE_NUMBER = 0

    // Major, minor and patch come before the build number.
    private const val BINARY_VERSION_MIN_COMPONENTS = 3

    fun evaluation(index: ChannelIndex, device: DeviceInfo): IndexEvaluation {
        if (isDeviceBeyondCap(index, device)) return IndexEvaluation(Evaluation.Skipped(null, SkippedReason.SPENDING_CAP_REACHED), emptyList())
        val verdicts = index.releases.sortedByDescending { it.number }.map { verdict(it, index, device) }
        return IndexEvaluation(outcome(verdicts, index, device), verdicts)
    }

    private fun outcome(verdicts: List<ReleaseVerdict>, index: ChannelIndex, device: DeviceInfo): Evaluation {
        val currentNumber = device.currentRelease?.number ?: EMBEDDED_RELEASE_NUMBER
        val isCurrentRevoked = device.currentRelease?.let { isRevoked(it.id, index) } ?: false
        val eligibleVerdicts = verdicts.filter { it.isEligible }
        val newerVerdict = verdicts.firstOrNull { it.release.number > currentNumber && it.reason != SkippedReason.RELEASE_REVOKED }
        val newerEligible = eligibleVerdicts.firstOrNull { it.release.number > currentNumber }
        val olderEligible = eligibleVerdicts.firstOrNull { it.release.number < currentNumber }
        if (index.pausedAt == null && newerEligible != null) return Evaluation.Available(newerEligible.release, isMandatoryTransitively(newerEligible.release, currentNumber, eligibleVerdicts))
        if (isCurrentRevoked) return Evaluation.Skipped(olderEligible?.release, SkippedReason.RELEASE_REVOKED)
        if (index.pausedAt != null && newerVerdict != null) return Evaluation.Skipped(newerVerdict.release, SkippedReason.CHANNEL_PAUSED)
        if (newerVerdict?.reason != null) return Evaluation.Skipped(newerVerdict.release, newerVerdict.reason, newerVerdict.conditionType)
        return Evaluation.UpToDate(device.currentRelease?.let { current -> index.releases.firstOrNull { it.id == current.id } })
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
        if (Hashing.rolloutBucket(device.deviceId, release.id) >= release.rolloutPercent) return ReleaseVerdict(release, false, SkippedReason.DEVICE_NOT_IN_ROLLOUT)
        return ReleaseVerdict(release, true)
    }

    // A case table: one branch per condition type.
    /** Whether the device satisfies the condition; an unknown type never does. */
    @Suppress("CyclomaticComplexMethod")
    fun isSatisfied(condition: Condition, device: DeviceInfo): Boolean = when (condition) {
        is Condition.Attribute -> device.attributes[condition.key]?.let { Hashing.attributeHash(condition.key, it) == condition.valueSha256 } ?: false
        is Condition.Binary -> resolveBinaryVersion(device)?.let { VersionRange.isVersionInRange(it, condition.range) == true } ?: false
        is Condition.Device -> Hashing.deviceIdHash(device.deviceId) in condition.hashedIds
        is Condition.Fingerprint -> device.fingerprint != null && device.fingerprint == condition.fingerprint
        is Condition.Os -> VersionRange.parseVersion(device.osVersion)?.let { VersionRange.isVersionInRange(it, condition.range) == true } ?: false
        is Condition.Unknown -> false
    }

    // A version of fewer than three components reads with zeros, so `1.0` build `57` is `1.0.0.57`.
    internal fun resolveBinaryVersion(device: DeviceInfo): List<Long>? {
        val version = VersionRange.parseVersion(device.binaryVersion) ?: return null
        val build = VersionRange.parseVersion(device.binaryBuild)
        if (build == null || build.size != 1) return version
        return version + List(maxOf(0, BINARY_VERSION_MIN_COMPONENTS - version.size)) { 0L } + build
    }

    internal fun isDeviceBeyondCap(index: ChannelIndex, device: DeviceInfo): Boolean {
        val cappedAt = index.cappedAt ?: return false
        val reportedAt = device.reportedAt ?: return true
        return reportedAt >= cappedAt
    }

    // Only releases the device could take count, so a skipped release the device is kept from never makes the move mandatory.
    internal fun isMandatoryTransitively(target: IndexRelease, currentNumber: Int, eligibleVerdicts: List<ReleaseVerdict>): Boolean =
        eligibleVerdicts.any { it.release.isMandatory && it.release.number > currentNumber && it.release.number <= target.number }

    internal fun isRevoked(id: String, index: ChannelIndex): Boolean = id in index.revokedReleaseIds
}
