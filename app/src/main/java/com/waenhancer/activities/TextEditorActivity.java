package com.waenhancer.activities;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.waenhancer.R;
import com.waenhancer.BuildConfig;
import com.waenhancer.App;
import com.waenhancer.diagnostics.LocalDiagnostics;
import com.waenhancer.activities.base.BaseActivity;
import com.waenhancer.preference.ThemePreference;
import com.waenhancer.theme.CssSafetyManager;
import com.waenhancer.xposed.utils.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import kotlin.io.FilesKt;
import rikka.core.util.IOUtils;

public class TextEditorActivity extends BaseActivity {
    // private CodeView codeView;
    private String folderName;
    private String preferenceKey;
    private ActivityResultLauncher<String> mGetContent;
    private ActivityResultLauncher<String> mExportFile;
    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_editor);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        updateWebViewContent("");

        FrameLayout container = findViewById(R.id.webViewContainer);
        container.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        mGetContent = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onUriSelected);
        mExportFile = registerForActivityResult(new ActivityResultContracts.CreateDocument("*/*"), this::exportAsZip);

        folderName = getIntent().getStringExtra("folder_name");
        preferenceKey = getIntent().getStringExtra("key");
        if (TextUtils.isEmpty(preferenceKey)) {
            preferenceKey = "folder_theme";
        }
        if (!TextUtils.isEmpty(folderName)) {
            readFile(folderName);
        }

    }

    @SuppressLint("SetJavaScriptEnabled")
    private void updateWebViewContent(String newContent) {
        if (webView != null) {
            try {
                var inputStream = getAssets().open("css_editor.html");
                var code = IOUtils.toString(inputStream);
                code = code.replace("{{content}}", newContent);
                webView.loadDataWithBaseURL("file:///android_asset/", code, "text/html", "UTF-8", null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private CompletableFuture<String> getTextareaContentAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (webView != null) {
            webView.evaluateJavascript("getTextareaContent();", content -> {
                if (content != null) {
                    content = content.substring(1, content.length() - 1)
                            .replace("\\n", "\n")
                            .replace("\\r", "\r")
                            .replace("\\\"", "\"")
                            .replace("\\'", "'")
                            .replace("\\\\", "\\");
                }
                future.complete(content);
            });
        } else {
            future.completeExceptionally(new Exception("WebView is null"));
        }
        return future;
    }

    private void readFile(String folderName) {
        try {
            File folderFolder = new File(ThemePreference.rootDirectory, folderName);
            File cssCode = new File(folderFolder, "style.css");
            if (cssCode.exists()) {
                var code = FilesKt.readText(cssCode, Charset.defaultCharset());
                updateWebViewContent(code);
                // codeView.setText(code);
            } else {
                cssCode.createNewFile();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private android.os.Handler testExpiryHandler;
    private Runnable testExpiryTask;

    /** Restarts WhatsApp once the temporary test expires, cancelling any previous timer. */
    private void scheduleTestExpiry() {
        cancelTestExpiry();
        testExpiryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        testExpiryTask = () -> {
            LocalDiagnostics.record(this, "css", "Temporary CSS test expired");
            notifyCssChanged();
            restartWhatsAppVariants();
        };
        testExpiryHandler.postDelayed(testExpiryTask, CssSafetyManager.DEFAULT_TEST_DURATION_MS);
    }

    private void cancelTestExpiry() {
        if (testExpiryHandler != null && testExpiryTask != null) {
            testExpiryHandler.removeCallbacks(testExpiryTask);
        }
        testExpiryHandler = null;
        testExpiryTask = null;
    }

    @Override
    protected void onDestroy() {
        // effectiveCss() reconciles KEY_TEST_EXPIRES_AT lazily, so dropping the timer here only
        // skips the courtesy restart - it never leaves the test CSS active past its deadline.
        cancelTestExpiry();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.css_editor_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menuitem_save -> {
                getTextareaContentAsync().thenAccept(content ->
                        runOnUiThread(() -> saveThemeContent(content)));
                return true;
            }
            case R.id.menuitem_test_theme -> {
                getTextareaContentAsync().thenAccept(content ->
                        runOnUiThread(() -> {
                            var preferences = PreferenceManager.getDefaultSharedPreferences(this);
                            CssSafetyManager.SaveResult result = CssSafetyManager.beginTest(
                                    preferences, content, CssSafetyManager.DEFAULT_TEST_DURATION_MS);
                            if (!result.saved) {
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle(getString(R.string.css_validation_failed))
                                        .setMessage(result.validation.message())
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show();
                                return;
                            }
                            LocalDiagnostics.record(this, "css",
                                    "Temporary two-minute CSS test started");
                            notifyCssChanged();
                            restartWhatsAppVariants();
                            scheduleTestExpiry();
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle(R.string.css_test_title)
                                    .setMessage(R.string.css_test_message)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        }));
                return true;
            }
            case R.id.menuitem_rollback_theme -> {
                var preferences = PreferenceManager.getDefaultSharedPreferences(this);
                boolean restored = CssSafetyManager.rollback(preferences);
                LocalDiagnostics.record(this, "css", restored
                        ? "Previous valid CSS restored" : "CSS rollback unavailable");
                if (restored) {
                    notifyCssChanged();
                    restartWhatsAppVariants();
                }
                Toast.makeText(this, restored
                                ? R.string.css_rollback_done
                                : R.string.css_rollback_unavailable,
                        Toast.LENGTH_LONG).show();
                return true;
            }
            case R.id.menuitem_css_safe_mode_exit -> {
                var preferences = PreferenceManager.getDefaultSharedPreferences(this);
                CssSafetyManager.disableSafeMode(preferences);
                LocalDiagnostics.record(this, "css", "CSS safe mode disabled manually");
                notifyCssChanged();
                restartWhatsAppVariants();
                Toast.makeText(this, R.string.css_safe_mode_disabled, Toast.LENGTH_LONG).show();
                return true;
            }
            case R.id.menuitem_css_safe_mode -> {
                var preferences = PreferenceManager.getDefaultSharedPreferences(this);
                CssSafetyManager.enableSafeMode(preferences);
                LocalDiagnostics.record(this, "css", "CSS safe mode enabled manually");
                notifyCssChanged();
                restartWhatsAppVariants();
                Toast.makeText(this, R.string.css_safe_mode_enabled, Toast.LENGTH_LONG).show();
                return true;
            }
            case R.id.menuitem_exit -> {
                finish();
                return true;
            }
            case R.id.menuitem_clear -> {
                updateWebViewContent("");
                return true;
            }
            case R.id.menuitem_import_image -> {
                mGetContent.launch("image/*");
                return true;
            }
            case R.id.menuitem_export -> {
                mExportFile.launch(folderName + ".zip");
                return true;
            }
            default -> {
                return super.onOptionsItemSelected(item);
            }
        }
    }

    private void saveThemeContent(String content) {
        try {
            var preferences = PreferenceManager.getDefaultSharedPreferences(this);
            CssSafetyManager.ValidationResult validation = CssSafetyManager.validate(content);
            if (!validation.valid) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.css_validation_failed))
                        .setMessage(validation.message())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }

            String selectedTheme = preferences.getString(preferenceKey, null);
            boolean activeTheme = !TextUtils.isEmpty(folderName)
                    && folderName.equals(selectedTheme);
            if (activeTheme) {
                CssSafetyManager.SaveResult result = CssSafetyManager.save(preferences, content);
                if (!result.saved) {
                    Toast.makeText(this, R.string.css_active_state_failed,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                validation = result.validation;
            }

            File folder = new File(ThemePreference.rootDirectory, folderName);
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IllegalStateException("Could not create the theme folder");
            }
            File cssFile = new File(folder, "style.css");
            FilesKt.writeText(cssFile, content == null ? "" : content,
                    Charset.defaultCharset());

            LocalDiagnostics.record(this, "css", activeTheme
                    ? "Validated active CSS saved"
                    : "Validated inactive theme CSS saved without activation");
            if (activeTheme) {
                notifyCssChanged();
            }
            Toast.makeText(this,
                    validation.warnings.isEmpty()
                            ? getString(R.string.saved)
                            : "Saved with warnings: " + validation.message(),
                    Toast.LENGTH_LONG).show();
        } catch (Exception exception) {
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void notifyCssChanged() {
        try {
            getContentResolver().notifyChange(
                    Uri.parse("content://" + BuildConfig.APPLICATION_ID
                            + ".hookprovider/preferences"), null);
        } catch (RuntimeException ignored) {
        }
    }

    private void restartWhatsAppVariants() {
        App.getInstance().restartApp("com.whatsapp");
        App.getInstance().restartApp("com.whatsapp.w4b");
    }

    private void exportAsZip(Uri uri) {
        try (var outputStream = getContentResolver().openOutputStream(uri)) {
            var zipOutputStream = new ZipOutputStream(outputStream);
            var dir = ThemePreference.rootDirectory.getAbsolutePath() + "/";
            var folderFolder = new File(ThemePreference.rootDirectory, folderName);
            var files = getAllFilesPath(folderFolder);
            for (File file : files) {
                var name = file.getAbsolutePath().replace(dir, "");
                zipOutputStream.putNextEntry(new ZipEntry(name));
                var bytes = FilesKt.readBytes(file);
                zipOutputStream.write(bytes);
                zipOutputStream.closeEntry();
            }
            zipOutputStream.close();
            Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Utils.showToast("Error: " + e.getMessage(), 1);
        }
    }

    private List<File> getAllFilesPath(File folderFolder) {
        File[] files = folderFolder.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        ArrayList<File> list = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory()) {
                list.addAll(getAllFilesPath(file));
            } else {
                list.add(file);
            }
        }
        return list;
    }

    public void onUriSelected(Uri uri) {
        if (uri == null) {
            return;
        }
        com.waenhancer.ui.helpers.BottomSheetHelper.showInput(
                this,
                getString(R.string.enter_image_file_name),
                "example.png",
                "OK",
                fileName -> {
                    if (fileName.endsWith(".png")) {
                        copyFromUri(fileName, uri);
                    } else {
                        Toast.makeText(this, R.string.error_image_name, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    public void copyFromUri(String fileName, Uri uri) {
        var outFolder = new File(ThemePreference.rootDirectory, folderName);
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            var outFile = new File(outFolder, fileName);
            FileOutputStream out = new FileOutputStream(outFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
            out.close();
            Toast.makeText(this, getString(R.string.imported_as) + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}