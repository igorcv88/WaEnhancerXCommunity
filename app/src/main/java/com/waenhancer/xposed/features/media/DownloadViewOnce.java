package com.waenhancer.xposed.features.media;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.R;
import com.waenhancer.xposed.utils.Utils;

import java.io.File;
import java.util.concurrent.CompletableFuture;

import de.robv.android.xposed.XC_MethodHook;
import android.content.SharedPreferences;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class DownloadViewOnce extends Feature {
    private static final int MENU_ID_DOWNLOAD = 0x7EAD0003;

    public DownloadViewOnce(@NonNull ClassLoader classLoader, @NonNull SharedPreferences preferences) {
        super(classLoader, preferences);
    }

    private static void downloadFile(FMessageWpp.UserJid userJid, File file) throws Exception {
        var dest = Utils.getDestination("View Once");
        var fileExtension = file.getAbsolutePath().substring(file.getAbsolutePath().lastIndexOf(".") + 1);
        var name = Utils.generateName(userJid, fileExtension);
        var error = Utils.copyFile(file, dest, name);
        if (TextUtils.isEmpty(error)) {
            Utils.showToast(com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.saved_to) + dest, Toast.LENGTH_LONG);
        } else {
            Utils.showToast(com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.error_when_saving_try_again) + ":" + error, Toast.LENGTH_LONG);
        }
    }

    @Override
    public void doHook() throws Throwable {
        if (prefs.getBoolean("downloadviewonce", false)) {
            // Media Activity
            try {
                var menuMethod = Unobfuscator.loadViewOnceDownloadMenuMethod(classLoader);
                XposedBridge.hookMethod(menuMethod, new XC_MethodHook() {
                    @Override
                    @SuppressLint("DiscouragedApi")
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        diagnosticTriggered();
                        if (DEBUG) ;

                        Object fmessageObj = ReflectionUtils.getArg(param.args, FMessageWpp.TYPE, 0);
                        if (fmessageObj == null) {
                            var fields = ReflectionUtils.getFieldsByExtendType(param.thisObject.getClass(), FMessageWpp.TYPE);
                            if (!fields.isEmpty()) {
                                for (var field : fields) {
                                    fmessageObj = field.get(param.thisObject);
                                    if (fmessageObj != null) break;
                                }
                            }
                        }

                        if (fmessageObj == null) {
                            var keyFields = ReflectionUtils.getFieldsByExtendType(param.thisObject.getClass(), FMessageWpp.Key.TYPE);
                            if (!keyFields.isEmpty()) {
                                for (var field : keyFields) {
                                    var keyObj = field.get(param.thisObject);
                                    if (keyObj != null) {
                                        fmessageObj = WppCore.getFMessageFromKey(keyObj);
                                        if (fmessageObj != null) break;
                                    }
                                }
                            }
                        }

                        if (fmessageObj == null) {
                            if (DEBUG) ;
                            return;
                        }

                        FMessageWpp fMessage = new FMessageWpp(fmessageObj);
                        if (DEBUG) ;

                        // check media is view once
                        if (!fMessage.isViewOnce()) return;
                        Menu menu = (Menu) ReflectionUtils.getArg(param.args, Menu.class, 0);
                        if (menu == null) return;

                        // Guard against duplicate entries
                        if (menu.findItem(MENU_ID_DOWNLOAD) != null) return;

                        MenuItem item = menu.add(0, MENU_ID_DOWNLOAD, 0, com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), com.waenhancer.R.string.download, "Download"));

                        android.graphics.drawable.Drawable icon = null;
                        try {
                            icon = DesignUtils.getDrawable(R.drawable.download);
                        } catch (Exception e1) {
                            try {
                                int id1 = Utils.getID("ic_action_download", "drawable");
                                if (id1 > 0) icon = DesignUtils.getDrawable(id1);
                                else throw new Exception();
                            } catch (Exception e2) {
                                try {
                                    int id2 = Utils.getID("ic_download", "drawable");
                                    if (id2 > 0) icon = DesignUtils.getDrawable(id2);
                                } catch (Exception ignored) {}
                            }
                        }
                        if (icon != null) {
                            item.setIcon(icon);
                        }

                        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                        item.setOnMenuItemClickListener(item1 -> {
                            try {
                                var file = fMessage.getMediaFile();
                                if (file == null) {
                                    Utils.showToast(com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.download_not_available), 1);
                                    return true;
                                }
                                downloadFile(fMessage.getKey().remoteJid, file);
                            } catch (Exception e) {
                                Utils.showToast(e.getMessage(), Toast.LENGTH_LONG);
                            }
                            return true;
                        });
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("WaEnhancer: DownloadViewOnce (Media Activity) hook failed: " + t.getMessage());
            }

            // View Once Activity
            try {
                XposedHelpers.findAndHookMethod(WppCore.getViewOnceViewerActivityClass(classLoader), "onCreateOptionsMenu", classLoader.loadClass("android.view.Menu"),
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                diagnosticTriggered();
                                if (DEBUG) ;

                                Menu menu = (Menu) ReflectionUtils.getArg(param.args, Menu.class, 0);
                                if (menu == null) return;

                                // Guard against duplicate entries
                                if (menu.findItem(MENU_ID_DOWNLOAD) != null) return;

                                MenuItem item = menu.add(0, MENU_ID_DOWNLOAD, 0, com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), com.waenhancer.R.string.download, "Download"));

                                android.graphics.drawable.Drawable icon = null;
                                try {
                                    icon = DesignUtils.getDrawable(R.drawable.download);
                                } catch (Exception e1) {
                                    try {
                                        int id1 = Utils.getID("ic_action_download", "drawable");
                                        if (id1 > 0) icon = DesignUtils.getDrawable(id1);
                                        else throw new Exception();
                                    } catch (Exception e2) {
                                        try {
                                            int id2 = Utils.getID("ic_download", "drawable");
                                            if (id2 > 0) icon = DesignUtils.getDrawable(id2);
                                        } catch (Exception ignored) {}
                                    }
                                }
                                if (icon != null) {
                                    item.setIcon(icon);
                                }

                                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                                item.setOnMenuItemClickListener(item1 -> {
                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            var keyClass = FMessageWpp.Key.TYPE;
                                            var fieldType = ReflectionUtils.getFieldByType(param.thisObject.getClass(), keyClass);
                                            var keyMessageObj = ReflectionUtils.getObjectField(fieldType, param.thisObject);
                                            if (keyMessageObj == null) {
                                                if (DEBUG) ;
                                                return;
                                            }
                                            var fmessage = new FMessageWpp.Key(keyMessageObj).getFMessage();
                                            if (fmessage == null) {
                                                if (DEBUG) ;
                                                return;
                                            }
                                            var file = fmessage.getMediaFile();
                                            if (file == null) {
                                                Utils.showToast(com.waenhancer.xposed.core.FeatureLoader.getModuleString(com.waenhancer.xposed.utils.Utils.getApplication(), R.string.download_not_available), 1);
                                                return;
                                            }
                                            var userJid = fmessage.getKey().remoteJid;
                                            downloadFile(userJid, file);
                                        } catch (Exception e) {
                                            XposedBridge.log("[WAEX] DownloadViewOnce Error: " + e.getMessage());
                                            Utils.showToast(e.getMessage(), Toast.LENGTH_LONG);
                                        }
                                    });
                                    return true;
                                });

                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log("WaEnhancer: DownloadViewOnce (View Once Activity) hook failed: " + t.getMessage());
            }
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Download View Once";
    }
}
