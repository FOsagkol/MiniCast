package com.example.minicast.devices;

import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;

public class DlnaDiscovery {

    public interface Listener {
        void onDeviceFound(DlnaDevice device);
        void onDone();
    }

    private static final String TAG = "DlnaDiscovery";
    private volatile boolean running = false;
    private final WifiManager.MulticastLock mlock;

    public DlnaDiscovery(WifiManager wifiManager) {
        // MulticastLock: discovery penceresi boyunca açık kalacak
        this.mlock = wifiManager.createMulticastLock("minicast-ssdp");
        this.mlock.setReferenceCounted(false);
    }

    public void cancel() { running = false; }

    public void discoverAsync(Listener listener, int timeoutMs) {
        new Thread(() -> {
            running = true;
            mlock.acquire();
            try {
                Set<String> seen = new HashSet<>();
                List<String> targets = Arrays.asList(
                        "urn:schemas-upnp-org:device:MediaRenderer:1",
                        "urn:schemas-upnp-org:service:AVTransport:1",
                        "ssdp:all"
                );

                long end = System.currentTimeMillis() + timeoutMs; // ~8000ms önerilir

                // NOTIFY ve M-SEARCH yanıtlarını dinlemek için multicast soket
                InetAddress group = InetAddress.getByName("239.255.255.250");
                MulticastSocket mcast = new MulticastSocket(1900);
                mcast.setReuseAddress(true);
                // Android’de basit joinGroup yeterli
                mcast.joinGroup(group);
                mcast.setSoTimeout(1000);
                mcast.setTimeToLive(2);

                // 3 tur M-SEARCH
                for (int round = 0; round < 3 && System.currentTimeMillis() < end && running; round++) {
                    for (String st : targets) {
                        if (!running) break;
                        sendMSearch(st, 2); // MX=2
                    }
                    collectSsdpResponses(mcast, listener, seen, end);
                }

                // Son dinleme
                collectSsdpResponses(mcast, listener, seen, end);
                try {
                    mcast.leaveGroup(group);
                } catch (Throwable ignore) {}
                mcast.close();

            } catch (Exception e) {
                Log.e(TAG, "SSDP error: ", e);
            } finally {
                running = false;
                if (listener != null) listener.onDone();
                try { mlock.release(); } catch (Throwable ignore) {}
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
        try {
            socket.setTrafficClass(0x10);
        } catch (Throwable ignore) {}
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
        while (System.currentTimeMillis() < end && running) {
            try {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                mcast.receive(resp);
                String s = new String(resp.getData(), 0, resp.getLength());

                Map<String,String> headers = parseHeaders(s);
                String nts = headers.get("nts");
                if (nts != null && !"ssdp:alive".equalsIgnoreCase(nts)) {
                    continue; // 'byebye' vb. atla
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

    private Map<String,String> parseHeaders(String raw) {
        Map<String,String> h = new HashMap<>();
        String[] lines = raw.split("\r?\n");
        for (String line : lines) {
            int i = line.indexOf(':');
            if (i > 0) {
                String k = line.substring(0, i).trim().toLowerCase(Locale.US);
                String v = line.substring(i + 1).trim();
                h.put(k, v);
            }
        }
        return h;
    }

    private String httpGet(URL url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // Çok basit XML parse (gerçek projede XML parser önerilir)
    private DlnaDevice parseDevice(String xml, URL location) {
        try {
            String fn = extract(xml, "<friendlyName>", "</friendlyName>");
            if (fn == null) fn = "DLNA Cihazı";
            // AVTransport control URL örnek çıkarımı (basitleştirilmiş)
            String control = extract(xml, "<controlURL>", "</controlURL>");
            URL controlUrl = control != null ? new URL(location, control) : null;
            return new DlnaDevice(location.toString(), fn, location, controlUrl);
        } catch (Exception e) {
            Log.w(TAG, "parseDevice failed: " + e);
            return null;
        }
    }

    private String extract(String xml, String a, String b) {
        int i = xml.indexOf(a);
        if (i < 0) return null;
        int j = xml.indexOf(b, i + a.length());
        if (j < 0) return null;
        return xml.substring(i + a.length(), j).trim();
    }
}
