package com.waenhancer.ui.fragments;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;

import com.waenhancer.App;
import com.waenhancer.backup.BackupCodec;
import com.waenhancer.backup.FullBackupCrypto;
import com.waenhancer.backup.FullBackupManager;
import com.waenhancer.BuildConfig;
import com.waenhancer.R;
import com.waenhancer.UpdateChecker;
import com.waenhancer.UpdateDownloader;
import com.waenhancer.activities.MainActivity;
import com.waenhancer.activities.ChangelogActivity;
import com.waenhancer.databinding.FragmentHomeBinding;
import com.waenhancer.ui.fragments.base.BaseFragment;
import com.waenhancer.utils.FilePicker;
import com.waenhancer.utils.ApkMirrorFeedHelper;
import com.waenhancer.xposed.core.FeatureLoader;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.devkit.UnobfuscatorCache;
import com.waenhancer.xposed.utils.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;

import rikka.core.util.IOUtils;

import java.io.File;

public class HomeFragment extends BaseFragment {

    /**
     * Import cap for a full backup container. {@code FullBackupManager} builds and validates its
     * manifest wholly in memory and caps media at 96 MB, which Base64 inflates to roughly 4/3 of
     * that; this leaves headroom over the largest container the exporter can produce.
     */
    private static final long MAX_FULL_BACKUP_BYTES = 160L * 1024L * 1024L;

    private static final String RELEASES_URL = "https://github.com/igorcv88/WaEnhancerXCommunity/releases";
    private static final String LATEST_STABLE_URL = "https://github.com/igorcv88/WaEnhancerXCommunity/releases/latest";

    /**
     * In-memory flag — reset to false every time the app process starts.
     * Becomes true only when WhatsApp/Business sends a live broadcast response
     * in the current session, proving the Xposed hook is actually running.
     * Using SharedPreferences here caused a false "Module Enabled" status
     * when the module was disabled in LSPosed after a previous active session.
     */
    private static volatile long sLastHeartbeatTime = 0L;

    private FragmentHomeBinding binding;
    private String pendingUpdateUrl;
    private String pendingUpdateVersion;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        var intentFilter = new IntentFilter(BuildConfig.APPLICATION_ID + ".RECEIVER_WPP");
        ContextCompat.registerReceiver(requireContext(), new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                String pkg = intent.getStringExtra("PKG");
                ;
                try {
                    if (FeatureLoader.PACKAGE_WPP.equals(pkg)) {
                        receiverBroadcastWpp(context, intent);
                    } else {
                        receiverBroadcastBusiness(context, intent);
                    }
                } catch (Exception e) {
                    Log.e("WAE_STATUS", "Error in receiverBroadcast: " + e.getMessage());
                }
            }
        }, intentFilter, ContextCompat.RECEIVER_EXPORTED);
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
            ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);

        // Migration: remove the legacy disk-persisted heartbeat key introduced in older builds.
        // That key caused a false "Module Enabled" status when the module was disabled in
        // LSPosed between app restarts. The heartbeat is now an in-memory flag only.
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().remove("module_heartbeat").apply();

        checkStateWpp(requireActivity());

        ApkMirrorFeedHelper.fetchVersionsIfNeeded(requireContext(), () -> {
            if (getActivity() != null && isAdded()) {
                checkStateWpp(getActivity());
            }
        });

        binding.rebootBtn.setOnClickListener(view -> {
            animateClick(view);
            App.getInstance().restartApp(FeatureLoader.PACKAGE_WPP);
            disableWpp(requireActivity());
        });

        binding.rebootBtn2.setOnClickListener(view -> {
            animateClick(view);
            App.getInstance().restartApp(FeatureLoader.PACKAGE_BUSINESS);
            disableBusiness(requireActivity());
        });

        binding.exportBtn.setOnClickListener(view -> {
            animateClick(view);
            chooseExportFormat(this.getContext());
        });

        binding.importBtn.setOnClickListener(view -> {
            animateClick(view);
            importConfigs(this.getContext());
        });

        binding.resetBtn.setOnClickListener(view -> {
            animateClick(view);
            showResetBottomSheet();
        });

        binding.viewSupportedVersionsBtn.setOnClickListener(view -> {
            animateClick(view);
            startActivity(new Intent(requireContext(), SupportedVersionsActivity.class));
        });

        binding.btnReportIssue.setOnClickListener(view -> {
            animateClick(view);
            try {
                String fwRaw = getXposedFrameworkVersion();
                String fwLabel = "Xposed/LSPosed API";
                String fwValue = fwRaw;
                String[] parts = fwRaw.split("\\|");
                if (parts.length == 2) {
                    fwLabel = parts[0] + " API";
                    fwValue = parts[1];
                }

                String dialogDetailsHtml = "<b>Device:</b> " + android.os.Build.MANUFACTURER + " "
                        + android.os.Build.MODEL + "<br>"
                        + "<b>Android Version:</b> " + android.os.Build.VERSION.RELEASE + " (SDK "
                        + android.os.Build.VERSION.SDK_INT + ")<br>"
                        + "<b>" + fwLabel + ":</b> " + fwValue + "<br>"
                        + "<b>Module Version:</b> " + com.waenhancer.BuildConfig.VERSION_NAME + "<br>";

                String githubDetailsMd = "**Device:** " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                        + "\n"
                        + "**Android Version:** " + android.os.Build.VERSION.RELEASE + " (SDK "
                        + android.os.Build.VERSION.SDK_INT + ")\n"
                        + "**" + fwLabel + ":** " + fwValue + "\n"
                        + "**Module Version:** " + com.waenhancer.BuildConfig.VERSION_NAME + "\n";

                String tempWaVersion = "Not Installed";
                try {
                    android.content.pm.PackageInfo pInfo = requireContext().getPackageManager()
                            .getPackageInfo(com.waenhancer.xposed.core.FeatureLoader.PACKAGE_WPP, 0);
                    tempWaVersion = pInfo.versionName;
                } catch (Exception e) {
                }
                final String waVersion = tempWaVersion;

                String tempWaBusinessVersion = "Not Installed";
                try {
                    android.content.pm.PackageInfo pInfo = requireContext().getPackageManager()
                            .getPackageInfo(com.waenhancer.xposed.core.FeatureLoader.PACKAGE_BUSINESS, 0);
                    tempWaBusinessVersion = pInfo.versionName;
                } catch (Exception e) {
                }
                final String waBusinessVersion = tempWaBusinessVersion;

                final String finalDialogDetails = dialogDetailsHtml;
                final String finalGithubDetails = githubDetailsMd;

                String dialogMessageHtml = "This will open the WaEnhancer Community GitHub Issues page to report a bug.<br><br>"
                        + "The following information about your device and installed apps will be pre-filled in your report:<br><br>"
                        + finalDialogDetails + "<b>WhatsApp Version:</b> " + waVersion + "<br>"
                        + "<b>WhatsApp Business Version:</b> " + waBusinessVersion + "<br>";

                var bottomSheetDialog = com.waenhancer.ui.helpers.BottomSheetHelper
                        .createStyledDialog(requireContext());
                var sheetView = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_sheet_report_issue, null);
                bottomSheetDialog.setContentView(sheetView);

                var bottomSheet = bottomSheetDialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (bottomSheet != null) {
                    bottomSheet.setBackgroundResource(android.R.color.transparent);
                }

                android.widget.TextView deviceDetailsText = sheetView.findViewById(R.id.device_details);
                deviceDetailsText.setText(androidx.core.text.HtmlCompat.fromHtml(dialogMessageHtml,
                        androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY));

                com.google.android.material.progressindicator.LinearProgressIndicator progressBar = sheetView.findViewById(R.id.progress_bar);
                progressBar.setMax(100);
                progressBar.setProgressCompat(33, true);

                android.widget.ViewFlipper viewFlipper = sheetView.findViewById(R.id.view_flipper);
                viewFlipper.setInAnimation(requireContext(), android.R.anim.fade_in);
                viewFlipper.setOutAnimation(requireContext(), android.R.anim.fade_out);

                com.google.android.material.textfield.TextInputEditText titleInput = sheetView.findViewById(R.id.title_input);
                com.google.android.material.textfield.TextInputEditText issueInput = sheetView.findViewById(R.id.issue_input);
                com.google.android.material.textfield.TextInputLayout inputLayout = sheetView.findViewById(R.id.input_layout);

                com.google.android.material.button.MaterialButton btnCancel = sheetView.findViewById(R.id.btn_cancel);
                com.google.android.material.button.MaterialButton btnNext = sheetView.findViewById(R.id.btn_next);

                titleInput.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (viewFlipper.getDisplayedChild() == 1) {
                            int len = s != null ? s.toString().trim().length() : 0;
                            btnNext.setEnabled(len >= 15 && len <= 50);
                        }
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                    }
                });

                issueInput.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (viewFlipper.getDisplayedChild() == 2) {
                            btnNext.setEnabled(s != null && s.toString().trim().length() >= 15);
                        }
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                    }
                });

                btnCancel.setOnClickListener(v -> {
                    if (viewFlipper.getDisplayedChild() > 0) {
                        viewFlipper.showPrevious();
                        if (viewFlipper.getDisplayedChild() == 0) {
                            progressBar.setProgressCompat(33, true);
                            btnCancel.setText(R.string.cancel);
                            btnNext.setText("Next");
                            btnNext.setEnabled(true);
                        } else if (viewFlipper.getDisplayedChild() == 1) {
                            progressBar.setProgressCompat(66, true);
                            btnCancel.setText("Back");
                            btnNext.setText("Next");
                            int len = titleInput.getText() != null ? titleInput.getText().toString().trim().length() : 0;
                            btnNext.setEnabled(len >= 15 && len <= 50);
                        }
                    } else {
                        bottomSheetDialog.dismiss();
                    }
                });

                btnNext.setOnClickListener(v -> {
                    if (viewFlipper.getDisplayedChild() == 0) {
                        viewFlipper.showNext();
                        progressBar.setProgressCompat(66, true);
                        btnCancel.setText("Back");
                        int len = titleInput.getText() != null ? titleInput.getText().toString().trim().length() : 0;
                        btnNext.setEnabled(len >= 15 && len <= 50);
                    } else if (viewFlipper.getDisplayedChild() == 1) {
                        viewFlipper.showNext();
                        progressBar.setProgressCompat(100, true);
                        btnNext.setText("Continue");
                        btnCancel.setText("Back");
                        int len = issueInput.getText() != null ? issueInput.getText().toString().trim().length() : 0;
                        btnNext.setEnabled(len >= 15);
                    } else {
                        String title = titleInput.getText() != null ? titleInput.getText().toString().trim() : "Bug Report";
                        String description = issueInput.getText() != null ? issueInput.getText().toString().trim() : "";
                        try {
                            String body = finalGithubDetails + "**WhatsApp Version:** " + waVersion + "\n"
                                    + "**WhatsApp Business Version:** " + waBusinessVersion + "\n"
                                    + "\n---\n"
                                    + description + "\n";

                            String url = "https://github.com/igorcv88/WaEnhancerXCommunity/issues/new?title="
                                    + java.net.URLEncoder.encode(title, "UTF-8") + "&body="
                                    + java.net.URLEncoder.encode(body, "UTF-8");
                            openUrl(requireContext(), url);
                            bottomSheetDialog.dismiss();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });

                bottomSheetDialog.show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // The Community fork has no Telegram channel. Keep the legacy view hidden so old
        // layouts remain source-compatible while the only community destination is GitHub.
        binding.telegramBtn.setVisibility(View.GONE);

        binding.githubBtn.setOnClickListener(view -> {
            animateClick(view);
            openUrl(requireContext(), "https://github.com/igorcv88/WaEnhancerXCommunity/issues");
        });

        binding.clearCacheBtn.setOnClickListener(view -> {
            animateClick(view);
            showClearCacheConfirmation();
        });

        binding.statusSummary.setOnClickListener(v -> {
            animateClick(v);
            Intent intent = new Intent(requireContext(), ChangelogActivity.class);
            startActivity(intent);
        });

        setupReleaseChannelSelector();
        setupUpdateBanner();
        startCardAnimations();


        return binding.getRoot();
    }

    private void openUrl(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }

    private void openTelegramChannel(Context context) {
        String channelUrl = "https://t.me/WaEnhancerX";
        String installedPackage = Utils.getInstalledTelegramPackage(context);

        if (installedPackage != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(channelUrl));
            try {
                intent.setPackage(installedPackage);
                context.startActivity(intent);
            } catch (Exception e) {
                context.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(channelUrl)));
            }
        } else {
            Toast.makeText(context, "Telegram app is not installed", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCardAnimations() {
        Context context = getContext();
        if (context == null) {
            return;
        }

        var slideUp = AnimationUtils.loadAnimation(context, R.anim.slide_up);
        var fadeIn = AnimationUtils.loadAnimation(context, R.anim.fade_in);

        binding.status.startAnimation(slideUp);

        binding.status2.postDelayed(() -> {
            if (getContext() == null || !isAdded()) {
                return;
            }
            var anim = AnimationUtils.loadAnimation(getContext(), R.anim.slide_up);
            binding.status2.startAnimation(anim);
        }, 100);

        binding.status3.postDelayed(() -> {
            if (getContext() == null || !isAdded()) {
                return;
            }
            var anim = AnimationUtils.loadAnimation(getContext(), R.anim.slide_up);
            binding.status3.startAnimation(anim);
        }, 100);

        binding.infoCard.postDelayed(() -> {
            if (getContext() == null || !isAdded()) {
                return;
            }
            binding.infoCard.startAnimation(fadeIn);
        }, 200);
    }

    private void animateClick(View view) {
        Context context = getContext();
        if (context != null) {
            var scaleIn = AnimationUtils.loadAnimation(context, R.anim.scale_in);
            view.startAnimation(scaleIn);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setDisplayHomeAsUpEnabled(false);
        syncReleaseChannelToInstalled();
        checkForUpdates();
        checkStateWpp(requireActivity());
    }

    @SuppressLint("StringFormatInvalid")
    private void receiverBroadcastBusiness(Context context, Intent intent) {
        markModuleActive();
        updateModuleStatusUi(MainActivity.isXposedFrameworkPresent(context), com.waenhancer.utils.ModuleStatus.isModuleActive(), true);
        binding.statusTitle3.setText(R.string.business_in_background);
        var version = intent.getStringExtra("VERSION");
        var supported_list = Arrays.asList(context.getResources().getStringArray(R.array.supported_versions_business));
        if (version != null && supported_list.stream().anyMatch(s -> version.startsWith(s.replace(".xx", "")))) {
            binding.statusSummary3.setText(getString(R.string.version_s, version));
            binding.statusDotBusiness.setBackgroundResource(R.drawable.status_dot_active);
        } else {
            binding.statusSummary3.setText(getString(R.string.version_s_not_listed, version));
            binding.statusDotBusiness.setBackgroundResource(R.drawable.status_dot_inactive);
        }
        binding.rebootBtn2.setVisibility(View.VISIBLE);
        binding.statusSummary3.setVisibility(View.VISIBLE);
    }

    @SuppressLint("StringFormatInvalid")
    private void receiverBroadcastWpp(Context context, Intent intent) {
        markModuleActive();
        updateModuleStatusUi(MainActivity.isXposedFrameworkPresent(context), com.waenhancer.utils.ModuleStatus.isModuleActive(), true);
        binding.statusTitle2.setText(R.string.whatsapp_in_background);
        var version = intent.getStringExtra("VERSION");
        var supported_list = Arrays.asList(context.getResources().getStringArray(R.array.supported_versions_wpp));

        if (version != null && supported_list.stream().anyMatch(s -> version.startsWith(s.replace(".xx", "")))) {
            binding.statusSummary1.setText(getString(R.string.version_s, version));
            binding.statusDotWpp.setBackgroundResource(R.drawable.status_dot_active);
        } else {
            binding.statusSummary1.setText(getString(R.string.version_s_not_listed, version));
            binding.statusDotWpp.setBackgroundResource(R.drawable.status_dot_inactive);
        }
        binding.rebootBtn.setVisibility(View.VISIBLE);
        binding.statusSummary1.setVisibility(View.VISIBLE);
    }

    private void showResetBottomSheet() {
        var context = requireContext();
        var bottomSheetDialog = com.waenhancer.ui.helpers.BottomSheetHelper
                .createStyledDialog(context);
        var sheetView = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_reset, null);
        bottomSheetDialog.setContentView(sheetView);

        var bottomSheet = bottomSheetDialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            bottomSheet.setBackgroundResource(android.R.color.transparent);
        }

        sheetView.findViewById(R.id.confirm_reset_btn).setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            resetConfigs(context);
        });

        sheetView.findViewById(R.id.cancel_reset_btn).setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
        });

        bottomSheetDialog.show();
    }

    private void resetConfigs(Context context) {
        var prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.getAll().forEach((key, value) -> prefs.edit().remove(key).apply());
        App.getInstance().restartApp(FeatureLoader.PACKAGE_WPP);
        App.getInstance().restartApp(FeatureLoader.PACKAGE_BUSINESS);
        Utils.showToast(context.getString(R.string.configs_reset), Toast.LENGTH_SHORT);
        if (getActivity() != null && context.getPackageName().equals(BuildConfig.APPLICATION_ID)) {
            getActivity().recreate();
        }
    }

    /**
     * Two backups exist and they carry different things, so the user picks before exporting.
     *
     * <p>The settings export is the allowlisted, shareable JSON. The full backup is the
     * password-encrypted container from {@link FullBackupManager}: deleted messages, their media
     * and the private secrets, none of which the settings export is allowed to contain.</p>
     */
    private void chooseExportFormat(Context context) {
        com.waenhancer.ui.helpers.BottomSheetHelper.showSingleChoice(
                requireActivity(),
                getString(R.string.backup_export_choose_title),
                new CharSequence[]{
                        getString(R.string.backup_export_settings_option),
                        getString(R.string.backup_export_full_option)},
                new CharSequence[]{"settings", "full"},
                null,
                (index, value) -> {
                    if ("full".equals(value)) {
                        saveFullBackup(context);
                    } else {
                        saveConfigs(context);
                    }
                });
    }

    /** Password-encrypted export of deleted data, its media and the private secrets. */
    private void saveFullBackup(Context context) {
        if (FilePicker.fileSalve == null) {
            Toast.makeText(context,
                    "Please use the standalone WaEnhancer Community app for file operations.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        com.waenhancer.ui.helpers.BottomSheetHelper.showInput(
                requireActivity(),
                getString(R.string.backup_full_password_title),
                getString(R.string.backup_full_password_hint),
                getString(R.string.backup_continue),
                password -> {
                    if (password == null || password.trim().isEmpty()) {
                        Toast.makeText(context, R.string.backup_full_password_required,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    com.waenhancer.ui.helpers.BottomSheetHelper.showSingleChoice(
                            requireActivity(),
                            getString(R.string.backup_full_media_title),
                            new CharSequence[]{
                                    getString(R.string.backup_full_media_include),
                                    getString(R.string.backup_full_media_exclude)},
                            new CharSequence[]{"with_media", "no_media"},
                            null,
                            (index, value) -> launchFullBackupExport(
                                    context, password, "with_media".equals(value)));
                });
    }

    private void launchFullBackupExport(Context context, String password, boolean includeMedia) {
        FilePicker.setOnUriPickedListener(uri -> {
            char[] secret = password.toCharArray();
            runAsync(() -> {
                try (var output = context.getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("Unable to open destination.");
                    output.write(FullBackupManager.export(context, secret, includeMedia));
                    runOnUiThread(() -> {
                        if (!isAdded()) return;
                        com.waenhancer.ui.helpers.BottomSheetHelper.showInfo(
                                requireActivity(),
                                getString(R.string.backup_full_saved_title),
                                getString(includeMedia
                                        ? R.string.backup_full_saved_message
                                        : R.string.backup_full_saved_message_no_media));
                    });
                } catch (Exception exception) {
                    Log.e("saveFullBackup", "Unable to export full backup", exception);
                    runOnUiThread(() -> Toast.makeText(context, exception.getMessage(),
                            Toast.LENGTH_LONG).show());
                } finally {
                    java.util.Arrays.fill(secret, '\0');
                }
            });
        });
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        FilePicker.fileSalve.launch("WaEnhancerCommunity-full-"
                + format.format(new Date()) + ".waeb");
    }

    /** Restores a full backup once the user supplies the password it was sealed with. */
    private void restoreFullBackup(Context context, byte[] container) {
        com.waenhancer.ui.helpers.BottomSheetHelper.showInput(
                requireActivity(),
                getString(R.string.backup_full_restore_title),
                getString(R.string.backup_full_password_hint),
                getString(R.string.backup_restore),
                password -> {
                    if (password == null || password.isEmpty()) {
                        Toast.makeText(context, R.string.backup_full_password_required,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    char[] secret = password.toCharArray();
                    runAsync(() -> {
                        try {
                            FullBackupManager.RestoreReport report =
                                    FullBackupManager.restore(context, container, secret);
                            runOnUiThread(() -> {
                                if (!isAdded()) return;
                                com.waenhancer.ui.helpers.BottomSheetHelper.showInfo(
                                        requireActivity(),
                                        getString(R.string.backup_full_restored_title),
                                        getString(R.string.backup_full_restored_message,
                                                report.messages, report.media, report.secrets));
                            });
                        } catch (Exception exception) {
                            Log.e("restoreFullBackup", "Unable to restore full backup", exception);
                            runOnUiThread(() -> Toast.makeText(context, exception.getMessage(),
                                    Toast.LENGTH_LONG).show());
                        } finally {
                            java.util.Arrays.fill(secret, '\0');
                        }
                    });
                });
    }

    private void saveConfigs(Context context) {
        if (FilePicker.fileSalve == null) {
            Toast.makeText(context,
                    "Please use the standalone WaEnhancer Community app for file operations.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Runnable launchExport = () -> {
            FilePicker.setOnUriPickedListener(uri -> {
                try (var output = context.getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("Unable to open destination.");
                    SharedPreferences preferences =
                            PreferenceManager.getDefaultSharedPreferences(context);
                    String backup = BackupCodec.exportSettings(
                            preferences, BuildConfig.VERSION_NAME);
                    output.write(backup.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    preferences.edit().putBoolean("backup_privacy_notice_seen", true).apply();

                    BackupCodec.ExcludedSummary excluded = BackupCodec.excludedFrom(preferences);
                    if (excluded.hasSecrets()) {
                        com.waenhancer.ui.helpers.BottomSheetHelper.showInfo(
                                requireActivity(),
                                getString(R.string.configs_saved),
                                excluded.secretsNotice());
                    } else {
                        Toast.makeText(context, R.string.configs_saved, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception exception) {
                    Log.e("saveConfigs", "Unable to export settings", exception);
                    Toast.makeText(context, exception.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
            FilePicker.fileSalve.launch("WaEnhancerCommunity-settings-"
                    + format.format(new Date()) + ".json");
        };

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        StringBuilder notice = new StringBuilder(
                "The export contains only allowlisted module settings. It excludes keys, tokens, "
                        + "certificates, internal paths, diagnostics, messages and media. "
                        + "Review the file before sharing it.");
        BackupCodec.ExcludedSummary excluded = BackupCodec.excludedFrom(preferences);
        if (excluded.hasSecrets()) {
            notice.append("\n\n").append(excluded.secretsNotice());
        }

        com.waenhancer.ui.helpers.BottomSheetHelper.showConfirmation(
                requireActivity(),
                "Export settings",
                notice.toString(),
                "Export",
                false,
                () -> launchExport.run());
    }

    private void importConfigs(Context context) {
        if (FilePicker.fileCapture == null) {
            Toast.makeText(context,
                    "Please use the standalone WaEnhancer Community app for file operations.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        FilePicker.setOnUriPickedListener(uri -> {
            try (var input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Unable to open backup.");

                byte[] prefix = new byte[FullBackupCrypto.MAGIC_LENGTH];
                int prefixRead = 0;
                while (prefixRead < prefix.length) {
                    int read = input.read(prefix, prefixRead, prefix.length - prefixRead);
                    if (read == -1) break;
                    prefixRead += read;
                }
                boolean isFullBackup = prefixRead == prefix.length
                        && FullBackupCrypto.isFullBackup(prefix);
                long limit = isFullBackup ? MAX_FULL_BACKUP_BYTES : BackupCodec.MAX_BYTES;

                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                buffer.write(prefix, 0, prefixRead);
                byte[] chunk = new byte[8192];
                long total = prefixRead;
                int read;
                while ((read = input.read(chunk)) != -1) {
                    total += read;
                    if (total > limit) {
                        throw new BackupCodec.BackupException(isFullBackup
                                ? "Full backup exceeds the " + (MAX_FULL_BACKUP_BYTES / (1024 * 1024))
                                        + " MB safety limit."
                                : "Backup exceeds the 2 MB safety limit.");
                    }
                    buffer.write(chunk, 0, read);
                }

                if (isFullBackup) {
                    restoreFullBackup(context, buffer.toByteArray());
                    return;
                }

                SharedPreferences preferences =
                        PreferenceManager.getDefaultSharedPreferences(context);
                BackupCodec.ImportPlan plan = BackupCodec.parseAndValidate(buffer.toByteArray());
                BackupCodec.ImportReport report = BackupCodec.apply(
                        context, preferences, plan);

                com.waenhancer.ui.helpers.BottomSheetHelper.showInfo(
                        requireActivity(), "Import complete", report.summary());
                App.getInstance().restartApp(FeatureLoader.PACKAGE_WPP);
                App.getInstance().restartApp(FeatureLoader.PACKAGE_BUSINESS);
                if (getActivity() != null
                        && context.getPackageName().equals(BuildConfig.APPLICATION_ID)) {
                    getActivity().recreate();
                }
            } catch (Exception exception) {
                Log.e("importConfigs", "Unable to import settings", exception);
                Toast.makeText(context, exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        FilePicker.fileCapture.launch(new String[]{"*/*"});
    }

    private boolean isInitialCheck = true;

    @SuppressLint("StringFormatInvalid")
    private void checkStateWpp(FragmentActivity activity) {
        boolean frameworkPresent = MainActivity.isXposedFrameworkPresent(requireContext());
        boolean hookEnabled = com.waenhancer.utils.ModuleStatus.isModuleActive();
        boolean heartbeatEnabled = hasRecentModuleHeartbeat();
        ;

        updateModuleStatusUi(frameworkPresent, hookEnabled, heartbeatEnabled);

        if (isInstalled(FeatureLoader.PACKAGE_WPP) && App.isOriginalPackage()) {
            disableWpp(activity);
        } else {
            binding.status2.setVisibility(View.GONE);
        }

        if (isInstalled(FeatureLoader.PACKAGE_BUSINESS)) {
            disableBusiness(activity);
        } else {
            binding.status3.setVisibility(View.GONE);
        }

        checkWpp(activity);

        binding.deviceName.setText(Build.MANUFACTURER);
        binding.sdk.setText(String.valueOf(Build.VERSION.SDK_INT));
        binding.modelName.setText(Build.DEVICE);
        
        String xposedVer = getXposedFrameworkVersion();
        String[] xposedParts = xposedVer.split("\\|");
        if (xposedParts.length == 2) {
            binding.xposedVersionLabel.setText(xposedParts[0] + " API");
            binding.xposedVersion.setText(xposedParts[1]);
        } else {
            binding.xposedVersionLabel.setText("Xposed/LSPosed API");
            binding.xposedVersion.setText(xposedVer);
        }

        if (App.isOriginalPackage()) {
            checkPackageVersion(activity, FeatureLoader.PACKAGE_WPP, binding.wppVersionRow, binding.wppInstalledVersion,
                    binding.wppVersionStatus, binding.wppStatusIcon, binding.wppUnsupportedBtn,
                    R.array.supported_versions_wpp);
        } else {
            View parent = (View) binding.wppInstalledVersion.getParent().getParent().getParent();
            if (parent != null) {
                parent.setVisibility(View.GONE);
            }
            View divider = (View) ((ViewGroup) parent.getParent())
                    .getChildAt(((ViewGroup) parent.getParent()).indexOfChild(parent) + 1);
            if (divider != null) {
                divider.setVisibility(View.GONE);
            }
        }

        checkPackageVersion(activity, FeatureLoader.PACKAGE_BUSINESS, binding.businessVersionRow, binding.businessInstalledVersion,
                binding.businessVersionStatus, binding.businessStatusIcon, binding.businessUnsupportedBtn,
                R.array.supported_versions_business);
    }

    private void updateModuleStatusUi(boolean frameworkPresent, boolean hookEnabled, boolean heartbeatEnabled) {
        binding.statusSummary.setText(String.format("v%s", BuildConfig.VERSION_NAME));
        binding.statusSummary.setVisibility(View.VISIBLE);

        if (hookEnabled || heartbeatEnabled) {
            binding.statusIcon.setImageResource(R.drawable.ic_round_check_circle_24);
            binding.statusIcon.setColorFilter(null);
            binding.statusTitle.setText(R.string.module_enabled);
            binding.status.getChildAt(0).setBackgroundResource(R.drawable.hero_glow_enabled);
        } else if (frameworkPresent) {
            binding.statusIcon.setImageResource(R.drawable.ic_round_warning_24);
            binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light));
            binding.statusTitle.setText(R.string.module_disabled);
            binding.status.getChildAt(0).setBackgroundResource(R.drawable.hero_glow_disabled);
        } else {
            binding.statusIcon.setImageResource(R.drawable.ic_round_error_outline_24);
            binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_red_light));
            binding.statusTitle.setText(R.string.framework_not_detected);
            binding.status.getChildAt(0).setBackgroundResource(R.drawable.hero_glow_disabled);
        }
    }

    private boolean isInstalled(String packageWpp) {
        try {
            App.getInstance().getPackageManager().getPackageInfo(packageWpp, 0);
            return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void checkPackageVersion(FragmentActivity activity, String packageName,
            View rowView,
            com.google.android.material.textview.MaterialTextView versionView,
            com.google.android.material.textview.MaterialTextView statusView, android.widget.ImageView iconView,
            View unsupportedBtnView,
            int supportedArrayResId) {

        int colorError = androidx.core.content.ContextCompat.getColor(activity, android.R.color.holo_red_light);
        int colorOutline = androidx.core.content.ContextCompat.getColor(activity, android.R.color.darker_gray);
        int colorSuccess = 0xFF2E7D32;

        try {
            var packageInfo = App.getInstance().getPackageManager().getPackageInfo(packageName, 0);
            var installedVersion = packageInfo.versionName;
            versionView.setText(installedVersion);

            android.content.SharedPreferences diagnosticPrefs =
                    com.waenhancer.config.PreferenceStores.privateStore(activity);
            com.waenhancer.diagnostics.ValidationModel.Compatibility compatibility =
                    com.waenhancer.diagnostics.ValidationSession.compatibility(
                            activity, diagnosticPrefs, packageName, installedVersion);
            boolean isSupported = compatibility != com.waenhancer.diagnostics.ValidationModel.Compatibility.INCOMPATIBLE;
            unsupportedBtnView.setVisibility(View.GONE);

            if (isSupported) {
                int compatibilityColor = compatibility == com.waenhancer.diagnostics.ValidationModel.Compatibility.VALIDATED
                        ? colorSuccess : 0xFFF9A825;
                statusView.setText(com.waenhancer.diagnostics.ValidationSession.label(compatibility));
                statusView.setTextColor(compatibilityColor);
                iconView.setImageResource(compatibility == com.waenhancer.diagnostics.ValidationModel.Compatibility.VALIDATED
                        ? R.drawable.ic_round_check_circle_24 : R.drawable.ic_round_warning_24);
                iconView.setColorFilter(compatibilityColor);
                rowView.setOnClickListener(v -> startActivity(new Intent(activity,
                        com.waenhancer.activities.DiagnosticsActivity.class)));
            } else {
                statusView.setText("Incompatible");
                statusView.setTextColor(colorError);
                iconView.setImageResource(R.drawable.ic_round_error_outline_24);
                iconView.setColorFilter(colorError);
                rowView.setOnClickListener(null);
                rowView.setClickable(false);
            }
        } catch (Exception e) {
            versionView.setText("Not Installed");
            statusView.setText("-");
            unsupportedBtnView.setVisibility(View.GONE);
            iconView.setImageResource(R.drawable.ic_round_error_outline_24);
            iconView.setColorFilter(colorOutline);
            rowView.setOnClickListener(null);
            rowView.setClickable(false);
        }
    }

    private void showBetaWarningDialog(FragmentActivity activity, String packageName, boolean forceShow) {
        String appName = FeatureLoader.PACKAGE_WPP.equals(packageName) ? "WhatsApp" : "WhatsApp Business";
        String prefKey = FeatureLoader.PACKAGE_WPP.equals(packageName) ? "last_beta_warning_dismissed_wpp" : "last_beta_warning_dismissed_business";

        SharedPreferences prefs = activity.getSharedPreferences("ApkMirrorCache", Context.MODE_PRIVATE);
        long lastDismissed = prefs.getLong(prefKey, 0L);
        long now = System.currentTimeMillis();

        if (!forceShow && (now - lastDismissed < java.util.concurrent.TimeUnit.DAYS.toMillis(1))) {
            return;
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Beta Version Detected")
                .setMessage("You have installed a beta version of " + appName + ". WaEnhancer Community is designed for the stable versions of WhatsApp, if you face any bugs please switch to a stable version of " + appName + ".")
                .setPositiveButton("Dismiss for 1 Day", (dialog, which) -> {
                    prefs.edit().putLong(prefKey, System.currentTimeMillis()).apply();
                    dialog.dismiss();
                })
                .setNegativeButton("Close", (dialog, which) -> {
                    prefs.edit().putLong(prefKey, System.currentTimeMillis()).apply();
                    dialog.dismiss();
                })
                .setOnCancelListener(dialog -> {
                    prefs.edit().putLong(prefKey, System.currentTimeMillis()).apply();
                })
                .show();
    }

    private void disableBusiness(FragmentActivity activity) {
        binding.statusTitle3.setText(R.string.business_is_not_running_or_has_not_been_activated_in_lsposed);
        binding.statusDotBusiness.setBackgroundResource(R.drawable.status_dot_inactive);
        binding.statusSummary3.setVisibility(View.GONE);
        binding.rebootBtn2.setVisibility(View.GONE);
    }

    private void disableWpp(FragmentActivity activity) {
        binding.statusTitle2.setText(R.string.whatsapp_is_not_running_or_has_not_been_activated_in_lsposed);
        binding.statusDotWpp.setBackgroundResource(R.drawable.status_dot_inactive);
        binding.statusSummary1.setVisibility(View.GONE);
        binding.rebootBtn.setVisibility(View.GONE);
    }

    private static void checkWpp(FragmentActivity activity) {
        ;
        Intent checkWpp = new Intent(BuildConfig.APPLICATION_ID + ".CHECK_WPP");
        checkWpp.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        activity.sendBroadcast(checkWpp);
    }

    private void setupReleaseChannelSelector() {
        syncReleaseChannelToInstalled();
        binding.releaseChannelGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            String selectedChannel = checkedId == R.id.release_channel_beta_btn ? "beta" : "stable";
            setReleaseChannel(selectedChannel);
            binding.updateNotificationCard.setVisibility(View.GONE);
            checkForUpdates();
        });
    }

    /**
     * Keep the legacy method name to avoid touching callers, but synchronize from the user's
     * persisted update channel instead of forcing it to match the currently installed build.
     */
    private void syncReleaseChannelToInstalled() {
        var prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String selectedChannel = prefs.getString("release_channel", "stable");
        if (!"beta".equals(selectedChannel)) {
            selectedChannel = "stable";
        }
        setReleaseChannel(selectedChannel);
        updateReleaseChannelUi(selectedChannel);
    }

    private String getInstalledReleaseChannel() {
        return BuildConfig.VERSION_NAME != null && BuildConfig.VERSION_NAME.contains("-beta-") ? "beta" : "stable";
    }

    private void setReleaseChannel(String channel) {
        var prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        prefs.edit()
                .putString("release_channel", channel)
                .remove("update_alert_pref")
                .apply();
        WppCore.setPrivString("release_channel", channel);
    }

    private void updateReleaseChannelUi(String channel) {
        if ("beta".equals(channel)) {
            binding.releaseChannelGroup.check(R.id.release_channel_beta_btn);
        } else {
            binding.releaseChannelGroup.check(R.id.release_channel_stable_btn);
        }
    }

    private void showReleaseInstallPrompt(String selectedChannel) {
        boolean isBeta = "beta".equals(selectedChannel);
        String title = getString(isBeta ? R.string.release_channel_beta_install_title : R.string.release_channel_stable_install_title);
        String message = getString(isBeta ? R.string.release_channel_beta_install_message : R.string.release_channel_stable_install_message);
        String url = isBeta ? RELEASES_URL : LATEST_STABLE_URL;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.download, (dialog, which) -> {
                    Intent intent = new Intent(requireContext(), ChangelogActivity.class);
                    intent.putExtra(ChangelogActivity.EXTRA_TARGET_CHANNEL, selectedChannel);
                    startActivity(intent);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void markModuleActive() {
        sLastHeartbeatTime = System.currentTimeMillis();
    }

    private boolean isWhatsAppRunning(Context context) {
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> runningProcesses = am.getRunningAppProcesses();
        if (runningProcesses != null) {
            for (android.app.ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                if ("com.whatsapp".equals(processInfo.processName) || "com.whatsapp.w4b".equals(processInfo.processName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasRecentModuleHeartbeat() {
        return (System.currentTimeMillis() - sLastHeartbeatTime) < 8000;
    }

    private void showClearCacheConfirmation() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_obfuscate_cache)
                .setMessage(R.string.clear_cache_confirmation)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    UnobfuscatorCache.init(App.getInstance());
                    UnobfuscatorCache.getInstance().clearCache();

                    Intent clearIntent = new Intent(BuildConfig.APPLICATION_ID + ".CLEAR_OBFUSCATE_CACHE");
                    requireContext().sendBroadcast(clearIntent);

                    Utils.showToast(getString(R.string.obfuscate_cache_cleared), Toast.LENGTH_SHORT);
                })
                .setNegativeButton(R.string.no, null)
                .show();
    }

    private void setupUpdateBanner() {
        binding.dismissUpdateBtn.setOnClickListener(v -> {
            animateClick(v);
            binding.updateNotificationCard.setVisibility(View.GONE);
        });

        binding.viewChangelogBtn.setOnClickListener(v -> {
            animateClick(v);
            Intent intent = new Intent(requireContext(), ChangelogActivity.class);
            startActivity(intent);
        });

        binding.updateNowBtn.setOnClickListener(v -> {
            animateClick(v);
            Intent intent = new Intent(requireContext(), ChangelogActivity.class);
            startActivity(intent);
        });
    }

    private void checkForUpdates() {
        var updateChecker = new UpdateChecker(requireActivity());
        updateChecker.setSilent(true);
        updateChecker.setOnUpdateFoundListener((version, tagName, changelog, publishedAt, downloadUrl) -> {
            if (binding == null) {
                return;
            }
            this.pendingUpdateUrl = downloadUrl;
            this.pendingUpdateVersion = version;

            boolean isBeta = tagName != null && tagName.contains("-beta-");
            int titleResId = isBeta ? R.string.new_beta_update_available : R.string.new_stable_update_available;
            binding.updateNotificationTitle.setText(getString(titleResId, version));
            binding.updateNotificationChangelog.setText(changelog);
            binding.updateNotificationCard.setVisibility(View.VISIBLE);

            var anim = AnimationUtils.loadAnimation(getContext(), R.anim.slide_up);
            binding.updateNotificationCard.startAnimation(anim);
        });
        java.util.concurrent.CompletableFuture.runAsync(updateChecker);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private String getXposedFrameworkVersion() {
        Context context = getContext();
        if (context == null) return "Unknown";
        
        String apiVal = "";
        try {
            Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge");
            java.lang.reflect.Method getVersion = bridge.getMethod("getXposedVersion");
            int ver = (Integer) getVersion.invoke(null);
            if (ver > 0) {
                apiVal = String.valueOf(ver);
            }
        } catch (Throwable ignored) {}
        
        if (apiVal.isEmpty()) {
            try {
                android.content.SharedPreferences localPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
                int localApi = localPrefs.getInt("active_xposed_api_version", 0);
                if (localApi > 0) {
                    apiVal = String.valueOf(localApi);
                }
            } catch (Throwable ignored) {}
        }
        
        if (apiVal.isEmpty()) {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
                apiVal = (String) get.invoke(null, "debug.waenhancer.lsposed.api", "");
            } catch (Throwable ignored) {}
        }
        
        boolean isActive = false;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String val = (String) get.invoke(null, "debug.waenhancer.lsposed", "0");
            if ("1".equals(val)) {
                isActive = true;
            }
        } catch (Throwable ignored) {}
        
        if (!isActive) {
            isActive = com.waenhancer.utils.ModuleStatus.isModuleActive();
        }

        if (apiVal.isEmpty() && !isActive) {
            return "LSPosed|Not Detected";
        }

        String frameworkName = "LSPosed";
        android.content.pm.PackageManager pm = context.getPackageManager();
        String[] managerPackages = {
                "org.lsposed.manager", 
                "io.github.lsposed.manager",
                "org.meowcat.edxposed.manager", 
                "com.solohsu.android.edxp.manager",
                "de.robv.android.xposed.installer"
        };
        for (String pkg : managerPackages) {
            try {
                pm.getPackageInfo(pkg, 0);
                frameworkName = pkg.contains("lsposed") ? "LSPosed" : (pkg.contains("edxposed") ? "EdXposed" : "Xposed");
                break;
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        }

        String finalApi = !apiVal.isEmpty() ? apiVal : "93";
        return frameworkName + "|" + finalApi;
    }
}
