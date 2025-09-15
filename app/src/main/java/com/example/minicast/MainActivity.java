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
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
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
import androidx.core.content.ContextCompat;
import androidx.mediarouter.media.MediaRouter;

import com.example.minicast.devices.CastDeviceWrapper;
import com.example.minicast.devices.CastDiscovery;
import com.example.minicast.devices.DlnaDevice;
import com.example.minicast.devices.DlnaDiscovery;
import com.example.minicast.devices.TargetDevice;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_LOC = 1001;

    private MaterialToolbar toolbar;
    private WebView web;
    private EditText urlInput;
    private Button goBtn;
    private ExtendedFloatingActionButton fabTv;

    // --- EKLENEN ALANLAR (URL bar davranışı) ---
    private MaterialCardView urlCard;
    private int urlCollapsedHeightPx, urlExpandedHeightPx;
    // -------------------------------------------

    private final List<Object> foundDevices = new ArrayList<>();
    private final Set<String> seenKeys = new HashSet<>();
    private ArrayAdapter<String> deviceAdapter;
    private AlertDialog deviceDialog;

    private volatile boolean dlnaDone = false;
    private volatile boolean castDone = false;

    private CastDiscovery castDiscovery;
    private Object pendingTarget;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // URL bar / WebView
        urlCard = findViewById(R.id.urlCard);    // EKLENDİ
        urlInput = findViewById(R.id.urlInput);
        goBtn = findViewById(R.id.goBtn);
        web = findViewById(R.id.web);
        setupWebView();
        setupUrlBar(); // EKLENDİ

        goBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!TextUtils.isEmpty(url)) {
                if (!url.startsWith("http")) url = "https://" + url;
                web.loadUrl(url);
            }
        });

        // Cast Framework'u erkenden ayağa kaldır (oturum yönetimi için)
        try { CastContext.getSharedInstance(this); } catch (Throwable ignore) {}

        fabTv = findViewById(R.id.fabTv);
        if (fabTv != null) {
            fabTv.setOnClickListener(v -> startUnifiedDiscovery());
            // FIX: ContextCompat ile güvenli renk alma
            fabTv.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.tv_ready_bg)));
            fabTv.setTextColor(ContextCompat.getColor(this, R.color.tv_ready_text));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // FIX: Ayarlardan geri dönüldüğünde konum hâlâ kapalı ise kullanıcıyı bilgilendir.
        if (!isLocationServiceEnabled()) {
            // Sessiz bilgi; butona basınca yine yönlendireceğiz.
        }
    }

    @Override
    protected void onStop() {
        // FIX: Activity görünmezken tüm keşifleri/diyalogları kapat
        safeDismissDeviceDialog();
        stopCastDiscovery();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // FIX: Ek güvenlik
        safeDismissDeviceDialog();
        stopCastDiscovery();
        super.onDestroy();
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

    // --- EKLENEN METOT: İnce URL bar odak/ellipsize davranışı ---
    @SuppressLint("ClickableViewAccessibility")
    private void setupUrlBar() {
        if (urlCard == null || urlInput == null) return;

        float d = getResources().getDisplayMetrics().density;
        urlCollapsedHeightPx = (int) (36 * d);
        urlExpandedHeightPx  = (int) (48 * d);

        // Odak kazanırsa: genişlet + tüm URL’i göster
        urlInput.setOnFocusChangeListener((v, hasFocus) -> {
            ViewGroup.LayoutParams lp = urlCard.getLayoutParams();
            lp.height = hasFocus ? urlExpandedHeightPx : urlCollapsedHeightPx;
            urlCard.setLayoutParams(lp);

            if (hasFocus) {
                urlInput.setEllipsize(null);          // tam göster
                urlInput.selectAll();
            } else {
                urlInput.setEllipsize(TextUtils.TruncateAt.MIDDLE); // kısalt
            }
        });

        // Kartın boş alanına tıklayınca da odak ver
        urlCard.setOnClickListener(v -> {
            urlInput.requestFocus();
            urlInput.post(() -> {
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(urlInput, InputMethodManager.SHOW_IMPLICIT);
            });
        });

        // Başlangıçta dar görünüm
        ViewGroup.LayoutParams lp = urlCard.getLayoutParams();
        lp.height = urlCollapsedHeightPx;
        urlCard.setLayoutParams(lp);
        urlInput.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        urlInput.setSingleLine(true);
    }
    // ------------------------------------------------------------

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

    /** Tek tuş: önce izin/konum, sonra Chromecast + DLNA paralel keşif */
    private void startUnifiedDiscovery() {
        // FIX: Her girişte temiz state
        resetDiscoveryState();

        if (!ensureDiscoveryPermissionAndLocation()) return;

        // UI hazırla
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        deviceDialog = new AlertDialog.Builder(this)
                .setTitle("Cihazlar aranıyor…")
                .setAdapter(deviceAdapter, (d, which) -> onDeviceChosen(foundDevices.get(which)))
                .setNegativeButton("Kapat", (d, w) -> stopCastDiscovery())
                .create();
        deviceDialog.setOnDismissListener(d -> stopCastDiscovery());
        deviceDialog.show();

        // DLNA
        startDlnaScan();

        // Cast
        startCastScan();

        // Güvenli kapanış
        web.postDelayed(this::stopCastDiscovery, 10_000);
    }

    private void resetDiscoveryState() {
        foundDevices.clear();
        seenKeys.clear();
        dlnaDone = false;
        castDone = false;
        pendingTarget = null;
        // Var ise eski dialog'u kapat
        safeDismissDeviceDialog();
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
        if (!isLocationServiceEnabled()) {
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

    private boolean isLocationServiceEnabled() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        try {
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignore) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(requestCode, perms, grants);
        if (requestCode == REQ_LOC) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                // FIX: İzin verildi → tek giriş noktasından akışı tekrar başlat
                startUnifiedDiscovery();
            } else {
                Toast.makeText(this, "Konum izni verilmeden cihazlar listelenemez", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /* ----------------------- DLNA KEŞFİ ----------------------- */

    private void startDlnaScan() {
        DlnaDiscovery.discover(this, new DlnaDiscovery.Listener() {
            @Override public void onDeviceFound(DlnaDevice device) {
                runOnUiThread(() -> addDeviceIfNew(
                        device.getUsn() != null ? "DLNA:" + device.getUsn() : "DLNA:" + device.getFriendlyName(),
                        device.getFriendlyName(),
                        device));
            }

            @Override public void onDone() {
                runOnUiThread(() -> {
                    dlnaDone = true;
                    updateDialogTitle();
                });
            }

            // onError @Override olmadan bırakıldı (arayüz destekliyorsa çalışır)
            public void onError(Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "DLNA hata: " + (e != null ? e.getMessage() : "bilinmeyen"),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /* --------------------- CHROMECAST KEŞFİ -------------------- */

    private void startCastScan() {
        castDiscovery = new CastDiscovery(this, new CastDiscovery.Listener() {
            @Override public void onDeviceFound(TargetDevice device) {
                if (device instanceof CastDeviceWrapper) {
                    CastDeviceWrapper cdw = (CastDeviceWrapper) device;
                    String key = "CAST:" + cdw.getId();
                    String name = cdw.getName();
                    runOnUiThread(() -> addDeviceIfNew(key, name, cdw));
                }
            }

            @Override public void onDone() {
                runOnUiThread(() -> {
                    castDone = true;
                    updateDialogTitle();
                });
            }

            public void onError(Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Cast keşif hata: " + (e != null ? e.getMessage() : "bilinmeyen"),
                        Toast.LENGTH_LONG).show());
            }
        });
        castDiscovery.start();
    }

    private void stopCastDiscovery() {
        if (castDiscovery != null) {
            try { castDiscovery.stop(); } catch (Throwable ignore) {}
            castDiscovery = null;
        }
        if (!castDone) {
            castDone = true;
            updateDialogTitle();
        }
    }

    private void safeDismissDeviceDialog() {
        if (
