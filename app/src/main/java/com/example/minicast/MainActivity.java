package com.example.minicast;

import android.view.WindowInsets;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private Button btnSmartView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        btnSmartView = findViewById(R.id.btnSmartView);

        // Ekranı açık tut
        getWindow().getDecorView().setKeepScreenOn(true);

        // WebView ayarları (kullanıyorsanız)
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());
        // İstediğiniz test sayfasını yükleyin; mevcut akışınız farklıysa bunu koruyun
        // webView.loadUrl("https://example.com/video_test.html");

        btnSmartView.setOnClickListener(v -> openSmartViewSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveFullscreen();
        // Smart View’dan dönünce, telefonda tam ekran → TV’de gerçek tam ekran
        requestVideoFullscreenAndPlay();
    }

    // Android 11+ ve önceki için kesintisiz (sticky) immersive tam ekran
    private void applyImmersiveFullscreen() {
        View d = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = d.getWindowInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            d.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    // Smart View ayar ekranını aç (Samsung’larda WIFI_DISPLAY_SETTINGS çoğunlukla çalışır)
    private void openSmartViewSettings() {
        Intent[] attempts = new Intent[] {
                // 1) Samsung/Eski cihazlar
                new Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
                // 2) Genel Cast ayarları (yedek)
                new Intent(Settings.ACTION_CAST_SETTINGS)
        };
        for (Intent i : attempts) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                return;
            } catch (ActivityNotFoundException ignored) {}
        }
        // Hiçbiri olmazsa sistem ayarları
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (ActivityNotFoundException ignored) {}
    }

    // Web sayfasındaki <video>’yu bulup: requestFullscreen + play + “cover” ile doldur
    private void requestVideoFullscreenAndPlay() {
        if (webView == null) return;

        String js =
                "(function(){\n" +
                "  try {\n" +
                "    var v = document.querySelector('video');\n" +
                "    if (!v) {\n" +
                "      // Bazı sitelerde <video> gömülü olabilir; gömme çerçeveler için deneyin\n" +
                "      var ifr = document.querySelector('iframe');\n" +
                "      if (ifr && ifr.contentWindow) {\n" +
                "        try { ifr.contentWindow.document.querySelector('video').play(); } catch(e) {}\n" +
                "      }\n" +
                "      return 'no-video';\n" +
                "    }\n" +
                "    v.setAttribute('playsinline','');\n" +
                "    v.style.position='fixed';\n" +
                "    v.style.left='0'; v.style.top='0';\n" +
                "    v.style.width='100vw'; v.style.height='100vh';\n" +
                "    v.style.objectFit='cover';\n" +
                "    var fs = v.requestFullscreen || v.webkitRequestFullscreen || v.msRequestFullscreen || v.mozRequestFullScreen;\n" +
                "    if (fs) { try { fs.call(v); } catch(e) {} }\n" +
                "    try { v.muted = false; v.play().catch(()=>v.play()); } catch(e) {}\n" +
                "    return 'ok';\n" +
                "  } catch(e) { return 'err:' + (e.message||e); }\n" +
                "})();";

        webView.evaluateJavascript(js, value -> {
            // İsterseniz loglayın: value -> \"ok\" / \"no-video\" / \"err:...\"\n        });
    }
// ... MainActivity sınıfınızın içinde başka kodlar ...

@Override
public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
        View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }
}

// <- sınıfın KAPATAN parantezi mutlaka burada olmalı
}
