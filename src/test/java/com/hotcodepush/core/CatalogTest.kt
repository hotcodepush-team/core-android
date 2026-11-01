package com.hotcodepush.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// Each catalog list is alphabetical, so an enum declared in another order, such as the lifecycle's, is compared sorted.
class CatalogTest {
    private val catalog = JSONObject(File("node_modules/@hotcodepush/protocol/catalog.json").readText())

    private fun assertCatalogValues(key: String, values: List<String>) = assertEquals(key, catalog.getJSONArray(key).toStringList(), values)

    @Test
    fun shouldCoverEveryListOfTheCatalog() = assertEquals(
        setOf(
            "applyStrategies", "applyUpdateStatuses", "channelSources", "checkStrategies", "conditionTypes", "deviceEventTypes", "downloadStrategies", "failedReasons",
            "mandatoryApplyStrategies", "packKinds", "platforms", "readySignals", "rollbackReasons", "skippedReasons", "syncStatuses", "syncTriggers",
        ),
        catalog.keys().asSequence().toSet(),
    )

    @Test
    fun shouldCarryTheCatalogsApplyStrategiesInItsOrder() = assertCatalogValues("applyStrategies", ApplyStrategy.entries.map { it.wire })

    @Test
    fun shouldCarryTheCatalogsApplyUpdateStatusesInItsOrder() = assertCatalogValues("applyUpdateStatuses", ApplyUpdateStatus.entries.map { it.wire })

    @Test
    fun shouldCarryTheCatalogsChannelSources() = assertCatalogValues("channelSources", ChannelSource.entries.map { it.wire }.sorted())

    @Test
    fun shouldCarryTheCatalogsCheckStrategiesInItsOrder() = assertCatalogValues("checkStrategies", CheckStrategy.entries.map { it.wire })

    @Test
    fun shouldCarryTheCatalogsConditionTypes() = assertCatalogValues("conditionTypes", ConditionType.entries.map { it.wire }.sorted())

    @Test
    fun shouldWriteTheCatalogsDeviceEventTypesInItsOrder() = assertCatalogValues(
        "deviceEventTypes",
        listOf(
            DeviceEvent.applied("r1"),
            DeviceEvent.checked("r1", SyncStatus.AVAILABLE),
            DeviceEvent.confirmed("r1"),
            DeviceEvent.downloaded("r1", 1, PackKind.FULL),
            DeviceEvent.failed("r1", FailedReason.DOWNLOAD_FAILED.wire),
        ).map { it.type },
    )

    @Test
    fun shouldCarryTheCatalogsDownloadStrategiesInItsOrder() = assertCatalogValues("downloadStrategies", DownloadStrategy.entries.map { it.wire })

    @Test
    fun shouldCarryTheCatalogsFailedReasonsInItsOrder() = assertCatalogValues("failedReasons", FailedReason.entries.map { it.wire })

    @Test
    fun shouldCarryTheCatalogsMandatoryApplyStrategiesInItsOrder() = assertCatalogValues("mandatoryApplyStrategies", MandatoryApplyStrategy.entries.map { it.wire })

    @Test
    fun shouldCarryTheCatalogsPackKinds() = assertCatalogValues("packKinds", PackKind.entries.map { it.wire }.sorted())

    @Test
    fun shouldCarryTheCatalogsPlatformsInItsOrder() = assertCatalogValues("platforms", ChannelIndex.PLATFORMS)

    @Test
    fun shouldCarryTheCatalogsReadySignalsInItsOrder() = assertCatalogValues("readySignals", ReadySignal.entries.map { it.wire })

    @Test
    fun shouldCarryTheCatalogsRollbackReasonsInItsOrder() = assertCatalogValues("rollbackReasons", RollbackReason.entries.map { it.wire })

    @Test
    fun shouldCarryTheCatalogsSkippedReasonsInItsOrder() = assertCatalogValues("skippedReasons", SkippedReason.entries.map { it.wire })

    @Test
    fun shouldCarryTheCatalogsSyncStatuses() = assertCatalogValues("syncStatuses", SyncStatus.entries.map { it.wire }.sorted())

    @Test
    fun shouldCarryTheCatalogsSyncTriggers() = assertCatalogValues("syncTriggers", SyncTrigger.entries.map { it.wire }.sorted())
}
