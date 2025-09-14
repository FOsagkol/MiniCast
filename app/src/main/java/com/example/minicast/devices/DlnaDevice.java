package com.example.minicast.devices;

public class DlnaDevice implements TargetDevice {
  private final String id;
  private final String name;
  private final String controlUrl; // AVTransport controlURL
  private final String endpointBase; // device description base URL (opsiyonel debug için)

  public DlnaDevice(String id, String name, String controlUrl, String endpointBase) {
    this.id = id;
    this.name = name;
    this.controlUrl = controlUrl;
    this.endpointBase = endpointBase;
  }

  public String getControlUrl() { return controlUrl; }
  public String getEndpointBase() { return endpointBase; }

  @Override public String getId() { return id; }

  @Override public String getName() { return name; }

  @Override public DeviceType getType() { return DeviceType.DLNA; }
}
