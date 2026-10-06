package com.waenhancer.xposed.features.media;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.HKDF;
import com.waenhancer.R;
import com.waenhancer.xposed.utils.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import okhttp3.OkHttpClient;
import okhttp3.Request;


public class MediaPreview extends Feature {

    private static final String HTML_LOADING = "<!DOCTYPE html><html><head> <meta charset=\"UTF-8\"> <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"> <title>Loading</title> <style> body { display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; background-color: #f0f0f0; font-family: Arial, sans-serif; } .loader { display: flex; align-items: center; } .spinner { width: 40px; height: 40px; border: 4px solid rgba(0, 0, 0, 0.1); border-top: 4px solid #000; border-radius: 50%; animation: spin 1s linear infinite; } @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } } .text { margin-left: 10px; font-size: 18px; } </style></head><body> <div class=\"loader\"> <div class=\"spinner\"></div> <div class=\"text\">$loading</div> </div></body></html>";
    private static final String HTML_VIDEO = "<!DOCTYPE html><html><head> <meta charset=\"UTF-8\"> <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"> <title>Player de Vídeo</title> <style> body { display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; background-color: #f0f0f0; font-family: Arial, sans-serif; } .video-container { text-align: center; } video { width: 100%; height: auto; } </style></head><body> <div class=\"video-container\"> <video controls> <source src=\"$url\" type=\"video/mp4\"> Browser not supported. </video> </div></body></html>";
    private static final String HTML_IMAGE = "<!DOCTYPE html><html><head> <meta charset=\"UTF-8\"> <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"> <title>Image</title> <style> body { display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; background-color: #f0f0f0; font-family: Arial, sans-serif; } .full-screen-image { width: 100%; height: auto;} </style></head><body> <img src=\"$url\" class=\"full-screen-image\"></body></html>";

    static HashMap<String, byte[]> MEDIA_KEYS = new HashMap<>();

    static {
        MEDIA_KEYS.put("image", "WhatsApp Image Keys".getBytes());
        MEDIA_KEYS.put("video", "WhatsApp Video Keys".getBytes());
        MEDIA_KEYS.put("audio", "WhatsApp Audio Keys".getBytes());
        MEDIA_KEYS.put("document", "WhatsApp Document Keys".getBytes());
        MEDIA_KEYS.put("image/webp", "WhatsApp Image Keys".getBytes());
        MEDIA_KEYS.put("image/jpeg", "WhatsApp Image Keys".getBytes());
        MEDIA_KEYS.put("image/png", "WhatsApp Image Keys".getBytes());
        MEDIA_KEYS.put("video/mp4", "WhatsApp Video Keys".getBytes());
        MEDIA_KEYS.put("audio/aac", "WhatsApp Audio Keys".getBytes());
        MEDIA_KEYS.put("audio/ogg", "WhatsApp Audio Keys".getBytes());
        MEDIA_KEYS.put("audio/wav", "WhatsApp Audio Keys".getBytes());
    }

    public MediaPreview(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {

        if (!prefs.getBoolean("media_preview", true)) return;

        var videoViewContainerClass = Unobfuscator.loadVideoViewContainerClass(classLoader);
        XposedBridge.hookAllConstructors(videoViewContainerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length < 2) return;
                var view = (View) param.thisObject;
                var context = view.getContext();
                var surface = (ViewGroup) view.findViewById(Utils.getID("invisible_press_surface", "id"));
                if (surface == null || surface.getChildCount() == 0) return;
                var controlFrame = surface.getChildAt(0);
                surface.removeViewAt(0);
                var linearLayout = new LinearLayout(context);
                surface.addView(linearLayout);
                linearLayout.addView(controlFrame);
                var prevBtn = new ImageView(context);
                var layoutParams = new LinearLayout.LayoutParams(Utils.dipToPixels(42), Utils.dipToPixels(32));
                layoutParams.gravity = Gravity.CENTER;
                prevBtn.setLayoutParams(layoutParams);
                var drawable = DesignUtils.getDrawable(R.drawable.preview_eye);
                drawable.setTint(Color.WHITE);
                prevBtn.setImageDrawable(drawable);
                prevBtn.setPadding(Utils.dipToPixels(4), Utils.dipToPixels(4), Utils.dipToPixels(4), Utils.dipToPixels(4));
                prevBtn.setBackground(DesignUtils.getDrawableByName("download_background"));
                prevBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
                linearLayout.addView(prevBtn);
                prevBtn.setOnClickListener((v) -> {
                    var objmessage = XposedHelpers.callMethod(param.thisObject, "getFMessage");
                    var id = new FMessageWpp(objmessage).getRowId();
                    var userJid = WppCore.getCurrentUserJid();
                    startPlayer(id, context, userJid != null && userJid.isNewsletter());
                });
            }
        });

        var imageViewContainerClass = Unobfuscator.loadImageVewContainerClass(classLoader);
        XposedBridge.hookAllConstructors(imageViewContainerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length < 2) return;
                var view = (View) param.thisObject;
                var context = view.getContext();

                ViewGroup mediaContainer = view.findViewById(Utils.getID("media_container", "id"));
                ViewGroup controlFrame = view.findViewById(Utils.getID("control_frame", "id"));
                if (mediaContainer == null || controlFrame == null) return;

                LinearLayout linearLayout = new LinearLayout(context);
                linearLayout.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                ));
                linearLayout.setOrientation(LinearLayout.VERTICAL);
                linearLayout.setBackground(DesignUtils.getDrawableByName("fragment_transparent_divider"));
                mediaContainer.removeView(controlFrame);
                linearLayout.addView(controlFrame);
                mediaContainer.addView(linearLayout);
                var prevBtn = new ImageView(context);
                var layoutParams2 = new LinearLayout.LayoutParams(Utils.dipToPixels(42), Utils.dipToPixels(32));
                layoutParams2.gravity = Gravity.CENTER;
                layoutParams2.topMargin = Utils.dipToPixels(8);
                prevBtn.setLayoutParams(layoutParams2);
                var drawable = DesignUtils.getDrawable(R.drawable.preview_eye);
                drawable.setTint(Color.WHITE);
                prevBtn.setImageDrawable(drawable);
                prevBtn.setPadding(Utils.dipToPixels(4), Utils.dipToPixels(4), Utils.dipToPixels(4), Utils.dipToPixels(4));
                prevBtn.setBackground(DesignUtils.getDrawableByName("download_background"));
                prevBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
                linearLayout.addView(prevBtn);
                prevBtn.setVisibility(controlFrame.getVisibility());
                controlFrame.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    if (prevBtn.getVisibility() != controlFrame.getVisibility())
                        prevBtn.setVisibility(controlFrame.getVisibility());
                });

                prevBtn.setOnClickListener((v) -> {
                    var objmessage = XposedHelpers.callMethod(param.thisObject, "getFMessage");
                    var id = new FMessageWpp(objmessage).getRowId();
                    var userJid = WppCore.getCurrentUserJid();
                    startPlayer(id, context, userJid != null && userJid.isNewsletter());
                });

            }
        });


    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startPlayer(long id, Context context, boolean isNewsletter) {
        String url;
        String mimeType;
        String mediaKey;
        try (Cursor cursor = MessageStore.getInstance().getDatabase().rawQuery(
                "SELECT message_url,mime_type,hex(media_key),direct_path FROM message_media WHERE message_row_id=?",
                new String[]{String.valueOf(id)})) {
            if (cursor == null || !cursor.moveToFirst()) return;
            url = cursor.getString(0);
            mimeType = cursor.getString(1);
            mediaKey = cursor.getString(2);
            if (isNewsletter) {
                String directPath = cursor.getString(3);
                if (directPath == null) return;
                url = "https://mmg.whatsapp.net" + directPath;
            }
        } catch (Exception failure) {
            logDebug(failure);
            return;
        }
        if (url == null || mimeType == null) return;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean dismissed = new AtomicBoolean();
        File output;
        try {
            output = File.createTempFile("mediapreview-", mimeType.startsWith("image") ? ".jpg" : ".mp4",
                    Utils.getApplication().getCacheDir());
        } catch (IOException failure) {
            executor.shutdownNow();
            logDebug(failure);
            return;
        }
        WebView webView = new WebView(context);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadDataWithBaseURL(null, HTML_LOADING.replace("$loading",
                com.waenhancer.xposed.core.FeatureLoader.getModuleString(
                        Utils.getApplication(), R.string.loading)), "text/html", "UTF-8", null);
        AlertDialog previewDialog = new AlertDialog.Builder(context).setView(webView).create();
        previewDialog.setOnDismissListener(ignored -> {
            dismissed.set(true);
            executor.shutdownNow();
            output.delete();
            webView.stopLoading();
            webView.destroy();
        });
        try {
            previewDialog.show();
            String requestUrl = url;
            executor.execute(() -> decodeMedia(requestUrl, mediaKey, mimeType,
                    executor, webView, previewDialog, output, dismissed, isNewsletter));
        } catch (Exception failure) {
            dismissed.set(true);
            executor.shutdownNow();
            output.delete();
            if (previewDialog.isShowing()) previewDialog.dismiss();
            else webView.destroy();
            logDebug(failure);
        }
    }

    /** Stream the download to disk; each preview owns its files, worker, and dialog. */
    private void decodeMedia(String url, String mediaKey, String mimeType, ExecutorService executor,
                             WebView webView, AlertDialog previewDialog, File output,
                             AtomicBoolean dismissed, boolean isNewsletter) {
        File encrypted = null;
        try {
            encrypted = File.createTempFile("mediapreview-", ".enc", output.getParentFile());
            try (okhttp3.Response response = new OkHttpClient.Builder()
                    .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                            .addHeader("User-Agent", "Chrome/117.0.5938.150").build()))
                    .build().newCall(new Request.Builder().url(url).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null)
                    throw new IOException("Media download failed: HTTP " + response.code());
                try (java.io.InputStream input = response.body().byteStream();
                     FileOutputStream download = new FileOutputStream(encrypted)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (dismissed.get() || Thread.currentThread().isInterrupted()) return;
                        download.write(buffer, 0, count);
                    }
                }
            }
            if (dismissed.get()) return;
            if (isNewsletter) {
                java.nio.file.Files.copy(encrypted.toPath(), output.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                decryptMediaFile(encrypted, output, mediaKey, mimeType);
            }
            if (output.length() == 0) throw new IOException("Empty media file");
            webView.post(() -> {
                if (dismissed.get() || !previewDialog.isShowing()) return;
                String fileUrl = "file://" + output.getAbsolutePath();
                webView.loadDataWithBaseURL(null,
                        (mimeType.startsWith("image") ? HTML_IMAGE : HTML_VIDEO).replace("$url", fileUrl),
                        "text/html", "UTF-8", null);
            });
        } catch (Throwable failure) {
            logDebug(failure);
            webView.post(() -> {
                if (dismissed.get()) return;
                Utils.showToast("Media preview failed", Toast.LENGTH_LONG);
                if (previewDialog.isShowing()) {
                    try { previewDialog.dismiss(); } catch (IllegalArgumentException ignored) {}
                }
            });
        } finally {
            if (encrypted != null) encrypted.delete();
            if (dismissed.get()) output.delete();
            executor.shutdown();
        }
    }

    private void decryptMediaFile(File encrypted, File output, String mediaKey, String mimeType)
            throws Exception {
        if (mediaKey == null || !mediaKey.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Invalid media key");
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 64; i += 2) {
            keyBytes[i / 2] = (byte) ((Character.digit(mediaKey.charAt(i), 16) << 4)
                    + Character.digit(mediaKey.charAt(i + 1), 16));
        }
        String normalizedMime = mimeType.split(";", 2)[0].trim();
        byte[] typeKey = MEDIA_KEYS.get(normalizedMime);
        if (typeKey == null) typeKey = MEDIA_KEYS.get(normalizedMime.split("/", 2)[0]);
        if (typeKey == null) typeKey = MEDIA_KEYS.get("document");
        byte[] derivedKey = HKDF.createFor(3).deriveSecrets(keyBytes, typeKey, 112);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(derivedKey, 16, 48), "AES"),
                new IvParameterSpec(Arrays.copyOfRange(derivedKey, 0, 16)));
        MediaPreviewPayload.decrypt(encrypted, output, cipher);
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Media Preview";
    }
}
