package com.waenhancer.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks a downloaded APK before it is handed to the installer.
 *
 * <p>The updater previously downloaded a file and installed it with no verification at all: no
 * digest comparison, no check that the package was even this application, no signature check,
 * and the root path passed {@code -d} so it would silently accept a downgrade. Anything that
 * could influence the download could install anything.</p>
 *
 * <p>Every check here is a refusal, never a warning. An update that cannot be proven is not
 * installed.</p>
 */
public final class UpdateVerifier {

    public enum Failure {
        UNREADABLE,
        DIGEST_MISMATCH,
        NOT_PARSEABLE,
        WRONG_PACKAGE,
        WRONG_SIGNATURE,
        DOWNGRADE
    }

    public static final class Result {
        public final boolean ok;
        public final Failure failure;
        public final String detail;

        private Result(boolean ok, Failure failure, String detail) {
            this.ok = ok;
            this.failure = failure;
            this.detail = detail;
        }

        static Result pass() {
            return new Result(true, null, null);
        }

        static Result fail(Failure failure, String detail) {
            return new Result(false, failure, detail);
        }
    }

    private UpdateVerifier() {
    }

    /** Lowercase hex SHA-256 of a file. */
    public static String sha256(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return sha256(input);
        }
    }

    public static String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    public static String hex(byte[] raw) {
        StringBuilder text = new StringBuilder(raw.length * 2);
        for (byte b : raw) text.append(String.format(Locale.ROOT, "%02x", b));
        return text.toString();
    }

    /**
     * Pulls the expected digest out of a GitHub release body.
     *
     * <p>The release workflow appends exactly one {@code SHA-256: `<hex>`} line to every release
     * note it publishes, so that line is the channel through which the published digest reaches
     * this class. Parsing lives here, next to {@link #digestMatches}, so the published format and
     * the code that consumes it cannot drift apart.</p>
     *
     * @return the lowercase digest, or {@code null} when the notes carry none — which
     *         {@link #verify} then treats as a refusal, not as permission to skip the check
     */
    public static String extractSha256(String releaseBody) {
        if (releaseBody == null) return null;
        java.util.regex.Matcher matcher = SHA256_IN_NOTES.matcher(releaseBody);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static final java.util.regex.Pattern SHA256_IN_NOTES =
            java.util.regex.Pattern.compile("SHA-256:\\s*`?([0-9a-fA-F]{64})`?");

    /** Compares digests case-insensitively and without leaking through timing. */
    public static boolean digestMatches(String expected, String actual) {
        if (expected == null || actual == null) return false;
        String a = expected.trim().toLowerCase(Locale.ROOT);
        String b = actual.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) return false;
        int diff = a.length() ^ b.length();
        for (int i = 0; i < a.length() && i < b.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    /**
     * A downgrade is never automatic. It proceeds only when the user has explicitly asked for
     * one, which the caller signals rather than inferring from the file.
     */
    public static boolean versionIsAcceptable(long installed, long candidate,
                                              boolean downgradeRequested) {
        if (candidate > installed) return true;
        return downgradeRequested && candidate < installed;
    }

    /**
     * Full check against the running installation.
     *
     * @param expectedSha256    digest published with the release; required
     * @param downgradeRequested true only when the user explicitly chose a downgrade
     */
    public static Result verify(Context context, File apk, String expectedSha256,
                                boolean downgradeRequested) {
        if (context == null || apk == null || !apk.isFile() || apk.length() == 0) {
            return Result.fail(Failure.UNREADABLE, "The downloaded file is missing or empty.");
        }

        String actual;
        try {
            actual = sha256(apk);
        } catch (IOException exception) {
            return Result.fail(Failure.UNREADABLE, "The downloaded file could not be read.");
        }
        if (!digestMatches(expectedSha256, actual)) {
            return Result.fail(Failure.DIGEST_MISMATCH,
                    "The download does not match the checksum published with this release.");
        }

        PackageManager packages = context.getPackageManager();
        PackageInfo candidate = packages.getPackageArchiveInfo(apk.getAbsolutePath(),
                signingFlags());
        if (candidate == null) {
            return Result.fail(Failure.NOT_PARSEABLE, "The download is not a readable APK.");
        }
        if (!context.getPackageName().equals(candidate.packageName)) {
            return Result.fail(Failure.WRONG_PACKAGE,
                    "The download is for " + candidate.packageName + ", not this application.");
        }

        PackageInfo installed;
        try {
            installed = packages.getPackageInfo(context.getPackageName(), signingFlags());
        } catch (PackageManager.NameNotFoundException exception) {
            return Result.fail(Failure.NOT_PARSEABLE, "The installed application is unreadable.");
        }

        Set<String> installedCerts = certificateDigests(installed);
        Set<String> candidateCerts = certificateDigests(candidate);
        if (installedCerts.isEmpty() || candidateCerts.isEmpty()
                || !installedCerts.equals(candidateCerts)) {
            return Result.fail(Failure.WRONG_SIGNATURE,
                    "The download is signed with a different key than the installed app.");
        }

        if (!versionIsAcceptable(versionCode(installed), versionCode(candidate),
                downgradeRequested)) {
            return Result.fail(Failure.DOWNGRADE,
                    "The download is not newer than the installed version.");
        }
        return Result.pass();
    }

    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> certificateDigests(PackageInfo info) {
        List<Signature> signatures = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signing = info.signingInfo;
            if (signing != null) {
                Signature[] all = signing.hasMultipleSigners()
                        ? signing.getApkContentsSigners()
                        : signing.getSigningCertificateHistory();
                if (all != null) java.util.Collections.addAll(signatures, all);
            }
        } else if (info.signatures != null) {
            java.util.Collections.addAll(signatures, info.signatures);
        }

        Set<String> digests = new LinkedHashSet<>();
        for (Signature signature : signatures) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digests.add(hex(digest.digest(signature.toByteArray())));
            } catch (NoSuchAlgorithmException ignored) {
                return java.util.Collections.emptySet();
            }
        }
        return digests;
    }
}
