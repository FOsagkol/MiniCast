package com.example.minicast.devices;

import android.content.Context;
import android.os.Bundle;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import androidx.mediarouter.media.MediaRouter.RouteInfo;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;

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

    public CastDiscovery(Context ctx, Listener listener) {
        this.listener = listener;
        this.router = MediaRouter.getInstance(ctx.getApplicationContext());
        this.selector = new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast("*"))
                .build();

        this.cb = new MediaRouter.Callback() {
            @Override
            public void onRouteAdded(MediaRouter router, RouteInfo route) {
                if (!running) return;

                // CastDevice'i MediaRouter RouteInfo.extras içinden çıkar
                Bundle extras = route.getExtras();
                if (extras == null) return;

                CastDevice cd = CastDevice.getFromBundle(extras);
                if (cd == null) return;

                TargetDevice dev = new CastDeviceWrapper(cd);
                if (CastDiscovery.this.listener != null) {
                    CastDiscovery.this.listener.onDeviceFound(dev);
                }
            }
        };
    }

    public void start() {
        if (running) return;
        running = true;
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
    }

    public void stop() {
        running = false;
        try {
            router.removeCallback(cb);
        } catch (Throwable ignore) {}
        if (listener != null) listener.onDone();
    }
}
