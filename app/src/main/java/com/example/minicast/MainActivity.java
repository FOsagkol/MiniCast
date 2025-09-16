package com.example.minicast;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.mediarouter.app.MediaRouteDialogFactory;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MainActivity — Toolbar menülü (Cast + Smart View/DLNA), WebView içi yönlendirme, çift logger */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MiniCastCrash";
    private static final int REQ_FINE_LOCATION = 42;

    private WebView web;
    private EditText urlBox;

    private WifiManager.MulticastLock mlock;

    /* ===== Logger: Downloads + app-özel + logcat ===== */

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
                    os.write(text.getBytes(StandardCharsets.UTF_8));
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
                     OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
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
        try { SystemClock.sleep(80); } catch (Throwable ignored) {}
    }
    private void dbg(String msg) { dbg(msg, null); }

    /* ===================== Activity ===================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // (Android 12+) Splash exit listener (null-safe)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getSplashScreen().setOnExitAnimationListener(sv -> {
                    try { View icon = sv.getIconView(); if (icon != null) icon.setAlpha(1f); }
                    catch (Throwable e) { dbg("splash icon fail", e); }
                    finally { try { sv.remove(); } catch (Throwable ignored) {} }
                });
            } catch (Throwable e) { dbg("splash listener err", e); }
        }

        setContentView(R.layout.activity_main);

        // Toolbar (menu: Cast + Smart View)
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setOnMenuItemClickListener(this::onToolbarItem);
        // Cast butonunu sisteme bağla (MediaRouteActionProvider)
        CastButtonFactory.setUpMediaRouteButton(this, tb.getMenu(), R.id.action_cast);

        // WebView + adres çubuğu
        web = findViewById(R.id.web);
        urlBox = findViewById(R.id.urlBox);
        MaterialButton btnGo = findViewById(R.id.btnGo);
        setupWebView(web);
        btnGo.setOnClickListener(v -> {
            String u = urlBox.getText().toString().trim();
            if (!u.startsWith("http")) u = "https://" + u;
            web.loadUrl(u);
        });
        if (urlBox.getText().length() == 0) urlBox.setText("https://www.google.com");
        web.loadUrl(urlBox.getText().toString());

        // Cast Context (güvenli)
        try { CastContext.getSharedInstance(this); } catch (Throwable e) { dbg("CastContext init failed", e); }

        ensureFineLocation();

        // DLNA için MulticastLock
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("minicast-dlna");
                mlock.setReferenceCounted(true);
                mlock.acquire();
            }
        } catch (Throwable e) { dbg("multicast lock failed", e); }
    }

    @Override protected void onDestroy() {
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /* ===================== Menü ===================== */

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    private boolean onToolbarItem(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_cast) {
            // MediaRouteActionProvider kendi chooser'ını açar; ekstra iş yok.
            return true;
        } else if (id == R.id.action_smartview) {
            scanDlnaThenMaybeSmartView();
            return true;
        }
        return false;
    }

    /* ===  DLNA/UPnP taraması; yoksa Smart View ayarı === */

    private void scanDlnaThenMaybeSmartView() {
        dbg("DLNA scan: start");
        new Thread(() -> {
            try {
                List<DlnaDevice> devices = DlnaScanner.scan(3500);
                runOnUiThread(() -> {
                    if (devices.isEmpty()) {
                        dbg("DLNA scan: none -> open Smart View");
                        openSmartViewSettings();
                    } else {
                        showDlnaDialog(devices);
                    }
                });
            } catch (Throwable e) {
                dbg("DLNA scan error", e);
                runOnUiThread(this::openSmartViewSettings);
            }
        }).start();
    }

    private void showDlnaDialog(List<DlnaDevice> devices) {
        String[] items = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            DlnaDevice d = devices.get(i);
            items[i] = (d.friendlyName != null ? d.friendlyName : "DLNA Aygıtı")
                    + "\n" + (d.server != null ? d.server : d.usn);
        }
        new AlertDialog.Builder(this)
                .setTitle("DLNA Aygıtları")
                .setItems(items, (d, which) -> {
                    DlnaDevice sel = devices.get(which);
                    Toast.makeText(this, "Seçildi: " + (sel.friendlyName != null ? sel.friendlyName : sel.usn),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Kapat", null)
                .show();
    }

    private void openSmartViewSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_CAST_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            dbg("opened Smart View / Cast settings");
        } catch (Throwable e1) {
            try {
                Intent i2 = new Intent("android.settings.WIFI_DISPLAY_SETTINGS");
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i2);
                dbg("opened WIFI_DISPLAY_SETTINGS");
            } catch (Throwable e2) {
                dbg("openSmartViewSettings failed", e2);
                Toast.makeText(this, "Cihaz yansıtma ayarı açılamadı.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /* ===  İzin  === */

    private void ensureFineLocation() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_FINE_LOCATION);
            }
        }
    }
    @Override public void onRequestPermissionsResult(int c, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(c, p, g);
    }

    /* ===  WebView  === */

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);

        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = (r != null) ? r.getUrl() : null;
                return handleUrlRouting(v, u != null ? u.toString() : null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleUrlRouting(v, url);
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                try {
                    WebView.HitTestResult result = view.getHitTestResult();
                    String data = (result != null) ? result.getExtra() : null;
                    if (data != null) view.loadUrl(data);
                    else {
                        WebView newWeb = new WebView(view.getContext());
                        newWeb.setWebViewClient(new WebViewClient(){
                            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                                view.loadUrl(url);
                                try { v.destroy(); } catch (Throwable ignored) {}
                            }
                        });
                        WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                        t.setWebView(newWeb);
                        resultMsg.sendToTarget();
                    }
                    return true;
                } catch (Throwable e) { dbg("onCreateWindow fail", e); return false; }
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.setSafeBrowsingEnabled(true);
        wv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
    }

    private boolean handleUrlRouting(WebView view, String url) {
        try {
            if (url == null) return false;
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                view.loadUrl(url); return true;
            }
            if ("intent".equalsIgnoreCase(scheme)) {
                try {
                    Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    if (i != null) {
                        if (i.getPackage() != null) startActivity(i);
                        else {
                            String fb = i.getStringExtra("browser_fallback_url");
                            if (fb != null) view.loadUrl(fb);
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
        } catch (Throwable e) { dbg("handleUrlRouting error", e); }
        return false;
    }

    /* ===== Basit DLNA/UPnP SSDP tarayıcı ===== */

    static class DlnaDevice {
        String usn, st, server, location, friendlyName;
    }

    static class DlnaScanner {
        private static final String SSDP_ADDR = "239.255.255.250";
        private static final int SSDP_PORT = 1900;

        static List<DlnaDevice> scan(int timeoutMs) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            Map<String, DlnaDevice> map = new LinkedHashMap<>();

            String msearch =
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";

            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setReuseAddress(true);
                sock.setSoTimeout(800);

                byte[] data = msearch.getBytes(StandardCharsets.US_ASCII);
                DatagramPacket dp = new DatagramPacket(
                        data, data.length, InetAddress.getByName(SSDP_ADDR), SSDP_PORT);

                sock.send(dp); Thread.sleep(120);
                sock.send(dp); Thread.sleep(120);
                sock.send(dp);

                byte[] buf = new byte[4096];
                while (SystemClock.elapsedRealtime() < deadline) {
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(resp);
                        String txt = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                        DlnaDevice d = parseResponse(txt);
                        if (d != null && d.usn != null) {
                            if (d.location != null && d.friendlyName == null) {
                                d.friendlyName = fetchFriendlyName(d.location, 500);
                            }
                            map.put(d.usn, d);
                        }
                    } catch (SocketTimeoutException ignore) {}
                }
            }
            return new ArrayList<>(map.values());
        }

        private static DlnaDevice parseResponse(String txt) {
            String[] lines = txt.split("\r?\n");
            if (lines.length == 0 || !lines[0].startsWith("HTTP/1.1 200")) return null;
            DlnaDevice d = new DlnaDevice();
            for (String l : lines) {
                int i = l.indexOf(':'); if (i <= 0) continue;
                String k = l.substring(0, i).trim().toUpperCase();
                String v = l.substring(i + 1).trim();
                if ("USN".equals(k)) d.usn = v;
                else if ("ST".equals(k)) d.st = v;
                else if ("SERVER".equals(k)) d.server = v;   // <-- DÜZELTİLDİ
                else if ("LOCATION".equals(k)) d.location = v;
            }
            return d;
        }

        private static String fetchFriendlyName(String locationUrl, int timeoutMs) {
            BufferedReader br = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(locationUrl).openConnection();
                c.setConnectTimeout(timeoutMs);
                c.setReadTimeout(timeoutMs);
                c.setInstanceFollowRedirects(true);
                c.connect();
                br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                    int s = sb.indexOf("<friendlyName>");
                    if (s >= 0) {
                        int e = sb.indexOf("</friendlyName>", s);
                        if (e > s) return sb.substring(s + 14, e).trim();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Throwable ignore) {}
            }
            return null;
        }
    }
                                                  }
