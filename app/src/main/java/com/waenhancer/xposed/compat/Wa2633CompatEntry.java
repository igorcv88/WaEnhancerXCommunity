package com.waenhancer.xposed.compat;

import android.app.Application;
import android.app.Instrumentation;
import android.content.SharedPreferences;

import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.db.MessageHistory;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.features.privacy.CustomPrivacy;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.xposed.utils.Utils;

import org.json.JSONObject;
import org.luckypray.dexkit.query.enums.StringMatchType;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XCallback;

/**
 * Narrow runtime compatibility layer for the WhatsApp 2.26.33+ transition.
 *
 * <p>The Community codebase deliberately keeps its proven legacy LSPosed entrypoint while
 * upstream's modern/libxposed migration remains incomplete. This class only supplements two
 * host contracts that changed after the current Community baseline:</p>
 *
 * <ul>
 *     <li>status-page callbacks may carry a null/non-numeric legacy position argument; and</li>
 *     <li>current WhatsApp exposes a direct incoming-message read-receipt method which provides
 *     a more reliable last line of defence for HideSeen.</li>
 * </ul>
 *
 * <p>Installation is deferred until {@link com.waenhancer.xposed.core.FeatureLoader} has
 * initialized DexKit/FMessage contracts. Every resolver is fail-open: a future host that no
 * longer exposes one of these contracts continues without this compatibility hook.</p>
 */
public final class Wa2633CompatEntry implements IXposedHookLoadPackage {

    private static final String PACKAGE_WHATSAPP = "com.whatsapp";
    private static final String PACKAGE_BUSINESS = "com.whatsapp.w4b";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PACKAGE_WHATSAPP.equals(lpparam.packageName)
                && !PACKAGE_BUSINESS.equals(lpparam.packageName)) {
            return;
        }

        XposedHelpers.findAndHookMethod(
                Instrumentation.class,
                "callApplicationOnCreate",
                Application.class,
                new XC_MethodHook(XCallback.PRIORITY_LOWEST) {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!INSTALLED.compareAndSet(false, true)) return;

                        installStatusPageNullGuard(lpparam.classLoader);
                        installDirectReadReceiptGuard(lpparam.classLoader);
                    }
                });
    }

    /**
     * Community's legacy status tracker assumes args[1] is always an Integer. On current
     * WhatsApp there are transient calls where that slot is null/non-numeric. LSPosed catches
     * the resulting Integer.intValue() NPE, but the callback then fails on every such call.
     *
     * <p>For those malformed-for-the-legacy-tracker calls only, invoke the host method directly
     * with the original, untouched arguments and return its real result. That preserves WhatsApp
     * behaviour while preventing lower-priority legacy callbacks from dereferencing the null
     * slot. Normal numeric calls are not changed at all.</p>
     */
    private static void installStatusPageNullGuard(ClassLoader loader) {
        try {
            Method statusPage = Unobfuscator.loadStatusActivePage(loader);
            XposedBridge.hookMethod(statusPage, new XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length <= 1) return;
                    if (param.args[1] instanceof Number) return;

                    Object result = XposedBridge.invokeOriginalMethod(
                            statusPage, param.thisObject, param.args);
                    param.setResult(result);
                }
            });
            XposedBridge.log("[WAEX][2.26.33] status-page null guard installed: " + statusPage);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX][2.26.33] status-page guard unavailable: " + t);
        }
    }

    /**
     * Port of the current upstream direct read-receipt enforcement path. Existing Community
     * dispatch/ProtocolTree/SendReadReceiptJob hooks remain in place; this hook is intentionally
     * an additional semantic guard and uses the same Community privacy preferences/history DB.
     */
    private static void installDirectReadReceiptGuard(ClassLoader loader) {
        try {
            Method readReceipt = Unobfuscator.findFirstMethodUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "ReadReceipts/sendReceiptForIncomingMessage");
            if (readReceipt == null) {
                throw new NoSuchMethodException("direct incoming read-receipt method not found");
            }

            XposedBridge.hookMethod(readReceipt, new XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (FMessageWpp.TYPE == null || param.args == null) return;

                        Object raw = ReflectionUtils.getArg(param.args, FMessageWpp.TYPE, 0);
                        if (raw == null) return;

                        FMessageWpp fMessage = new FMessageWpp(raw);
                        FMessageWpp.Key key = fMessage.getKey();
                        if (key == null || key.isFromMe || key.remoteJid == null) return;

                        SharedPreferences prefs = Utils.xprefs;
                        if (prefs == null) return;

                        boolean globalHideReceipt = prefs.getBoolean("hidereceipt", false);
                        boolean ghostMode = prefs.getBoolean("ghostmode_actual", false);
                        JSONObject privacy = CustomPrivacy.getJSON(key.remoteJid.getPhoneNumber());
                        boolean customHideReceipt = privacy.optBoolean(
                                "HideReceipt", globalHideReceipt);

                        if (!customHideReceipt && !ghostMode) return;

                        // Return early from the receipt method, matching upstream's new guard.
                        param.setResult(null);

                        MessageHistory history = MessageHistory.getInstance();
                        if (history != null && key.messageID != null) {
                            history.insertHideSeenMessage(
                                    key.remoteJid.getPhoneRawString(),
                                    key.messageID,
                                    MessageHistory.MessageType.MESSAGE_TYPE,
                                    false);
                        }
                    } catch (Throwable t) {
                        // Privacy enforcement must fail open rather than destabilize WhatsApp.
                        XposedBridge.log("[WAEX][2.26.33] direct read-receipt guard failed open: " + t);
                    }
                }
            });
            XposedBridge.log("[WAEX][2.26.33] direct read-receipt guard installed: " + readReceipt);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX][2.26.33] direct read-receipt guard unavailable: " + t);
        }
    }

    private Wa2633CompatEntry() {
    }
}
