package com.waenhancer.library.core;

import static com.waenhancer.library.core.LibraryFixture.mp4;
import static com.waenhancer.library.core.LibraryFixture.request;
import static com.waenhancer.library.core.LibraryFixture.source;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class MediaLibraryConcurrencyTest {
    private LibraryFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new LibraryFixture();
    }

    @After
    public void tearDown() throws Exception {
        fixture.close();
    }

    @Test
    public void concurrentThreadsOnSeparateConnectionsImportIdenticalAndDistinctMediaConsistently() throws Exception {
        int threads = 8;
        int perThread = 12;
        List<MediaLibrary> instances = new ArrayList<>();
        for (int i = 0; i < threads; i++) instances.add(fixture.open());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    try {
                        // Every thread imports the same 4 "popular" files plus its own files, and
                        // every thread also re-imports message SAME-i to race idempotency.
                        byte[] bytes = i < 4 ? mp4(900 + i, 8_000) : mp4(thread * 1000 + i, 8_000);
                        instances.get(thread).importMedia(request("chat@s.whatsapp.net", "T" + thread + "-" + i).build(),
                                source(bytes));
                        instances.get(thread).importMedia(request("chat@s.whatsapp.net", "SAME-" + i).build(),
                                source(mp4(700 + i, 8_000)));
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) future.get(120, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue("failures: " + failures, failures.isEmpty());

        MediaLibrary check = fixture.open();
        int expectedItems = threads * perThread + perThread;
        int expectedBlobs = 4 + threads * (perThread - 4) + perThread;
        assertEquals(expectedItems, check.listAll(false).size());
        assertEquals(expectedBlobs, check.blobs().listBlobs().size());
        assertEquals(expectedBlobs, LibraryFixture.count(fixture.connection(), "blobs"));
        assertEquals(0, new File(fixture.libraryDir(), "staging").list().length);
        MediaLibrary.ReconcileReport report = check.reconcile(true);
        assertEquals(0, report.orphanBlobsRemoved + report.markedCorrupt + report.markedMissing + report.unreferencedBlobsRemoved);
        assertEquals(expectedBlobs, report.verified);
    }

    @Test
    public void twoOperatingSystemProcessesShareOneLibraryWithoutCorruption() throws Exception {
        MediaLibrary parent = fixture.open();
        int count = 30;
        Process child = ChildProcess.start(ChildProcess.Importer.class, Collections.emptyList(),
                fixture.root.getPath(), "CHILD", String.valueOf(count));
        for (int i = 0; i < count; i++) {
            byte[] bytes = i % 2 == 0 ? mp4(5_000 + i, 20_000) : mp4(-17 * 31 + i * 7 + 3, 20_000);
            parent.importMedia(request("chat@s.whatsapp.net", "PARENT" + i).build(), source(bytes));
        }
        String output = ChildProcess.finish(child, 180);
        assertTrue(output, output.contains("imported " + count));

        MediaLibrary check = fixture.open();
        assertEquals(2 * count, check.listAll(false).size());
        Set<String> shas = new HashSet<>();
        for (LibraryItem item : check.listAll(false)) shas.add(item.sha256);
        assertEquals("shared files are stored once across processes", shas.size(), check.blobs().listBlobs().size());
        assertEquals(count / 2 + count / 2 + count / 2, shas.size());
        MediaLibrary.ReconcileReport report = check.reconcile(true);
        assertEquals(shas.size(), report.verified);
        assertEquals(0, report.orphanBlobsRemoved + report.markedCorrupt + report.markedMissing);
    }

    @Test
    public void lockIsReentrantForItsOwnerAndExclusiveForOthers() throws Exception {
        LibraryLock a = new LibraryLock(fixture.libraryDir().getParentFile());
        LibraryLock b = new LibraryLock(fixture.libraryDir().getParentFile());
        try (LibraryLock.Held outer = a.acquire(1000); LibraryLock.Held inner = b.acquire(1000)) {
            assertTrue(a.isHeldByCurrentThread());
            Thread other = new Thread(() -> {
                try {
                    b.acquire(200).close();
                    throw new AssertionError("another thread must not get the lock");
                } catch (LibraryException expected) {
                    // busy
                } catch (Exception unexpected) {
                    throw new AssertionError(unexpected);
                }
            });
            other.start();
            other.join();
        }
        assertTrue(!a.isHeldByCurrentThread());
        try (LibraryLock.Held again = b.acquire(1000)) {
            assertTrue(b.isHeldByCurrentThread());
        }
    }
}
