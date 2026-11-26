package com.waenhancer.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.BuildConfig;
import com.waenhancer.library.android.GalleryAccess;
import com.waenhancer.library.android.MediaLibraryRuntime;
import com.waenhancer.library.core.ImportRequest;
import com.waenhancer.library.core.LibraryException;
import com.waenhancer.library.core.LibraryItem;
import com.waenhancer.library.core.MediaLibrary;
import com.waenhancer.security.CallerAuthority;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * The media library's only cross-process door. Exported so WhatsApp can reach it; every
 * operation re-checks the calling UID, and the WhatsApp flavour used to scope data is derived
 * from that UID, never from request extras, so WhatsApp and WhatsApp Business can neither read
 * nor write each other's items.
 *
 * <p>Operations: {@code import} (bytes arrive as a read-only file descriptor that WhatsApp opened
 * itself, so the module never needs storage permissions or path trust), {@code lookup} (which
 * of up to 64 visible messages have a kept copy), {@code gallery_token} (a single-use capability
 * for the gallery activity) and read-only {@code openFile} for one item. There is no list,
 * delete or generic query operation.</p>
 */
public final class MediaLibraryProvider extends ContentProvider {
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".medialibrary";
    public static final String METHOD_IMPORT = "import";
    public static final String METHOD_LOOKUP = "lookup";
    public static final String METHOD_GALLERY_TOKEN = "gallery_token";
    public static final String METHOD_STATE = "state";
    public static final String METHOD_LIST_CHAT = "list_chat";
    public static final String METHOD_CONFIRM_ORIGIN = "confirm_origin";
    public static final int MAX_LIST = 2000;
    public static final int MAX_LOOKUP = 64;

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The trusted WhatsApp package of the caller, {@code null} for anyone else. */
    @Nullable
    private static String whatsAppCaller(Context context) {
        if (context == null || !CallerAuthority.isTrustedCaller(context)) return null;
        for (String name : CallerAuthority.packagesFor(context, Binder.getCallingUid())) {
            if (CallerAuthority.PACKAGE_WHATSAPP.equals(name) || CallerAuthority.PACKAGE_WHATSAPP_BUSINESS.equals(name)) {
                return name;
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Context context = getContext();
        String caller = whatsAppCaller(context);
        if (caller == null || extras == null) return null;
        long identity = Binder.clearCallingIdentity();
        try {
            switch (method) {
                case METHOD_IMPORT:
                    return importMedia(context, caller, extras);
                case METHOD_LOOKUP:
                    return lookup(context, caller, extras);
                case METHOD_GALLERY_TOKEN:
                    return galleryToken(caller, extras);
                case METHOD_STATE:
                    return state(context);
                case METHOD_LIST_CHAT:
                    return listChat(context, caller, extras);
                case METHOD_CONFIRM_ORIGIN:
                    return confirmOrigin(context, caller, extras);
                default:
                    return null;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static Bundle importMedia(Context context, String caller, Bundle extras) {
        Bundle result = new Bundle();
        ParcelFileDescriptor media = extras.getParcelable("media");
        try {
            if (media == null) {
                return error(result, LibraryException.Code.INVALID_REQUEST);
            }
            ImportRequest request = ImportRequest.builder()
                    .packageName(caller)
                    .ownJid(extras.getString("own_jid"))
                    .chatJid(extras.getString("chat_jid"))
                    .chatAltJid(extras.getString("chat_alt_jid"))
                    .messageKey(extras.getString("message_key"))
                    .fromMe(extras.getBoolean("from_me"))
                    .senderJid(extras.getString("sender_jid"))
                    .viewOnce(extras.getBoolean("view_once"))
                    .statusBroadcast(extras.getBoolean("status_broadcast"))
                    .userInitiated(extras.getBoolean("user_initiated"))
                    // The module's own switch decides; a caller-supplied flag would be meaningless.
                    .keepReceivedViewOnce(keepReceivedViewOnce(context))
                    .messageTimestamp(extras.getLong("message_timestamp"))
                    .caption(extras.getString("caption"))
                    .build();
            ParcelFileDescriptor descriptor = media;
            MediaLibrary.ImportResult imported = MediaLibraryRuntime.get(context).importMedia(request,
                    () -> new ParcelFileDescriptor.AutoCloseInputStream(descriptor));
            result.putString("status", imported.status.name());
            result.putString("uuid", imported.item.uuid);
            return result;
        } catch (LibraryException refused) {
            return error(result, refused.code);
        } catch (IOException | RuntimeException failure) {
            return error(result, LibraryException.Code.IO);
        } finally {
            if (media != null) {
                try {
                    media.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    static boolean keepReceivedViewOnce(Context context) {
        try {
            android.content.SharedPreferences prefs = com.waenhancer.config.PreferenceStores.publicStore(context);
            return prefs.getBoolean("media_library", false) && prefs.getBoolean("media_library_keep_view_once", true);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static Bundle error(Bundle result, LibraryException.Code code) {
        result.putString("status", "ERROR");
        result.putString("error", code.name());
        return result;
    }

    private static Bundle lookup(Context context, String caller, Bundle extras) {
        String[] keys = extras.getStringArray("message_keys");
        boolean[] fromMe = extras.getBooleanArray("from_me");
        if (keys == null || fromMe == null || keys.length != fromMe.length || keys.length > MAX_LOOKUP) return null;
        String ownJid = extras.getString("own_jid");
        String chat = extras.getString("chat_jid");
        String alt = extras.getString("chat_alt_jid");
        if (ownJid == null || ownJid.isEmpty()) return null; // account is mandatory
        String[] uuids = new String[keys.length];
        String[] kinds = new String[keys.length];
        String[] hashes = new String[keys.length];
        String state;
        try {
            MediaLibrary library = MediaLibraryRuntime.get(context);
            state = stateToken(context, library);
            for (int i = 0; i < keys.length; i++) {
                LibraryItem item = library.lookup(caller, ownJid, chat, alt, keys[i], fromMe[i]);
                boolean usable = item != null && item.isAvailable();
                uuids[i] = usable ? item.uuid : "";
                kinds[i] = usable ? item.mediaKind : "";
                hashes[i] = usable ? item.sha256 : "";
            }
        } catch (IOException | RuntimeException failure) {
            return null;
        }
        Bundle result = new Bundle();
        result.putStringArray("uuids", uuids);
        result.putStringArray("kinds", kinds);
        result.putStringArray("sha256", hashes);
        result.putString("state", state);
        return result;
    }

    /**
     * Kept items of one conversation of the caller's package and stated account, split by origin
     * (view-once or normal) for WhatsApp's media screen. Newest first, at most {@link #MAX_LIST}.
     */
    private static Bundle listChat(Context context, String caller, Bundle extras) {
        String ownJid = extras.getString("own_jid");
        String chat = extras.getString("chat_jid");
        if (ownJid == null || ownJid.isEmpty() || chat == null || chat.isEmpty()) return null;
        try {
            MediaLibrary library = MediaLibraryRuntime.get(context);
            java.util.List<LibraryItem> items = library.listChatByOrigin(caller, ownJid,
                    java.util.Arrays.asList(chat, extras.getString("chat_alt_jid")), extras.getBoolean("view_once"));
            java.util.ArrayList<String> uuids = new java.util.ArrayList<>();
            java.util.ArrayList<String> hashes = new java.util.ArrayList<>();
            java.util.ArrayList<String> kinds = new java.util.ArrayList<>();
            for (LibraryItem item : items) {
                if (!item.isAvailable()) continue;
                if (uuids.size() >= MAX_LIST) break;
                uuids.add(item.uuid);
                hashes.add(item.sha256);
                kinds.add(item.mediaKind);
            }
            Bundle result = new Bundle();
            result.putStringArrayList("uuids", uuids);
            result.putStringArrayList("sha256", hashes);
            result.putStringArrayList("kinds", kinds);
            result.putString("state", stateToken(context, library));
            return result;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /** WhatsApp reports whether a message is view-once; only rows with an unknown origin change. */
    private static Bundle confirmOrigin(Context context, String caller, Bundle extras) {
        String ownJid = extras.getString("own_jid");
        String chat = extras.getString("chat_jid");
        String key = extras.getString("message_key");
        if (ownJid == null || ownJid.isEmpty() || chat == null || key == null) return null;
        try {
            boolean changed = MediaLibraryRuntime.get(context).confirmOrigin(caller, ownJid, chat,
                    extras.getString("chat_alt_jid"), key, extras.getBoolean("from_me"), extras.getBoolean("view_once"));
            Bundle result = new Bundle();
            result.putBoolean("changed", changed);
            return result;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /** Library generation + the module settings that change what WhatsApp should present. */
    static String stateToken(Context context, MediaLibrary library) {
        return library.generation() + ":" + (keepReceivedViewOnce(context) ? "vo1" : "vo0");
    }

    private static Bundle state(Context context) {
        try {
            Bundle result = new Bundle();
            result.putString("state", stateToken(context, MediaLibraryRuntime.get(context)));
            return result;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static Bundle galleryToken(String caller, Bundle extras) {
        String ownJid = extras.getString("own_jid");
        String chat = extras.getString("chat_jid");
        if (ownJid == null || ownJid.isEmpty() || chat == null || chat.isEmpty()) return null;
        String title = extras.getString("title");
        if (title != null && title.length() > 128) title = title.substring(0, 128);
        Bundle result = new Bundle();
        result.putString("token", GalleryAccess.issue(new GalleryAccess.Grant(caller, ownJid, chat,
                extras.getString("chat_alt_jid"), title, extras.getString("open_uuid"), System.currentTimeMillis())));
        return result;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        Context context = getContext();
        boolean self = Binder.getCallingUid() == Process.myUid();
        String caller = self ? null : whatsAppCaller(context);
        if (!"r".equals(mode) || (!self && caller == null)) throw new FileNotFoundException();
        java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !"item".equals(segments.get(0))) throw new FileNotFoundException();
        long identity = Binder.clearCallingIdentity();
        try {
            MediaLibrary library = MediaLibraryRuntime.get(context);
            LibraryItem item = library.get(segments.get(1));
            // Isolation by WhatsApp package (from the UID) and by the active account it states.
            if (item == null || !item.isAvailable()
                    || !com.waenhancer.library.core.AccessPolicy.mayRead(item, self, caller, uri.getQueryParameter("account"))) {
                throw new FileNotFoundException();
            }
            return ParcelFileDescriptor.open(library.fileOf(item), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException failure) {
            throw new FileNotFoundException();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public static Uri itemUri(String uuid) {
        return Uri.parse("content://" + AUTHORITY + "/item/" + uuid);
    }

    /** URI WhatsApp uses: the active account is part of every access. */
    public static Uri itemUri(String uuid, String account) {
        return itemUri(uuid).buildUpon().appendQueryParameter("account", account).build();
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
