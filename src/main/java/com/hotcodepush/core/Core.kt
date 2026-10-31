package com.hotcodepush.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** The update state machine every SDK on Android shares: the releases, the readiness gate and the update cycle. */
class Core(
    val configuration: Configuration,
    private val device: DeviceFacts,
    store: KeyValueStore,
    private val files: FileStore,
    private val embedded: EmbeddedBundle,
    private val http: HttpClient,
    private val loader: BundleLoader,
    private val listener: CoreListener,
    private val scheduler: Scheduler,
    private val clock: Clock,
    private val scope: CoroutineScope,
    temporaryDirectory: File,
    private val processState: ProcessState = ActivityManagerProcessState,
) {
    private enum class Stage { CHECK, DOWNLOAD, SYNC }

    private sealed class ChannelResolution {
        data class Id(val id: String) : ChannelResolution()
        object Offline : ChannelResolution()
        object Unknown : ChannelResolution()

        // The build carries no channel and the app set none at runtime.
        object Missing : ChannelResolution()
        data class Invalid(val message: String) : ChannelResolution()
    }

    private class QueuedRestart(val isAskedByApp: Boolean, val restart: () -> Unit)

    private class RunningCycle(val stage: Stage, val result: Deferred<SyncResult>)

    private val state = StateStore(store)
    private val downloader = Downloader(configuration, device.platform, files, embedded, http, temporaryDirectory)
    private val lock = Mutex()

    // Keeps the file cleanup apart from a cycle, since a download writes files no kept release lists yet.
    private val cycleLock = Mutex()
    private val log = SessionLog()

    // A task the core runs on its own logs its failure and never crashes the app.
    private val failureHandler = CoroutineExceptionHandler { _, failure -> log.record(LogEntry(clock.now(), SyncStatus.FAILED.wire, "a task of the core stopped: $failure")) }

    private var readyTimer: ScheduledTask? = null
    private var isReadyTimerPaused = false
    private var intervalTimer: ScheduledTask? = null
    private var runningCycle: RunningCycle? = null
    private var isRestartAllowed = true
    private var queuedRestart: QueuedRestart? = null

    // The app is up in this run: it rendered, called notifyReady() or timed out since the start or the last reload.
    private var hasStartSettled = false

    // A readiness signal reported before the latest bundle load belongs to the replaced bundle.
    @Volatile private var bundleLoadCount = 0

    private var isStartSyncPending = false
    private var isSendingDeviceEvents = false
    private var eventCountEnqueuedInFlight = 0

    // Also set at the start when the system started the process without a foreground activity.
    private var backgroundedAt: Long? = null
    private var resolvedChannelName: Pair<String, String>? = null
    private var hasAnnouncedRollback = false

    // The release a switch replaced, null for the embedded bundle, kept until notifyReady() reads it.
    private var switchedFromRelease: Release? = null

    /**
     * Builds the core with the Android defaults: state in the app's default preferences, files in its private files directory,
     * temporary download files in its cache directory, OkHttp and the wall clock.
     */
    constructor(
        context: Context,
        configuration: Configuration,
        device: DeviceFacts,
        embedded: EmbeddedBundle,
        loader: BundleLoader,
        listener: CoreListener,
        scope: CoroutineScope,
        scheduler: Scheduler = HandlerScheduler(),
    ) : this(
        configuration = configuration,
        device = device,
        store = SharedPreferencesStore(context),
        files = FileStore(FileStore.defaultRootDirectory(context)),
        embedded = embedded,
        http = OkHttpClientAdapter(),
        loader = loader,
        listener = listener,
        scheduler = scheduler,
        clock = Clock { System.currentTimeMillis() },
        scope = scope,
        temporaryDirectory = File(context.cacheDir, "hotcodepush"),
    )

    /**
     * Starts a run and returns the bundle the host serves, `null` for the embedded one.
     *
     * It drops a stale store, rolls back a crash of the previous run, switches to a waiting release and arms the readiness gate.
     * It never waits for the network: the check and the file cleanup run after it returns. Any failure, an unreadable store
     * included, is logged and answers the embedded bundle.
     * A reload the core did not perform keeps the running bundle. A `next_start` release applies at the next start or through
     * the core's own apply.
     *
     * Pass `isHeadless` when no screen will render in this run, such as a process started without an activity or an Expo
     * background task. Such a start applies no waiting release, arms no gate and loads no bundle; the persisted bundle stays.
     * A process the system started in the background without that flag counts as backgrounded: its readiness timer waits for
     * the first resume.
     */
    suspend fun handleAppStart(isHeadless: Boolean = false): String? {
        val bundleId = lock.withLock {
            try {
                applyStartRule(isHeadless)
            } catch (failure: Throwable) {
                runEmbeddedBundleAfter(failure)
            }
            runLogged("the start") { beginRun(isHeadless) }
            resolveStartBundleId()
        }
        launchTask(::deleteUnusedFiles)
        return bundleId
    }

    /**
     * Runs `handleAppStart()` for a host that picks its bundle in synchronous code, waiting at most `START_TIMEOUT` seconds.
     * Without an answer in time it returns the embedded bundle, and the start reloads the host into the right bundle once it runs.
     */
    fun handleAppStartBlocking(isHeadless: Boolean = false): String? = handleAppStartBlocking(isHeadless, START_TIMEOUT)

    internal fun handleAppStartBlocking(isHeadless: Boolean, timeout: Double): String? {
        val start = scope.async { handleAppStart(isHeadless) }
        return runBlocking { withTimeoutOrNull((timeout * 1000).toLong()) { start.await() } }
    }

    // After this, the current release is confirmed unless it switched. A headless start never switches, so it arms no gate.
    private fun applyStartRule(isHeadless: Boolean) {
        if (state.pendingRollbackEvent == null) state.lastRollback = null
        if (state.lastBuiltAt != configuration.builtAt || hasReleaseWithoutManifest()) dropStoredReleases()
        if (isCurrentReleaseUnconfirmed()) rollbackCurrentRelease(RollbackReason.APP_CRASHED, null)
        discardNextReleaseThatLeftTheIndex()
        val next = state.nextRelease
        if (!isHeadless && next != null && shouldSwitchAtStart(next)) switchToNextRelease()
    }

    // Dropping the stored releases, as a new binary does, keeps the state in line with the embedded bundle that now runs.
    private fun runEmbeddedBundleAfter(failure: Throwable) {
        log.record(LogEntry(clock.now(), SyncStatus.FAILED.wire, "the start runs the embedded bundle after $failure"))
        runLogged("forgetting the stored releases", ::dropStoredReleases)
    }

    private fun beginRun(isHeadless: Boolean) {
        if (!processState.isInForeground()) backgroundedAt = clock.now()
        if (!isHeadless) loadBundle()
        if (!hasAnnouncedRollback) announceRollback()
        if (isCurrentReleaseUnconfirmed()) {
            startReadyTimer()
            isStartSyncPending = true
        } else if (isCheckAutomatic) {
            startAutomaticCycle(SyncTrigger.START)
        }
    }

    private fun resolveStartBundleId(): String? = runCatching { resolveServedBundleId(state.currentRelease) }.getOrNull()

    private fun runLogged(task: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            log.record(LogEntry(clock.now(), SyncStatus.FAILED.wire, "$task failed: $failure"))
        }
    }

    // The moment stored at download decides, so a per-call strategy holds for that release.
    // A held `immediate` apply switches too, and a bundle the host already serves is adopted.
    private fun shouldSwitchAtStart(next: NextRelease): Boolean =
        loader.servedBundleId() == resolveServedBundleId(next.release) || next.applyStrategy == ApplyStrategy.NEXT_START || next.applyStrategy == ApplyStrategy.IMMEDIATE

    private fun shouldApplyAtResume(next: NextRelease): Boolean = next.applyStrategy == ApplyStrategy.NEXT_RESUME

    /**
     * Reports the first render of the run or of a reload.
     * It confirms the current release when `readySignal` is `render` and settles the start on every setting. A render reported
     * before the core's latest bundle load belongs to the replaced bundle and does nothing.
     */
    suspend fun handleRendered() {
        val bundleLoadCountAtSignal = bundleLoadCount
        lock.withLock {
            if (hasLoadedBundleSince(bundleLoadCountAtSignal)) return
            if (configuration.readySignal == ReadySignal.RENDER) confirmCurrentRelease()
            settleStart()
        }
    }

    /**
     * Reports that the app is ready: confirms the current release, settles the start and tells whether a rollback came before.
     * `previousRelease` is the release before this start when a switch or a rollback changed it, reported once. A call made
     * before the core's latest bundle load belongs to the replaced bundle and does nothing.
     */
    suspend fun notifyReady(): NotifyReadyResult {
        val bundleLoadCountAtSignal = bundleLoadCount
        return lock.withLock {
            if (hasLoadedBundleSince(bundleLoadCountAtSignal)) return NotifyReadyResult(state.currentRelease, null, false, null)
            confirmCurrentRelease()
            val rollback = state.lastRollback
            state.lastRollback = null
            val previousRelease = rollback?.fromRelease ?: switchedFromRelease
            switchedFromRelease = null
            val result = NotifyReadyResult(state.currentRelease, previousRelease, rollback != null, rollback?.reason)
            settleStart()
            result
        }
    }

    /**
     * Handles the move to the background: stops the interval and readiness timers and records the moment for `next_resume`.
     * Interval checks belong to the foreground, and an app that cannot render in the background proves nothing.
     */
    suspend fun handleAppPause() = lock.withLock {
        backgroundedAt = clock.now()
        intervalTimer?.cancel()
        intervalTimer = null
        pauseReadyTimer()
    }

    /**
     * Handles the return to the foreground.
     * It restarts a paused readiness window in full, applies a `next_resume` release after enough time in the background, and
     * otherwise checks once the interval has passed.
     */
    suspend fun handleAppResume() = lock.withLock {
        val backgroundDuration = backgroundedAt?.let { (clock.now() - it) / 1000.0 }
        backgroundedAt = null
        resumeReadyTimer()
        discardNextReleaseThatLeftTheIndex()
        val next = state.nextRelease
        if (backgroundDuration != null && next != null && shouldApplyAtResume(next) && backgroundDuration >= configuration.nextResumeMinimumBackgroundSeconds) {
            restartIntoNextRelease()
            return
        }
        if (!isCheckAutomatic) return
        val elapsedSeconds = state.lastSyncAt?.let { (clock.now() - it) / 1000.0 }
        if (elapsedSeconds == null || elapsedSeconds >= configuration.checkIntervalSeconds) {
            startAutomaticCycle(SyncTrigger.RESUME)
            return
        }
        scheduleIntervalSync(configuration.checkIntervalSeconds - elapsedSeconds)
    }

    // A device without a channel starts no automatic cycle, since it could only fail.
    private fun startAutomaticCycle(trigger: SyncTrigger) {
        if (!hasChannel) return
        launchTask { sync(trigger) }
    }

    private fun launchTask(task: suspend () -> Unit) {
        scope.launch(failureHandler) { task() }
    }

    /**
     * Runs one update cycle under the configured strategies, stopping at a `manual` gate.
     * A second call while a cycle runs joins it. Throws the plain error, fetching nothing, when the channel id in effect is
     * not a UUID.
     */
    suspend fun sync(trigger: SyncTrigger, options: SyncOptions = SyncOptions()): SyncResult {
        verifyChannelId()
        return runCycle(trigger, Stage.SYNC, options)
    }

    /**
     * Runs `sync()` with the download gate closed: fetches and evaluates, but downloads and applies nothing.
     * A release that carries the running bundle is still adopted, as from every stage, the target of a revoked release included.
     */
    suspend fun checkForUpdate(): SyncResult {
        verifyChannelId()
        return runCycle(SyncTrigger.MANUAL, Stage.CHECK, SyncOptions(downloadStrategy = DownloadStrategy.MANUAL))
    }

    /**
     * Runs `sync()` with the download gate open, whatever `downloadStrategy` says.
     * It checks the index again with a conditional GET, so nothing downloads from stale state, then downloads, verifies and
     * applies the update under the apply strategies.
     */
    suspend fun downloadUpdate(options: DownloadUpdateOptions = DownloadUpdateOptions()): SyncResult {
        verifyChannelId()
        return runCycle(SyncTrigger.MANUAL, Stage.DOWNLOAD, SyncOptions(options.applyStrategy, DownloadStrategy.AUTO, options.mandatoryApplyStrategy))
    }

    private suspend fun runCycle(trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult {
        while (true) {
            val cycle = lock.withLock { runningCycle ?: startCycle(trigger, stage, options) }
            if (cycle.stage == stage) return cycle.result.await()
            cycle.result.join()
        }
    }

    private fun startCycle(trigger: SyncTrigger, stage: Stage, options: SyncOptions): RunningCycle {
        val result = scope.async(start = CoroutineStart.LAZY) {
            try {
                cycleLock.withLock { performCycle(trigger, stage, options) }
            } finally {
                withContext(NonCancellable) { lock.withLock { runningCycle = null } }
            }
        }
        return RunningCycle(stage, result).also { runningCycle = it }
    }

    /** Applies the downloaded update and reloads the app, at once or, before the app is up in this run, once it is. */
    suspend fun applyUpdate(): ApplyUpdateResult = lock.withLock {
        discardNextReleaseThatLeftTheIndex()
        val next = state.nextRelease ?: return ApplyUpdateResult(ApplyStatus.NOTHING_TO_APPLY, state.currentRelease)
        restartThroughGate(isAskedByApp = true, ::applyNextRelease)
        ApplyUpdateResult(ApplyStatus.APPLIED, next.release)
    }

    private suspend fun performCycle(trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult {
        val result = resolveCycleSafely(trigger, stage, options)
        lock.withLock {
            state.lastCheck = LastCheck(clock.now(), trigger, result)
            if (stage == Stage.SYNC) {
                state.lastSyncAt = clock.now()
                scheduleIntervalSync(configuration.checkIntervalSeconds)
            }
        }
        log.record(LogEntry.ofCycle(result, trigger, clock.now()))
        if (result.status == SyncStatus.FAILED) {
            val reason = result.reason?.let { wire -> FailedReason.entries.firstOrNull { it.wire == wire } }
            if (reason != null) listener.updateFailed(UpdateFailedEvent(result.release, reason, result.message ?: "", trigger))
        }
        launchTask { sendDeviceEvents() }
        return result
    }

    private suspend fun resolveCycleSafely(trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult = try {
        resolveCycle(trigger, stage, options)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        SyncResult.failed(null, FailedReason.INDEX_INVALID, "The cycle stopped on $failure")
    }

    private suspend fun resolveCycle(trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult {
        val current = state.currentRelease
        if (isDisabledInThisBuild) return SyncResult.skipped(null, SkippedReason.BUILD_DEBUG)
        val channelId = when (val resolution = resolveChannelId()) {
            is ChannelResolution.Id -> resolution.id
            ChannelResolution.Offline -> return SyncResult.failed(null, FailedReason.DEVICE_OFFLINE, "The channels index could not be fetched to resolve the channel name")
            ChannelResolution.Unknown -> return SyncResult.failed(null, FailedReason.CHANNEL_UNKNOWN, "The channel set at runtime is not in the app's channels index")
            ChannelResolution.Missing -> return SyncResult.failed(null, FailedReason.CHANNEL_UNKNOWN, MISSING_CHANNEL_MESSAGE)
            is ChannelResolution.Invalid -> return SyncResult.failed(null, FailedReason.INDEX_INVALID, resolution.message)
        }
        val index = when (val fetch = fetchChannelIndex(channelId)) {
            is IndexFetch.Index -> fetch.index
            IndexFetch.Offline -> return SyncResult.failed(null, FailedReason.DEVICE_OFFLINE, "The channel index could not be fetched and no cached copy exists")
            is IndexFetch.Invalid -> return SyncResult.failed(null, FailedReason.INDEX_INVALID, fetch.message)
            IndexFetch.Absent -> return SyncResult.upToDate(current)
            IndexFetch.NoChannel -> return SyncResult.failed(null, FailedReason.CHANNEL_UNKNOWN, MISSING_CHANNEL_MESSAGE)
        }
        return when (val evaluation = Evaluator.evaluate(index, deviceInfo())) {
            is Evaluation.UpToDate -> SyncResult.upToDate(current)
            is Evaluation.Available -> {
                lock.withLock { recordChecked(evaluation.release, index, SyncStatus.AVAILABLE) }
                update(evaluation.release, evaluation.isMandatory, trigger, stage, options)
            }
            is Evaluation.Skipped -> when {
                evaluation.reason != SkippedReason.RELEASE_REVOKED -> {
                    evaluation.release?.let { release -> lock.withLock { recordChecked(release, index, SyncStatus.SKIPPED, evaluation.reason, evaluation.condition) } }
                    SyncResult.skipped(evaluation.release?.release, evaluation.reason, evaluation.condition)
                }
                (evaluation.release?.bundleId ?: configuration.embeddedBundleId) == resolveRunningBundleId() -> {
                    lock.withLock { adoptInPlace(evaluation.release?.release) }
                    SyncResult.skipped(evaluation.release?.release, SkippedReason.RELEASE_REVOKED)
                }
                stage == Stage.CHECK -> SyncResult.skipped(evaluation.release?.release, SkippedReason.RELEASE_REVOKED)
                evaluation.release == null -> {
                    lock.withLock { revertToEmbedded() }
                    SyncResult.skipped(null, SkippedReason.RELEASE_REVOKED)
                }
                else -> {
                    val outcome = downloadAndApply(evaluation.release, true, ApplyStrategy.IMMEDIATE, trigger)
                    if (outcome.status == SyncStatus.FAILED) outcome else SyncResult.skipped(evaluation.release.release, SkippedReason.RELEASE_REVOKED)
                }
            }
        }
    }

    private suspend fun update(target: IndexRelease, isMandatory: Boolean, trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult {
        val release = resolveRelease(target, isMandatory)
        // An adopted release already runs without a reload, so the cycle answers `up_to_date`.
        if (target.bundleId == resolveRunningBundleId()) {
            lock.withLock { adoptInPlace(release) }
            return SyncResult.upToDate(release)
        }
        // The call's strategy wins over the downloaded release's, so an automatic cycle never overrides the app's choice.
        val requestedApplyStrategy = resolveRequestedApplyStrategy(isMandatory, options)
        val downloaded = readDownloadedRelease(target)
        if (downloaded != null) {
            if (stage == Stage.CHECK) return SyncResult.available(release, target.notes, resolveDownloadSizeBytes(target))
            return lock.withLock { applyDownloaded(NextRelease(release, requestedApplyStrategy ?: downloaded.applyStrategy), target.notes) }
        }
        val applyStrategy = requestedApplyStrategy ?: resolveConfiguredApplyStrategy(isMandatory)
        listener.updateAvailable(UpdateAvailableEvent(release, target.notes, resolveDownloadSizeBytes(target), trigger))
        return when (resolveDownloadStrategy(target, stage, options)) {
            DownloadStrategy.MANUAL -> SyncResult.available(release, target.notes, resolveDownloadSizeBytes(target))
            DownloadStrategy.UNMETERED -> if (loader.isConnectionMetered()) SyncResult.skipped(release, SkippedReason.CONNECTION_METERED) else downloadAndApply(target, isMandatory, applyStrategy, trigger)
            DownloadStrategy.AUTO -> downloadAndApply(target, isMandatory, applyStrategy, trigger)
        }
    }

    // The binary already carries the embedded bundle, so a release of it downloads nothing.
    private fun resolveDownloadSizeBytes(target: IndexRelease): Long =
        if (target.bundleId == configuration.embeddedBundleId) 0 else target.sizeBytes

    // A release of the embedded bundle downloads nothing, so it passes every gate but the check's.
    private fun resolveDownloadStrategy(target: IndexRelease, stage: Stage, options: SyncOptions): DownloadStrategy =
        if (target.bundleId == configuration.embeddedBundleId && stage != Stage.CHECK) DownloadStrategy.AUTO else options.downloadStrategy ?: configuration.downloadStrategy

    private fun resolveRelease(target: IndexRelease, isMandatory: Boolean) = Release(target.id, target.number, target.bundleId, target.bundleVersion, isMandatory)

    private fun resolveRequestedApplyStrategy(isMandatory: Boolean, options: SyncOptions): ApplyStrategy? =
        if (isMandatory) options.mandatoryApplyStrategy?.toApplyStrategy() else options.applyStrategy

    private fun resolveConfiguredApplyStrategy(isMandatory: Boolean): ApplyStrategy =
        if (isMandatory) configuration.mandatoryApplyStrategy.toApplyStrategy() else configuration.applyStrategy

    private fun readDownloadedRelease(target: IndexRelease): NextRelease? {
        val next = state.nextRelease ?: return null
        if (next.release.bundleId != target.bundleId) return null
        val manifest = readBundleManifest(next.release.bundleId) ?: return null
        return if (files.isComplete(manifest, embedded)) next else null
    }

    private suspend fun downloadAndApply(target: IndexRelease, isMandatory: Boolean, applyStrategy: ApplyStrategy, trigger: SyncTrigger): SyncResult {
        val release = resolveRelease(target, isMandatory)
        if (target.bundleId != configuration.embeddedBundleId) {
            val failure = downloadBundle(target, release)
            if (failure != null) return failure
        }
        if (applyStrategy != ApplyStrategy.IMMEDIATE) listener.updateDownloaded(UpdateDownloadedEvent(release, applyStrategy, trigger))
        return lock.withLock { applyDownloaded(NextRelease(release, applyStrategy), target.notes) }
    }

    private suspend fun downloadBundle(target: IndexRelease, release: Release): SyncResult? {
        try {
            val outcome = downloader.downloadRelease(target, resolveRunningBundleId()) { progressBytes, downloadSizeBytes -> listener.downloadProgress(DownloadProgressEvent(downloadSizeBytes, progressBytes, target.id)) }
            BundleProjection.project(outcome.manifest, files, embedded, loader.projectionDirectory(target.bundleId))
            lock.withLock { enqueueDeviceEvent(DeviceEvent.downloaded(target.id, target.bundleId, outcome.bytes, outcome.packKind)) }
        } catch (failure: DownloadFailure) {
            lock.withLock { enqueueDeviceEvent(DeviceEvent.failed(target.id, failure.reason.wire)) }
            return SyncResult.failed(release, failure.reason, failure.message ?: "")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // An OutOfMemoryError on a large file fails the download, not the process.
            lock.withLock { enqueueDeviceEvent(DeviceEvent.failed(target.id, FailedReason.DOWNLOAD_FAILED.wire)) }
            return SyncResult.failed(release, FailedReason.DOWNLOAD_FAILED, failure.message ?: failure.toString())
        }
        return null
    }

    private fun applyDownloaded(next: NextRelease, notes: String?): SyncResult {
        setNextRelease(next)
        when (next.applyStrategy) {
            ApplyStrategy.IMMEDIATE -> restartIntoNextRelease()
            ApplyStrategy.NEXT_START -> persistServedBundle(next.release)
            ApplyStrategy.NEXT_RESUME, ApplyStrategy.MANUAL -> Unit
        }
        return if (next.applyStrategy == ApplyStrategy.IMMEDIATE) SyncResult.applied(next.release, notes) else SyncResult.downloaded(next.release, notes, next.applyStrategy)
    }

    /**
     * Rolls the running release back and reloads at once, even before the app is up.
     * `detail` is the app's own cause, sent with the failure event. Does nothing while the embedded bundle runs, also through
     * a release of it.
     */
    suspend fun rollbackUpdate(detail: String?) = lock.withLock {
        if (detail != null) AttributeRules.validate(detail)
        if (resolveServedBundleId(state.currentRelease) == null) return
        rollbackCurrentRelease(RollbackReason.APP_REQUESTED, detail)
        reloadApp()
    }

    /**
     * Returns to the embedded bundle and deletes every downloaded update and the failed list; the device identity stays.
     * Before the app is up in this run, the whole call waits until it is, the deletion included.
     */
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
            persistServedBundle(null)
            reloadApp()
        }
    }

    suspend fun setRestartAllowed(isAllowed: Boolean) = lock.withLock {
        isRestartAllowed = isAllowed
        runQueuedRestart()
    }

    /** Returns everything the debug screen shows: the device, the configuration, the state and this session's log. */
    fun debugSnapshot(): DebugSnapshot = DebugSnapshot(clock.now(), deviceResult(), configuration, device.isDebugBuild, getState(), log.entries())

    fun getState(): StateResult {
        val cached = state.cachedIndex
        return StateResult(
            currentRelease = state.currentRelease,
            nextRelease = state.nextRelease?.release,
            fallbackRelease = state.fallbackRelease,
            embeddedBundleId = configuration.embeddedBundleId,
            lastCheck = state.lastCheck,
            indexSequence = cached?.body?.sequence,
            indexFetchedAt = cached?.fetchedAt,
            failedBundleIds = state.failedBundleIds,
            reportedAt = state.reportedAt,
        )
    }

    fun channel(): ChannelResult = when (val choice = state.channel) {
        is ChannelChoice.Id -> ChannelResult(choice.id, null, ChannelSource.RUNTIME)
        is ChannelChoice.Name -> ChannelResult(resolvedChannelName?.takeIf { it.first == choice.name }?.second, choice.name, ChannelSource.RUNTIME)
        null -> ChannelResult(configuration.channelId, null, ChannelSource.CONFIG)
    }

    /**
     * Sets the channel at runtime, or clears the choice with `null`.
     * A channel id is part of the index URL, so one that is not a UUID throws the plain error and nothing is stored.
     */
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

    // A restored phone brings back the store's keys without the files, so a release may name a tree that is not there.
    private fun hasReleaseWithoutManifest(): Boolean = listOfNotNull(state.currentRelease, state.nextRelease?.release).any { readBundleManifest(it.bundleId) == null }

    private fun readBundleManifest(bundleId: String): BundleManifest? =
        if (bundleId == configuration.embeddedBundleId) configuration.embeddedBundleManifest else files.readManifest(bundleId)

    // A new binary brings a new floor and a restored phone has no files, so the embedded bundle runs.
    // The kept index and its ETag go too, so the first check fetches unconditionally and a frozen sequence heals.
    private fun dropStoredReleases() {
        state.cachedIndex = null
        state.currentRelease = null
        state.nextRelease = null
        state.fallbackRelease = null
        state.failedBundleIds = emptyList()
        state.lastRollback = null
        state.pendingRollbackEvent = null
        state.lastBuiltAt = configuration.builtAt
        persistServedBundle(null)
    }

    private fun setNextRelease(next: NextRelease) {
        state.nextRelease = next
    }

    private fun discardNextReleaseThatLeftTheIndex() {
        val next = state.nextRelease ?: return
        val index = state.cachedIndex?.body ?: return
        if (!hasLeftIndex(next.release, index)) return
        state.nextRelease = null
        persistServedBundle(state.currentRelease)
    }

    private fun hasLeftIndex(release: Release, index: ChannelIndex): Boolean = release.id in index.revokedReleaseIds || index.releases.none { it.id == release.id }

    private fun switchToNextRelease() {
        val next = state.nextRelease?.release ?: return
        switchedFromRelease = state.currentRelease
        state.currentRelease = next
        state.nextRelease = null
        persistServedBundle(next)
        enqueueDeviceEvent(DeviceEvent.applied(next.id))
        // The embedded bundle is never gated, so no readiness timer arms for it and no rollback blocklists it.
        if (next.bundleId == configuration.embeddedBundleId) confirmCurrentRelease()
    }

    private fun loadBundle() {
        if (loader.servedBundleId() == resolveServedBundleId(state.currentRelease)) return
        loadServedBundle(state.currentRelease)
        // A render handled before the start began settled the replaced bundle.
        unsettleStart()
    }

    private fun reloadApp() {
        loadServedBundle(state.currentRelease)
        gateReloadedApp()
    }

    private fun loadServedBundle(release: Release?) {
        loader.loadServedBundle(resolveServedBundleId(release))
        bundleLoadCount += 1
    }

    private fun persistServedBundle(release: Release?) {
        loader.persistServedBundle(resolveServedBundleId(release))
    }

    // A release of the embedded bundle has no tree of its own: the binary serves it.
    private fun resolveServedBundleId(release: Release?): String? = release?.bundleId?.takeIf { it != configuration.embeddedBundleId }

    // Null only in a build that names no embedded bundle.
    private fun resolveRunningBundleId(): String? = state.currentRelease?.bundleId ?: configuration.embeddedBundleId

    private fun hasLoadedBundleSince(bundleLoadCountAtSignal: Int): Boolean = bundleLoadCount != bundleLoadCountAtSignal

    private fun gateReloadedApp() {
        unsettleStart()
        announceRollback()
        if (isCurrentReleaseUnconfirmed()) startReadyTimer()
    }

    private fun unsettleStart() {
        hasStartSettled = false
        // The reload runs what the state says, so a held restart is moot.
        queuedRestart = null
    }

    // Persisting the next bundle first lets the next start switch if this run never does.
    private fun restartIntoNextRelease() {
        state.nextRelease?.let { persistServedBundle(it.release) }
        restartThroughGate(isAskedByApp = false, ::applyNextRelease)
    }

    private fun applyNextRelease() {
        discardNextReleaseThatLeftTheIndex()
        if (state.nextRelease == null) {
            if (loader.servedBundleId() != resolveServedBundleId(state.currentRelease)) reloadApp()
            return
        }
        switchToNextRelease()
        reloadApp()
    }

    // One restart is held at most: the app's replaces a held one, the core's yields to it.
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

    private fun settleStart() {
        hasStartSettled = true
        if (hasAnnouncedRollback) {
            hasAnnouncedRollback = false
            state.pendingRollbackEvent = null
        }
        runQueuedRestart()
    }

    // The notice stays stored until the app is up, so JavaScript that listens late still receives it.
    private fun announceRollback() {
        val event = state.pendingRollbackEvent ?: return
        hasAnnouncedRollback = true
        listener.updateRolledBack(event)
    }

    private fun adoptInPlace(release: Release?) {
        val wasConfirmed = !isCurrentReleaseUnconfirmed()
        state.currentRelease = release
        // A release waiting to apply is older, so no start may switch to it.
        state.nextRelease = null
        if (wasConfirmed) state.fallbackRelease = release
        persistServedBundle(release)
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
            if (isCheckAutomatic) startAutomaticCycle(SyncTrigger.START)
        }
    }

    // The caller performs the reload.
    private fun rollbackCurrentRelease(reason: RollbackReason, detail: String?) {
        val current = state.currentRelease ?: return
        stopReadyTimer()
        state.failedBundleIds = (state.failedBundleIds + current.bundleId).distinct().sorted()
        val fallback = resolveFallbackRelease()
        state.currentRelease = fallback
        state.nextRelease = null
        val rollback = UpdateRolledBackEvent(current, fallback, reason)
        state.lastRollback = rollback
        state.pendingRollbackEvent = rollback
        hasAnnouncedRollback = false
        enqueueDeviceEvent(DeviceEvent.failed(current.id, reason.wire, detail))
        enqueueDeviceEvent(DeviceEvent.rolledBack(current.id, fallback?.id))
        persistServedBundle(fallback)
    }

    private fun resolveFallbackRelease(): Release? {
        val fallback = state.fallbackRelease ?: return null
        if (fallback.bundleId in state.failedBundleIds) return null
        if (state.cachedIndex?.body?.revokedReleaseIds?.contains(fallback.id) == true) return null
        val manifest = readBundleManifest(fallback.bundleId) ?: return null
        return if (files.isComplete(manifest, embedded)) fallback else null
    }

    private fun revertToEmbedded() {
        stopReadyTimer()
        state.currentRelease = null
        state.nextRelease = null
        persistServedBundle(null)
        restartThroughGate(isAskedByApp = false) { reloadApp() }
    }

    // The readiness window runs only in the foreground, so a gate armed in the background waits for the resume.
    private fun startReadyTimer() {
        stopReadyTimer()
        if (backgroundedAt != null) {
            isReadyTimerPaused = true
            return
        }
        readyTimer = scheduler.schedule(configuration.readyTimeoutSeconds) { launchTask { handleReadyTimeout() } }
    }

    private fun stopReadyTimer() {
        readyTimer?.cancel()
        readyTimer = null
        isReadyTimerPaused = false
    }

    private fun pauseReadyTimer() {
        if (readyTimer == null) return
        stopReadyTimer()
        isReadyTimerPaused = true
    }

    // The full window starts again, and the time already spent counts for nothing.
    private fun resumeReadyTimer() {
        if (isReadyTimerPaused) startReadyTimer()
    }

    internal suspend fun handleReadyTimeout() = lock.withLock {
        // A timeout that fires as the timer stops, paused or confirmed, is ignored.
        if (readyTimer == null || !isCurrentReleaseUnconfirmed()) return
        // Settling the start lets the rollback reload unless the app holds restarts.
        hasStartSettled = true
        rollbackCurrentRelease(RollbackReason.READINESS_TIMED_OUT, null)
        restartThroughGate(isAskedByApp = false, ::reloadApp)
    }

    private fun scheduleIntervalSync(afterSeconds: Double) {
        intervalTimer?.cancel()
        if (!isCheckAutomatic) return
        intervalTimer = scheduler.schedule(afterSeconds) { launchTask { lock.withLock { startAutomaticCycle(SyncTrigger.INTERVAL) } } }
    }

    // The projections go before the files, since their links hold the bytes.
    private suspend fun deleteUnusedFiles() = cycleLock.withLock {
        val kept = lock.withLock { listOfNotNull(state.currentRelease, state.nextRelease?.release, state.fallbackRelease).map { it.bundleId }.toSet() }
        files.bundleIds().filter { it !in kept }.forEach(loader::deleteProjection)
        files.deleteUnusedFiles(kept)
    }

    private sealed class IndexFetch {
        data class Index(val index: ChannelIndex) : IndexFetch()
        object Offline : IndexFetch()
        data class Invalid(val message: String) : IndexFetch()
        object Absent : IndexFetch()

        // The runtime channel serves no index and the build carries none to fall back to.
        object NoChannel : IndexFetch()
    }

    private suspend fun resolveChannelId(): ChannelResolution = when (val choice = state.channel) {
        null -> configuration.channelId?.let { ChannelResolution.Id(it) } ?: ChannelResolution.Missing
        is ChannelChoice.Id -> ChannelResolution.Id(choice.id)
        is ChannelChoice.Name -> {
            val resolved = resolvedChannelName
            if (resolved != null && resolved.first == choice.name) {
                ChannelResolution.Id(resolved.second)
            } else {
                val url = "${configuration.filesBaseUrl}/apps/${configuration.appId}/channels/v1/index.json"
                val response = runCatching { http.get(url, emptyMap()) }.getOrNull()
                // A failed fetch means offline, never an unknown name.
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

    private fun verifyChannelId() {
        channel().id?.let(::verifyChannelId)
    }

    private fun verifyChannelId(id: String) {
        if (!WireRule.UUID.accepts(id)) throw PlainException("A channel id is a UUID: $id")
    }

    private val isCheckAutomatic: Boolean
        get() = configuration.checkStrategy == CheckStrategy.AUTO

    private val hasChannel: Boolean
        get() = state.channel != null || configuration.channelId != null

    private suspend fun fetchChannelIndex(channelId: String): IndexFetch {
        val url = "${configuration.filesBaseUrl}/apps/${configuration.appId}/channels/$channelId/${device.platform}/v1/index.json"
        val cached = state.cachedIndex?.takeIf { it.body.channelId == channelId }
        val headers = cached?.etag?.let { mapOf("If-None-Match" to it) } ?: emptyMap()
        val response = runCatching { http.get(url, headers) }.getOrNull() ?: return cached?.let { IndexFetch.Index(it.body) } ?: IndexFetch.Offline
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
                if (cached != null && isBehindCachedIndex(index, cached)) return IndexFetch.Index(cached.body)
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

    // An older kept index counts as absent, so a forged far-future sequence freezes the device for a day at most.
    private fun isBehindCachedIndex(index: ChannelIndex, cached: CachedIndex): Boolean =
        index.sequence < cached.body.sequence && clock.now() - cached.fetchedAt < (CACHED_INDEX_MAX_AGE * 1000).toLong()

    private fun isIndexForDevice(index: ChannelIndex, channelId: String): Boolean =
        index.appId == configuration.appId && index.channelId == channelId && index.platform == device.platform

    // A React Native or Expo debug build bundles no JavaScript, so it carries no embedded manifest.
    private val isDisabledInThisBuild: Boolean
        get() = configuration.embeddedBundleManifest == null || (device.isDebugBuild && !configuration.isEnabledInDebugBuilds)

    private fun deviceInfo() = DeviceInfo(state.attributes, device.binaryBuild, device.binaryVersion, configuration.builtAt, state.currentRelease, state.deviceId, state.failedBundleIds, configuration.fingerprint, device.osVersion, state.reportedAt)

    private fun recordChecked(release: IndexRelease, index: ChannelIndex, status: SyncStatus, reason: SkippedReason? = null, condition: ConditionType? = null) {
        val checked = state.checkedReleaseIds.filter { id -> index.releases.any { it.id == id } }
        if (release.id in checked) {
            state.checkedReleaseIds = checked
            return
        }
        state.checkedReleaseIds = checked + release.id
        enqueueDeviceEvent(DeviceEvent.checked(release.id, status, reason, condition))
    }

    private fun enqueueDeviceEvent(event: DeviceEvent) {
        state.unsentEvents = (state.unsentEvents + event).takeLast(DeviceEventsRequest.MAXIMUM_EVENT_COUNT)
        if (isSendingDeviceEvents) eventCountEnqueuedInFlight += 1
        LogEntry.ofDeviceEvent(event, clock.now())?.let(log::record)
    }

    private suspend fun sendDeviceEvents() {
        val request = lock.withLock {
            if (isSendingDeviceEvents || isDisabledInThisBuild) return
            val events = state.unsentEvents
            var report = buildDeviceReport()
            if (report?.isReadable == false) {
                log.record(LogEntry(clock.now(), REPORT_UNREADABLE, "the device report stays unsent: a fact or an attribute is one the events endpoint refuses"))
                report = null
            }
            if (events.isEmpty() && report == null) return
            val request = DeviceEventsRequest(state.deviceId, events, device.platform, report, device.sdkVersion)
            if (!request.isReadable) {
                log.record(LogEntry(clock.now(), REPORT_UNREADABLE, "${events.size} events kept: the SDK's version or the platform is one the events endpoint refuses"))
                return
            }
            isSendingDeviceEvents = true
            eventCountEnqueuedInFlight = 0
            request
        }
        val url = "${configuration.updatesBaseUrl}/v1/apps/${configuration.appId}/events"
        val answer = BatchAnswer.of(runCatching { http.post(url, mapOf("Content-Type" to "application/json"), request.toJson().toString().toByteArray()) }.getOrNull())
        log.record(LogEntry.ofBatch(answer, request.events.size, clock.now()))
        lock.withLock {
            isSendingDeviceEvents = false
            when (answer) {
                is BatchAnswer.Acknowledged -> {
                    dropBatchEvents()
                    state.reportedAt = resolveKeptReportedAt(state.reportedAt, DeviceEventsAcknowledgement(request.report != null, answer.reportedAt))
                    request.report?.let { state.acknowledgedReport = it }
                }
                is BatchAnswer.Refused -> dropBatchEvents()
                is BatchAnswer.Failed -> Unit
            }
        }
    }

    // Only the events enqueued while the batch was in flight stay, even when the outbox cap dropped some of the batch meanwhile.
    private fun dropBatchEvents() {
        state.unsentEvents = state.unsentEvents.takeLast(eventCountEnqueuedInFlight)
    }

    private fun buildDeviceReport(): DeviceReport? {
        val channel = channel()
        // A device without a channel id reports nothing, since a row for it would mislead.
        val channelId = channel.id ?: return null
        val report = DeviceReport(state.attributes, device.binaryBuild, device.binaryVersion, channelId, channel.source, configuration.embeddedBundleId, configuration.fingerprint, device.osVersion, state.currentRelease?.id)
        val reportedAt = state.reportedAt
        val isAcknowledged = report == state.acknowledgedReport && reportedAt != null && resolveUtcMonthNumber(reportedAt) == resolveUtcMonthNumber(clock.now())
        return if (isAcknowledged) null else report
    }

    companion object {
        /** The longest `handleAppStartBlocking()` waits for the start, in seconds, before it answers the embedded bundle. */
        const val START_TIMEOUT = 2.0

        // In seconds, counted from the kept index's fetchedAt.
        private const val CACHED_INDEX_MAX_AGE = 86_400.0

        private const val REPORT_UNREADABLE = "report_unreadable"

        /** The message a build without a channel answers with, without sending a request. */

        const val MISSING_CHANNEL_MESSAGE = "The build carries no channel: it was built without a token or offline, so the channel's name was never resolved. Build it with a token to receive updates."
    }
}
