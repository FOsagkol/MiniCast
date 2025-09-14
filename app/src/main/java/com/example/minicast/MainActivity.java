package com.example.minicast;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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

    private static final int REQ_LOC = 1001;

    private MaterialToolbar toolbar;
    private WebView web;
    private EditText urlInput;
    private Button goBtn;
    private ExtendedFloatingActionButton fabTv;

    private final List<DlnaDevice> dlnaDevices = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private AlertDialog deviceDialog;

    private DlnaDevice pendingDevice;

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
            fabTv.setOnClickListener(v -> startUnifiedDiscovery());
            // Renkler projendeki values/colors.xml’den geliyor
            fabTv.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.tv_ready_bg)));
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
            startUnifiedDiscovery();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Tek tuş akışı: önce izin/konum kontrolü, sonra mevcut DLNA keşfi */
    private void startUnifiedDiscovery() {
        if (!ensureDiscoveryPermissionAndLocation()) return;
        startDlnaDiscovery();
        // İleride CastDiscovery eklersek buraya paralel olarak ekleriz.
    }

    /** Android 6+ için: Konum izni verildi mi ve servis açık mı? */
    private boolean ensureDiscoveryPermissionAndLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
                Toast.makeText(this, "Cihazları bulmak için Konum izni gerekli", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        // Konum servisi açık mı?
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean enabled = false;
        if (lm != null) {
            try {
                enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            } catch (Exception ignore) {}
        }
        if (!enabled) {
            new AlertDialog.Builder(this)
                    .setMessage("Cast/DLNA keşfi için ‘Konum’ açık olmalı. Ayarlar > Konum’u açalım mı?")
                    .setPositiveButton("Aç", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("İptal", null)
                    .show();
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(requestCode, perms, grants);
        if (requestCode == REQ_LOC) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                startUnifiedDiscovery(); // izin verildi, tekrar dene
            } else {
                Toast.makeText(this, "Konum izni verilmeden cihazlar listelenemez", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Mevcut DLNA keşif diyalogu (sendeki akış korunuyor) */
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
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "DLNA hata: " + (e != null ? e.getMessage() : "bilinmeyen"),
                        Toast.LENGTH_LONG).show());
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

        // URL geldikten sonra JsBridge içinde oynatma tetiklenecek
        pendingDevice = device;
    }

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
