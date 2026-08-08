package com.waenhancer.xposed.core.db;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;

/**
 * Stages a user-supplied upstream database snapshot. It never replaces the live database:
 * callers must force-stop/checkpoint upstream first, then import records through the current
 * schema after validating this staged copy.
 */
public final class UpstreamDeletedDataMigrationAssistant {
    private UpstreamDeletedDataMigrationAssistant() { }
    public static File stageSnapshot(Context context, File source, String expectedSha256) throws IOException {
        if (source == null || !source.isFile() || expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) throw new IOException("Invalid upstream snapshot");
        if (!expectedSha256.equalsIgnoreCase(sha256(source))) throw new IOException("Upstream snapshot checksum mismatch");
        File directory = new File(context.getFilesDir(), "upstream_migration");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create migration staging");
        File target = new File(directory, "delmessages-" + System.currentTimeMillis() + ".db");
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) { byte[] b=new byte[32768]; for(int n;(n=in.read(b))!=-1;) out.write(b,0,n); out.getFD().sync(); }
        if (!expectedSha256.equalsIgnoreCase(sha256(target))) { target.delete(); throw new IOException("Staged snapshot verification failed"); }
        return target;
    }
    private static String sha256(File file) throws IOException { try { MessageDigest d=MessageDigest.getInstance("SHA-256"); try(FileInputStream in=new FileInputStream(file)){byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);} StringBuilder s=new StringBuilder(64);for(byte v:d.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",v));return s.toString(); } catch(Exception e){throw new IOException("Could not hash snapshot",e);} }
}
