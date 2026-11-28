package com.waenhancer.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Pins the diagnostics catalog to the sources it describes. */
public class FeatureCatalogTest {
    private static final Path SOURCES = Paths.get("src/main/java/com/waenhancer/xposed");
    private static final Pattern LOADER_ARRAY = Pattern.compile(
            "var classes = new Class<\\?>\\[\\]\\s*\\{(.*?)\\};", Pattern.DOTALL);
    private static final Pattern LEADING_GATE = Pattern.compile(
            "public void doHook\\(\\)[^{]*\\{\\s*if\\s*\\(\\s*!prefs\\.getBoolean\\(\"([\\w.]+)\",\\s*(true|false)\\)\\)"
                    + "\\s*\\{?\\s*return;");

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Map<String, String> featureSources() throws IOException {
        Map<String, String> out = new HashMap<>();
        try (Stream<Path> files = Files.walk(SOURCES.resolve("features"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                String name = file.getFileName().toString().replace(".java", "");
                out.put(name, read(file));
            }
        }
        return out;
    }

    @Test
    public void manifestEqualsLoaderRegistrationInOrder() throws IOException {
        Matcher array = LOADER_ARRAY.matcher(read(SOURCES.resolve("core/FeatureLoader.java")));
        assertTrue("loader class array not found", array.find());
        List<String> loader = new ArrayList<>();
        Matcher item = Pattern.compile("(\\w+)\\.class").matcher(array.group(1));
        while (item.find()) loader.add(item.group(1));
        assertEquals(loader, FeatureManifest.FEATURES);
    }

    @Test
    public void catalogCoversEveryRegisteredFeature() {
        assertEquals(new TreeSet<>(FeatureManifest.FEATURES), new TreeSet<>(FeatureCatalog.entries().keySet()));
    }

    @Test
    public void runtimeProbesAreExactlyTheFeaturesThatEmitCallbackEvents() throws IOException {
        Set<String> emitters = new TreeSet<>();
        for (var source : featureSources().entrySet()) {
            String code = source.getValue().replaceAll("//[^\n]*", "");
            if (code.contains("diagnosticTriggered()") || code.contains("diagnosticActed(")) {
                emitters.add(source.getKey());
            }
        }
        emitters.retainAll(FeatureManifest.FEATURES);
        assertEquals(emitters, new TreeSet<>(FeatureCatalog.RUNTIME_PROBES));
    }

    @Test
    public void requiredPathsAreEmittedByTheirFeature() throws IOException {
        Map<String, String> sources = featureSources();
        for (var path : FeatureCatalog.REQUIRED_PATHS.entrySet()) {
            String code = sources.get(path.getKey());
            assertTrue(path.getKey() + " never acts on " + path.getValue(),
                    code.contains("\"" + path.getValue() + "\""));
            assertTrue(FeatureCatalog.entries().get(path.getKey()).required);
        }
    }

    @Test
    public void enablementGatesMirrorEachFeaturesLeadingPreferenceCheck() throws IOException {
        Map<String, String> sources = featureSources();
        Map<String, String> fromSource = new TreeMap<>();
        for (String feature : FeatureManifest.FEATURES) {
            String code = sources.get(feature);
            if (code == null) continue;
            Matcher gate = LEADING_GATE.matcher(code);
            if (gate.find()) fromSource.put(feature, gate.group(1) + "=" + gate.group(2));
        }
        Map<String, String> declared = new TreeMap<>();
        for (var gate : FeatureCatalog.GATES.entrySet()) {
            declared.put(gate.getKey(), gate.getValue().key + "=" + gate.getValue().defaultValue);
        }
        assertEquals(fromSource, declared);
    }
}
