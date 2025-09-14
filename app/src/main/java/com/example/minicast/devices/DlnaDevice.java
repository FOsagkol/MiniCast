package com.example.minicast.devices;

import java.net.URL;

public class DlnaDevice {
    private final String usn;
    private final String friendlyName;
    private final URL locationUrl;
    private final URL controlUrl;

    public DlnaDevice(String usn, String friendlyName, URL locationUrl, URL controlUrl) {
        this.usn = usn;
        this.friendlyName = friendlyName;
        this.locationUrl = locationUrl;
        this.controlUrl = controlUrl;
    }

    public String getUsn() { return usn; }
    public String getFriendlyName() { return friendlyName; }
    public URL getLocationUrl() { return locationUrl; }
    public URL getControlUrl() { return controlUrl; }

    @Override public String toString() { return friendlyName != null ? friendlyName : usn; }
}
