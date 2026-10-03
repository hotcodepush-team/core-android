package com.hotcodepush.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** How a runtime channel name resolved. */
    private sealed class ChannelResolution {
        data class Id(val id: String) : ChannelResolution()
        object Offline : ChannelResolution()
        object Unknown : ChannelResolution()
        data class Invalid(val message: String) : ChannelResolution()
    }

    private val state = StateStore(store)
    private val downloader = Downloader(configuration, files, embedded, http, temporaryDirectory)
    private val httpClient = http
    private val lock = Mutex()

    private var readyTimer: ScheduledTask? = null
    private var intervalTimer: ScheduledTask? = null
    private var runningSync: Deferred<SyncResult>? = null
    private var isRestartAllowed = true
    private var queuedRestart: (() -> Unit)? = null
    private var isStartSyncPending = false
    private var isSendingDeviceEvents = false
    private var backgroundedAt: Long? = null
    private var resolvedChannelName: Pair<String, String>? = null

    /** The rollback the next reload announces, once, before the gate. */
    private var pendingRollbackEvent: RolledBackEvent? = null

    // Lifecycle

    /** The start of a run: the binary's floor, the files on disk, the previous run's verdict, the pending switch, the gate, then the cleanup. */
    suspend fun handleAppStart() = lock.withLock {
        state.lastRollback = null
        if (state.lastBuiltAt != configuration.builtAt || hasReleaseWithoutManifest()) dropStoredReleases()
        if (isCurrentReleaseUnconfirmed()) rollbackCurrentRelease(RollbackReason.CRASHED, null)
        val next = state.nextRelease
        if (next != null && shouldSwitchAtStart(next)) switchToNextRelease()
        loadBundle()
        if (isCurrentReleaseUnconfirmed()) {
            startReadyTimer()
            isStartSyncPending = true
        } else if (configuration.autoCheck) {
            scope.launch { sync(SyncTrigger.START) }
        }
        deleteUnusedFiles()
    }

    /** A mandatory release follows its own strategy, so one the app took over waits across starts; any other switches under `next-start`; a bundle the WebView already serves is adopted. */
    private fun shouldSwitchAtStart(next: Release): Boolean = when {
        loader.servedBundleId() == next.bundleId -> true
        next.isMandatory -> configuration.mandatoryInstallStrategy == MandatoryInstallStrategy.IMMEDIATE
        else -> configuration.installStrategy == InstallStrategy.NEXT_START
    }

    /** The first render, the readiness signal when `readySignal` is `render`. */
    suspend fun handleRendered() = lock.withLock {
        if (configuration.readySignal == ReadySignal.RENDER) confirmCurrentRelease()
    }

    /** Ends the gate when `readySignal` is `manual`, and tells the app whether this start follows a rollback. */
    suspend fun notifyReady(): NotifyReadyResult = lock.withLock {
        confirmCurrentRelease()
        val rollback = state.lastRollback
        state.lastRollback = null
        NotifyReadyResult(state.currentRelease, rollback?.from, rollback != null, rollback?.reason)
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
        if (backgroundDuration != null && configuration.installStrategy == InstallStrategy.NEXT_RESUME && state.nextRelease != null && backgroundDuration >= configuration.installOnResumeAfter) {
            installNextRelease()
            return
        }
        if (!configuration.autoCheck) return
        val elapsedSeconds = state.lastSyncAt?.let { (clock.now() - it) / 1000.0 }
        if (elapsedSeconds == null || elapsedSeconds >= configuration.checkInterval) {
            scope.launch { sync(SyncTrigger.RESUME) }
            return
        }
        scheduleIntervalSync(configuration.checkInterval - elapsedSeconds)
    }

    // The three stages

    /** One full cycle; a second call while one runs joins the running one. */
    suspend fun sync(trigger: SyncTrigger, options: SyncOptions = SyncOptions()): SyncResult {
        val running = lock.withLock {
            runningSync ?: scope.async { performCycle(trigger, Stage.SYNC, options) }.also { runningSync = it }
        }
        val result = running.await()
        lock.withLock { if (runningSync === running) runningSync = null }
        return result
    }

    /** The first stage: fetch and evaluate, download nothing. */
    suspend fun checkForUpdate(): SyncResult {
        lock.withLock { runningSync }?.await()
        return performCycle(SyncTrigger.MANUAL, Stage.CHECK, SyncOptions())
    }

    /** The second stage: download and verify the update the check finds, whatever `downloadStrategy` says, then install per the strategies. */
    suspend fun downloadUpdate(): SyncResult {
        lock.withLock { runningSync }?.await()
        return performCycle(SyncTrigger.MANUAL, Stage.DOWNLOAD, SyncOptions())
    }

    /** The third stage: apply the downloaded update now and reload the app. */
    suspend fun applyUpdate(): ApplyResult = lock.withLock {
        val next = state.nextRelease ?: return ApplyResult(ApplyStatus.NOTHING_TO_APPLY, state.currentRelease)
        switchToNextRelease()
        reloadApp()
        ApplyResult(ApplyStatus.APPLIED, next)
    }

    private suspend fun performCycle(trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult {
        val result = resolveCycle(trigger, stage, options)
        lock.withLock {
            if (stage != Stage.DOWNLOAD) state.lastCheck = LastCheck(clock.now(), trigger, result)
            if (stage == Stage.SYNC) {
                state.lastSyncAt = clock.now()
                scheduleIntervalSync(configuration.checkInterval)
            }
        }
        if (result.status == SyncStatus.FAILED) {
            val reason = result.reason?.let { name -> FailedReason.entries.firstOrNull { it.name == name } }
            if (reason != null) listener.updateFailed(UpdateFailedEvent(result.release, reason, result.message ?: "", trigger))
        }
        scope.launch { sendDeviceEvents() }
        return result
    }

    private suspend fun resolveCycle(trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult {
        val current = state.currentRelease
        if (isDisabledInThisBuild) return SyncResult.skipped(current, SkippedReason.DEBUG_BUILD)
        val channelId = when (val resolution = resolveChannelId()) {
            is ChannelResolution.Id -> resolution.id
            ChannelResolution.Offline -> return SyncResult.failed(current, FailedReason.OFFLINE, "The channels index could not be fetched to resolve the channel name")
            ChannelResolution.Unknown -> return SyncResult.failed(current, FailedReason.UNKNOWN_CHANNEL, "The channel set at runtime is not in the app's channels index")
            is ChannelResolution.Invalid -> return SyncResult.failed(current, FailedReason.INVALID_INDEX, resolution.message)
        }
        val index = when (val fetch = fetchChannelIndex(channelId)) {
            is IndexFetch.Index -> fetch.index
            IndexFetch.Offline -> return SyncResult.failed(current, FailedReason.OFFLINE, "The channel index could not be fetched and no cached copy exists")
            is IndexFetch.Invalid -> return SyncResult.failed(current, FailedReason.INVALID_INDEX, fetch.message)
            IndexFetch.Absent -> return SyncResult.upToDate(current)
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

    /** A release the device qualifies for: adopted in place when it carries the running bundle, else announced and taken as far as the stage goes. */
    private suspend fun update(target: IndexRelease, isMandatory: Boolean, trigger: SyncTrigger, stage: Stage, options: SyncOptions): SyncResult {
        val release = resolveRelease(target, isMandatory)
        val current = state.currentRelease
        if (stage != Stage.CHECK && current != null && current.bundleId == target.bundleId) {
            lock.withLock { adoptInPlace(release) }
            return SyncResult.updated(release, target.notes, InstallMoment.IMMEDIATE)
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
                DownloadStrategy.UNMETERED -> if (loader.isConnectionMetered()) SyncResult.skipped(release, SkippedReason.METERED_CONNECTION) else install(target, isMandatory, strategy, trigger, stage)
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
        } catch (exception: Exception) {
            lock.withLock { enqueueDeviceEvent(DeviceEvent.failed(target.id, FailedReason.DOWNLOAD_FAILED.name)) }
            return SyncResult.failed(release, FailedReason.DOWNLOAD_FAILED, exception.message ?: "")
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

    /** Rolls the running release back now; `detail` is the app's own cause, carried on the failure event. */
    suspend fun rollback(detail: String?) = lock.withLock {
        if (detail != null) AttributeRules.validate(detail)
        if (state.currentRelease != null) rollbackCurrentRelease(RollbackReason.REPORTED_BY_APP, detail)
    }

    /** Back to the embedded bundle: every downloaded update and the failed list go, the identity stays. */
    suspend fun clearUpdates() = lock.withLock {
        stopReadyTimer()
        state.currentRelease = null
        state.nextRelease = null
        state.fallbackRelease = null
        state.failedBundleIds = emptyList()
        state.lastRollback = null
        files.bundleIds().forEach(loader::deleteProjection)
        files.deleteEverything()
        loader.persistServedBundle(null)
        reloadApp()
    }

    suspend fun setRestartAllowed(allowed: Boolean) = lock.withLock {
        isRestartAllowed = allowed
        val restart = queuedRestart ?: return
        if (!allowed) return
        queuedRestart = null
        restart()
    }

    // State

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
        is ChannelChoice.Name -> ChannelResult(resolvedChannelName?.takeIf { it.first == choice.name }?.second ?: "", choice.name, ChannelSource.RUNTIME)
        null -> ChannelResult(configuration.channelId, null, ChannelSource.CONFIG)
    }

    suspend fun setChannel(choice: ChannelChoice?) = lock.withLock {
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
        state.lastBuiltAt = configuration.builtAt
        loader.persistServedBundle(null)
    }

    private fun setNextRelease(release: Release) {
        state.nextRelease = release
    }

    private fun switchToNextRelease() {
        val next = state.nextRelease ?: return
        state.currentRelease = next
        state.nextRelease = null
        loader.persistServedBundle(next.bundleId)
        enqueueDeviceEvent(DeviceEvent.applied(next.id))
    }

    private fun loadBundle() {
        val expected = state.currentRelease?.bundleId
        if (loader.servedBundleId() != expected) loader.loadServedBundle(expected)
    }

    /** The restart of the web layer: the bundle loads, a rollback this start follows is announced once, then the gate runs. */
    private fun reloadApp() {
        loader.loadServedBundle(state.currentRelease?.bundleId)
        pendingRollbackEvent?.let { event ->
            pendingRollbackEvent = null
            listener.rolledBack(event)
        }
        if (isCurrentReleaseUnconfirmed()) startReadyTimer()
    }

    /** The install the SDK performs on its own: the switch and the reload as one act behind the gate, so nothing changes until it runs. */
    private fun installNextRelease() = restartThroughGate {
        switchToNextRelease()
        reloadApp()
    }

    /** A restart the SDK performs on its own waits while the app holds restarts; the first one held runs when it lets go. */
    private fun restartThroughGate(restart: () -> Unit) {
        if (isRestartAllowed) restart() else if (queuedRestart == null) queuedRestart = restart
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
            if (configuration.autoCheck) scope.launch { sync(SyncTrigger.START) }
        }
    }

    private fun rollbackCurrentRelease(reason: RollbackReason, detail: String?) {
        val current = state.currentRelease ?: return
        stopReadyTimer()
        state.failedBundleIds = (state.failedBundleIds + current.bundleId).distinct().sorted()
        val fallback = resolveFallbackRelease()
        state.currentRelease = fallback
        state.nextRelease = null
        state.lastRollback = LastRollback(current, fallback, reason)
        pendingRollbackEvent = RolledBackEvent(current, fallback, reason)
        enqueueDeviceEvent(DeviceEvent.failed(current.id, reason.name, detail))
        enqueueDeviceEvent(DeviceEvent.rolledBack(current.id, fallback?.id))
        loader.persistServedBundle(fallback?.bundleId)
        if (reason == RollbackReason.REPORTED_BY_APP) reloadApp() else restartThroughGate { reloadApp() }
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
        restartThroughGate { reloadApp() }
    }

    private fun startReadyTimer() {
        stopReadyTimer()
        readyTimer = scheduler.schedule(configuration.readyTimeout) { scope.launch { handleReadyTimeout() } }
    }

    private fun stopReadyTimer() {
        readyTimer?.cancel()
        readyTimer = null
    }

    internal suspend fun handleReadyTimeout() = lock.withLock {
        if (isCurrentReleaseUnconfirmed()) rollbackCurrentRelease(RollbackReason.READY_TIMEOUT, null)
    }

    private fun scheduleIntervalSync(afterSeconds: Double) {
        intervalTimer?.cancel()
        if (!configuration.autoCheck) return
        intervalTimer = scheduler.schedule(afterSeconds) { scope.launch { sync(SyncTrigger.INTERVAL) } }
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
    }

    /** The runtime choice, then the configured id; a name resolves through the channels index, offline being offline and not an unknown name. */
    private suspend fun resolveChannelId(): ChannelResolution = when (val choice = state.channel) {
        null -> ChannelResolution.Id(configuration.channelId)
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
                if (index.schema != ChannelIndex.SCHEMA) return IndexFetch.Invalid("The channel index has schema ${index.schema}, this SDK reads ${ChannelIndex.SCHEMA}")
                if (cached != null && index.sequence < cached.body.sequence) return IndexFetch.Index(cached.body)
                lock.withLock { state.cachedIndex = CachedIndex(response.header("ETag"), clock.now(), index) }
                IndexFetch.Index(index)
            }
            404 -> {
                if (state.channel != null) {
                    lock.withLock { state.channel = null }
                    fetchChannelIndex(configuration.channelId)
                } else {
                    IndexFetch.Absent
                }
            }
            else -> cached?.let { IndexFetch.Index(it.body) } ?: IndexFetch.Offline
        }
    }

    private val isDisabledInThisBuild: Boolean
        get() = device.isDebugBuild && !configuration.enabledInDebugBuilds

    private fun deviceInfo() = DeviceInfo(null, state.attributes, device.binaryBuild, device.binaryVersion, configuration.builtAt, state.currentRelease, state.deviceId, state.failedBundleIds, configuration.fingerprint, device.osVersion, state.reportedAt, null)

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
    }

    /** One batch to the events endpoint, the outbox and the report when it changed: the 202 clears what was sent, anything else keeps it for the next sync. */
    private suspend fun sendDeviceEvents() {
        val request = lock.withLock {
            if (isSendingDeviceEvents || isDisabledInThisBuild) return
            val events = state.unsentEvents
            val report = buildDeviceReport()
            if (events.isEmpty() && report == null) return
            isSendingDeviceEvents = true
            DeviceEventsRequest(state.deviceId, events, device.platform, report, device.sdkVersion)
        }
        val url = "${configuration.updatesBaseUrl}/v1/apps/${configuration.appId}/events"
        val response = runCatching { httpClient.post(url, mapOf("Content-Type" to "application/json"), request.toJson().toString().toByteArray()) }.getOrNull()
        val acknowledged = response?.takeIf { it.status == 202 }?.let { runCatching { DeviceEventsResponse.fromJson(org.json.JSONObject(String(it.body, Charsets.UTF_8))) }.getOrNull() }
        lock.withLock {
            isSendingDeviceEvents = false
            if (acknowledged == null) return
            state.unsentEvents = state.unsentEvents.drop(request.events.size)
            state.reportedAt = acknowledged.reportedAt
            request.report?.let { state.acknowledgedReport = it }
        }
    }

    /** The facts the server should hold: the report when they differ from the acknowledged ones or the month began, else nothing. */
    private fun buildDeviceReport(): DeviceReport? {
        val channel = channel()
        if (channel.id.isEmpty()) return null
        val report = DeviceReport(state.attributes, device.binaryBuild, device.binaryVersion, channel.id, channel.source, configuration.embeddedBundleId, configuration.fingerprint, device.osVersion, state.currentRelease?.id)
        val reportedAt = state.reportedAt
        val isAcknowledged = report == state.acknowledgedReport && reportedAt != null && resolveMonth(reportedAt) == resolveMonth(clock.now())
        return if (isAcknowledged) null else report
    }

    private fun resolveMonth(epochMillis: Long) = Iso8601.format(epochMillis).substring(0, 7)
}
