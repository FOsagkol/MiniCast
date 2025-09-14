package com.example.minicast.devices;

import java.net.URL;

public class DlnaDevice implements TargetDevice {
    private final String id;              // UDN ya da LOCATION fallback
    private final String name;            // friendlyName
    private final URL descriptionUrl;     // LOCATION (device description XML)
    private final URL controlUrl;         // AVTransport controlURL (absolute)

    public DlnaDevice(String id, String name, URL descriptionUrl, URL controlUrl) {
        this.id = id;
        this.name = name;
        this.descriptionUrl = descriptionUrl;
        this.controlUrl = controlUrl;
    }

    @Override public String getId()        { return id; }
    @Override public String getName()      { return name; }
    public URL getDescriptionUrl()         { return descriptionUrl; }
    public URL getControlUrl()             { return controlUrl; }

    @Override public DeviceType getType()  { return DeviceType.DLNA; }
}
