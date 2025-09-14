package com.example.minicast;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.minicast.devices.DlnaDevice;
import com.example.minicast.devices.DlnaDiscovery;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    private WebView web;
    private EditText urlInput;
    private Button goBtn;
    private ExtendedFloatingActionButton fabTv;

    private final List<DlnaDevice> dlnaDevices = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private AlertDialog deviceDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        urlInput = findViewById(R.id.urlInput);
        goBtn = findViewById(R.id.goBtn);
        web = findViewById(R.id.web);
        setupWebView();

        goBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!TextUtils.isEmpty(url)) {
                if (!url.startsWith("http")) url = "https://" + url;
                web.loadUrl(url);
            }
        });

        fabTv = findViewById(R.id.fabTv);
        if (fabTv != null) {
            fabTv.setOnClickListener(v -> startDlnaDiscovery());
            fabTv.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.tv_ready_bg)));
            fabTv.setTextColor(getColor(R.color.tv_ready_text));
        }
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_connect_tv) {
            startDlnaDiscovery();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void startDlnaDiscovery() {
        dlnaDevices.clear();

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        deviceDialog = new AlertDialog.Builder(this)
                .setTitle("Cihazlar aranıyor…")
                .setAdapter(deviceAdapter, (d, which) -> onDeviceChosen(dlnaDevices.get(which)))
                .setNegativeButton("Kapat", null)
                .create();
        deviceDialog.show();

        DlnaDiscovery.discover(this, new DlnaDiscovery.Listener() {
            @Override public void onDeviceFound(DlnaDevice device) {
                runOnUiThread(() -> {
                    dlnaDevices.add(device);
                    deviceAdapter.add(device.getFriendlyName());
                    deviceAdapter.notifyDataSetChanged();
                });
            }
            @Override public void onError(Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "DLNA hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
            @Override public void onDone() {
                runOnUiThread(() -> {
                    if (deviceDialog != null && deviceDialog.isShowing()) {
                        deviceDialog.setTitle(dlnaDevices.isEmpty() ? "Cihaz bulunamadı" : "Cihaz seçin");
                    }
                });
            }
        });
    }

    private void onDeviceChosen(DlnaDevice device) {
        // Sayfadaki video URL’sini çek ve TV’ye gönder
        String js = "javascript:(function(){try{"
                + "var v=document.querySelector('video');"
                + "var u=v?(v.currentSrc||v.src||''):'';"
                + "if(!u&&v){var s=v.querySelector('source'); if(s) u=s.src;}"
                + "MiniCast.onVideoSelected(u||'');"
                + "}catch(e){MiniCast.onVideoSelected('');}})();";
        web.evaluateJavascript(js, null);

        // URL geldikten sonra JsBridge.playOn(device, url) çağrılır
        pendingDevice = device;
    }

    private DlnaDevice pendingDevice;

    private class JsBridge {
        @JavascriptInterface
        public void onVideoSelected(String url) {
            runOnUiThread(() -> {
                if (pendingDevice == null) {
                    Toast.makeText(MainActivity.this, "Cihaz seçilmedi.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (TextUtils.isEmpty(url) || url.startsWith("blob:")) {
                    Toast.makeText(MainActivity.this, "Bu video (DRM/blob) aktarılamıyor.", Toast.LENGTH_LONG).show();
                    return;
                }
                new Thread(() -> {
                    boolean ok = DlnaDiscovery.setUriAndPlay(pendingDevice.getControlUrl(), url);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            ok ? "TV’de oynatılıyor" : "DLNA oynatma başarısız",
                            Toast.LENGTH_SHORT).show());
                }).start();
            });
        }
    }
}
