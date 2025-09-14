package com.example.minicast.devices;

import com.google.android.gms.cast.CastDevice;

public class CastDeviceWrapper implements TargetDevice {
  private final CastDevice device;

  public CastDeviceWrapper(CastDevice device) { this.device = device; }

  public CastDevice getDevice() { return device; }

  @Override public String getId() { return device.getDeviceId(); }

  @Override public String getName() { return device.getFriendlyName(); }

  @Override public DeviceType getType() { return DeviceType.CAST; }
}
