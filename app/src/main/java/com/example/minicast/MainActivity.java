package com.example.minicast;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.material.appbar.MaterialToolbar;

public class MainActivity extends AppCompatActivity {

  private WebView web;
  private EditText urlInput;
  private SessionManager sessionManager;

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

    // Cast
    CastContext castContext = CastContext.getSharedInstance(this);
    sessionManager = castContext.getSessionManager();
  }

  @SuppressLint({"SetJavaScriptEnabled"})
  private void setupWebView() {
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setLoadWithOverviewMode(true);
    s.setUseWideViewPort(true);

    web.addJavascriptInterface(new JsBridge(), "MiniCast"); // JS -> Java köprüsü
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(new WebViewClient(){
      @Override public void onPageFinished(WebView view, String url) {
        // URL çubuğunu güncelle
        urlInput.setText(url);
      }
    });

    // Başlangıç sayfası
    web.loadUrl("https://www.google.com");
  }

  @Override public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main_menu, menu);
    // Cast butonunu bağla
    CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), menu, R.id.media_route_menu_item);
    return true;
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == R.id.action_cast_video) {
      injectAndGrabVideoSrc(); // sayfayı tara
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  /** Sayfadaki <video> elementinin URL'sini bulmak için JS enjektesi */
  private void injectAndGrabVideoSrc() {
    String js =
      "javascript:(function(){\n" +
      "  try{\n" +
      "    var v = document.querySelector('video');\n" +
      "    if(!v){ MiniCast.onVideoUrl(''); return; }\n" +
      "    var src = v.currentSrc || v.src || '';\n" +
      "    // Bazı siteler <source> kullanır\n" +
      "    if(!src && v.querySelector('source')) src = v.querySelector('source').src;\n" +
      "    MiniCast.onVideoUrl(src || '');\n" +
      "  }catch(e){ MiniCast.onVideoUrl(''); }\n" +
      "})();";
    web.evaluateJavascript(js, null);
  }

  /** JS köprüsü: URL’yi Java’ya getirir ve Cast etmeyi dener */
  private class JsBridge {
    @JavascriptInterface
    public void onVideoUrl(String url) {
      runOnUiThread(() -> {
        if (TextUtils.isEmpty(url) || url.startsWith("blob:")) {
          // blob: veya boş -> büyük olasılıkla DRM/MSE, doğrudan alınamaz
          // İsterseniz burada kullanıcıya uyarı gösterin.
          return;
        }
        castUrl(url);
      });
    }
  }

  private void castUrl(String url) {
    CastSession session = sessionManager.getCurrentCastSession();
    if (session == null || !session.isConnected()) {
      // Kullanıcı menüdeki Cast ikonundan önce bir cihaza bağlanmalı
      return;
    }

    String contentType = guessContentType(url); // basit tahmin

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
    if (u.contains(".m3u8")) return "application/x-mpegURL";   // HLS
    if (u.contains(".mpd"))  return "application/dash+xml";     // DASH
    if (u.matches(".*\\.(mp4|m4v)(\\?.*)?$")) return "video/mp4";
    if (u.matches(".*\\.(webm)(\\?.*)?$"))    return "video/webm";
    return "video/mp4"; // güvenli varsayılan
  }
}
