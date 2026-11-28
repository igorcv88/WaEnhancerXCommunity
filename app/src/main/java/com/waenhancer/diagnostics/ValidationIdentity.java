package com.waenhancer.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.waenhancer.BuildConfig;
import com.waenhancer.config.PreferenceSchema;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Exact installed binary identity. APK reads are restricted to the provider's worker threads. */
final class ValidationIdentity {
    private static final Map<String, CachedDigest> DIGESTS = new HashMap<>();

    private ValidationIdentity() {}

    static JSONObject read(Context context, String target) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo host = pm.getPackageInfo(target, PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo module = pm.getPackageInfo(BuildConfig.APPLICATION_ID, 0);
        if (host.signingInfo == null || host.versionName == null
                || !BuildConfig.VERSION_NAME.equals(module.versionName)
                || BuildConfig.VERSION_CODE != module.getLongVersionCode()) {
            throw new IllegalStateException("Package identity unavailable");
        }
        TreeMap<String, String> splits = new TreeMap<>();
        String[] splitPaths = host.applicationInfo.splitSourceDirs;
        if (splitPaths != null) {
            if (host.splitNames == null || host.splitNames.length != splitPaths.length) {
                throw new IllegalStateException("Split names and paths disagree");
            }
            for (int i = 0; i < splitPaths.length; i++) {
                if (splits.put(host.splitNames[i], digestApk(host, splitPaths[i])) != null) {
                    throw new IllegalStateException("Duplicate split");
                }
            }
        }
        ArrayList<String> signers = new ArrayList<>();
        for (var signer : host.signingInfo.getApkContentsSigners()) {
            signers.add(hex(sha256().digest(signer.toByteArray())));
        }
        if (signers.isEmpty()) throw new IllegalStateException("Signer unavailable");
        Collections.sort(signers);
        JSONObject identity = new JSONObject()
                .put("hostPackage", host.packageName).put("hostVersion", host.versionName)
                .put("hostVersionCode", host.getLongVersionCode())
                .put("baseSha256", digestApk(host, host.applicationInfo.sourceDir))
                .put("splits", new JSONObject(splits)).put("signerSha256", new JSONArray(signers))
                .put("moduleVersion", module.versionName).put("moduleVersionCode", module.getLongVersionCode())
                .put("moduleCodeSha256", moduleCodeDigest(module, module.applicationInfo.sourceDir))
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("deviceKind", deviceKind());
        // Local-only token to detect a reinstall even when its APK contents are identical.
        identity.put("installation", installation(context, target));
        return identity;
    }

    static String exactBuildKey(JSONObject identity, String configuration, int xposedApi) throws Exception {
        if (!configuration.matches("[0-9a-f]{64}") || xposedApi <= 0) {
            throw new IllegalArgumentException("Missing configuration or Xposed API");
        }
        JSONObject record = new JSONObject();
        for (String key : new String[]{"hostPackage", "hostVersion", "hostVersionCode", "baseSha256",
                "splits", "signerSha256", "moduleVersion", "moduleVersionCode", "moduleCodeSha256",
                "androidApi", "deviceKind"}) {
            record.put(key, identity.get(key));
        }
        record.put("configuration", configuration).put("xposedApi", xposedApi);
        return hex(sha256().digest(record.toString().getBytes(StandardCharsets.UTF_8)));
    }

    static String installation(Context context, String target) throws Exception {
        PackageManager pm = context.getPackageManager();
        StringBuilder token = new StringBuilder();
        for (String pkg : new String[]{target, BuildConfig.APPLICATION_ID}) {
            PackageInfo p = pm.getPackageInfo(pkg, 0);
            token.append(pkg).append(':').append(p.getLongVersionCode()).append(':')
                    .append(p.firstInstallTime).append(':').append(p.lastUpdateTime).append('\n');
            addStamp(token, p.applicationInfo.sourceDir);
            String[] paths = p.applicationInfo.splitSourceDirs;
            if (paths != null) {
                paths = paths.clone();
                java.util.Arrays.sort(paths);
                for (String path : paths) addStamp(token, path);
            }
        }
        return hex(sha256().digest(token.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Public list settings: their values come from a closed set of entry values, so they change
     * behaviour without carrying free text. {@code ValidationIdentityTest} keeps this set equal to
     * the ListPreference keys of the settings screens that the schema marks public.
     */
    static final Set<String> LIST_SETTINGS = Set.of(
            "animation_list", "antirevoke", "antirevokestatus", "audio_type", "call_privacy",
            "call_recording_mode", "call_type", "changecolor_mode", "chatfilter", "custom_privacy_type",
            "open_settings_mode", "open_waex", "seentick", "separategroups_counter_type",
            "show_home_menu", "status_style", "thememode", "transcription_provider", "typearchive",
            "wae_color_mode", "wae_color_preset");

    static String configuration(SharedPreferences prefs) throws Exception {
        // Schema-approved scalar settings and public list settings (closed value sets).
        // Exclude free-form strings, paths, contact lists, set values, caches and secrets.
        TreeMap<String, String> values = new TreeMap<>();
        for (var item : prefs.getAll().entrySet()) {
            var entry = PreferenceSchema.entry(item.getKey());
            if (entry == null || entry.store != PreferenceSchema.Store.PUBLIC
                    || entry.sensitivity != PreferenceSchema.Sensitivity.PUBLIC_SETTING) continue;
            Object value = item.getValue();
            if (value instanceof Boolean || value instanceof Integer
                    || value instanceof Long || value instanceof Float
                    || (LIST_SETTINGS.contains(item.getKey()) && value instanceof String
                    && ((String) value).matches("[A-Za-z0-9_.#-]{0,64}"))) {
                values.put(item.getKey(), value.getClass().getSimpleName() + ":" + value);
            }
        }
        return hex(sha256().digest(new JSONObject(values).toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String deviceKind() {
        return Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("Emulator")
                || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
                ? "EMULATOR" : "DEVICE_UNCONFIRMED";
    }

    private static void addStamp(StringBuilder token, String path) throws Exception {
        File file = new File(path);
        if (file.length() <= 0 || file.lastModified() <= 0) {
            throw new IllegalStateException("APK metadata unavailable");
        }
        token.append(path).append(':').append(file.length()).append(':')
                .append(file.lastModified()).append('\n');
    }

    /**
     * Digest of the module's executable content: every APK entry except the signature block and
     * the official-records catalog. Hashing the whole APK made a record self-referential: adding
     * it to {@code assets/validated_builds.json} changed the very digest it certified.
     */
    private static synchronized String moduleCodeDigest(PackageInfo pkg, String path) throws Exception {
        String cacheKey = "code:" + path;
        StringBuilder stampBuilder = new StringBuilder().append(pkg.getLongVersionCode()).append(':')
                .append(pkg.lastUpdateTime).append(':');
        addStamp(stampBuilder, path);
        String stamp = stampBuilder.toString();
        CachedDigest old = DIGESTS.get(cacheKey);
        if (old != null && stamp.equals(old.stamp)) return old.digest;
        String digest = codeDigest(new File(path));
        if (DIGESTS.size() >= 64) DIGESTS.clear();
        DIGESTS.put(cacheKey, new CachedDigest(stamp, digest));
        return digest;
    }

    static boolean excludedFromCode(String entry) {
        return entry.startsWith("META-INF/") || "assets/validated_builds.json".equals(entry);
    }

    static String codeDigest(File apk) throws Exception {
        MessageDigest md = sha256();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            ArrayList<String> names = new ArrayList<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.isDirectory() && !excludedFromCode(entry.getName())) names.add(entry.getName());
            }
            Collections.sort(names);
            byte[] buffer = new byte[64 * 1024];
            for (String name : names) {
                md.update(name.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                MessageDigest entryDigest = sha256();
                try (var input = zip.getInputStream(zip.getEntry(name))) {
                    for (int read; (read = input.read(buffer)) != -1;) entryDigest.update(buffer, 0, read);
                }
                md.update(entryDigest.digest());
            }
        }
        return hex(md.digest());
    }

    private static synchronized String digestApk(PackageInfo pkg, String path) throws Exception {
        File file = new File(path);
        StringBuilder stampBuilder = new StringBuilder().append(pkg.getLongVersionCode()).append(':')
                .append(pkg.firstInstallTime).append(':').append(pkg.lastUpdateTime).append(':');
        addStamp(stampBuilder, path);
        String stamp = stampBuilder.toString();
        CachedDigest old = DIGESTS.get(path);
        if (old != null && stamp.equals(old.stamp)) return old.digest;
        MessageDigest md = sha256();
        long length = file.length();
        long modified = file.lastModified();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) md.update(buffer, 0, read);
        }
        if (length != file.length() || modified != file.lastModified()) {
            throw new IllegalStateException("APK changed during hashing");
        }
        String digest = hex(md.digest());
        if (DIGESTS.size() >= 64) DIGESTS.clear();
        DIGESTS.put(path, new CachedDigest(stamp, digest));
        return digest;
    }

    private static MessageDigest sha256() throws Exception {
        return MessageDigest.getInstance("SHA-256");
    }

    private static String hex(byte[] bytes) {
        var out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value & 0xff) >>> 4, 16));
            out.append(Character.forDigit(value & 0xf, 16));
        }
        return out.toString();
    }

    private static final class CachedDigest {
        final String stamp;
        final String digest;

        CachedDigest(String stamp, String digest) {
            this.stamp = stamp;
            this.digest = digest;
        }
    }
}
