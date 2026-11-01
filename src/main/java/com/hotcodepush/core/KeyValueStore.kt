package com.hotcodepush.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/** The platform's key-value store, one value per key in the store's native type; `SharedPreferences` on Android, a map in tests. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int?)
}

/**
 * Commits every write before it returns. `apply()` reaches the disk about 100 ms later, and a crash in that window would lose
 * the state the next start rolls back from.
 */
@SuppressLint("ApplySharedPref")
class SharedPreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
    /** The app's default `SharedPreferences`, the file `PreferenceManager.getDefaultSharedPreferences` names, without the dependency. */
    constructor(context: Context) : this(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE))

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }

    override fun getInt(key: String): Int? = if (preferences.contains(key)) runCatching { preferences.getInt(key, 0) }.getOrNull() else null

    override fun putInt(key: String, value: Int?) {
        preferences.edit().apply { if (value == null) remove(key) else putInt(key, value) }.commit()
    }
}
