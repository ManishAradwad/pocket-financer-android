package com.pocketfinancer.pipeline

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.selectSlmForDevice
import com.pocketfinancer.inference.LlamaEngine
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.sms.SmsWorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker to process a transaction SMS.
 *
 * Runs out of process / backgrounded.
 * Evaluates transactional criteria, dynamically loads the local model,
 * runs inference, parses output, saves transaction, and unloads model.
 */
class SmsParserWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ParserWorkerEntryPoint {
        fun smsFilterPipeline(): SmsFilterPipeline
        fun llamaEngine(): LlamaEngine
        fun deviceCapabilities(): DeviceCapabilities
        fun pipelineService(): PipelineService
    }

    override suspend fun doWork(): Result {
        val address = inputData.getString("address") ?: return Result.failure()
        val body = inputData.getString("body") ?: return Result.failure()
        val date = inputData.getLong("date", 0L)
        val type = inputData.getInt("type", 1)

        val sms = SmsReader.SmsMessage(address, body, date, type)
        Log.i("SmsParserWorker", "Starting background SMS processing for sender: $address")

        // Retrieve dependencies from Hilt Application EntryPoint
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            ParserWorkerEntryPoint::class.java
        )
        val smsFilterPipeline = entryPoint.smsFilterPipeline()
        val llamaEngine = entryPoint.llamaEngine()
        val deviceCapabilities = entryPoint.deviceCapabilities()
        val pipelineService = entryPoint.pipelineService()

        // 1. Stage 0-5 pre-filtering
        if (!smsFilterPipeline.isTransactional(address, body)) {
            Log.i("SmsParserWorker", "SMS from $address is non-transactional. Skipping background inference.")
            return Result.success()
        }

        var loadedModelHere = false
        try {
            // 2. Ensure model is loaded
            if (!llamaEngine.isModelLoaded()) {
                Log.i("SmsParserWorker", "Model not loaded. Performing dynamic hardware selection...")
                val device = deviceCapabilities.assessDevice()
                val slm = selectSlmForDevice(device)
                if (slm == null) {
                    Log.e("SmsParserWorker", "No viable SLM for this device (RAM below minimum).")
                    return Result.failure()
                }

                val modelFile = File(llamaEngine.getModelStorageDir(), slm.modelFile)
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    Log.e("SmsParserWorker", "Selected model ${slm.name} is not downloaded yet at ${modelFile.absolutePath}.")
                    return Result.failure()
                }

                Log.i("SmsParserWorker", "Loading model ${slm.name}...")
                val hasFp16 = device.cpu?.hasFp16 ?: false
                val loadResult = llamaEngine.loadModel(
                    path = modelFile.absolutePath,
                    contextSize = 3072,
                    gpuLayers = 0,
                    numThreads = 0,
                    hasFp16 = hasFp16,
                    hasThinkingMode = slm.hasThinkingMode
                )

                if (loadResult.isFailure) {
                    Log.e("SmsParserWorker", "Failed to load model in background", loadResult.exceptionOrNull())
                    return Result.retry()
                }
                loadedModelHere = true
                Log.i("SmsParserWorker", "Model ${slm.name} loaded successfully in background.")
            }

            // 3. Process SMS transaction
            Log.i("SmsParserWorker", "Executing pipeline service for extraction...")
            val parsedResult = pipelineService.processSingle(sms)
            if (parsedResult != null) {
                Log.i("SmsParserWorker", "Background processing completed successfully. Saved transaction of ₹${parsedResult.amount}.")
                try {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            applicationContext,
                            "Background: Saved transaction of ₹${parsedResult.amount} from ${parsedResult.counterparty ?: address}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    Log.e("SmsParserWorker", "Failed to show background Toast", e)
                }
            } else {
                Log.w("SmsParserWorker", "Pipeline execution finished but returned null (no transaction saved).")
            }

        } catch (e: Exception) {
            Log.e("SmsParserWorker", "Exception in background SMS parsing worker", e)
            return Result.failure()
        } finally {
            // 4. Always unload the model if we loaded it in this worker session to preserve device RAM
            if (loadedModelHere) {
                Log.i("SmsParserWorker", "Unloading background model to release memory...")
                llamaEngine.unloadModel()
                Log.i("SmsParserWorker", "Background model unloaded successfully.")
            }
        }

        return Result.success()
    }
}

/**
 * Concrete implementation of the SmsWorkScheduler interface.
 * Decouples the :sms module from the background worker.
 */
@Singleton
class SmsWorkSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SmsWorkScheduler {
    override fun scheduleSmsParsing(address: String, body: String, date: Long) {
        Log.i("SmsWorkScheduler", "Scheduling background SMS parsing worker for: $address")
        val data = workDataOf(
            "address" to address,
            "body" to body,
            "date" to date
        )
        val workRequest = OneTimeWorkRequestBuilder<SmsParserWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }
}

/**
 * Hilt module to bind the work scheduler implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SmsPipelineModule {
    @Binds
    @Singleton
    abstract fun bindSmsWorkScheduler(impl: SmsWorkSchedulerImpl): SmsWorkScheduler
}
