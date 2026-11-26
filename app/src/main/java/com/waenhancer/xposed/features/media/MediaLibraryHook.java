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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.BuildConfig;
import com.waenhancer.R;
import com.waenhancer.library.core.ImportPolicy;
import com.waenhancer.library.core.InlineBinder;
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
    private static final long NEGATIVE_TTL_MS = 60_000L;
    private static final String TAG_CLICK = "waex_library_click";
    private static final int SWEEP_LIMIT = 300;

    /** Owner switch "Keep received view-once" (also enforced again by the module). */
    private static volatile boolean keepViewOnce;
    /** Separate owner switch "Restore into WhatsApp" (off by default). */
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
    private static final LruCache<String, Bitmap> THUMBNAILS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
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
        restoreToWhatsApp = prefs.getBoolean("media_library_restore_to_whatsapp", false);
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
            MAIN.postDelayed(() -> WORKER.execute(MediaLibraryHook::sweepReceivedViewOnce), 20_000L);
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

    /**
     * Reads (never writes) WhatsApp's database for the newest received view-once photos/videos
     * that still have a local file and keeps them. Schema-specific; any failure only skips the
     * sweep. Logs counts, never content or identifiers.
     */
    private static void sweepReceivedViewOnce() {
        if (!keepViewOnce) return;
        String own = ownJid();
        Context context = Utils.getApplication();
        if (own == null || context == null) return;
        android.database.sqlite.SQLiteDatabase db;
        try {
            db = com.waenhancer.xposed.core.db.MessageStore.getInstance().getDatabase();
        } catch (Throwable unavailable) {
            return;
        }
        if (db == null) return;
        List<String> roots = mediaRoots(context);
        int found = 0;
        int kept = 0;
        try (android.database.Cursor cursor = db.rawQuery("SELECT m.key_id, m.timestamp, m.message_type, mm.file_path, "
                + "j.raw_string FROM message m JOIN message_media mm ON mm.message_row_id = m._id "
                + "JOIN chat c ON c._id = m.chat_row_id JOIN jid j ON j._id = c.jid_row_id "
                + "WHERE m.from_me = 0 AND m.message_type IN (42, 43) AND mm.file_path IS NOT NULL "
                + "ORDER BY m._id DESC LIMIT " + SWEEP_LIMIT, null)) {
            while (cursor.moveToNext()) {
                String keyId = cursor.getString(0);
                String chat = cursor.getString(4);
                if (keyId == null || chat == null) continue;
                String identity = context.getPackageName() + '|' + own + '|' + chat + '|' + keyId + '|' + false;
                if (AUTO_ATTEMPTED.contains(identity)) continue;
                File file = null;
                for (String candidate : com.waenhancer.library.core.WhatsAppMediaPaths.candidates(cursor.getString(3), roots)) {
                    File probe = new File(candidate);
                    if (probe.isFile() && probe.length() > 0) {
                        file = probe;
                        break;
                    }
                }
                if (file == null) continue;
                found++;
                AUTO_ATTEMPTED.add(identity);
                Bundle extras = new Bundle();
                extras.putString("own_jid", own);
                extras.putString("chat_jid", chat);
                extras.putString("message_key", keyId);
                extras.putBoolean("from_me", false);
                extras.putBoolean("view_once", true);
                extras.putBoolean("user_initiated", false);
                extras.putLong("message_timestamp", cursor.getLong(1));
                String status = importFile(file, extras);
                if ("CREATED".equals(status) || "DEDUPLICATED".equals(status) || "REPAIRED".equals(status)) kept++;
                if ("ERROR".equals(status)) AUTO_ATTEMPTED.remove(identity);
            }
        } catch (Throwable failure) {
            XposedBridge.log("[WAEX] MediaLibrary view-once sweep skipped: " + failure.getClass().getSimpleName());
            return;
        }
        XposedBridge.log("[WAEX] MediaLibrary view-once sweep: with_file=" + found + " newly_kept=" + kept);
        if (kept > 0) ConversationItemListener.notifyDataSetChanged();
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
        FMessageWpp.Key key = message.getKey();
        boolean receivedViewOnce = isReceivedViewOnce(message, key);
        Context context = row.getContext();
        WORKER.execute(() -> {
            if (!BINDER.isCurrent(task)) return;
            File nativeFile;
            try {
                nativeFile = message.getMediaFile();
            } catch (Throwable ignored) {
                nativeFile = null;
            }
            boolean nativeAvailable = nativeFile != null && nativeFile.isFile() && nativeFile.length() > 0;
            if (nativeAvailable) {
                // Copy a received view-once now, while WhatsApp still has it.
                if (receivedViewOnce) autoKeep(message);
                return;
            }
            InlineBinder.Answer answer = BINDER.resolve(task, source(context), System.currentTimeMillis());
            if (answer == null || !BINDER.isCurrent(task)) return;
            // Normal media: put the kept bytes back where WhatsApp expects them, so its own
            // bubble, viewer and "Media, links and docs" work again; msgstore.db is untouched.
            if (!message.isViewOnce() && restoreIntoWhatsApp(context, message, nativeFile, task.ref, answer)) {
                ConversationItemListener.notifyDataSetChanged();
                return;
            }
            Bitmap bitmap = thumbnail(context, task.ref, answer);
            if (bitmap == null) return;
            MAIN.post(() -> {
                if (BINDER.isCurrent(task)) attachOverlay(row, bitmap, answer.uuid);
            });
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
     * Optional "Restore into WhatsApp" (separate switch, off by default): writes a kept normal
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
            // this build; that is why the feature is opt-in and off by default.
            WhatsAppRestore.Outcome outcome = WhatsAppRestore.restore(true, message.isViewOnce(), false, target, roots,
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
        if (bitmap != null) THUMBNAILS.put(cacheKey, bitmap);
        return bitmap;
    }

    static int sampleSize(int width, int height, int target) {
        int sample = 1;
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    private static void attachOverlay(ViewGroup row, Bitmap bitmap, String uuid) {
        InlinePresentation.Host host = InlinePresentation.selectHost(new ViewNode(row), Utils.dipToPixels(72));
        View thumb = host == null ? null : ((ViewNode) host.node).view;
        // A tap opens the kept copy, as a tap opens a normal photo; restored on rebind.
        if (thumb != null) redirectClick(row, thumb, uuid);
        if (thumb == null || thumb.getWidth() == 0) return;
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
            chip.setOnClickListener(v -> {
                Activity activity = WppCore.getCurrentConversation();
                FMessageWpp.UserJid chat = WppCore.getCurrentUserJid();
                if (activity != null && chat != null && !chat.isNull()) {
                    WORKER.execute(() -> openGallery(activity, chat, uuid));
                }
            });
            chip.setOnLongClickListener(v -> row.performLongClick());
            parent.addView(chip, params);
        }
    }

    /**
     * On a row whose media WhatsApp no longer has, a tap opens the kept copy (for an expired
     * view-once, instead of WhatsApp's "Photo expired" dialog). The original listener is saved and put back when the row is
     * rebound; if it cannot be read, nothing is replaced (the overlay's ▶ target still works).
     */
    private static void redirectClick(ViewGroup row, View host, String uuid) {
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
        target.setOnClickListener(v -> {
            Activity activity = WppCore.getCurrentConversation();
            FMessageWpp.UserJid chat = WppCore.getCurrentUserJid();
            if (activity != null && chat != null && !chat.isNull()) {
                WORKER.execute(() -> openGallery(activity, chat, uuid));
            }
        });
        XposedHelpers.setAdditionalInstanceField(row, TAG_CLICK, new Object[] { target, original });
    }

    private static void clearOverlay(ViewGroup row) {
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
