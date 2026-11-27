package com.waenhancer.xposed.features.media;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.BuildConfig;
import com.waenhancer.R;
import com.waenhancer.library.core.ImportPolicy;
import com.waenhancer.library.core.InitialImport;
import com.waenhancer.library.core.InlineBinder;
import com.waenhancer.library.core.MediaLibrary;
import com.waenhancer.library.core.InlinePresentation;
import com.waenhancer.library.core.WhatsAppRestore;
import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.FeatureLoader;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.devkit.Beta10Resolvers;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.features.listeners.ConversationItemListener;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.xposed.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * WhatsApp side of the persistent media library.
 *
 * <ul>
 *   <li>Media viewer menu: <b>Keep in library</b>, an explicit per-item user action. The file is
 *   opened here (WhatsApp owns it) and only its read-only descriptor crosses to the module.</li>
 *   <li>Conversation menu: <b>Media library</b> opens the module's private gallery for this chat.</li>
 *   <li>Rows: when WhatsApp no longer has a message's file but the library holds an intact copy,
 *   the copy is drawn as a {@link android.view.ViewOverlay} on WhatsApp's own thumbnail view.
 *   Nothing in the row (type, layout, background, listeners) is changed, so touch, scrolling,
 *   recycling and the Liquid Glass bubble renderer keep their native behaviour; the overlay is
 *   removed when the row is rebound.</li>
 * </ul>
 *
 * <p>Received view-once media and other people's status posts are excluded everywhere: they get
 * neither the menu item nor an overlay, and the module refuses them independently.</p>
 */
public class MediaLibraryHook extends Feature {
    private static final int MENU_ID_KEEP = 0x7EAD0010;
    private static final int MENU_ID_GALLERY = 0x7EAD0011;
    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".medialibrary";
    static final Uri PROVIDER = Uri.parse("content://" + AUTHORITY);
    private static final String GALLERY_ALIAS = "com.waenhancer.library.ui.ChatMediaGallery";
    private static final String TAG_OVERLAY = "waex_library_overlay";
    private static final String TAG_CHIP = "waex_library_chip";
    private static final String TAG_BUBBLE = "waex_library_bubble";
    private static final String TAG_SHOWN = "waex_library_shown";
    private static final long NEGATIVE_TTL_MS = 60_000L;
    private static final String TAG_CLICK = "waex_library_click";
    private static final int SWEEP_LIMIT = 300;

    /** Owner switch "Keep received view-once" (also enforced again by the module). */
    private static volatile boolean keepViewOnce;
    /** Separate owner switch "Restore into WhatsApp" (on by default; owner decision 2026-11-26). */
    private static volatile boolean restoreToWhatsApp;
    /** Messages already sent for automatic keeping in this process. */
    private static final java.util.Set<String> AUTO_ATTEMPTED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /** Messages whose file was already restored into WhatsApp in this process. */
    private static final java.util.Set<String> RESTORE_ATTEMPTED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "waex-media-library");
        thread.setDaemon(true);
        return thread;
    });
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Row tokens + account-scoped, state-keyed lookup cache (see {@link InlineBinder}). */
    private static final InlineBinder<ViewGroup> BINDER = new InlineBinder<>();
    /** Keyed by package + account + item + content hash ({@link InlineBinder#thumbnailKey}). */
    /** Decoded copies; an eighth of the heap (at least 12 MiB), so rows can be presented again synchronously. */
    private static final LruCache<String, Bitmap> THUMBNAILS = new LruCache<String, Bitmap>(
            (int) Math.max(12L * 1024 * 1024, Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 8))) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public MediaLibraryHook(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("media_library", false)) return;
        keepViewOnce = prefs.getBoolean("media_library_keep_view_once", true);
        restoreToWhatsApp = prefs.getBoolean("media_library_restore_to_whatsapp", true);
        // Clean temporary/partial files of a restore interrupted by a killed process.
        WORKER.execute(() -> {
            try {
                Context app = Utils.getApplication();
                if (app == null) return;
                WhatsAppRestore.Recovery recovery = WhatsAppRestore.recover(restoreJournal(app));
                if (recovery.journals > 0) {
                    XposedBridge.log("[WAEX] MediaLibrary restore recovery: journals=" + recovery.journals
                            + " temps=" + recovery.tempsRemoved + " partial=" + recovery.partialTargetsRemoved
                            + " kept=" + recovery.completeTargetsKept);
                }
            } catch (Throwable ignored) {
            }
        });
        // Each part fails closed on its own; one unresolved hook never disables the others.
        try {
            hookMediaViewerMenu();
        } catch (Throwable failure) {
            XposedBridge.log("[WAEX] MediaLibrary viewer menu unavailable: " + failure);
        }
        try {
            hookConversationMenu();
        } catch (Throwable failure) {
            XposedBridge.log("[WAEX] MediaLibrary conversation menu unavailable: " + failure);
        }
        ConversationItemListener.conversationListeners.add(new ConversationItemListener.OnConversationItemListener() {
            @Override
            public void onItemBind(FMessageWpp fMessage, ViewGroup row) {
                bindRow(fMessage, row);
            }
        });
        try {
            ViewOnceGalleryTab.install();
        } catch (Throwable failure) {
            XposedBridge.log("[WAEX] MediaLibrary view-once tab unavailable: " + failure);
        }
        if (keepViewOnce) {
            try {
                hookViewOnceViewer();
            } catch (Throwable failure) {
                XposedBridge.log("[WAEX] MediaLibrary view-once viewer trigger unavailable: " + failure);
            }
            // Received view-once media must be copied before WhatsApp expires it, including
            // chats the user does not open: one bounded pass over WhatsApp's own database.
            MAIN.postDelayed(() -> WORKER.execute(MediaLibraryHook::runViewOnceImport), 20_000L);
        }
    }

    // ------------------------------------------------------------------ automatic view-once keep

    private static boolean isReceivedViewOnce(FMessageWpp message, FMessageWpp.Key key) {
        return key != null && !key.isFromMe && message.isViewOnce();
    }

    /** Silent automatic copy of a received view-once whose file WhatsApp still has. */
    private static void autoKeep(FMessageWpp message) {
        FMessageWpp.Key key = message.getKey();
        if (!keepViewOnce || !isReceivedViewOnce(message, key) || key.messageID == null) return;
        if (!AUTO_ATTEMPTED.add(lookupKey(key))) return;
        File file = message.getMediaFile();
        if (file == null || !file.isFile() || file.length() == 0) {
            AUTO_ATTEMPTED.remove(lookupKey(key)); // try again once WhatsApp has the file
            return;
        }
        String own = ownJid();
        if (own == null) {
            AUTO_ATTEMPTED.remove(lookupKey(key));
            return;
        }
        Bundle extras = identity(key, own);
        extras.putBoolean("view_once", true);
        extras.putBoolean("user_initiated", false);
        extras.putLong("message_timestamp", message.getTimestamp());
        if (key.participant != null) extras.putString("sender_jid", raw(key.participant));
        String status = importFile(file, extras);
        if ("CREATED".equals(status) || "DEDUPLICATED".equals(status) || "REPAIRED".equals(status)) {
            forget(key);
        } else if ("ERROR".equals(status)) {
            AUTO_ATTEMPTED.remove(lookupKey(key));
        }
    }

    /** The view-once viewer: the file certainly exists while it is shown. */
    private void hookViewOnceViewer() throws Exception {
        XposedHelpers.findAndHookMethod(WppCore.getViewOnceViewerActivityClass(classLoader), "onCreateOptionsMenu",
                Menu.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            var field = ReflectionUtils.getFieldByType(param.thisObject.getClass(), FMessageWpp.Key.TYPE);
                            Object rawKey = ReflectionUtils.getObjectField(field, param.thisObject);
                            if (rawKey == null) return;
                            FMessageWpp message = new FMessageWpp.Key(rawKey).getFMessage();
                            if (message != null) WORKER.execute(() -> autoKeep(message));
                        } catch (Throwable ignored) {
                            // Fail closed: the row and sweep triggers still apply.
                        }
                    }
                });
    }

    private static final long SWEEP_INTERVAL_MS = 10L * 60L * 1000L;
    private static final int SWEEP_PAGE = 100;

    /**
     * First-run and incremental import of every view-once photo/video WhatsApp still has
     * ({@link InitialImport}): the first run for an account imports all of them, oldest first and
     * resumable; later runs only take newer messages. Reads WhatsApp's database, never writes it.
     */
    private static void runViewOnceImport() {
        try {
            if (!keepViewOnce) return;
            String own = ownJid();
            Context context = Utils.getApplication();
            if (own == null || context == null) return;
            android.database.sqlite.SQLiteDatabase db = com.waenhancer.xposed.core.db.MessageStore.getInstance().getDatabase();
            if (db == null) return;
            List<String> roots = mediaRoots(context);
            InitialImport.Report report = InitialImport.run(new WhatsAppViewOnceCandidates(db, roots),
                    candidate -> importCandidate(own, candidate), sweepProgress(context, own), SWEEP_PAGE, () -> false);
            XposedBridge.log("[WAEX] MediaLibrary view-once import: initial=" + report.initialRun + " completed="
                    + report.completed + " scanned=" + report.scanned + " imported=" + report.imported + " present="
                    + report.alreadyPresent + " without_file=" + report.withoutFile + " refused=" + report.refused
                    + " ambiguous=" + report.ambiguous + " failed=" + report.failed + " reset=" + report.watermarkReset);
            if (report.imported > 0) ConversationItemListener.notifyDataSetChanged();
            if (report.initialRun && report.completed && report.imported > 0) {
                Utils.showToast(String.format(java.util.Locale.ROOT, text(R.string.media_library_initial_import_done,
                        "Media library: %1$d view-once items kept"), report.imported), Toast.LENGTH_LONG);
            }
        } catch (Throwable failure) {
            XposedBridge.log("[WAEX] MediaLibrary view-once import skipped: " + failure.getClass().getSimpleName());
        } finally {
            MAIN.postDelayed(() -> WORKER.execute(MediaLibraryHook::runViewOnceImport), SWEEP_INTERVAL_MS);
        }
    }

    private static InitialImport.Settled importCandidate(String own, InitialImport.Candidate candidate) throws java.io.IOException {
        Bundle extras = new Bundle();
        extras.putString("own_jid", own);
        extras.putString("chat_jid", candidate.chatJid);
        if (candidate.chatAltJid != null) extras.putString("chat_alt_jid", candidate.chatAltJid);
        extras.putString("message_key", candidate.messageKey);
        extras.putBoolean("from_me", candidate.fromMe);
        extras.putBoolean("view_once", true);
        extras.putBoolean("user_initiated", false);
        extras.putLong("message_timestamp", candidate.timestamp);
        Bundle result = importCall(candidate.file, extras);
        String status = result == null ? "ERROR" : result.getString("status", "ERROR");
        String error = result == null ? "IO" : result.getString("error", "IO");
        switch (status) {
            case "CREATED":
            case "DEDUPLICATED":
            case "REPAIRED":
            case "RESTORED_FROM_TRASH":
                return InitialImport.Settled.IMPORTED;
            case "ALREADY_PRESENT":
                return InitialImport.Settled.ALREADY_PRESENT;
            default:
                switch (error) {
                    case "AMBIGUOUS": return InitialImport.Settled.AMBIGUOUS;
                    case "NOT_AUTHORIZED":
                    case "INCOMPLETE_MEDIA":
                    case "UNSUPPORTED_MEDIA":
                    case "TOO_LARGE":
                    case "INVALID_REQUEST": return InitialImport.Settled.REFUSED;
                    default: throw new java.io.IOException("retry later: " + error); // busy, full, unreachable
                }
        }
    }

    private static InitialImport.Progress sweepProgress(Context context, String own) {
        return new InitialImport.Progress() {
            @Override
            public MediaLibrary.SweepState load() throws java.io.IOException {
                return sweepCall(context, own, null);
            }

            @Override
            public void save(MediaLibrary.SweepState state) throws java.io.IOException {
                sweepCall(context, own, state);
            }
        };
    }

    private static MediaLibrary.SweepState sweepCall(Context context, String own, MediaLibrary.SweepState save)
            throws java.io.IOException {
        Bundle extras = new Bundle();
        extras.putString("own_jid", own);
        if (save != null) {
            extras.putLong("watermark", save.watermark);
            extras.putBoolean("initial_done", save.initialDone);
            extras.putLong("imported", save.imported);
        }
        Bundle result;
        try {
            result = context.getContentResolver().call(PROVIDER, save == null ? "sweep_state" : "sweep_save", null, extras);
        } catch (Throwable failure) {
            result = null;
        }
        if (result == null) throw new java.io.IOException("library unreachable");
        return new MediaLibrary.SweepState(result.getLong("watermark"), result.getBoolean("initial_done"),
                result.getLong("imported"));
    }

    /** View-once rows of WhatsApp's database (read-only), with the phone JID of a LID chat when known. */
    private static final class WhatsAppViewOnceCandidates implements InitialImport.Candidates {
        private static final String WITH_MAP = "SELECT m._id, m.key_id, m.timestamp, m.from_me, mm.file_path, "
                + "j.raw_string AS chat, pj.raw_string AS alt FROM message m "
                + "LEFT JOIN message_media mm ON mm.message_row_id = m._id "
                + "JOIN chat c ON c._id = m.chat_row_id JOIN jid j ON j._id = c.jid_row_id "
                + "LEFT JOIN jid_map jm ON jm.lid_row_id = j._id LEFT JOIN jid pj ON pj._id = jm.jid_row_id "
                + "WHERE m.message_type IN (42, 43) AND m._id > ? ORDER BY m._id LIMIT ?";
        private static final String PLAIN = "SELECT m._id, m.key_id, m.timestamp, m.from_me, mm.file_path, "
                + "j.raw_string AS chat, NULL AS alt FROM message m "
                + "LEFT JOIN message_media mm ON mm.message_row_id = m._id "
                + "JOIN chat c ON c._id = m.chat_row_id JOIN jid j ON j._id = c.jid_row_id "
                + "WHERE m.message_type IN (42, 43) AND m._id > ? ORDER BY m._id LIMIT ?";
        private final android.database.sqlite.SQLiteDatabase db;
        private final List<String> roots;
        private boolean mapUnavailable;

        WhatsAppViewOnceCandidates(android.database.sqlite.SQLiteDatabase db, List<String> roots) {
            this.db = db;
            this.roots = roots;
        }

        @Override
        public List<InitialImport.Candidate> page(long afterRowId, int limit) {
            String[] args = { String.valueOf(afterRowId), String.valueOf(limit) };
            android.database.Cursor cursor;
            if (!mapUnavailable) {
                try {
                    cursor = db.rawQuery(WITH_MAP, args);
                } catch (RuntimeException noJidMap) {
                    mapUnavailable = true; // older schema: no jid_map table
                    cursor = db.rawQuery(PLAIN, args);
                }
            } else {
                cursor = db.rawQuery(PLAIN, args);
            }
            List<InitialImport.Candidate> page = new ArrayList<>();
            try (android.database.Cursor rows = cursor) {
                while (rows.moveToNext()) {
                    String key = rows.getString(1);
                    String chat = rows.getString(5);
                    if (key == null || chat == null) continue;
                    File file = null;
                    for (String path : com.waenhancer.library.core.WhatsAppMediaPaths.candidates(rows.getString(4), roots)) {
                        File probe = new File(path);
                        if (probe.isFile() && probe.length() > 0) {
                            file = probe;
                            break;
                        }
                    }
                    String alt = rows.getString(6);
                    page.add(new InitialImport.Candidate(rows.getLong(0), chat,
                            alt != null && !alt.equals(chat) ? alt : null, key, rows.getInt(3) != 0, rows.getLong(2), file));
                }
            }
            return page;
        }

        @Override
        public long maxRowId() {
            try (android.database.Cursor cursor = db.rawQuery("SELECT MAX(_id) FROM message", null)) {
                return cursor.moveToFirst() ? cursor.getLong(0) : 0;
            }
        }
    }

    /** WhatsApp's own media roots (its scoped external media dir and its private data). */
    private static List<String> mediaRoots(Context context) {
        List<String> roots = new ArrayList<>();
        String pkg = context.getPackageName();
        File external = android.os.Environment.getExternalStorageDirectory();
        for (File root : new File[] {
                new File(external, "Android/media/" + pkg + "/WhatsApp"),
                new File(external, "Android/media/" + pkg + "/WhatsApp Business"),
                new File(external, "Android/media/" + pkg),
                context.getFilesDir(),
                context.getDataDir() }) {
            try {
                if (root != null) roots.add(root.getCanonicalPath());
            } catch (java.io.IOException ignored) {
            }
        }
        return roots;
    }

    // ------------------------------------------------------------------ explicit import

    private void hookMediaViewerMenu() throws Exception {
        var menuMethod = Beta10Resolvers.loadViewOnceDownloadMenuMethod(classLoader);
        XposedBridge.hookMethod(menuMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Menu menu = ReflectionUtils.getArg(param.args, Menu.class, 0);
                if (menu == null || menu.findItem(MENU_ID_KEEP) != null) return;
                FMessageWpp message = messageOf(param.thisObject, param.args);
                if (message == null || !message.isMediaFile() || !eligible(message)) return;
                diagnosticTriggered();
                MenuItem item = menu.add(0, MENU_ID_KEEP, 0, text(R.string.media_library_keep, "Keep in library"));
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                item.setOnMenuItemClickListener(clicked -> {
                    WORKER.execute(() -> keep(message));
                    return true;
                });
            }
        });
    }

    private static FMessageWpp messageOf(Object owner, Object[] args) throws Exception {
        Object raw = ReflectionUtils.getArg(args, FMessageWpp.TYPE, 0);
        if (raw == null && owner != null) {
            for (var field : ReflectionUtils.getFieldsByExtendType(owner.getClass(), FMessageWpp.TYPE)) {
                raw = field.get(owner);
                if (raw != null) break;
            }
        }
        if (raw == null && owner != null) {
            for (var field : ReflectionUtils.getFieldsByExtendType(owner.getClass(), FMessageWpp.Key.TYPE)) {
                Object key = field.get(owner);
                if (key != null && (raw = WppCore.getFMessageFromKey(key)) != null) break;
            }
        }
        return raw == null ? null : new FMessageWpp(raw);
    }

    /** Mirror of {@link ImportPolicy}; the module enforces it again with its own switch. */
    private static boolean eligible(FMessageWpp message) {
        FMessageWpp.Key key = message.getKey();
        if (key == null) return false;
        boolean status = key.remoteJid != null && key.remoteJid.isStatus();
        try {
            ImportPolicy.authorize(new ImportPolicy.Facts(key.isFromMe, message.isViewOnce(), status, true, keepViewOnce));
            return true;
        } catch (Exception refused) {
            return false;
        }
    }

    private void keep(FMessageWpp message) {
        Context context = Utils.getApplication();
        FMessageWpp.Key key = message.getKey();
        File file = message.getMediaFile();
        if (key == null || file == null || !file.isFile() || file.length() == 0) {
            toast(R.string.media_library_no_file, "Download the media first");
            return;
        }
        String own = ownJid();
        if (own == null) {
            toast(R.string.media_library_no_account, "Account could not be identified");
            return;
        }
        Bundle extras = identity(key, own);
        extras.putBoolean("view_once", message.isViewOnce());
        extras.putBoolean("status_broadcast", key.remoteJid != null && key.remoteJid.isStatus());
        extras.putBoolean("user_initiated", true);
        extras.putLong("message_timestamp", message.getTimestamp());
        if (key.participant != null) extras.putString("sender_jid", raw(key.participant));
        try {
            String caption = message.getMessageStr();
            if (caption != null && !caption.isEmpty()) extras.putString("caption", caption);
        } catch (Throwable ignored) {
        }
        Bundle result = importCall(file, extras);
        String status = result == null ? "ERROR" : result.getString("status", "ERROR");
        String error = result == null ? "IO" : result.getString("error", "");
        forget(key);
        switch (status) {
            case "CREATED":
            case "DEDUPLICATED":
            case "RESTORED_FROM_TRASH":
                toast(R.string.media_library_kept, "Kept in the media library");
                ConversationItemListener.notifyDataSetChanged();
                break;
            case "ALREADY_PRESENT":
                toast(R.string.media_library_already_kept, "Already in the media library");
                break;
            case "REPAIRED":
                toast(R.string.media_library_repaired, "Kept copy repaired");
                break;
            default:
                if ("NOT_AUTHORIZED".equals(error)) toast(R.string.media_library_not_allowed, "Not allowed");
                else if ("QUOTA_EXCEEDED".equals(error) || "INSUFFICIENT_STORAGE".equals(error)) {
                    toast(R.string.media_library_full, "The media library is full");
                } else toast(R.string.media_library_failed, "Could not keep this media");
        }
    }

    /** Sends a WhatsApp-opened read-only descriptor to the module; returns the status string. */
    private static String importFile(File file, Bundle extras) {
        Bundle result = importCall(file, extras);
        return result == null ? "ERROR" : result.getString("status", "ERROR");
    }

    private static Bundle importCall(File file, Bundle extras) {
        Context context = Utils.getApplication();
        if (context == null) return null;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            extras.putParcelable("media", descriptor);
            return context.getContentResolver().call(PROVIDER, "import", null, extras);
        } catch (Throwable failure) {
            XposedBridge.log("[WAEX] MediaLibrary import call failed: " + failure.getClass().getSimpleName());
            return null;
        }
    }

    // ------------------------------------------------------------------ gallery

    private void hookConversationMenu() throws Exception {
        var method = Unobfuscator.loadBlueOnReplayCreateMenuConversationMethod(classLoader);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Menu)) return;
                Menu menu = (Menu) param.args[0];
                if (menu.findItem(MENU_ID_GALLERY) != null) return;
                menu.add(0, MENU_ID_GALLERY, 0, text(R.string.media_library, "Media library"))
                        .setOnMenuItemClickListener(item -> {
                            Activity activity = WppCore.getCurrentConversation();
                            FMessageWpp.UserJid chat = WppCore.getCurrentUserJid();
                            if (activity != null && chat != null && !chat.isNull()) {
                                WORKER.execute(() -> openGallery(activity, chat, null));
                            }
                            return true;
                        });
            }
        });
    }

    private static void openGallery(Activity activity, FMessageWpp.UserJid chat, String openUuid) {
        String primary = chat.getUserRawString() != null ? chat.getUserRawString() : chat.getPhoneRawString();
        String alt = chat.getPhoneRawString();
        String title = null;
        try {
            title = WppCore.getContactName(chat);
        } catch (Throwable ignored) {
        }
        openGallery(activity, primary, alt != null && !alt.equals(primary) ? alt : null, title, openUuid);
    }

    /** Opens the module gallery (or one item) for a chat given by raw JIDs. */
    static void openGallery(Activity activity, String chatJid, String chatAltJid, String title, String openUuid) {
        String own = ownJid();
        if (own == null) {
            toast(R.string.media_library_no_account, "Account could not be identified");
            return;
        }
        Bundle extras = new Bundle();
        extras.putString("own_jid", own);
        extras.putString("chat_jid", chatJid);
        if (chatAltJid != null) extras.putString("chat_alt_jid", chatAltJid);
        if (openUuid != null) extras.putString("open_uuid", openUuid);
        if (title != null) extras.putString("title", title);
        Bundle result;
        try {
            result = activity.getContentResolver().call(PROVIDER, "gallery_token", null, extras);
        } catch (Throwable failure) {
            result = null;
        }
        String token = result == null ? null : result.getString("token");
        if (token == null) {
            toast(R.string.media_library_unavailable, "The media library could not be opened");
            return;
        }
        MAIN.post(() -> {
            try {
                Intent intent = new Intent().setComponent(new ComponentName(BuildConfig.APPLICATION_ID, GALLERY_ALIAS))
                        .putExtra("token", token);
                activity.startActivity(intent);
            } catch (Throwable failure) {
                toast(R.string.media_library_unavailable, "The media library could not be opened");
            }
        });
    }

    // ------------------------------------------------------------------ inline presentation

    private void bindRow(FMessageWpp message, ViewGroup row) {
        clearOverlay(row);
        // Every bind issues a new token, even for a message without media, so work started for
        // the row's previous message can never decorate it.
        InlineBinder.Task<ViewGroup> task = BINDER.bind(row, refFor(message));
        if (task == null) return;
        // A message shown before is presented again within this bind, before WhatsApp measures
        // the row: the row keeps its media size instead of shrinking to the native pill and
        // growing again after the lookup, which moved the list (scroll-to-bottom after sending
        // used the small height). The lookup below still confirms or withdraws it.
        Shown shown = SHOWN.get(task.ref.cacheKey());
        Bitmap cached = shown == null ? null : THUMBNAILS.get(shown.thumbnailKey);
        if (cached != null) attachOverlay(row, cached, shown.uuid, shown.viewOnce, task.ref, shown.thumbnailKey, false);
        FMessageWpp.Key key = message.getKey();
        boolean receivedViewOnce = isReceivedViewOnce(message, key);
        Context context = row.getContext();
        WORKER.execute(() -> {
            if (!BINDER.isCurrent(task)) return;
            // Own media imported before schema 2 may have an unknown view-once flag; settle it once.
            if (key.isFromMe && ORIGIN_CONFIRMED.add(task.ref.scope() + '|' + task.ref.chatJid + '|' + task.ref.messageKey)) {
                confirmOrigin(context, task.ref, message.isViewOnce());
            }
            File nativeFile;
            try {
                nativeFile = message.getMediaFile();
            } catch (Throwable ignored) {
                nativeFile = null;
            }
            boolean nativeAvailable = nativeFile != null && nativeFile.isFile() && nativeFile.length() > 0;
            boolean viewOnce = message.isViewOnce();
            // Copy a received view-once now, while WhatsApp still has it (autoKeep forgets the
            // row's cached answer, so the lookup below sees the new copy).
            if (nativeAvailable && receivedViewOnce) autoKeep(message);
            // Normal media: native first. View-once: the kept copy is shown whenever it exists.
            if (!InlinePresentation.lookUpKeptCopy(viewOnce, nativeAvailable)) {
                withdraw(task, row);
                return;
            }
            InlineBinder.Answer answer = BINDER.resolve(task, source(context), System.currentTimeMillis());
            if (!InlinePresentation.presentKeptCopy(viewOnce, nativeAvailable, answer != null)
                    || !BINDER.isCurrent(task)) {
                withdraw(task, row);
                return;
            }
            // Normal media: put the kept bytes back where WhatsApp expects them, so its own
            // bubble, viewer and "Media, links and docs" work again; msgstore.db is untouched.
            if (!viewOnce && restoreIntoWhatsApp(context, message, nativeFile, task.ref, answer)) {
                withdraw(task, row);
                ConversationItemListener.notifyDataSetChanged();
                return;
            }
            Bitmap bitmap = thumbnail(context, task.ref, answer);
            if (bitmap == null) {
                withdraw(task, row);
                return;
            }
            MAIN.post(() -> {
                if (!BINDER.isCurrent(task)) return;
                // Already presented synchronously with the same item: leave the row untouched.
                if (answer.uuid.equals(XposedHelpers.getAdditionalInstanceField(row, TAG_SHOWN))) return;
                clearOverlay(row);
                attachOverlay(row, bitmap, answer.uuid, viewOnce, task.ref,
                        InlineBinder.thumbnailKey(task.ref, answer), true);
            });
        });
    }

    /** What a message last showed, to present it again synchronously on the next bind. */
    private static final class Shown {
        /** Key into {@link #THUMBNAILS}; the bitmap is not held here, so memory stays bounded. */
        final String thumbnailKey;
        final String uuid;
        final boolean viewOnce;

        Shown(String thumbnailKey, String uuid, boolean viewOnce) {
            this.thumbnailKey = thumbnailKey;
            this.uuid = uuid;
            this.viewOnce = viewOnce;
        }
    }

    /** Main thread only; keys are {@link InlineBinder.Ref#cacheKey()} (package + account + message). */
    private static final LruCache<String, Shown> SHOWN = new LruCache<>(256);

    /** The lookup no longer supports what the row shows (deleted, restored natively, …): undo it. */
    private static void withdraw(InlineBinder.Task<ViewGroup> task, ViewGroup row) {
        MAIN.post(() -> {
            SHOWN.remove(task.ref.cacheKey());
            if (BINDER.isCurrent(task) && XposedHelpers.getAdditionalInstanceField(row, TAG_SHOWN) != null) {
                clearOverlay(row);
            }
        });
    }

    /** The scoped identity of an eligible media message, or {@code null} (row stays native). */
    private static InlineBinder.Ref refFor(FMessageWpp message) {
        try {
            if (message == null || !message.isMediaFile()) return null;
            FMessageWpp.Key key = message.getKey();
            if (key == null || key.messageID == null) return null;
            if (!key.isFromMe && key.remoteJid != null && key.remoteJid.isStatus()) return null;
            if (isReceivedViewOnce(message, key) && !keepViewOnce) return null;
            return refOf(key);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static InlineBinder.Ref refOf(FMessageWpp.Key key) {
        String own = ownJid();
        Context context = Utils.getApplication();
        if (own == null || context == null || key == null) return null;
        String primary = raw(key.remoteJid);
        if (primary == null) return null;
        String alt = key.remoteJid.getPhoneRawString();
        return new InlineBinder.Ref(context.getPackageName(), own, primary,
                alt != null && !alt.equals(primary) ? alt : null, key.messageID, key.isFromMe);
    }

    private static void forget(FMessageWpp.Key key) {
        InlineBinder.Ref ref = refOf(key);
        if (ref != null) BINDER.forget(ref);
    }

    /** The provider as an {@link InlineBinder.Source}; every call states the active account. */
    private static InlineBinder.Source source(Context context) {
        return new InlineBinder.Source() {
            @Override
            public String stateToken() {
                try {
                    Bundle result = context.getContentResolver().call(PROVIDER, "state", null, new Bundle());
                    return result == null ? null : result.getString("state");
                } catch (Throwable failure) {
                    return null;
                }
            }

            @Override
            public InlineBinder.Answer lookup(InlineBinder.Ref ref) {
                Bundle extras = new Bundle();
                extras.putString("own_jid", ref.ownJid);
                extras.putString("chat_jid", ref.chatJid);
                if (ref.chatAltJid != null) extras.putString("chat_alt_jid", ref.chatAltJid);
                extras.putStringArray("message_keys", new String[] { ref.messageKey });
                extras.putBooleanArray("from_me", new boolean[] { ref.fromMe });
                try {
                    Bundle result = context.getContentResolver().call(PROVIDER, "lookup", null, extras);
                    String[] uuids = result == null ? null : result.getStringArray("uuids");
                    String[] hashes = result == null ? null : result.getStringArray("sha256");
                    if (uuids == null || hashes == null || uuids.length != 1 || hashes.length != 1) return null;
                    return new InlineBinder.Answer(uuids[0], hashes[0]);
                } catch (Throwable failure) {
                    return null;
                }
            }
        };
    }

    private static Uri itemUri(InlineBinder.Ref ref, String uuid) {
        return Uri.parse("content://" + AUTHORITY + "/item/" + uuid).buildUpon()
                .appendQueryParameter("account", ref.ownJid).build();
    }

    /**
     * Optional "Restore into WhatsApp" (separate switch, on by default): writes a kept normal
     * copy back to the path WhatsApp records for the message. Rules in {@link WhatsAppRestore}.
     */
    private static boolean restoreIntoWhatsApp(Context context, FMessageWpp message, File recorded,
                                               InlineBinder.Ref ref, InlineBinder.Answer answer) {
        if (!restoreToWhatsApp) return false;
        if (!RESTORE_ATTEMPTED.add(ref.scope() + '|' + ref.chatJid + '|' + ref.messageKey + '|' + answer.sha256)) return false;
        try {
            File target = recorded;
            List<String> roots = mediaRoots(context);
            if (target == null) {
                String stored = com.waenhancer.xposed.core.db.MessageStore.getInstance().getMediaFromID(message.getRowId());
                List<String> candidates = com.waenhancer.library.core.WhatsAppMediaPaths.candidates(stored, roots);
                if (candidates.isEmpty()) return false;
                target = new File(candidates.get(0));
            }
            Uri uri = itemUri(ref, answer.uuid);
            // userCleared: WhatsApp gives no reliable signal for a deliberate "delete media" on
            // this build; the owner chose on by default and can turn it off.
            WhatsAppRestore.Outcome outcome = WhatsAppRestore.restore(true, message.isViewOnce(), false, target, roots,
                    restoreJournal(context),
                    () -> context.getContentResolver().openInputStream(uri));
            if (outcome == WhatsAppRestore.Outcome.RESTORED) {
                toast(R.string.media_library_restored_to_whatsapp, "Restored from the media library");
                return true;
            }
            return false;
        } catch (Throwable failure) {
            XposedBridge.log("[WAEX] MediaLibrary restore into WhatsApp skipped: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static final java.util.Set<String> ORIGIN_CONFIRMED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static void confirmOrigin(Context context, InlineBinder.Ref ref, boolean viewOnce) {
        Bundle extras = new Bundle();
        extras.putString("own_jid", ref.ownJid);
        extras.putString("chat_jid", ref.chatJid);
        if (ref.chatAltJid != null) extras.putString("chat_alt_jid", ref.chatAltJid);
        extras.putString("message_key", ref.messageKey);
        extras.putBoolean("from_me", ref.fromMe);
        extras.putBoolean("view_once", viewOnce);
        try {
            context.getContentResolver().call(PROVIDER, "confirm_origin", null, extras);
        } catch (Throwable ignored) {
        }
    }

    /** Journal of in-flight restores, in WhatsApp's private files (not the clearable cache). */
    private static File restoreJournal(Context context) {
        return new File(context.getFilesDir(), "waex/restore-journal");
    }

    /** Package + active account + message; used for the per-process attempt sets. */
    private static String lookupKey(FMessageWpp.Key key) {
        Context context = Utils.getApplication();
        return (context == null ? "" : context.getPackageName()) + '|' + ownJid() + '|' + raw(key.remoteJid)
                + '|' + key.messageID + '|' + key.isFromMe;
    }

    static Bitmap thumbnail(Context context, InlineBinder.Ref ref, InlineBinder.Answer answer) {
        String cacheKey = InlineBinder.thumbnailKey(ref, answer);
        Bitmap cached = THUMBNAILS.get(cacheKey);
        if (cached != null) return cached;
        Uri uri = itemUri(ref, answer.uuid);
        Bitmap bitmap = null;
        try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, bounds);
            if (bounds.outWidth > 0) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 720);
                bitmap = BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, options);
            } else {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(fd.getFileDescriptor());
                    bitmap = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720);
                } finally {
                    try {
                        retriever.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable failure) {
            return null;
        }
        if (bitmap != null) {
            // Never more pixels than this image's media bubble shows (center-cropped cover).
            float density = context.getResources().getDisplayMetrics().density;
            int[] box = InlinePresentation.mediaBubbleSize(bitmap.getWidth(), bitmap.getHeight(),
                    Math.round(InlinePresentation.BUBBLE_WIDTH_DP * density), density);
            float scale = box == null ? 1f
                    : InlinePresentation.coverScale(bitmap.getWidth(), bitmap.getHeight(), box[0], box[1]);
            if (scale < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }
            THUMBNAILS.put(cacheKey, bitmap);
        }
        return bitmap;
    }

    static int sampleSize(int width, int height, int target) {
        int sample = 1;
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    /**
     * @param afterLayout {@code true} when the row was already laid out with its native size
     *                    (the asynchronous first presentation), so a size change must keep a list
     *                    that sat at its bottom at the bottom
     */
    private static void attachOverlay(ViewGroup row, Bitmap bitmap, String uuid, boolean viewOnce,
                                      InlineBinder.Ref ref, String thumbnailKey, boolean afterLayout) {
        InlinePresentation.Host host = InlinePresentation.selectHost(new ViewNode(row), Utils.dipToPixels(72), viewOnce);
        View thumb = host == null ? null : ((ViewNode) host.node).view;
        if (thumb == null) return;
        if (host.kind == InlinePresentation.HostKind.VIEW_ONCE_BODY) {
            // The view-once "1 Photo" pill becomes a media bubble showing the copy; a tap opens
            // the copy, never WhatsApp's view-once viewer (which would consume the message).
            if (presentAsMediaBubble(row, thumb, bitmap, afterLayout)) {
                redirectClick(row, thumb, ref, uuid);
                shown(row, ref, thumbnailKey, uuid, viewOnce);
            }
            return;
        }
        if (thumb.getWidth() == 0) return;
        // A tap opens the kept copy, as a tap opens a normal photo; restored on rebind.
        redirectClick(row, thumb, ref, uuid);
        shown(row, ref, thumbnailKey, uuid, viewOnce);
        Drawable overlay = new CopyDrawable(row.getResources(), bitmap,
                text(R.string.media_library_badge, "Library copy"));
        overlay.setBounds(0, 0, thumb.getWidth(), thumb.getHeight());
        thumb.getOverlay().add(overlay);
        XposedHelpers.setAdditionalInstanceField(row, TAG_OVERLAY, new Object[] { thumb, overlay });
        // A tap target is only added where the native container is a FrameLayout, so no native
        // layout is changed; long-press still reaches WhatsApp's own selection.
        if (thumb.getParent() instanceof FrameLayout) {
            FrameLayout parent = (FrameLayout) thumb.getParent();
            TextView chip = new TextView(row.getContext());
            chip.setTag(TAG_CHIP);
            chip.setText("▶");
            chip.setTextColor(Color.WHITE);
            chip.setGravity(Gravity.CENTER);
            chip.setContentDescription(text(R.string.media_library_open, "Open media library"));
            int size = Utils.dipToPixels(44);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
            chip.setOnClickListener(v -> openGalleryForRow(row, ref, uuid));
            chip.setOnLongClickListener(v -> row.performLongClick());
            parent.addView(chip, params);
        }
    }

    private static void shown(ViewGroup row, InlineBinder.Ref ref, String thumbnailKey, String uuid, boolean viewOnce) {
        XposedHelpers.setAdditionalInstanceField(row, TAG_SHOWN, uuid);
        if (ref != null) SHOWN.put(ref.cacheKey(), new Shown(thumbnailKey, uuid, viewOnce));
    }

    /**
     * Turns a view-once body into a media-sized bubble: the body gets a media size
     * ({@link InlinePresentation#mediaBubbleSize}), its children are hidden and the copy is
     * drawn over it with rounded corners and no badge. Everything changed is recorded in
     * {@link BubbleState} and undone by {@link #clearOverlay} on the next bind.
     */
    private static boolean presentAsMediaBubble(ViewGroup row, View body, Bitmap bitmap, boolean afterLayout) {
        ViewGroup.LayoutParams params = body.getLayoutParams();
        if (params == null) return false;
        int rowWidth = row.getWidth() > 0 ? row.getWidth() : row.getResources().getDisplayMetrics().widthPixels;
        int[] size = InlinePresentation.mediaBubbleSize(bitmap.getWidth(), bitmap.getHeight(), rowWidth * 3 / 4,
                row.getResources().getDisplayMetrics().density);
        if (size == null) return false;
        BubbleState state = new BubbleState(body, params.width, params.height, size[0], size[1],
                new RoundedCopyDrawable(bitmap, Utils.dipToPixels(8)));
        if (body instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) body;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                child.setVisibility(View.INVISIBLE);
                state.hidden.add(child);
            }
        }
        // The row grows after WhatsApp already laid out (and possibly scrolled to the bottom of)
        // the list with the pill's height; a list that sat at its bottom stays there.
        AbsListView list = afterLayout ? listOf(row) : null;
        int oldHeight = body.getHeight();
        if (list != null && oldHeight > 0 && size[1] > oldHeight && !list.canScrollVertically(1)) {
            int growth = size[1] - oldHeight;
            list.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    list.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    if (list.canScrollVertically(1)) list.scrollListBy(growth);
                }
            });
        }
        params.width = size[0];
        params.height = size[1];
        body.setLayoutParams(params);
        state.listener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                state.overlay.setBounds(0, 0, right - left, bottom - top);
        body.addOnLayoutChangeListener(state.listener);
        state.overlay.setBounds(0, 0, Math.max(body.getWidth(), 0), Math.max(body.getHeight(), 0));
        body.getOverlay().add(state.overlay);
        XposedHelpers.setAdditionalInstanceField(row, TAG_BUBBLE, state);
        return true;
    }

    private static AbsListView listOf(View row) {
        for (android.view.ViewParent parent = row.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent instanceof AbsListView) return (AbsListView) parent;
        }
        return null;
    }

    /** What {@link #presentAsMediaBubble} changed on a view-once body. */
    private static final class BubbleState {
        final View body;
        final int width, height, appliedWidth, appliedHeight;
        final Drawable overlay;
        final List<View> hidden = new ArrayList<>();
        View.OnLayoutChangeListener listener;

        BubbleState(View body, int width, int height, int appliedWidth, int appliedHeight, Drawable overlay) {
            this.body = body;
            this.width = width;
            this.height = height;
            this.appliedWidth = appliedWidth;
            this.appliedHeight = appliedHeight;
            this.overlay = overlay;
        }

        /**
         * Undo, but only what is still as this module left it: when WhatsApp's own bind has
         * already set a child's visibility or the body's size, its value wins.
         */
        void restore() {
            body.removeOnLayoutChangeListener(listener);
            body.getOverlay().remove(overlay);
            for (View child : hidden) {
                if (child.getVisibility() == View.INVISIBLE) child.setVisibility(View.VISIBLE);
            }
            ViewGroup.LayoutParams params = body.getLayoutParams();
            if (params != null && params.width == appliedWidth && params.height == appliedHeight) {
                params.width = width;
                params.height = height;
                body.setLayoutParams(params);
            }
        }
    }

    /**
     * On a row that shows a kept copy, a tap opens the copy: for normal media whose file is gone,
     * and for every view-once row with a copy (instead of WhatsApp's view-once viewer, which would
     * consume the message, or its "Photo expired" dialog). The original listener is saved and put
     * back when the row is rebound; if it cannot be read, nothing is replaced.
     */
    private static void redirectClick(ViewGroup row, View host, InlineBinder.Ref ref, String uuid) {
        View clickable = host;
        while (clickable != null && clickable != row && !clickable.hasOnClickListeners()) {
            clickable = clickable.getParent() instanceof View ? (View) clickable.getParent() : null;
        }
        if (clickable == null || clickable == row) return;
        Object original;
        try {
            Object info = XposedHelpers.callMethod(clickable, "getListenerInfo");
            original = XposedHelpers.getObjectField(info, "mOnClickListener");
        } catch (Throwable unreadable) {
            return;
        }
        if (!(original instanceof View.OnClickListener)) return;
        View target = clickable;
        target.setOnClickListener(v -> openGalleryForRow(row, ref, uuid));
        XposedHelpers.setAdditionalInstanceField(row, TAG_CLICK, new Object[] { target, original });
    }

    /**
     * Opens the library item of a row in the chat the row's message belongs to. The chat comes
     * from the message key captured at bind time, never from the "current conversation", which on
     * tablets and after onNewIntent can name another chat.
     */
    private static void openGalleryForRow(View row, InlineBinder.Ref ref, String uuid) {
        if (ref == null || ref.chatJid == null || ref.chatJid.isEmpty()) return;
        Context context = row.getContext();
        while (!(context instanceof Activity) && context instanceof android.content.ContextWrapper) {
            Context base = ((android.content.ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        Activity activity = context instanceof Activity ? (Activity) context : WppCore.getCurrentConversation();
        if (activity == null) return;
        WORKER.execute(() -> {
            String title = null;
            try {
                title = WppCore.getContactName(new FMessageWpp.UserJid(ref.chatJid));
            } catch (Throwable ignored) {
            }
            String alt = ref.chatAltJid != null && !ref.chatAltJid.equals(ref.chatJid) ? ref.chatAltJid : null;
            openGallery(activity, ref.chatJid, alt, title, uuid);
        });
    }

    private static void clearOverlay(ViewGroup row) {
        XposedHelpers.removeAdditionalInstanceField(row, TAG_SHOWN);
        Object bubble = XposedHelpers.removeAdditionalInstanceField(row, TAG_BUBBLE);
        if (bubble instanceof BubbleState) ((BubbleState) bubble).restore();
        Object click = XposedHelpers.removeAdditionalInstanceField(row, TAG_CLICK);
        if (click instanceof Object[]) {
            Object[] pair = (Object[]) click;
            ((View) pair[0]).setOnClickListener((View.OnClickListener) pair[1]);
        }
        Object state = XposedHelpers.removeAdditionalInstanceField(row, TAG_OVERLAY);
        if (state instanceof Object[]) {
            Object[] pair = (Object[]) state;
            View thumb = (View) pair[0];
            thumb.getOverlay().remove((Drawable) pair[1]);
            if (thumb.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) thumb.getParent();
                for (int i = parent.getChildCount() - 1; i >= 0; i--) {
                    if (TAG_CHIP.equals(parent.getChildAt(i).getTag())) parent.removeViewAt(i);
                }
            }
        }
    }

    /** Adapts a live view tree to the pure selector. */
    private static final class ViewNode implements InlinePresentation.Node {
        final View view;

        ViewNode(View view) {
            this.view = view;
        }

        @Override
        public String idName() {
            int id = view.getId();
            if (id == View.NO_ID) return null;
            try {
                return view.getResources().getResourceEntryName(id);
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public boolean isImageView() {
            return view instanceof ImageView;
        }

        @Override
        public boolean isVisible() {
            return view.getVisibility() == View.VISIBLE;
        }

        @Override
        public int width() {
            return view.getWidth();
        }

        @Override
        public int height() {
            return view.getHeight();
        }

        @Override
        public List<ViewNode> children() {
            if (!(view instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) view;
            List<ViewNode> result = new ArrayList<>(group.getChildCount());
            for (int i = 0; i < group.getChildCount(); i++) result.add(new ViewNode(group.getChildAt(i)));
            return result;
        }
    }

    /** Center-cropped copy with a small "Library copy" label, drawn inside the thumbnail bounds. */
    private static final class CopyDrawable extends BitmapDrawable {
        private final String label;
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);

        CopyDrawable(android.content.res.Resources resources, Bitmap bitmap, String label) {
            super(resources, bitmap);
            this.label = label;
            text.setColor(Color.WHITE);
            text.setTextSize(Utils.dipToPixels(11));
            pill.setColor(0xB0000000);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Bitmap bitmap = getBitmap();
            android.graphics.Rect bounds = getBounds();
            if (bitmap == null || bounds.isEmpty()) return;
            float scale = Math.max(bounds.width() / (float) bitmap.getWidth(), bounds.height() / (float) bitmap.getHeight());
            float w = bitmap.getWidth() * scale;
            float h = bitmap.getHeight() * scale;
            canvas.save();
            canvas.clipRect(bounds);
            canvas.drawBitmap(bitmap, null, new RectF(bounds.centerX() - w / 2, bounds.centerY() - h / 2,
                    bounds.centerX() + w / 2, bounds.centerY() + h / 2), getPaint());
            float pad = Utils.dipToPixels(6);
            float width = text.measureText(label) + pad * 2;
            float height = text.getTextSize() + pad * 1.4f;
            RectF badge = new RectF(bounds.left + pad, bounds.bottom - pad - height, bounds.left + pad + width,
                    bounds.bottom - pad);
            canvas.drawRoundRect(badge, height / 2, height / 2, pill);
            canvas.drawText(label, badge.left + pad, badge.bottom - pad * 0.9f, text);
            canvas.restore();
        }
    }

    /** The copy center-cropped into the bounds with anti-aliased rounded corners, no badge. */
    private static final class RoundedCopyDrawable extends Drawable {
        private final Bitmap bitmap;
        private final float radius;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final RectF rect = new RectF();

        RoundedCopyDrawable(Bitmap bitmap, float radius) {
            this.bitmap = bitmap;
            this.radius = radius;
            paint.setShader(new android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP));
        }

        @Override
        protected void onBoundsChange(@NonNull android.graphics.Rect bounds) {
            if (bounds.isEmpty()) return;
            float scale = Math.max(bounds.width() / (float) bitmap.getWidth(), bounds.height() / (float) bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(bounds.left + (bounds.width() - bitmap.getWidth() * scale) / 2f,
                    bounds.top + (bounds.height() - bitmap.getHeight() * scale) / 2f);
            paint.getShader().setLocalMatrix(matrix);
            rect.set(bounds);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (getBounds().isEmpty()) return;
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Bundle identity(FMessageWpp.Key key, String own) {
        Bundle extras = new Bundle();
        extras.putString("own_jid", own);
        String primary = raw(key.remoteJid);
        extras.putString("chat_jid", primary);
        String alt = key.remoteJid == null ? null : key.remoteJid.getPhoneRawString();
        if (alt != null && !alt.equals(primary)) extras.putString("chat_alt_jid", alt);
        extras.putString("message_key", key.messageID);
        extras.putBoolean("from_me", key.isFromMe);
        return extras;
    }

    private static String raw(FMessageWpp.UserJid jid) {
        if (jid == null) return null;
        String user = jid.getUserRawString();
        return user != null ? user : jid.getPhoneRawString();
    }

    /** The phone JID of this WhatsApp installation's account; {@code null} fails closed. */
    static String ownJid() {
        try {
            FMessageWpp.UserJid me = WppCore.getMyUserJid();
            if (me == null) return null;
            String phone = me.getPhoneRawString();
            return phone != null ? phone : me.getUserRawString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String text(int id, String fallback) {
        return FeatureLoader.getModuleString(Utils.getApplication(), id, fallback);
    }

    private static void toast(int id, String fallback) {
        Utils.showToast(text(id, fallback), Toast.LENGTH_LONG);
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Media Library";
    }
}
