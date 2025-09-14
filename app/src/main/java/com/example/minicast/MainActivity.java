package com.example.minicast;

import com.example.minicast.devices.DlnaDevice;
import com.example.minicast.devices.DlnaDiscovery;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
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
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import androidx.mediarouter.media.MediaRouter.RouteInfo;

import com.example.minicast.devices.TargetDevice;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.cast.CastMediaControlIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class MainActivity extends AppCompatActivity {

    private WebView web;
    private EditText urlInput;

    // FAB: hem Extended hem normal FAB ile uyumlu çalışalım
    private ExtendedFloatingActionButton eFab;
    private FloatingActionButton sFab;

    // Tek listede Cast + DLNA cihazları
    private final List<TargetDevice> foundDevices = new CopyOnWriteArrayList<>();
    private final List<TargetDevice> deviceIndex = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private AlertDialog deviceDialog;

    // Cast
    private SessionManager sessionManager;
    private MediaRouter mediaRouter;
    private MediaRouteSelector castSelector;
    private boolean castScanning = false;
    private final MediaRouter.Callback castCallback = new MediaRouter.Callback() {
        @Override public void onRouteAdded(MediaRouter router, RouteInfo route) {
            if (!castScanning) return;
            addDevice(new CastDeviceWrapper(route.getId(), safe(route.getName())));
        }
        @Override public void onRouteChanged(MediaRouter router, RouteInfo route) {
            // no-op
        }
    };

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

        // FAB referansları (hangisi varsa onu kullan)
        View fab = findViewById(R.id.fabTv);
        if (fab instanceof ExtendedFloatingActionButton) {
            eFab = (ExtendedFloatingActionButton) fab;
        } else if (fab instanceof FloatingActionButton) {
            sFab = (FloatingActionButton) fab;
        }
        setUi(UiState.IDLE);
        if (eFab != null) eFab.setOnClickListener(v -> openDevicePicker());
        if (sFab != null) sFab.setOnClickListener(v -> openDevicePicker());

        // Cast
        sessionManager = CastContext.getSharedInstance(this).getSessionManager();
        mediaRouter = MediaRouter.getInstance(getApplicationContext());
        castSelector = new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast("*")) // tüm alıcılar
                .build();

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
        // Extended FAB varsa yazı/çerçeve ile zengin, yoksa normal FAB'de sadece renk/ikon
        if (eFab != null) {
            switch (s) {
                case IDLE -> {
                    eFab.setText("TV");
                    eFab.setIconResource(android.R.drawable.ic_media_play);
                    eFab.setClickable(true);
                    eFab.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_idle_bg)));
                    eFab.setStrokeWidth(4);
                    eFab.setStrokeColor(ColorStateList.valueOf(getColor(R.color.tv_idle_border)));
                    eFab.setTextColor(getColor(R.color.tv_idle_text));
                }
                case SEARCHING -> {
                    eFab.setText("Aranıyor…");
                    eFab.setIconResource(android.R.drawable.ic_popup_sync);
                    eFab.setStrokeWidth(0);
                    eFab.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_searching_bg)));
                    eFab.setTextColor(getColor(R.color.tv_searching_text));
                }
                case READY -> {
                    eFab.setText("Bağlan");
                    eFab.setIconResource(android.R.drawable.ic_media_play);
                    eFab.setStrokeWidth(0);
                    eFab.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_ready_bg)));
                    eFab.setTextColor(getColor(R.color.tv_ready_text));
                }
                case CONNECTED -> {
                    eFab.setText("Bağlı");
                    eFab.setIconResource(android.R.drawable.presence_online);
                    eFab.setStrokeWidth(0);
                    eFab.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_connected_bg)));
                    eFab.setTextColor(getColor(R.color.tv_connected_text));
                }
            }
        } else if (sFab != null) {
            // Normal FAB için sade renk geçişleri
            switch (s) {
                case IDLE -> {
                    sFab.setImageResource(android.R.drawable.ic_media_play);
                    sFab.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_idle_bg)));
                    sFab.setImageTintList(ColorStateList.valueOf(getColor(R.color.tv_idle_border)));
                }
                case SEARCHING -> {
                    sFab.setImageResource(android.R.drawable.ic_popup_sync);
                    sFab.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_searching_bg)));
                    sFab.setImageTintList(ColorStateList.valueOf(getColor(R.color.tv_searching_text)));
                }
                case READY -> {
                    sFab.setImageResource(android.R.drawable.ic_media_play);
                    sFab.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_ready_bg)));
                    sFab.setImageTintList(ColorStateList.valueOf(getColor(R.color.tv_ready_text)));
                }
                case CONNECTED -> {
                    sFab.setImageResource(android.R.drawable.presence_online);
                    sFab.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_connected_bg)));
                    sFab.setImageTintList(ColorStateList.valueOf(getColor(R.color.tv_connected_text)));
                }
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
        // DLNA keşfi
        DlnaDiscovery dlna = new DlnaDiscovery(wifiManager);
        dlna.discoverAsync(new DlnaDiscovery.Listener() {
            @Override public void onDeviceFound(TargetDevice device) { addDevice(device); }
            @Override public void onDone() { /* no-op */ }
        }, 8000);

        // CAST keşfi (aynı listeye)
        castScanning = true;
        mediaRouter.addCallback(castSelector, castCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);

        // Dialog kapanınca Cast keşfini durdur
        if (deviceDialog != null) {
            deviceDialog.setOnDismissListener(d -> stopCastDiscovery());
        }
    }

    private void stopCastDiscovery() {
        if (castScanning) {
            castScanning = false;
            try { mediaRouter.removeCallback(castCallback); } catch (Throwable ignore) {}
        }
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
        String u = url.toLowerCase(Locale.US);
        if (u.contains(".m3u8")) return "application/x-mpegURL";
        if (u.contains(".mpd"))  return "application/dash+xml";
        if (u.matches(".*\\.(mp4|m4v)(\\?.*)?$")) return "video/mp4";
        if (u.matches(".*\\.(webm)(\\?.*)?$"))    return "video/webm";
        return "video/mp4";
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /* ----------------------------- Yardımcı sınıf ---------------------------------- */
    // Basit Cast sarmalayıcı (ayrı dosya gerektirmesin diye burada)
    private static class CastDeviceWrapper implements TargetDevice {
        private final String id;
        private final String name;
        CastDeviceWrapper(String id, String name) { this.id = id; this.name = name; }
        @Override public String getId() { return id; }
        @Override public String getName() { return name; }
        @Override public DeviceType getType() { return DeviceType.CAST; }
    }

    private static String safe(CharSequence cs) { return cs == null ? "Cast Cihazı" : cs.toString(); }
    }
