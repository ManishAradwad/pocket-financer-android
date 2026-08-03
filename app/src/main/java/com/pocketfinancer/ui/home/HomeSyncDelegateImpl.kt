package com.pocketfinancer.ui.home

import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.pipeline.HomeSyncDelegate
import com.pocketfinancer.pipeline.SmsWorkerFlowLease
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeSyncDelegateImpl @Inject constructor(
    private val appFlowCoordinator: SlmAppFlowCoordinator
) : HomeSyncDelegate {

    override suspend fun tryEnterSmsWorkerFlow(): SmsWorkerFlowLease? {
        val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.SMS_WORKER)
            ?: return null
        return object : SmsWorkerFlowLease {
            override suspend fun release() {
                flowLease.release()
            }
        }
    }
}
