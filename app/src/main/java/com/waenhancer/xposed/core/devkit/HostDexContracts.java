package com.waenhancer.xposed.core.devkit;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.UsingType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
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
            // host declaration of the anchored lifecycle method: same name and descriptor.
            if (owner.getMethods().stream().anyMatch(method -> method.getName().equals(entry.getName())
                    && method.getMethodSign().equals(entry.getMethodSign()))) {
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

    public static final String VIDEO_ROW = "conversation-video-row";
    private static final String VIDEO_ROW_ASSERTION = "thumb view type is not correct";
    private static final String VIDEO_ROW_LEGACY = "frame_visibility_serial_worker";

    /**
     * The conversation row that renders a video message. Its animated-container accessor carries a
     * unique assertion message on both supported hosts; the worker-name string used before is gone
     * from 2.26.46.82 and remains only as a fallback for older hosts.
     */
    public static Candidates videoRowContainer(DexKitBridge dexkit) {
        List<MethodData> accessors = dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .addUsingString(VIDEO_ROW_ASSERTION, StringMatchType.Equals)))
                .stream().filter(method -> !Modifier.isStatic(method.getModifiers())
                        && method.getParamCount() == 0).collect(Collectors.toList());
        if (!accessors.isEmpty()) return new Candidates("animated-container-accessor-assertion", accessors);
        List<MethodData> legacy = dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .addUsingString(VIDEO_ROW_LEGACY, StringMatchType.Equals)));
        // One candidate per declaring class: the runtime contract is the class, not the method.
        Set<String> owners = new LinkedHashSet<>();
        List<MethodData> perClass = legacy.stream().filter(method -> owners.add(method.getClassName()))
                .collect(Collectors.toList());
        return new Candidates("legacy-frame-worker-name", perClass);
    }

    // ---- Message identity, contacts, calls, AB props and media: DEX-only queries shared with the
    // offline audit. Each returns every candidate; callers require uniqueness.

    public static final String MESSAGE_CLASS = "message-class";
    public static final String MESSAGE_KEY_TYPE = "message-key-type";
    public static final String MESSAGE_KEY_LEGACY = "message-key-legacy-final-field";
    public static final String ORIGINAL_MESSAGE_KEY = "original-message-key";
    public static final String WA_CONTACT = "wa-contact-class";
    public static final String OUTGOING_CALL = "outgoing-call-launcher";
    public static final String PROPS_BOOLEAN = "ab-props-boolean-getter";
    public static final String PROPS_INTEGER = "ab-props-integer-getter";
    public static final String IMAGE_ROW = "conversation-image-row";

    private static final String MESSAGE_ANCHOR = "FMessage/getSenderUserJid/key.id";
    private static final String ORIGINAL_KEY_ANCHOR = "FMessageUtil/getOriginalMessageKeyIfEdited";
    private static final String CONTACT_ANCHOR = "ContactManager/getContactFromCacheOrDbByJid";
    private static final String CONTACT_FALLBACK = "problematic contact:";
    private static final String CALL_ANCHOR = "outgoing-launch/cm-null-contact";
    private static final String SINGLETON_LIST =
            "Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;";

    public record Types(String strategy, List<ClassData> classes) {
        public ClassData unique() throws ClassNotFoundException {
            if (classes.size() != 1) throw new ClassNotFoundException(strategy + ": candidates=" + classes.size());
            return classes.get(0);
        }
    }

    public record Fields(String strategy, List<FieldData> fields) {
        public FieldData unique() throws NoSuchFieldException {
            if (fields.size() != 1) throw new NoSuchFieldException(strategy + ": candidates=" + fields.size());
            return fields.get(0);
        }
    }

    /** Classes declaring a method that loads {@code anchor}, in DEX order, without repeats. */
    private static List<ClassData> anchoredClasses(DexKitBridge dexkit, String anchor) {
        Set<String> seen = new LinkedHashSet<>();
        List<ClassData> out = new ArrayList<>();
        for (MethodData method : anchoredMethods(dexkit, anchor)) {
            if (seen.add(method.getClassName())) out.add(method.getDeclaredClass());
        }
        return out;
    }

    public static Types messageClass(DexKitBridge dexkit) {
        return new Types("sender-jid-log-anchor", anchoredClasses(dexkit, MESSAGE_ANCHOR));
    }

    /**
     * The key value type: three fields, a toString that names "Key", and held by an instance
     * field of the message class or a superclass. The first two alone match 182 classes in
     * 2.26.38.74 and 190 in 2.26.46.82, so the legacy key path could never resolve.
     */
    public static Types messageKeyType(DexKitBridge dexkit, String messageClass) {
        Set<String> held = new LinkedHashSet<>();
        for (ClassData owner = dexkit.getClassData(messageClass); owner != null; owner = owner.getSuperClass()) {
            for (FieldData field : owner.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) held.add(field.getTypeName());
            }
        }
        List<ClassData> out = new ArrayList<>();
        for (ClassData type : dexkit.findClass(FindClass.create().matcher(ClassMatcher.create().fieldCount(3)
                .addMethod(MethodMatcher.create().addUsingString("Key").name("toString"))))) {
            if (held.contains(type.getName())) out.add(type);
        }
        return new Types("three-fields-key-tostring-held-by-message", out);
    }

    /**
     * Hosts without the sender-read pattern: the final instance fields of the key type declared
     * by the message class or its superclasses. Exactly one is accepted; field order is never
     * evidence (both audited hosts also carry a mutable field of the same type).
     */
    public static Fields legacyMessageKey(DexKitBridge dexkit, String messageClass, String keyType) {
        List<FieldData> out = new ArrayList<>();
        for (ClassData owner = dexkit.getClassData(messageClass); owner != null; owner = owner.getSuperClass()) {
            for (FieldData field : owner.getFields()) {
                int modifiers = field.getModifiers();
                if (keyType.equals(field.getTypeName()) && Modifier.isFinal(modifiers)
                        && !Modifier.isStatic(modifiers)) out.add(field);
            }
        }
        return new Fields("final-instance-field-of-key-type", out);
    }

    /** Instance fields of the key type (mutable or final), for the audit's ambiguity report. */
    public static int keyTypedFieldCount(DexKitBridge dexkit, String messageClass, String keyType) {
        int count = 0;
        for (ClassData owner = dexkit.getClassData(messageClass); owner != null; owner = owner.getSuperClass()) {
            for (FieldData field : owner.getFields()) {
                if (keyType.equals(field.getTypeName()) && !Modifier.isStatic(field.getModifiers())) count++;
            }
        }
        return count;
    }

    /**
     * The static (message) -> key lookup that returns the original key of an edited message. The
     * log anchor is gone in 2.26.46.82; the structural rule needs the primary key field, a
     * boolean accessor of the message and a call that maps the message to an object holding a key.
     */
    public static Candidates originalMessageKey(DexKitBridge dexkit, String messageClass, FieldData keyField) {
        List<MethodData> logged = anchoredMethods(dexkit, ORIGINAL_KEY_ANCHOR).stream()
                .filter(MethodData::isMethod).collect(Collectors.toList());
        if (!logged.isEmpty()) return new Candidates("edited-original-key-log-anchor", logged);
        String keyType = keyField.getTypeName();
        List<MethodData> out = new ArrayList<>();
        for (MethodData candidate : dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .paramTypes(messageClass).returnType(keyType)
                .addUsingField(keyField.getDescriptor(), UsingType.Read)))) {
            if (!candidate.isMethod() || !Modifier.isStatic(candidate.getModifiers())) continue;
            var calls = candidate.getInvokes();
            boolean checksMessage = calls.stream().anyMatch(call -> call.isMethod()
                    && messageClass.equals(call.getClassName())
                    && call.getParamCount() == 0 && "boolean".equals(call.getReturnTypeName()));
            boolean hasEditLookup = calls.stream().anyMatch(call -> call.isMethod()
                    && !messageClass.equals(call.getClassName())
                    && call.getParamTypeNames().equals(List.of(messageClass))
                    && call.getReturnType() != null
                    && call.getReturnType().getFields().stream().anyMatch(field -> keyType.equals(field.getTypeName())));
            if (checksMessage && hasEditLookup) out.add(candidate);
        }
        return new Candidates("static-key-lookup-with-edit-check", out);
    }

    public static Types waContact(DexKitBridge dexkit) {
        List<ClassData> returned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MethodData method : anchoredMethods(dexkit, CONTACT_ANCHOR)) {
            if (!method.isMethod() || method.getReturnType() == null
                    || "void".equals(method.getReturnTypeName())) continue;
            if (seen.add(method.getReturnTypeName())) returned.add(method.getReturnType());
        }
        if (!returned.isEmpty()) return new Types("contact-lookup-return-type", returned);
        return new Types("problematic-contact-anchor", anchoredClasses(dexkit, CONTACT_FALLBACK));
    }

    /**
     * The (Context, WaContact, int, boolean) call launcher that wraps the contact in a singleton
     * list. The log string is not a selector: 2.26.38.74 uses it in three methods and 2.26.46.82
     * not at all; it is consulted only together with the launcher's signature.
     */
    public static Candidates outgoingCall(DexKitBridge dexkit, String contactClass) {
        String[] signature = {"android.content.Context", contactClass, "int", "boolean"};
        List<MethodData> shaped = dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .paramTypes(signature).addInvoke(SINGLETON_LIST)));
        if (shaped.size() == 1) return new Candidates("contact-launcher-singleton-list", shaped);
        List<MethodData> logged = dexkit.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .paramTypes(signature).addUsingString(CALL_ANCHOR, StringMatchType.Contains)));
        if (logged.size() == 1) return new Candidates("contact-launcher-log-anchor", logged);
        return new Candidates("contact-launcher-singleton-list", shaped);
    }

    public static Candidates propsBoolean(DexKitBridge dexkit) {
        return new Candidates("unknown-boolean-field-anchor", anchoredMethods(dexkit, "Unknown BooleanField")
                .stream().filter(MethodData::isMethod).collect(Collectors.toList()));
    }

    public static Candidates propsInteger(DexKitBridge dexkit) {
        return new Candidates("unknown-int-field-anchor", anchoredMethods(dexkit, "Unknown IntField")
                .stream().filter(MethodData::isMethod).collect(Collectors.toList()));
    }

    /**
     * Index of the property key among a getter's parameters: the only {@code int}, else the only
     * {@code Integer}; -1 when absent or ambiguous. Runtime (HostArgCompat) and offline share it.
     */
    public static int keyParameterIndex(List<String> parameterTypes) {
        if (parameterTypes == null) return -1;
        for (String type : new String[]{"int", "java.lang.Integer"}) {
            int index = -1;
            for (int i = 0; i < parameterTypes.size(); i++) {
                if (!type.equals(parameterTypes.get(i))) continue;
                if (index >= 0) return -1;
                index = i;
            }
            if (index >= 0) return index;
        }
        return -1;
    }

    /**
     * The image message row: a class with a method that uses both the HD-overlay and the control
     * button view ids. Resource ids come from each host's own resource table (runtime: Resources;
     * offline: aapt2), so the contract is checked per APK.
     */
    public static Types imageRowContainer(DexKitBridge dexkit, int hdInvisibleTouchId, int controlButtonId) {
        if (hdInvisibleTouchId == 0 || controlButtonId == 0) return new Types("resource-ids-missing", List.of());
        // Listeners and helpers using the same ids extend Object; the row is the view among them.
        return new Types("hd-overlay-and-control-button-ids-view", dexkit.findClass(FindClass.create().matcher(
                ClassMatcher.create().addMethod(MethodMatcher.create()
                        .addUsingNumber(hdInvisibleTouchId).addUsingNumber(controlButtonId))))
                .stream().filter(type -> type.getSuperClass() != null
                        && !"java.lang.Object".equals(type.getSuperClass().getName()))
                .collect(Collectors.toList()));
    }

    public static final String TAB_FRAME = "navigation-bar-base";
    public static final String NAVIGATION_MAX_ITEMS = "navigation-bar-max-item-count";
    public static final String NAVIGATION_LISTENER = "navigation-bar-item-selected-listener";

    /** Material's NavigationBarView base, which saves its menu presenters under this key. */
    public static Types tabFrame(DexKitBridge dexkit) {
        return new Types("menu-presenters-state-key", anchoredClasses(dexkit, "android:menu:presenters"));
    }

    /**
     * Concrete implementations of {@code name} with {@code paramCount} parameters in {@code base}
     * and all its subclasses. 2.26.46.82 made NavigationBarView#setOnItemSelectedListener abstract
     * and implements it in HomeLegacyBottomNavigationView, WDSBottomBar and WDSNavigationRail;
     * getMaxItemCount is implemented by the bottom bar and the rail bases, not by
     * BottomNavigationView, which no longer exists under that name.
     */
    public static Candidates concreteOverrides(DexKitBridge dexkit, String base, String name, int paramCount) {
        List<MethodData> out = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(List.of(base));
        while (!pending.isEmpty() && visited.size() < 256) {
            String type = pending.poll();
            if (!visited.add(type)) continue;
            ClassData data = dexkit.getClassData(type);
            if (data != null) {
                for (MethodData method : data.getMethods()) {
                    if (method.isMethod() && name.equals(method.getName()) && method.getParamCount() == paramCount
                            && !Modifier.isAbstract(method.getModifiers())) out.add(method);
                }
            }
            for (ClassData child : dexkit.findClass(FindClass.create().matcher(ClassMatcher.create().superClass(type)))) {
                pending.add(child.getName());
            }
        }
        return new Candidates("concrete-overrides-in-hierarchy", out);
    }
}
