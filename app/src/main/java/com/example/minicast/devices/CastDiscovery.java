package com.example.minicast.devices;

import android.content.Context;
import android.os.Bundle;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import androidx.mediarouter.media.MediaRouter.RouteInfo;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chromecast keşfi (MediaRouter tabanlı).
 * - RouteInfo + CastDevice birlikte sarılır (CastDeviceWrapper).
 * - Tekrarlayan bildirimleri engellemek için deviceId/routeId ile de-dupe yapılır.
 * - start() anında mevcut rotalar da işlenir (yalnızca callback’e bel bağlamaz).
 */
public class CastDiscovery {

    public interface Listener {
        void onDeviceFound(TargetDevice device);
        void onDone();
    }

    private final MediaRouter router;
    private final MediaRouteSelector selector;
    private final MediaRouter.Callback cb;
    private final Listener listener;

    private boolean running = false;
    private final Set<String> seenIds = new HashSet<>();

    public CastDiscovery(Context ctx, Listener listener) {
        this.listener = listener;
        this.router = MediaRouter.getInstance(ctx.getApplicationContext());
        this.selector = new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast("*"))
                .build();

        this.cb = new MediaRouter.Callback() {
            @Override
            public void onRouteAdded(MediaRouter router, RouteInfo route) {
                handleRoute(route);
            }

            @Override
            public void onRouteChanged(MediaRouter router, RouteInfo route) {
                handleRoute(route);
            }

            @Override
            public void onRouteRemoved(MediaRouter router, RouteInfo route) {
                // Silinen route'u tekrar keşfetmeye izin vermek için de-dupe setinden çıkar.
                if (route != null && route.getId() != null) {
                    seenIds.remove(route.getId());
                }
            }
        };
    }

    /** Route'i uygun ise (selector ile eşleşiyorsa) listener'a tekil olarak bildir. */
    private void handleRoute(RouteInfo route) {
        if (!running || route == null) return;
        if (!route.matchesSelector(selector)) return;

        // CastDevice'i extras'tan al
        Bundle extras = route.getExtras();
        CastDevice cd = (extras != null) ? CastDevice.getFromBundle(extras) : null;

        // Tekillik anahtarı: öncelik deviceId, yoksa routeId
        String uniqueId = (cd != null && cd.getDeviceId() != null) ? cd.getDeviceId() : route.getId();
        if (uniqueId == null) return;
        if (!seenIds.add(uniqueId)) return; // zaten bildirilmiş

        // RouteInfo'yu da sarmala koyuyoruz ki selectRoute yapabilelim
        TargetDevice dev = new CastDeviceWrapper(cd, route);
        if (listener != null) listener.onDeviceFound(dev);
    }

    /** Keşfi başlatır; mevcut rotaları da ilk anda işler. */
    public void start() {
        if (running) return;
        running = true;
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);

        // Halihazırda var olan rotaları da bildir (bazı cihazlar callback tetiklemeyebilir)
        List<RouteInfo> routes = router.getRoutes();
        for (RouteInfo r : routes) {
            handleRoute(r);
        }
    }

    /** Keşfi durdurur ve dinleyiciyi kapatır. */
    public void stop() {
        if (!running) {
            if (listener != null) listener.onDone();
            return;
        }
        running = false;
        try {
            router.removeCallback(cb);
        } catch (Throwable ignore) {}
        seenIds.clear();
        if (listener != null) listener.onDone();
    }
}
