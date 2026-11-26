package com.waenhancer.library.core;

import static com.waenhancer.library.core.LibraryFixture.ME;
import static com.waenhancer.library.core.LibraryFixture.W4B;
import static com.waenhancer.library.core.LibraryFixture.WA;
import static com.waenhancer.library.core.LibraryFixture.jpeg;
import static com.waenhancer.library.core.LibraryFixture.request;
import static com.waenhancer.library.core.LibraryFixture.source;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Row association tokens, delayed asynchronous callbacks and cache invalidation, driven against
 * a real library (real SQLite and files) through the same {@link InlineBinder} the hook uses.
 */
public class InlineBinderTest {
    private static final String CHAT = "5511888880000@s.whatsapp.net";
    private static final String OTHER_ME = "5511666660000@s.whatsapp.net";

    private LibraryFixture fixture;
    private MediaLibrary library;
    private InlineBinder<Object> binder;
    private String settings = "vo1";
    private final AtomicInteger lookups = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        fixture = new LibraryFixture();
        library = fixture.open();
        binder = new InlineBinder<>();
    }

    @After
    public void tearDown() throws Exception {
        fixture.close();
    }

    /** The provider's behaviour, over the real library. */
    private InlineBinder.Source source = new InlineBinder.Source() {
        @Override
        public String stateToken() {
            return library.generation() + ":" + settings;
        }

        @Override
        public InlineBinder.Answer lookup(InlineBinder.Ref ref) {
            lookups.incrementAndGet();
            LibraryItem item = library.lookup(ref.packageName, ref.ownJid, ref.chatJid, ref.chatAltJid,
                    ref.messageKey, ref.fromMe);
            return item != null && item.isAvailable() ? new InlineBinder.Answer(item.uuid, item.sha256)
                    : new InlineBinder.Answer("", "");
        }
    };

    private static InlineBinder.Ref ref(String own, String key) {
        return new InlineBinder.Ref(WA, own, CHAT, null, key, false);
    }

    private long now() {
        return fixture.clock.get();
    }

    @Test
    public void delayedCallbackIsDiscardedWhenTheRowWasReboundToAMessageWithoutMedia() throws Exception {
        library.importMedia(request(CHAT, "PHOTO").build(), source(jpeg(1)));
        Object row = new Object();
        InlineBinder.Task<Object> photo = binder.bind(row, ref(ME, "PHOTO"));
        // The row is recycled for a text message before the background lookup finishes.
        assertNull("a text message gets no task", binder.bind(row, null));
        assertNull("the late answer must not reach the row", binder.resolve(photo, source, now()));
        assertFalse(binder.isCurrent(photo));
        assertEquals("a stale task does not even query the library", 0, lookups.get());
    }

    @Test
    public void lateMainThreadPostIsRejectedAfterRebindEvenIfTheLookupSucceeded() throws Exception {
        library.importMedia(request(CHAT, "A").build(), source(jpeg(1)));
        library.importMedia(request(CHAT, "B").build(), source(jpeg(2)));
        Object row = new Object();
        InlineBinder.Task<Object> first = binder.bind(row, ref(ME, "A"));
        InlineBinder.Answer answer = binder.resolve(first, source, now());
        assertNotNull(answer);
        InlineBinder.Task<Object> second = binder.bind(row, ref(ME, "B"));
        assertFalse("the post for A must be dropped (row now shows B)", binder.isCurrent(first));
        assertTrue(binder.isCurrent(second));
        assertNotEquals(answer.uuid, binder.resolve(second, source, now()).uuid);
    }

    @Test
    public void rebindToTheSameMessageAlsoInvalidatesThePreviousToken() {
        Object row = new Object();
        InlineBinder.Task<Object> before = binder.bind(row, ref(ME, "SAME"));
        InlineBinder.Task<Object> after = binder.bind(row, ref(ME, "SAME"));
        assertFalse(binder.isCurrent(before));
        assertTrue(binder.isCurrent(after));
    }

    @Test
    public void callbackRacingARebindOnAnotherThreadNeverWins() throws Exception {
        library.importMedia(request(CHAT, "RACE").build(), source(jpeg(3)));
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch rebound = new CountDownLatch(1);
        InlineBinder.Source slow = new InlineBinder.Source() {
            @Override
            public String stateToken() {
                return source.stateToken();
            }

            @Override
            public InlineBinder.Answer lookup(InlineBinder.Ref r) {
                lookupStarted.countDown();
                try {
                    rebound.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return source.lookup(r);
            }
        };
        Object row = new Object();
        InlineBinder.Task<Object> task = binder.bind(row, ref(ME, "RACE"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<Boolean> attached = worker.submit(() -> {
            InlineBinder.Answer answer = binder.resolve(task, slow, now());
            // What the hook does on the main thread before touching the row:
            return answer != null && binder.isCurrent(task);
        });
        assertTrue(lookupStarted.await(5, TimeUnit.SECONDS));
        binder.bind(row, null); // recycled to a non-media message while the lookup is in flight
        rebound.countDown();
        assertFalse(attached.get(5, TimeUnit.SECONDS));
        worker.shutdown();
    }

    @Test
    public void positiveCacheIsDroppedAfterTrashRestoreAndPurge() throws Exception {
        LibraryItem item = library.importMedia(request(CHAT, "DEL").build(), source(jpeg(4))).item;
        Object row = new Object();
        assertNotNull(binder.resolve(binder.bind(row, ref(ME, "DEL")), source, now()));
        assertNotNull("served from cache", binder.resolve(binder.bind(row, ref(ME, "DEL")), source, now()));
        assertEquals(1, lookups.get());

        library.trash(item.uuid);
        assertNull("trashed media must disappear at once", binder.resolve(binder.bind(row, ref(ME, "DEL")), source, now()));
        library.restoreFromTrash(item.uuid);
        assertNotNull("restored media must reappear at once", binder.resolve(binder.bind(row, ref(ME, "DEL")), source, now()));
        library.trash(item.uuid);
        library.purge(item.uuid);
        assertNull(binder.resolve(binder.bind(row, ref(ME, "DEL")), source, now()));
    }

    @Test
    public void repairChangesTheThumbnailKeyAndDamageHidesTheItem() throws Exception {
        LibraryItem item = library.importMedia(request(CHAT, "REP").build(), source(jpeg(5))).item;
        Object row = new Object();
        InlineBinder.Task<Object> task = binder.bind(row, ref(ME, "REP"));
        InlineBinder.Answer before = binder.resolve(task, source, now());
        try (RandomAccessFile file = new RandomAccessFile(library.fileOf(item), "rw")) {
            file.setLength(10);
        }
        library.reconcile(false); // marks it corrupt → generation changes
        assertNull("a damaged copy is never presented from cache",
                binder.resolve(binder.bind(row, ref(ME, "REP")), source, now()));

        // Repaired with different bytes (WhatsApp re-sent a better copy): new content hash.
        library.importMedia(request(CHAT, "REP").build(), source(jpeg(6)));
        InlineBinder.Task<Object> again = binder.bind(row, ref(ME, "REP"));
        InlineBinder.Answer after = binder.resolve(again, source, now());
        assertNotNull(after);
        assertEquals(before.uuid, after.uuid);
        assertNotEquals("the old picture can never be shown",
                InlineBinder.thumbnailKey(task.ref, before), InlineBinder.thumbnailKey(again.ref, after));
    }

    @Test
    public void accountSwitchNeverServesTheOtherAccountsMedia() throws Exception {
        library.importMedia(request(CHAT, "ACC").build(), source(jpeg(7))); // account ME
        Object row = new Object();
        assertNotNull(binder.resolve(binder.bind(row, ref(ME, "ACC")), source, now()));
        // The user switches WhatsApp to another account; same chat JID and message key.
        assertNull(binder.resolve(binder.bind(row, ref(OTHER_ME, "ACC")), source, now()));
        assertEquals("only the new account's answer is cached", 1, binder.cachedEntries());
        InlineBinder.Ref business = new InlineBinder.Ref(W4B, ME, CHAT, null, "ACC", false);
        assertNull("WhatsApp Business does not see WhatsApp's media",
                binder.resolve(binder.bind(row, business), source, now()));
        assertNotEquals(InlineBinder.thumbnailKey(ref(ME, "ACC"), new InlineBinder.Answer("u", "h")),
                InlineBinder.thumbnailKey(ref(OTHER_ME, "ACC"), new InlineBinder.Answer("u", "h")));
    }

    @Test
    public void settingsChangeInvalidatesCachedAnswers() throws Exception {
        library.importMedia(request(CHAT, "SET").build(), source(jpeg(8)));
        Object row = new Object();
        binder.resolve(binder.bind(row, ref(ME, "SET")), source, now());
        binder.resolve(binder.bind(row, ref(ME, "SET")), source, now());
        assertEquals(1, lookups.get());
        settings = "vo0";
        binder.resolve(binder.bind(row, ref(ME, "SET")), source, now());
        assertEquals("a settings change forces a fresh lookup", 2, lookups.get());
        binder.invalidateAll();
        binder.resolve(binder.bind(row, ref(ME, "SET")), source, now());
        assertEquals(3, lookups.get());
    }

    @Test
    public void negativeAnswersExpireAndANewKeepIsSeenImmediately() throws Exception {
        Object row = new Object();
        assertNull(binder.resolve(binder.bind(row, ref(ME, "LATER")), source, now()));
        assertNull(binder.resolve(binder.bind(row, ref(ME, "LATER")), source, now()));
        assertEquals("negative answer cached", 1, lookups.get());
        library.importMedia(request(CHAT, "LATER").build(), source(jpeg(9))); // generation changes
        assertNotNull(binder.resolve(binder.bind(row, ref(ME, "LATER")), source, now()));
        fixture.clock.addAndGet(InlineBinder.NEGATIVE_TTL_MS + 1);
        assertNull(binder.resolve(binder.bind(row, ref(ME, "NEVER")), source, now()));
    }

    @Test
    public void unreachableLibraryIsNeverCached() {
        InlineBinder.Source down = new InlineBinder.Source() {
            @Override
            public String stateToken() {
                return "1:vo1";
            }

            @Override
            public InlineBinder.Answer lookup(InlineBinder.Ref r) {
                lookups.incrementAndGet();
                return null;
            }
        };
        Object row = new Object();
        binder.resolve(binder.bind(row, ref(ME, "X")), down, now());
        binder.resolve(binder.bind(row, ref(ME, "X")), down, now());
        assertEquals(2, lookups.get());
    }

    @Test
    public void generationChangesOnEveryVisibleMutationOnly() throws Exception {
        long g0 = library.generation();
        LibraryItem item = library.importMedia(request(CHAT, "G").build(), source(jpeg(10))).item;
        long g1 = library.generation();
        assertTrue(g1 > g0);
        library.importMedia(request(CHAT, "G").build(), source(jpeg(10)));
        assertEquals("an idempotent re-import changes nothing visible", g1, library.generation());
        List<Long> seen = new ArrayList<>();
        library.trash(item.uuid);
        seen.add(library.generation());
        library.restoreFromTrash(item.uuid);
        seen.add(library.generation());
        library.setQuotaBytes(MediaLibrary.GIB);
        seen.add(library.generation());
        assertTrue(seen.get(0) > g1);
        assertTrue(seen.get(1) > seen.get(0));
        assertEquals("quota does not change what is shown", seen.get(1), seen.get(2));
        assertEquals("generation is persisted across restarts", seen.get(2).longValue(), fixture.open().generation());
    }

    @Test
    public void providerAccessRequiresTheSamePackageAndAccount() throws Exception {
        LibraryItem item = library.importMedia(request(CHAT, "AP").build(), source(jpeg(11))).item;
        assertTrue(AccessPolicy.mayRead(item, false, WA, ME));
        assertTrue("the module itself", AccessPolicy.mayRead(item, true, null, null));
        assertFalse("other account in the same app", AccessPolicy.mayRead(item, false, WA, OTHER_ME));
        assertFalse("no account stated", AccessPolicy.mayRead(item, false, WA, null));
        assertFalse(AccessPolicy.mayRead(item, false, WA, ""));
        assertFalse("other WhatsApp flavour", AccessPolicy.mayRead(item, false, W4B, ME));
        assertFalse("unknown caller", AccessPolicy.mayRead(item, false, null, ME));
        assertFalse(AccessPolicy.mayRead(null, true, WA, ME));
    }
}
