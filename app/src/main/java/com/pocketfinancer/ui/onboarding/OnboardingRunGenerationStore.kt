package com.pocketfinancer.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable generation boundary for starting [OnboardingService].
 *
 * Android may deliver a previously scheduled service start after a destructive
 * reset has finished releasing its in-memory admission gates. Each start is
 * stamped with the current generation, while reset increments the generation
 * in the same SharedPreferences commit as `onboarding_completed = false`.
 * Delayed starts from an older generation can then be rejected safely, even
 * when delivery crosses process death.
 */
@Singleton
class OnboardingRunGenerationStore internal constructor(
    private val preferences: SharedPreferences
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        context.getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
    )

    fun currentGeneration(): Long =
        preferences.getLong(PREFERENCE_KEY, INITIAL_GENERATION)

    fun nextGeneration(): Long = currentGeneration() + 1L

    fun stamp(
        intent: Intent,
        generation: Long = currentGeneration()
    ) {
        intent.putExtra(EXTRA_RUN_GENERATION, generation)
    }

    fun isCurrent(intent: Intent?): Boolean {
        val requestedGeneration = intent
            ?.takeIf { it.hasExtra(EXTRA_RUN_GENERATION) }
            ?.getLongExtra(EXTRA_RUN_GENERATION, INITIAL_GENERATION)
        return isCurrentGeneration(requestedGeneration)
    }

    internal fun isCurrentGeneration(requestedGeneration: Long?): Boolean =
        requestedGeneration != null && requestedGeneration == currentGeneration()

    companion object {
        const val EXTRA_RUN_GENERATION =
            "com.pocketfinancer.extra.ONBOARDING_RUN_GENERATION"
        const val PREFERENCE_KEY = "onboarding_run_generation"
        const val INITIAL_GENERATION = 0L

        private const val APP_SETTINGS = ".app_settings"
    }
}
