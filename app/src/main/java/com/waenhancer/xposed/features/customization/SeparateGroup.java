package com.waenhancer.xposed.features.customization;

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.MenuItem;
import android.widget.BaseAdapter;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.HookTransaction;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.core.devkit.UnobfuscatorCache;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.xposed.utils.Utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.SharedPreferences;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SeparateGroup extends Feature {

    public static final int CHATS = 200;
    public static final int STATUS = 300;
    public static final int GROUPS = 500;

    public static ArrayList<Integer> tabs = new ArrayList<>();
    public static HashMap<Integer, Object> tabInstances = new HashMap<>();

    public SeparateGroup(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("separategroups", false)) return;

        // Every concrete getMaxItemCount of the navigation bar: BottomNavigationView (and a rail)
        // in 2.26.38.74; the bottom-bar and rail bases in 2.26.46.82, where BottomNavigationView
        // is no longer a class name. Phones use the bar, tablets the rail.
        var maxItemCounts = Unobfuscator.loadTabFrameOverrides(classLoader, "getMaxItemCount", 0);
        if (maxItemCounts.isEmpty()) throw new NoSuchMethodException("Navigation bar getMaxItemCount not found");

        // All-or-nothing: a failure in any required step removes every hook installed before it,
        // so the chat list, FAB, search and navigation are never left partially modified.
        HookTransaction tx = new HookTransaction();
        try {
            for (Method maxItemCount : maxItemCounts) tx.hook(maxItemCount, MAX_ITEMS);
            requireStep("icon", hookTabIcon(tx));
            requireStep("fragment", hookTabInstance(tx));
            requireStep("name", hookTabName(tx));
            // Split unread badges are cosmetic: without them the tab still works, so a badge
            // failure removes only the badge hooks and is reported as a degraded install.
            HookTransaction badge = new HookTransaction();
            if (hookTabCount(badge)) {
                tx.adopt(badge);
            } else {
                badge.rollback();
                diagnosticDegraded();
            }
            // Publishing the tab is the last step, after its fragment factory and UI hooks exist.
            hookTabList(tx);
            int hooks = tx.size();
            tx.commit();
            diagnosticRegistered(hooks);
        } catch (Throwable failure) {
            tx.rollback();
            throw failure;
        }
    }

    private static final XC_MethodHook MAX_ITEMS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            param.setResult(SeparateGroupLimits.atLeastNavigationItems(param.getResult()));
        }
    };

    private static void requireStep(String step, boolean installed) {
        if (!installed) throw new IllegalStateException("Separate Groups step failed: " + step);
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Separate Group";
    }

    private static Object getEmptyBadge(Class emptyBadgeClass) {
        try {
            for (java.lang.reflect.Field f : emptyBadgeClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == emptyBadgeClass) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
            java.lang.reflect.Constructor ctor = emptyBadgeClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressLint("Range")
    private boolean hookTabCount(HookTransaction tx) {
        try {
            // XposedBridge.log("[WAEX-SG] hookTabCount starting...");
            Method runMethod = Unobfuscator.loadTabCountMethod(classLoader);
            /* Log removed */

            Method enableCountMethod = Unobfuscator.loadEnableCountTabMethod(classLoader);
            Constructor<?> badgeWrapperConstructor = Unobfuscator.loadEnableCountTabBadgeWrapper(classLoader);
            Constructor<?> badgeItemConstructor = Unobfuscator.loadEnableCountTabBadgeItem(classLoader);
            Class<?> emptyBadgeClass = Unobfuscator.loadEnableCountTabEmptyBadgeClass(classLoader);
            // XposedBridge.log("[WAEX-SG] enableCountMethod found: " + enableCountMethod);

            tx.hook(enableCountMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int indexTab = (int) param.args[2];
                    int chatsIdx = tabs.indexOf(CHATS);
                    // Only intercept the CHATS badge call (which carries the total unread count).
                    // We then split it into separate CHATS and GROUPS counts from the DB.
                    // XposedBridge.log("[WAEX-SG] badge call: indexTab=" + indexTab
                            // + " chatsIdx=" + chatsIdx
                            // + " groupsIdx=" + tabs.indexOf(GROUPS)
                            // + " tabs=" + tabs
                            // + " tabInstances=" + tabInstances.keySet());
                    if (indexTab != chatsIdx) return;
                    param.setResult(null);

                    Object originalBadge = param.args[1];
                    // XposedBridge.log("[WAEX-SG] Original badge: " + originalBadge 
                            // + " class=" + (originalBadge != null ? originalBadge.getClass().getName() : "null"));
                    if (originalBadge != null) {
                        for (Field f : originalBadge.getClass().getDeclaredFields()) {
                            try {
                                f.setAccessible(true);
                                // XposedBridge.log("[WAEX-SG]   Field " + f.getName() + " type " + f.getType().getName() + " = " + f.get(originalBadge));
                                Object val = f.get(originalBadge);
                                if (val != null) {
                                    for (Field f2 : val.getClass().getDeclaredFields()) {
                                        try {
                                            f2.setAccessible(true);
                                            // XposedBridge.log("[WAEX-SG]     Subfield " + f2.getName() + " type " + f2.getType().getName() + " = " + f2.get(val));
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }

                    new Thread(() -> {
                        try {
                            String counterType = getSafeString("separategroups_counter_type", "conversations");
                            int chatCount = 0;
                            int groupCount = 0;
                            SQLiteDatabase db = MessageStore.getInstance().getDatabase();
                            // XposedBridge.log("[WAEX-SG] DB=" + (db != null ? "OK" : "NULL"));
                            if (db != null) {
                                try {
                                    // Dynamically retrieve existing columns in the 'chat' table
                                    // to make this extremely robust against schema changes in different versions of WA.
                                    Set<String> chatColumns = new HashSet<>();
                                    try (Cursor colCursor = db.rawQuery("SELECT * FROM chat LIMIT 0", null)) {
                                        if (colCursor != null) {
                                            chatColumns.addAll(Arrays.asList(colCursor.getColumnNames()));
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("[WAEX-SG] Schema check failed: " + t);
                                    }

                                    // Build the query dynamically using only verified columns
                                    StringBuilder queryBuilder = new StringBuilder("SELECT c.unseen_message_count, j.server AS jid_server");
                                    if (chatColumns.isEmpty() || chatColumns.contains("group_type")) {
                                        queryBuilder.append(", c.group_type");
                                    }
                                    queryBuilder.append(" FROM chat c LEFT JOIN jid j ON c.jid_row_id = j._id WHERE c.unseen_message_count > 0");
                                    if (chatColumns.isEmpty() || chatColumns.contains("archived")) {
                                        queryBuilder.append(" AND (c.archived = 0 OR c.archived IS NULL)");
                                    }
                                    if (chatColumns.isEmpty() || chatColumns.contains("chat_lock")) {
                                        queryBuilder.append(" AND (c.chat_lock = 0 OR c.chat_lock IS NULL)");
                                    }

                                    try (Cursor cursor = db.rawQuery(queryBuilder.toString(), null)) {
                                        if (cursor != null) {
                                            int idxUnseen = cursor.getColumnIndex("unseen_message_count");
                                            int idxServer = cursor.getColumnIndex("jid_server");
                                            int idxGroupType = cursor.getColumnIndex("group_type");

                                            while (cursor.moveToNext()) {
                                                // If group_type column exists, filter out community parent / non-standard groups
                                                if (idxGroupType >= 0) {
                                                    int groupType = cursor.getInt(idxGroupType);
                                                    if (groupType != 0 && groupType != 6) {
                                                        continue;
                                                    }
                                                }

                                                int unseen = idxUnseen >= 0 ? cursor.getInt(idxUnseen) : 1;
                                                if (idxServer >= 0) {
                                                    String server = cursor.getString(idxServer);
                                                    int groupTypeVal = idxGroupType >= 0 ? cursor.getInt(idxGroupType) : -1;
                                                    // XposedBridge.log("[WAEX-SG] DB row: server=" + server + " unseen=" + unseen + " group_type=" + groupTypeVal);
                                                    boolean isGroup = "g.us".equals(server) || "broadcast".equals(server);
                                                    int increment = "messages".equals(counterType) ? unseen : 1;
                                                    if (isGroup) {
                                                        groupCount += increment;
                                                    } else {
                                                        chatCount += increment;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[WAEX-SG] DB Query Error: " + t);
                                }
                            }

                            final int finalChatCount = chatCount;
                            final int finalGroupCount = groupCount;
                            // XposedBridge.log("[WAEX-SG] counts: chat=" + finalChatCount + " group=" + finalGroupCount);

                            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                            handler.post(() -> {
                                try {
                                    // Set CHATS badge — no tabInstances check; nav view is already set up
                                    if (tabs.contains(CHATS)) {
                                        Object chatsBadge;
                                        if (finalChatCount <= 0) {
                                            chatsBadge = getEmptyBadge(emptyBadgeClass);
                                        } else {
                                            Object badgeItem = badgeItemConstructor.newInstance(finalChatCount);
                                            if (badgeWrapperConstructor.getParameterTypes().length == 2) {
                                                chatsBadge = badgeWrapperConstructor.newInstance(badgeItem, String.valueOf(finalChatCount));
                                            } else {
                                                chatsBadge = badgeWrapperConstructor.newInstance(badgeItem);
                                            }
                                        }
                                        // XposedBridge.log("[WAEX-SG] setChats badge=" + chatsBadge + " idx=" + tabs.indexOf(CHATS));
                                        XposedBridge.invokeOriginalMethod(
                                                param.method, param.thisObject,
                                                new Object[]{param.args[0], chatsBadge, tabs.indexOf(CHATS)});
                                    }

                                    // Set GROUPS badge — no tabInstances check; nav view is already set up
                                    if (tabs.contains(GROUPS)) {
                                        Object groupsBadge;
                                        if (finalGroupCount <= 0) {
                                            groupsBadge = getEmptyBadge(emptyBadgeClass);
                                        } else {
                                            Object badgeItem = badgeItemConstructor.newInstance(finalGroupCount);
                                            if (badgeWrapperConstructor.getParameterTypes().length == 2) {
                                                groupsBadge = badgeWrapperConstructor.newInstance(badgeItem, String.valueOf(finalGroupCount));
                                            } else {
                                                groupsBadge = badgeWrapperConstructor.newInstance(badgeItem);
                                            }
                                        }
                                        // XposedBridge.log("[WAEX-SG] setGroups badge=" + groupsBadge + " idx=" + tabs.indexOf(GROUPS));
                                        XposedBridge.invokeOriginalMethod(
                                                param.method, param.thisObject,
                                                new Object[]{param.args[0], groupsBadge, tabs.indexOf(GROUPS)});
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[WAEX-SG] Error setting badges: " + t);
                                }
                            });
                        } catch (Throwable t) {
                            XposedBridge.log("[WAEX-SG] Error in tab count thread: " + t);
                        }
                    }).start();
                }
            });
            // XposedBridge.log("[WAEX-SG] enableCountMethod hooked successfully");
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[WAEX-SG] hookTabCount error: " + t);
            return false;
        }
    }

    private boolean hookTabIcon(HookTransaction tx) {
        try {
            Method iconTabMethod = Unobfuscator.loadIconTabMethod(classLoader);
            /* Log removed */
            Method menuAddAndroidX = Unobfuscator.loadAddMenuAndroidX(classLoader);
            /* Log removed */

            tx.hook(iconTabMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XC_MethodHook.Unhook hooked = XposedBridge.hookMethod(menuAddAndroidX, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam innerParam) throws Throwable {
                            if (innerParam.args.length > 2 && (int) innerParam.args[1] == GROUPS) {
                                MenuItem menuItem = (MenuItem) innerParam.getResult();
                                menuItem.setIcon(
                                        Utils.getID("home_tab_communities_selector", "drawable")
                                );
                            }
                        }
                    });
                    param.setObjectExtra("hooked", hooked);
                }

                @SuppressLint("ResourceType")
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XC_MethodHook.Unhook hooked = (XC_MethodHook.Unhook) param.getObjectExtra("hooked");
                    if (hooked != null) {
                        hooked.unhook();
                    }
                }
            });
            // XposedBridge.log("[WAEX-SG] hookTabIcon hooked successfully");
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[WAEX-SG] hookTabIcon error: " + t);
            return false;
        }
    }

    @SuppressLint("ResourceType")
    private boolean hookTabName(HookTransaction tx) {
        try {
            Method tabNameMethod = Unobfuscator.loadTabNameMethod(classLoader);
            tx.hook(tabNameMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int tab = (int) param.args[0];
                    if (tab == GROUPS) {
                        param.setResult(UnobfuscatorCache.getInstance().getString("groups"));
                    }
                }
            });
            // XposedBridge.log("[WAEX-SG] hookTabName hooked successfully");
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[WAEX-SG] hookTabName error: " + t);
            return false;
        }
    }

    private boolean hookTabInstance(HookTransaction tx) {
        try {
            Class<?> cFragClass = Unobfuscator.findFirstClassUsingName(
                    classLoader,
                    org.luckypray.dexkit.query.enums.StringMatchType.EndsWith,
                    ".ConversationsFragment"
            );

            Method getTabMethod = Unobfuscator.loadGetTabMethod(classLoader);
            /* Log removed */

            Method methodTabInstance = Unobfuscator.loadTabFragmentMethod(classLoader);
            /* Log removed */

            Constructor<?> recreateFragmentMethod = Unobfuscator.loadRecreateFragmentConstructor(classLoader);

            Pattern pattern = Pattern.compile("android:switcher:\\d+:(\\d+)");

            Class<?> fragmentClass = Unobfuscator.loadFragmentClass(classLoader);

            // Hook recreate fragment constructor
            tx.hook(recreateFragmentMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String string;
                    Object arg0 = param.args[0];
                    if (arg0 instanceof Bundle) {
                        @SuppressWarnings("deprecation")
                        Parcelable state = ((Bundle) arg0).getParcelable("state");
                        if (state == null) return;
                        string = state.toString();
                    } else {
                        string = param.args[2].toString();
                    }
                    Matcher matcher = pattern.matcher(string);
                    if (matcher.find()) {
                        String group1 = matcher.group(1);
                        if (group1 == null) return;
                        int tabId;
                        try {
                            tabId = Integer.parseInt(group1);
                        } catch (NumberFormatException e) {
                            return;
                        }
                        if (tabId == GROUPS || tabId == CHATS) {
                            Field fragmentField = ReflectionUtils.getFieldByType(
                                    param.thisObject.getClass(),
                                    fragmentClass
                            );
                            Object convFragment = ReflectionUtils.getObjectField(fragmentField, param.thisObject);
                            tabInstances.remove(tabId);
                            if (convFragment != null) {
                                tabInstances.put(tabId, convFragment);
                            }
                        }
                    }
                }
            });

            // Hook getTab method
            tx.hook(getTabMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int index = (int) param.args[0];
                    if (index < 0 || index >= tabs.size()) return;
                    int tabId = tabs.get(index);
                    if (tabId == GROUPS || tabId == CHATS) {
                        java.lang.reflect.Constructor<?>[] constructors = cFragClass.getDeclaredConstructors();
                        for (java.lang.reflect.Constructor<?> ctor : constructors) {
                            if (ctor.getParameterCount() == 0) {
                                ctor.setAccessible(true);
                                Object convFragment = ctor.newInstance();
                                param.setResult(convFragment);
                                break;
                            }
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int index = (int) param.args[0];
                    if (index < 0 || index >= tabs.size()) return;
                    int tabId = tabs.get(index);
                    tabInstances.remove(tabId);
                    if (param.getResult() != null) {
                        tabInstances.put(tabId, param.getResult());
                    }
                }
            });

            // Hook tab fragment method (filter chat list)
            tx.hook(methodTabInstance, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    List<?> chatsList = (List<?>) param.getResult();
                    List<?> resultList = filterChat(param.thisObject, chatsList);
                    param.setResult(resultList);
                }
            });

            // Hook fab method
            Method fabintMethod = Unobfuscator.loadFabMethod(classLoader);
            /* Log removed */

            tx.hook(fabintMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (tabInstances.get(GROUPS) == param.thisObject) {
                        param.setResult(GROUPS);
                    }
                }
            });

            // Hook publishResults / getFilters method
            Method publishResultsMethod = Unobfuscator.loadGetFiltersMethod(classLoader);
            /* Log removed */

            final Method finalPublishResultsMethod = publishResultsMethod;
            tx.hook(publishResultsMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object filters = param.args[1];
                    if (filters == null || filters.getClass() == finalPublishResultsMethod.getParameterTypes()[1]) {
                        param.setResult(null);
                        return;
                    }
                    List<?> chatsList = (List<?>) XposedHelpers.getObjectField(filters, "values");
                    Field baseField = ReflectionUtils.getFieldByExtendType(
                            finalPublishResultsMethod.getDeclaringClass(),
                            BaseAdapter.class
                    );
                    if (baseField == null) return;
                    Field convField = ReflectionUtils.getFieldByType(baseField.getType(), cFragClass);
                    Object thiz = convField.get(baseField.get(param.thisObject));
                    if (thiz == null) return;
                    List<?> resultList = filterChat(thiz, chatsList);
                    XposedHelpers.setObjectField(filters, "values", resultList);
                    XposedHelpers.setIntField(filters, "count", resultList.size());
                }
            });
            // XposedBridge.log("[WAEX-SG] hookTabInstance hooked successfully");
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[WAEX-SG] hookTabInstance error: " + t);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private List<?> filterChat(Object thiz, List<?> chatsList) {
        Object tabChat = tabInstances.get(CHATS);
        Object tabGroup = tabInstances.get(GROUPS);

        if (tabChat != thiz && tabGroup != thiz) {
            return chatsList;
        }

        ArrayListFilter editableChatList = new ArrayListFilter(tabGroup == thiz);
        editableChatList.addAll((Collection<Object>) chatsList);
        return editableChatList;
    }

    private void hookTabList(HookTransaction tx) throws Exception {
        Method onCreateTabList = Unobfuscator.loadTabListMethod(classLoader);
        tx.hook(onCreateTabList, new XC_MethodHook() {
            @SuppressWarnings("unchecked")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                ArrayList<Integer> resultTabs = (ArrayList<Integer>) param.getResult();
                if (resultTabs == null) return;
                tabs = resultTabs;
                if (!tabs.contains(GROUPS)) {
                    tabs.add(tabs.isEmpty() ? 0 : 1, GROUPS);
                }
            }
        });
    }

    /**
     * A custom ArrayList that filters chat items based on whether they belong to groups or contacts.
     * Used to separate the chat list into individual chats and group chats.
     */
    public static class ArrayListFilter extends ArrayList<Object> {

        private final boolean isGroup;

        public ArrayListFilter(boolean isGroup) {
            this.isGroup = isGroup;
        }

        @Override
        public void add(int index, Object element) {
            if (checkGroup(element)) {
                super.add(index, element);
            }
        }

        @Override
        public boolean add(Object element) {
            if (checkGroup(element)) {
                return super.add(element);
            }
            return true;
        }

        @Override
        public boolean addAll(@NonNull Collection<?> c) {
            for (Object chat : c) {
                if (checkGroup(chat)) {
                    super.add(chat);
                }
            }
            return true;
        }

        private boolean checkGroup(Object chat) {
            return ChatListClassifier.keep(chat, isGroup);
        }
    }
}
