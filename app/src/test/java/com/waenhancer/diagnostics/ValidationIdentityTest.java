package com.waenhancer.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.waenhancer.config.PreferenceSchema;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

public class ValidationIdentityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void listSettingsAreThePublicListPreferencesOfTheSettingsScreens() throws Exception {
        TreeSet<String> fromXml = new TreeSet<>();
        File[] screens = new File("src/main/res/xml").listFiles((dir, name) -> name.endsWith(".xml"));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        for (File screen : screens) {
            NodeList lists = factory.newDocumentBuilder().parse(screen).getElementsByTagName("ListPreference");
            for (int i = 0; i < lists.getLength(); i++) {
                String key = ((Element) lists.item(i)).getAttributeNS("http://schemas.android.com/apk/res-auto", "key");
                var entry = PreferenceSchema.entry(key);
                if (entry != null && entry.store == PreferenceSchema.Store.PUBLIC
                        && entry.sensitivity == PreferenceSchema.Sensitivity.PUBLIC_SETTING) fromXml.add(key);
            }
        }
        assertEquals(fromXml, new TreeSet<>(ValidationIdentity.LIST_SETTINGS));
    }

    @Test
    public void listSettingChangesTheFingerprintButFreeTextDoesNot() throws Exception {
        String base = ValidationIdentity.configuration(new MapPrefs(Map.of("chatfilter", "2")));
        assertNotEquals(base, ValidationIdentity.configuration(new MapPrefs(Map.of("chatfilter", "1"))));
        // Not a list setting: never part of the fingerprint, whatever its value.
        assertEquals(ValidationIdentity.configuration(new MapPrefs(Map.of())),
                ValidationIdentity.configuration(new MapPrefs(Map.of("not_a_schema_key", "anything"))));
    }

    @Test
    public void moduleCodeDigestIgnoresSignatureAndRecordsCatalog() throws Exception {
        File one = apk("a.apk", "{\"builds\":[]}", "sig1");
        File two = apk("b.apk", "{\"builds\":[{\"exactBuildKey\":\"x\"}]}", "sig2");
        assertEquals(ValidationIdentity.codeDigest(one), ValidationIdentity.codeDigest(two));
        File changedCode = apk("c.apk", "{\"builds\":[]}", "sig1", "different dex");
        assertNotEquals(ValidationIdentity.codeDigest(one), ValidationIdentity.codeDigest(changedCode));
    }

    private File apk(String name, String catalog, String signature) throws Exception {
        return apk(name, catalog, signature, "dex bytes");
    }

    private File apk(String name, String catalog, String signature, String dex) throws Exception {
        File file = temp.newFile(name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            put(zip, "classes.dex", dex);
            put(zip, "assets/validated_builds.json", catalog);
            put(zip, "META-INF/CERT.SF", signature);
        }
        return file;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Read-only SharedPreferences over a map. */
    static final class MapPrefs implements android.content.SharedPreferences {
        private final Map<String, ?> values;
        MapPrefs(Map<String, ?> values) { this.values = values; }
        @Override public Map<String, ?> getAll() { return values; }
        @Override public String getString(String k, String d) { return values.containsKey(k) ? (String) values.get(k) : d; }
        @Override public java.util.Set<String> getStringSet(String k, java.util.Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { return d; }
        @Override public long getLong(String k, long d) { return d; }
        @Override public float getFloat(String k, float d) { return d; }
        @Override public boolean getBoolean(String k, boolean d) { return d; }
        @Override public boolean contains(String k) { return values.containsKey(k); }
        @Override public Editor edit() { throw new UnsupportedOperationException(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    }
}
