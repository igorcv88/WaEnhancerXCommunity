package com.waenhancer.xposed.core.devkit;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.devrel.gmscore.tools.apk.arsc.ArscUtils;
import com.waenhancer.BuildConfig;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.R;
import com.waenhancer.xposed.utils.Utils;
import com.waenhancer.xposed.utils.XResManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class UnobfuscatorCache {

    private final Application mApplication;
    private static UnobfuscatorCache mInstance;
    public final SharedPreferences sPrefsCacheHooks;

    private final Map<String, String> reverseResourceMap = new HashMap<>();
    private final SharedPreferences sPrefsCacheStrings;

    @SuppressLint("ApplySharedPref")
    public UnobfuscatorCache(Application application) {
        mApplication = application;
        try {
            sPrefsCacheHooks = mApplication.getSharedPreferences("UnobfuscatorCache", Context.MODE_PRIVATE);
            sPrefsCacheStrings = mApplication.getSharedPreferences("UnobfuscatorCacheStrings", Context.MODE_PRIVATE);
            long version = 0;
            try {
                version = sPrefsCacheHooks.getLong("version", 0);
            } catch (Exception e) {
                try {
                    String v = sPrefsCacheHooks.getString("version", "0");
                    version = Long.parseLong(v);
                } catch (Exception ignored) {}
            }
            
            long currentVersion = mApplication.getPackageManager().getPackageInfo(mApplication.getPackageName(), 0).getLongVersionCode();
            
            long savedUpdateTime = 0;
            try {
                savedUpdateTime = sPrefsCacheHooks.getLong("updateTime", 0);
            } catch (Exception e) {
                try {
                    String v = sPrefsCacheHooks.getString("updateTime", "0");
                    savedUpdateTime = Long.parseLong(v);
                } catch (Exception ignored) {}
            }

            String savedVersionName = sPrefsCacheHooks.getString("wae_version_name", "");
            String versionName = BuildConfig.VERSION_NAME;
            long lastUpdateTime = savedUpdateTime;
            try {
                lastUpdateTime = mApplication.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID, 0).lastUpdateTime;
            } catch (Exception ignored) {
            }
            int savedSchema = 0;
            try {
                savedSchema = sPrefsCacheHooks.getInt(SCHEMA_KEY, 0);
            } catch (Exception ignored) {
            }
            if (version != currentVersion || savedUpdateTime != lastUpdateTime
                    || !versionName.equals(savedVersionName) || savedSchema != CACHE_SCHEMA) {
                int toastResId = R.string.starting_cache;
                try {
                    if (XResManager.moduleResources != null) {
                        boolean showToast = true;
                        if (Utils.xprefs != null) {
                            showToast = Utils.xprefs.getBoolean("show_hook_toast", true);
                        }
                        if (showToast) {
                            String message = XResManager.moduleResources.getString(toastResId);
                            Utils.showToast(message, Toast.LENGTH_LONG);
                        }
                    }
                } catch (Exception ignored) {
                }
                sPrefsCacheHooks.edit().clear().commit();
                sPrefsCacheHooks.edit().putLong("version", currentVersion).commit();
                sPrefsCacheHooks.edit().putLong("updateTime", lastUpdateTime).commit();
                sPrefsCacheHooks.edit().putString("wae_version_name", versionName).commit();
                sPrefsCacheHooks.edit().putInt(SCHEMA_KEY, CACHE_SCHEMA).commit();
                if (version != currentVersion) {
                    sPrefsCacheStrings.edit().clear().commit();
                }
            }
            initCacheStrings();
        } catch (Exception e) {
            throw new RuntimeException("Can't initialize UnobfuscatorCache: " + e.getMessage(), e);
        }

    }

    public static synchronized void init(Application mApp) {
        if (mInstance == null && mApp != null)
            mInstance = new UnobfuscatorCache(mApp);
    }

    public static synchronized UnobfuscatorCache getInstance() {
        if (mInstance == null) {
            Application app = Utils.getApplication();
            if (app != null) {
                init(app);
            }
        }
        return mInstance;
    }

    @SuppressLint("ApplySharedPref")
    public void clearCache() {
        sPrefsCacheHooks.edit().clear().commit();
        try {
            long currentVersion = mApplication.getPackageManager().getPackageInfo(mApplication.getPackageName(), 0).getLongVersionCode();
            long lastUpdateTime = mApplication.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID, 0).lastUpdateTime;
            String versionName = BuildConfig.VERSION_NAME;
            sPrefsCacheHooks.edit().putLong("version", currentVersion).commit();
            sPrefsCacheHooks.edit().putLong("updateTime", lastUpdateTime).commit();
            sPrefsCacheHooks.edit().putString("wae_version_name", versionName).commit();
            sPrefsCacheHooks.edit().putInt(SCHEMA_KEY, CACHE_SCHEMA).commit();
        } catch (Exception ignored) {
        }
        // An explicit clear is a request to search again in this process too.
        descriptors.clearFailures();
    }

    private void initCacheStrings() {
        getOfuscateIDString("mystatus");
        getOfuscateIDString("online");
        getOfuscateIDString("groups");
        getOfuscateIDString("messagedeleted");
        getOfuscateIDString("selectcalltype");
        getOfuscateIDString("lastseensun%s");
        getOfuscateIDString("updates");
    }

    private void initializeReverseResourceMap() {
        try {
            var app = Utils.getApplication();
            var source = app.getApplicationInfo().sourceDir;
            var table = ArscUtils.getResourceTable(new File(source));
            var pool = table.getStringPool();
            var pkg = table.getPackage(app.getPackageName());
            var typeChunks = pkg.getTypeChunks("string");
            var chunk = typeChunks.stream().filter(typeChunk -> typeChunk.getConfiguration().isDefault()).findFirst().orElse(null);
            var entries = chunk.getEntries();
            int baseValue = 0x7f12;
            for (var entry : entries.entrySet()) {
                try {
                    int keyHexValue = entry.getKey();
                    int result = baseValue << 16 | keyHexValue;
                    String resourceString = pool.getString(entry.getValue().value().data()).toLowerCase().replaceAll("\\s", "");
                    reverseResourceMap.put(resourceString, String.valueOf(result));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            XposedBridge.log(e);
            reverseResourceMap.clear();
        }
        if (reverseResourceMap.isEmpty()) {
            initializeReverseResourceMapBruteForce();
        }
    }

    private void initializeReverseResourceMapBruteForce() {
        var currentTime = System.currentTimeMillis();
        int numThreads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(numThreads); // Create a thread pool with 4 threads

        try {
            var configuration = new Configuration(mApplication.getResources().getConfiguration());
            configuration.setLocale(Locale.ENGLISH);
            var context = Utils.getApplication().createConfigurationContext(configuration);
            Resources resources = context.getResources();

            int startId = 0x7f120000;
            int endId = 0x7f12ffff;

            int chunkSize = (endId - startId + 1) / numThreads;
            CountDownLatch latch = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                int threadStartId = startId + t * chunkSize;
                int threadEndId = t == numThreads - 1 ? endId : threadStartId + chunkSize - 1;

                executor.submit(() -> {
                    try {
                        for (int i = threadStartId; i <= threadEndId; i++) {
                            try {
                                String resourceString = resources.getString(i);
                                reverseResourceMap.put(resourceString.toLowerCase().replaceAll("\\s", ""), String.valueOf(i));
                            } catch (Resources.NotFoundException ignored) {
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(); // Wait for all threads to finish
            ;
        } catch (Exception e) {
            Utils.log(e);
        } finally {
            executor.shutdown();
        }
    }

    private String getMapIdString(String search) {
        if (reverseResourceMap.isEmpty()) {
            initializeReverseResourceMap();
            System.gc();
        }
        search = search.toLowerCase().replaceAll("\\s", "");
        ;
        return reverseResourceMap.get(search);
    }

    @SuppressLint("ApplySharedPref")
    public int getOfuscateIDString(String search) {
        search = search.toLowerCase().replaceAll("\\s", "");
        var id = sPrefsCacheStrings.getString(search, null);
        if (id == null) {
            id = getMapIdString(search);
            if (id != null) {
                sPrefsCacheStrings.edit().putString(search, id).commit();
            }
        }
        return id == null ? -1 : Integer.parseInt(id);
    }

    public String getString(String search) {
        var id = getOfuscateIDString(search);
        return id < 1 ? "" : mApplication.getResources().getString(id);
    }

    /** Bumped when descriptor semantics change; a mismatch clears persisted descriptors once. */
    static final int CACHE_SCHEMA = 2;
    private static final String SCHEMA_KEY = "wae_cache_schema";

    private final DescriptorCache descriptors = new DescriptorCache(new DescriptorCache.Store() {
        @Override
        public String get(String key) {
            try {
                return sPrefsCacheHooks.getString(key, null);
            } catch (ClassCastException wrongType) {
                return "";
            }
        }

        @SuppressLint("ApplySharedPref")
        @Override
        public void put(String key, String value) {
            sPrefsCacheHooks.edit().putString(key, value).commit();
        }

        @SuppressLint("ApplySharedPref")
        @Override
        public void remove(String key) {
            sPrefsCacheHooks.edit().remove(key).commit();
        }
    }, (key, cause) -> XposedBridge.log("[WAE] UnobfuscatorCache: re-resolving stale descriptor "
            + key + " (" + cause.getClass().getSimpleName() + ")"));

    private static ReflectionDescriptors.ClassFinder finder(ClassLoader loader) {
        return name -> ReflectionUtils.findClass(name, loader);
    }

    private <T> DescriptorCache.Codec<T> codec(Encoder<T> encoder, Decoder<T> decoder) {
        return new DescriptorCache.Codec<T>() {
            @Override
            public String encode(T value) throws Exception {
                return encoder.encode(value);
            }

            @Override
            public T decode(String raw) throws Exception {
                return decoder.decode(raw);
            }
        };
    }

    private interface Encoder<T> {
        String encode(T value) throws Exception;
    }

    private interface Decoder<T> {
        T decode(String raw) throws Exception;
    }

    public Field getField(ClassLoader loader, FunctionCall<Field> functionCall) throws Exception {
        var f = finder(loader);
        return descriptors.get("field", getKeyName(),
                codec(ReflectionDescriptors::encodeField, raw -> ReflectionDescriptors.decodeField(f, raw)),
                functionCall::call);
    }

    public Field[] getFields(ClassLoader loader, FunctionCall<Field[]> functionCall) throws Exception {
        var f = finder(loader);
        return descriptors.get("fields", getKeyName(),
                codec(ReflectionDescriptors::encodeFields, raw -> ReflectionDescriptors.decodeFields(f, raw)),
                functionCall::call);
    }

    public Method getMethod(ClassLoader loader, FunctionCall<Method> functionCall) throws Exception {
        var f = finder(loader);
        return descriptors.get("method", getKeyName(),
                codec(ReflectionDescriptors::encodeMethod, raw -> ReflectionDescriptors.decodeMethod(f, raw)),
                functionCall::call);
    }

    public Method[] getMethods(ClassLoader loader, FunctionCall<Method[]> functionCall) throws Exception {
        var f = finder(loader);
        return descriptors.get("methods", getKeyName(),
                codec(ReflectionDescriptors::encodeMethods, raw -> ReflectionDescriptors.decodeMethods(f, raw)),
                functionCall::call);
    }

    public Class<?> getClass(ClassLoader loader, FunctionCall<Class<?>> functionCall) throws Exception {
        return getClass(loader, getKeyName(), functionCall);
    }

    public Class<?> getClass(ClassLoader loader, String key, FunctionCall<Class<?>> functionCall) throws Exception {
        var f = finder(loader);
        return descriptors.get("class", key,
                codec(ReflectionDescriptors::encodeClass, raw -> ReflectionDescriptors.decodeClass(f, raw)),
                functionCall::call);
    }

    public Class<?>[] getClasses(ClassLoader loader, FunctionCall<Class<?>[]> functionCall) throws Exception {
        var f = finder(loader);
        return descriptors.get("classes", getKeyName(),
                codec(ReflectionDescriptors::encodeClasses, raw -> ReflectionDescriptors.decodeClasses(f, raw)),
                functionCall::call);
    }

    public HashMap<String, Field> getMapField(ClassLoader loader, FunctionCall<HashMap<String, Field>> functionCall) throws Exception {
        return getMapField(loader, getKeyName(), functionCall);
    }

    /** A map with any entry that no longer resolves is re-resolved as a whole, never returned partially. */
    public HashMap<String, Field> getMapField(ClassLoader loader, String key, FunctionCall<HashMap<String, Field>> functionCall) throws Exception {
        var f = finder(loader);
        return descriptors.get("HashMap", key,
                codec(ReflectionDescriptors::encodeFieldMap, raw -> ReflectionDescriptors.decodeFieldMap(f, raw)),
                functionCall::call);
    }

    private String getKeyName() {
        AtomicReference<String> keyName = new AtomicReference<>("");
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        
        // Try finding Unobfuscator method first (ignoring synthetic/obfuscated/lambda methods)
        Arrays.stream(stackTrace)
                .filter(stackTraceElement -> stackTraceElement.getClassName().equals(Unobfuscator.class.getName())
                        && stackTraceElement.getMethodName().startsWith("load")
                        && !stackTraceElement.getMethodName().contains("$"))
                .findFirst()
                .ifPresent(stackTraceElement -> keyName.set(stackTraceElement.getMethodName()));
        
        // Fallback if not found (e.g. inlined or called from somewhere else)
        if (keyName.get().isEmpty()) {
            for (StackTraceElement ste : stackTrace) {
                String clsName = ste.getClassName();
                if (!clsName.equals(Thread.class.getName())
                        && !clsName.equals(UnobfuscatorCache.class.getName())
                        && !clsName.startsWith("java.lang.reflect.")
                        && !clsName.startsWith("android.util.Log")
                        && !clsName.contains("ReflectionUtils")) {
                    String simpleClsName = clsName.substring(clsName.lastIndexOf('.') + 1);
                    keyName.set("fb_" + simpleClsName + "_" + ste.getMethodName());
                    break;
                }
            }
        }
        return keyName.get();
    }

    public Constructor getConstructor(ClassLoader loader, FunctionCall functionCall) throws Exception {
        var f = finder(loader);
        return descriptors.get("constructor", getKeyName(),
                codec(c -> ReflectionDescriptors.encodeConstructor((Constructor<?>) c),
                        raw -> ReflectionDescriptors.decodeConstructor(f, raw)),
                () -> (Constructor<?>) functionCall.call());
    }

    public Number getNumber(ClassLoader loader, FunctionCall<Number> functionCall) throws Exception {
        return descriptors.get("number", getKeyName(),
                codec(ReflectionDescriptors::encodeNumber, ReflectionDescriptors::decodeNumber),
                functionCall::call);
    }

    public interface FunctionCall<T> {
        T call() throws Exception;
    }

}
