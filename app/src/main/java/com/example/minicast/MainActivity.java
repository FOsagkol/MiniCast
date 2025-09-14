package com.example.minicast;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.minicast.devices.DlnaDevice;
import com.example.minicast.devices.DlnaDiscovery;
import com.example.minicast.devices.TargetDevice;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MainActivity extends AppCompatActivity {

    private WebView web;
    private EditText urlInput;
    private ExtendedFloatingActionButton fabTv;

    // Tek listede Cast + DLNA cihazları
    private final List<TargetDevice> foundDevices = new CopyOnWriteArrayList<>();
    private final List<TargetDevice> deviceIndex = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private AlertDialog deviceDialog;

    // Cast
    private SessionManager sessionManager;

    // DLNA
    private WifiManager wifiManager;

    // FAB UI state
    private enum UiState { IDLE, SEARCHING, READY, CONNECTED }
    private UiState ui = UiState.IDLE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        urlInput = findViewById(R.id.urlInput);
        Button goBtn = findViewById(R.id.goBtn);
        web = findViewById(R.id.web);
        setupWebView();

        goBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!TextUtils.isEmpty(url)) {
                if (!url.startsWith("http")) url = "https://" + url;
                web.loadUrl(url);
            }
        });

        // FAB
        fabTv = findViewById(R.id.fabTv);
        setUi(UiState.IDLE);
        fabTv.setOnClickListener(v -> openDevicePicker());

        // Cast
        sessionManager = CastContext.getSharedInstance(this).getSessionManager();

        // Wifi
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    @SuppressLint({"SetJavaScriptEnabled"})
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        web.addJavascriptInterface(new JsBridge(), "MiniCast");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                urlInput.setText(url);
            }
        });

        web.loadUrl("https://www.google.com");
    }

    /* ---------------- Toolbar menüsü (varsa) aynı aksiyonu tetikler ---------------- */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_connect_tv) {
            openDevicePicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /* -------------------------------- UI State ------------------------------------ */

    private void setUi(UiState s) {
        ui = s;
        switch (s) {
            case IDLE -> {
                fabTv.setText("TV");
                fabTv.setIconResource(android.R.drawable.ic_media_play);
                fabTv.setClickable(true);
                fabTv.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_idle_bg)));
                fabTv.setStrokeWidth(4);
                fabTv.setStrokeColor(ColorStateList.valueOf(getColor(R.color.tv_idle_border)));
                fabTv.setTextColor(getColor(R.color.tv_idle_text));
            }
            case SEARCHING -> {
                fabTv.setText("Aranıyor…");
                fabTv.setIconResource(android.R.drawable.ic_popup_sync);
                fabTv.setStrokeWidth(0);
                fabTv.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_searching_bg)));
                fabTv.setTextColor(getColor(R.color.tv_searching_text));
            }
            case READY -> {
                fabTv.setText("Bağlan");
                fabTv.setIconResource(android.R.drawable.ic_media_play);
                fabTv.setStrokeWidth(0);
                fabTv.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_ready_bg)));
                fabTv.setTextColor(getColor(R.color.tv_ready_text));
            }
            case CONNECTED -> {
                fabTv.setText("Bağlı");
                fabTv.setIconResource(android.R.drawable.presence_online);
                fabTv.setStrokeWidth(0);
                fabTv.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_connected_bg)));
                fabTv.setTextColor(getColor(R.color.tv_connected_text));
            }
        }
    }

    /* ------------------------------- Cihaz seçici ---------------------------------- */

    private void openDevicePicker() {
        foundDevices.clear();
        deviceIndex.clear();

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Cihazlar aranıyor…")
                .setAdapter(deviceAdapter, (d, which) -> onDeviceChosen(deviceIndex.get(which)))
                .setNegativeButton("Kapat", (d, w) -> { /* no-op */ })
                .setPositiveButton("Yeniden Tara", (d, w) -> openDevicePicker());
        deviceDialog = b.show();

        setUi(UiState.SEARCHING);
        startDiscovery();

        // 10 sn sonra durumu güncelle
        web.postDelayed(() -> {
            if (deviceDialog != null && deviceDialog.isShowing()) {
                deviceDialog.setTitle(foundDevices.isEmpty() ? "Cihaz bulunamadı" : "Cihaz seçin");
            }
            setUi(foundDevices.isEmpty() ? UiState.IDLE : UiState.READY);
        }, 10_000);
    }

    private void startDiscovery() {
        // DLNA keşfi (MulticastLock'u içeride yönetiyor)
        DlnaDiscovery dlna = new DlnaDiscovery(wifiManager);
        dlna.discoverAsync(new DlnaDiscovery.Listener() {
            @Override public void onDeviceFound(TargetDevice device) { addDevice(device); }
            @Override public void onDone() { /* no-op */ }
        }, 8000);

        // Chromecast keşfi: Kendi CastDiscovery sınıfınızı kullanıyorsanız paralel başlatın ve addDevice(...) ile aynı listeye ekleyin.
        // Örn:
        // CastDiscovery cast = new CastDiscovery(this, new CastDiscovery.Listener() {
        //     @Override public void onDeviceFound(TargetDevice device) { addDevice(device); }
        //     @Override public void onDone() { /* no-op */ }
        // });
        // cast.start();
        // Not: Dialog kapanırken cast.stop() çağırabilirsiniz.
    }

    private void addDevice(TargetDevice d) {
        for (TargetDevice x : foundDevices) {
            if (x.getId().equals(d.getId())) return;
        }
        foundDevices.add(d);
        deviceIndex.add(d);
        runOnUiThread(() -> {
            deviceAdapter.add(d.getName()); // protokolü göstermiyoruz
            deviceAdapter.notifyDataSetChanged();
        });
    }

    private void onDeviceChosen(TargetDevice device) {
        // Seçilen cihaza uygun URL’yi sayfadan çek
        injectAndGrabVideoSrc(device);
    }

    /* ------------------------------ Video URL çıkarımı ----------------------------- */

    private void injectAndGrabVideoSrc(TargetDevice target) {
        String js =
                "javascript:(function(){try{"
                        + "var v=document.querySelector('video');"
                        + "if(!v){MiniCast.onVideoUrlFor('','" + target.getId() + "');return;}"
                        + "var src=v.currentSrc||v.src||'';"
                        + "if(!src&&v.querySelector('source'))src=v.querySelector('source').src;"
                        + "MiniCast.onVideoUrlFor(src||'','" + target.getId() + "');"
                        + "}catch(e){MiniCast.onVideoUrlFor('','" + target.getId() + "');}})();";
        web.evaluateJavascript(js, null);
    }

    private class JsBridge {
        @JavascriptInterface
        public void onVideoUrlFor(String url, String targetId) {
            runOnUiThread(() -> {
                TargetDevice chosen = null;
                for (TargetDevice d : foundDevices) {
                    if (d.getId().equals(targetId)) { chosen = d; break; }
                }
                if (chosen == null) { toast("Cihaz artık yok."); return; }

                if (TextUtils.isEmpty(url) || url.startsWith("blob:")) {
                    toast("Bu video aktarılamıyor (DRM/blob).");
                    return;
                }
                playOnDevice(chosen, url);
            });
        }
    }

    /* --------------------------------- Oynatma ------------------------------------- */

    private void playOnDevice(TargetDevice device, String mediaUrl) {
        switch (device.getType()) {
            case CAST -> castUrl(mediaUrl);
            case DLNA -> new Thread(() -> {
                DlnaDevice d = (DlnaDevice) device;
                boolean ok = DlnaDiscovery.setUriAndPlay(d.getControlUrl(), mediaUrl);
                runOnUiThread(() -> {
                    if (ok) {
                        setUi(UiState.CONNECTED);
                        toast("TV’de oynatılıyor");
                    } else {
                        toast("DLNA oynatma başarısız");
                    }
                });
            }).start();
        }
    }

    private void castUrl(String url) {
        CastSession session = sessionManager.getCurrentCastSession();
        if (session == null || !session.isConnected()) {
            toast("Önce bir Cast cihazına bağlanın veya DLNA cihazı seçin.");
            return;
        }
        String contentType = guessContentType(url);
        MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        md.putString(MediaMetadata.KEY_TITLE, "MiniCast");
        MediaInfo mediaInfo = new MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(contentType)
                .setMetadata(md)
                .build();
        RemoteMediaClient client = session.getRemoteMediaClient();
        if (client != null) {
            client.load(mediaInfo, true, 0);
            setUi(UiState.CONNECTED);
        }
    }

    private String guessContentType(String url) {
        String u = url.toLowerCase();
        if (u.contains(".m3u8")) return "application/x-mpegURL";
        if (u.contains(".mpd"))  return "application/dash+xml";
        if (u.matches(".*\\.(mp4|m4v)(\\?.*)?$")) return "video/mp4";
        if (u.matches(".*\\.(webm)(\\?.*)?$"))    return "video/webm";
        return "video/mp4";
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
            }
