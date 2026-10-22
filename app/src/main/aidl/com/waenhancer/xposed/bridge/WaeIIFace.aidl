// WaeIIFace.aidl
package com.waenhancer.xposed.bridge;

import android.os.ParcelFileDescriptor;
import java.util.List;

// Declare any non-default types here with import statements

interface WaeIIFace {
    /**
     * Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */
    ParcelFileDescriptor openFile(String path, boolean create);

    boolean createDir(String path);

    List listFiles(String path);

    /** Read-only access to the one user-imported emoji font. No caller-provided path. */
    ParcelFileDescriptor openEmojiFont();

}
