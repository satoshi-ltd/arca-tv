package com.satoshilimited.tv;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

final class MusicCovers {
    private static final long MAX_DISK = 150L * 1024 * 1024;
    private static volatile int generation;

    private MusicCovers() {
    }

    static int generation() {
        return generation;
    }

    static byte[] read(Context c, String volume, String key, boolean large) {
        File f = file(c, volume, key, large);
        if (!f.exists()) return null;
        try {
            byte[] raw = Files.readAllBytes(f.toPath());
            f.setLastModified(System.currentTimeMillis());
            return raw;
        } catch (Exception e) {
            f.delete();
            return null;
        }
    }

    static synchronized void write(Context c, String volume, String key, boolean large, byte[] raw, int requestGeneration) {
        if (requestGeneration != generation) return;
        File f = file(c, volume, key, large);
        File tmp = new File(f.getPath() + ".tmp");
        try {
            Files.write(tmp.toPath(), raw);
            if (!tmp.renameTo(f)) tmp.delete();
            trim(f.getParentFile());
        } catch (Exception e) {
            tmp.delete();
        }
    }

    static void forget(Context c) {
        generation++;
        File dir = new File(c.getCacheDir(), "music-covers");
        new Thread(() -> {
            synchronized (MusicCovers.class) {
                File[] files = dir.listFiles();
                if (files != null) for (File f : files) f.delete();
            }
        }).start();
    }

    private static void trim(File dir) {
        File[] files = dir.listFiles((parent, name) -> name.endsWith(".jpg"));
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_DISK) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_DISK * 3 / 4) break;
            long length = f.length();
            if (f.delete()) total -= length;
        }
    }

    private static File dir(Context c) {
        File dir = new File(c.getCacheDir(), "music-covers");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File file(Context c, String volume, String key, boolean large) {
        return new File(dir(c), digest(volume + "|" + key) + (large ? "-large" : "-small") + ".jpg");
    }

    private static String digest(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }
}
