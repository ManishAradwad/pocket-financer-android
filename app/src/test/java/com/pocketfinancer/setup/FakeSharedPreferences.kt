package com.pocketfinancer.setup

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk

internal class FakeSharedPreferences(
    initialValues: Map<String, Any?> = emptyMap()
) {
    var commitSucceeds: Boolean = true
    var commitFailure: RuntimeException? = null
    var beforeCommit: (() -> Unit)? = null
    val values = initialValues.toMutableMap()
    val preferences: SharedPreferences = mockk()
    private val editor: SharedPreferences.Editor = mockk()
    private val pendingValues = mutableMapOf<String, Any?>()
    private val pendingRemovals = mutableSetOf<String>()

    init {
        every { preferences.contains(any()) } answers {
            values.containsKey(firstArg())
        }
        every { preferences.getBoolean(any(), any()) } answers {
            values[firstArg()] as? Boolean ?: secondArg()
        }
        every { preferences.getInt(any(), any()) } answers {
            values[firstArg()] as? Int ?: secondArg()
        }
        every { preferences.getLong(any(), any()) } answers {
            values[firstArg()] as? Long ?: secondArg()
        }
        every { preferences.getString(any(), any()) } answers {
            values[firstArg()] as? String ?: secondArg()
        }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            val key = firstArg<String>()
            pendingRemovals.remove(key)
            pendingValues[key] = secondArg<Boolean>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            val key = firstArg<String>()
            pendingRemovals.remove(key)
            pendingValues[key] = secondArg<Int>()
            editor
        }
        every { editor.putLong(any(), any()) } answers {
            val key = firstArg<String>()
            pendingRemovals.remove(key)
            pendingValues[key] = secondArg<Long>()
            editor
        }
        every { editor.putString(any(), any()) } answers {
            val key = firstArg<String>()
            pendingRemovals.remove(key)
            pendingValues[key] = secondArg<String?>()
            editor
        }
        every { editor.remove(any()) } answers {
            val key = firstArg<String>()
            pendingValues.remove(key)
            pendingRemovals += key
            editor
        }
        every { editor.commit() } answers {
            try {
                beforeCommit?.invoke()
                commitFailure?.let { throw it }
                commitSucceeds.also { succeeded ->
                    if (succeeded) {
                        pendingRemovals.forEach(values::remove)
                        values.putAll(pendingValues)
                    }
                }
            } finally {
                pendingValues.clear()
                pendingRemovals.clear()
            }
        }
    }
}
