package io.github.leekin2.xnotify;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone's launcher apps, sent to the tablet through the notification link itself so the
 * tablet's app picker can list them. One "package\tlabel" per line.
 */
final class AppCatalog {
    /** Title of the hidden catalogue message; never shown as a notification. */
    static final String MARKER = "XNOTIFY_APP_CATALOG";
    /** First line of the list when the phone serves icons: "#icons\thost:port/key". */
    static final String ICON_LINE = "#icons";
    /** Notification text is not cut when written straight into extras, but the link drops messages whose text is much above 2 KB (encryption and encoding roughly double it). */
    static final int CHUNK = 1_000;
    static final String SELF = "io.github.leekin2.xnotify";
    private static final String FILE_PREFIX = "xnotify_apps_";

    static String build(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, String> apps = new LinkedHashMap<>();
        for (ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
            String pkg = r.activityInfo.packageName;
            if (SELF.equals(pkg) || apps.containsKey(pkg)) continue;
            String label = String.valueOf(r.loadLabel(pm)).replace('\t', ' ').replace('\n', ' ');
            apps.put(pkg, label);
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(apps.entrySet());
        sorted.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : sorted) out.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        return out.toString();
    }

    /** package → label, in the order sent. */
    static Map<String, String> parse(String payload) {
        Map<String, String> apps = new LinkedHashMap<>();
        if (payload == null) return apps;
        for (String line : payload.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0 && line.charAt(0) != '#') apps.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return apps;
    }

    /** The icon address announced in a list, or null. */
    static String iconAddress(String payload) {
        if (payload == null || !payload.startsWith(ICON_LINE + "\t")) return null;
        int end = payload.indexOf('\n');
        return payload.substring(ICON_LINE.length() + 1, end < 0 ? payload.length() : end);
    }

    /** Whether some listed app has neither a local install nor a stored icon here. */
    static boolean missingIcons(Context context, java.util.Collection<String> packages) {
        File dir = new File(context.getFilesDir(), "xnotify_icons");
        PackageManager pm = context.getPackageManager();
        for (String pkg : packages) {
            if (new File(dir, pkg + ".webp").isFile()) continue;
            try { pm.getApplicationInfo(pkg, 0); } catch (PackageManager.NameNotFoundException e) { return true; }
        }
        return false;
    }

    /** Split on line boundaries into parts of at most {@link #CHUNK} characters. */
    static List<String> chunks(String payload) {
        List<String> out = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (String line : payload.split("\n")) {
            if (part.length() + line.length() + 1 > CHUNK && part.length() > 0) { out.add(part.toString()); part.setLength(0); }
            part.append(line).append('\n');
        }
        if (part.length() > 0 || out.isEmpty()) out.add(part.toString());
        return out;
    }

    /** Title of one part: KIND|index|count|id. */
    static String partTitle(String kind, int index, int count, String id) {
        return kind + "|" + index + "|" + count + "|" + id;
    }

    /**
     * Stores one received part; once every part of the same list is present, they become the
     * device's catalogue. Returns the merged list, or null while parts are missing.
     */
    static String receivePart(Context context, String deviceId, String title, String payload) {
        String kind = title.substring(0, title.indexOf('|'));
        String[] f = title.split("\\|");
        if (f.length != 4) return null;
        int index = Integer.parseInt(f[1]), count = Integer.parseInt(f[2]);
        String id = f[3].replaceAll("[^A-Za-z0-9]", "");
        File dir = new File(context.getFilesDir(), "xnotify_parts");
        dir.mkdirs();
        File[] old = dir.listFiles();
        long dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        if (old != null) for (File x : old) if (x.lastModified() < dayAgo) x.delete();
        try (FileOutputStream out = new FileOutputStream(new File(dir, deviceId.replaceAll("[^A-Za-z0-9]", "_") + "_" + kind + "_" + id + "_" + index))) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        StringBuilder merged = new StringBuilder();
        for (int i = 0; i < count; i++) {
            File part = new File(dir, deviceId.replaceAll("[^A-Za-z0-9]", "_") + "_" + kind + "_" + id + "_" + i);
            if (!part.isFile()) return null;
            try { merged.append(new String(Files.readAllBytes(part.toPath()), StandardCharsets.UTF_8)); } catch (Exception e) { return null; }
        }
        String full = merged.toString();
        // Parts are kept for the next round, so every later part re-completes the same list.
        if (full.equals(load(context, deviceId))) return null;
        if (MARKER.equals(kind)) save(context, deviceId, full);
        File[] stale = dir.listFiles();
        if (stale != null) for (File x : stale) if (x.getName().startsWith(deviceId.replaceAll("[^A-Za-z0-9]", "_") + "_" + kind + "_")
                && !x.getName().contains("_" + id + "_")) x.delete();
        return full;
    }

    /** Small launcher icons of the given apps, as "package\tbase64 webp" lines. */
    static String buildIcons(Context context, java.util.Collection<String> packages) {
        PackageManager pm = context.getPackageManager();
        StringBuilder out = new StringBuilder();
        for (String pkg : packages) {
            try {
                android.graphics.drawable.Drawable d = pm.getApplicationIcon(pkg);
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, 96, 96);
                d.draw(new android.graphics.Canvas(b));
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                b.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 90, bytes);
                out.append(pkg).append('\t').append(android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)).append('\n');
            } catch (Exception ignored) { }
        }
        return out.toString();
    }

    static void saveIcons(Context context, String payload) {
        File dir = new File(context.getFilesDir(), "xnotify_icons");
        dir.mkdirs();
        for (Map.Entry<String, String> e : parse(payload).entrySet()) {
            try (FileOutputStream out = new FileOutputStream(new File(dir, e.getKey() + ".webp"))) {
                out.write(android.util.Base64.decode(e.getValue(), android.util.Base64.NO_WRAP));
            } catch (Exception ignored) { }
        }
    }

    /** The app's icon (this device's own when installed, else the phone's), drawn at {@code px} pixels. */
    static android.graphics.drawable.Drawable icon(Context context, String pkg, int px) {
        android.graphics.drawable.Drawable source = null;
        try { source = context.getPackageManager().getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
        if (source == null) {
            File f = new File(new File(context.getFilesDir(), "xnotify_icons"), pkg + ".webp");
            android.graphics.Bitmap b = f.isFile() ? android.graphics.BitmapFactory.decodeFile(f.getPath()) : null;
            if (b == null) return null;
            source = new android.graphics.drawable.BitmapDrawable(context.getResources(), b);
        }
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        source.setBounds(0, 0, px, px);
        source.draw(new android.graphics.Canvas(out));
        out.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        return new android.graphics.drawable.BitmapDrawable(context.getResources(), out);
    }

    static void save(Context context, String deviceId, String payload) {
        try (FileOutputStream out = new FileOutputStream(file(context, deviceId))) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    static String load(Context context, String deviceId) {
        try {
            File f = file(context, deviceId);
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static File file(Context context, String deviceId) {
        return new File(context.getFilesDir(), FILE_PREFIX + deviceId.replaceAll("[^A-Za-z0-9]", "_"));
    }

    private AppCatalog() { }
}
