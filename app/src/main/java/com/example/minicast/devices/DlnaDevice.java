package com.example.minicast.devices;
public void discoverAsync(Listener listener, int timeoutMs) {
  new Thread(() -> {
    running = true;
    try {
      Set<String> seen = new HashSet<>();
      List<String> targets = Arrays.asList(
          "urn:schemas-upnp-org:device:MediaRenderer:1",
          "urn:schemas-upnp-org:service:AVTransport:1",
          "ssdp:all"
      );

      long end = System.currentTimeMillis() + timeoutMs; // ~8000ms öneririm
      // 1) Multicast socket – NOTIFY dinlemek için
      InetAddress group = InetAddress.getByName("239.255.255.250");
      MulticastSocket mcast = new MulticastSocket(1900);
      mcast.setReuseAddress(true);
      mcast.joinGroup(new InetSocketAddress(group, 1900), NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));
      mcast.setSoTimeout(1000);
      mcast.setTimeToLive(2);

      // 2) M-SEARCH turları
      for (int round = 0; round < 3 && System.currentTimeMillis() < end; round++) {
        for (String st : targets) {
          if (!running) break;
          sendMSearch(st, 2); // MX=2
        }
        // gelen yanıtları topla + NOTIFY dinle
        collectSsdpResponses(mcast, listener, seen, end);
      }

      // son kez dinleme
      collectSsdpResponses(mcast, listener, seen, end);
      mcast.leaveGroup(new InetSocketAddress(group, 1900), NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));
      mcast.close();

    } catch (Exception e) {
      Log.e(TAG, "SSDP error: " + e);
    } finally {
      running = false;
      if (listener != null) listener.onDone();
    }
  }).start();
}

private void sendMSearch(String st, int mx) throws Exception {
  String req = "M-SEARCH * HTTP/1.1\r\n" +
      "HOST: 239.255.255.250:1900\r\n" +
      "MAN: \"ssdp:discover\"\r\n" +
      "MX: " + mx + "\r\n" +
      "ST: " + st + "\r\n\r\n";

  DatagramSocket socket = new DatagramSocket();
  socket.setReuseAddress(true);
  socket.setSoTimeout(1000);
  // TTL=2
  socket.setTrafficClass(0x10);
  DatagramPacket packet = new DatagramPacket(
      req.getBytes(),
      req.length(),
      InetAddress.getByName("239.255.255.250"),
      1900
  );
  socket.send(packet);
  socket.close();
}

private void collectSsdpResponses(MulticastSocket mcast, Listener listener, Set<String> seen, long end) {
  byte[] buf = new byte[8192];
  while (System.currentTimeMillis() < end) {
    try {
      DatagramPacket resp = new DatagramPacket(buf, buf.length);
      mcast.receive(resp);
      String s = new String(resp.getData(), 0, resp.getLength());

      // Hem yanıtlar hem NOTIFY'ler gelir; her ikisinde de LOCATION var
      Map<String,String> headers = parseHeaders(s);
      String nts = headers.get("nts");
      if (nts != null && !nts.equalsIgnoreCase("ssdp:alive")) {
        continue; // bye bye / update'leri at
      }
      String location = headers.get("location");
      if (location == null || !seen.add(location)) continue;

      try {
        URL loc = new URL(location);
        String deviceXml = httpGet(loc);
        DlnaDevice dev = parseDevice(deviceXml, loc);
        if (dev != null && listener != null) listener.onDeviceFound(dev);
      } catch (Exception e) {
        Log.w(TAG, "DLNA parse failed: " + e);
      }
    } catch (SocketTimeoutException ignore) {
      break;
    } catch (Exception e) {
      Log.w(TAG, "multicast receive error: " + e);
      break;
    }
  }
}
