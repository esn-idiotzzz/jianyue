package com.joe.jmlite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoverRequestsTest {
    private static void ready(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "cover worker did not complete");
    }

    @Test void coversRunTogetherWithoutExceedingTheWorkerLimit() throws Exception {
        try (CoverRequests<String> requests = new CoverRequests<>(2, 8)) {
            CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1), completed = new CountDownLatch(3);
            AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger();
            for (int index = 0; index < 3; index++) {
                requests.load("cover" + index, cancellation -> {
                    int active = running.incrementAndGet(); peak.accumulateAndGet(active, Math::max); started.countDown();
                    try { ready(release); return "bitmap"; } finally { running.decrementAndGet(); }
                }, result -> completed.countDown());
            }
            try { ready(started); assertEquals(2, running.get()); assertEquals(2, peak.get()); }
            finally { release.countDown(); }
            ready(completed); assertEquals(2, peak.get());
        }
    }

    @Test void matchingVisibleCoversShareASingleDownload() throws Exception {
        try (CoverRequests<String> requests = new CoverRequests<>(2, 8)) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(2);
            AtomicInteger downloads = new AtomicInteger();
            List<String> results = Collections.synchronizedList(new ArrayList<>());
            CoverRequests.Loader<String> loader = cancellation -> { downloads.incrementAndGet(); started.countDown(); ready(release); return "bitmap"; };
            requests.load("same-source|same-url", loader, result -> { results.add(result); completed.countDown(); });
            ready(started);
            requests.load("same-source|same-url", loader, result -> { results.add(result); completed.countDown(); });
            release.countDown(); ready(completed);
            assertEquals(1, downloads.get()); assertEquals(Arrays.asList("bitmap", "bitmap"), results);
        }
    }

    @Test void detachingOneViewDoesNotCancelAnotherViewOfTheSameCover() throws Exception {
        try (CoverRequests<String> requests = new CoverRequests<>(2, 8)) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(1);
            AtomicInteger cancelled = new AtomicInteger(), detachedResults = new AtomicInteger();
            CoverRequests.Ticket first = requests.load("shared", cancellation -> {
                cancellation.onCancel(cancelled::incrementAndGet); started.countDown(); ready(release); return "bitmap";
            }, result -> detachedResults.incrementAndGet());
            ready(started);
            requests.load("shared", cancellation -> { fail("duplicate loader must not run"); return null; }, result -> completed.countDown());
            first.cancel(); release.countDown(); ready(completed);
            assertEquals(0, cancelled.get()); assertEquals(0, detachedResults.get());
        }
    }

    @Test void theLastDetachedViewCancelsItsRunningNetworkRequest() throws Exception {
        try (CoverRequests<String> requests = new CoverRequests<>(1, 8)) {
            CountDownLatch started = new CountDownLatch(1), cancelled = new CountDownLatch(1), finished = new CountDownLatch(1);
            AtomicInteger delivered = new AtomicInteger();
            CoverRequests.Ticket ticket = requests.load("old-screen", cancellation -> {
                cancellation.onCancel(cancelled::countDown); started.countDown(); ready(cancelled);
                assertTrue(cancellation.isCancelled()); finished.countDown(); return "obsolete";
            }, result -> delivered.incrementAndGet());
            ready(started); ticket.cancel(); ready(cancelled); ready(finished); assertEquals(0, delivered.get());
        }
    }

    @Test void queuedDetachedCoversAreRemovedAndNewestVisibleCoversStartFirst() throws Exception {
        try (CoverRequests<String> requests = new CoverRequests<>(1, 8)) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(3);
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            requests.load("first", cancellation -> { started.countDown(); ready(release); order.add("first"); return "first"; }, result -> completed.countDown());
            ready(started);
            CoverRequests.Ticket discarded = requests.load("detached", cancellation -> { fail("detached cover must not download"); return null; }, result -> fail("detached view must not update"));
            requests.load("older", cancellation -> { order.add("older"); return "older"; }, result -> completed.countDown());
            discarded.cancel();
            requests.load("newest", cancellation -> { order.add("newest"); return "newest"; }, result -> completed.countDown());
            release.countDown(); ready(completed); assertEquals(Arrays.asList("first", "newest", "older"), order);
        }
    }

    @Test void pendingQueueIsBoundedWithoutDiscardingAlreadyQueuedCovers() throws Exception {
        try (CoverRequests<String> requests = new CoverRequests<>(1, 2)) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(3), rejected = new CountDownLatch(1);
            requests.load("running", cancellation -> { started.countDown(); ready(release); return "running"; }, result -> completed.countDown());
            ready(started);
            for (int index = 0; index < 2; index++) requests.load("queued" + index, cancellation -> "queued", result -> completed.countDown());
            requests.load("overflow", cancellation -> { fail("overflow must not create an extra worker"); return null; }, result -> { assertNull(result); rejected.countDown(); });
            ready(rejected); release.countDown(); ready(completed);
        }
    }

    @Test void failedCoverReleasesItsKeySoLaterVisibleRequestsCanRetry() throws Exception {
        try (CoverRequests<String> requests = new CoverRequests<>(1, 8)) {
            CountDownLatch failed = new CountDownLatch(1), recovered = new CountDownLatch(1);
            requests.load("retry", cancellation -> { throw new IOException("offline"); }, result -> { assertNull(result); failed.countDown(); });
            ready(failed);
            requests.load("retry", cancellation -> "bitmap", result -> { assertEquals("bitmap", result); recovered.countDown(); });
            ready(recovered);
        }
    }
}
