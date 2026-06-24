package com.waenhancer.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public final class RootUtils {
    private RootUtils() {
    }

    public static boolean hasRootAccess() {
        // First check standard bin paths
        boolean suExists = false;
        String[] paths = {"/system/xbin/su", "/system/bin/su", "/sbin/su", "/system/sd/xbin/su", 
                          "/system/bin/failsafe/su", "/data/local/xbin/su", "/data/local/bin/su", 
                          "/data/local/su", "/su/bin/su"};
        for (String path : paths) {
            if (new java.io.File(path).exists()) {
                suExists = true;
                break;
            }
        }
        if (!suExists) {
            // Check PATH environment variable
            try {
                String pathEnv = System.getenv("PATH");
                if (pathEnv != null) {
                    for (String dir : pathEnv.split(":")) {
                        if (new java.io.File(dir, "su").exists()) {
                            suExists = true;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (!suExists) {
            return false;
        }

        String output = runRootCommand("id");
        return output != null && (output.contains("uid=0") || output.contains("root"));
    }

    public static String runRootCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            return output.toString().trim();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }
}
