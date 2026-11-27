package com.waenhancer.xposed.core.devkit;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;

/** DEX-only portions of runtime contracts. The offline audit runs these same queries. */
public final class HostDexContracts {
    public static final String CHAT_LIST = "chat-list-source";
    public static final String HOME_CONVERSATION = "tablet-active-conversation";
    public static final String READ_RECEIPT = "incoming-read-receipt";
    private static final String CHAT_FRAGMENT = "com.whatsapp.conversationslist.ConversationsFragment";
    private static final String HIDDEN_CHAT_DELEGATE =
            "com.whatsapp.conversationslist.delegate.ChatListHiddenChatsDelegate";
    private static final String HOME_TAG = "com.whatsapp.home.ui.HomeActivity.ConversationFragment";
    private static final String CONVERSATION_FRAGMENT = "com.whatsapp.conversation.ConversationFragment";
    private static final String INCOMING_READ = "ReadReceipts/sendReceiptForIncomingMessage";
    private HostDexContracts() {}

    public record Candidates(String strategy, List<MethodData> methods) {
        public MethodData unique() throws NoSuchMethodException {
            if (methods.size() != 1) {
                throw new NoSuchMethodException(strategy + ": candidates=" + methods.size());
            }
            return methods.get(0);
        }
    }

    public static Candidates chatList(DexKitBridge dexkit) {
        ClassData fragment = dexkit.getClassData(CHAT_FRAGMENT);
        if (fragment == null) return new Candidates("conversation-list-class", List.of());

        // Retain the unique old declared method. The Kotlin base has another List method
        // forwarding an interface call, so a shape-only hierarchy search is insufficient.
        List<MethodData> declared = listSources(fragment);
        if (!declared.isEmpty()) return new Candidates("declared-instance-list", declared);

        ClassData parent = fragment.getSuperClass();
        if (parent == null || !parent.getName().equals(CHAT_FRAGMENT + "Kt")) {
            return new Candidates("kotlin-conversation-list-class", List.of());
        }
        return new Candidates("kotlin-list-invokes-hidden-chat-filter", listSources(parent).stream()
                .filter(method -> method.getInvokes().stream().anyMatch(callee ->
                        HIDDEN_CHAT_DELEGATE.equals(callee.getClassName())
                        && "java.util.List".equals(callee.getReturnTypeName())
                        && callee.getParamTypeNames().contains("java.util.List")))
                .collect(Collectors.toList()));
    }

    private static List<MethodData> listSources(ClassData owner) {
        return owner.getMethods().stream()
                .filter(method -> !Modifier.isStatic(method.getModifiers())
                        && !Modifier.isAbstract(method.getModifiers())
                        && method.getParamCount() == 0
                        && "java.util.List".equals(method.getReturnTypeName()))
                .collect(Collectors.toList());
    }

    public static Candidates homeConversation(DexKitBridge dexkit) {
        ClassData home = dexkit.getClassData("com.whatsapp.home.ui.HomeActivity");
        if (home == null) return new Candidates("home-class", List.of());

        List<MethodData> candidates = dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .paramCount(0).addEqString(HOME_TAG)))
                .stream().filter(method -> !Modifier.isStatic(method.getModifiers())
                        && CONVERSATION_FRAGMENT.equals(method.getReturnTypeName())
                        && declaredInHierarchy(home, method.getClassName())
                        && method.getInvokes().stream().anyMatch(callee ->
                            "androidx.fragment.app.Fragment".equals(callee.getReturnTypeName())
                            && callee.getParamTypeNames().equals(List.of("java.lang.String"))))
                .collect(Collectors.toList());
        return new Candidates("home-tag-and-fragment-lookup", candidates);
    }

    private static boolean declaredInHierarchy(ClassData type, String owner) {
        for (ClassData current = type; current != null; current = current.getSuperClass()) {
            if (owner.equals(current.getName())) return true;
        }
        return false;
    }

    public static List<List<FieldData>> homeDelegatePaths(DexKitBridge dexkit) {
        var lifecycle = dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .addEqString("conversation/createconversation").paramTypes("android.os.Bundle")
                        .returnType(void.class)))
                .stream().filter(method -> !Modifier.isStatic(method.getModifiers())).collect(Collectors.toList());
        ClassData fragment = dexkit.getClassData(CONVERSATION_FRAGMENT);
        if (lifecycle.size() != 1 || fragment == null) return List.of();

        MethodData entry = lifecycle.get(0);
        Set<ClassData> owners = new HashSet<>();
        collectTypes(entry.getDeclaredClass(), owners);
        Set<String> delegateTypes = new HashSet<>();
        for (ClassData owner : owners) {
            // A generic holder can also implement an interface on the delegate. Require the
            // host declaration of the anchored lifecycle method, with its exact signature.
            if (owner.getMethods().stream().anyMatch(method ->
                    method.getMethodSign().equals(entry.getMethodSign()))) {
                delegateTypes.add(owner.getName());
            }
        }
        Set<List<FieldData>> paths = new LinkedHashSet<>();
        for (MethodData method : fragment.getMethods()) {
            List<FieldData> reads = method.getUsingFields().stream()
                    .filter(usage -> usage.getUsingType().isRead())
                    .map(UsingFieldData::getField).collect(Collectors.toList());
            for (FieldData outer : reads) {
                if (!fragment.getName().equals(outer.getDeclaredClassName())
                        || Modifier.isStatic(outer.getModifiers())) continue;
                if (delegateTypes.contains(outer.getTypeName())) {
                    paths.add(List.of(outer));
                }
                for (FieldData inner : reads) {
                    if (outer.getTypeName().equals(inner.getDeclaredClassName())
                            && !Modifier.isStatic(inner.getModifiers())
                            && delegateTypes.contains(inner.getTypeName())
                            && method.getInvokes().stream().anyMatch(callee ->
                                inner.getTypeName().equals(callee.getClassName()))) {
                        paths.add(List.of(outer, inner));
                    }
                }
            }
        }
        return List.copyOf(paths);
    }

    private static void collectTypes(ClassData type, Set<ClassData> types) {
        if (type == null || "java.lang.Object".equals(type.getName()) || !types.add(type)) return;
        collectTypes(type.getSuperClass(), types);
        for (ClassData parent : type.getInterfaces()) collectTypes(parent, types);
    }

    public static Candidates startupPreferences(DexKitBridge dexkit) {
        return new Candidates("startup-migration-anchor-constructor", anchoredMethods(dexkit,
                "startup_migrated_version").stream().filter(MethodData::isConstructor).collect(Collectors.toList()));
    }

    public static Candidates cachedMessage(DexKitBridge dexkit) {
        return new Candidates("cached-message-key-anchor", anchoredMethods(dexkit,
                "CachedMessageStore/getAvailableMessage/key"));
    }

    public static Candidates convertLidToJid(DexKitBridge dexkit) {
        return new Candidates("phone-jid-by-account-jid-anchor", anchoredMethods(dexkit,
                "WaJidMapRepository/getPhoneJidByAccountUserJid"));
    }

    public static Candidates convertJidToLid(DexKitBridge dexkit) {
        return new Candidates("account-jid-by-phone-jid-anchor", anchoredMethods(dexkit,
                "WaJidMapRepository/getAccountUserJidByPhoneJid"));
    }

    public static List<FieldData> messageKey(DexKitBridge dexkit) {
        var senders = anchoredMethods(dexkit, "FMessage/getSenderUserJid/key.id");
        Set<FieldData> matches = new LinkedHashSet<>();
        for (MethodData sender : senders) {
            for (var usage : sender.getUsingFields()) {
                FieldData field = usage.getField();
                if (!usage.getUsingType().isRead() || !sender.getClassName().equals(field.getClassName())
                        || !Modifier.isFinal(field.getModifiers()) || Modifier.isStatic(field.getModifiers())) continue;
                ClassData type = field.getType();
                if (type == null || type.getFieldCount() != 3
                        || type.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .name("toString").addUsingString("Key"))).isEmpty()) continue;
                var writers = field.getWriters();
                if (!writers.isEmpty() && writers.stream().allMatch(writer -> writer.isConstructor()
                        && writer.getClassName().equals(field.getClassName())
                        && writer.getParamTypeNames().contains(field.getTypeName()))) {
                    matches.add(field);
                }
            }
        }
        return List.copyOf(matches);
    }

    private static List<MethodData> anchoredMethods(DexKitBridge dexkit, String anchor) {
        return dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .addUsingString(anchor, StringMatchType.Contains)));
    }

    public static Candidates incomingReadReceipt(DexKitBridge dexkit) {
        return new Candidates("incoming-receipt-anchor-instance-void", dexkit.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().addUsingString(INCOMING_READ, StringMatchType.Contains)
                        .paramCount(2).returnType(void.class)))
                .stream().filter(method -> !Modifier.isStatic(method.getModifiers())
                        && "int".equals(method.getParamTypeNames().get(1))).collect(Collectors.toList()));
    }
}
