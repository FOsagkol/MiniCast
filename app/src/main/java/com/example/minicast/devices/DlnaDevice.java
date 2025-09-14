package com.example.minicast.devices;

import java.net.URL;

public class DlnaDevice {
    public final String usn;
    public final String friendlyName;
    public final URL locationUrl;
    public final URL controlUrl;

    public DlnaDevice(String usn, String friendlyName, URL locationUrl, URL controlUrl) {
        this.usn = usn;
        this.friendlyName = friendlyName;
        this.locationUrl = locationUrl;
        this.controlUrl = controlUrl;
    }

    @Override public String toString() { return friendlyName != null ? friendlyName : usn; }
}
