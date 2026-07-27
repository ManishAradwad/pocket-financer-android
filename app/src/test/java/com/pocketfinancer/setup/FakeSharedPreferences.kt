package com.pocketfinancer.setup

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk

internal class FakeSharedPreferences(
    initialValues: Map<String, Any?> = emptyMap()
) {
    val values = initialValues.toMutableMap()
    val preferences: SharedPreferences = mockk()
    private val editor: SharedPreferences.Editor = mockk()

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
            values[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            values[firstArg()] = secondArg<Int>()
            editor
        }
        every { editor.putLong(any(), any()) } answers {
            values[firstArg()] = secondArg<Long>()
            editor
        }
        every { editor.putString(any(), any()) } answers {
            values[firstArg()] = secondArg<String?>()
            editor
        }
        every { editor.remove(any()) } answers {
            values.remove(firstArg())
            editor
        }
        every { editor.commit() } returns true
    }
}
