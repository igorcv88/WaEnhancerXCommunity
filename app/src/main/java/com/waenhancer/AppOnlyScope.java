package com.waenhancer;

/** Positive allowlist shared by entrypoint guards and JVM regression tests. */
public final class AppOnlyScope {
    private AppOnlyScope() {}

    public static boolean accepts(String packageName, String moduleId) {
        return "com.whatsapp".equals(packageName)
                || "com.whatsapp.w4b".equals(packageName)
                || (moduleId != null && moduleId.equals(packageName));
    }
}
