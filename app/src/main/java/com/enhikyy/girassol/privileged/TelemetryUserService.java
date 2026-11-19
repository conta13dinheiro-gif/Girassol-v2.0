package com.enhikyy.girassol.privileged;

import android.os.Process;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

/**
 * Read-only Shizuku UserService. It deliberately exposes no shell execution,
 * file mutation, package management or privilege-escalation method.
 */
public final class TelemetryUserService extends IShizukuTelemetry.Stub {
    private static String readFirstLines(String path, int limit) {
        File file = new File(path);
        if (!file.isFile() || !file.canRead()) return "unavailable";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int count = 0;
            while (count < limit && (line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
                count++;
            }
        } catch (IOException | SecurityException error) {
            return "unavailable: " + error.getClass().getSimpleName();
        }
        return out.toString();
    }

    @Override
    public String readTelemetry() {
        int uid = Process.myUid();
        int pid = Process.myPid();
        String thermal = readFirstLines("/sys/class/thermal/thermal_zone0/temp", 1);
        String zram = readFirstLines("/sys/block/zram0/mm_stat", 1);
        String memory = readFirstLines("/proc/meminfo", 8);
        return String.format(
            Locale.ROOT,
            "uid=%d\npid=%d\nthermal_zone0=%s\nzram_mm_stat=%s\nmeminfo:\n%s",
            uid, pid, thermal, zram, memory
        );
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
