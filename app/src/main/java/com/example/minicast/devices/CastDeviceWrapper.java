package com.example.minicast.devices;

import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.CastDevice;

import java.util.Objects;

/** Cast cihazı için sarmalayıcı: CastDevice + (opsiyonel) RouteInfo */
public class CastDeviceWrapper implements TargetDevice {
    private final CastDevice device;
    private final MediaRouter.RouteInfo route; // selectRoute için gerekli

    /** Tercih edilen kurucu: Discovery'den hem CastDevice hem RouteInfo verin. */
    public CastDeviceWrapper(CastDevice device, MediaRouter.RouteInfo route) {
        this.device = device;
        this.route = route;
    }

    /** Geri uyumluluk: Eski kod sadece CastDevice veriyorsa da derlensin. */
    public CastDeviceWrapper(CastDevice device) {
        this(device, null);
    }

    public CastDevice getDevice() { return device; }

    /** MainActivity: mediaRouter.selectRoute(wrapper.getRoute()) ile kullanın. */
    public MediaRouter.RouteInfo getRoute() { return route; }

    @Override
    public String getId() {
        if (device != null && device.getDeviceId() != null) return device.getDeviceId();
        return route != null ? route.getId() : "cast-unknown";
    }

    @Override
    public String getName() {
        if (device != null && device.getFriendlyName() != null && !device.getFriendlyName().isEmpty()) {
            return device.getFriendlyName();
        }
        return route != null ? String.valueOf(route.getName()) : "Chromecast";
    }

    @Override
    public DeviceType getType() { return DeviceType.CAST; }

    @Override
    public String toString() { return getName(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CastDeviceWrapper)) return false;
        CastDeviceWrapper that = (CastDeviceWrapper) o;
        return Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() { return Objects.hash(getId()); }
}
