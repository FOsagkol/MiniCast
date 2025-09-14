package com.example.minicast.devices;

import android.content.Context;
import android.util.Log;
import androidx.mediarouter.media.MediaRouter;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter.Callback;
import androidx.mediarouter.media.MediaRouter.RouteInfo;
import com.google.android.gms.cast.CastMediaControlIntent;
import java.util.Locale;

public class CastDiscovery {
    public interface Listener {
        void onDeviceFound(TargetDevice device);
        void onDone();
    }

    private final MediaRouter router;
    private final MediaRouteSelector selector;
    private final Listener listener;
    private final Callback cb;
    private boolean running = false;

    public CastDiscovery(Context ctx, Listener listener) {
        this.listener = listener;
        this.router = MediaRouter.getInstance(ctx.getApplicationContext());
        this.selector = new MediaRouteSelector.Builder()
                // tüm alıcılar: "*" (sadece DEFAULT_MEDIA_RECEIVER değil)
                .addControlCategory(CastMediaControlIntent.categoryForCast("*"))
                .build();

        this.cb = new Callback() {
            @Override public void onRouteAdded(MediaRouter router, RouteInfo route) {
                if (!running) return;
                CastDeviceWrapper dev = new CastDeviceWrapper(route.getId(), safeName(route.getName()));
                if (listener != null) listener.onDeviceFound(dev);
            }
            @Override public void onRouteChanged(MediaRouter router, RouteInfo route) {
                // no-op
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
        try { router.removeCallback(cb); } catch (Throwable ignore) {}
        if (listener != null) listener.onDone();
    }

    private static String safeName(CharSequence name) {
        return name == null ? "Cast Cihazı" : name.toString();
    }
}
