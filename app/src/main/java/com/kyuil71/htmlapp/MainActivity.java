package com.kyuil71.htmlapp;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
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

    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        s.setAllowContentAccess(false);
        // 페이지 색을 WebView가 임의로 어둡게 바꾸지 않도록 (페이지 자체 다크모드는 그대로 동작)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        }

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.addJavascriptInterface(new Haptic(this), "AndroidHaptic");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
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
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() { super.onPause(); web.onPause(); }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
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
