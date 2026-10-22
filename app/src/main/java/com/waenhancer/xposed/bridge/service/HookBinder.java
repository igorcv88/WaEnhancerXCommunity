package com.waenhancer.xposed.bridge.service;

import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import android.system.OsConstants;

import com.waenhancer.config.PreferenceStores;
import com.waenhancer.config.EmojiFontStore;
import com.waenhancer.security.BridgeFilePolicy;
import com.waenhancer.security.CallerAuthority;
import com.waenhancer.xposed.bridge.WaeIIFace;

import java.io.File;
import java.io.FileDescriptor;
import java.util.List;

public class HookBinder extends WaeIIFace.Stub {
    private static HookBinder mInstance;
    private final Context context;

    private HookBinder(Context context) { this.context = context.getApplicationContext(); }

    public static synchronized HookBinder getInstance(Context context) {
        if (mInstance == null) mInstance = new HookBinder(context);
        return mInstance;
    }

    private void enforceCaller() {
        if (!CallerAuthority.isTrustedCaller(context)) throw new SecurityException("Untrusted bridge caller");
    }

    private BridgeFilePolicy policy() {
        var prefs = PreferenceStores.publicStore(context);
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new BridgeFilePolicy(Environment.getExternalStorageDirectory(),
                new File(prefs.getString("download_local", downloads.getAbsolutePath())),
                new File(downloads, "WaEnhancerCommunity/themes"),
                prefs.getString("wallpaper_file", ""));
    }

    @Override
    public ParcelFileDescriptor openFile(String path, boolean create) throws RemoteException {
        enforceCaller();
        FileDescriptor fd = null;
        ParcelFileDescriptor result = null;
        try {
            BridgeFilePolicy policy = policy();
            File target = policy.file(path, create);
            int access = create ? OsConstants.O_WRONLY | OsConstants.O_CREAT : OsConstants.O_RDONLY;
            // No truncation until the opened descriptor itself has passed confinement checks.
            fd = Os.open(target.getPath(), access | OsConstants.O_NOFOLLOW
                    | OsConstants.O_CLOEXEC | OsConstants.O_NONBLOCK, 0600);
            if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) throw new SecurityException("Not a regular file");
            result = ParcelFileDescriptor.dup(fd);
            String opened = Os.readlink("/proc/self/fd/" + result.getFd());
            if (!target.equals(policy.file(opened, create))) throw new SecurityException("File changed during open");
            if (create) Os.ftruncate(fd, 0);
            ParcelFileDescriptor accepted = result;
            result = null;
            return accepted;
        } catch (SecurityException denied) {
            throw denied;
        } catch (Exception unavailable) {
            return null;
        } finally {
            if (result != null) try { result.close(); } catch (Exception ignored) {}
            if (fd != null) try { Os.close(fd); } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean createDir(String path) throws RemoteException {
        enforceCaller();
        try {
            BridgeFilePolicy policy = policy();
            File directory = policy.directory(path);
            boolean available = directory.isDirectory() || directory.mkdirs();
            return available && directory.equals(policy.directory(directory.getPath()));
        } catch (SecurityException denied) {
            throw denied;
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Override
    public List listFiles(String path) throws RemoteException {
        enforceCaller();
        // There are no file-bridge enumeration consumers. Retain the ABI but grant no capability.
        throw new SecurityException("File enumeration is not a bridge capability");
    }

    @Override
    public ParcelFileDescriptor openEmojiFont() throws RemoteException {
        enforceCaller();
        File target = EmojiFontStore.fontFile(context);
        FileDescriptor descriptor = null;
        try {
            // Dedicated capability: no path argument, no write mode, no directory enumeration.
            descriptor = Os.open(target.getAbsolutePath(), OsConstants.O_RDONLY
                    | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
            if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) return null;
            return ParcelFileDescriptor.dup(descriptor);
        } catch (Exception unavailable) {
            return null;
        } finally {
            if (descriptor != null) try { Os.close(descriptor); } catch (Exception ignored) {}
        }
    }
}
