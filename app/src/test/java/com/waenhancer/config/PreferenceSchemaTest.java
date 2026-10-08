package com.waenhancer.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.waenhancer.backup.BackupCodec;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the invariant that made settings backup lose data: the schema, the user interface
 * and the backup allowlist must describe the same set of keys.
 *
 * <p>The previous allowlist was hand-written from the plan document instead of from the code.
 * It named keys the app never defines and misspelled others, so export wrote a fraction of the
 * real settings and import restored that same fraction. These tests fail if that drift returns.
 */
public class PreferenceSchemaTest {

    private static final Pattern XML_KEY =
            Pattern.compile("(?:app|android):key\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PREFERENCE_ELEMENT =
            Pattern.compile("<\\s*(?:[\\w.]+\\.)?(\\w*Preference\\w*)\\b");

    /** Screens whose entries are navigation or headings rather than stored values. */
    private static final Set<String> NON_STORING_ELEMENTS = new LinkedHashSet<>(java.util.Arrays
            .asList("Preference", "PreferenceCategory", "PreferenceScreen"));

    private static Path resDir() {
        Path fromModule = Paths.get("src", "main", "res");
        if (Files.isDirectory(fromModule)) return fromModule;
        return Paths.get("app", "src", "main", "res");
    }

    /** Every key a preference screen stores must exist in the schema. */
    @Test
    public void everyStoringUiKeyIsInTheSchema() throws IOException {
        List<String> missing = new ArrayList<>();
        Path xmlDir = resDir().resolve("xml");
        File[] files = xmlDir.toFile().listFiles((dir, name) -> name.endsWith(".xml"));
        assertTrue("preference screens not found under " + xmlDir.toAbsolutePath(),
                files != null && files.length > 0);

        for (File file : files) {
            String name = file.getName();
            if (name.equals("devices.xml") || name.equals("file_paths.xml")) continue;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            for (String element : splitElements(text)) {
                Matcher keyMatcher = XML_KEY.matcher(element);
                if (!keyMatcher.find()) continue;
                Matcher typeMatcher = PREFERENCE_ELEMENT.matcher(element);
                if (!typeMatcher.find()) continue;
                if (NON_STORING_ELEMENTS.contains(typeMatcher.group(1))) continue;
                String key = keyMatcher.group(1);
                if (!PreferenceSchema.isKnown(key)) {
                    missing.add(key + " (" + name + ")");
                }
            }
        }
        assertEquals("preference screens declare keys the schema does not describe: " + missing,
                0, missing.size());
    }

    /** Every numeric bottom bar key must also be described by the schema. */
    @Test
    public void everyBottomBarKeyIsInTheSchema() {
        List<String> missing = new ArrayList<>();
        for (String key : BottomBarPreferenceSchema.all().keySet()) {
            if (!PreferenceSchema.isKnown(key)) missing.add(key);
        }
        assertEquals("bottom bar keys absent from the schema: " + missing, 0, missing.size());
    }

    /** Every numeric bottom-bar setting is consumed inside the hooked WhatsApp process. */
    @Test
    public void everyBottomBarKeyIsReadableByHookProcess() {
        List<String> misclassified = new ArrayList<>();
        for (String key : BottomBarPreferenceSchema.all().keySet()) {
            PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
            if (entry == null
                    || entry.sensitivity != PreferenceSchema.Sensitivity.PUBLIC_SETTING
                    || entry.store != PreferenceSchema.Store.PUBLIC) {
                misclassified.add(key);
            }
        }
        assertEquals("bottom bar runtime keys must be PUBLIC_SETTING/Store.PUBLIC: "
                + misclassified, 0, misclassified.size());
    }

    /** A secret must never be placed in the world-readable store. */
    @Test
    public void noSecretLivesInThePublicStore() {
        for (PreferenceSchema.Entry entry : PreferenceSchema.all().values()) {
            if (entry.sensitivity == PreferenceSchema.Sensitivity.SECRET) {
                assertEquals("secret " + entry.key + " must not be in the public store",
                        PreferenceSchema.Store.PRIVATE, entry.store);
            }
        }
        assertFalse("the schema should still describe the known secrets",
                PreferenceSchema.secretKeys().isEmpty());
    }

    /** A secret must never be exportable, and must be refused by name too. */
    @Test
    public void secretsAreNeverExportable() {
        for (String key : PreferenceSchema.secretKeys()) {
            assertFalse(key + " must not be exportable", PreferenceSchema.isExportable(key));
            assertFalse(key + " must not be in the backup allowlist",
                    BackupCodec.safeKeys().contains(key));
            assertTrue(key + " must be refused as sensitive", BackupCodec.isSensitive(key));
        }
    }

    /** The backup allowlist is the schema, not a second list that can drift from it. */
    @Test
    public void backupAllowlistIsDerivedFromTheSchema() {
        assertEquals(PreferenceSchema.exportableKeys(), BackupCodec.safeKeys());
        for (String key : BackupCodec.safeKeys()) {
            assertTrue("allowlist names a key the schema does not define: " + key,
                    PreferenceSchema.isKnown(key));
        }
    }

    /** Cache and runtime state is internal and must stay out of backups. */
    @Test
    public void cacheAndRuntimeStateIsNotExportable() {
        for (PreferenceSchema.Entry entry : PreferenceSchema.all().values()) {
            if (entry.sensitivity == PreferenceSchema.Sensitivity.CACHE
                    || entry.sensitivity == PreferenceSchema.Sensitivity.RUNTIME) {
                assertFalse(entry.key + " is internal state and must not be exported",
                        BackupCodec.safeKeys().contains(entry.key));
            }
        }
    }

    @Test
    public void diagnosticSnapshotsAreKnownPrivateRuntimeKeys() {
        for (String key : java.util.Arrays.asList(
                "validation_runtime_snapshot_wpp", "validation_runtime_snapshot_business")) {
            PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
            assertTrue(key + " must be registered", entry != null);
            assertEquals(PreferenceSchema.Sensitivity.RUNTIME, entry.sensitivity);
            assertEquals(PreferenceSchema.Store.PRIVATE, entry.store);
            assertFalse(PreferenceSchema.isExportable(key));
        }
    }

    /** Every legacy alias must resolve onto a key the schema actually defines. */
    @Test
    public void legacyAliasesResolveToRealKeys() throws Exception {
        java.lang.reflect.Field field = BackupCodec.class.getDeclaredField("LEGACY_ALIASES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> aliases = (java.util.Map<String, String>) field.get(null);
        List<String> broken = new ArrayList<>();
        for (java.util.Map.Entry<String, String> alias : aliases.entrySet()) {
            if (!PreferenceSchema.isKnown(alias.getValue())) {
                broken.add(alias.getKey() + " -> " + alias.getValue());
            }
        }
        assertEquals("aliases point at keys that do not exist: " + broken, 0, broken.size());
    }

    /**
     * Hook-side reads written as {@code prefs.getX("key", default)}. One-argument
     * {@code getString("key")} calls are JSON lookups and are deliberately not matched.
     */
    private static final Pattern HOOK_PREF_READ = Pattern.compile(
            "\\b(?:prefs|pref|mPrefs|activePrefs)\\s*\\.\\s*"
                    + "(?:get(?:Boolean|String|StringSet|Int|Long|Float)\\(\\s*\"([^\"]+)\"\\s*,"
                    + "|contains\\(\\s*\"([^\"]+)\"\\s*\\))");

    /**
     * Keys a hook reads that are intentionally absent from the bridge. Each one has no module
     * writer, so the hook always falls back to its default; adding a key here needs a reason.
     */
    private static final Set<String> HOOK_ONLY_DEFAULTS = new LinkedHashSet<>(java.util.Arrays.asList(
            // Legacy key read only as a fallback for upgraded installs; BackupCodec aliases it.
            "floating_bottom_bar_scroll_hide",
            // Legacy key name for admin_grp, read only for installs that predate the rename.
            "show_admin_group_icon",
            // No settings UI; hooks use the default.
            "lazy_feature_loading",
            "wa_enhancer_button",
            // No writer exists for a bundled default spoofer XML; see handoff.md.
            "bootloader_spoofer_default_xml"));

    private static Path javaDir() {
        Path fromModule = Paths.get("src", "main", "java");
        if (Files.isDirectory(fromModule)) return fromModule;
        return Paths.get("app", "src", "main", "java");
    }

    /**
     * Every key a hook reads through the provider bridge must be schema-known and PUBLIC.
     *
     * <p>HookProvider drops every other key from {@code get_all_preferences}, so a missing entry
     * silently pins the setting to its default inside WhatsApp while the module UI shows it on.
     * That is how the eight Liquid Glass surfaces, Ghost/DND mode and root call recording broke.
     */
    @Test
    public void everyHookReadKeyCrossesTheBridge() throws IOException {
        Path hooks = javaDir().resolve(Paths.get("com", "waenhancer", "xposed"));
        assertTrue("hook sources not found under " + hooks.toAbsolutePath(), Files.isDirectory(hooks));
        List<String> broken = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (java.util.stream.Stream<Path> files = Files.walk(hooks)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = stripLineComments(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                Matcher matcher = HOOK_PREF_READ.matcher(text);
                while (matcher.find()) {
                    String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    seen.add(key);
                    if (HOOK_ONLY_DEFAULTS.contains(key)) continue;
                    PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
                    if (entry == null || entry.store != PreferenceSchema.Store.PUBLIC) {
                        broken.add(key + " (" + file.getFileName() + ")");
                    }
                }
            }
        }
        assertTrue("the scan should find the hooks' preference reads", seen.size() > 50);
        assertEquals("hooks read keys HookProvider will never serve: " + broken, 0, broken.size());
    }

    /** Quick-settings tiles must toggle a key the schema knows, or they toggle nothing. */
    @Test
    public void everyTileKeyIsInTheSchema() throws IOException {
        Path services = javaDir().resolve(Paths.get("com", "waenhancer", "services"));
        Pattern tileKey = Pattern.compile(
                "getPreferenceKey\\(\\)\\s*\\{\\s*return\\s*\"([^\"]+)\"");
        List<String> broken = new ArrayList<>();
        int tiles = 0;
        File[] files = services.toFile().listFiles((dir, name) -> name.endsWith("TileService.java"));
        assertTrue("tile services not found under " + services.toAbsolutePath(),
                files != null && files.length > 0);
        for (File file : files) {
            Matcher matcher = tileKey.matcher(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (!matcher.find()) continue;
            tiles++;
            if (!PreferenceSchema.isKnown(matcher.group(1))) {
                broken.add(matcher.group(1) + " (" + file.getName() + ")");
            }
        }
        assertTrue("expected to find tile keys", tiles > 5);
        assertEquals("tiles toggle keys the schema does not define: " + broken, 0, broken.size());
    }

    /** Device-local or hook-read state that must reach WhatsApp. */
    @Test
    public void hookStateKeysArePublic() {
        for (String key : java.util.Arrays.asList("ghostmode_actual", "dndmode_actual",
                "call_recording_use_root", "custom_versions_wpp", "custom_versions_business")) {
            PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
            assertTrue(key + " must be registered", entry != null);
            assertEquals(key, PreferenceSchema.Store.PUBLIC, entry.store);
        }
        assertFalse("a root grant is device-local and must not be restored elsewhere",
                PreferenceSchema.isExportable("call_recording_use_root"));
    }

    private static String stripLineComments(String text) {
        return text.replaceAll("(?m)^\\s*//.*$", "");
    }

    private static List<String> splitElements(String text) {
        List<String> elements = new ArrayList<>();
        Matcher matcher = Pattern.compile("<[^>]+>", Pattern.DOTALL).matcher(text);
        while (matcher.find()) elements.add(matcher.group());
        return elements;
    }
}
