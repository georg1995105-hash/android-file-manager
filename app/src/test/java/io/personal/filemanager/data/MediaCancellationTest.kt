package io.personal.filemanager.data

import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class MediaCancellationTest {
    @Test fun cancelledReadSignalsProvider() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CountDownLatch(1)
        val cancelled = CompletableDeferred<Boolean>()
        val job = launch(Dispatchers.IO) {
            cancellableMediaRead { signal ->
                signal.setOnCancelListener { stopped.countDown() }
                entered.complete(Unit)
                cancelled.complete(stopped.await(5, TimeUnit.SECONDS) && signal.isCanceled)
                signal.throwIfCanceled()
            }
        }
        try {
            withTimeout(10_000) { entered.await() }
            job.cancel()
            assertTrue(withTimeout(10_000) { cancelled.await() })
            job.join()
        } finally { job.cancel() }
    }

    @Test fun successfulReadReturnsResult() = runBlocking {
        org.junit.Assert.assertEquals(42, cancellableMediaRead { 42 })
    }
}
