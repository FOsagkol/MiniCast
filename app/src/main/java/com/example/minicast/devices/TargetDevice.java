package com.example.minicast.devices;

public interface TargetDevice {
    String getId();
    String getName();

    DeviceType getType();

    enum DeviceType { CAST, DLNA }
}
