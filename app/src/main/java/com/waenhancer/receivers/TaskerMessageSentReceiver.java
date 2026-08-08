package com.waenhancer.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.waenhancer.BuildConfig;
import com.waenhancer.config.PreferenceStores;
import com.waenhancer.security.TaskerGuard;

/**
 * Entry point for automation apps asking the module to send a WhatsApp message.
 *
 * <p>This receiver used to accept any broadcast of {@code com.waenhancer.MESSAGE_SENT} that
 * carried a number and a message, from any application, and forwarded it straight into the
 * hooked process. That made "send a WhatsApp message as the user" an unauthenticated capability
 * available to every app on the device.</p>
 *
 * <p>A request must now satisfy {@link TaskerGuard}: the integration enabled, the
 * per-installation token presented, the declared caller on the user's allowlist, not a
 * duplicate, and within the rate limit.</p>
 *
 * <p><strong>Where the boundary actually is.</strong> A broadcast carries no caller identity —
 * {@code onReceive} cannot ask Binder who sent it — so the package allowlist rests on a value
 * the sender declares and is defence in depth, not a security boundary. The token is the
 * boundary: it is generated per installation with a CSPRNG, kept in the private store, never
 * exported in a backup, and compared without leaking through timing. An attacker who cannot
 * read the private store cannot produce it.</p>
 */
public class TaskerMessageSentReceiver extends BroadcastReceiver {

    /** Namespaced to the installed application ID rather than the upstream package name. */
    public static final String ACTION_SEND = BuildConfig.APPLICATION_ID + ".MESSAGE_SENT";
    /** Kept for one release so existing profiles keep working while legacy mode is on. */
    public static final String ACTION_SEND_LEGACY = "com.waenhancer.MESSAGE_SENT";
    public static final String ACTION_INTERNAL = BuildConfig.APPLICATION_ID
            + ".MESSAGE_SENT_INTERNAL";

    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_PACKAGE = "package";

    /**
     * Process-wide, not instance-owned. This receiver is manifest-declared, so Android builds a
     * fresh instance per broadcast; an instance field would reset the rate-limit window and the
     * duplicate history on every request and neither limit would ever be reached.
     */
    private static TaskerGuard guard() {
        return TaskerGuard.shared();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        String number = intent.getStringExtra("number");
        String message = intent.getStringExtra("message");
        String token = intent.getStringExtra(EXTRA_TOKEN);
        String declaredPackage = intent.getStringExtra(EXTRA_PACKAGE);

        SharedPreferences preferences = PreferenceStores.publicStore(context);
        String secret = PreferenceStores.privateStore(context)
                .getString(TaskerGuard.KEY_SECRET, null);

        // The token lives in the private store; hand the guard a view that can see it.
        SharedPreferences view = new TokenAwarePreferences(preferences, secret);

        TaskerGuard.Decision decision = guard().evaluate(view, declaredPackage, token,
                number, message, System.currentTimeMillis());
        if (decision != TaskerGuard.Decision.ALLOWED) return;

        // The internal forward crosses into WhatsApp's process, so it must be exported there and
        // is therefore reachable by other apps. Carry the token so the receiving side can tell a
        // genuine forward from a direct injection.
        String forwardToken = secret;
        forward(context, "com.whatsapp", number, message, forwardToken);
        forward(context, "com.whatsapp.w4b", number, message, forwardToken);
    }

    private static void forward(Context context, String packageName, String number,
                                String message, String token) {
        try {
            Intent forwarded = new Intent(ACTION_INTERNAL);
            forwarded.putExtra("number", number);
            forwarded.putExtra("message", message);
            forwarded.putExtra(EXTRA_TOKEN, token);
            forwarded.setPackage(packageName);
            forwarded.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(forwarded);
        } catch (RuntimeException ignored) {
            // A missing WhatsApp variant is not an error worth surfacing here.
        }
    }

    /**
     * Presents the public store with the token overlaid, so the guard reads one consistent view
     * without the token ever being written to the world-readable file.
     */
    private static final class TokenAwarePreferences
            extends com.waenhancer.config.OverlayPreferences {
        TokenAwarePreferences(SharedPreferences delegate, String secret) {
            super(delegate, TaskerGuard.KEY_SECRET, secret);
        }
    }
}
