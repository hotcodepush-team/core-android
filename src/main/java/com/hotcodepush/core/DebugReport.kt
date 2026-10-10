package com.hotcodepush.core

/** Everything the debug screen shows, read from the core in one call. */
data class DebugSnapshot(
    val takenAt: Long,
    val device: DeviceResult,
    val configuration: Configuration,
    val isDebugBuild: Boolean,
    val state: StateResult,
    val log: List<LogEntry>,
)

data class DebugRow(val label: String, val value: String)

data class DebugSection(val title: String, val rows: List<DebugRow>)

/** The debug screen's content as sections of label and value, and the same content as the text the share sheet carries. */
object DebugReport {
    fun sections(snapshot: DebugSnapshot): List<DebugSection> = listOf(
        deviceSection(snapshot),
        channelSection(snapshot),
        releasesSection(snapshot),
        lastCheckSection(snapshot),
        indexSection(snapshot),
        configurationSection(snapshot),
        logSection(snapshot),
    )

    fun text(snapshot: DebugSnapshot): String {
        val lines = mutableListOf("HotCodePush debug report, ${Iso8601.format(snapshot.takenAt)}")
        for (section in sections(snapshot)) {
            lines += ""
            lines += section.title
            for (row in section.rows) lines += "  ${row.label}: ${row.value}"
        }
        return lines.joinToString("\n")
    }

    private fun deviceSection(snapshot: DebugSnapshot): DebugSection {
        val device = snapshot.device
        val attributes = device.attributes.toSortedMap().map { (key, value) -> "$key=$value" }
        return DebugSection(
            "Device",
            listOf(
                DebugRow("Device id", device.id),
                DebugRow("Platform", device.platform),
                DebugRow("Binary", "${device.binaryVersion} (${device.binaryBuild})"),
                DebugRow("OS", device.osVersion),
                DebugRow("SDK", device.sdkVersion),
                DebugRow("Fingerprint", device.fingerprint ?: "none"),
                DebugRow("Debug build", if (snapshot.isDebugBuild) "yes" else "no"),
                DebugRow("Attributes", if (attributes.isEmpty()) "none" else attributes.joinToString(", ")),
            ),
        )
    }

    private fun channelSection(snapshot: DebugSnapshot): DebugSection {
        val channel = snapshot.device.channel
        return DebugSection(
            "Channel",
            listOf(
                DebugRow("Channel id", describeChannelId(channel, snapshot.configuration)),
                DebugRow("Name", channel.name ?: "none"),
                DebugRow("Source", channel.source.wire),
            ),
        )
    }

    private fun releasesSection(snapshot: DebugSnapshot): DebugSection {
        val state = snapshot.state
        return DebugSection(
            "Releases",
            listOf(
                DebugRow("Running", describe(state.currentRelease) ?: "the embedded bundle"),
                DebugRow("Downloaded", describe(state.nextRelease) ?: "none"),
                DebugRow("Fallback", describe(state.fallbackRelease) ?: "the embedded bundle"),
                DebugRow("Embedded bundle", describeEmbeddedBundle(snapshot)),
                DebugRow("Failed bundles", if (state.failedBundleIds.isEmpty()) "none" else state.failedBundleIds.joinToString(", ")),
            ),
        )
    }

    private fun lastCheckSection(snapshot: DebugSnapshot): DebugSection {
        val check = snapshot.state.lastCheck ?: return DebugSection("Last check", listOf(DebugRow("When", "never")))
        val entry = LogEntry.ofCycle(check.result, check.trigger, check.at)
        return DebugSection(
            "Last check",
            listOf(
                DebugRow("When", Iso8601.format(check.at)),
                DebugRow("Trigger", check.trigger.wire),
                DebugRow("Result", entry.code),
                DebugRow("Release", describe(check.result.release) ?: "none"),
                DebugRow("Message", entry.message),
            ),
        )
    }

    private fun indexSection(snapshot: DebugSnapshot): DebugSection {
        val state = snapshot.state
        return DebugSection(
            "Index",
            listOf(
                DebugRow("Sequence", state.indexSequence?.toString() ?: "none"),
                DebugRow("Fetched at", state.indexFetchedAt?.let(Iso8601::format) ?: "never"),
                DebugRow("Last report at", state.reportedAt?.let(Iso8601::format) ?: "never"),
            ),
        )
    }

    private fun configurationSection(snapshot: DebugSnapshot): DebugSection {
        val configuration = snapshot.configuration
        return DebugSection(
            "Configuration",
            listOf(
                DebugRow("App id", configuration.appId),
                DebugRow("Configured channel", configuration.channelId ?: "none"),
                DebugRow("Built at", Iso8601.format(configuration.builtAt)),
                DebugRow("Files host", configuration.filesBaseUrl),
                DebugRow("Updates host", configuration.updatesBaseUrl),
                DebugRow("Check strategy", if (configuration.checkStrategy == CheckStrategy.AUTO) "auto, every ${configuration.checkIntervalSeconds.toInt()} s" else "manual"),
                DebugRow("Strategies", "download ${configuration.downloadStrategy.wire}, apply ${configuration.applyStrategy.wire}, mandatory ${configuration.mandatoryApplyStrategy.wire}"),
                DebugRow("Ready signal", "${configuration.readySignal.wire}, ${configuration.readyTimeoutSeconds.toInt()} s"),
                DebugRow("Debug builds", if (configuration.enabledInDebugBuilds) "enabled" else "disabled"),
                DebugRow("Public keys", configuration.publicKeys.size.toString()),
            ),
        )
    }

    private fun logSection(snapshot: DebugSnapshot): DebugSection {
        val rows = snapshot.log.map { DebugRow(Iso8601.format(it.at), "${it.code} — ${it.message}") }
        return DebugSection("Log", rows.ifEmpty { listOf(DebugRow("Entries", "none this session")) })
    }

    /** The id in effect; without one, a runtime name waits for its first sync and a build without a channel says why it has none. */
    private fun describeChannelId(channel: ChannelResult, configuration: Configuration): String = when {
        channel.id != null -> channel.id
        channel.source == ChannelSource.RUNTIME -> "unresolved"
        configuration.embeddedBundleManifest == null -> "none: a build without an embedded bundle resolves no channel name"
        else -> "none: the build carries no channel, it was built without a token or offline"
    }

    /** The registered bundle's id; a build without an embedded bundle says that live updates are off in it. */
    private fun describeEmbeddedBundle(snapshot: DebugSnapshot): String = when {
        snapshot.configuration.embeddedBundleManifest == null -> "none: the build embeds no bundle, live updates are off in it"
        else -> snapshot.state.embeddedBundleId ?: "not registered"
    }

    private fun describe(release: Release?): String? = release?.let { "#${it.number} (${it.bundleVersion}), bundle ${it.bundleId}${if (it.isMandatory) ", mandatory" else ""}" }
}
