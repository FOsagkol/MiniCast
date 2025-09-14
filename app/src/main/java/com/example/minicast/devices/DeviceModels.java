package com.example.minicast.devices;

public interface TargetDevice {
  String getId();
  String getName();
  DeviceType getType(); // CAST or DLNA
  enum DeviceType { CAST, DLNA }
}
