package com.waenhancer.xposed.core;

import com.waenhancer.diagnostics.RuntimeDiagnostics;
import com.waenhancer.xposed.compat.HostResolverCompat;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.core.devkit.ViewHolderCompat;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.lang.reflect.Member;
import java.util.ArrayList;
import org.json.JSONObject;
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
        // Never touch semantic resolvers before DexKit exists. UnobfuscatorCache keeps an in-memory
        // failed-key set; probing too early poisons that set for the entire WhatsApp process and a
        // later, otherwise valid post-init probe will only report "lookup failed previously".
        if (Unobfuscator.getDexKit() == null) {
            throw new IllegalStateException(
                    "DexKit is not initialized; compatibility probe must run after Unobfuscator.initWithPath()");
        }

        ProbeResult existing = cachedResult;
        if (existing != null) return existing;

        synchronized (LOCK) {
            existing = cachedResult;
            if (existing != null) return existing;

            List<String> required = new ArrayList<>();
            List<String> optional = new ArrayList<>();
            List<JSONObject> contracts = new ArrayList<>();

            // FMessageWpp + WppCore minimum contract. These are all read-only resolver calls;
            // no hook is installed by the probe itself.
            resolve(required, contracts, true, "jid.Jid", () -> Unobfuscator.findFirstClassUsingName(
                    loader, StringMatchType.EndsWith, "jid.Jid"));
            resolve(required, contracts, true, "jid.UserJid", () -> Unobfuscator.findFirstClassUsingName(
                    loader, StringMatchType.EndsWith, "jid.UserJid"));
            resolve(required, contracts, true, "jid.PhoneUserJid", () -> Unobfuscator.findFirstClassUsingName(
                    loader, StringMatchType.EndsWith, "jid.PhoneUserJid"));
            resolve(required, contracts, true, "jid.DeviceJid", () -> Unobfuscator.findFirstClassUsingName(
                    loader, StringMatchType.EndsWith, "jid.DeviceJid"));
            resolve(required, contracts, true, "FMessage", () -> Unobfuscator.loadFMessageClass(loader));
            resolve(required, contracts, true, "MessageKey", () -> Unobfuscator.loadMessageKeyField(loader));
            resolve(required, contracts, true, "DialogView", () -> Unobfuscator.loadDialogViewClass(loader));
            resolve(required, contracts, true, "StartPrefsConfig", () -> Unobfuscator.loadStartPrefsConfig(loader));
            resolve(required, contracts, true, "CachedMessageStore", () -> Unobfuscator.loadCachedMessageStoreKey(loader));
            resolve(required, contracts, true, "ConvertLidToJid", () -> Unobfuscator.loadConvertLidToJid(loader));
            resolve(required, contracts, true, "ConvertJidToLid", () -> Unobfuscator.loadConvertJidToLid(loader));
            resolve(required, contracts, true, "MeManager", () -> Unobfuscator.loadMeManagerClass(loader));

            // These are important but feature-scoped. A miss must not disable unrelated hooks;
            // FeatureLoader will report the concrete feature that fails to install.
            resolve(optional, contracts, false, "ConversationViewHolder", () -> ViewHolderCompat.loadViewHolder(loader));
            resolve(optional, contracts, false, "ConversationViewHolderField", () -> ViewHolderCompat.loadContainerField(loader));
            // Message text extraction is feature-scoped. Prefer the legacy resolver on hosts where
            // it still works, then use the structural compatibility resolver when anchor ordering
            // changes without changing the underlying FMessage contract.
            resolve(optional, contracts, false, "NewMessage", () -> HostResolverCompat.loadNewMessageMethod(loader));
            resolve(optional, contracts, false, "NewMessageWithMedia", () ->
                    Unobfuscator.loadNewMessageWithMediaMethod(loader));
            resolve(optional, contracts, false, "MediaType", () -> Unobfuscator.loadMediaTypeField(loader));
            resolve(optional, contracts, false, "OriginalMessageKey", () ->
                    Unobfuscator.loadOriginalMessageKey(loader));
            resolve(optional, contracts, false, "AbstractMediaMessage", () ->
                    Unobfuscator.loadAbstractMediaMessageClass(loader));
            resolve(optional, contracts, false, "BroadcastTag", () -> Unobfuscator.loadBroadcastTagField(loader));
            resolve(optional, contracts, false, "EditMessageField", () ->
                    Unobfuscator.loadSetEditMessageField(loader));
            resolve(optional, contracts, false, "FStatus", () -> Unobfuscator.loadFStatusClass(loader));
            resolve(optional, contracts, false, "FStatusKey", () -> Unobfuscator.loadFStatusKeyClass(loader));
            resolve(optional, contracts, false, "StatusByKey", () -> Unobfuscator.loadGetStatusByKey(loader));

            ProbeResult result = new ProbeResult(required, optional);
            cachedResult = result;
            RuntimeDiagnostics.probe(result.coreCompatible(), contracts);
            XposedBridge.log("[WAEX] Host compatibility probe: " + result.summary());
            return result;
        }
    }

    /** Visible for process-reload/tests that deliberately replace the host APK. */
    static void clearCachedResult() {
        synchronized (LOCK) {
            cachedResult = null;
        }
    }

    private static void resolve(List<String> failures, List<JSONObject> contracts,
            boolean required, String name, Resolver resolver) {
        String failure = null;
        Object result = null;
        try {
            result = resolver.resolve();
            if (result == null) failure = "null";
        } catch (Throwable error) {
            failure = error.getClass().getSimpleName();
        }
        if (failure != null) failures.add(name + "=" + failure);
        try {
            JSONObject contract = new JSONObject().put("contract", name).put("required", required)
                    .put("returned", failure == null);
            if (failure != null) contract.put("reason", failure);
            if (result instanceof Member) contract.put("member", result.toString());
            if (result instanceof Class) contract.put("declaringClass", ((Class<?>) result).getName());
            contracts.add(contract);
        } catch (org.json.JSONException ignored) { }
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
