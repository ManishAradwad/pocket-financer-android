package com.pocketfinancer.pipeline

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmsWorkSchedulerImplTest {

    private val context = mockk<Context>()
    private val workManager = mockk<WorkManager>()
    private lateinit var completedEnqueue: Operation
    private lateinit var completedCancel: Operation

    @Before
    fun setUp() {
        mockkObject(WorkManager.Companion)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { WorkManager.getInstance(context) } returns workManager

        completedEnqueue = completedOperation()
        completedCancel = completedOperation()
        every {
            workManager.enqueueUniqueWork(
                any(),
                any<ExistingWorkPolicy>(),
                any<OneTimeWorkRequest>()
            )
        } returns completedEnqueue
        every { workManager.cancelUniqueWork(any()) } returns completedCancel
    }

    @After
    fun tearDown() {
        unmockkObject(WorkManager.Companion)
        unmockkStatic(Log::class)
    }

    @Test
    fun `all parser requests append to one unique secondary defence chain`() {
        val request = slot<OneTimeWorkRequest>()
        val scheduler = SmsWorkSchedulerImpl(context, SmsWorkAdmissionGate())

        scheduler.scheduleSmsParsing(
            address = "AX-HDFCBK",
            body = "Rs.500 credited",
            date = 1234L
        )

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                SmsWorkSchedulerImpl.UNIQUE_SMS_PARSER_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                capture(request)
            )
        }
        assertEquals("AX-HDFCBK", request.captured.workSpec.input.getString("address"))
        assertEquals("Rs.500 credited", request.captured.workSpec.input.getString("body"))
        assertEquals(1234L, request.captured.workSpec.input.getLong("date", 0L))
    }

    @Test
    fun `pause waits for admitted enqueue and drops all enqueue attempts until release`() =
        runTest {
            val enqueueFuture = TestListenableFuture<Operation.State.SUCCESS>()
            val pendingEnqueue = operation(enqueueFuture)
            every {
                workManager.enqueueUniqueWork(
                    any(),
                    any<ExistingWorkPolicy>(),
                    any<OneTimeWorkRequest>()
                )
            } returns pendingEnqueue

            val gate = SmsWorkAdmissionGate()
            val scheduler = SmsWorkSchedulerImpl(context, gate)
            val controller = WorkManagerSmsWorkController(context, gate)

            scheduler.scheduleSmsParsing("AX-HDFCBK", "Rs.500 credited", 1234L)
            val pause = controller.pauseAdmissions()
            val cancellation = async {
                pause.cancelPending()
            }
            runCurrent()

            assertFalse(cancellation.isCompleted)
            verify(exactly = 0) { workManager.cancelUniqueWork(any()) }

            scheduler.scheduleSmsParsing("AX-ICICI", "Rs.200 debited", 2345L)
            verify(exactly = 1) {
                workManager.enqueueUniqueWork(
                    any(),
                    any<ExistingWorkPolicy>(),
                    any<OneTimeWorkRequest>()
                )
            }

            enqueueFuture.complete(Operation.SUCCESS)
            advanceUntilIdle()
            cancellation.await()
            assertFalse(pause.isReleased)
            verify(exactly = 1) {
                workManager.cancelUniqueWork(
                    SmsWorkSchedulerImpl.UNIQUE_SMS_PARSER_WORK
                )
            }

            scheduler.scheduleSmsParsing("AX-SBI", "Rs.300 debited", 3456L)
            verify(exactly = 1) {
                workManager.enqueueUniqueWork(
                    any(),
                    any<ExistingWorkPolicy>(),
                    any<OneTimeWorkRequest>()
                )
            }

            pause.release()
            pause.release()
            assertTrue(pause.isReleased)

            scheduler.scheduleSmsParsing("AX-KOTAK", "Rs.400 debited", 4567L)
            verify(exactly = 2) {
                workManager.enqueueUniqueWork(
                    any(),
                    any<ExistingWorkPolicy>(),
                    any<OneTimeWorkRequest>()
                )
            }
        }

    @Test
    fun `nested pause handles keep admission closed until every handle releases`() =
        runTest {
            val gate = SmsWorkAdmissionGate()
            val scheduler = SmsWorkSchedulerImpl(context, gate)
            val controller = WorkManagerSmsWorkController(context, gate)

            val first = controller.pauseAdmissions()
            first.cancelPending()
            val second = controller.pauseAdmissions()
            second.cancelPending()
            first.release()

            scheduler.scheduleSmsParsing("AX-HDFC", "Rs.100 credited", 1000L)
            verify(exactly = 0) {
                workManager.enqueueUniqueWork(
                    any(),
                    any<ExistingWorkPolicy>(),
                    any<OneTimeWorkRequest>()
                )
            }

            second.release()
            scheduler.scheduleSmsParsing("AX-HDFC", "Rs.200 credited", 2000L)
            verify(exactly = 1) {
                workManager.enqueueUniqueWork(
                    any(),
                    any<ExistingWorkPolicy>(),
                    any<OneTimeWorkRequest>()
                )
            }
        }

    @Test
    fun `standalone cancellation waits for WorkManager acknowledgement`() = runTest {
        val cancellationFuture = TestListenableFuture<Operation.State.SUCCESS>()
        every {
            workManager.cancelUniqueWork(
                SmsWorkSchedulerImpl.UNIQUE_SMS_PARSER_WORK
            )
        } returns operation(cancellationFuture)
        val controller = WorkManagerSmsWorkController(
            context,
            SmsWorkAdmissionGate()
        )

        val cancellation = async { controller.cancelPending() }
        runCurrent()

        assertFalse(cancellation.isCompleted)
        cancellationFuture.complete(Operation.SUCCESS)
        advanceUntilIdle()
        assertTrue(cancellation.isCompleted)
    }

    private fun completedOperation(): Operation {
        val future = TestListenableFuture<Operation.State.SUCCESS>()
        future.complete(Operation.SUCCESS)
        return operation(future)
    }

    private fun operation(
        future: TestListenableFuture<Operation.State.SUCCESS>
    ): Operation = mockk {
        every { result } returns future
    }
}

class SmsParserWorkerPolicyTest {

    @Test
    fun `foreground admission pause maps to retry without starting sync`() {
        val success = Any()
        val retry = Any()
        var started = false

        val result = foldIncomingSmsQueueResult(
            queueResult = IncomingSmsQueueResult.ADMISSION_PAUSED,
            onQueuedTransaction = {
                started = true
                success
            },
            onIgnored = { success },
            onAdmissionPaused = { retry }
        )

        assertSame(retry, result)
        assertFalse(started)
    }

    @Test
    fun `ignored foreground message remains terminal`() {
        val success = Any()
        val retry = Any()

        val result = foldIncomingSmsQueueResult(
            queueResult = IncomingSmsQueueResult.IGNORED,
            onQueuedTransaction = { error("Ignored SMS must not start foreground sync") },
            onIgnored = { success },
            onAdmissionPaused = { retry }
        )

        assertSame(success, result)
    }

    @Test
    fun `direct worker does not run while model maintenance owns admission`() = runTest {
        val delegate = mockk<HomeSyncDelegate>()
        val retry = Any()
        var processed = false
        coEvery { delegate.tryEnterSmsWorkerFlow() } returns null

        val result = withSmsWorkerFlowAdmission(
            delegate = delegate,
            onAdmissionPaused = { retry }
        ) {
            processed = true
            Any()
        }

        assertSame(retry, result)
        assertFalse(processed)
    }

    @Test
    fun `direct worker holds and releases admission across processing`() = runTest {
        val delegate = mockk<HomeSyncDelegate>()
        val flowLease = mockk<SmsWorkerFlowLease>()
        coEvery { delegate.tryEnterSmsWorkerFlow() } returns flowLease
        coEvery { flowLease.release() } returns Unit

        val result = withSmsWorkerFlowAdmission(
            delegate = delegate,
            onAdmissionPaused = { error("Admission should be available") }
        ) {
            "processed"
        }

        assertEquals("processed", result)
        coVerify(exactly = 1) { flowLease.release() }
    }

    @Test
    fun `direct worker releases admission when processing fails`() = runTest {
        val delegate = mockk<HomeSyncDelegate>()
        val flowLease = mockk<SmsWorkerFlowLease>()
        coEvery { delegate.tryEnterSmsWorkerFlow() } returns flowLease
        coEvery { flowLease.release() } returns Unit

        assertFailsWith<IllegalStateException> {
            withSmsWorkerFlowAdmission(
                delegate = delegate,
                onAdmissionPaused = { error("Admission should be available") }
            ) {
                throw IllegalStateException("processing failed")
            }
        }

        coVerify(exactly = 1) { flowLease.release() }
    }

    @Test
    fun `persisted parser work is terminal when onboarding was reset`() {
        val preferences = mockk<SharedPreferences>()
        every {
            preferences.getBoolean("onboarding_completed", false)
        } returns false

        assertFalse(isOnboardingCompleteForSmsWork(preferences))
    }

    @Test
    fun `parser work remains eligible after completed onboarding`() {
        val preferences = mockk<SharedPreferences>()
        every {
            preferences.getBoolean("onboarding_completed", false)
        } returns true

        assertTrue(isOnboardingCompleteForSmsWork(preferences))
    }

    @Test
    fun `second onboarding read observes reset committed while runtime acquire waited`() {
        val preferences = mockk<SharedPreferences>()
        every {
            preferences.getBoolean("onboarding_completed", false)
        } returnsMany listOf(true, false)

        assertTrue(isOnboardingCompleteForSmsWork(preferences))
        assertFalse(isOnboardingCompleteForSmsWork(preferences))
        verify(exactly = 2) {
            preferences.getBoolean("onboarding_completed", false)
        }
    }

    @Test
    fun `exception from foreground delegate or filter cannot poison appended work`() =
        runTest {
            val retry = ListenableWorker.Result.retry()
            var reported: Exception? = null

            val result = protectSmsParserChain(
                onFailure = { reported = it },
                retryOrFinishChain = { retry }
            ) {
                throw IllegalStateException("delegate failed")
            }

            assertEquals("delegate failed", reported?.message)
            assertSame(retry, result)
        }

    @Test
    fun `chain boundary still propagates worker cancellation`() = runTest {
        assertFailsWith<CancellationException> {
            protectSmsParserChain(
                onFailure = { error("Cancellation must not be reported as failure") },
                retryOrFinishChain = { ListenableWorker.Result.retry() }
            ) {
                throw CancellationException("worker stopped")
            }
        }
    }
}

private class TestListenableFuture<T> : ListenableFuture<T> {
    private val monitor = Object()
    private val listeners = mutableListOf<Pair<Runnable, Executor>>()
    private var completed = false
    private var cancelled = false
    private var value: T? = null
    private var failure: Throwable? = null

    fun complete(result: T) {
        finish(result = result, failure = null, wasCancelled = false)
    }

    override fun addListener(listener: Runnable, executor: Executor) {
        val executeImmediately = synchronized(monitor) {
            if (completed) {
                true
            } else {
                listeners += listener to executor
                false
            }
        }
        if (executeImmediately) {
            executor.execute(listener)
        }
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        synchronized(monitor) {
            if (completed) return false
        }
        finish(result = null, failure = null, wasCancelled = true)
        return true
    }

    override fun isCancelled(): Boolean = synchronized(monitor) { cancelled }

    override fun isDone(): Boolean = synchronized(monitor) { completed }

    override fun get(): T {
        synchronized(monitor) {
            while (!completed) {
                monitor.wait()
            }
            return resolvedValue()
        }
    }

    override fun get(timeout: Long, unit: TimeUnit): T {
        val deadlineNanos = System.nanoTime() + unit.toNanos(timeout)
        synchronized(monitor) {
            while (!completed) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) throw TimeoutException()
                val millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                val nanos = (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
                monitor.wait(millis, nanos)
            }
            return resolvedValue()
        }
    }

    private fun finish(
        result: T?,
        failure: Throwable?,
        wasCancelled: Boolean
    ) {
        val callbacks = synchronized(monitor) {
            if (completed) return
            completed = true
            cancelled = wasCancelled
            value = result
            this.failure = failure
            monitor.notifyAll()
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { (listener, executor) ->
            executor.execute(listener)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolvedValue(): T {
        if (cancelled) throw CancellationException()
        failure?.let { throw ExecutionException(it) }
        return value as T
    }
}
