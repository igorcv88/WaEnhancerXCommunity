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

    private static List<String> splitElements(String text) {
        List<String> elements = new ArrayList<>();
        Matcher matcher = Pattern.compile("<[^>]+>", Pattern.DOTALL).matcher(text);
        while (matcher.find()) elements.add(matcher.group());
        return elements;
    }
}
