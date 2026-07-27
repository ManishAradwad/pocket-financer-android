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
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.sms.SmsWorkScheduler
import com.pocketfinancer.ui.settings.LocalFinancialEraseRecovery
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class PocketFinancerApp : Application() {
    @Inject
    lateinit var selectedModelResidency: SelectedModelResidency

    @Inject
    lateinit var modelStorage: SlmModelStorage

    @Inject
    lateinit var deviceCapabilities: DeviceCapabilities

    @Inject
    lateinit var smsWorkScheduler: SmsWorkScheduler

    @Inject
    lateinit var localFinancialEraseRecovery: LocalFinancialEraseRecovery

    @Inject
    lateinit var setupImportStore: SetupImportStore

    private val applicationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeActivities = 0

    val isAppInForeground: Boolean
        get() = activeActivities > 0

    override fun onCreate() {
        super.onCreate()
        recoverInterruptedLocalFinancialErase()
        if (!localFinancialEraseRecovery.isRecoveryPending()) {
            reconcileSetupModelAvailability()
            restoreSelectedModelPin()
            reconcileEncryptedSmsOutbox()
        } else {
            Log.e(
                TAG,
                "Local financial erase recovery is still pending; startup work remains locked"
            )
        }
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
     * A pending erase has already durably locked the shell and advanced the
     * service generation. Finish it before UI, model restoration, or encrypted
     * outbox reconciliation can observe the new process.
     */
    private fun recoverInterruptedLocalFinancialErase() {
        if (!localFinancialEraseRecovery.isRecoveryPending()) return
        try {
            val result = runBlocking(Dispatchers.IO) {
                localFinancialEraseRecovery.recoverIfNeeded()
            }
            result.cleanupFailure?.let { failure ->
                Log.e(
                    TAG,
                    "Recovered local financial erase with ancillary cleanup failure",
                    failure
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not complete interrupted local financial erase", error)
        }
    }

    private fun reconcileSetupModelAvailability() {
        val hasPublishedModel = SlmTier.ALL_TIERS.any { tier ->
            isPublishedModelArtifact(modelStorage.modelFile(tier.modelFile))
        }
        setupImportStore.reconcileModelAvailability(hasPublishedModel)
    }

    private fun reconcileEncryptedSmsOutbox() {
        applicationScope.launch {
            try {
                smsWorkScheduler.reconcilePendingAutomaticWork()
            } catch (error: Exception) {
                Log.e(TAG, "Could not reconcile encrypted SMS work", error)
            }
        }
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
