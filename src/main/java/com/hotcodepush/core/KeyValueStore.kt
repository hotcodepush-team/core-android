package com.hotcodepush.core

/** The platform's key-value store, one value per key in the store's native type; `SharedPreferences` on Android, a map in tests. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int?)
}
