package com.kyuil71.softsmile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window win = getWindow();
        win.setStatusBarColor(Color.parseColor("#F8F2EA"));
        win.setNavigationBarColor(Color.parseColor("#FFFAF1"));
        win.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#F8F2EA"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // 메모(localStorage) 저장
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);                // 시스템 글꼴 크기와 무관하게 디자인 그대로

        web.addJavascriptInterface(new Haptic(this), "AndroidHaptic");
        web.setWebViewClient(new WebViewClient() {
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
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

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
                    t[0] = 0;
                    for (int i = 0; i < parts.length; i++) t[i + 1] = Math.round(Double.parseDouble(parts[i].trim()));
                    vib.vibrate(VibrationEffect.createWaveform(t, -1));
                }
            } catch (Exception ignored) { }
        }
    }
}
