package com.example.minicast;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import androidx.mediarouter.app.MediaRouteDialogFactory;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MiniCastCrash";
    private static final int REQ_FINE_LOCATION = 42;

    private WebView web;
    private ImageButton btnCast;

    /* =====================  LOG YARDIMCILARI  ===================== */

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

    private void dbg(String msg, @Nullable Throwable e) {
        Log.e(TAG, msg, e);
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, "crash.txt");
                try (FileOutputStream fos = new FileOutputStream(f, true);
                     OutputStreamWriter osw = new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8);
                     PrintWriter pw = new PrintWriter(osw)) {
                    pw.println("=== " + new java.util.Date() + " ===");
                    pw.println(msg);
                    if (e != null) e.printStackTrace(pw);
                    pw.flush(); osw.flush(); fos.getFD().sync();
                }
            }
        } catch (Throwable ignore) {}
        try {
            String payload = "=== " + new java.util.Date() + " ===\n" +
                    msg + "\n" +
                    (e != null ? Log.getStackTraceString(e) : "") + "\n";
            writeToDownloads("minicast_crash.txt", payload);
        } catch (Throwable ignore) {}
        try { SystemClock.sleep(120); } catch (Throwable ignored) {}
    }
    private void dbg(String msg) { dbg(msg, null); }

    /* =====================  ACTIVITY  ===================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            dbg("Uncaught in thread: " + t.getName(), e);
            try { Toast.makeText(this, "Hata: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); }
            catch (Throwable ignored) {}
        });
        dbg("onCreate: START (handler installed)");

        // Android 12+ splash listener: güvenli (null-check)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getSplashScreen().setOnExitAnimationListener(sv -> {
                    try {
                        View icon = sv.getIconView();
                        if (icon != null) icon.setAlpha(1f);
                    } catch (Throwable e) { dbg("splash icon fail", e); }
                    finally { try { sv.remove(); } catch (Throwable ignored) {} }
                });
            } catch (Throwable e) { dbg("splash listener err", e); }
        }

        setContentView(R.layout.activity_main);

        // WebView
        web = findViewById(R.id.web);
        if (web != null) {
            setupWebView(web);
            web.loadUrl("https://www.google.com");
        } else {
            dbg("WebView (R.id.web) is null!");
        }

        // Cast Context güvenli init
        try {
            CastContext.getSharedInstance(this);
            dbg("CastContext initialized");
        } catch (Throwable e) {
            dbg("CastContext init failed", e);
        }

        ensureFineLocation();

        // Buton → Cast chooser’ı programatik aç
        btnCast = findViewById(R.id.btnCast);
        if (btnCast != null) {
            btnCast.setOnClickListener(v -> {
                dbg("btnCast clicked");
                showCastChooser();
            });
        }
        dbg("onCreate: END");
    }

    // Resmi MediaRoute chooser dialog’u aç
    private void showCastChooser() {
        try {
            DialogFragment f = MediaRouteDialogFactory.getDefault().onCreateChooserDialogFragment();
            f.show(getSupportFragmentManager(), "mr_chooser_dialog");
            dbg("cast chooser shown");
        } catch (Throwable e) {
            dbg("cast chooser failed", e);
            try { Toast.makeText(this, "Cast seçici açılamadı.", Toast.LENGTH_SHORT).show(); }
            catch (Throwable ignored) {}
        }
    }

    // Menüde resmi Cast butonu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        try {
            getMenuInflater().inflate(R.menu.cast_menu, menu);
            CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item);
            dbg("menu cast button ready");
        } catch (Throwable e) {
            dbg("menu setup failed", e);
        }
        return true;
    }

    private void ensureFineLocation() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_FINE_LOCATION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(code, p, g);
        if (code == REQ_FINE_LOCATION) dbg("ACCESS_FINE_LOCATION granted? " + (g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED));
    }

    /* =====================  WEBVIEW AYARLARI  ===================== */

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);

        // "Yeni pencerede aç" (target=_blank / window.open) davranışını bizim yöneteceğiz
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);

        // Her şeyi içeride aç: http/https bizde; özel şemalar kontrollü dışarı
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri uri = (req != null) ? req.getUrl() : null;
                return handleUrlRouting(view, uri != null ? uri.toString() : null);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlRouting(view, url);
            }
        });

        // target="_blank" / window.open isteklerini aynı WebView’e yönlendir
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                try {
                    // Yeni webview yerine URL’i mevcut web’e yolla
                    WebView.HitTestResult result = view.getHitTestResult();
                    String data = (result != null) ? result.getExtra() : null;
                    if (data != null) {
                        view.loadUrl(data);
                    } else {
                        // Bazı sayfalarda URL resultMsg içinden gelir
                        WebView newWeb = new WebView(view.getContext());
                        newWeb.setWebViewClient(new WebViewClient(){
                            @Override
                            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                                view.loadUrl(url);
                                try { v.destroy(); } catch (Throwable ignored) {}
                            }
                        });
                        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                        transport.setWebView(newWeb);
                        resultMsg.sendToTarget();
                    }
                    return true;
                } catch (Throwable e) {
                    dbg("onCreateWindow fail", e);
                    return false;
                }
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }
        wv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
    }

    // URL yönlendirme kuralı: http/https içeride; intent/mail/tel dışa
    private boolean handleUrlRouting(WebView view, String url) {
        try {
            if (url == null) return false;
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();

            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                view.loadUrl(url);    // içeride aç
                return true;
            }
            if ("intent".equalsIgnoreCase(scheme)) {
                try {
                    Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    if (i != null) {
                        if (i.getPackage() != null) startActivity(i);
                        else {
                            String fallback = i.getStringExtra("browser_fallback_url");
                            if (fallback != null) view.loadUrl(fallback);
                        }
                    }
                } catch (Exception e) { dbg("intent:// fail", e); }
                return true;
            }
            if ("mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme) || "geo".equalsIgnoreCase(scheme)) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (Exception e) { dbg("external scheme fail", e); }
                return true;
            }
        } catch (Throwable e) {
            dbg("handleUrlRouting error", e);
        }
        return false; // default davranış
    }

    @Override protected void onStop() { super.onStop(); dbg("onStop"); }
    @Override protected void onDestroy() { dbg("onDestroy"); super.onDestroy(); }
        }
