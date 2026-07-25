package com.pocketfinancer.ui.home

import android.content.Context
import com.pocketfinancer.PocketFinancerApp
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.pipeline.HomeSyncDelegate
import com.pocketfinancer.pipeline.IncomingSmsQueueResult
import com.pocketfinancer.pipeline.SmsWorkerFlowLease
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeSyncDelegateImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeSyncManager: HomeSyncManager,
    private val appFlowCoordinator: SlmAppFlowCoordinator
) : HomeSyncDelegate {

    override suspend fun queueIncomingSms(
        address: String,
        body: String,
        date: Long
    ): IncomingSmsQueueResult {
        return homeSyncManager.queueIncomingSms(address, body, date)
    }

    override suspend fun tryEnterSmsWorkerFlow(): SmsWorkerFlowLease? {
        val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.SMS_WORKER)
            ?: return null
        return object : SmsWorkerFlowLease {
            override suspend fun release() {
                flowLease.release()
            }
        }
    }

    override fun startSyncService() {
        SyncService.start(context)
    }

    override fun isAppInForeground(): Boolean {
        val app = context.applicationContext as? PocketFinancerApp
        return app?.isAppInForeground ?: false
    }
}
