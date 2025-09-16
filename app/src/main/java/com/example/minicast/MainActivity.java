package com.example.minicast;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
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

/** MiniCast – DLNA push (genişletilmiş tarama + test klip), Smart View, Chromecast */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MiniCastCrash";
    private static final int REQ_FINE_LOCATION = 42;

    // --- TEST KLİBİ (ilk bağlantı doğrulaması için) ---
    private static final String TEST_URL  = "https://filesamples.com/samples/video/mp4/sample_960x400_ocean_with_audio.mp4";
    private static final String TEST_MIME = "video/mp4";

    private WebView web;
    private EditText urlBox;
    private FloatingActionButton fab;

    // DLNA hedefi
    private DlnaDevice selectedDevice;

    // Ağdan yakalanan medya URL adayları
    private final Set<String> mediaCandidates = new LinkedHashSet<>();
    private volatile String lastMediaUrl = null;

    // Multicast lock (tarama sırasında)
    private WifiManager.MulticastLock mlock;

    // Tarama iptal kontrolü
    private volatile boolean scanCancelled = false;

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
        try { SystemClock.sleep(20); } catch (Throwable ignored) {}
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

        // Multicast lock hazırla (tarama anında acquire/release edeceğiz)
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("minicast-dlna");
                mlock.setReferenceCounted(false);
            }
        } catch (Throwable e) { dbg("multicast lock prepare failed", e); }

        web.addJavascriptInterface(new JsBridge(), "MiniCastBridge");
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
        // Progress dialog (modal)
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);
        container.addView(pb);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Cihazlar aranıyor…")
                .setMessage("DLNA MediaRenderer cihazları taranıyor (≈5 sn)…")
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
                list = DlnaScanner.scanExtended(6000, (st, sent) -> dbg("SSDP sent: " + st),
                        (from, usn, loc) -> dbg("SSDP resp from " + from + " usn=" + usn + " loc=" + loc),
                        () -> scanCancelled);
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

            final List<DlnaDevice> devices = good;
            runOnUiThread(() -> {
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (scanCancelled) {
                    Toast.makeText(this, "Tarama iptal edildi.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (devices.isEmpty()) {
                    showNoDeviceFoundSheet(list); // ham liste ile opsiyonlar
                } else if (devices.size() == 1) {
                    DlnaDevice sel = devices.get(0);
                    selectedDevice = sel;
                    Toast.makeText(this, "DLNA hedef: " + sel.displayName(), Toast.LENGTH_SHORT).show();
                    playTestClip(sel);
                } else {
                    showDlnaPickerForTest(devices);
                }
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
        // Ham yanıtları logla
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
                .setMessage("TV’nin IP adresini girin. Ortak UPnP açıklama yolları otomatik denenecek.")
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
        Toast.makeText(this, "IP üzerinden deneniyor…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            DlnaDevice dev = DlnaScanner.tryCommonDescriptionOnIp(ip);
            if (dev != null) DlnaControl.fillServiceInfo(dev);
            runOnUiThread(() -> {
                if (dev != null && dev.avTransportCtrl != null) {
                    selectedDevice = dev;
                    Toast.makeText(this, "Bulundu: " + dev.displayName(), Toast.LENGTH_SHORT).show();
                    playTestClip(dev);
                } else {
                    Toast.makeText(this, "Olmadı: UPnP açıklaması ya da AVTransport bulunamadı.", Toast.LENGTH_LONG).show();
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
                    playTestClip(sel);
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void playTestClip(DlnaDevice dev) {
        Toast.makeText(this, "TV’ye test videosu gönderiliyor…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                DlnaControl ctl = DlnaControl.fromDevice(dev);
                boolean ok = (ctl != null) && ctl.playUrl(TEST_URL, TEST_MIME);
                runOnUiThread(() -> {
                    if (ok) {
                        Toast.makeText(this, "Tamam! TV’de test video oynuyor.", Toast.LENGTH_LONG).show();
                        // Buradan sonra sayfa içi video yakalama/push adımına geçebilirsiniz (FAB > DLNA ile gönder).
                    } else {
                        Toast.makeText(this, "OLMADI: Cihaz AVTransport desteklemiyor ya da reddetti.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable e) {
                dbg("playTestClip error", e);
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
            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
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
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
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
        @JavascriptInterface public void onVideoUrl(String url) {
            dbg("JS video url: " + url);
            lastMediaUrl = url;
        }
        @JavascriptInterface public void onNoVideo() { dbg("JS: no <video> on page"); }
    }

    /* === (Opsiyonel) Sayfa içi bulunan URL'yi push etmek için çağırın === */
    private void tryAutoDetectAndPush() {
        String candidate = lastMediaUrl;
        if (candidate == null) {
            Toast.makeText(this, "Önce videoyu başlatın ya da sayfada video bulun.", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                String playUrl = candidate;
                String mime = guessMime(playUrl);
                DlnaControl ctl = DlnaControl.fromDevice(selectedDevice);
                boolean ok = (ctl != null) && ctl.playUrl(playUrl, mime);
                runOnUiThread(() -> Toast.makeText(this, ok ? "TV’de oynatılıyor." : "DLNA oynatma başarısız.", Toast.LENGTH_LONG).show());
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

    /* ===================== DLNA/UPnP: MODEL + TARAYICI + KONTROL ===================== */

    static class DlnaDevice { String usn, st, server, location, friendlyName; String avTransportCtrl, avTransportUrn;
        String displayName() { return friendlyName!=null? friendlyName : (server!=null? server : (usn!=null? usn : "DLNA Aygıtı")); } }

    interface SendHook { void onSend(String st, int count); }
    interface RespHook { void onResp(String from, String usn, String location); }
    interface CancelFlag { boolean isCancelled(); }

    static class DlnaScanner {
        private static final String SSDP_ADDR = "239.255.255.250";
        private static final int SSDP_PORT = 1900;

        // Genişletilmiş tarama: çoklu ST + tekrar + 6 sn alım
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

            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setReuseAddress(true);
                sock.setSoTimeout(700);

                // Her ST için 2 gönderim
                for (String st : sts) {
                    String msearch = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: " + st + "\r\n\r\n";
                    byte[] data = msearch.getBytes(StandardCharsets.US_ASCII);
                    DatagramPacket dp = new DatagramPacket(data, data.length,
                            InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
                    sock.send(dp); if (sendHook != null) sendHook.onSend(st, 1);
                    Thread.sleep(120);
                    sock.send(dp); if (sendHook != null) sendHook.onSend(st, 2);
                    Thread.sleep(80);
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
                            if (d.location != null && d.friendlyName == null) {
                                d.friendlyName = fetchFriendlyName(d.location, 700);
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

        // Kullanıcı IP verirse yaygın description yollarını dene
        static DlnaDevice tryCommonDescriptionOnIp(String ip) {
            String[] paths = new String[]{
                    "/description.xml", "/rootDesc.xml", "/DeviceDescription.xml", "/RenderingControl/desc.xml", "/dmr.xml", "/devdesc.xml"
            };
            int[] ports = new int[]{ 80, 2869, 49152, 49153, 49154, 49155, 49156, 49157, 49158, 49159, 49160 };
            for (int p : ports) {
                for (String path : paths) {
                    String url = "http://" + ip + ":" + p + path;
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(800); c.setReadTimeout(800);
                        c.addRequestProperty("User-Agent","Mozilla/5.0");
                        int code = c.getResponseCode();
                        if (code >= 200 && code < 300) {
                            DlnaDevice d = new DlnaDevice();
                            d.location = url;
                            d.friendlyName = fetchFriendlyName(url, 600);
                            d.usn = "manual:"+ip;
                            return d;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            return null;
        }
    }

    /* === DLNA kontrol katmanı: AVTransport (versiyon-duyarlı) === */
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
                String desc = httpGet(d.location, 1500);
                if (desc == null) return;
                String base = baseUrlOf(d.location);

                ServiceInfo avt = findService(desc, "AVTransport");
                if (avt != null && avt.controlURL != null && avt.serviceType != null) {
                    d.avTransportCtrl = join(base, avt.controlURL);
                    d.avTransportUrn  = avt.serviceType; // urn:schemas-upnp-org:service:AVTransport:X
                }
            } catch (Throwable ignored) {}
        }

        boolean playUrl(String mediaUrl, String mime) {
            try {
                String meta = didlLiteFor(mediaUrl, mime);
                String setBody =
                        "<u:SetAVTransportURI xmlns:u=\"" + dev.avTransportUrn + "\">" +
                                "<InstanceID>0</InstanceID>" +
                                "<CurrentURI>" + xmlEsc(mediaUrl) + "</CurrentURI>" +
                                "<CurrentURIMetaData>" + xmlEsc(meta) + "</CurrentURIMetaData>" +
                                "</u:SetAVTransportURI>";
                if (!soap(dev.avTransportCtrl, dev.avTransportUrn, "SetAVTransportURI", setBody)) return false;

                String playBody =
                        "<u:Play xmlns:u=\"" + dev.avTransportUrn + "\">" +
                                "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>";
                return soap(dev.avTransportCtrl, dev.avTransportUrn, "Play", playBody);
            } catch (Throwable ignored) { return false; }
        }

        /* ===== helpers ===== */
        private static class ServiceInfo { String serviceType; String controlURL; }
        private static ServiceInfo findService(String descXml, String name) {
            String needle = "urn:schemas-upnp-org:service:" + name + ":";
            int pos = descXml.indexOf(needle);
            if (pos < 0) return null;
            int st1 = descXml.lastIndexOf("<serviceType>", pos);
            int st2 = descXml.indexOf("</serviceType>", pos);
            int c1  = descXml.indexOf("<controlURL>", pos);
            int c2  = descXml.indexOf("</controlURL>", c1);
            if (st1<0 || st2<0 || c1<0 || c2<0) return null;
            ServiceInfo s = new ServiceInfo();
            s.serviceType = descXml.substring(st1 + 13, st2).trim();
            s.controlURL  = descXml.substring(c1 + 11, c2).trim();
            return s;
        }
        private static boolean soap(String ctrlUrl, String urn, String action, String inner) throws Exception {
            if (ctrlUrl == null || urn == null) return false;
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
            return s.replace("&","&amp;").replace("<","&lt;")
                    .replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
        }
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
    private void acquireMl(boolean on) {
        if (mlock == null) return;
        try { if (on && !mlock.isHeld()) mlock.acquire(); else if (!on && mlock.isHeld()) mlock.release(); }
        catch (Throwable ignored) {}
    }
            }
