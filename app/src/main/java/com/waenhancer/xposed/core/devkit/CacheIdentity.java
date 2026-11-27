package com.waenhancer.xposed.core.devkit;

import android.content.pm.ApplicationInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cheap identity of the installed host files, for resolver-cache invalidation.
 *
 * <p>It covers the base APK and every split by path, size and modification time. Hashing the
 * APK contents (hundreds of MiB) on each start is not needed: any reinstall or split change
 * rewrites at least one of these. The diagnostic identity keeps the full SHA-256.</p>
 */
public final class CacheIdentity {
    private CacheIdentity() {}

    public static String hostInstallation(ApplicationInfo info) {
        List<String> paths = new ArrayList<>();
        if (info != null && info.sourceDir != null) paths.add(info.sourceDir);
        if (info != null && info.splitSourceDirs != null) {
            String[] splits = info.splitSourceDirs.clone();
            Arrays.sort(splits);
            paths.addAll(Arrays.asList(splits));
        }
        return of(paths);
    }

    /** Base first, then sorted splits, as produced by {@link #hostInstallation}. */
    static String of(List<String> paths) {
        StringBuilder token = new StringBuilder();
        for (String path : paths) {
            File file = new File(path);
            token.append(path).append(':').append(file.length()).append(':')
                    .append(file.lastModified()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            // Unknown identity: a token that never matches a stored one, so the cache resets.
            return "unknown-" + System.nanoTime();
        }
    }
}
