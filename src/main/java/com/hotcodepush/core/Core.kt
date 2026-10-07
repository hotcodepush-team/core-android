package com.hotcodepush.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The state machine every framework shares: three named releases, a readiness gate and one cycle of three stages. */
class Core(
    val configuration: Configuration,
    private val device: DeviceFacts,
    store: KeyValueStore,
    private val files: FileStore,
    private val embedded: EmbeddedBundle,
    http: HttpClient,
    private val loader: BundleLoader,
    private val listener: CoreListener,
    private val scheduler: Scheduler,
    private val clock: Clock,
    private val scope: CoroutineScope,
    temporaryDirectory: File,
) {
    /** How far a cycle goes: the check alone, the download whatever the strategy says, or the whole sync. */
    private enum class Stage { CHECK, DOWNLOAD, SYNC }

    /** How the channel in effect resolved: the runtime choice, a name through the channels index, else the build's own. */
    private sealed class ChannelResolution {
        data class Id(val id: String) : ChannelResolution()
        object Offline : ChannelResolution()
        object Unknown : ChannelResolution()

        /** The build carries no channel and the app set none at runtime. */
        object Missing : ChannelResolution()
        data class Invalid(val message: String) : ChannelResolution()
    }

    /** A restart held at the gate and who asked for it: the app's own is never held by `setRestartAllowed(false)`. */
    private class QueuedRestart(val isAskedByApp: Boolean, val restart: () -> Unit)

    private val state = StateStore(store)
    private val downloader = Downloader(configuration, device.platform, files, embedded, http, temporaryDirectory)
    private val httpClient = http
    private val lock = Mutex()

    /** One cycle at a time, whatever its stage: two never fetch, download or write the same files beside each other. */
    private val cycleLock = Mutex()
    private val log = SessionLog()

    /** What escapes a task the core runs on its own is logged, never thrown at the app's process. */
    private val failureHandler = CoroutineExceptionHandler { _, failure -> log.record(LogEntry(clock.now(), SyncStatus.FAILED.wire, "a task of the core stopped: $failure")) }

    private var readyTimer: ScheduledTask? = null
    private var intervalTimer: ScheduledTask? = null
    private var runningSync: Deferred<SyncResult>? = null
    private var isRestartAllowed = true
    private var queuedRestart: QueuedRestart? = null

    /** The app is up in this run: it rendered, called `notifyReady()` or ran out of time since the start or the last reload. */
    private var hasStartSettled = false
    private var isStartSyncPending = false
    private var isSendingDeviceEvents = false

    /** The events enqueued while a batch is on its way: never part of it, so they stay in the outbox whatever the answer. */
    private var eventCountEnqueuedInFlight = 0
    private var backgroundedAt: Long? = null
    private var resolvedChannelName: Pair<String, String>? = null

    /** The stored rollback notice was announced in this process, so the app coming up next has received it. */
    private var hasAnnouncedRollback = false

    /** The release a switch in this process replaced, `null` for the embedded bundle, until `notifyReady()` reads it. */
    private var switchedFromRelease: Release? = null

    // Lifecycle

    /**
     * The start of a run: the binary's floor, the files on disk, the previous run's verdict, the pending switch, a rollback the app
     * has not come up after, the gate, then the cleanup.
     */
    suspend fun handleAppStart() = lock.withLock {
        if (state.pendingRollbackEvent == null) state.lastRollback = null
        if (state.lastBuiltAt != configuration.builtAt || hasReleaseWithoutManifest()) dropStoredReleases()
        if (isCurrentReleaseUnconfirmed()) rollbackCurrentRelease(RollbackReason.APP_CRASHED, null)
        discardNextReleaseThatLeftTheIndex()
        val next = state.nextRelease
        if (next != null && shouldSwitchAtStart(next)) switchToNextRelease()
        loadBundle()
        if (!hasAnnouncedRollback) announceRollback()
        if (isCurrentReleaseUnconfirmed()) {
            startReadyTimer()
            isStartSyncPending = true
        } else if (configuration.autoCheck) {
            startAutomaticCycle(SyncTrigger.START)
        }
        deleteUnusedFiles()
    }

    /** A mandatory release follows its own strategy, so one the app took over waits across starts; any other switches under `next-start`; a bundle the WebView already serves is adopted. */
    private fun shouldSwitchAtStart(next: Release): Boolean = when {
        loader.servedBundleId() == next.bundleId -> true
        next.isMandatory -> configuration.mandatoryInstallStrategy == MandatoryInstallStrategy.IMMEDIATE
        else -> configuration.installStrategy == InstallStrategy.NEXT_START
    }

    /** A `next-resume` release; a mandatory one follows `mandatoryInstallStrategy` instead, so one the app took over with `manual` waits for `applyUpdate()`. */
    private fun shouldInstallAtResume(next: Release): Boolean = !next.isMandatory && configuration.installStrategy == InstallStrategy.NEXT_RESUME

    /** The first render of the run or of a reload: the readiness signal when `readySignal` is `render`, and on every setting what settles the start. */
    suspend fun handleRendered() = lock.withLock {
        if (configuration.readySignal == ReadySignal.RENDER) confirmCurrentRelease()
        settleStart()
    }

    /**
     * Ends the gate when `readySignal` is `manual`, settles the start, and tells the app whether this start follows a rollback;
     * `previousRelease` is the release before this start when it changed, by a switch or a rollback, once.
     */
    suspend fun notifyReady(): NotifyReadyResult = lock.withLock {
        confirmCurrentRelease()
        val rollback = state.lastRollback
        state.lastRollback = null
        val previousRelease = rollback?.from ?: switchedFromRelease
        switchedFromRelease = null
        val result = NotifyReadyResult(state.currentRelease, previousRelease, rollback != null, rollback?.reason)
        settleStart()
        result
    }

    /** The background: the interval timer stops, since interval checks belong to the foreground, and the moment is kept for `next-resume`. */
    suspend fun handleAppPause() = lock.withLock {
        backgroundedAt = clock.now()
        intervalTimer?.cancel()
        intervalTimer = null
    }

    /** A resume installs a `next-resume` release after enough time in the background, else checks when the interval has passed. */
    suspend fun handleAppResume() = lock.withLock {
        val backgroundDuration = backgroundedAt?.let { (clock.now() - it) / 1000.0 }
        backgroundedAt = null
        discardNextReleaseThatLeftTheIndex()
        val next = state.nextRelease
        if (backgroundDuration != null && next != null && shouldInstallAtResume(next) && backgroundDuration >= configuration.installOnResumeAfter) {
            installNextRelease()
            return
        }
        if (!configuration.autoCheck) return
        val elapsedSeconds = state.lastSyncAt?.let { (clock.now() - it) / 1000.0 }
        if (elapsedSeconds == null || elapsedSeconds >= configuration.checkInterval) {
            startAutomaticCycle(SyncTrigger.RESUME)
            return
        }
        scheduleIntervalSync(configuration.checkInterval - elapsedSeconds)
    }

    /** The start's, the resume's and the interval's cycle; a device without a channel starts none, since it could only fail. */
    private fun startAutomaticCycle(trigger: SyncTrigger) {
        if (!hasChannel) return
        launchTask { sync(trigger) }
    }

    private fun launchTask(task: suspend () -> Unit) {
        scope.launch(failureHandler) { task() }
    }

    // The three stages

    /** One full cycle; a second call while one runs joins the running one. */
    suspend fun sync(trigger: SyncTrigger, options: SyncOptions = SyncOptions()): SyncResult {
        verifyChannelId()
        val running = lock.withLock {
            runningSync ?: scope.async(start = CoroutineStart.LAZY) { performSync(trigger, options) }.also { runningSync = it }
        }
        return running.await()
    }

    /** The running sync, which the next sync no longer joins once it ended, however it ended. */
    private suspend fun performSync(trigger: SyncTrigger, options: SyncOptions): SyncResult = try {
        cycleLock.withLock { performCycle(trigger, Stage.SYNC, options) }
    } finally {
        withContext(NonCancellable) { lock.withLock { runningSync = null } }
    }

    /** The first stage: fetch and evaluate, download nothing; after the cycle that runs, never beside it. */
    suspend fun checkForUpdate(): SyncResult {
        verifyChannelId()
        return cycleLock.withLock { performCycle(SyncTrigger.MANUAL, Stage.CHECK, SyncOptions()) }
    }

    /** The second stage: download and verify the update the check finds, whatever `downloadStrategy` says, then install per the strategies; after the cycle that runs, never beside it. */
    suspend fun downloadUpdate(): SyncResult {
        verifyChannelId()
        return cycleLock.withLock { performCycle(SyncTrigger.MANUAL, Stage.DOWNLOAD, SyncOptions()) }
    }

    /** The third stage: apply the downloaded update and reload the app, now or, before the app is up in this run, once it is. */
    suspend fun applyUpdate(): ApplyResult = lock.withLock {
        discardNextReleaseThatLeftTheIndex()
        val next = state.nextRelease ?: return ApplyResult(ApplyStatus.NOTHING_TO_APPLY, state.currentRelease)
        restartThroughGate(isAskedByApp = true) {
            switchToNextRelease()
            reloadApp()
        }
        ApplyResult(ApplyStatus.APPLIED, next)
    }

    private suspend fun performCycle(trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult {
        val result = resolveCycleSafely(trigger, stage, options)
        lock.withLock {
            if (stage != Stage.DOWNLOAD) state.lastCheck = LastCheck(clock.now(), trigger, result)
            if (stage == Stage.SYNC) {
                state.lastSyncAt = clock.now()
                scheduleIntervalSync(configuration.checkInterval)
            }
        }
        log.record(LogEntry.ofCycle(result, trigger, clock.now()))
        if (result.status == SyncStatus.FAILED) {
            val reason = result.reason?.let { name -> FailedReason.entries.firstOrNull { it.name == name } }
            if (reason != null) listener.updateFailed(UpdateFailedEvent(result.release, reason, result.message ?: "", trigger))
        }
        launchTask { sendDeviceEvents() }
        return result
    }

    /** A cycle answers, whatever is thrown inside it: what no stage turned into its own reason is `FAILED` with `INDEX_INVALID`, the cause in the message. */
    private suspend fun resolveCycleSafely(trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult = try {
        resolveCycle(trigger, stage, options)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        SyncResult.failed(null, FailedReason.INDEX_INVALID, "The cycle stopped on $failure")
    }

    private suspend fun resolveCycle(trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult {
        val current = state.currentRelease
        if (isDisabledInThisBuild) return SyncResult.skipped(current, SkippedReason.BUILD_DEBUG)
        val channelId = when (val resolution = resolveChannelId()) {
            is ChannelResolution.Id -> resolution.id
            ChannelResolution.Offline -> return SyncResult.failed(current, FailedReason.DEVICE_OFFLINE, "The channels index could not be fetched to resolve the channel name")
            ChannelResolution.Unknown -> return SyncResult.failed(current, FailedReason.CHANNEL_UNKNOWN, "The channel set at runtime is not in the app's channels index")
            ChannelResolution.Missing -> return SyncResult.failed(current, FailedReason.CHANNEL_UNKNOWN, MISSING_CHANNEL_MESSAGE)
            is ChannelResolution.Invalid -> return SyncResult.failed(current, FailedReason.INDEX_INVALID, resolution.message)
        }
        val index = when (val fetch = fetchChannelIndex(channelId)) {
            is IndexFetch.Index -> fetch.index
            IndexFetch.Offline -> return SyncResult.failed(current, FailedReason.DEVICE_OFFLINE, "The channel index could not be fetched and no cached copy exists")
            is IndexFetch.Invalid -> return SyncResult.failed(current, FailedReason.INDEX_INVALID, fetch.message)
            IndexFetch.Absent -> return SyncResult.upToDate(current)
            IndexFetch.NoChannel -> return SyncResult.failed(current, FailedReason.CHANNEL_UNKNOWN, MISSING_CHANNEL_MESSAGE)
        }
        return when (val evaluation = Evaluator.evaluate(index, deviceInfo())) {
            is Evaluation.UpToDate -> SyncResult.upToDate(current)
            is Evaluation.Available -> {
                lock.withLock { recordChecked(evaluation.release, index, SyncStatus.AVAILABLE, null) }
                update(evaluation.release, evaluation.isMandatory, trigger, stage, options)
            }
            is Evaluation.Skipped -> when {
                evaluation.reason != SkippedReason.RELEASE_REVOKED -> {
                    evaluation.release?.let { release -> lock.withLock { recordChecked(release, index, SyncStatus.SKIPPED, Skip(evaluation.reason, evaluation.condition)) } }
                    SyncResult.skipped(evaluation.release?.release, evaluation.reason, evaluation.condition)
                }
                stage == Stage.CHECK -> SyncResult.skipped(evaluation.release?.release, SkippedReason.RELEASE_REVOKED)
                evaluation.release == null -> {
                    lock.withLock { revertToEmbedded() }
                    SyncResult.skipped(null, SkippedReason.RELEASE_REVOKED)
                }
                else -> {
                    val outcome = install(evaluation.release, true, InstallStrategy.IMMEDIATE, trigger, Stage.SYNC)
                    if (outcome.status == SyncStatus.FAILED) outcome else SyncResult.skipped(evaluation.release.release, SkippedReason.RELEASE_REVOKED)
                }
            }
        }
    }

    /**
     * A release the device qualifies for: adopted in place when it carries the running bundle, else announced and taken as far
     * as the stage goes. An adopted release runs already, so a download answers `UP_TO_DATE` where a sync answers `UPDATED`.
     */
    private suspend fun update(target: IndexRelease, isMandatory: Boolean, trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult {
        val release = resolveRelease(target, isMandatory)
        val current = state.currentRelease
        if (stage != Stage.CHECK && current != null && current.bundleId == target.bundleId) {
            lock.withLock { adoptInPlace(release) }
            return if (stage == Stage.DOWNLOAD) SyncResult.upToDate(release) else SyncResult.updated(release, target.notes, InstallMoment.IMMEDIATE)
        }
        val strategy = resolveInstallStrategy(isMandatory, options)
        if (isDownloaded(target)) {
            if (stage == Stage.CHECK) return SyncResult.available(release, target.notes, target.sizeBytes)
            val outcome = lock.withLock { applyDownloaded(release, target.notes, strategy) }
            return if (stage == Stage.DOWNLOAD) SyncResult.downloaded(release, target.notes) else outcome
        }
        listener.updateAvailable(UpdateAvailableEvent(release, target.notes, target.sizeBytes, trigger))
        return when (stage) {
            Stage.CHECK -> SyncResult.available(release, target.notes, target.sizeBytes)
            Stage.SYNC -> when (options.downloadStrategy ?: configuration.downloadStrategy) {
                DownloadStrategy.MANUAL -> SyncResult.available(release, target.notes, target.sizeBytes)
                DownloadStrategy.UNMETERED -> if (loader.isConnectionMetered()) SyncResult.skipped(release, SkippedReason.CONNECTION_METERED) else install(target, isMandatory, strategy, trigger, stage)
                DownloadStrategy.AUTO -> install(target, isMandatory, strategy, trigger, stage)
            }
            Stage.DOWNLOAD -> install(target, isMandatory, strategy, trigger, stage)
        }
    }

    /** The release as the app sees it: the index's entry with the mandatory flag the evaluation decided, transitive included. */
    private fun resolveRelease(target: IndexRelease, isMandatory: Boolean) = Release(target.id, target.number, target.bundleId, target.bundleVersion, isMandatory)

    /** A mandatory release follows `mandatoryInstallStrategy`; any other the install strategy. */
    private fun resolveInstallStrategy(isMandatory: Boolean, options: SyncOptions): InstallStrategy {
        if (isMandatory) {
            return when (options.mandatoryInstallStrategy ?: configuration.mandatoryInstallStrategy) {
                MandatoryInstallStrategy.IMMEDIATE -> InstallStrategy.IMMEDIATE
                MandatoryInstallStrategy.MANUAL -> InstallStrategy.MANUAL
            }
        }
        return options.installStrategy ?: configuration.installStrategy
    }

    private fun isDownloaded(target: IndexRelease): Boolean {
        val next = state.nextRelease ?: return false
        if (next.bundleId != target.bundleId) return false
        val manifest = files.readManifest(next.bundleId) ?: return false
        return files.isComplete(manifest, embedded)
    }

    private suspend fun install(target: IndexRelease, isMandatory: Boolean, strategy: InstallStrategy, trigger: SyncTrigger, stage: Stage): SyncResult {
        val release = resolveRelease(target, isMandatory)
        try {
            val baseBundleId = state.currentRelease?.bundleId ?: configuration.embeddedBundleId
            val outcome = downloader.downloadRelease(target, baseBundleId) { downloaded, total -> listener.downloadProgress(target.id, downloaded, total) }
            BundleProjection.project(outcome.manifest, files, embedded, loader.projectionDirectory(target.bundleId))
            lock.withLock { enqueueDeviceEvent(DeviceEvent.downloaded(target.id, target.bundleId, outcome.bytes, outcome.packKind)) }
        } catch (failure: DownloadFailure) {
            lock.withLock { enqueueDeviceEvent(DeviceEvent.failed(target.id, failure.reason.name)) }
            return SyncResult.failed(release, failure.reason, failure.message ?: "")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // An OutOfMemoryError from a large file among them: the download fails, the process lives.
            lock.withLock { enqueueDeviceEvent(DeviceEvent.failed(target.id, FailedReason.DOWNLOAD_FAILED.name)) }
            return SyncResult.failed(release, FailedReason.DOWNLOAD_FAILED, failure.message ?: failure.toString())
        }
        if (strategy != InstallStrategy.IMMEDIATE) listener.updateDownloaded(UpdateDownloadedEvent(release, strategy, trigger))
        val outcome = lock.withLock { applyDownloaded(release, target.notes, strategy) }
        return if (stage == Stage.DOWNLOAD) SyncResult.downloaded(release, target.notes) else outcome
    }

    /** Choosing and applying are two acts: the strategy is a policy over the four functions. */
    private fun applyDownloaded(release: Release, notes: String?, strategy: InstallStrategy): SyncResult {
        setNextRelease(release)
        when (strategy) {
            InstallStrategy.IMMEDIATE -> installNextRelease()
            InstallStrategy.NEXT_START -> loader.persistServedBundle(release.bundleId)
            InstallStrategy.NEXT_RESUME, InstallStrategy.MANUAL -> Unit
        }
        return SyncResult.updated(release, notes, strategy)
    }

    /** Rolls the running release back now, even before the app is up; `detail` is the app's own cause, carried on the failure event. */
    suspend fun rollbackUpdate(detail: String?) = lock.withLock {
        if (detail != null) AttributeRules.validate(detail)
        if (state.currentRelease != null) rollbackCurrentRelease(RollbackReason.APP_REQUESTED, detail)
    }

    /** Back to the embedded bundle, now or, before the app is up in this run, once it is: every downloaded update and the failed list go, the identity stays. */
    suspend fun clearUpdates() = lock.withLock {
        restartThroughGate(isAskedByApp = true) {
            stopReadyTimer()
            state.currentRelease = null
            state.nextRelease = null
            state.fallbackRelease = null
            state.failedBundleIds = emptyList()
            state.lastRollback = null
            state.pendingRollbackEvent = null
            files.bundleIds().forEach(loader::deleteProjection)
            files.deleteEverything()
            loader.persistServedBundle(null)
            reloadApp()
        }
    }

    suspend fun setRestartAllowed(allowed: Boolean) = lock.withLock {
        isRestartAllowed = allowed
        runQueuedRestart()
    }

    // State

    /** Everything the debug screen shows: the device, the configuration, the state and this session's log. */
    fun debugSnapshot(): DebugSnapshot = DebugSnapshot(clock.now(), deviceResult(), configuration, device.isDebugBuild, getState(), log.entries())

    fun getState(): StateResult {
        val cached = state.cachedIndex
        return StateResult(
            currentRelease = state.currentRelease,
            nextRelease = state.nextRelease,
            fallbackRelease = state.fallbackRelease,
            embeddedBundleId = configuration.embeddedBundleId,
            lastCheck = state.lastCheck,
            indexSequence = cached?.body?.sequence,
            indexFetchedAt = cached?.fetchedAt,
            failedBundleIds = state.failedBundleIds,
            lastReportAt = state.reportedAt,
        )
    }

    fun channel(): ChannelResult = when (val choice = state.channel) {
        is ChannelChoice.Id -> ChannelResult(choice.id, null, ChannelSource.RUNTIME)
        is ChannelChoice.Name -> ChannelResult(resolvedChannelName?.takeIf { it.first == choice.name }?.second, choice.name, ChannelSource.RUNTIME)
        null -> ChannelResult(configuration.channelId, null, ChannelSource.CONFIG)
    }

    /** A channel id names the index's URL, so one that is not a UUID is refused with the plain error and nothing is stored. */
    suspend fun setChannel(choice: ChannelChoice?) = lock.withLock {
        if (choice is ChannelChoice.Id) verifyChannelId(choice.id)
        state.channel = choice
        state.cachedIndex = null
    }

    fun deviceResult(): DeviceResult = DeviceResult(state.deviceId, device.platform, device.binaryVersion, device.binaryBuild, device.osVersion, device.sdkVersion, configuration.fingerprint, channel(), state.attributes)

    suspend fun setAttributes(changes: Map<String, String?>) = lock.withLock {
        val attributes = state.attributes.toMutableMap()
        for ((key, value) in changes) {
            if (value != null) {
                AttributeRules.validate(key, value)
                attributes[key] = value
            } else {
                attributes.remove(key)
            }
        }
        state.attributes = attributes
    }

    // The four functions and the gate

    /** A restored phone brings the store's keys back without its files: a current or next release with no manifest on disk names a tree that is not there. */
    private fun hasReleaseWithoutManifest(): Boolean = listOfNotNull(state.currentRelease, state.nextRelease).any { files.readManifest(it.bundleId) == null }

    /** A new binary carries a new floor and a restored phone carries no files: the stored releases are forgotten and the embedded bundle runs. */
    private fun dropStoredReleases() {
        state.currentRelease = null
        state.nextRelease = null
        state.fallbackRelease = null
        state.failedBundleIds = emptyList()
        state.lastRollback = null
        state.pendingRollbackEvent = null
        state.lastBuiltAt = configuration.builtAt
        loader.persistServedBundle(null)
    }

    private fun setNextRelease(release: Release) {
        state.nextRelease = release
    }

    /** A downloaded release that has left the cached index since — revoked, or gone from it — is never installed: it is dropped and the served bundle stays the running one. */
    private fun discardNextReleaseThatLeftTheIndex() {
        val next = state.nextRelease ?: return
        val index = state.cachedIndex?.body ?: return
        if (!hasLeftIndex(next, index)) return
        state.nextRelease = null
        loader.persistServedBundle(state.currentRelease?.bundleId)
    }

    private fun hasLeftIndex(release: Release, index: ChannelIndex): Boolean = release.id in index.revokedReleaseIds || index.releases.none { it.id == release.id }

    private fun switchToNextRelease() {
        val next = state.nextRelease ?: return
        switchedFromRelease = state.currentRelease
        state.currentRelease = next
        state.nextRelease = null
        loader.persistServedBundle(next.bundleId)
        enqueueDeviceEvent(DeviceEvent.applied(next.id))
    }

    private fun loadBundle() {
        val expected = state.currentRelease?.bundleId
        if (loader.servedBundleId() != expected) loader.loadServedBundle(expected)
    }

    /** The restart of the web layer: the start is unsettled until the reloaded app is up, a held restart is moot since the reloaded app runs what the state says, the bundle loads, a rollback the app has not come up after is announced, then the gate runs. */
    private fun reloadApp() {
        hasStartSettled = false
        queuedRestart = null
        loader.loadServedBundle(state.currentRelease?.bundleId)
        announceRollback()
        if (isCurrentReleaseUnconfirmed()) startReadyTimer()
    }

    /** The install the SDK performs on its own: the switch and the reload as one act behind the gate, so nothing changes until it runs; the served bundle is the next one already, so the next start switches if this run never does. */
    private fun installNextRelease() {
        state.nextRelease?.let { loader.persistServedBundle(it.bundleId) }
        restartThroughGate(isAskedByApp = false) {
            switchToNextRelease()
            reloadApp()
        }
    }

    /** A restart waits until the app is up in this run, and the SDK's own also while the app holds restarts. One is held at most: the app's replaces a held one, the SDK's yields to it. */
    private fun restartThroughGate(isAskedByApp: Boolean, restart: () -> Unit) {
        if (isAskedByApp || queuedRestart == null) queuedRestart = QueuedRestart(isAskedByApp, restart)
        runQueuedRestart()
    }

    private fun runQueuedRestart() {
        val queued = queuedRestart ?: return
        if (!hasStartSettled || !(isRestartAllowed || queued.isAskedByApp)) return
        queuedRestart = null
        queued.restart()
    }

    /** The app is up in this run: a rollback announced to it is delivered, and the restart held for it runs. */
    private fun settleStart() {
        hasStartSettled = true
        if (hasAnnouncedRollback) {
            hasAnnouncedRollback = false
            state.pendingRollbackEvent = null
        }
        runQueuedRestart()
    }

    /** The stored notice reaches the JavaScript that just started, once per start; it stays stored until the app is up after it. */
    private fun announceRollback() {
        val event = state.pendingRollbackEvent ?: return
        hasAnnouncedRollback = true
        listener.rolledBack(event)
    }

    private fun adoptInPlace(release: Release) {
        val wasConfirmed = !isCurrentReleaseUnconfirmed()
        state.currentRelease = release
        if (wasConfirmed) state.fallbackRelease = release
        loader.persistServedBundle(release.bundleId)
    }

    private fun isCurrentReleaseUnconfirmed(): Boolean {
        val current = state.currentRelease ?: return false
        return current.bundleId != state.fallbackRelease?.bundleId
    }

    private fun confirmCurrentRelease() {
        stopReadyTimer()
        val current = state.currentRelease
        if (current != null && isCurrentReleaseUnconfirmed()) {
            state.fallbackRelease = current
            enqueueDeviceEvent(DeviceEvent.confirmed(current.id))
        }
        if (isStartSyncPending) {
            isStartSyncPending = false
            if (configuration.autoCheck) startAutomaticCycle(SyncTrigger.START)
        }
    }

    /** A rollback never waits for the app to be up: the app's own and the crash a start finds reload at once, the timer's, which settles the start, waits only while the app holds restarts. */
    private fun rollbackCurrentRelease(reason: RollbackReason, detail: String?) {
        val current = state.currentRelease ?: return
        stopReadyTimer()
        state.failedBundleIds = (state.failedBundleIds + current.bundleId).distinct().sorted()
        val fallback = resolveFallbackRelease()
        state.currentRelease = fallback
        state.nextRelease = null
        state.lastRollback = LastRollback(current, fallback, reason)
        state.pendingRollbackEvent = RolledBackEvent(current, fallback, reason)
        hasAnnouncedRollback = false
        enqueueDeviceEvent(DeviceEvent.failed(current.id, reason.name, detail))
        enqueueDeviceEvent(DeviceEvent.rolledBack(current.id, fallback?.id))
        loader.persistServedBundle(fallback?.bundleId)
        if (reason == RollbackReason.READINESS_TIMED_OUT) restartThroughGate(isAskedByApp = false) { reloadApp() } else reloadApp()
    }

    /** The release to fall back to right now: the last confirmed one while it can still run, else the embedded bundle. */
    private fun resolveFallbackRelease(): Release? {
        val fallback = state.fallbackRelease ?: return null
        if (fallback.bundleId in state.failedBundleIds) return null
        if (state.cachedIndex?.body?.revokedReleaseIds?.contains(fallback.id) == true) return null
        val manifest = files.readManifest(fallback.bundleId) ?: return null
        return if (files.isComplete(manifest, embedded)) fallback else null
    }

    private fun revertToEmbedded() {
        stopReadyTimer()
        state.currentRelease = null
        state.nextRelease = null
        loader.persistServedBundle(null)
        restartThroughGate(isAskedByApp = false) { reloadApp() }
    }

    private fun startReadyTimer() {
        stopReadyTimer()
        readyTimer = scheduler.schedule(configuration.readyTimeout) { launchTask { handleReadyTimeout() } }
    }

    private fun stopReadyTimer() {
        readyTimer?.cancel()
        readyTimer = null
    }

    /** The timer running out settles the start, so its rollback waits only while the app holds restarts. */
    internal suspend fun handleReadyTimeout() = lock.withLock {
        if (!isCurrentReleaseUnconfirmed()) return
        hasStartSettled = true
        rollbackCurrentRelease(RollbackReason.READINESS_TIMED_OUT, null)
    }

    private fun scheduleIntervalSync(afterSeconds: Double) {
        intervalTimer?.cancel()
        if (!configuration.autoCheck) return
        intervalTimer = scheduler.schedule(afterSeconds) { launchTask { lock.withLock { startAutomaticCycle(SyncTrigger.INTERVAL) } } }
    }

    /** Everything no kept release lists: the served tree of every other bundle first, since its links hold the bytes. */
    private fun deleteUnusedFiles() {
        val kept = listOfNotNull(state.currentRelease, state.nextRelease, state.fallbackRelease).map { it.bundleId }.toSet()
        files.bundleIds().filter { it !in kept }.forEach(loader::deleteProjection)
        files.deleteUnusedFiles(kept)
    }

    // The index

    private sealed class IndexFetch {
        data class Index(val index: ChannelIndex) : IndexFetch()
        object Offline : IndexFetch()
        data class Invalid(val message: String) : IndexFetch()
        object Absent : IndexFetch()

        /** The runtime channel serves no index and the build carries none to fall back to. */
        object NoChannel : IndexFetch()
    }

    /** The runtime choice, then the configured id, which a build made without a token or offline does not carry; a name resolves through the channels index, offline being offline and not an unknown name. */
    private suspend fun resolveChannelId(): ChannelResolution = when (val choice = state.channel) {
        null -> configuration.channelId?.let { ChannelResolution.Id(it) } ?: ChannelResolution.Missing
        is ChannelChoice.Id -> ChannelResolution.Id(choice.id)
        is ChannelChoice.Name -> {
            val resolved = resolvedChannelName
            if (resolved != null && resolved.first == choice.name) {
                ChannelResolution.Id(resolved.second)
            } else {
                val url = "${configuration.filesBaseUrl}/apps/${configuration.appId}/channels/v1/index.json"
                val response = runCatching { httpClient.get(url, emptyMap()) }.getOrNull()
                when (response?.status) {
                    null -> ChannelResolution.Offline
                    200 -> {
                        val index = runCatching { ChannelsIndex.fromJson(org.json.JSONObject(String(response.body, Charsets.UTF_8))) }.getOrNull()
                        if (index == null) {
                            ChannelResolution.Invalid("The channels index could not be parsed")
                        } else {
                            val entry = index.channels.firstOrNull { it.name == choice.name }
                            if (entry == null) {
                                ChannelResolution.Unknown
                            } else {
                                resolvedChannelName = choice.name to entry.id
                                ChannelResolution.Id(entry.id)
                            }
                        }
                    }
                    404 -> ChannelResolution.Unknown
                    else -> ChannelResolution.Offline
                }
            }
        }
    }

    /** The channel id a cycle would fetch by, the runtime one or the build's, is a UUID; any other is a programming mistake, refused before anything is fetched. */
    private fun verifyChannelId() {
        when (val choice = state.channel) {
            is ChannelChoice.Id -> verifyChannelId(choice.id)
            is ChannelChoice.Name -> Unit
            null -> configuration.channelId?.let(::verifyChannelId)
        }
    }

    private fun verifyChannelId(id: String) {
        if (!WireRule.UUID.accepts(id)) throw PlainException("A channel id is a UUID: $id")
    }

    /** A device has a channel when the app set one at runtime or the build carries one. */
    private val hasChannel: Boolean
        get() = state.channel != null || configuration.channelId != null

    private suspend fun fetchChannelIndex(channelId: String): IndexFetch {
        val url = "${configuration.filesBaseUrl}/apps/${configuration.appId}/channels/$channelId/${device.platform}/v1/index.json"
        val cached = state.cachedIndex?.takeIf { it.body.channelId == channelId }
        val headers = cached?.etag?.let { mapOf("If-None-Match" to it) } ?: emptyMap()
        val response = runCatching { httpClient.get(url, headers) }.getOrNull() ?: return cached?.let { IndexFetch.Index(it.body) } ?: IndexFetch.Offline
        return when (response.status) {
            304 -> {
                if (cached == null) return IndexFetch.Offline
                lock.withLock { state.cachedIndex = CachedIndex(cached.etag, clock.now(), cached.body) }
                IndexFetch.Index(cached.body)
            }
            200 -> {
                val index = runCatching { ChannelIndex.fromJson(org.json.JSONObject(String(response.body, Charsets.UTF_8))) }.getOrNull()
                    ?: return IndexFetch.Invalid("The channel index could not be parsed")
                if (!isIndexForDevice(index, channelId)) return IndexFetch.Invalid("The channel index names another app, channel or platform")
                if (cached != null && index.sequence < cached.body.sequence) return IndexFetch.Index(cached.body)
                lock.withLock { state.cachedIndex = CachedIndex(response.header("ETag"), clock.now(), index) }
                IndexFetch.Index(index)
            }
            404 -> {
                if (state.channel == null) return IndexFetch.Absent
                lock.withLock { state.channel = null }
                configuration.channelId?.let { fetchChannelIndex(it) } ?: IndexFetch.NoChannel
            }
            else -> cached?.let { IndexFetch.Index(it.body) } ?: IndexFetch.Offline
        }
    }

    /** The index the device asked for: its app, the channel it fetched by and its platform, so another index at that URL serves nothing. */
    private fun isIndexForDevice(index: ChannelIndex, channelId: String): Boolean =
        index.appId == configuration.appId && index.channelId == channelId && index.platform == device.platform

    /** Live updates are off in a build that embeds no bundle, and in a debug build that has them disabled: every cycle skips with `BUILD_DEBUG`. */
    private val isDisabledInThisBuild: Boolean
        get() = configuration.embeddedBundleManifest == null || (device.isDebugBuild && !configuration.enabledInDebugBuilds)

    private fun deviceInfo() = DeviceInfo(state.attributes, device.binaryBuild, device.binaryVersion, configuration.builtAt, state.currentRelease, state.deviceId, state.failedBundleIds, configuration.fingerprint, device.osVersion, state.reportedAt)

    // Events

    private fun recordChecked(release: IndexRelease, index: ChannelIndex, status: SyncStatus, skip: Skip?) {
        val checked = state.checkedReleaseIds.filter { id -> index.releases.any { it.id == id } }
        if (release.id in checked) {
            state.checkedReleaseIds = checked
            return
        }
        state.checkedReleaseIds = checked + release.id
        enqueueDeviceEvent(DeviceEvent.checked(release.id, status, skip?.reason, skip?.condition))
    }

    private fun enqueueDeviceEvent(event: DeviceEvent) {
        state.unsentEvents = (state.unsentEvents + event).takeLast(200)
        if (isSendingDeviceEvents) eventCountEnqueuedInFlight += 1
        LogEntry.ofDeviceEvent(event, clock.now())?.let(log::record)
    }

    /**
     * One batch to the events endpoint, the outbox as it stands and the report when it changed: a readable 202 takes both,
     * a refusal drops the events and leaves the report unacknowledged, anything else keeps both for the next sync.
     */
    private suspend fun sendDeviceEvents() {
        val request = lock.withLock {
            if (isSendingDeviceEvents || isDisabledInThisBuild) return
            val events = state.unsentEvents
            val report = buildDeviceReport()
            if (events.isEmpty() && report == null) return
            isSendingDeviceEvents = true
            eventCountEnqueuedInFlight = 0
            DeviceEventsRequest(state.deviceId, events, device.platform, report, device.sdkVersion)
        }
        val url = "${configuration.updatesBaseUrl}/v1/apps/${configuration.appId}/events"
        val answer = BatchAnswer.of(runCatching { httpClient.post(url, mapOf("Content-Type" to "application/json"), request.toJson().toString().toByteArray()) }.getOrNull())
        log.record(LogEntry.ofBatch(answer, request.events.size, clock.now()))
        lock.withLock {
            isSendingDeviceEvents = false
            when (answer) {
                is BatchAnswer.Acknowledged -> {
                    dropBatchEvents()
                    state.reportedAt = answer.reportedAt
                    request.report?.let { state.acknowledgedReport = it }
                }
                is BatchAnswer.Refused -> dropBatchEvents()
                is BatchAnswer.Failed -> Unit
            }
        }
    }

    /**
     * The batch's events leave the outbox: what stays is exactly what was enqueued while the batch was on its way, the newest 200 of it,
     * also when the outbox's cap dropped events of the batch meanwhile.
     */
    private fun dropBatchEvents() {
        state.unsentEvents = state.unsentEvents.takeLast(eventCountEnqueuedInFlight)
    }

    /**
     * The facts the server should hold: the report when they differ from the acknowledged ones or the month began, else nothing.
     * A device without a channel id — a runtime name not yet resolved, a build that carries none — reports nothing: a row for it would mislead.
     */
    private fun buildDeviceReport(): DeviceReport? {
        val channel = channel()
        val channelId = channel.id ?: return null
        val report = DeviceReport(state.attributes, device.binaryBuild, device.binaryVersion, channelId, channel.source, configuration.embeddedBundleId, configuration.fingerprint, device.osVersion, state.currentRelease?.id)
        val reportedAt = state.reportedAt
        val isAcknowledged = report == state.acknowledgedReport && reportedAt != null && resolveMonth(reportedAt) == resolveMonth(clock.now())
        return if (isAcknowledged) null else report
    }

    private fun resolveMonth(epochMillis: Long) = Iso8601.format(epochMillis).substring(0, 7)

    companion object {
        /** What a build without a channel answers, without a request: it can never update until the app sets a channel at runtime. */
        const val MISSING_CHANNEL_MESSAGE = "The build carries no channel: it was built without a token or offline, so the channel's name was never resolved. Build it with a token to receive updates."
    }
}
