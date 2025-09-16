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
 * MiniCast – Activity
 * - Çift log: app-özel dosya + Downloads + logcat (MiniCastCrash)
 * - Splash çıkışında null-safe
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MiniCastCrash";

    private WebView web;
    private ImageButton btnCast;

    /* =====================  LOG YARDIMCILARI  ===================== */

    // MediaStore ile Downloads’a yaz (API 29+)
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

        // Global yakalayıcı
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            dbg("Uncaught in thread: " + t.getName(), e);
            try { Toast.makeText(this, "Hata: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); }
            catch (Throwable ignored) {}
        });
        dbg("onCreate: START (handler installed)");

        // Splash/çıkış animasyonu (Android 12+), NULL-SAFE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getSplashScreen().setOnExitAnimationListener(splashView -> {
                    try {
                        View icon = splashView.getIconView(); // Bazı cihazlarda null gelebiliyor
                        if (icon != null) {
                            icon.setAlpha(1f); // güvenliyse
                        }
                    } catch (Throwable e) {
                        dbg("splash icon handling failed", e);
                    } finally {
                        try { splashView.remove(); } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable e) { dbg("splash listener err", e); }
        }

        dbg("before setContentView");
        setContentView(R.layout.activity_main);
        dbg("after setContentView");

        // WebView
        web = findViewById(R.id.web);
        if (web != null) {
            setupWebView(web);
            web.loadUrl("https://www.google.com");
        } else {
            dbg("WebView (R.id.web) is null!");
        }

        // Cast – güvenli init
        try {
            CastContext.getSharedInstance(this);
            dbg("CastContext initialized");
        } catch (Throwable e) {
            dbg("CastContext init failed", e);
        }

        // Buton
        btnCast = findViewById(R.id.btnCast);
        if (btnCast != null) {
            btnCast.setOnClickListener(v -> {
                dbg("btnCast clicked");
                Toast.makeText(this, "Cast menüsü/keşfi burada tetiklenecek.", Toast.LENGTH_SHORT).show();
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
