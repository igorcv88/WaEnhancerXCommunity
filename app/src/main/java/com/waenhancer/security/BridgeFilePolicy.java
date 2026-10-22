package com.waenhancer.security;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Capabilities for the three existing file-bridge consumers, independent of Android. */
public final class BridgeFilePolicy {
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(
            "View Once", "Video Notes", "Profile Photo", "Status Videos", "Status Images",
            "Status Sounds", "Status Media"));
    private static final Set<String> IMAGES = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif"));
    private static final Set<String> MEDIA = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif",
            "mp4", "mkv", "webm", "3gp", "mov", "m4v", "opus", "ogg", "mp3", "m4a",
            "aac", "wav", "flac", "amr"));
    private final File external;
    private final File downloadBase;
    private final File themes;
    private final String wallpaper;

    public BridgeFilePolicy(File external, File downloadBase, File themes, String wallpaper) {
        this.external = external;
        this.downloadBase = downloadBase;
        this.themes = themes;
        this.wallpaper = wallpaper;
    }

    private static boolean within(File file, File directory) {
        return file.toPath().startsWith(directory.toPath());
    }

    private File shared(File file) throws IOException {
        if (!file.isAbsolute()) throw new SecurityException("Absolute shared-storage path required");
        // Reject traversal even when it would normalize back into an allowed directory.
        for (java.nio.file.Path part : file.toPath()) {
            if ("..".equals(part.toString())) throw new SecurityException("Traversal refused");
        }
        File root = external.getCanonicalFile();
        File canonical = file.getCanonicalFile();
        if (!within(canonical, root) || canonical.equals(root)
                || within(canonical, new File(root, "Android"))) {
            throw new SecurityException("Not public shared storage");
        }
        return canonical;
    }

    public File directory(String path) throws IOException {
        if (path == null) throw new SecurityException("Missing path");
        File target = shared(new File(path));
        File root = shared(new File(downloadBase, "WhatsApp"));
        if (!target.equals(root) && !(root.equals(target.getParentFile())
                && CATEGORIES.contains(target.getName()))) {
            throw new SecurityException("Not a media output directory");
        }
        return target;
    }

    public File file(String path, boolean write) throws IOException {
        if (path == null) throw new SecurityException("Missing path");
        File target = shared(new File(path));
        String name = target.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot < 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (target.exists() && !target.isFile()) throw new SecurityException("Not a regular file");
        if (write) {
            File parent = directory(target.getParent());
            File root = shared(new File(downloadBase, "WhatsApp"));
            if (parent.equals(root) || !MEDIA.contains(extension)) {
                throw new SecurityException("Not a supported media output");
            }
        } else {
            File themeRoot = shared(themes);
            boolean themeImage = within(target, themeRoot) && !target.equals(themeRoot);
            boolean selectedWallpaper = !themeImage && wallpaper != null && !wallpaper.isEmpty()
                    && target.equals(shared(new File(wallpaper)));
            if (!IMAGES.contains(extension) || !(themeImage || selectedWallpaper)) {
                throw new SecurityException("Not a theme image or selected wallpaper");
            }
            if (!target.isFile()) throw new SecurityException("Image does not exist");
        }
        return target;
    }
}
