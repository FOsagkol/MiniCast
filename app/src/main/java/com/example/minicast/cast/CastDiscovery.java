package com.example.minicast.cast;

import android.content.Context;
import android.util.Log;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import com.example.minicast.devices.CastDeviceWrapper;
import com.example.minicast.devices.TargetDevice;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;

public class CastDiscovery {
  public interface Listener {
    void onDeviceFound(TargetDevice device);
    void onDone();
  }

  private static final String TAG = "CastDiscovery";
  private final MediaRouter mediaRouter;
  private final MediaRouter.Callback callback;
  private final MediaRouteSelector selector;

  public CastDiscovery(Context ctx, Listener listener) {
    mediaRouter = MediaRouter.getInstance(ctx.getApplicationContext());
    selector = new MediaRouteSelector.Builder()
    .addControlCategory(
        CastMediaControlIntent.categoryForCast("*") // tüm alıcılar
    )
    .build();

    callback = new MediaRouter.Callback() {
      @Override public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo route) {
        CastDevice dev = CastDevice.getFromBundle(route.getExtras());
        if (dev != null && listener != null) listener.onDeviceFound(new CastDeviceWrapper(dev));
      }
      @Override public void onProviderAdded(MediaRouter router, MediaRouter.ProviderInfo provider) {}
      @Override public void onRouteChanged(MediaRouter router, MediaRouter.RouteInfo route) {}
    };
  }

  public void start() {
    mediaRouter.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
  }

  public void stop() {
    mediaRouter.removeCallback(callback);
  }
}
