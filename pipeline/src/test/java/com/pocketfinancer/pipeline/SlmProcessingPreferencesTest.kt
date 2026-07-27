package com.pocketfinancer.pipeline

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlmProcessingPreferencesTest {

    @Test
    fun `missing grammar preference defaults off and explicit values survive recreation`() {
        val context = mockk<Context>()
        val sharedPreferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        var storedValue: Boolean? = null

        every {
            context.getSharedPreferences(
                SlmProcessingPreferences.PREFERENCES_NAME,
                Context.MODE_PRIVATE
            )
        } returns sharedPreferences
        every {
            sharedPreferences.getBoolean(
                SlmProcessingPreferences.KEY_GBNF_GRAMMAR_ENABLED,
                SlmProcessingPreferences.DEFAULT_GBNF_GRAMMAR_ENABLED
            )
        } answers {
            storedValue ?: SlmProcessingPreferences.DEFAULT_GBNF_GRAMMAR_ENABLED
        }
        every { sharedPreferences.edit() } returns editor
        every {
            editor.putBoolean(
                SlmProcessingPreferences.KEY_GBNF_GRAMMAR_ENABLED,
                any()
            )
        } answers {
            storedValue = secondArg()
            editor
        }
        every { editor.commit() } returns true

        val preferences = SlmProcessingPreferences(context)
        assertFalse(preferences.gbnfGrammarEnabled.value)

        preferences.setGbnfGrammarEnabled(true)
        assertTrue(preferences.gbnfGrammarEnabled.value)

        val recreatedPreferences = SlmProcessingPreferences(context)
        assertTrue(recreatedPreferences.gbnfGrammarEnabled.value)

        recreatedPreferences.setGbnfGrammarEnabled(false)
        assertFalse(recreatedPreferences.gbnfGrammarEnabled.value)
        assertFalse(SlmProcessingPreferences(context).gbnfGrammarEnabled.value)
    }
}
