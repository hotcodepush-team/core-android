package com.hotcodepush.core

import android.content.SharedPreferences
import org.junit.Test
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock

/**
 * The core's state as the store writes it: committed before the put returns, so a crash right after it finds the value on disk.
 * Over a recorded editor, since Robolectric's own `apply()` waits for the disk and so cannot tell the two writes apart.
 */
class SharedPreferencesStoreTest {
    private val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
    private val store = SharedPreferencesStore(mock(SharedPreferences::class.java).also { `when`(it.edit()).thenReturn(editor) })

    @Test
    fun shouldCommitAStringBeforeThePutReturns() {
        store.putString("hotcodepush.deviceId", "d1")

        val writes = inOrder(editor)
        writes.verify(editor).putString("hotcodepush.deviceId", "d1")
        writes.verify(editor).commit()
    }

    @Test
    fun shouldCommitAnIntBeforeThePutReturns() {
        store.putInt("hotcodepush.stateVersion", 4)

        val writes = inOrder(editor)
        writes.verify(editor).putInt("hotcodepush.stateVersion", 4)
        writes.verify(editor).commit()
    }
}
