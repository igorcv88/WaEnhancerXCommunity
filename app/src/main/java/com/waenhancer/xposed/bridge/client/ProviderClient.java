package com.waenhancer.xposed.bridge.client;

import android.content.Context;
import android.net.Uri;

import com.waenhancer.BuildConfig;
import com.waenhancer.xposed.bridge.WaeIIFace;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import de.robv.android.xposed.XposedBridge;

/** Direct module IPC. No SettingsProvider, activity trampoline or PackageManager hook. */
public class ProviderClient extends BaseClient {
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WAEX-bridge-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final Context context;
    private volatile WaeIIFace service;
    private CompletableFuture<Boolean> connection;

    public ProviderClient(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public WaeIIFace getService() {
        return service;
    }

    @Override
    public synchronized CompletableFuture<Boolean> connect() {
        if (service != null && service.asBinder().pingBinder()) {
            return CompletableFuture.completedFuture(true);
        }
        if (connection != null && !connection.isDone()) return connection;
        service = null;
        final CompletableFuture<Boolean> attempt = new CompletableFuture<>();
        connection = attempt;
        CompletableFuture.runAsync(() -> {
            WaeIIFace candidate = null;
            try {
                var result = context.getContentResolver().call(
                        Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".hookprovider"),
                        "getHookBinder", null, null);
                var binder = result == null ? null : result.getBinder("binder");
                if (binder != null && binder.pingBinder()) {
                    candidate = WaeIIFace.Stub.asInterface(binder);
                }
            } catch (Exception e) {
                XposedBridge.log("[WAEX] Direct bridge unavailable: " + e.getClass().getSimpleName());
            }
            synchronized (ProviderClient.this) {
                // A timed-out or superseded call must not publish a stale Binder.
                if (connection == attempt && !attempt.isDone()) {
                    service = candidate;
                    attempt.complete(candidate != null);
                }
            }
        });
        var timeout = TIMEOUTS.schedule(() -> {
            synchronized (ProviderClient.this) {
                attempt.complete(false);
            }
        }, 3, TimeUnit.SECONDS);
        attempt.whenComplete((value, failure) -> timeout.cancel(false));
        return attempt;
    }

    @Override
    public void tryReconnect() {
        connect().thenAccept(connected -> {
            if (!connected) XposedBridge.log("[WAEX] Direct bridge reconnect failed; no automatic restart.");
        });
    }
}
