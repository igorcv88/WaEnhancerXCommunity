import com.waenhancer.xposed.core.devkit.HostDexContracts;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.base.BaseData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Desktop runner. All matcher rules live in the module. */
public final class OfflineDexAudit {
    private OfflineDexAudit() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 3) {
            throw new IllegalArgumentException("Expected a local base APK and optionally the ids of "
                    + "id/hd_invisible_touch and id/control_btn from that APK's resource table");
        }
        int hdInvisibleTouch = args.length == 3 ? Integer.decode(args[1]) : 0;
        int controlButton = args.length == 3 ? Integer.decode(args[2]) : 0;
        System.loadLibrary("dexkit");
        try (DexKitBridge bridge = DexKitBridge.create(args[0])) {
            emit(HostDexContracts.CHAT_LIST, HostDexContracts.chatList(bridge));
            emit(HostDexContracts.HOME_CONVERSATION, HostDexContracts.homeConversation(bridge));
            emit(HostDexContracts.READ_RECEIPT, HostDexContracts.incomingReadReceipt(bridge));
            emit(HostDexContracts.VIDEO_ROW, HostDexContracts.videoRowContainer(bridge));
            emit("startup-preferences", HostDexContracts.startupPreferences(bridge));
            emit("cached-message-store", HostDexContracts.cachedMessage(bridge));
            emit("convert-lid-to-jid", HostDexContracts.convertLidToJid(bridge));
            emit("convert-jid-to-lid", HostDexContracts.convertJidToLid(bridge));
            var keys = HostDexContracts.messageKey(bridge);
            result("message-key", "sender-read-of-final-key-written-by-owner-constructor", keys.size());
            for (var key : keys) row("path", "message-key", key.getDescriptor(), dexName(key));
            identity(bridge, keys);
            var contact = types(HostDexContracts.WA_CONTACT, HostDexContracts.waContact(bridge));
            if (contact != null) {
                emit(HostDexContracts.OUTGOING_CALL, HostDexContracts.outgoingCall(bridge, contact.getName()));
            } else {
                result(HostDexContracts.OUTGOING_CALL, "needs-unique-wa-contact", 0);
            }
            emitProps(HostDexContracts.PROPS_BOOLEAN, HostDexContracts.propsBoolean(bridge));
            emitProps(HostDexContracts.PROPS_INTEGER, HostDexContracts.propsInteger(bridge));
            types(HostDexContracts.IMAGE_ROW, HostDexContracts.imageRowContainer(bridge, hdInvisibleTouch, controlButton));
            navigation(bridge);
            var paths = HostDexContracts.homeDelegatePaths(bridge);
            String contract = "tablet-conversation-delegate";
            result(contract, "fragment-field-reads-and-delegate-interface", paths.size());
            for (var path : paths) {
                row("path", contract, String.join(",", path.stream().map(FieldData::getDescriptor).toList()),
                        String.join(",", path.stream().map(OfflineDexAudit::dexName).toList()));
            }
        }
    }

    /** Message class, key type, legacy final key and the original-key lookup (edits). */
    private static void identity(DexKitBridge bridge, List<FieldData> primaryKeys) throws Exception {
        var message = types(HostDexContracts.MESSAGE_CLASS, HostDexContracts.messageClass(bridge));
        var keyType = message == null ? null
                : types(HostDexContracts.MESSAGE_KEY_TYPE, HostDexContracts.messageKeyType(bridge, message.getName()));
        if (message == null || keyType == null) {
            result(HostDexContracts.MESSAGE_KEY_LEGACY, "needs-unique-message-and-key-type", 0);
            result(HostDexContracts.ORIGINAL_MESSAGE_KEY, "needs-unique-message-and-key-type", 0);
            return;
        }
        var legacy = HostDexContracts.legacyMessageKey(bridge, message.getName(), keyType.getName());
        result(HostDexContracts.MESSAGE_KEY_LEGACY, legacy.strategy(), legacy.fields().size());
        for (var field : legacy.fields()) row("field", HostDexContracts.MESSAGE_KEY_LEGACY, field.getDescriptor(), dexName(field));
        row("count", HostDexContracts.MESSAGE_KEY_LEGACY, "instance-fields-of-key-type",
                String.valueOf(HostDexContracts.keyTypedFieldCount(bridge, message.getName(), keyType.getName())));
        // The runtime key is the primary evidence when unique, otherwise the legacy final field.
        FieldData key = primaryKeys.size() == 1 ? primaryKeys.get(0)
                : legacy.fields().size() == 1 ? legacy.fields().get(0) : null;
        row("agreement", HostDexContracts.MESSAGE_KEY_LEGACY, "primary-equals-legacy",
                String.valueOf(primaryKeys.size() == 1 && legacy.fields().size() == 1
                        && primaryKeys.get(0).getDescriptor().equals(legacy.fields().get(0).getDescriptor())));
        if (key == null) {
            result(HostDexContracts.ORIGINAL_MESSAGE_KEY, "needs-unique-message-key", 0);
            return;
        }
        emit(HostDexContracts.ORIGINAL_MESSAGE_KEY, HostDexContracts.originalMessageKey(bridge, message.getName(), key));
    }

    private static ClassData types(String contract, HostDexContracts.Types candidates) {
        result(contract, candidates.strategy(), candidates.classes().size());
        for (ClassData type : candidates.classes()) {
            ClassData parent = type.getSuperClass();
            row("class", contract, type.getDescriptor(), dexName(type),
                    parent == null ? "super=?" : "super=" + parent.getName());
        }
        return candidates.classes().size() == 1 ? candidates.classes().get(0) : null;
    }

    /** A props getter is usable only with an unambiguous key parameter. */
    private static void emitProps(String contract, HostDexContracts.Candidates candidates) throws Exception {
        emit(contract, candidates);
        for (MethodData method : candidates.methods()) {
            row("key-index", contract, method.getDescriptor(),
                    String.valueOf(HostDexContracts.keyParameterIndex(method.getParamTypeNames())));
        }
    }

    private static void emit(String contract, HostDexContracts.Candidates candidates) throws Exception {
        result(contract, candidates.strategy(), candidates.methods().size());
        for (MethodData method : candidates.methods()) {
            // Hash the opcode sequence and signature shape without obfuscated names.
            String shape = method.getModifiers() + "|" + typeShape(method.getMethodSign())
                    + "|" + method.getOpCodes();
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(shape.getBytes(StandardCharsets.UTF_8)));
            row("method", contract, method.getDescriptor(), dexName(method), fingerprint);
            relationships(contract, method, "caller", method.getCallers());
            relationships(contract, method, "invoke", method.getInvokes());
        }
    }

    private static void relationships(String contract, MethodData method, String relationship,
            List<MethodData> members) {
        row(relationship + "-count", contract, method.getDescriptor(), String.valueOf(members.size()));
        // These are DEX references. The audit doesn't assert each referenced method has a
        // definition in the host APK. Only the candidate row represents a definition.
        members.stream().map(MethodData::getDescriptor).distinct().sorted().limit(40)
                .forEach(descriptor -> row(relationship, contract, method.getDescriptor(), descriptor));
    }

    private static String typeShape(String type) {
        return type.substring(type.indexOf('(')).replaceAll("LX/[^;]+;", "Lhost;");
    }

    private static String dexName(BaseData member) {
        long index = member.getEncodeId() >>> 32;
        return index == 0 ? "classes.dex" : "classes" + (index + 1) + ".dex";
    }

    /**
     * The navigation-bar base and every concrete implementation the runtime hooks (bar, rail and
     * their subclasses). A set of implementations is the expected result, not an ambiguity.
     */
    private static void navigation(DexKitBridge bridge) throws Exception {
        var base = types(HostDexContracts.TAB_FRAME, HostDexContracts.tabFrame(bridge));
        if (base == null) {
            result(HostDexContracts.NAVIGATION_MAX_ITEMS, "needs-unique-navigation-base", 0);
            result(HostDexContracts.NAVIGATION_LISTENER, "needs-unique-navigation-base", 0);
            return;
        }
        emitSet(HostDexContracts.NAVIGATION_MAX_ITEMS,
                HostDexContracts.concreteOverrides(bridge, base.getName(), "getMaxItemCount", 0));
        var listener = HostDexContracts.concreteOverrides(bridge, base.getName(), "setOnItemSelectedListener", 1);
        if (listener.methods().isEmpty()) {
            listener = HostDexContracts.concreteOverrides(bridge, base.getName(), "setOnNavigationItemSelectedListener", 1);
        }
        emitSet(HostDexContracts.NAVIGATION_LISTENER, listener);
    }

    /** Like {@link #emit}, for contracts whose every candidate is used: one or more is resolved. */
    private static void emitSet(String contract, HostDexContracts.Candidates candidates) throws Exception {
        int count = candidates.methods().size();
        row("result", contract, candidates.strategy(), String.valueOf(count), count == 0 ? "RESOLVER_FAILED" : "L1_SET");
        for (MethodData method : candidates.methods()) {
            String shape = method.getModifiers() + "|" + typeShape(method.getMethodSign()) + "|" + method.getOpCodes();
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(shape.getBytes(StandardCharsets.UTF_8)));
            row("method", contract, method.getDescriptor(), dexName(method), fingerprint);
        }
    }

    private static void result(String contract, String strategy, int count) {
        row("result", contract, strategy, String.valueOf(count),
                count == 1 ? "L1_UNIQUE" : count == 0 ? "RESOLVER_FAILED" : "AMBIGUOUS");
    }

    private static void row(String... columns) {
        System.out.println("audit\t" + String.join("\t", columns));
    }
}
