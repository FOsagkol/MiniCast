package com.example.minicast;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.mediarouter.app.MediaRouteDialogFactory;

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
import java.net.InetSocketAddress;
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

/** MiniCast – DLNA push (gelişmiş tarama + test klip), Smart View, Chromecast */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MiniCastCrash";
    private static final int REQ_FINE_LOCATION = 42;

    // Test klibi (ilk bağlantıyı doğrulamak için)
    private static final String TEST_URL  = "https://filesamples.com/samples/video/mp4/sample_960x400_ocean_with_audio.mp4";
    private static final String TEST_MIME = "video/mp4";

    // Süreler
    private static final int SCAN_WINDOW_MS   = 15_000; // 15 sn tarama
    private static final int DESC_TIMEOUT_MS  = 2_000;  // cihaz açıklaması
    private static final int SOAP_TIMEOUT_MS  = 4_000;  // AVTransport SOAP

    private WebView web;
    private EditText urlBox;
    private FloatingActionButton fab;

    private DlnaDevice selectedDevice;

    private final Set<String> mediaCandidates = new LinkedHashSet<>();
    private volatile String lastMediaUrl = null;

    private WifiManager.MulticastLock mlock;
    private volatile boolean scanCancelled = false;
    private boolean smartViewLaunched = false;

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
        try { SystemClock.sleep(10); } catch (Throwable ignored) {}
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

        if (urlBox.getText().length() == 0)
            urlBox.setText("https://www.google.com");
        web.loadUrl(urlBox.getText().toString());

        btnGo.setOnClickListener(v -> {
            String u = urlBox.getText().toString().trim();
            if (!u.startsWith("http")) u = "https://" + u;
            mediaCandidates.clear(); lastMediaUrl = null;
            web.loadUrl(u);
        });

        // FAB: kısa bas = yol seçici; uzun bas = direkt Chromecast chooser
        fab.setOnClickListener(v -> showRoutePicker());
        fab.setOnLongClickListener(v -> { openCastChooser(); return true; });

        try { CastContext.getSharedInstance(this); } catch (Throwable e) { dbg("CastContext init failed", e); }

        ensureFineLocation();
        prepareMulticast();
        bindToWifiNetwork(); // multicast/SSDP dönüşleri kaçmasın

        web.addJavascriptInterface(new JsBridge(), "MiniCastBridge");
    }

    @Override protected void onResume() {
        super.onResume();
        if (smartViewLaunched) {
            smartViewLaunched = false;
            // Smart View ayarından dönünce otomatik DLNA taraması + test push
            startDlnaTestFlowWithDialog();
        }
    }

    @Override protected void onDestroy() {
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /* ===================== Yol seçici ===================== */
    private void showRoutePicker() {
        final CharSequence[] items = new CharSequence[]{
                "DLNA ile gönder (test klip)",
                "Ekranı yansıt (Smart View)",
                "Chromecast"
        };
        new AlertDialog.Builder(this)
                .setTitle("Nasıl oynatılsın?")
                .setItems(items, (d, which) -> {
                    if (which == 0) startDlnaTestFlowWithDialog();
                    else if (which == 1) openSmartViewSettings();
                    else openCastChooser();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    /* ===================== Chromecast chooser (uzun bas) ===================== */
    private void openCastChooser() {
        try {
            DialogFragment f = MediaRouteDialogFactory.getDefault().onCreateChooserDialogFragment();
            f.show(getSupportFragmentManager(), "mr_chooser_dialog");
            dbg("cast chooser shown");
        } catch (Throwable e) { dbg("cast chooser failed", e); }
    }

    /* ===================== DLNA: Tarama Diyaloğu + Test Push ===================== */

    private void startDlnaTestFlowWithDialog() {
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);
        container.addView(pb);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Cihazlar aranıyor…")
                .setMessage("DLNA MediaRenderer cihazları taranıyor (≈15 sn)…")
                .setView(container)
                .setNegativeButton("İptal", (d, w) -> { scanCancelled = true; })
                .create();
        progressDialog.setCancelable(false);
        progressDialog.show();

        scanCancelled = false;
        new Thread(() -> {
            List<DlnaDevice> list = new ArrayList<>();
            try {
                acquireMl(true);
                List<DlnaDevice> scanned = DlnaScanner.scanExtended(SCAN_WINDOW_MS,
                        (st, sent) -> dbg("SSDP sent: " + st + " #" + sent),
                        (from, usn, loc) -> dbg("SSDP resp from " + from + " usn=" + usn + " loc=" + loc),
                        () -> scanCancelled);
                if (scanned != null) list.addAll(scanned);
            } catch (Throwable e) {
                dbg("DLNA scan error", e);
            } finally {
                acquireMl(false);
            }

            List<DlnaDevice> good = new ArrayList<>();
            for (DlnaDevice d : list) {
                DlnaControl.fillServiceInfo(d);
                if (d.avTransportCtrl != null && d.avTransportUrn != null) {
                    good.add(d);
                }
            }

            runOnUiThread(() -> {
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (scanCancelled) { Toast.makeText(this, "Tarama iptal edildi.", Toast.LENGTH_SHORT).show(); return; }
                if (good.isEmpty()) { showNoDeviceFoundSheet(list); }
                else if (good.size() == 1) { selectedDevice = good.get(0); Toast.makeText(this, "DLNA hedef: " + selectedDevice.displayName(), Toast.LENGTH_SHORT).show(); playTestClipCompat(selectedDevice); }
                else { showDlnaPickerForTest(good); }
            });
        }).start();
    }

    private void showNoDeviceFoundSheet(List<DlnaDevice> rawList) {
        String msg = "Uygun DLNA MediaRenderer bulunamadı.\n\n" +
                "İpuçları:\n• TV ve telefon aynı Wi-Fi ağında olsun\n• Modemde AP Isolation kapalı olsun\n• Philips TV’de DLNA/SimplyShare açık olsun";
        new AlertDialog.Builder(this)
                .setTitle("Cihaz bulunamadı")
                .setMessage(msg)
                .setPositiveButton("Yeniden tara", (d,w)-> startDlnaTestFlowWithDialog())
                .setNeutralButton("Smart View", (d,w)-> openSmartViewSettings())
                .setNegativeButton("IP ile ekle", (d,w)-> promptManualIp())
                .show();

        if (!rawList.isEmpty()) {
            StringBuilder sb = new StringBuilder("RAW DEVICES ("+rawList.size()+"):\n");
            for (DlnaDevice x: rawList) sb.append(x.displayName()).append(" | ").append(x.location).append("\n");
            dbg(sb.toString());
        }
    }

    private void promptManualIp() {
        final EditText input = new EditText(this);
        input.setHint("Örn: 192.168.1.35");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("Cihaz IP’si")
                .setMessage("TV’nin IP’sini girin. Önce UNICAST M-SEARCH, sonra yaygın UPnP description yolları denenecek.")
                .setView(input)
                .setPositiveButton("Bağlan", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (ip.isEmpty()) { Toast.makeText(this, "IP gerekli.", Toast.LENGTH_SHORT).show(); return; }
                    tryManualDiscovery(ip);
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void tryManualDiscovery(String ip) {
        Toast.makeText(this, "IP üzerinden deneme başlatıldı…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            // 1) Unicast SSDP M-SEARCH (1900/udp)
            DlnaDevice dev = DlnaScanner.tryUnicastSsdp(ip, 2_000);
            if (dev == null) {
                // 2) HTTP description taraması
                dev = DlnaScanner.tryCommonDescriptionOnIp(ip);
            }
            if (dev != null) DlnaControl.fillServiceInfo(dev);

            DlnaDevice finalDev = dev;
            runOnUiThread(() -> {
                if (finalDev != null && finalDev.avTransportCtrl != null) {
                    selectedDevice = finalDev;
                    Toast.makeText(this, "Bulundu: " + finalDev.displayName(), Toast.LENGTH_SHORT).show();
                    playTestClipCompat(finalDev);
                } else {
                    Toast.makeText(this, "Olmadı: UPnP açıklaması/AVTransport yok veya yanıt gelmedi.", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void showDlnaPickerForTest(List<DlnaDevice> devices) {
        String[] items = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) items[i] = devices.get(i).displayName();
        new AlertDialog.Builder(this)
                .setTitle("DLNA Aygıtı Seç (önce test klip)")
                .setItems(items, (d, which) -> {
                    DlnaDevice sel = devices.get(which);
                    selectedDevice = sel;
                    Toast.makeText(this, "Seçildi: " + sel.displayName(), Toast.LENGTH_SHORT).show();
                    playTestClipCompat(sel);
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    /* === Uyumluluk denemeli test push + ayrıntılı rapor === */
    private void playTestClipCompat(DlnaDevice dev) {
        Toast.makeText(this, "TV’ye test videosu gönderiliyor…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                DlnaControl ctl = DlnaControl.fromDevice(dev);
                if (ctl == null) { runOnUiThread(() -> Toast.makeText(this, "DLNA denetim URL’leri yok.", Toast.LENGTH_LONG).show()); return; }
                DlnaControl.PushReport rep = ctl.playUrlCompat(TEST_URL, TEST_MIME, SOAP_TIMEOUT_MS);
                String human = rep.humanReadable();
                dbg("PUSH REPORT:\n" + human);
                runOnUiThread(() -> Toast.makeText(this, rep.success ? "Tamam! TV’de test video oynuyor." : ("Olmadı:\n" + human), Toast.LENGTH_LONG).show());
            } catch (Throwable e) {
                dbg("playTestClipCompat error", e);
                runOnUiThread(() -> Toast.makeText(this, "Push sırasında hata.", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /* ===================== WebView & Sayfa içi video yakalama ===================== */

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

        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { super.onPageFinished(v, url); evaluateVideoProbe(); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { Uri u = (r != null) ? r.getUrl() : null; if (u != null) v.loadUrl(u.toString()); return true; }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { if (url != null) v.loadUrl(url); return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    Uri u = request.getUrl();
                    if (u != null) {
                        String su = u.toString().toLowerCase(Locale.ROOT);
                        if (su.contains(".m3u8") || su.contains(".mp4") || su.contains(".mpd")) {
                            synchronized (mediaCandidates) { mediaCandidates.add(u.toString()); }
                            lastMediaUrl = u.toString();
                            dbg("INTERCEPT media: " + u);
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

    private class JsBridge {
        @JavascriptInterface public void onVideoUrl(String url) { dbg("JS video url: " + url); lastMediaUrl = url; }
        @JavascriptInterface public void onNoVideo() { dbg("JS: no <video> on page"); }
    }

    private void tryAutoDetectAndPush() {
        String candidate = lastMediaUrl;
        if (candidate == null) { Toast.makeText(this, "Önce videoyu başlatın ya da sayfada video bulun.", Toast.LENGTH_SHORT).show(); return; }
        new Thread(() -> {
            try {
                String playUrl = candidate;
                String mime = guessMime(playUrl);
                DlnaControl ctl = DlnaControl.fromDevice(selectedDevice);
                DlnaControl.PushReport rep = (ctl != null)
                        ? ctl.playUrlCompat(playUrl, mime, SOAP_TIMEOUT_MS)
                        : DlnaControl.PushReport.fail("NoControl", "DLNA control not available");
                dbg("AUTO PUSH REPORT:\n" + rep.humanReadable());
                runOnUiThread(() -> Toast.makeText(this,
                        rep.success ? "TV’de oynatılıyor." : ("Başarısız:\n" + rep.humanReadable()),
                        Toast.LENGTH_LONG).show());
            } catch (Throwable e) {
                dbg("tryAutoDetectAndPush error", e);
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

    /* ===================== DLNA/UPnP ===================== */

    static class DlnaDevice {
        String usn, st, server, location, friendlyName;
        String avTransportCtrl, avTransportUrn;
        String displayName() { return friendlyName!=null? friendlyName : (server!=null? server : (usn!=null? usn : "DLNA Aygıtı")); }
    }

    interface SendHook { void onSend(String st, int count); }
    interface RespHook { void onResp(String from, String usn, String location); }
    interface CancelFlag { boolean isCancelled(); }

    static class DlnaScanner {
        private static final String SSDP_ADDR = "239.255.255.250";
        private static final int SSDP_PORT = 1900;

        // Çoklu ST + 3 tekrar + 15 sn dinleme
        static List<DlnaDevice> scanExtended(int windowMs, SendHook sendHook, RespHook respHook, CancelFlag cancel) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + windowMs;
            Map<String, DlnaDevice> map = new LinkedHashMap<>();
            String[] sts = new String[]{
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "urn:schemas-upnp-org:service:AVTransport:2",
                    "urn:schemas-upnp-org:service:AVTransport:3",
                    "upnp:rootdevice",
                    "ssdp:all"
            };

            try (DatagramSocket sock = new DatagramSocket(null)) {
                sock.setReuseAddress(true);
                sock.bind(new InetSocketAddress(0));
                sock.setSoTimeout(900);

                for (String st : sts) {
                    String msearch = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: " + st + "\r\n\r\n";
                    byte[] data = msearch.getBytes(StandardCharsets.US_ASCII);
                    DatagramPacket dp = new DatagramPacket(data, data.length, InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
                    for (int i=1;i<=3;i++){
                        sock.send(dp); if (sendHook != null) sendHook.onSend(st, i);
                        Thread.sleep(120);
                    }
                }

                byte[] buf = new byte[4096];
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (cancel != null && cancel.isCancelled()) break;
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(resp);
                        String txt = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                        DlnaDevice d = parseResponse(txt);
                        if (d != null && d.usn != null) {
                            if (respHook != null) respHook.onResp(resp.getAddress().getHostAddress(), d.usn, d.location);
                            if (d.location != null && d.friendlyName == null) d.friendlyName = fetchFriendlyName(d.location, 900);
                            map.put(d.usn, d);
                        }
                    } catch (SocketTimeoutException ignore) {}
                }
            }
            return new ArrayList<>(map.values());
        }

        // IP’ye unicast SSDP M-SEARCH
        static DlnaDevice tryUnicastSsdp(String ip, int listenMs) {
            try (DatagramSocket sock = new DatagramSocket(null)) {
                sock.setReuseAddress(true);
                sock.bind(new InetSocketAddress(0));
                sock.setSoTimeout(listenMs);

                String[] sts = new String[]{
                        "urn:schemas-upnp-org:device:MediaRenderer:1",
                        "urn:schemas-upnp-org:service:AVTransport:1",
                        "urn:schemas-upnp-org:service:AVTransport:2",
                        "upnp:rootdevice"
                };
                for (String st: sts) {
                    String msearch = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: " + ip + ":" + SSDP_PORT + "\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: " + st + "\r\n\r\n";
                    byte[] data = msearch.getBytes(StandardCharsets.US_ASCII);
                    DatagramPacket dp = new DatagramPacket(data, data.length, InetAddress.getByName(ip), SSDP_PORT);
                    sock.send(dp);
                    Thread.sleep(60);
                }

                long end = SystemClock.elapsedRealtime() + listenMs;
                byte[] buf = new byte[4096];
                while (SystemClock.elapsedRealtime() < end) {
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(resp);
                        String txt = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                        DlnaDevice d = parseResponse(txt);
                        if (d != null && d.usn != null) {
                            if (d.location != null && d.friendlyName == null)
                                d.friendlyName = fetchFriendlyName(d.location, 900);
                            return d;
                        }
                    } catch (SocketTimeoutException ignore) {}
                }
            } catch (Throwable ignored) {}
            return null;
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
                c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
                c.setInstanceFollowRedirects(true); c.connect();
                br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                    int s = sb.indexOf("<friendlyName>");
                    if (s >= 0) { int e = sb.indexOf("</friendlyName>", s);
                        if (e > s) return sb.substring(s + 14, e).trim(); }
                }
            } catch (Throwable ignored) {
            } finally { try { if (br != null) br.close(); } catch (Throwable ignore) {} }
            return null;
        }

        static DlnaDevice tryCommonDescriptionOnIp(String ip) {
            String[] paths = new String[]{
                    "/description.xml", "/rootDesc.xml", "/DeviceDescription.xml",
                    "/RenderingControl/desc.xml", "/dmr.xml", "/devdesc.xml",
                    "/MediaRenderer/desc.xml", "/dmr/DeviceDescription.xml", "/upnp/desc.xml"
            };
            int[] ports = new int[]{ 80, 2869, 49152, 49153, 49154, 49155, 49156, 49157, 49158, 49159, 49160, 1400 };
            for (int p : ports) {
                for (String path : paths) {
                    String url = "http://" + ip + ":" + p + path;
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(1800); c.setReadTimeout(1800);
                        c.addRequestProperty("User-Agent","Mozilla/5.0");
                        int code = c.getResponseCode();
                        if (code >= 200 && code < 300) {
                            DlnaDevice d = new DlnaDevice();
                            d.location = url;
                            d.friendlyName = fetchFriendlyName(url, 1200);
                            d.usn = "manual:"+ip;
                            return d;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            return null;
        }
    }

    /* === DLNA kontrol katmanı: AVTransport uyumluluk denemeleri + ayrıntılı rapor === */
    static class DlnaControl {
        final DlnaDevice dev;
        DlnaControl(DlnaDevice d){ this.dev = d; }

        static DlnaControl fromDevice(DlnaDevice d) {
            fillServiceInfo(d);
            return (d != null && d.avTransportCtrl != null && d.avTransportUrn != null) ? new DlnaControl(d) : null;
        }
        static void fillServiceInfo(DlnaDevice d) {
            if (d == null || d.location == null || d.avTransportCtrl != null) return;
            try {
                String desc = httpGet(d.location, DESC_TIMEOUT_MS);
                if (desc == null) return;
                String base = baseUrlOf(d.location);
                ServiceInfo avt = findService(desc, "AVTransport");
                if (avt != null && avt.controlURL != null && avt.serviceType != null) {
                    d.avTransportCtrl = join(base, avt.controlURL);
                    d.avTransportUrn  = avt.serviceType;
                }
            } catch (Throwable ignored) {}
        }

        static class StepResult { String step; int http; String soapFault; boolean ok(){ return http>=200 && http<300 && (soapFault==null || soapFault.isEmpty()); } }
        static class PushReport {
            boolean success; List<StepResult> steps = new ArrayList<>();
            static PushReport fail(String step, String msg){ PushReport pr=new PushReport(); StepResult r=new StepResult(); r.step=step; r.http=-1; r.soapFault=msg; pr.steps.add(r); pr.success=false; return pr; }
            String humanReadable(){ StringBuilder sb=new StringBuilder(); sb.append(success? "SUCCESS" : "FAIL").append("\n"); for (StepResult r: steps){ sb.append("• ").append(r.step).append(" -> HTTP=").append(r.http); if (r.soapFault!=null && !r.soapFault.isEmpty()) sb.append(" SOAP=").append(r.soapFault); sb.append("\n"); } return sb.toString().trim(); }
        }

        PushReport playUrlCompat(String mediaUrl, String mime, int soapTimeoutMs) {
            PushReport rep = new PushReport();
            soapQuiet("Stop", "<u:Stop xmlns:u=\""+dev.avTransportUrn+"\"><InstanceID>0</InstanceID></u:Stop>", soapTimeoutMs);

            StepResult s1 = setUri(mediaUrl, null, 0, soapTimeoutMs); rep.steps.add(s1);
            if (s1.ok()) { sleep(600); StepResult p1 = play(0, soapTimeoutMs); rep.steps.add(p1); if (p1.ok()){ rep.success=true; return rep; } }

            StepResult s2 = setUri(mediaUrl, didlLiteFor(mediaUrl, mime), 0, soapTimeoutMs); rep.steps.add(s2);
            if (s2.ok()) { sleep(600); StepResult p2 = play(0, soapTimeoutMs); rep.steps.add(p2); if (p2.ok()){ rep.success=true; return rep; } }

            StepResult st3 = stop(0, soapTimeoutMs); rep.steps.add(st3);
            StepResult s3 = setUri(mediaUrl, didlLiteFor(mediaUrl, mime), 0, soapTimeoutMs); rep.steps.add(s3);
            if (s3.ok()) { sleep(600); StepResult p3 = play(0, soapTimeoutMs); rep.steps.add(p3); if (p3.ok()){ rep.success=true; return rep; } }

            StepResult s4 = setUri(mediaUrl, didlLiteFor(mediaUrl, mime), 1, soapTimeoutMs); rep.steps.add(s4);
            if (s4.ok()) { sleep(600); StepResult p4 = play(1, soapTimeoutMs); rep.steps.add(p4); if (p4.ok()){ rep.success=true; return rep; } }

            StepResult sn = setNext(mediaUrl, didlLiteFor(mediaUrl, mime), 0, soapTimeoutMs); rep.steps.add(sn);
            if (sn.ok()) { StepResult p5 = play(0, soapTimeoutMs); rep.steps.add(p5); if (p5.ok()){ rep.success=true; return rep; } }

            return rep;
        }

        private StepResult stop(int instanceId, int to){ String body="<u:Stop xmlns:u=\""+dev.avTransportUrn+"\"><InstanceID>"+instanceId+"</InstanceID></u:Stop>"; return doSoap("Stop", body, to); }
        private void soapQuiet(String action, String inner, int to){ try { doSoap(action, inner, to); } catch (Throwable ignore) {} }
        private StepResult play(int instanceId, int to){ String body="<u:Play xmlns:u=\""+dev.avTransportUrn+"\"><InstanceID>"+instanceId+"</InstanceID><Speed>1</Speed></u:Play>"; return doSoap("Play", body, to); }
        private StepResult setUri(String url, String meta, int instanceId, int to){
            String md = (meta==null)? "" : xmlEsc(meta);
            String body = "<u:SetAVTransportURI xmlns:u=\"" + dev.avTransportUrn + "\">" +
                    "<InstanceID>"+instanceId+"</InstanceID><CurrentURI>"+xmlEsc(url)+"</CurrentURI>" +
                    "<CurrentURIMetaData>"+md+"</CurrentURIMetaData></u:SetAVTransportURI>";
            return doSoap("SetAVTransportURI", body, to);
        }
        private StepResult setNext(String url, String meta, int instanceId, int to){
            String md = (meta==null)? "" : xmlEsc(meta);
            String body = "<u:SetNextAVTransportURI xmlns:u=\"" + dev.avTransportUrn + "\">" +
                    "<InstanceID>"+instanceId+"</InstanceID><NextURI>"+xmlEsc(url)+"</NextURI>" +
                    "<NextURIMetaData>"+md+"</NextURIMetaData></u:SetNextAVTransportURI>";
            return doSoap("SetNextAVTransportURI", body, to);
        }

        /* ===== helpers ===== */
        private static class ServiceInfo { String serviceType; String controlURL; }
        private static ServiceInfo findService(String descXml, String name) {
            String needle = "urn:schemas-upnp-org:service:" + name + ":";
            int pos = descXml.indexOf(needle); if (pos < 0) return null;
            int st1 = descXml.lastIndexOf("<serviceType>", pos), st2 = descXml.indexOf("</serviceType>", pos);
            int c1  = descXml.indexOf("<controlURL>", pos),  c2  = descXml.indexOf("</controlURL>", c1);
            if (st1<0 || st2<0 || c1<0 || c2<0) return null;
            ServiceInfo s = new ServiceInfo();
            s.serviceType = descXml.substring(st1 + 13, st2).trim();
            s.controlURL  = descXml.substring(c1 + 11, c2).trim();
            return s;
        }

        static class SoapResponse { int httpCode; String faultCode; }
        private static SoapResponse soap(String ctrlUrl, String urn, String action, String inner, int timeoutMs) throws Exception {
            SoapResponse out = new SoapResponse();
            if (ctrlUrl == null || urn == null) { out.httpCode=-1; out.faultCode="NoControlUrlOrUrn"; return out; }

            byte[] body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body>" + inner + "</s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);

            HttpURLConnection c = (HttpURLConnection) new URL(ctrlUrl).openConnection();
            c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
            c.setDoOutput(true); c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction", "\"" + urn + "#" + action + "\"");
            try (var os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            out.httpCode = code;

            if (code >= 200 && code < 300) { out.faultCode=""; return out; }

            String fault = null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    (code >= 400 ? c.getErrorStream() : c.getInputStream()), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                String xml = sb.toString();
                int i = xml.indexOf("<faultcode>"); if (i>=0){ int j=xml.indexOf("</faultcode>", i); if (j>i) fault = xml.substring(i+11, j).trim(); }
                if (fault == null) { int k=xml.indexOf("<errorCode>"); if (k>=0){ int j=xml.indexOf("</errorCode>", k); if (j>k) fault="errorCode:"+xml.substring(k+11, j).trim(); } }
            } catch (Throwable ignore) {}
            out.faultCode = (fault != null ? fault : "HTTP_"+code);
            return out;
        }

        private StepResult doSoap(String action, String inner, int to) {
            StepResult r = new StepResult(); r.step = action;
            try { SoapResponse resp = soap(dev.avTransportCtrl, dev.avTransportUrn, action, inner, to); r.http = resp.httpCode; r.soapFault = resp.faultCode; }
            catch (Throwable e) { r.http = -1; r.soapFault = e.getClass().getSimpleName()+": "+e.getMessage(); }
            return r;
        }

        private static String didlLiteFor(String url, String mime) {
            String prot = "http-get:*:" + (mime != null? mime : "video/*") + ":*";
            return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                    "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                    "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
                    "<item id=\"0\" parentID=\"0\" restricted=\"1\">" +
                    "<dc:title>MiniCast Test</dc:title>" +
                    "<res protocolInfo=\"" + prot + "\">" + xmlEsc(url) + "</res>" +
                    "<upnp:class>object.item.videoItem</upnp:class>" +
                    "</item></DIDL-Lite>";
        }
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
        private static String join(String base, String path) {
            if (path == null) return null;
            try { return new URL(new URL(base + "/"), path).toString(); } catch (Throwable e) { return path; }
        }
        private static String xmlEsc(String s) {
            return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
        }
        private static void sleep(long ms){ try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
    }

    /* ===================== İzin/SmartView & yardımcılar ===================== */
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
            smartViewLaunched = true;
            dbg("opened Smart View / Cast settings");
        } catch (Throwable e1) {
            try {
                Intent i2 = new Intent("android.settings.WIFI_DISPLAY_SETTINGS");
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i2);
                smartViewLaunched = true;
                dbg("opened WIFI_DISPLAY_SETTINGS");
            } catch (Throwable e2) {
                dbg("openSmartViewSettings failed", e2);
                Toast.makeText(this, "Cihaz yansıtma ayarı açılamadı.", Toast.LENGTH_SHORT).show();
            }
        }
    }
    private void prepareMulticast() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("minicast-dlna");
                mlock.setReferenceCounted(false);
            }
        } catch (Throwable e) { dbg("multicast lock prepare failed", e); }
    }
    private void bindToWifiNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            cm.requestNetwork(req, new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    try {
                        if (Build.VERSION.SDK_INT >= 23) {
                            ConnectivityManager cm2 = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                            cm2.bindProcessToNetwork(network); // deprecated ama iş görüyor
                            dbg("Process bound to Wi-Fi network");
                        }
                    } catch (Throwable e) { dbg("bindProcessToNetwork failed", e); }
                }
            });
        } catch (Throwable e) { dbg("requestNetwork/bind Wi-Fi failed", e); }
    }
    private void acquireMl(boolean on) {
        if (mlock == null) return;
        try { if (on && !mlock.isHeld()) mlock.acquire(); else if (!on && mlock.isHeld()) mlock.release(); } catch (Throwable ignored) {}
    }
            }
