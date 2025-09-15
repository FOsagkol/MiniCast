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
import android.view.View;                // urlCard artık View
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
// import com.google.android.material.card.MaterialCardView; // GEREK YOK
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

    // --- URL bar alanları (A seçeneği: XML sabit, id: urlBar) ---
    private View urlCard; // MaterialCardView yerine View
    private int urlCollapsedHeightPx, urlExpandedHeightPx;
    // ------------------------------------------------------------

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
        urlCard  = findViewById(R.id.urlBar);   // A seçeneği: urlBar
        urlInput = findViewById(R.id.urlInput);
        goBtn    = findViewById(R.id.goBtn);
        web      = findViewById(R.id.web);

        setupWebView();
        setupUrlBar();

        goBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!TextUtils.isEmpty(url)) {
                if (!url.startsWith("http")) url = "https://" + url;
                web.loadUrl(url);
            }
        });

        // Cast Framework’u erkenden ayağa kaldır
        try { CastContext.getSharedInstance(this); } catch (Throwable ignore) {}

        fabTv = findViewById(R.id.fabTv);
        if (fabTv != null) {
            fabTv.setOnClickListener(v -> startUnifiedDiscovery());
            fabTv.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.tv_ready_bg)));
            fabTv.setTextColor(ContextCompat.getColor(this, R.color.tv_ready_text));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!isLocationServiceEnabled()) {
            // Sessiz bilgi; butona basınca yine yönlendireceğiz.
        }
    }

    @Override
    protected void onStop() {
        safeDismissDeviceDialog();
        stopCastDiscovery();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
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

    // İnce URL bar odak/ellipsize davranışı — XML’e dokunmadan çalışır
    @SuppressLint("ClickableViewAccessibility")
    private void setupUrlBar() {
        if (urlCard == null || urlInput == null) return;

        float d = getResources().getDisplayMetrics().density;
        urlCollapsedHeightPx = (int) (36 * d);
        urlExpandedHeightPx  = (int) (48 * d);

        urlInput.setOnFocusChangeListener((v, hasFocus) -> {
            ViewGroup.LayoutParams lp = urlCard.getLayoutParams();
            lp.height = hasFocus ? urlExpandedHeightPx : urlCollapsedHeightPx;
            urlCard.setLayoutParams(lp);

            if (hasFocus) {
                urlInput.setEllipsize(null);  // tam göster
                urlInput.selectAll();
            } else {
                urlInput.setEllipsize(TextUtils.TruncateAt.MIDDLE); // kısalt
            }
        });

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
        if (deviceDialog != null && deviceDialog.isShowing()) {
            try { deviceDialog.dismiss(); } catch (Throwable ignore) {}
        }
        deviceDialog = null;
        deviceAdapter = null;
    }

    /* --------------------- DİYALOG / LİSTE --------------------- */

    private void addDeviceIfNew(String key, String displayName, Object deviceObj) {
        if (key == null || displayName == null || deviceObj == null) return;
        if (!seenKeys.add(key)) return;

        foundDevices.add(deviceObj);
        if (deviceAdapter != null) {
            deviceAdapter.add(displayName);
            deviceAdapter.notifyDataSetChanged();
        }
        updateDialogTitle();
    }

    private void updateDialogTitle() {
        if (deviceDialog == null || !deviceDialog.isShowing()) return;
        boolean any = !foundDevices.isEmpty();
        boolean allDone = dlnaDone && castDone;
        if (!any && !allDone) {
            deviceDialog.setTitle("Cihazlar aranıyor…");
        } else if (any && !allDone) {
            deviceDialog.setTitle("Cihazlar (aranıyor…)");
        } else {
            deviceDialog.setTitle(any ? "Cihaz seçin" : "Cihaz bulunamadı");
        }
    }

    /* ----------------------- SEÇİM / OYNAT --------------------- */

    private void onDeviceChosen(Object device) {
        if (device instanceof CastDeviceWrapper) {
            CastDeviceWrapper wrapper = (CastDeviceWrapper) device;
            MediaRouter mediaRouter = MediaRouter.getInstance(getApplicationContext());
            if (wrapper.getRoute() != null) {
                mediaRouter.selectRoute(wrapper.getRoute());
                Toast.makeText(this, "Chromecast seçildi, bağlantı kuruluyor…", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Chromecast route bilgisi yok.", Toast.LENGTH_SHORT).show();
            }
            pendingTarget = device;
            requestPageVideoUrl();
            return;
        }

        if (device instanceof DlnaDevice) {
            pendingTarget = device;
            requestPageVideoUrl();
        }
    }

    private void requestPageVideoUrl() {
        String js = "javascript:(function(){try{"
                + "var v=document.querySelector('video');"
                + "var u=v?(v.currentSrc||v.src||''):'';"
                + "if(!u&&v){var s=v.querySelector('source'); if(s) u=s.src;}"
                + "MiniCast.onVideoSelected(u||'');"
                + "}catch(e){MiniCast.onVideoSelected('');}})();";
        web.evaluateJavascript(js, null);
    }

    private class JsBridge {
        @JavascriptInterface
        public void onVideoSelected(String url) {
            runOnUiThread(() -> {
                if (pendingTarget == null) {
                    Toast.makeText(MainActivity.this, "Cihaz seçilmedi.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (TextUtils.isEmpty(url) || url.startsWith("blob:")) {
                    Toast.makeText(MainActivity.this, "Bu video (DRM/blob) aktarılamıyor.", Toast.LENGTH_LONG).show();
                    return;
                }

                if (pendingTarget instanceof DlnaDevice) {
                    DlnaDevice d = (DlnaDevice) pendingTarget;
                    new Thread(() -> {
                        boolean ok = DlnaDiscovery.setUriAndPlay(d.getControlUrl(), url);
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                ok ? "TV’de oynatılıyor" : "DLNA oynatma başarısız",
                                Toast.LENGTH_SHORT).show());
                    }).start();
                } else if (pendingTarget instanceof CastDeviceWrapper) {
                    tryCastLoad(url);
                }
            });
        }
    }

    private void tryCastLoad(String url) {
        try {
            CastContext cc = CastContext.getSharedInstance(this);
            CastSession session = (cc != null) ? cc.getSessionManager().getCurrentCastSession() : null;
            if (session == null || session.getRemoteMediaClient() == null) {
                Toast.makeText(this, "Cast oturumu hazır değil; bağlandıktan sonra tekrar deneyin.", Toast.LENGTH_SHORT).show();
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

            MediaLoadRequestData req = new MediaLoadRequestData.Builder()
                    .setMediaInfo(mediaInfo)
                    .build();

            session.getRemoteMediaClient().load(req);
            Toast.makeText(this, "Chromecast’e gönderildi", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Cast yükleme hatası: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String guessContentType(String url) {
        String u = url.toLowerCase();
        if (u.contains(".m3u8")) return "application/x-mpegurl";
        if (u.contains(".mpd"))  return "application/dash+xml";
        if (u.contains(".webm")) return "video/webm";
        if (u.contains(".mp4"))  return "video/mp4";
        return "video/*";
    }
    }
