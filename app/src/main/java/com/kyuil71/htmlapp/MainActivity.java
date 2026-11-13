package com.kyuil71.htmlapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;

/**
 * app/src/<앱>/assets/index.html 을 화면 전체로 띄우는 공통 액티비티.
 * 에셋은 https://appassets.androidplatform.net/assets/ 주소로 열어
 * ES 모듈(import), 웹폰트, localStorage가 일반 웹사이트처럼 동작합니다.
 */
public class MainActivity extends Activity {

    // 앱마다 시작 주소가 다를 수 있음 (기본: 앱 안의 assets/index.html, 웹사이트를 그대로 여는 앱은 그 주소)
    private static final String HOME = BuildConfig.START_URL;
    private static final int REQ_CAMERA = 71;
    private WebView web;
    private SharedPreferences prefs;
    private PermissionRequest pendingPerm;
    private static final int REQ_FILE = 72;
    private ValueCallback<Uri[]> fileCb;   // <input type=file> (e.g. bringing back a backup file)
    // 기울기: WebView의 devicemotion은 화면을 한 번 터치해야 시작되는 경우가 있어, 가속도(중력 포함)를 앱이 직접 페이지에 전달
    private SensorManager sm;
    private long lastGrav = 0;
    private final SensorEventListener grav = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            long t = System.nanoTime();
            if (t - lastGrav < 15_000_000L || web == null) return;   // ~60회/초
            lastGrav = t;
            web.evaluateJavascript("window.__hfGrav&&__hfGrav(" + e.values[0] + "," + e.values[1] + "," + e.values[2] + ")", null);
        }
        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        if (BuildConfig.LOCK_ROTATION) {
            // 켠 순간의 방향으로 고정 (큰 화면에서도 targetSdk 34라 이 요청이 지켜짐)
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
        if (BuildConfig.IMMERSIVE) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 28) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }

        int bg = getResources().getColor(R.color.app_bg, getTheme());
        boolean lightBars = getResources().getBoolean(R.bool.light_bars);
        Window win = getWindow();
        win.setStatusBarColor(bg);
        win.setNavigationBarColor(bg);
        win.getDecorView().setSystemUiVisibility(lightBars
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : 0);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // localStorage 저장
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);                // 시스템 글꼴 크기와 무관하게 디자인 그대로
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);    // 파일 선택 창에서 고른 파일(content://)을 페이지가 읽을 수 있게 (백업 가져오기)
        // 페이지 색을 WebView가 임의로 어둡게 바꾸지 않도록 (페이지 자체 다크모드는 그대로 동작)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        }

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.addJavascriptInterface(new Haptic(this), "AndroidHaptic");
        // 녹화(MP4)·캡처(JPEG)를 갤러리에 저장 (WebView는 blob 다운로드를 못 하므로)
        web.addJavascriptInterface(new Saver(this), "AndroidSave");
        // 페이지의 JS 오류를 화면(로딩 문구)에 그대로 보여줘 원인을 바로 알 수 있게
        web.setWebChromeClient(new WebChromeClient() {
            // 페이지의 카메라 요청(getUserMedia) → 안드로이드 카메라 권한으로 연결
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(new Runnable() { public void run() {
                    boolean cam = false;
                    for (String r : req.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) cam = true;
                    if (!cam) { req.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        if (pendingPerm != null) pendingPerm.deny();
                        pendingPerm = req;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                }});
            }

            // 페이지의 파일 선택(<input type=file>) → 안드로이드 파일 선택 창
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(Intent.createChooser(i, "파일 선택"), REQ_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    String src = m.sourceId() == null ? "" : m.sourceId().substring(m.sourceId().lastIndexOf('/') + 1);
                    report(m.message() + " (" + src + ":" + m.lineNumber() + ")");
                }
                return super.onConsoleMessage(m);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse r = loader.shouldInterceptRequest(request.getUrl());
                if (r != null && r.getData() == null && "appassets.androidplatform.net".equals(request.getUrl().getHost())) {
                    report("앱 안에 파일이 없음: " + request.getUrl().getPath());
                }
                return r;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // WebView에는 웹 진동 API가 없어 기기 진동으로 연결합니다
                view.evaluateJavascript(
                        "navigator.vibrate=function(p){try{AndroidHaptic.vibrate(JSON.stringify(p));}catch(e){}return true;};",
                        null);
            }
        });

        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME);
        if (BuildConfig.LOCK_ROTATION) askWriteSettings();
    }

    @Override
    public void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_FILE || fileCb == null) return;
        Uri[] r = null;
        if (result == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
        fileCb.onReceiveValue(r);
        fileCb = null;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_CAMERA || pendingPerm == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pendingPerm.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        } else pendingPerm.deny();
        pendingPerm = null;
    }

    /* ---------- 화면 회전 완전 고정 ----------
     * 안드로이드 16의 큰 화면(폴드 내부 화면)은 앱의 방향 고정 요청을 무시할 수 있어서,
     * '시스템 설정 변경' 권한이 있으면 앱이 보이는 동안만 기기의 자동 회전을 끄고
     * 지금 방향으로 고정했다가, 앱을 떠나면 원래 설정으로 되돌립니다. */
    private boolean canWriteSettings() {
        return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
    }

    private void askWriteSettings() {
        if (canWriteSettings() || prefs.getBoolean("ws_never", false)) return;
        new AlertDialog.Builder(this)
                .setTitle("화면 회전 고정")
                .setMessage("폴드 내부 화면에서도 화면이 돌아가지 않게 하려면 '시스템 설정 변경'을 허용해 주세요.\n\n"
                        + "앱이 켜져 있는 동안만 자동 회전을 잠시 끄고, 앱을 나가면 원래대로 되돌립니다.")
                .setPositiveButton("허용하러 가기", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("나중에", null)
                .setNeutralButton("다시 묻지 않기", (d, w) -> prefs.edit().putBoolean("ws_never", true).apply())
                .show();
    }

    private int currentRotation() {
        try {
            if (Build.VERSION.SDK_INT >= 30 && getDisplay() != null) return getDisplay().getRotation();
            return getWindowManager().getDefaultDisplay().getRotation();
        } catch (Exception e) { return 0; }
    }

    private void lockRotation() {
        if (!BuildConfig.LOCK_ROTATION || !canWriteSettings()) return;
        try {
            ContentResolver cr = getContentResolver();
            if (!prefs.getBoolean("rot_saved", false)) {
                prefs.edit().putInt("rot_prev", Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1))
                        .putBoolean("rot_saved", true).apply();
            }
            Settings.System.putInt(cr, Settings.System.USER_ROTATION, currentRotation());
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0);
        } catch (Exception ignored) { }
    }

    private void unlockRotation() {
        if (!prefs.getBoolean("rot_saved", false)) return;
        try {
            Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, prefs.getInt("rot_prev", 1));
        } catch (Exception ignored) { }
        prefs.edit().remove("rot_saved").apply();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        // 접거나 펼쳐 화면이 바뀌면 그 화면의 지금 방향으로 다시 고정
        if (BuildConfig.LOCK_ROTATION) lockRotation();
        if (BuildConfig.IMMERSIVE) immersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && BuildConfig.IMMERSIVE) immersive();
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void report(final String msg) {
        if (web == null) return;
        final String js = "window.__showErr&&window.__showErr(" + org.json.JSONObject.quote(msg) + ")";
        web.post(new Runnable() { public void run() { web.evaluateJavascript(js, null); } });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() { super.onPause(); web.onPause(); unlockRotation(); if (sm != null) sm.unregisterListener(grav); }

    @Override
    protected void onResume() {
        super.onResume(); web.onResume();
        lockRotation();
        if (sm == null) sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sm.registerListener(grav, acc, SensorManager.SENSOR_DELAY_GAME);
        if (BuildConfig.IMMERSIVE) immersive();
    }

    @Override
    protected void onDestroy() { unlockRotation(); super.onDestroy(); }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** 페이지가 만든 파일(녹화·캡처)을 조각(base64)으로 받아 갤러리(사진/동영상)에 저장 */
    static class Saver {
        private final Activity act;
        private final Map<Integer, Object[]> open = new HashMap<>();
        private int next = 1;

        Saver(Activity a) { act = a; }

        /** 2 = 사진·동영상 외 파일은 다운로드 폴더에 저장 + 파일 선택 창 지원 (페이지가 기능 유무를 확인) */
        @JavascriptInterface
        public int version() { return 2; }

        @JavascriptInterface
        public synchronized int begin(String name, String mime) {
            if (Build.VERSION.SDK_INT < 29) return -1;
            try {
                boolean video = mime != null && mime.startsWith("video");
                boolean media = mime != null && (mime.startsWith("video") || mime.startsWith("image"));
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (!media ? Environment.DIRECTORY_DOWNLOADS : video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Hand Flow Art");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                // 사진·동영상은 갤러리, 그 밖의 파일(백업 등)은 다운로드 폴더
                Uri col = !media ? MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = act.getContentResolver().insert(col, v);
                if (uri == null) return -1;
                OutputStream os = act.getContentResolver().openOutputStream(uri);
                if (os == null) return -1;
                int id = next++;
                open.put(id, new Object[]{uri, os, video, media});
                return id;
            } catch (Exception e) { return -1; }
        }

        @JavascriptInterface
        public synchronized boolean append(int id, String b64) {
            Object[] o = open.get(id);
            if (o == null) return false;
            try { ((OutputStream) o[1]).write(Base64.decode(b64, Base64.DEFAULT)); return true; }
            catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public synchronized boolean end(int id) {
            Object[] o = open.remove(id);
            if (o == null) return false;
            try {
                ((OutputStream) o[1]).close();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                act.getContentResolver().update((Uri) o[0], v, null, null);
                final String msg = !((Boolean) o[3]) ? "다운로드 폴더(Hand Flow Art)에 저장했어요" : ((Boolean) o[2]) ? "동영상을 갤러리에 저장했어요" : "사진을 갤러리에 저장했어요";
                act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_SHORT).show());
                return true;
            } catch (Exception e) { return false; }
        }
    }

    static class Haptic {
        private final Vibrator vib;

        Haptic(Context ctx) {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vib = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
        }

        @JavascriptInterface
        public void vibrate(String pattern) {
            if (vib == null || pattern == null) return;
            try {
                String p = pattern.trim().replace("[", "").replace("]", "");
                if (p.isEmpty()) { vib.cancel(); return; }
                String[] parts = p.split(",");
                if (parts.length == 1) {
                    long ms = Math.round(Double.parseDouble(parts[0].trim()));
                    if (ms <= 0) { vib.cancel(); return; }
                    vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    long[] t = new long[parts.length + 1];
                    for (int i = 0; i < parts.length; i++) t[i + 1] = Math.round(Double.parseDouble(parts[i].trim()));
                    vib.vibrate(VibrationEffect.createWaveform(t, -1));
                }
            } catch (Exception ignored) { }
        }
    }
}
