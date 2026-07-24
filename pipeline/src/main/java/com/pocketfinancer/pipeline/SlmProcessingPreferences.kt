package com.pocketfinancer.pipeline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent settings that affect SLM-based SMS extraction.
 *
 * Consumers must snapshot [gbnfGrammarEnabled] once when an SMS starts
 * processing so a preference change cannot alter an in-flight inference.
 */
@Singleton
class SlmProcessingPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _gbnfGrammarEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_GBNF_GRAMMAR_ENABLED, DEFAULT_GBNF_GRAMMAR_ENABLED)
    )
    val gbnfGrammarEnabled: StateFlow<Boolean> = _gbnfGrammarEnabled.asStateFlow()

    fun setGbnfGrammarEnabled(enabled: Boolean) {
        _gbnfGrammarEnabled.value = enabled
        preferences.edit()
            .putBoolean(KEY_GBNF_GRAMMAR_ENABLED, enabled)
            .apply()
    }

    companion object {
        internal const val PREFERENCES_NAME = ".app_settings"
        internal const val KEY_GBNF_GRAMMAR_ENABLED = "gbnf_grammar_enabled"
        internal const val DEFAULT_GBNF_GRAMMAR_ENABLED = true
    }
}
