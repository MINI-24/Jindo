package com.jindo.launcher;

import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends BridgeActivity {

    // ROM folder picker (Files app). The tree URI becomes a plain path, since scanning reads files directly
    // with the all-files permission: .../tree/EE46-D98A%3Aroms%2Fswitch -> /storage/EE46-D98A/roms/switch
    private final ActivityResultLauncher<Uri> folderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> {
                String path = "";
                if (uri != null) {
                    String docId = android.provider.DocumentsContract.getTreeDocumentId(uri);
                    int colon = docId.indexOf(':');
                    String volume = colon < 0 ? docId : docId.substring(0, colon);
                    String rel = colon < 0 ? "" : docId.substring(colon + 1);
                    path = ("primary".equals(volume) ? "/storage/emulated/0" : "/storage/" + volume) + (rel.isEmpty() ? "" : "/" + rel);
                }
                final String js = "window.onFolderPicked && window.onFolderPicked(" + JSONObject.quote(path) + ");";
                WebView webView = getBridge().getWebView();
                webView.post(() -> webView.evaluateJavascript(js, null));
            });

    void pickFolder() {
        runOnUiThread(() -> folderPicker.launch(null));
    }

    // File picker for sounds, music, fonts and pack ZIPs. The pick is copied into app storage
    // (files/<subdir>/) so it survives; with subdir "packs" a ZIP is unpacked as an audio pack instead.
    private String pickJobId, pickSubdir;
    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                final String id = pickJobId, subdir = pickSubdir;
                if (uri == null) { notifyJob(id, "{\"cancelled\":true}"); return; }
                new Thread(() -> {
                    try {
                        String name = displayName(uri);
                        File dir = new File(getFilesDir(), subdir);
                        dir.mkdirs();
                        // Art files get a unique name so two pictures called image.jpg never overwrite each other
                        File out = new File(dir, "art".equals(subdir) ? System.currentTimeMillis() + "_" + name : name);
                        try (InputStream in = getContentResolver().openInputStream(uri)) { copy(in, out); }
                        if ("art".equals(subdir)) shrinkImage(out, 1920);
                        if ("packs".equals(subdir)) {
                            JSONObject pack = unzipPack(out, name.replaceAll("\\.zip$", ""));
                            out.delete();
                            notifyJob(id, pack.toString());
                        } else {
                            notifyJob(id, new JSONObject().put("path", out.getAbsolutePath()).put("name", name).toString());
                        }
                    } catch (Exception e) {
                        notifyJob(id, "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                    }
                }).start();
            });

    // ===== Backup / restore =====
    // The ZIP holds backup.json (the page's settings and library) and the app's stored files under files/<dir>/.
    static final String[] BACKUP_DIRS = { "art", "sounds", "music", "packs", "fonts", "video" };
    private String backupJobId, backupJson;
    private final ActivityResultLauncher<String> backupSaver = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                final String id = backupJobId, json = backupJson;
                if (uri == null) { notifyJob(id, "{\"cancelled\":true}"); return; }
                new Thread(() -> {
                    try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(getContentResolver().openOutputStream(uri))) {
                        zip.putNextEntry(new java.util.zip.ZipEntry("backup.json"));
                        zip.write(json.getBytes("UTF-8"));
                        zip.closeEntry();
                        int files = 0;
                        for (String dir : BACKUP_DIRS) files += zipTree(zip, new File(getFilesDir(), dir), dir);
                        zip.finish();
                        notifyJob(id, "{\"files\":" + files + "}");
                    } catch (Exception e) {
                        notifyJob(id, "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                    }
                }).start();
            });

    private int zipTree(java.util.zip.ZipOutputStream zip, File f, String name) throws java.io.IOException {
        File[] children = f.listFiles();
        if (children != null) {
            int n = 0;
            for (File c : children) n += zipTree(zip, c, name + "/" + c.getName());
            return n;
        }
        if (!f.isFile() || f.getName().endsWith(".part") || f.getName().endsWith(".shrink")) return 0;
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) zip.write(buf, 0, r);
        }
        zip.closeEntry();
        return 1;
    }

    void exportBackup(String id, String json, String fileName) {
        backupJobId = id;
        backupJson = json;
        runOnUiThread(() -> backupSaver.launch(fileName));
    }

    // Restore: files go back under files/<dir>/ (only the backup folders, never outside files/);
    // backup.json comes back to the page, which writes the settings and reloads.
    private final ActivityResultLauncher<String[]> backupOpener = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                final String id = backupJobId;
                if (uri == null) { notifyJob(id, "{\"cancelled\":true}"); return; }
                new Thread(() -> {
                    String json = null;
                    try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri))) {
                        String root = getFilesDir().getCanonicalPath() + File.separator;
                        java.util.zip.ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            if (e.isDirectory()) continue;
                            String name = e.getName().replace('\\', '/');
                            if (name.equals("backup.json")) {
                                ByteArrayOutputStream b = new ByteArrayOutputStream();
                                byte[] buf = new byte[65536];
                                int r;
                                while ((r = zin.read(buf)) > 0 && b.size() < 32 * 1024 * 1024) b.write(buf, 0, r);
                                json = b.toString("UTF-8");
                                continue;
                            }
                            String top = name.contains("/") ? name.substring(0, name.indexOf('/')) : "";
                            if (!java.util.Arrays.asList(BACKUP_DIRS).contains(top)) continue;
                            File out = new File(getFilesDir(), name);
                            if (!out.getCanonicalPath().startsWith(root)) continue;
                            out.getParentFile().mkdirs();
                            copy(zin, out);
                        }
                        if (json == null) throw new Exception("Jindo 백업 파일이 아니에요");
                        notifyJob(id, new JSONObject().put("json", json).toString());
                    } catch (Exception ex) {
                        notifyJob(id, "{\"error\":" + JSONObject.quote(String.valueOf(ex.getMessage())) + "}");
                    }
                }).start();
            });

    void importBackup(String id) {
        backupJobId = id;
        runOnUiThread(() -> backupOpener.launch(new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"}));
    }

    void pickFile(String id, String[] mimeTypes, String subdir) {
        pickJobId = id;
        pickSubdir = subdir;
        runOnUiThread(() -> filePicker.launch(mimeTypes));
    }

    // Result of a background job for the page: window.onNativeJob(id, json)
    void notifyJob(String id, String json) {
        final String js = "window.onNativeJob && window.onNativeJob(" + JSONObject.quote(id) + "," + JSONObject.quote(json) + ");";
        WebView webView = getBridge().getWebView();
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return safeName(c.getString(0));
        } catch (Exception ignored) {}
        return "file" + System.currentTimeMillis();
    }

    static String safeName(String name) {
        name = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return name.isEmpty() || name.startsWith(".") ? "file" + System.currentTimeMillis() : name;
    }

    static void copy(InputStream in, File out) throws java.io.IOException {
        try (java.io.OutputStream o = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    // A COCOON-style sound/music pack (Silk Pod asset ZIP or theme ZIP): every ".../sounds/<name>.<ext>" and
    // ".../music/<file>" goes to files/packs/<packId>/; metadata.json or theme.json comes back as text (playlist, volumes).
    // Only base names are written, so entries can't escape the pack folder.
    JSONObject unzipPack(File zip, String packId) throws Exception {
        File root = new File(new File(getFilesDir(), "packs"), safeName(packId));
        deleteTree(root);
        JSONObject sounds = new JSONObject();
        JSONArray music = new JSONArray();
        JSONObject overlays = new JSONObject();
        String meta = "";
        long total = 0;
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String path = "/" + e.getName().replace('\\', '/').toLowerCase();
                String base = e.getName().substring(e.getName().replace('\\', '/').lastIndexOf('/') + 1);
                // The folder right above the file decides (packs nest as assets/sounds/<id>/sounds/x.mp3); audio files only
                String parent = path.substring(0, path.lastIndexOf('/'));
                parent = parent.substring(parent.lastIndexOf('/') + 1);
                boolean audio = path.matches(".*\\.(mp3|ogg|wav|m4a|flac|aac|opus)$");
                String kind = !audio ? null : "sounds".equals(parent) ? "sounds" : "music".equals(parent) ? "music" : null;
                // Icon overlay packs: .../icon_overlays/<platform or global>/(mask|overlay).png
                if (kind == null && path.contains("/icon_overlays/") && path.matches(".*/(mask|overlay)\\.(png|webp)$")) {
                    File dir = new File(new File(root, "overlays"), safeName(parent));
                    dir.mkdirs();
                    File out = new File(dir, safeName(base));
                    copy(zin, out);
                    total += out.length();
                    overlays.put(parent + "/" + base.substring(0, base.lastIndexOf('.')), out.getAbsolutePath());
                    continue;
                }
                if (kind == null) {
                    if (base.equals("metadata.json") || base.equals("theme.json")) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zin.read(buf)) > 0 && b.size() < 262144) b.write(buf, 0, n);
                        meta = b.toString("UTF-8");
                    }
                    continue;
                }
                File dir = new File(root, kind);
                dir.mkdirs();
                File out = new File(dir, safeName(base));
                copy(zin, out);
                total += out.length();
                if (total > 500L * 1024 * 1024) throw new Exception("팩이 너무 커요 (500MB 초과)");
                if ("sounds".equals(kind)) {
                    String name = base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : base;
                    sounds.put(name.toLowerCase(), out.getAbsolutePath());
                } else {
                    music.put(out.getAbsolutePath());
                }
            }
        }
        return new JSONObject().put("packId", safeName(packId)).put("sounds", sounds).put("music", music).put("overlays", overlays).put("meta", meta);
    }

    // Pictures larger than the screen are scaled down in place (pictures with transparency stay PNG,
    // others become JPEG). Decoding a 4K hero took ~150 ms on the UI thread every time it was shown.
    static void shrinkImage(File f, int max) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = o.outWidth, h = o.outHeight;
            // Screen-sized but heavy (an opaque picture saved as PNG) is re-encoded too
            if (w <= 0 || h <= 0 || (Math.max(w, h) <= max && f.length() < 700_000)) return;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 1;
            while (Math.max(w, h) / (o.inSampleSize * 2) >= max) o.inSampleSize *= 2;
            Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return;
            float scale = (float) max / Math.max(b.getWidth(), b.getHeight());
            if (scale < 1f) {
                Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true);
                if (s != b) b.recycle();
                b = s;
            }
            File tmp = new File(f.getParentFile(), f.getName() + ".shrink");
            try (java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                if (hasTransparency(b)) b.compress(Bitmap.CompressFormat.PNG, 100, out);
                else b.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            b.recycle();
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Throwable ignored) {}
    }

    // PNGs often carry an alpha channel without using it; check a grid of pixels
    static boolean hasTransparency(Bitmap b) {
        if (!b.hasAlpha()) return false;
        int stepX = Math.max(1, b.getWidth() / 64), stepY = Math.max(1, b.getHeight() / 64);
        for (int y = 0; y < b.getHeight(); y += stepY)
            for (int x = 0; x < b.getWidth(); x += stepX)
                if (android.graphics.Color.alpha(b.getPixel(x, y)) < 250) return true;
        return false;
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        makeFullScreen();
        setupNativeBridge();
        // The status bar (system clock) can come back after dialogs, the keyboard or other apps; hide it again
        final View decor = getWindow().getDecorView();
        decor.setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) decor.postDelayed(this::makeFullScreen, 1200);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        makeFullScreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            makeFullScreen();
        }
    }

    private void makeFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void setupNativeBridge() {
        if (getBridge() != null && getBridge().getWebView() != null) {
            WebView webView = getBridge().getWebView();
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            // Sound effects and background music start from gamepad keys, which are not page gestures
            webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
            webView.addJavascriptInterface(new JindoNativeBridge(this), "JindoNative");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            String keyStr = null;

            if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                keyStr = "A";
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) {
                keyStr = "B";
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_X) {
                keyStr = "X";
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
                keyStr = "Y";
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                keyStr = "R1";
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                keyStr = "L1";
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
                keyStr = "R3";
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                // Odin 3's select-side button reports BUTTON_START; unhandled, Android turns it into DPAD_CENTER (= A)
                keyStr = "Select";
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                keyStr = "ArrowUp";
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                keyStr = "ArrowDown";
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                keyStr = "ArrowLeft";
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                keyStr = "ArrowRight";
            }

            if (keyStr != null && getBridge() != null && getBridge().getWebView() != null) {
                final String k = keyStr;
                // Second argument: the key is being held (auto-repeat), so lists stop at their ends instead of wrapping
                final boolean repeat = event.getRepeatCount() > 0;
                getBridge().getWebView().post(new Runnable() {
                    @Override
                    public void run() {
                        getBridge().getWebView().evaluateJavascript("if(window.handleNativeGamepadKey) window.handleNativeGamepadKey('" + k + "', " + repeat + ");", null);
                    }
                });
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    public static class JindoNativeBridge {
        private final Context context;

        public JindoNativeBridge(Context context) {
            this.context = context;
        }

        // Single source for the version shown in Settings: versionName in app/build.gradle
        @JavascriptInterface
        public String getAppVersion() {
            try {
                return "v" + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String getInstalledApps() {
            try {
                PackageManager pm = context.getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

                List<ResolveInfo> pkgAppsList = pm.queryIntentActivities(mainIntent, 0);
                JSONArray arr = new JSONArray();

                for (ResolveInfo resolveInfo : pkgAppsList) {
                    String pkg = resolveInfo.activityInfo.packageName;
                    if (pkg.equals(context.getPackageName())) continue; // Skip self

                    String label = resolveInfo.loadLabel(pm).toString();
                    Drawable icon = resolveInfo.loadIcon(pm);
                    String iconBase64 = drawableToBase64(icon);

                    JSONObject obj = new JSONObject();
                    obj.put("name", label);
                    obj.put("packageName", pkg);
                    obj.put("icon", iconBase64);
                    arr.put(obj);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public boolean launchApp(String packageName) {
            try {
                PackageManager pm = context.getPackageManager();
                Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launchIntent);
                    return true;
                }
            } catch (Exception ignored) {}
            return false;
        }

        @JavascriptInterface
        public void openSettings() {
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void openWifiSettings() {
            try {
                Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void openBluetoothSettings() {
            try {
                Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public int getBatteryLevel() {
            try {
                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                }
            } catch (Exception ignored) {}
            return 95;
        }

        @JavascriptInterface
        public String getQuickSettingsState() {
            JSONObject obj = new JSONObject();
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                obj.put("wifi", wm != null && wm.isWifiEnabled());

                boolean bt = false;
                try {
                    BluetoothManager btm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                    bt = btm != null && btm.getAdapter() != null && btm.getAdapter().isEnabled();
                } catch (SecurityException ignored) {}
                obj.put("bluetooth", bt);

                obj.put("brightness", Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128));
                obj.put("autoBrightness", Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                obj.put("canWriteSettings", Settings.System.canWrite(context));

                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                obj.put("volume", am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : 0);
                obj.put("volumeMax", am != null ? am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 15);

                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                obj.put("battery", bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 0);
                obj.put("charging", bm != null && bm.isCharging());
            } catch (Exception ignored) {}
            return obj.toString();
        }

        @JavascriptInterface
        public boolean setBrightness(int value) {
            try {
                if (!Settings.System.canWrite(context)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return false;
                }
                Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(0, Math.min(255, value)));
                return true;
            } catch (Exception ignored) {}
            return false;
        }

        @JavascriptInterface
        public void setVolume(int value) {
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, value)), 0);
                }
            } catch (Exception ignored) {}
        }

        // Android 10+ does not let apps switch Wi-Fi themselves (setWifiEnabled is a no-op for them);
        // there the system's small Wi-Fi panel opens instead, with its own switch. True = switched directly.
        @JavascriptInterface
        public boolean toggleWifi() {
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                boolean on = wm != null && wm.isWifiEnabled();
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wm != null) return wm.setWifiEnabled(!on);
                if (shizukuRun("cmd", "wifi", "set-wifi-enabled", on ? "disabled" : "enabled")) return true;
                if (shizukuAsked) return false;
            } catch (Exception ignored) {}
            openWifiPanel();
            return false;
        }

        // Shizuku: when its service runs and this app is allowed, run a shell command and report success.
        // Not allowed yet: ask (Shizuku shows its own dialog) and return false so the caller falls back.
        // Set when the Shizuku permission dialog was just shown, so no system panel opens on top of it
        private boolean shizukuAsked = false;

        private boolean shizukuRun(String... cmd) {
            shizukuAsked = false;
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) return false;
                if (rikka.shizuku.Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    if (!rikka.shizuku.Shizuku.shouldShowRequestPermissionRationale()) {
                        rikka.shizuku.Shizuku.requestPermission(42);
                        shizukuAsked = true;
                    }
                    return false;
                }
                // newProcess is private in API 13 but stays the simple way to run one command
                java.lang.reflect.Method newProcess = rikka.shizuku.Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                newProcess.setAccessible(true);
                Process p = (Process) newProcess.invoke(null, cmd, null, null);
                return p.waitFor() == 0;
            } catch (Throwable e) {
                return false;
            }
        }

        // Whether instant switching works: "ok", "denied" (Shizuku runs, permission not given) or "off"
        @JavascriptInterface
        public String shizukuState() {
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) return "off";
                return rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? "ok" : "denied";
            } catch (Throwable e) {
                return "off";
            }
        }

        // Bluetooth on/off through the system's confirm dialog (apps can't switch it silently on Android 13+)
        @JavascriptInterface
        public void toggleBluetooth() {
            try {
                BluetoothManager btm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                boolean on = btm != null && btm.getAdapter() != null && btm.getAdapter().isEnabled();
                if (shizukuRun("cmd", "bluetooth_manager", on ? "disable" : "enable") || shizukuAsked) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestQuickPermissions();
                    return;
                }
                Intent intent = new Intent(on ? "android.bluetooth.adapter.action.REQUEST_DISABLE" : android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e) {
                openBluetoothSettings();
            }
        }

        // Name of the connected Wi-Fi; Android only tells it with the location permission (else "")
        @JavascriptInterface
        public String getWifiName() {
            try {
                if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "";
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm == null || wm.getConnectionInfo() == null) return "";
                String ssid = wm.getConnectionInfo().getSSID();
                if (ssid == null || ssid.contains("<unknown ssid>")) return "";
                return ssid.replaceAll("^\"|\"$", "");
            } catch (Exception e) {
                return "";
            }
        }

        // Asks once for what the quick panel needs: location (Wi-Fi name) and Bluetooth (on/off dialog)
        @JavascriptInterface
        public void requestQuickPermissions() {
            final MainActivity activity = (MainActivity) context;
            List<String> missing = new ArrayList<>();
            if (activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(android.Manifest.permission.BLUETOOTH_CONNECT);
            if (!missing.isEmpty()) activity.runOnUiThread(() -> activity.requestPermissions(missing.toArray(new String[0]), 41));
        }

        @JavascriptInterface
        public void openWifiPanel() {
            try {
                Intent intent = new Intent(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean hasAllFilesAccess() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
        }

        @JavascriptInterface
        public void requestAllFilesAccess() {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {}
        }

        // Lists ROM files directly inside a folder (not recursive, so Update/DLC subfolders are skipped).
        // Returns {"files":[{"name","path"}],"error":null|String}.
        @JavascriptInterface
        public String listRomFiles(String dirPath, String extensionsCsv) {
            JSONObject result = new JSONObject();
            JSONArray files = new JSONArray();
            try {
                File dir = new File(dirPath);
                File[] children = dir.listFiles();
                if (children == null) {
                    result.put("error", dir.exists() ? "폴더를 읽을 수 없어요 (권한 확인)" : "폴더가 없어요");
                } else {
                    List<String> exts = new ArrayList<>();
                    for (String e : extensionsCsv.split(",")) exts.add("." + e.trim().toLowerCase());
                    for (File f : children) {
                        if (!f.isFile()) continue;
                        String name = f.getName();
                        String lower = name.toLowerCase();
                        for (String ext : exts) {
                            if (lower.endsWith(ext)) {
                                JSONObject o = new JSONObject();
                                o.put("name", name);
                                o.put("path", f.getAbsolutePath());
                                files.put(o);
                                break;
                            }
                        }
                    }
                    result.put("error", JSONObject.NULL);
                }
                result.put("files", files);
            } catch (Exception e) {
                try { result.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
            }
            return result.toString();
        }

        // Generic emulator launch, built from a spec the page derives from ES-DE's Android launch commands
        // (es_systems.xml / es_find_rules.xml): components to try, action, category, data URI, extras, flags.
        @JavascriptInterface
        public String launchIntent(String specJson) {
            try {
                JSONObject spec = new JSONObject(specJson);
                PackageManager pm = context.getPackageManager();
                JSONArray components = spec.optJSONArray("components");
                String pkg = null, activity = null;
                for (int i = 0; components != null && i < components.length(); i++) {
                    String entry = components.getString(i);
                    String p = entry.contains("/") ? entry.substring(0, entry.indexOf('/')) : entry;
                    if (!isInstalled(pm, p)) continue;
                    pkg = p;
                    if (entry.contains("/")) {
                        activity = entry.substring(entry.indexOf('/') + 1);
                        if (activity.startsWith(".")) activity = pkg + activity;
                    }
                    break;
                }
                if (pkg == null) return "에뮬레이터가 설치되어 있지 않아요.";

                Intent intent;
                if (activity != null) {
                    intent = new Intent();
                    intent.setComponent(new ComponentName(pkg, activity));
                } else {
                    intent = pm.getLaunchIntentForPackage(pkg);
                    if (intent == null) return "에뮬레이터를 실행할 수 없어요.";
                }
                if (!spec.isNull("action")) intent.setAction(spec.getString("action"));
                if (!spec.isNull("category")) intent.addCategory(spec.getString("category"));

                int flags = Intent.FLAG_ACTIVITY_NEW_TASK;
                JSONObject data = spec.optJSONObject("data");
                if (data != null) {
                    String value = data.getString("value").replace("%ANDROIDPACKAGE%", pkg);
                    Uri uri;
                    if ("provider".equals(data.getString("kind"))) {
                        File file = new File(value);
                        if (!file.canRead()) return "롬 파일을 읽을 수 없어요: " + value;
                        uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
                        flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    } else {
                        uri = Uri.parse(value);
                    }
                    intent.setDataAndType(uri, spec.isNull("mime") ? "application/octet-stream" : spec.getString("mime"));
                }

                JSONArray extras = spec.optJSONArray("extras");
                for (int i = 0; extras != null && i < extras.length(); i++) {
                    JSONObject e = extras.getJSONObject(i);
                    String key = e.getString("key");
                    switch (e.getString("type")) {
                        case "bool": intent.putExtra(key, e.getBoolean("value")); break;
                        case "int": intent.putExtra(key, e.getInt("value")); break;
                        case "array": {
                            JSONArray a = e.getJSONArray("value");
                            String[] arr = new String[a.length()];
                            for (int j = 0; j < arr.length; j++) arr[j] = a.getString(j).replace("%ANDROIDPACKAGE%", pkg);
                            intent.putExtra(key, arr);
                            break;
                        }
                        default: intent.putExtra(key, e.getString("value").replace("%ANDROIDPACKAGE%", pkg));
                    }
                }

                JSONArray f = spec.optJSONArray("flags");
                for (int i = 0; f != null && i < f.length(); i++) {
                    switch (f.getString(i)) {
                        case "CLEAR_TASK": flags |= Intent.FLAG_ACTIVITY_CLEAR_TASK; break;
                        case "CLEAR_TOP": flags |= Intent.FLAG_ACTIVITY_CLEAR_TOP; break;
                        case "NO_HISTORY": flags |= Intent.FLAG_ACTIVITY_NO_HISTORY; break;
                    }
                }
                intent.addFlags(flags);
                context.startActivity(intent);
                return "ok";
            } catch (Exception e) {
                return "실행 실패: " + e.getMessage();
            }
        }

        // One boolean per "pkg/activity" entry: is that package installed (marks emulators in Settings)
        // Pick a file (mime types comma separated) into files/<subdir>; subdir "packs" unpacks a sound/music ZIP
        @JavascriptInterface
        public void pickFile(String id, String mimeCsv, String subdir) {
            ((MainActivity) context).pickFile(id, mimeCsv.split(","), MainActivity.safeName(subdir));
        }

        // Download a pack ZIP (Silk Pod) and unpack it; result via window.onNativeJob
        @JavascriptInterface
        public void installPackFromUrl(final String id, final String url, final String packId) {
            final MainActivity activity = (MainActivity) context;
            new Thread(() -> {
                File tmp = new File(activity.getCacheDir(), "pack.zip");
                HttpURLConnection conn = null;
                try {
                    if (!url.startsWith("https://")) throw new Exception("https 주소만 받아요");
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("User-Agent", "Jindo-Launcher");
                    if (conn.getResponseCode() >= 400) throw new Exception("다운로드 실패 (" + conn.getResponseCode() + ")");
                    try (InputStream in = conn.getInputStream()) { copy(in, tmp); }
                    activity.notifyJob(id, activity.unzipPack(tmp, packId).toString());
                } catch (Exception e) {
                    activity.notifyJob(id, "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                } finally {
                    if (conn != null) conn.disconnect();
                    tmp.delete();
                }
            }).start();
        }

        // Art from the web (SteamGridDB etc.) is kept in files/art/ after the first download, so cards and
        // backgrounds load instantly and never paint half-downloaded; result {path} via window.onNativeJob
        @JavascriptInterface
        public void cacheImage(final String id, final String url) {
            final MainActivity activity = (MainActivity) context;
            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    if (!url.startsWith("https://") && !url.startsWith("http://")) throw new Exception("주소가 아니에요");
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                    StringBuilder hex = new StringBuilder();
                    for (byte b : md.digest(url.getBytes("UTF-8"))) hex.append(String.format("%02x", b));
                    String ext = url.replaceAll("[?#].*$", "").replaceAll("^.*?(\\.(png|jpe?g|webp|gif))?$", "$1").toLowerCase();
                    File dir = new File(activity.getFilesDir(), "art");
                    dir.mkdirs();
                    File out = new File(dir, hex + (ext.isEmpty() ? ".img" : ext));
                    if (!out.exists() || out.length() == 0) {
                        // Each download writes its own temp file (two taps used to write one file at once and corrupt it)
                        File tmp = new File(dir, hex + "." + Thread.currentThread().getId() + "." + System.nanoTime() + ".part");
                        conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(30000);
                        conn.setRequestProperty("User-Agent", "Jindo-Launcher");
                        if (conn.getResponseCode() >= 400) throw new Exception("HTTP " + conn.getResponseCode());
                        String type = conn.getContentType();
                        if (type != null && !type.startsWith("image/")) throw new Exception("이미지가 아니에요 (" + type + ")");
                        // Progress goes to the page as window.onArtProgress(url, percent); -1 when the size is unknown
                        final long total = conn.getContentLengthLong();
                        final WebView web = activity.getBridge().getWebView();
                        try (InputStream in = conn.getInputStream(); java.io.OutputStream o = new java.io.FileOutputStream(tmp)) {
                            byte[] buf = new byte[65536];
                            long done = 0;
                            int n, sent = -2;
                            while ((n = in.read(buf)) > 0) {
                                o.write(buf, 0, n);
                                done += n;
                                int pct = total > 0 ? (int) (done * 100 / total) : -1;
                                if (pct != sent && (pct < 0 ? sent != -1 : pct - sent >= 4 || pct == 100)) {
                                    sent = pct;
                                    final String js = "window.onArtProgress && window.onArtProgress(" + JSONObject.quote(url) + "," + pct + ");";
                                    web.post(() -> web.evaluateJavascript(js, null));
                                }
                            }
                        }
                        if (tmp.length() == 0) { tmp.delete(); throw new Exception("빈 파일"); }
                        shrinkImage(tmp, 1920);
                        synchronized (MainActivity.class) {
                            if (out.exists() && out.length() > 0) tmp.delete();
                            else if (!tmp.renameTo(out)) { tmp.delete(); throw new Exception("저장 실패"); }
                        }
                    }
                    activity.notifyJob(id, new JSONObject().put("path", out.getAbsolutePath()).toString());
                } catch (Exception e) {
                    activity.notifyJob(id, "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        // Plain GET for public JSON (the Silk Pod asset list only allows its own site through CORS)
        // Scales down every stored picture that is larger than the screen; result {done} via window.onNativeJob
        @JavascriptInterface
        public void shrinkStoredArt(final String id) {
            final MainActivity activity = (MainActivity) context;
            new Thread(() -> {
                int n = 0;
                File[] files = new File(activity.getFilesDir(), "art").listFiles();
                for (int i = 0; files != null && i < files.length; i++) {
                    long before = files[i].length();
                    shrinkImage(files[i], 1920);
                    if (files[i].length() != before) n++;
                }
                activity.notifyJob(id, "{\"done\":" + n + "}");
            }).start();
        }

        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            final MainActivity activity = (MainActivity) context;
            new Thread(() -> {
                int status = 0;
                String body = "";
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "Jindo-Launcher");
                    status = conn.getResponseCode();
                    InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
                    if (in != null) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close();
                        body = out.toString("UTF-8");
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
                try {
                    activity.notifyJob(id, new JSONObject().put("status", status).put("body", body).toString());
                } catch (Exception ignored) {}
            }).start();
        }

        // Removes a file this app stored itself (a replaced sound, a removed track or pack)
        @JavascriptInterface
        public boolean deleteFile(String path) {
            File f = new File(path);
            if (!f.getAbsolutePath().startsWith(context.getFilesDir().getAbsolutePath())) return false;
            MainActivity.deleteTree(f);
            return true;
        }

        @JavascriptInterface
        public void exportBackup(String id, String json, String fileName) {
            ((MainActivity) context).exportBackup(id, json, MainActivity.safeName(fileName));
        }

        @JavascriptInterface
        public void importBackup(String id) {
            ((MainActivity) context).importBackup(id);
        }

        // Short buzz for button presses; strength 1-255 (the Odin 3 motor supports amplitude control)
        @JavascriptInterface
        public void vibrate(int ms, int strength) {
            try {
                android.os.Vibrator v = (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                int amp = v.hasAmplitudeControl() ? Math.max(1, Math.min(255, strength)) : android.os.VibrationEffect.DEFAULT_AMPLITUDE;
                v.vibrate(android.os.VibrationEffect.createOneShot(Math.max(1, Math.min(200, ms)), amp));
            } catch (Exception ignored) {}
        }

        // Update: downloads the APK (progress via window.onArtProgress(url, %)) and opens Android's installer,
        // which asks the user to confirm. Result via window.onNativeJob.
        @JavascriptInterface
        public void installUpdate(final String id, final String url) {
            final MainActivity activity = (MainActivity) context;
            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    if (!url.startsWith("https://")) throw new Exception("https 주소만 받아요");
                    File apk = new File(activity.getCacheDir(), "jindo-update.apk");
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("User-Agent", "Jindo-Launcher");
                    if (conn.getResponseCode() >= 400) throw new Exception("다운로드 실패 (" + conn.getResponseCode() + ")");
                    final long total = conn.getContentLengthLong();
                    final WebView web = activity.getBridge().getWebView();
                    try (InputStream in = conn.getInputStream(); java.io.OutputStream o = new java.io.FileOutputStream(apk)) {
                        byte[] buf = new byte[65536];
                        long done = 0;
                        int n, sent = -1;
                        while ((n = in.read(buf)) > 0) {
                            o.write(buf, 0, n);
                            done += n;
                            int pct = total > 0 ? (int) (done * 100 / total) : -1;
                            if (pct >= 0 && pct - sent >= 3) {
                                sent = pct;
                                final String js = "window.onArtProgress && window.onArtProgress(" + JSONObject.quote(url) + "," + pct + ");";
                                web.post(() -> web.evaluateJavascript(js, null));
                            }
                        }
                    }
                    Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(intent);
                    activity.notifyJob(id, "{\"ok\":true}");
                } catch (Exception e) {
                    activity.notifyJob(id, "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        @JavascriptInterface
        public void pickFolder() {
            ((MainActivity) context).pickFolder();
        }

        @JavascriptInterface
        public String installedComponents(String componentsJsonArray) {
            JSONArray out = new JSONArray();
            try {
                JSONArray in = new JSONArray(componentsJsonArray);
                PackageManager pm = context.getPackageManager();
                for (int i = 0; i < in.length(); i++) {
                    String entry = in.getString(i);
                    out.put(isInstalled(pm, entry.contains("/") ? entry.substring(0, entry.indexOf('/')) : entry));
                }
            } catch (Exception ignored) {}
            return out.toString();
        }

        // ES-DE %INJECT%: small text files next to a game (e.g. a .psvita file holding the Vita title ID)
        @JavascriptInterface
        public String readTextFile(String path) {
            try {
                File file = new File(path);
                if (!file.isFile() || !file.canRead() || file.length() > 4096) return "";
                InputStream in = new java.io.FileInputStream(file);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                return out.toString("UTF-8").trim();
            } catch (Exception e) {
                return "";
            }
        }

        // SteamGridDB API call off the UI thread; the WebView can't call it directly because the API sends no CORS headers.
        // The result goes back through window.onSgdbResult(requestId, httpStatus, body).
        @JavascriptInterface
        public void sgdbRequest(final String requestId, final String path, final String apiKey) {
            new Thread(() -> {
                int status = 0;
                String body = "";
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL("https://www.steamgriddb.com/api/v2" + path).openConnection();
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    status = conn.getResponseCode();
                    InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
                    if (in != null) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close();
                        body = out.toString("UTF-8");
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final String js = "window.onSgdbResult && window.onSgdbResult(" + JSONObject.quote(requestId) + "," + status + "," + JSONObject.quote(body) + ");";
                final WebView webView = ((MainActivity) context).getBridge().getWebView();
                webView.post(() -> webView.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public String getClipboardText() {
            try {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
                    return text != null ? text.toString() : "";
                }
            } catch (Exception ignored) {}
            return "";
        }

        private boolean isInstalled(PackageManager pm, String pkg) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean isAndroidDevice() {
            return true;
        }

        private String drawableToBase64(Drawable drawable) {
            try {
                Bitmap bitmap;
                if (drawable instanceof BitmapDrawable) {
                    bitmap = ((BitmapDrawable) drawable).getBitmap();
                } else {
                    bitmap = Bitmap.createBitmap(
                        Math.max(drawable.getIntrinsicWidth(), 72),
                        Math.max(drawable.getIntrinsicHeight(), 72),
                        Bitmap.Config.ARGB_8888
                    );
                    Canvas canvas = new Canvas(bitmap);
                    drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                    drawable.draw(canvas);
                }
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream);
                byte[] bytes = stream.toByteArray();
                return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }
    }
}
