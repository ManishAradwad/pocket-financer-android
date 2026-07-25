package com.pocketfinancer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.isPublishedModelArtifact
import com.pocketfinancer.inference.SlmModelStorage
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PocketFinancerApp : Application() {
    @Inject
    lateinit var selectedModelResidency: SelectedModelResidency

    @Inject
    lateinit var modelStorage: SlmModelStorage

    @Inject
    lateinit var deviceCapabilities: DeviceCapabilities

    private var activeActivities = 0

    val isAppInForeground: Boolean
        get() = activeActivities > 0

    override fun onCreate() {
        super.onCreate()
        restoreSelectedModelPin()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activeActivities++
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                activeActivities--
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Runtime leases are intentionally in-memory, so process death clears them.
     * Recreate the persisted user-selection pin from the exact downloaded
     * artifact before any UI, service, or WorkManager entry point needs it.
     */
    private fun restoreSelectedModelPin() {
        val preferences = getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(ONBOARDING_COMPLETED, false)) return
        val selectedId = preferences
            .getString(SELECTED_SLM_ID, null)
            ?: return
        val tier = SlmTier.ALL_TIERS.find { it.id == selectedId } ?: return
        val file = modelStorage.modelFile(tier.modelFile)
        if (!isPublishedModelArtifact(file)) return

        try {
            val spec = tier.toModelSpec(modelStorage, deviceCapabilities.assessDevice())
            selectedModelResidency.restore(spec)
        } catch (error: Exception) {
            Log.e(TAG, "Could not prepare selected-model residency restore", error)
        }
    }

    private companion object {
        const val TAG = "PocketFinancerApp"
        const val APP_SETTINGS = ".app_settings"
        const val ONBOARDING_COMPLETED = "onboarding_completed"
        const val SELECTED_SLM_ID = "selected_slm_id"
    }
}
