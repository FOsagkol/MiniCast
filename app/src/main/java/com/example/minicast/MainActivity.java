package com.example.minicast;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.cast.framework.CastContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * MiniCast – sade Activity
 * - Çift log: app-özel dosya + Downloads + logcat (MiniCastCrash)
 * - Hızlı WebView açılışı
 * - CastContext init try/catch (erken init kill’lerine karşı güvenli)
 *
 * NOT: layout tarafında en azından aşağıdaki id’ler olmalı:
 *   - R.layout.activity_main
 *   - R.id.web  (WebView)
 *   - R.id.btnCast  (ImageButton)  — yoksa sorun değil, null kontrolü var.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MiniCastCrash";

    private WebView web;
    private ImageButton btnCast;

    /* =====================  LOG YARDIMCILARI  ===================== */

    // MediaStore ile Downloads’a ek dosya düş (API 29+)
    private boolean writeToDownloads(String fileName, String text) {
        try {
            ContentValues v = new ContentValues();
            v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
            v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            Uri uri = getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                try (java.io.OutputStream os =
                             getContentResolver().openOutputStream(uri, "wa")) {
                    os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.flush();
                }
                return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    // Dosyaya (app-özel) + logcat’e yaz; mümkünse Downloads’a da yansıt
    private void dbg(String msg, @Nullable Throwable e) {
        Log.e(TAG, msg, e); // logcat

        try {
            File dir = getExternalFilesDir(null); // /sdcard/Android/data/.../files
            if (dir != null) {
                File f = new File(dir, "crash.txt");
                try (FileOutputStream fos = new FileOutputStream(f, true);
                     OutputStreamWriter osw = new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8);
                     PrintWriter pw = new PrintWriter(osw)) {

                    pw.println("=== " + new java.util.Date() + " ===");
                    pw.println(msg);
                    if (e != null) e.printStackTrace(pw);
                    pw.flush();
                    osw.flush();
                    fos.getFD().sync(); // diske yazmayı zorla
                }
            }
        } catch (Throwable ignore) {}

        try {
            String payload = "=== " + new java.util.Date() + " ===\n"
                    + msg + "\n"
                    + (e != null ? Log.getStackTraceString(e) : "") + "\n";
            writeToDownloads("minicast_crash.txt", payload);
        } catch (Throwable ignore) {}

        try { SystemClock.sleep(200); } catch (Throwable ignored) {} // kill öncesi flush
    }

    private void dbg(String msg) { dbg(msg, null); }

    /* =====================  ACTIVITY  ===================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1) Global yakalayıcı: beklenmeyen hatayı hem dosyaya hem Downloads’a hem logcat’e yaz.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            dbg("Uncaught in thread: " + t.getName(), e);
            try {
                Toast.makeText(this, "Hata: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        });
        dbg("onCreate: START (handler installed)");

        // 2) Splash/çıkış animasyonu (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getSplashScreen().setOnExitAnimationListener(splashView -> {
                    splashView.getIconView().setAlpha(1f);
                    splashView.remove();
                });
            } catch (Throwable e) { dbg("splash listener err", e); }
        }

        // 3) Arayüz
        dbg("before setContentView");
        setContentView(R.layout.activity_main);
        dbg("after setContentView");

        // 4) WebView
        web = findViewById(R.id.web);
        if (web != null) {
            setupWebView(web);
            web.loadUrl("https://www.google.com");
        } else {
            dbg("WebView (R.id.web) is null!");
        }

        // 5) Cast – güvenli init (hemen kill olursa try/catch)
        try {
            CastContext.getSharedInstance(this);
            dbg("CastContext initialized");
        } catch (Throwable e) {
            dbg("CastContext init failed", e);
        }

        // 6) Buton
        btnCast = findViewById(R.id.btnCast);
        if (btnCast != null) {
            btnCast.setOnClickListener(v -> {
                dbg("btnCast clicked");
                Toast.makeText(this, "Cast menüsü/keşfi burada tetiklenecek.", Toast.LENGTH_SHORT).show();
                // Burada mevcut keşif/bağlama kodunuzu çağırabilirsiniz (DLNA/Cast).
            });
        }
        dbg("onCreate: END");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }
        wv.setWebChromeClient(new WebChromeClient());
        wv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
    }

    @Override
    protected void onStop() {
        super.onStop();
        dbg("onStop");
    }

    @Override
    protected void onDestroy() {
        dbg("onDestroy");
        super.onDestroy();
    }
            }
