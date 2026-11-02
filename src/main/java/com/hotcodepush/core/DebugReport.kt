package com.hotcodepush.core

/** Everything the debug screen shows, read from the core in one call. */
data class DebugSnapshot(
    val takenAt: Long,
    val device: GetDeviceResult,
    val configuration: Configuration,
    val isDebugBuild: Boolean,
    val state: GetStateResult,
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
                DebugRow(label = "Device id", value = device.id),
                DebugRow(label = "Platform", value = device.platform),
                DebugRow(label = "Binary", value = "${device.binaryVersion} (${device.binaryBuild})"),
                DebugRow(label = "OS", value = device.osVersion),
                DebugRow(label = "SDK", value = device.sdkVersion),
                DebugRow(label = "Fingerprint", value = device.fingerprint ?: "none"),
                DebugRow(label = "Debug build", value = if (snapshot.isDebugBuild) "yes" else "no"),
                DebugRow(label = "Attributes", value = if (attributes.isEmpty()) "none" else attributes.joinToString(", ")),
            ),
        )
    }

    private fun channelSection(snapshot: DebugSnapshot): DebugSection {
        val channel = snapshot.device.channel
        return DebugSection(
            "Channel",
            listOf(
                DebugRow(label = "Channel id", value = describeChannelId(channel, snapshot.configuration)),
                DebugRow(label = "Name", value = channel.name ?: "none"),
                DebugRow(label = "Source", value = channel.source.wire),
            ),
        )
    }

    private fun releasesSection(snapshot: DebugSnapshot): DebugSection {
        val state = snapshot.state
        return DebugSection(
            "Releases",
            listOf(
                DebugRow(label = "Running", value = describe(state.currentRelease) ?: "the embedded bundle"),
                DebugRow(label = "Downloaded", value = describe(state.nextRelease) ?: "none"),
                DebugRow(label = "Fallback", value = describe(state.fallbackRelease) ?: "the embedded bundle"),
                DebugRow(label = "Embedded bundle", value = describeEmbeddedBundle(snapshot)),
                DebugRow(label = "Failed bundles", value = if (state.failedBundleIds.isEmpty()) "none" else state.failedBundleIds.joinToString(", ")),
            ),
        )
    }

    private fun lastCheckSection(snapshot: DebugSnapshot): DebugSection {
        val check = snapshot.state.lastCheck ?: return DebugSection("Last check", listOf(DebugRow(label = "When", value = "never")))
        val entry = LogEntry.ofCycle(check.result, check.trigger, check.checkedAt)
        return DebugSection(
            "Last check",
            listOf(
                DebugRow(label = "When", value = Iso8601.format(check.checkedAt)),
                DebugRow(label = "Trigger", value = check.trigger.wire),
                DebugRow(label = "Result", value = entry.code),
                DebugRow(label = "Release", value = describe(check.result.release) ?: "none"),
                DebugRow(label = "Message", value = entry.message),
            ),
        )
    }

    private fun indexSection(snapshot: DebugSnapshot): DebugSection {
        val state = snapshot.state
        return DebugSection(
            "Index",
            listOf(
                DebugRow(label = "Published at", value = state.indexSequence?.let(Iso8601::format) ?: "none"),
                DebugRow(label = "Fetched at", value = state.indexFetchedAt?.let(Iso8601::format) ?: "never"),
                DebugRow(label = "Reported at", value = state.reportedAt?.let(Iso8601::format) ?: "never"),
            ),
        )
    }

    private fun configurationSection(snapshot: DebugSnapshot): DebugSection {
        val configuration = snapshot.configuration
        return DebugSection(
            "Configuration",
            listOf(
                DebugRow(label = "App id", value = configuration.appId),
                DebugRow(label = "Configured channel", value = configuration.channelId ?: "none"),
                DebugRow(label = "Built at", value = Iso8601.format(configuration.builtAt)),
                DebugRow(label = "Check strategy", value = if (configuration.checkStrategy == CheckStrategy.AUTO) "auto, every ${configuration.checkIntervalSeconds.toInt()} s" else "manual"),
                DebugRow(label = "Strategies", value = "download ${configuration.downloadStrategy.wire}, apply ${configuration.applyStrategy.wire}, mandatory ${configuration.mandatoryApplyStrategy.wire}"),
                DebugRow(label = "Ready signal", value = "${configuration.readySignal.wire}, ${configuration.readyTimeoutSeconds.toInt()} s"),
                DebugRow(label = "Debug builds", value = if (configuration.isEnabledInDebugBuilds) "enabled" else "disabled"),
                DebugRow(label = "Public keys", value = configuration.publicKeys.size.toString()),
            ),
        )
    }

    private fun logSection(snapshot: DebugSnapshot): DebugSection {
        val rows = snapshot.log.map { DebugRow(label = Iso8601.format(it.at), value = "${it.code} — ${it.message}") }
        return DebugSection("Log", rows.ifEmpty { listOf(DebugRow(label = "Entries", value = "none this session")) })
    }

    private fun describeChannelId(channel: GetChannelResult, configuration: Configuration): String = when {
        channel.id != null -> channel.id
        channel.source == ChannelSource.RUNTIME -> "unresolved"
        configuration.embeddedBundleManifest == null -> "none: a build without an embedded bundle resolves no channel name"
        else -> "none: the build carries no channel, it was built without a token or offline"
    }

    private fun describeEmbeddedBundle(snapshot: DebugSnapshot): String = when {
        snapshot.configuration.embeddedBundleManifest == null -> "none: the build embeds no bundle, over-the-air (OTA) updates are off in it"
        else -> snapshot.state.embeddedBundleId ?: "not registered"
    }

    private fun describe(release: Release?): String? = release?.let { "#${it.number} (${it.bundleVersion}), bundle ${it.bundleId}${if (it.isMandatory) ", mandatory" else ""}" }
}
