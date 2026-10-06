package com.hotcodepush.core

import org.json.JSONObject

/** The SDK's own view of an index entry: what is on disk, what runs, what passed the gate. */
data class Release(
    val id: String,
    val number: Int,
    val bundleId: String,
    val bundleVersion: String,
    val isMandatory: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("number", number)
        .put("bundleId", bundleId)
        .put("bundleVersion", bundleVersion)
        .put("isMandatory", isMandatory)

    companion object {
        fun fromJson(json: JSONObject) = Release(
            id = json.getString("id"),
            number = json.getInt("number"),
            bundleId = json.getString("bundleId"),
            bundleVersion = json.getString("bundleVersion"),
            isMandatory = json.getBoolean("isMandatory"),
        )
    }
}
