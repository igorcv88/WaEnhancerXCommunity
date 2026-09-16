package com.waenhancer.xposed.compat;

import com.waenhancer.xposed.core.devkit.Unobfuscator;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XposedHelpers;

/**
 * Narrow compatibility fallbacks for host contracts whose semantic shape is still stable but whose
 * original lookup path changed in newer WhatsApp builds.
 *
 * <p>These resolvers intentionally try the legacy Unobfuscator implementation first so older host
 * versions keep their existing behavior. Fallbacks are based on structural relationships observed
 * in the host DEX rather than hard-coded obfuscated class or method names.</p>
 */
public final class HostResolverCompat {

    private static final String[] CONVERSATION_ANCHORS = {
            "conversation/createconversation",
            "conversation/create",
            "conversation/refresh",
            "conversation/onCreate"
    };

    private HostResolverCompat() { }

    /**
     * Resolves the zero-argument String accessor on FMessage used for the message body.
     *
     * <p>WhatsApp 2.26.33 still contains the {@code extra_payment_note} semantic anchor, but it is
     * referenced by several methods and the first match no longer calls the FMessage accessor. The
     * legacy resolver inspected only that first match. Search all anchor users before falling back
     * to the FMessage method structure itself.</p>
     */
    public static Method loadNewMessageMethod(ClassLoader loader) throws Exception {
        try {
            return Unobfuscator.loadNewMessageMethod(loader);
        } catch (Throwable ignored) {
            // Continue with the semantic fallback below.
        }

        var dexkit = Unobfuscator.getDexKit();
        if (dexkit == null) {
            throw new IllegalStateException("DexKit is not initialized");
        }

        Class<?> fMessageClass = Unobfuscator.loadFMessageClass(loader);
        String fMessageName = fMessageClass.getName();

        var anchors = dexkit.findMethod(FindMethod.create()
                .searchPackages("com.whatsapp")
                .matcher(MethodMatcher.create().addUsingString(
                        "extra_payment_note", StringMatchType.Equals)));

        for (MethodData anchor : anchors) {
            for (MethodData invoke : anchor.getInvokes()) {
                if (!invoke.isMethod()) continue;
                if (!fMessageName.equals(invoke.getDeclaredClassName())) continue;
                if (invoke.getParamCount() != 0) continue;
                if (invoke.getReturnType() == null
                        || !String.class.getName().equals(invoke.getReturnType().getName())) continue;

                Method candidate = invoke.getMethodInstance(loader);
                if (!Modifier.isStatic(candidate.getModifiers())) {
                    return candidate;
                }
            }
        }

        // Anchor-independent fallback. The current message-body accessor is the unique zero-arg
        // String method on FMessage that reads both an FMessage String cache and a byte[] payload.
        var classData = dexkit.getClassData(fMessageClass);
        MethodData best = null;
        int bestScore = -1;
        boolean tied = false;

        if (classData != null) {
            for (MethodData candidate : classData.getMethods()) {
                if (!candidate.isMethod()) continue;
                if (candidate.getParamCount() != 0) continue;
                if (candidate.getReturnType() == null
                        || !String.class.getName().equals(candidate.getReturnType().getName())) continue;

                Method method;
                try {
                    method = candidate.getMethodInstance(loader);
                } catch (Throwable ignored) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers()) || "toString".equals(method.getName())) continue;

                int score = 0;
                for (UsingFieldData usingField : candidate.getUsingFields()) {
                    var field = usingField.getField();
                    if (!fMessageName.equals(field.getClassName())) continue;
                    String typeName = field.getType().getName();
                    if (String.class.getName().equals(typeName)) score += 2;
                    if (byte[].class.getName().equals(typeName) || "byte[]".equals(typeName)) score += 3;
                }

                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                    tied = false;
                } else if (score == bestScore) {
                    tied = true;
                }
            }
        }

        if (best != null && bestScore >= 5 && !tied) {
            return best.getMethodInstance(loader);
        }

        throw new NoSuchMethodException("FMessage message-body accessor not found");
    }

    /**
     * Resolves the Conversation field that stores the current conversation delegate.
     *
     * <p>Current WhatsApp exposes an implementation class through the semantic conversation
     * anchors while {@code Conversation} stores it using an interface/supertype. The legacy helper
     * tested assignability in the opposite direction, so an implementation such as X.234 could not
     * match its X.3io storage field.</p>
     */
    public static Field loadConversationDelegateField(ClassLoader loader) throws Exception {
        try {
            Field legacy = Unobfuscator.loadConversationDelegateField(loader);
            if (legacy != null) return legacy;
        } catch (Throwable ignored) {
            // Continue with the structural fallback below.
        }

        Class<?> conversation = XposedHelpers.findClass("com.whatsapp.Conversation", loader);
        Class<?> conversationFragment = XposedHelpers.findClassIfExists(
                "com.whatsapp.ConversationFragment", loader);
        Class<?> jidClass = Unobfuscator.findFirstClassUsingName(
                loader, StringMatchType.EndsWith, "jid.Jid");

        for (String anchor : CONVERSATION_ANCHORS) {
            Class<?>[] implementations = Unobfuscator.findAllClassUsingStrings(
                    loader, StringMatchType.Contains, anchor);
            if (implementations == null) continue;

            for (Class<?> implementation : implementations) {
                // A conversation delegate must itself expose the active JID. This disambiguates
                // unrelated providers/helpers that happen to share one of the conversation anchors.
                if (findAssignableInstanceField(implementation, jidClass) == null) continue;

                Field field = findAssignableStorageField(conversation, implementation);
                if (field != null) return field;

                field = findAssignableStorageField(conversationFragment, implementation);
                if (field != null) return field;
            }
        }

        throw new NoSuchFieldException("Conversation delegate storage field not found");
    }

    static Field findAssignableStorageField(Class<?> owner, Class<?> implementation) {
        if (owner == null || implementation == null) return null;

        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> fieldType = field.getType();
                if (fieldType == Object.class) continue;
                if (fieldType.isAssignableFrom(implementation)) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    static Field findAssignableInstanceField(Class<?> owner, Class<?> targetSupertype) {
        if (owner == null || targetSupertype == null) return null;

        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (targetSupertype.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }
}
