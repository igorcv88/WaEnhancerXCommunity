package com.waenhancer.xposed.bridge.service;

import android.os.ParcelFileDescriptor;
import android.content.Context;
import com.waenhancer.security.CallerAuthority;
import android.os.RemoteException;

import com.waenhancer.xposed.bridge.WaeIIFace;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HookBinder extends WaeIIFace.Stub {

    private static HookBinder mInstance;
    private final Context context;

    private HookBinder(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized HookBinder getInstance(Context context) {
        if (mInstance == null) mInstance = new HookBinder(context);
        return mInstance;
    }

    private void enforceCaller() {
        // Authenticate each transaction as well as Binder acquisition. Do not clear identity.
        if (!CallerAuthority.isTrustedCaller(context)) throw new SecurityException("Untrusted bridge caller");
    }

    @Override
    public ParcelFileDescriptor openFile(String path, boolean create) throws RemoteException {
        enforceCaller();
        File file = new File(path);
        if (!file.exists() && create) {
            try {
                file.createNewFile();
            } catch (Exception ignored) {
                return null;
            }
        }
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public boolean createDir(String path) throws RemoteException {
        enforceCaller();
        File file = new File(path);
        return file.mkdirs();
    }

    @Override
    public List listFiles(String path) throws RemoteException {
        enforceCaller();
        var files = new File(path).listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(files);
    }


}
