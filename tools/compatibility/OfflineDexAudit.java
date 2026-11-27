import com.waenhancer.xposed.core.devkit.HostDexContracts;

import org.luckypray.dexkit.DexKitBridge;
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
        if (args.length != 1) throw new IllegalArgumentException("Expected one local base APK");
        System.loadLibrary("dexkit");
        try (DexKitBridge bridge = DexKitBridge.create(args[0])) {
            emit(HostDexContracts.CHAT_LIST, HostDexContracts.chatList(bridge));
            emit(HostDexContracts.HOME_CONVERSATION, HostDexContracts.homeConversation(bridge));
            emit(HostDexContracts.READ_RECEIPT, HostDexContracts.incomingReadReceipt(bridge));
            emit("startup-preferences", HostDexContracts.startupPreferences(bridge));
            emit("cached-message-store", HostDexContracts.cachedMessage(bridge));
            emit("convert-lid-to-jid", HostDexContracts.convertLidToJid(bridge));
            emit("convert-jid-to-lid", HostDexContracts.convertJidToLid(bridge));
            var keys = HostDexContracts.messageKey(bridge);
            result("message-key", "sender-read-of-final-key-written-by-owner-constructor", keys.size());
            for (var key : keys) row("path", "message-key", key.getDescriptor(), dexName(key));
            var paths = HostDexContracts.homeDelegatePaths(bridge);
            String contract = "tablet-conversation-delegate";
            result(contract, "fragment-field-reads-and-delegate-interface", paths.size());
            for (var path : paths) {
                row("path", contract, String.join(",", path.stream().map(FieldData::getDescriptor).toList()),
                        String.join(",", path.stream().map(OfflineDexAudit::dexName).toList()));
            }
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

    private static void result(String contract, String strategy, int count) {
        row("result", contract, strategy, String.valueOf(count),
                count == 1 ? "L1_UNIQUE" : count == 0 ? "RESOLVER_FAILED" : "AMBIGUOUS");
    }

    private static void row(String... columns) {
        System.out.println("audit\t" + String.join("\t", columns));
    }
}
