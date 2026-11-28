package com.waenhancer.xposed.core.devkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class CacheIdentityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void sameFilesGiveTheSameIdentity() throws Exception {
        File base = file("base.apk", "base");
        File split = file("split_config.xxhdpi.apk", "split");
        assertEquals(CacheIdentity.of(List.of(base.getPath(), split.getPath())),
                CacheIdentity.of(List.of(base.getPath(), split.getPath())));
    }

    @Test
    public void sameVersionCodeWithDifferentSplitsInvalidates() throws Exception {
        File base = file("base.apk", "base");
        File xxhdpi = file("split_config.xxhdpi.apk", "split");
        File hdpi = file("split_config.hdpi.apk", "split");
        assertNotEquals(CacheIdentity.of(List.of(base.getPath(), xxhdpi.getPath())),
                CacheIdentity.of(List.of(base.getPath(), hdpi.getPath())));
        assertNotEquals(CacheIdentity.of(List.of(base.getPath())),
                CacheIdentity.of(List.of(base.getPath(), hdpi.getPath())));
    }

    @Test
    public void rewrittenBaseInvalidates() throws Exception {
        File base = file("base.apk", "base");
        String before = CacheIdentity.of(List.of(base.getPath()));
        Files.write(base.toPath(), "reinstalled base with other bytes".getBytes());
        assertNotEquals(before, CacheIdentity.of(List.of(base.getPath())));
        String sameSize = CacheIdentity.of(List.of(base.getPath()));
        base.setLastModified(base.lastModified() - 60_000);
        assertNotEquals(sameSize, CacheIdentity.of(List.of(base.getPath())));
    }

    private File file(String name, String content) throws Exception {
        File file = temp.newFile(name);
        Files.write(file.toPath(), content.getBytes());
        return file;
    }
}
