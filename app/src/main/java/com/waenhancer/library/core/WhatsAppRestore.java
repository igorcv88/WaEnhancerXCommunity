package com.waenhancer.library.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Writes a kept normal photo/video back to the path WhatsApp still records for its message.
 *
 * <p>Separate, opt-in feature ("Restore into WhatsApp", off by default) because it writes into
 * WhatsApp's own storage, which media scanners, WhatsApp's storage manager, cloud sync and the
 * user's own deletions also touch. Rules, each covered by tests:</p>
 * <ul>
 *   <li>only when the switch is on;</li>
 *   <li>never for view-once (its native state is not a file problem);</li>
 *   <li>only inside WhatsApp's own media roots, never outside, never over an existing file;</li>
 *   <li>never for a message the user deliberately cleared ({@code userCleared}, e.g. WhatsApp's
 *   storage manager or "delete media" — the hook reports it when known);</li>
 *   <li>the bytes go to a temporary sibling first and are renamed only when complete, so an
 *   interruption never leaves a truncated file under WhatsApp's name.</li>
 * </ul>
 */
public final class WhatsAppRestore {
    private WhatsAppRestore() { }

    public enum Outcome {
        RESTORED, DISABLED, VIEW_ONCE, USER_CLEARED, OUTSIDE_ROOTS, ALREADY_PRESENT, NO_PARENT, FAILED
    }

    public interface Source {
        InputStream open() throws IOException;
    }

    public static Outcome restore(boolean enabled, boolean viewOnce, boolean userCleared, File target,
                                  List<String> canonicalRoots, Source source) {
        if (!enabled) return Outcome.DISABLED;
        if (viewOnce) return Outcome.VIEW_ONCE;
        if (userCleared) return Outcome.USER_CLEARED;
        if (target == null) return Outcome.OUTSIDE_ROOTS;
        String canonical;
        try {
            canonical = target.getCanonicalPath();
        } catch (IOException invalid) {
            return Outcome.OUTSIDE_ROOTS;
        }
        if (target.exists()) return Outcome.ALREADY_PRESENT;
        if (!WhatsAppMediaPaths.isSafeRestoreTarget(canonical, canonicalRoots, false, false)) return Outcome.OUTSIDE_ROOTS;
        File parent = new File(canonical).getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return Outcome.NO_PARENT;
        File temp = new File(parent, "." + target.getName() + ".waex-restore");
        try (InputStream in = source.open(); FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("no source");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
            out.getFD().sync();
        } catch (IOException | RuntimeException failure) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return Outcome.FAILED;
        }
        // Re-check: never replace a file that appeared meanwhile (WhatsApp re-downloaded it).
        if (new File(canonical).exists() || !temp.renameTo(new File(canonical))) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return new File(canonical).exists() ? Outcome.ALREADY_PRESENT : Outcome.FAILED;
        }
        return Outcome.RESTORED;
    }
}
