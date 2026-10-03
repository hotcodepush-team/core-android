package com.hotcodepush.protocol

/**
 * One line of this session's log: when, the code from the catalog, and the sentence behind it. The log lives in memory
 * behind the debug screen, never on disk and never on the wire.
 */
data class LogEntry(val at: Long, val code: String, val message: String) {
    companion object {
        const val CAPACITY = 200

        /** A cycle's result: the status, the reason and the condition as the code, the trigger and the release in the sentence. */
        fun ofCycle(result: SyncResult, trigger: SyncTrigger, at: Long): LogEntry {
            val code = listOfNotNull(result.status.wire, result.reason, result.condition?.wire).joinToString(" ")
            return LogEntry(at, code, "${trigger.wire}: ${resolveCycleSentence(result)}")
        }

        /** An outcome event as it enters the outbox; a check event is the cycle's line already. */
        fun ofDeviceEvent(event: DeviceEvent, at: Long): LogEntry? = when (event.type) {
            "downloaded" -> LogEntry(at, "DOWNLOADED", "release ${event.releaseId ?: ""}: ${event.bytes ?: 0} bytes as ${event.packKind ?: ""} pack")
            "applied" -> LogEntry(at, "APPLIED", "release ${event.releaseId ?: ""} is the running release")
            "confirmed" -> LogEntry(at, "CONFIRMED", "release ${event.releaseId ?: ""} passed the readiness gate")
            "failed" -> LogEntry(at, "FAILED ${event.reason ?: ""}", "release ${event.releaseId ?: ""}" + (event.detail?.let { ": $it" } ?: ""))
            "rolledBack" -> LogEntry(at, "ROLLED_BACK", "release ${event.fromReleaseId ?: ""} rolled back to ${event.toReleaseId ?: "the embedded bundle"}")
            else -> null
        }

        /** One batch to the events endpoint: acknowledged, or kept for the next sync. */
        fun ofReport(eventCount: Int, status: Int?, at: Long): LogEntry = when (status) {
            null -> LogEntry(at, "REPORT_FAILED", "$eventCount events kept for the next sync: the events endpoint could not be reached")
            202 -> LogEntry(at, "REPORTED", "$eventCount events acknowledged")
            else -> LogEntry(at, "REPORT_FAILED", "$eventCount events kept for the next sync: HTTP $status")
        }

        private fun resolveCycleSentence(result: SyncResult): String {
            val release = result.release?.let { "release #${it.number} (${it.bundleVersion})" }
            return when (result.status) {
                SyncStatus.UP_TO_DATE -> "${release ?: "the embedded bundle"} is current"
                SyncStatus.AVAILABLE -> "${release ?: "a release"} is available"
                SyncStatus.DOWNLOADED -> "${release ?: "a release"} is downloaded and waits for applyUpdate()"
                SyncStatus.UPDATED -> "${release ?: "a release"} installs ${result.installAt?.wire ?: ""}"
                SyncStatus.SKIPPED -> "${release ?: "the newest release"} is not taken"
                SyncStatus.FAILED -> result.message ?: ""
            }
        }
    }
}

/** The log itself, the newest two hundred lines: the core's cycles write it, the debug screen reads it. */
class SessionLog {
    private val entries = ArrayDeque<LogEntry>()

    @Synchronized
    fun record(entry: LogEntry) {
        entries.addLast(entry)
        if (entries.size > LogEntry.CAPACITY) entries.removeFirst()
    }

    @Synchronized
    fun entries(): List<LogEntry> = entries.toList()
}
