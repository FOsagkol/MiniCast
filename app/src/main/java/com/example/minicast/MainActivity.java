package com.example.minicast;

import android.annotation.SuppressLint;
import android.content.Context;
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
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.minicast.cast.CastDiscovery;
import com.example.minicast.devices.CastDeviceWrapper;
import com.example.minicast.devices.DlnaDevice;
import com.example.minicast.devices.TargetDevice;
import com.example.minicast.dlna.DlnaDiscovery;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class MainActivity extends AppCompatActivity {

  private WebView web;
  private EditText urlInput;

  // Cast
  private SessionManager sessionManager;

  // DLNA
  private WifiManager.MulticastLock mcLock;

  // Discovery
  private final List<TargetDevice> foundDevices = new CopyOnWriteArrayList<>();
  private AlertDialog deviceDialog;
  private ArrayAdapter<String> deviceAdapter;
  private final List<TargetDevice> deviceIndex = new ArrayList<>();

  @Override protected void onCreate(Bundle savedInstanceState) {
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

    // Cast session
    sessionManager = CastContext.getSharedInstance(this).getSessionManager();
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
    web.setWebViewClient(new WebViewClient(){
      @Override public void onPageFinished(WebView view, String url) { urlInput.setText(url); }
    });
    web.loadUrl("https://www.google.com");
  }

  @Override public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main_menu, menu);
    return true;
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == R.id.action_connect_tv) {
      openDevicePicker();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  /* -------- Birleşik cihaz seçici -------- */

  private void openDevicePicker() {
    foundDevices.clear();
    deviceIndex.clear();

    // Dialog
    deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
    AlertDialog.Builder b = new AlertDialog.Builder(this)
        .setTitle("Cihazlar aranıyor…")
        .setAdapter(deviceAdapter, (d, which) -> onDeviceChosen(deviceIndex.get(which)))
        .setNegativeButton("Kapat", (d, w) -> stopDiscovery())
        .setPositiveButton("Yeniden Tara", (d, w) -> {
          stopDiscovery();
          openDevicePicker();
        });
    deviceDialog = b.show();

    startDiscovery();
  }

  private void startDiscovery() {
    // DLNA: multicast lock
    acquireMulticastLock();

    // Chromecast keşfi
    CastDiscovery cast = new CastDiscovery(this, new CastDiscovery.Listener() {
      @Override public void onDeviceFound(TargetDevice device) {
        addDevice(device);
      }
      @Override public void onDone() { /* no-op */ }
    });
    cast.start();

    // DLNA keşfi
    DlnaDiscovery dlna = new DlnaDiscovery();
    dlna.discoverAsync(new DlnaDiscovery.Listener() {
      @Override public void onDeviceFound(TargetDevice device) { addDevice(device); }
      @Override public void onDone() { /* no-op */ }
    }, 5000);

    // 6 sn sonra otomatik başlığı güncelle
    web.postDelayed(() -> {
      if (deviceDialog != null && deviceDialog.isShowing()) {
        deviceDialog.setTitle(foundDevices.isEmpty() ? "Cihaz bulunamadı" : "Cihaz seçin");
      }
      // discovery stop; CastDiscovery için removeCallback gerekecek:
      cast.stop();
      releaseMulticastLock();
    }, 6000);
  }

  private void stopDiscovery() {
    releaseMulticastLock();
    // CastDiscovery removeCallback startDiscovery içinde tutulmuyor; basitlik adına dialog kapanınca zaten discovery bitirildi.
  }

  private void addDevice(TargetDevice d) {
    // Aynı ID gelirse tekrarlama
    for (TargetDevice x : foundDevices) if (x.getId().equals(d.getId())) return;
    foundDevices.add(d);
    deviceIndex.add(d);
    runOnUiThread(() -> {
      String label = d.getName(); // kullanıcıya protokol göstermiyoruz
      deviceAdapter.add(label);
      deviceAdapter.notifyDataSetChanged();
    });
  }

  private void onDeviceChosen(TargetDevice device) {
    // Önce sayfadaki video URL’ini alalım
    injectAndGrabVideoSrc(device);
  }

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
        for (TargetDevice d : foundDevices) if (d.getId().equals(targetId)) { chosen = d; break; }
        if (chosen == null) { toast("Cihaz artık yok."); return; }

        if (TextUtils.isEmpty(url) || url.startsWith("blob:")) {
          toast("Bu video aktarılamıyor (DRM/blob).");
          return;
        }
        playOnDevice(chosen, url);
      });
    }
  }

  private void playOnDevice(TargetDevice device, String mediaUrl) {
    switch (device.getType()) {
      case CAST -> castUrl(mediaUrl);
      case DLNA -> new Thread(() -> {
        DlnaDevice d = (DlnaDevice) device;
        boolean ok = DlnaDiscovery.setUriAndPlay(d.getControlUrl(), mediaUrl);
        runOnUiThread(() -> toast(ok ? "TV’de oynatılıyor" : "DLNA oynatma başarısız"));
      }).start();
    }
  }

  private void castUrl(String url) {
    CastSession session = sessionManager.getCurrentCastSession();
    if (session == null || !session.isConnected()) {
      toast("Önce Chromecast cihazına bağlanın (seçimde Cast cihazı yoksa DLNA seçin).");
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
    if (client != null) client.load(mediaInfo, true, 0);
  }

  private String guessContentType(String url) {
    String u = url.toLowerCase();
    if (u.contains(".m3u8")) return "application/x-mpegURL";
    if (u.contains(".mpd"))  return "application/dash+xml";
    if (u.matches(".*\\.(mp4|m4v)(\\?.*)?$")) return "video/mp4";
    if (u.matches(".*\\.(webm)(\\?.*)?$"))    return "video/webm";
    return "video/mp4";
  }

  /* ---- Multicast Lock ---- */
  private void acquireMulticastLock() {
    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    if (wm != null && (mcLock == null || !mcLock.isHeld())) {
      mcLock = wm.createMulticastLock("minicast-ssdp");
      mcLock.setReferenceCounted(true);
      mcLock.acquire();
    }
  }
  private void releaseMulticastLock() {
    if (mcLock != null && mcLock.isHeld()) mcLock.release();
  }

  private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
