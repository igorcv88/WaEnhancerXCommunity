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
import com.waenhancer.library.core.InlinePresentation;
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
    private static final Uri PROVIDER = Uri.parse("content://" + AUTHORITY);
    private static final String GALLERY_ALIAS = "com.waenhancer.library.ui.ChatMediaGallery";
    private static final String TAG_OVERLAY = "waex_library_overlay";
    private static final String TAG_CHIP = "waex_library_chip";
    private static final long NEGATIVE_TTL_MS = 60_000L;
    private static final String TAG_CLICK = "waex_library_click";
    private static final int SWEEP_LIMIT = 300;

    /** Owner switch "Keep received view-once" (also enforced again by the module). */
    private static volatile boolean keepViewOnce;
    /** Messages already sent for automatic keeping in this process. */
    private static final java.util.Set<String> AUTO_ATTEMPTED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /** Messages whose file was already restored into WhatsApp in this process. */
    private static final java.util.Set<String> RESTORE_ATTEMPTED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "waex-media-library");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** message identity → uuid ("" = none, with time of the negative answer). */
    private static final LruCache<String, Lookup> LOOKUPS = new LruCache<>(512);
    private static final LruCache<String, Bitmap> THUMBNAILS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final class Lookup {
        final String uuid;
        final long at;

        Lookup(String uuid, long at) {
            this.uuid = uuid;
            this.at = at;
        }
    }

    public MediaLibraryHook(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("media_library", false)) return;
        keepViewOnce = prefs.getBoolean("media_library_keep_view_once", false);
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
            LOOKUPS.remove(lookupKey(key));
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
                String identity = chat + '|' + keyId + '|' + false;
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
        LOOKUPS.remove(lookupKey(key));
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
        String own = ownJid();
        if (own == null) {
            toast(R.string.media_library_no_account, "Account could not be identified");
            return;
        }
        Bundle extras = new Bundle();
        extras.putString("own_jid", own);
        String primary = chat.getUserRawString() != null ? chat.getUserRawString() : chat.getPhoneRawString();
        extras.putString("chat_jid", primary);
        String alt = chat.getPhoneRawString();
        if (alt != null && !alt.equals(primary)) extras.putString("chat_alt_jid", alt);
        if (openUuid != null) extras.putString("open_uuid", openUuid);
        try {
            String title = WppCore.getContactName(chat);
            if (title != null) extras.putString("title", title);
        } catch (Throwable ignored) {
        }
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
        if (!message.isMediaFile()) return;
        FMessageWpp.Key key = message.getKey();
        if (key == null || key.messageID == null) return;
        if (!key.isFromMe && key.remoteJid != null && key.remoteJid.isStatus()) return;
        boolean receivedViewOnce = isReceivedViewOnce(message, key);
        // Without the owner's switch a received view-once keeps WhatsApp's behaviour entirely.
        if (receivedViewOnce && !keepViewOnce) return;
        String identity = lookupKey(key);
        Object token = message.getObject();
        XposedHelpers.setAdditionalInstanceField(row, TAG_OVERLAY + "_msg", token);
        Context context = row.getContext();
        WORKER.execute(() -> {
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
            String uuid = lookup(context, key, identity);
            if (!InlinePresentation.shouldPresent(true, uuid != null, false, false)) return;
            // Normal media: put the kept bytes back where WhatsApp expects them, so its own
            // bubble, viewer and "Media, links and docs" work again; msgstore.db is untouched.
            if (!message.isViewOnce() && restoreIntoWhatsApp(context, message, nativeFile, uuid, identity)) {
                ConversationItemListener.notifyDataSetChanged();
                return;
            }
            Bitmap bitmap = thumbnail(context, uuid);
            if (bitmap == null) return;
            boolean viewOnce = message.isViewOnce();
            MAIN.post(() -> {
                // The row may have been recycled for another message meanwhile.
                if (XposedHelpers.getAdditionalInstanceField(row, TAG_OVERLAY + "_msg") != token) return;
                attachOverlay(row, bitmap, uuid, viewOnce);
            });
        });
    }

    /**
     * Writes a kept conventional copy to the exact path WhatsApp records for the message, only
     * inside WhatsApp's own media roots, never over an existing file, never for view-once.
     */
    private static boolean restoreIntoWhatsApp(Context context, FMessageWpp message, File recorded, String uuid,
                                               String identity) {
        if (!RESTORE_ATTEMPTED.add(identity)) return false;
        try {
            File target = recorded;
            List<String> roots = mediaRoots(context);
            if (target == null) {
                String stored = com.waenhancer.xposed.core.db.MessageStore.getInstance().getMediaFromID(message.getRowId());
                List<String> candidates = com.waenhancer.library.core.WhatsAppMediaPaths.candidates(stored, roots);
                if (candidates.isEmpty()) return false;
                target = new File(candidates.get(0));
            }
            String canonical = target.getCanonicalPath();
            if (!com.waenhancer.library.core.WhatsAppMediaPaths.isSafeRestoreTarget(canonical, roots,
                    target.exists(), message.isViewOnce())) {
                return false;
            }
            File parent = target.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
            File temp = new File(parent, "." + target.getName() + ".waex-restore");
            Uri uri = Uri.parse("content://" + AUTHORITY + "/item/" + uuid);
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                if (in == null) return false;
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
                out.getFD().sync();
            }
            if (target.exists() || !temp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return false;
            }
            toast(R.string.media_library_restored_to_whatsapp, "Restored from the media library");
            return true;
        } catch (Throwable failure) {
            XposedBridge.log("[WAEX] MediaLibrary restore into WhatsApp skipped: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static String lookupKey(FMessageWpp.Key key) {
        return raw(key.remoteJid) + '|' + key.messageID + '|' + key.isFromMe;
    }

    private static String lookup(Context context, FMessageWpp.Key key, String identity) {
        Lookup cached = LOOKUPS.get(identity);
        long now = System.currentTimeMillis();
        if (cached != null && (!cached.uuid.isEmpty() || now - cached.at < NEGATIVE_TTL_MS)) {
            return cached.uuid.isEmpty() ? null : cached.uuid;
        }
        String own = ownJid();
        if (own == null) return null;
        Bundle extras = identity(key, own);
        extras.putStringArray("message_keys", new String[] { key.messageID });
        extras.putBooleanArray("from_me", new boolean[] { key.isFromMe });
        Bundle result;
        try {
            result = context.getContentResolver().call(PROVIDER, "lookup", null, extras);
        } catch (Throwable failure) {
            return null;
        }
        String[] uuids = result == null ? null : result.getStringArray("uuids");
        String uuid = uuids != null && uuids.length == 1 ? uuids[0] : "";
        LOOKUPS.put(identity, new Lookup(uuid == null ? "" : uuid, now));
        return uuid == null || uuid.isEmpty() ? null : uuid;
    }

    private static Bitmap thumbnail(Context context, String uuid) {
        Bitmap cached = THUMBNAILS.get(uuid);
        if (cached != null) return cached;
        Uri uri = Uri.parse("content://" + AUTHORITY + "/item/" + uuid);
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
        if (bitmap != null) THUMBNAILS.put(uuid, bitmap);
        return bitmap;
    }

    static int sampleSize(int width, int height, int target) {
        int sample = 1;
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    private static void attachOverlay(ViewGroup row, Bitmap bitmap, String uuid, boolean viewOnce) {
        View thumb = viewOnce ? findViewOnceContainer(row) : findThumbnail(row);
        if (viewOnce && thumb != null) redirectClick(row, thumb, uuid);
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

    /** WhatsApp's view-once bubble body (no image thumbnail exists for it). */
    private static View findViewOnceContainer(ViewGroup row) {
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(row);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view != row && view.getId() != View.NO_ID) {
                try {
                    if (view.getResources().getResourceEntryName(view.getId()).startsWith("view_once_media_container")) {
                        return view;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return null;
    }

    /**
     * On an expired view-once row with a kept copy, a tap opens the copy instead of WhatsApp's
     * "Photo expired" dialog. The original listener is saved and put back when the row is
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

    private static View findThumbnail(ViewGroup row) {
        InlinePresentation.Node selected = InlinePresentation.selectThumbnail(new ViewNode(row), Utils.dipToPixels(72));
        return selected == null ? null : ((ViewNode) selected).view;
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
    private static String ownJid() {
        try {
            FMessageWpp.UserJid me = WppCore.getMyUserJid();
            if (me == null) return null;
            String phone = me.getPhoneRawString();
            return phone != null ? phone : me.getUserRawString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String text(int id, String fallback) {
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
