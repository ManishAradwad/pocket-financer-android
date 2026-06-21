package com.pocketfinancer.ui.home

import android.content.Context
import com.pocketfinancer.PocketFinancerApp
import com.pocketfinancer.pipeline.HomeSyncDelegate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeSyncDelegateImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeSyncManager: HomeSyncManager
) : HomeSyncDelegate {

    override suspend fun queueIncomingSms(address: String, body: String, date: Long): Boolean {
        return homeSyncManager.queueIncomingSms(address, body, date)
    }

    override fun startSyncService() {
        SyncService.start(context)
    }

    override fun isAppInForeground(): Boolean {
        val app = context.applicationContext as? PocketFinancerApp
        return app?.isAppInForeground ?: false
    }
}
