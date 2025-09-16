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
import android.view.MenuItem;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.gms.cast.framework.CastContext;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** MiniCast – Tek FAB (sağ-alt): DLNA push (HLS en yüksek kalite), yoksa Smart View; uzun bas: Cast chooser.
 *  WebView dışa kaçmıyor; m3u8/mp4/mpd ağ istekleri yakalanıyor (iframe’ler dahil). */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MiniCastCrash";
    private static final int REQ_FINE_LOCATION = 42;

    private WebView web;
    private EditText urlBox;
    private FloatingActionButton fab;

    private WifiManager.MulticastLock mlock;

    // DLNA hedefi
    private DlnaDevice selectedDevice;

    // Ağdan yakalanan medya URL adayları
    private final Set<String> mediaCandidates = new LinkedHashSet<>();
    private volatile String lastMediaUrl = null;

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
        try { SystemClock.sleep(50); } catch (Throwable ignored) {}
    }
    private void dbg(String msg) { dbg(msg, null); }

    /* ===================== Activity ===================== */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        MaterialToolbar tb = findViewById(R.id.toolbar);

        web = findViewById(R.id.web);
        urlBox = findViewById(R.id.urlBox);
        MaterialButton btnGo = findViewById(R.id.btnGo);
        fab = findViewById(R.id.fabAction);

        setupWebView(web);

        // İlk URL
        if (urlBox.getText().length() == 0)
            urlBox.setText("https://www.google.com");
        web.loadUrl(urlBox.getText().toString());

        btnGo.setOnClickListener(v -> {
            String u = urlBox.getText().toString().trim();
            if (!u.startsWith("http")) u = "https://" + u;
            mediaCandidates.clear(); lastMediaUrl = null; // yeni sayfa -> temizle
            web.loadUrl(u);
        });

        // FAB: tek tık => DLNA akışı, uzun bas => Cast chooser
        fab.setOnClickListener(v -> startDlnaFlowAndPush());
        fab.setOnLongClickListener(v -> { openCastChooser(); return true; });

        // Cast context (güvenli)
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

        // Web’e JS arayüzü (video varsa yakalayalım)
        web.addJavascriptInterface(new JsBridge(), "MiniCastBridge");
    }

    @Override protected void onDestroy() {
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /* ===================== Cast chooser (uzun bas) ===================== */
    private void openCastChooser() {
        try {
            DialogFragment f = MediaRouteDialogFactory.getDefault().onCreateChooserDialogFragment();
            f.show(getSupportFragmentManager(), "mr_chooser_dialog");
            dbg("cast chooser shown");
        } catch (Throwable e) { dbg("cast chooser failed", e); }
    }

    /* ===================== DLNA/SmartView akışı ===================== */
    private void startDlnaFlowAndPush() {
        dbg("DLNA flow: scan");
        new Thread(() -> {
            try {
                List<DlnaDevice> devices = DlnaScanner.scan(3200);
                runOnUiThread(() -> {
                    if (devices.isEmpty()) {
                        dbg("DLNA none -> Smart View settings");
                        openSmartViewSettings(); // kullanıcı ayarı açılır, geri ile uygulamaya döner
                    } else if (devices.size() == 1) {
                        selectedDevice = devices.get(0);
                        Toast.makeText(this, "DLNA hedef: " + nameOf(selectedDevice), Toast.LENGTH_SHORT).show();
                        tryAutoDetectAndPush();
                    } else {
                        showDlnaPicker(devices);
                    }
                });
            } catch (Throwable e) {
                dbg("DLNA scan error", e);
                runOnUiThread(this::openSmartViewSettings);
            }
        }).start();
    }

    private void showDlnaPicker(List<DlnaDevice> devices) {
        String[] items = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            DlnaDevice d = devices.get(i);
            items[i] = (d.friendlyName != null ? d.friendlyName : "DLNA Aygıtı")
                    + "\n" + (d.server != null ? d.server : d.usn);
        }
        new AlertDialog.Builder(this)
                .setTitle("DLNA Aygıtı Seç")
                .setItems(items, (d, which) -> {
                    selectedDevice = devices.get(which);
                    Toast.makeText(this, "Seçildi: " + nameOf(selectedDevice), Toast.LENGTH_SHORT).show();
                    tryAutoDetectAndPush();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private String nameOf(DlnaDevice d) {
        return d == null ? "?" : (d.friendlyName != null ? d.friendlyName :
                (d.server != null ? d.server : d.usn));
    }

    /* === Medya URL’sini bul ve push et === */
    private void tryAutoDetectAndPush() {
        // 1) Ağdan yakalanan son medya URLsini dene
        String candidate = lastMediaUrl;
        if (candidate != null) {
            handleFoundMedia(candidate);
            return;
        }
        // 2) Yoksa JS ile <video> tara (iframe’de değilse)
        String js =
                "(function(){\n" +
                "  function bestSrc(v){ if(!v) return null; if(v.currentSrc) return v.currentSrc; if(v.src) return v.src; var ss=v.querySelectorAll('source'); for(var i=0;i<ss.length;i++){ if(ss[i].src) return ss[i].src; } return null; }\n" +
                "  var vids=document.querySelectorAll('video'); if(vids.length===0){ window.MiniCastBridge.onNoVideo(); return; }\n" +
                "  var v=vids[0]; var u=bestSrc(v); if(u){ window.MiniCastBridge.onVideoUrl(u); }\n" +
                "  v.addEventListener('play', function(){ var u2=bestSrc(v); if(u2) window.MiniCastBridge.onVideoUrl(u2); }, {once:true});\n" +
                "})();";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
        Toast.makeText(this, "Videoyu arıyorum… (ağ istekleri dinleniyor)", Toast.LENGTH_SHORT).show();
    }

    /** JS → Android köprüsü */
    private class JsBridge {
        @JavascriptInterface public void onNoVideo() {
            dbg("JS: no <video> on page");
        }
        @JavascriptInterface public void onVideoUrl(String url) {
            dbg("JS video url: " + url);
            lastMediaUrl = url;
            handleFoundMedia(url);
        }
    }

    // URL analiz: m3u8 ise en yüksek bitrate’i seç, değilse doğrudan push
    private void handleFoundMedia(String url) {
        new Thread(() -> {
            try {
                String playUrl = url;
                String lower = url.toLowerCase(Locale.ROOT);
                if (lower.contains(".m3u8")) {
                    String master = httpGetString(url, 4500);
                    if (master != null) {
                        String best = pickBestFromHlsMaster(master, url);
                        if (best != null) playUrl = best;
                    }
                }
                String mime = guessMime(playUrl);
                dbg("DLNA play: " + playUrl + " (" + mime + ")");
                if (selectedDevice != null) {
                    DlnaControl ctl = DlnaControl.fromDevice(selectedDevice);
                    if (ctl != null) {
                        boolean ok = ctl.playUrl(playUrl, mime);
                        runOnUiThread(() ->
                                Toast.makeText(this, ok ? "TV’de oynatılıyor: "+nameOf(selectedDevice)
                                                        : "DLNA oynatma başarısız.", Toast.LENGTH_LONG).show());
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, "DLNA denetim URL’leri bulunamadı.", Toast.LENGTH_LONG).show());
                    }
                }
            } catch (Throwable e) {
                dbg("handleFoundMedia error", e);
                runOnUiThread(() -> Toast.makeText(this, "Oynatma hatası.", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String guessMime(String u) {
        String l = u.toLowerCase(Locale.ROOT);
        if (l.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if (l.contains(".mpd"))  return "application/dash+xml";
        if (l.contains(".mp4"))  return "video/mp4";
        if (l.contains(".webm")) return "video/webm";
        return "video/*";
    }

    private String httpGetString(String url, int timeoutMs) {
        BufferedReader br = null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.addRequestProperty("User-Agent","Mozilla/5.0");
            c.connect();
            br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line; while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        } finally { try { if (br != null) br.close(); } catch (Throwable ignore) {} }
    }

    // HLS master’dan en yüksek BANDWIDTH varyantını seç
    private String pickBestFromHlsMaster(String master, String baseUrl) {
        long bestBw = -1;
        String bestUri = null;
        String[] lines = master.split("\n");
        for (int i=0; i<lines.length-1; i++) {
            String l = lines[i].trim();
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                long bw = parseBandwidth(l);
                String next = lines[i+1].trim();
                if (!next.startsWith("#") && next.length()>0) {
                    if (bw > bestBw) { bestBw = bw; bestUri = resolveHlsUri(baseUrl, next); }
                }
            }
        }
        return bestUri;
    }
    private long parseBandwidth(String inf) {
        try {
            int i = inf.toUpperCase(Locale.ROOT).indexOf("BANDWIDTH=");
            if (i>=0) {
                int e = inf.indexOf(",", i);
                String v = (e>i ? inf.substring(i+10, e) : inf.substring(i+10)).trim();
                return Long.parseLong(v);
            }
        } catch (Throwable ignored) {}
        return -1;
    }
    private String resolveHlsUri(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Throwable e) { return rel; }
    }

    /* ===================== WebView ===================== */
    @SuppressLint({"SetJavaScriptEnabled"})
    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);

        // Dışarı atma yok; iframe’ler dahil tüm frame’ler bu WebView’de
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                // Sayfa yüklenince, varsa otomatik yakalama:
                evaluateVideoProbe();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = (r != null) ? r.getUrl() : null;
                if (u != null) v.loadUrl(u.toString());
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url != null) v.loadUrl(url);
                return true;
            }

            // *** ÖNEMLİ: Ağ isteklerinden medya URL’lerini yakala (iframe’ler dahil) ***
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    Uri u = request.getUrl();
                    if (u != null) {
                        String su = u.toString();
                        String sul = su.toLowerCase(Locale.ROOT);
                        if (sul.contains(".m3u8") || sul.contains(".mp4") || sul.contains(".mpd")) {
                            synchronized (mediaCandidates) { mediaCandidates.add(su); }
                            lastMediaUrl = su;
                            dbg("INTERCEPT media: " + su);
                        }
                    }
                } catch (Throwable ignored) {}
                return super.shouldInterceptRequest(view, request);
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                try {
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
                    return true;
                } catch (Throwable e) { dbg("onCreateWindow fail", e); return false; }
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.setSafeBrowsingEnabled(true);
        wv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
    }

    private void evaluateVideoProbe() {
        String js =
                "(function(){\n" +
                "  function bestSrc(v){ if(!v) return null; if(v.currentSrc) return v.currentSrc; if(v.src) return v.src; var ss=v.querySelectorAll('source'); for(var i=0;i<ss.length;i++){ if(ss[i].src) return ss[i].src; } return null; }\n" +
                "  var vids=document.querySelectorAll('video'); if(vids.length===0){ return; }\n" +
                "  var v=vids[0]; var u=bestSrc(v); if(u){ window.MiniCastBridge.onVideoUrl(u); }\n" +
                "})();";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    /* ===================== DLNA/UPnP: SSDP tarayıcı + kontrol ===================== */

    static class DlnaDevice { String usn, st, server, location, friendlyName; }

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
                String k = l.substring(0, i).trim().toUpperCase(Locale.ROOT);
                String v = l.substring(i + 1).trim();
                if ("USN".equals(k)) d.usn = v;
                else if ("ST".equals(k)) d.st = v;
                else if ("SERVER".equals(k)) d.server = v;
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
            } finally { try { if (br != null) br.close(); } catch (Throwable ignore) {} }
            return null;
        }
    }

    /* === DLNA kontrol katmanı: AVTransport (Play) === */
    static class DlnaControl {
        String avTransportCtrl; String renderingCtrl;

        static DlnaControl fromDevice(DlnaDevice d) {
            try {
                String desc = httpGet(d.location, 4000);
                if (desc == null) return null;
                String base = baseUrlOf(d.location);
                String avt = findServiceCtrl(desc, "AVTransport");
                String rct = findServiceCtrl(desc, "RenderingControl");
                DlnaControl ctl = new DlnaControl();
                ctl.avTransportCtrl = join(base, avt);
                ctl.renderingCtrl = join(base, rct);
                return ctl;
            } catch (Throwable ignored) { return null; }
        }
        boolean playUrl(String mediaUrl, String mime) {
            try {
                String meta = didlLiteFor(mediaUrl, mime);
                String setBody =
                        "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                        "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>" + xmlEsc(mediaUrl) + "</CurrentURI>" +
                        "<CurrentURIMetaData>" + xmlEsc(meta) + "</CurrentURIMetaData>" +
                        "</u:SetAVTransportURI>";
                if (!soap(avTransportCtrl, "SetAVTransportURI", setBody)) return false;
                String playBody =
                        "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                        "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>";
                return soap(avTransportCtrl, "Play", playBody);
            } catch (Throwable ignored) { return false; }
        }

        /* ===== helpers ===== */
        private static String httpGet(String url, int timeout) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
            c.setInstanceFollowRedirects(true);
            c.addRequestProperty("User-Agent","Mozilla/5.0");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (var is = c.getInputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
        private static String baseUrlOf(String url) throws Exception {
            URL u = new URL(url);
            int p = (u.getPort() >= 0) ? u.getPort() : u.getDefaultPort();
            return u.getProtocol() + "://" + u.getHost() + (p>0? (":" + p): "");
        }
        private static String findServiceCtrl(String descXml, String serviceName) {
            String upnp = "urn:schemas-upnp-org:service:" + serviceName + ":1";
            int i = descXml.indexOf(upnp);
            if (i < 0) return null;
            int c1 = descXml.indexOf("<controlURL>", i);
            if (c1 < 0) return null;
            int c2 = descXml.indexOf("</controlURL>", c1);
            if (c2 < 0) return null;
            return descXml.substring(c1 + 11, c2).trim();
        }
        private static String join(String base, String path) {
            if (path == null) return null;
            try { return new URL(new URL(base + "/"), path).toString(); } catch (Throwable e) { return path; }
        }
        private static boolean soap(String ctrlUrl, String action, String inner) throws Exception {
            if (ctrlUrl == null) return false;
            String urn = "urn:schemas-upnp-org:service:AVTransport:1";
            byte[] body = (
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body>" + inner + "</s:Body></s:Envelope>"
            ).getBytes(StandardCharsets.UTF_8);

            HttpURLConnection c = (HttpURLConnection) new URL(ctrlUrl).openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(4000);
            c.setDoOutput(true); c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction", "\"" + urn + "#" + action + "\"");
            try (var os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            return (code >= 200 && code < 300);
        }
        private static String didlLiteFor(String url, String mime) {
            String prot = "http-get:*:" + (mime != null? mime : "video/*") + ":*";
            return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                    "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                    "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
                    "<item id=\"0\" parentID=\"0\" restricted=\"1\">" +
                    "<dc:title>MiniCast</dc:title>" +
                    "<res protocolInfo=\"" + prot + "\">" + xmlEsc(url) + "</res>" +
                    "<upnp:class>object.item.videoItem</upnp:class>" +
                    "</item></DIDL-Lite>";
        }
        private static String xmlEsc(String s) {
            return s.replace("&","&amp;").replace("<","&lt;")
                    .replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
        }
    }

    /* ===================== İzin/SmartView ===================== */
    private void ensureFineLocation() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_FINE_LOCATION);
            }
        }
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
    }
