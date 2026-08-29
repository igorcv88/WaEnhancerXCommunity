package com.waenhancer.xposed.core;

import com.waenhancer.xposed.core.devkit.Unobfuscator;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Runtime compatibility probe for the WhatsApp host.
 *
 * <p>WhatsApp obfuscates internal classes on every build. A version string therefore cannot prove
 * compatibility: the only useful pre-flight signal is whether the semantic DexKit resolvers that
 * the core actually depends on still resolve against the installed APK. Feature-specific hooks are
 * still validated by their own {@code doHook()} calls, which FeatureLoader already isolates and
 * reports independently.</p>
 */
public final class HostCompatibility {

    private static volatile ProbeResult cachedResult;
    private static final Object LOCK = new Object();

    private HostCompatibility() { }

    /**
     * Fail before a Feature can install hooks when the minimum host contract no longer resolves.
     * The result is cached because every Feature constructor passes through this method.
     */
    public static ProbeResult requireCoreCompatibility(ClassLoader loader) {
        ProbeResult result = probe(loader);
        if (!result.coreCompatible()) {
            throw new IllegalStateException("WhatsApp runtime compatibility probe failed: "
                    + String.join("; ", result.requiredFailures));
        }
        if (!result.optionalFailures.isEmpty() && result.markOptionalWarningLogged()) {
            XposedBridge.log("[WAEX] Host compatibility probe: core compatible; optional resolvers failed: "
                    + String.join("; ", result.optionalFailures));
        }
        return result;
    }

    public static ProbeResult probe(ClassLoader loader) {
        ProbeResult existing = cachedResult;
        if (existing != null) return existing;

        synchronized (LOCK) {
            existing = cachedResult;
            if (existing != null) return existing;

            List<String> required = new ArrayList<>();
            List<String> optional = new ArrayList<>();

            // FMessageWpp + WppCore minimum contract. These are all read-only resolver calls;
            // no hook is installed by the probe itself.
            require(required, "jid.Jid", () -> Unobfuscator.findFirstClassUsingName(
                    loader, StringMatchType.EndsWith, "jid.Jid"));
            require(required, "jid.UserJid", () -> Unobfuscator.findFirstClassUsingName(
                    loader, StringMatchType.EndsWith, "jid.UserJid"));
            require(required, "jid.PhoneUserJid", () -> Unobfuscator.findFirstClassUsingName(
                    loader, StringMatchType.EndsWith, "jid.PhoneUserJid"));
            require(required, "jid.DeviceJid", () -> Unobfuscator.findFirstClassUsingName(
                    loader, StringMatchType.EndsWith, "jid.DeviceJid"));
            require(required, "FMessage", () -> Unobfuscator.loadFMessageClass(loader));
            require(required, "MessageKey", () -> Unobfuscator.loadMessageKeyField(loader));
            require(required, "NewMessage", () -> Unobfuscator.loadNewMessageMethod(loader));
            require(required, "DialogView", () -> Unobfuscator.loadDialogViewClass(loader));
            require(required, "StartPrefsConfig", () -> Unobfuscator.loadStartPrefsConfig(loader));
            require(required, "CachedMessageStore", () -> Unobfuscator.loadCachedMessageStoreKey(loader));
            require(required, "ConvertLidToJid", () -> Unobfuscator.loadConvertLidToJid(loader));
            require(required, "ConvertJidToLid", () -> Unobfuscator.loadConvertJidToLid(loader));
            require(required, "MeManager", () -> Unobfuscator.loadMeManagerClass(loader));

            // These are important but feature-scoped. A miss must not disable unrelated hooks;
            // FeatureLoader will report the concrete feature that fails to install.
            optional(optional, "NewMessageWithMedia", () ->
                    Unobfuscator.loadNewMessageWithMediaMethod(loader));
            optional(optional, "MediaType", () -> Unobfuscator.loadMediaTypeField(loader));
            optional(optional, "OriginalMessageKey", () ->
                    Unobfuscator.loadOriginalMessageKey(loader));
            optional(optional, "AbstractMediaMessage", () ->
                    Unobfuscator.loadAbstractMediaMessageClass(loader));
            optional(optional, "BroadcastTag", () -> Unobfuscator.loadBroadcastTagField(loader));
            optional(optional, "EditMessageField", () ->
                    Unobfuscator.loadSetEditMessageField(loader));
            optional(optional, "FStatus", () -> Unobfuscator.loadFStatusClass(loader));
            optional(optional, "FStatusKey", () -> Unobfuscator.loadFStatusKeyClass(loader));
            optional(optional, "StatusByKey", () -> Unobfuscator.loadGetStatusByKey(loader));

            cachedResult = new ProbeResult(required, optional);
            XposedBridge.log("[WAEX] Host compatibility probe: " + cachedResult.summary());
            return cachedResult;
        }
    }

    /** Visible for process-reload/tests that deliberately replace the host APK. */
    static void clearCachedResult() {
        synchronized (LOCK) {
            cachedResult = null;
        }
    }

    private static void require(List<String> failures, String name, Resolver resolver) {
        resolve(failures, name, resolver);
    }

    private static void optional(List<String> failures, String name, Resolver resolver) {
        resolve(failures, name, resolver);
    }

    private static void resolve(List<String> failures, String name, Resolver resolver) {
        try {
            Object value = resolver.resolve();
            if (value == null) failures.add(name + "=null");
        } catch (Throwable t) {
            String message = t.getMessage();
            if (message == null || message.trim().isEmpty()) {
                failures.add(name + "=" + t.getClass().getSimpleName());
            } else {
                message = message.replace('\n', ' ').replace('\r', ' ').trim();
                if (message.length() > 180) message = message.substring(0, 180) + "…";
                failures.add(name + "=" + t.getClass().getSimpleName() + ": " + message);
            }
        }
    }

    private interface Resolver {
        Object resolve() throws Throwable;
    }

    public static final class ProbeResult {
        public final List<String> requiredFailures;
        public final List<String> optionalFailures;
        private boolean optionalWarningLogged;

        private ProbeResult(List<String> requiredFailures, List<String> optionalFailures) {
            this.requiredFailures = Collections.unmodifiableList(new ArrayList<>(requiredFailures));
            this.optionalFailures = Collections.unmodifiableList(new ArrayList<>(optionalFailures));
        }

        public boolean coreCompatible() {
            return requiredFailures.isEmpty();
        }

        public boolean fullyResolved() {
            return requiredFailures.isEmpty() && optionalFailures.isEmpty();
        }

        public String summary() {
            if (!coreCompatible()) {
                return "INCOMPATIBLE required=" + requiredFailures;
            }
            if (!optionalFailures.isEmpty()) {
                return "DEGRADED optional=" + optionalFailures;
            }
            return "COMPATIBLE";
        }

        private synchronized boolean markOptionalWarningLogged() {
            if (optionalWarningLogged) return false;
            optionalWarningLogged = true;
            return true;
        }
    }
}
