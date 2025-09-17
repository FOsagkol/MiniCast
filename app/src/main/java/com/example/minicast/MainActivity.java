/*  --- MainActivity.java (DLNA tarama güçlendirilmiş sürüm) ---  */
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
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** MiniCast – DLNA push */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MiniCastCrash";
    private static final int REQ_FINE_LOCATION = 42;

    private static final String TEST_URL  = "https://filesamples.com/samples/video/mp4/sample_960x400_ocean_with_audio.mp4";
    private static final String TEST_MIME = "video/mp4";

    private static final int SCAN_WINDOW_MS   = 25_000; // 25 sn
    private static final int DESC_TIMEOUT_MS  = 2_500;
    private static final int SOAP_TIMEOUT_MS  = 5_000;

    private WebView web;
    private EditText urlBox;
    private FloatingActionButton fab;

    private DlnaDevice selectedDevice;

    private final Set<String> mediaCandidates = new LinkedHashSet<>();
    private volatile String lastMediaUrl = null;

    private WifiManager.MulticastLock mlock;
    private volatile boolean scanCancelled = false;
    private boolean smartViewLaunched = false;

    /* ------------ logging ------------ */
    private boolean writeToDownloads(String fileName, String text) {
        try {
            ContentValues v = new ContentValues();
            v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
            v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            Uri uri = getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wa")) {
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
    }
    private void dbg(String msg) { dbg(msg, null); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { getSplashScreen().setOnExitAnimationListener(sv -> { try { sv.remove(); } catch (Throwable ignored) {} }); } catch (Throwable ignored) {}
        }
        setContentView(R.layout.activity_main);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        web = findViewById(R.id.web);
        urlBox = findViewById(R.id.urlBox);
        MaterialButton btnGo = findViewById(R.id.btnGo);
        fab = findViewById(R.id.fabAction);

        setupWebView(web);
        if (urlBox.getText().length() == 0) urlBox.setText("https://www.google.com");
        web.loadUrl(urlBox.getText().toString());

        btnGo.setOnClickListener(v -> {
            String u = urlBox.getText().toString().trim();
            if (!u.startsWith("http")) u = "https://" + u;
            mediaCandidates.clear(); lastMediaUrl = null;
            web.loadUrl(u);
        });

        fab.setOnClickListener(v -> showRoutePicker());
        fab.setOnLongClickListener(v -> { openCastChooser(); return true; });

        try { CastContext.getSharedInstance(this); } catch (Throwable e) { dbg("CastContext init failed", e); }

        ensureFineLocation();
        prepareMulticast();
        bindToWifiNetwork();
        web.addJavascriptInterface(new JsBridge(), "MiniCastBridge");
    }

    @Override protected void onResume() {
        super.onResume();
        if (smartViewLaunched) { smartViewLaunched = false; startDlnaTestFlowWithDialog(); }
    }

    @Override protected void onDestroy() {
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /* -------- route picker -------- */
    private void showRoutePicker() {
        final CharSequence[] items = new CharSequence[]{
                "DLNA ile gönder (test klip)",
                "Ekranı yansıt (Smart View)",
                "Chromecast"
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Nasıl oynatılsın?")
                .setItems(items, (d, which) -> {
                    if (which == 0) startDlnaTestFlowWithDialog();
                    else if (which == 1) openSmartViewSettings();
                    else openCastChooser();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void openCastChooser() {
        try {
            DialogFragment f = MediaRouteDialogFactory.getDefault().onCreateChooserDialogFragment();
            f.show(getSupportFragmentManager(), "mr_chooser_dialog");
            dbg("cast chooser shown");
        } catch (Throwable e) { dbg("cast chooser failed", e); }
    }

    /* -------- DLNA scan + push -------- */
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
                .setMessage("DLNA MediaRenderer cihazları taranıyor (≈25 sn)…")
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
                List<DlnaDevice> scanned = DlnaScanner.scanAggressive(SCAN_WINDOW_MS,
                        (st, n) -> dbg("SSDP sent: "+st+" #"+n),
                        (from, usn, loc) -> dbg("SSDP resp from "+from+" usn="+usn+" loc="+loc),
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
                if (d.avTransportCtrl != null && d.avTransportUrn != null) good.add(d);
            }

            runOnUiThread(() -> {
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (scanCancelled) { Toast.makeText(this, "Tarama iptal edildi.", Toast.LENGTH_SHORT).show(); return; }
                if (good.isEmpty()) showNoDeviceFoundSheet(list);
                else if (good.size()==1){ selectedDevice = good.get(0); Toast.makeText(this,"DLNA hedef: "+selectedDevice.displayName(),Toast.LENGTH_SHORT).show(); playTestClipCompat(selectedDevice); }
                else showDlnaPickerForTest(good);
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
                .setMessage("Önce UNICAST M-SEARCH, sonra yaygın UPnP description yolları denenecek.")
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
        Toast.makeText(this, "IP üzerinden deneme…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            DlnaDevice dev = DlnaScanner.tryUnicastSsdp(ip, 3_000);
            if (dev == null) dev = DlnaScanner.tryCommonDescriptionOnIp(ip);
            if (dev != null) DlnaControl.fillServiceInfo(dev);
            DlnaDevice finalDev = dev;
            runOnUiThread(() -> {
                if (finalDev != null && finalDev.avTransportCtrl != null) {
                    selectedDevice = finalDev;
                    Toast.makeText(this, "Bulundu: " + finalDev.displayName(), Toast.LENGTH_SHORT).show();
                    playTestClipCompat(finalDev);
                } else {
                    Toast.makeText(this, "Olmadı: AVTransport yok/yanıt yok (IP ekle).", Toast.LENGTH_LONG).show();
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

    private void playTestClipCompat(DlnaDevice dev) {
        Toast.makeText(this, "TV’ye test videosu gönderiliyor…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                DlnaControl ctl = DlnaControl.fromDevice(dev);
                if (ctl == null) { runOnUiThread(() -> Toast.makeText(this, "DLNA denetim URL’leri yok.", Toast.LENGTH_LONG).show()); return; }
                DlnaControl.PushReport rep = ctl.playUrlCompat(TEST_URL, TEST_MIME, SOAP_TIMEOUT_MS);
                dbg("PUSH REPORT:\n" + rep.humanReadable());
                runOnUiThread(() -> Toast.makeText(this, rep.success ? "Tamam! TV’de test video oynuyor." : ("Olmadı:\n" + rep.humanReadable()), Toast.LENGTH_LONG).show());
            } catch (Throwable e) {
                dbg("playTestClipCompat error", e);
                runOnUiThread(() -> Toast.makeText(this, "Push sırasında hata.", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /* -------- WebView -------- */
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

    private String guessMime(String u) {
        String l = u.toLowerCase(Locale.ROOT);
        if (l.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if (l.contains(".mpd"))  return "application/dash+xml";
        if (l.contains(".mp4"))  return "video/mp4";
        if (l.contains(".webm")) return "video/webm";
        return "video/*";
    }

    /* -------- DLNA models -------- */
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

        // AGGRESSIVE: multicast join + NOTIFY dinle + 6 tur M-SEARCH + 25 sn pencere
        static List<DlnaDevice> scanAggressive(int windowMs, SendHook sendHook, RespHook respHook, CancelFlag cancel) throws Exception {
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

            // 1) Multicast dinleme soketi (NOTIFY + 200 OK’ler)
            try (MulticastSocket msock = new MulticastSocket(SSDP_PORT)) {
                msock.setReuseAddress(true);
                msock.setSoTimeout(800);
                msock.setTimeToLive(2);

                // Tüm arayüzlerde 239.255.255.250 grubuna katılmayı dene
                Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
                List<NetworkInterface> listIfs = ifs != null ? Collections.list(ifs) : new ArrayList<>();
                for (NetworkInterface nif : listIfs) {
                    try {
                        if (!nif.isUp() || nif.isLoopback()) continue;
                        msock.joinGroup(new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT), nif);
                        // sadece ilk join yeterli olabilir; ama birden çoğuna katılmak problem olmaz
                    } catch (Throwable ignored) {}
                }

                // 2) Gönderim için ayrı DatagramSocket (bazı cihazlar MulticastSocket gönderilerine cevap vermez)
                try (DatagramSocket sendSock = new DatagramSocket(null)) {
                    sendSock.setReuseAddress(true);
                    sendSock.bind(new InetSocketAddress(0));

                    String localInfo = "sendSock local=" + sendSock.getLocalAddress() + ":" + sendSock.getLocalPort();
                    Log.e(TAG, "SSDP send socket: " + localInfo);

                    byte[] buf = new byte[8192];
                    int round = 0;
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (cancel != null && cancel.isCancelled()) break;

                        // Her 2 saniyede bir ST listesini sırayla 6 tur gönder
                        if (round < 6) {
                            for (String st : sts) {
                                String msearch = "M-SEARCH * HTTP/1.1\r\n" +
                                        "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                                        "MAN: \"ssdp:discover\"\r\n" +
                                        "MX: 2\r\n" +
                                        "ST: " + st + "\r\n\r\n";
                                byte[] data = msearch.getBytes(StandardCharsets.US_ASCII);
                                DatagramPacket dp = new DatagramPacket(data, data.length,
                                        InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
                                for (int i=1;i<=2;i++) { // her ST için 2 kez
                                    sendSock.send(dp);
                                    if (sendHook!=null) sendHook.onSend(st, (round*2)+i);
                                    Thread.sleep(80);
                                }
                            }
                            round++;
                        }

                        // Cevap/NOTIFY dinle
                        DatagramPacket resp = new DatagramPacket(buf, buf.length);
                        try {
                            msock.receive(resp);
                            String txt = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                            DlnaDevice d = parseSsdp(txt);
                            if (d != null && d.usn != null) {
                                if (respHook != null) respHook.onResp(resp.getAddress().getHostAddress(), d.usn, d.location);
                                if (d.location != null && d.friendlyName == null)
                                    d.friendlyName = fetchFriendlyName(d.location, 900);
                                map.put(d.usn, d);
                            }
                        } catch (SocketTimeoutException ignore) {}
                    }
                } finally {
                    // gruptan ayrılma
                    try {
                        Enumeration<NetworkInterface> ifs2 = NetworkInterface.getNetworkInterfaces();
                        List<NetworkInterface> list2 = ifs2 != null ? Collections.list(ifs2) : new ArrayList<>();
                        for (NetworkInterface nif : list2) {
                            try { msock.leaveGroup(new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT), nif); }
                            catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
            }
            return new ArrayList<>(map.values());
        }

        // Unicast SSDP (IP ile ekle)
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
                        DlnaDevice d = parseSsdp(txt);
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

        private static DlnaDevice parseSsdp(String txt) {
            String[] lines = txt.split("\r?\n");
            if (lines.length == 0) return null;
            if (!(lines[0].startsWith("HTTP/1.1 200") || lines[0].startsWith("NOTIFY * HTTP/1.1"))) return null;
            DlnaDevice d = new DlnaDevice();
            for (String l : lines) {
                int i = l.indexOf(':'); if (i <= 0) continue;
                String k = l.substring(0, i).trim().toUpperCase(Locale.ROOT);
                String v = l.substring(i + 1).trim();
                if ("USN".equals(k)) d.usn = v;
                else if ("ST".equals(k) || "NT".equals(k)) d.st = v; // NOTIFY’de NT
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

    /* -------- DLNA control -------- */
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

        private StepResult doSoap(String action, String inner, int to) {
            StepResult r = new StepResult(); r.step = action;
            try { SoapResponse resp = soap(dev.avTransportCtrl, dev.avTransportUrn, action, inner, to); r.http = resp.httpCode; r.soapFault = resp.faultCode; }
            catch (Throwable e) { r.http = -1; r.soapFault = e.getClass().getSimpleName()+": "+e.getMessage(); }
            return r;
        }

        /* helpers */
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
        private static String httpGet(String url, int timeout) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
            c.setInstanceFollowRedirects(true);
            c.addRequestProperty("User-Agent","Mozilla/5.0");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (var is = c.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n); }
            return bos.toString(StandardCharsets.UTF_8);
        }
        private static String baseUrlOf(String url) throws Exception {
            URL u = new URL(url);
            int p = (u.getPort() >= 0) ? u.getPort() : u.getDefaultPort();
            return u.getProtocol() + "://" + u.getHost() + (p>0? (":" + p): "");
        }
        private static String join(String base, String path) {
            if (path == null) return null;
            try { return new URL(new URL(base + "/"), path).toString(); }
            catch (Throwable e) { return path; }
        }
        private static String xmlEsc(String s) {
            return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
        }
        private static void sleep(long ms){ try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
    }

    /* -------- permissions / network helpers -------- */
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
            NetworkRequest req = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            cm.requestNetwork(req, new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    try {
                        if (Build.VERSION.SDK_INT >= 23) {
                            ConnectivityManager cm2 = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                            cm2.bindProcessToNetwork(network);
                            dbg("Process bound to Wi-Fi");
                        }
                    } catch (Throwable e) { dbg("bindProcessToNetwork failed", e); }
                }
            });
        } catch (Throwable e) { dbg("requestNetwork/bind Wi-Fi failed", e); }
    }
    private void acquireMl(boolean on) {
        if (mlock == null) return;
        try { if (on && !mlock.isHeld()) mlock.acquire(); else if (!on && mlock.isHeld()) mlock.release(); }
        catch (Throwable ignored) {}
    }
                }
