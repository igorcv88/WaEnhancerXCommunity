package com.waenhancer.security;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which callers may reach this module across a process boundary.
 *
 * <p>Authorisation is by <strong>UID</strong>, resolved from the installed package at call
 * time. A package name carried in an Intent or an extra is attacker-controlled and proves
 * nothing; the calling UID is supplied by Binder and cannot be forged. Two applications may
 * also share a UID, so the check is written as "the calling UID owns one of these packages"
 * rather than "the calling UID equals this one".</p>
 *
 * <p>The accepted callers are the module itself and the WhatsApp packages it is scoped to.
 * Everything else is refused, including the shell.</p>
 */
public final class CallerAuthority {

    public static final String PACKAGE_WHATSAPP = "com.whatsapp";
    public static final String PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b";

    private static final List<String> TRUSTED_PACKAGES = Collections.unmodifiableList(
            Arrays.asList(PACKAGE_WHATSAPP, PACKAGE_WHATSAPP_BUSINESS));

    private CallerAuthority() {
    }

    /** True when the current Binder caller is this module or a scoped WhatsApp package. */
    public static boolean isTrustedCaller(Context context) {
        return isTrusted(context, Binder.getCallingUid());
    }

    public static boolean isTrusted(Context context, int uid) {
        if (context == null) return false;
        if (uid == Process.myUid()) return true;
        // uid 0 (root) and 2000 (shell) are not module callers and get no special standing.
        for (String name : packagesFor(context, uid)) {
            if (TRUSTED_PACKAGES.contains(name)) return true;
        }
        return false;
    }

    /**
     * True when the caller is the module itself. Used for operations that no other process,
     * including WhatsApp, has any reason to perform.
     */
    public static boolean isSelf() {
        return Binder.getCallingUid() == Process.myUid();
    }

    /** The packages owned by a UID, empty when it cannot be resolved. */
    public static Set<String> packagesFor(Context context, int uid) {
        Set<String> names = new LinkedHashSet<>();
        if (context == null) return names;
        try {
            PackageManager packages = context.getPackageManager();
            String[] owned = packages.getPackagesForUid(uid);
            if (owned != null) names.addAll(Arrays.asList(owned));
        } catch (RuntimeException ignored) {
            // A caller we cannot resolve is a caller we do not trust.
        }
        return names;
    }

    /** The UID of an installed package, or -1 when it is not installed. */
    public static int uidOf(Context context, String packageName) {
        if (context == null || packageName == null) return -1;
        try {
            return context.getPackageManager().getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return -1;
        }
    }

    /**
     * Describes the caller for a local diagnostic line. Names the packages, never the UID of an
     * unrelated app in a form that could identify the user.
     */
    public static String describeCaller(Context context) {
        int uid = Binder.getCallingUid();
        Set<String> names = packagesFor(context, uid);
        return names.isEmpty() ? "unknown caller" : String.join(",", names);
    }
}
