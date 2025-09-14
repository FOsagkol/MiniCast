package com.example.minicast.devices;

import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.*;

/**
 * DLNA/UPnP keşfi (SSDP) + AVTransport kontrolü
 */
public class DlnaDiscovery {

    public interface Listener {
        void onDeviceFound(TargetDevice device);
        void onDone();
    }

    private static final String TAG = "DlnaDiscovery";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int    SSDP_PORT = 1900;

    private final WifiManager.MulticastLock mlock;
    private volatile boolean running = false;

    public DlnaDiscovery(WifiManager wifiManager) {
        this.mlock = wifiManager.createMulticastLock("minicast-ssdp");
        this.mlock.setReferenceCounted(false);
    }

    public void cancel() { running = false; }

    public void discoverAsync(Listener listener, int timeoutMs) {
        new Thread(() -> {
            running = true;
            try { mlock.acquire(); } catch (Throwable ignore) {}

            MulticastSocket mcast = null;
            try {
                final long end = System.currentTimeMillis() + timeoutMs;
                final Set<String> seenLocations = new HashSet<>();
                final List<String> targets = Arrays.asList(
                        "urn:schemas-upnp-org:device:MediaRenderer:1",
                        "urn:schemas-upnp-org:service:AVTransport:1",
                        "ssdp:all"
                );

                InetAddress group = InetAddress.getByName(SSDP_ADDR);
                mcast = new MulticastSocket(SSDP_PORT);
                mcast.setReuseAddress(true);
                mcast.joinGroup(group);
                mcast.setSoTimeout(1000);
                try { mcast.setTimeToLive(2); } catch (Throwable ignore) {}

                for (int round = 0; round < 3 && running && System.currentTimeMillis() < end; round++) {
                    for (String st : targets) {
                        if (!running) break;
                        sendMSearch(st, 2);
                    }
                    collectSsdp(mcast, listener, seenLocations, end);
                }
                collectSsdp(mcast, listener, seenLocations, end);

            } catch (Exception e) {
                Log.e(TAG, "SSDP error: ", e);
            } finally {
                running = false;
                if (mcast != null) {
                    try { mcast.leaveGroup(InetAddress.getByName(SSDP_ADDR)); } catch (Throwable ignore) {}
                    try { mcast.close(); } catch (Throwable ignore) {}
                }
                try { mlock.release(); } catch (Throwable ignore) {}
                if (listener != null) listener.onDone();
            }
        }).start();
    }

    private void sendMSearch(String st, int mx) throws Exception {
        String req =
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: " + mx + "\r\n" +
                "ST: " + st + "\r\n\r\n";

        DatagramSocket socket = new DatagramSocket();
        socket.setReuseAddress(true);
        socket.setSoTimeout(1000);
        DatagramPacket packet = new DatagramPacket(
                req.getBytes(),
                req.length(),
                InetAddress.getByName(SSDP_ADDR),
                SSDP_PORT
        );
        socket.send(packet);
        socket.close();
    }

    private void collectSsdp(MulticastSocket mcast, Listener listener, Set<String> seen, long end) {
        byte[] buf = new byte[8192];
        while (running && System.currentTimeMillis() < end) {
            try {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                mcast.receive(resp);
                String msg = new String(resp.getData(), 0, resp.getLength());

                Map<String,String> h = parseHeaders(msg);
                String nts = h.get("nts");
                if (nts != null && !"ssdp:alive".equalsIgnoreCase(nts)) continue;
                String location = h.get("location");
                if (location == null) continue;
                if (!seen.add(location)) continue;

                try {
                    URL locUrl = new URL(location);
                    String deviceXml = httpGet(locUrl);
                    DlnaDevice dev = parseDevice(deviceXml, locUrl);
                    if (dev != null && listener != null) listener.onDeviceFound(dev);
                } catch (Exception e) {
                    Log.w(TAG, "DLNA parse failed: " + e.getMessage());
                }

            } catch (SocketTimeoutException ignore) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "multicast receive error: " + e.getMessage());
                break;
            }
        }
    }

    private Map<String,String> parseHeaders(String raw) {
        Map<String,String> map = new HashMap<>();
        String[] lines = raw.split("\r?\n");
        for (String line : lines) {
            int i = line.indexOf(':');
            if (i > 0) {
                String k = line.substring(0, i).trim().toLowerCase(Locale.US);
                String v = line.substring(i + 1).trim();
                map.put(k, v);
            }
        }
        return map;
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

    /** ---- Philips uyumlu parseDevice ---- */
    private DlnaDevice parseDevice(String xml, URL locationUrl) {
        try {
            String friendly = extract(xml, "<friendlyName>", "</friendlyName>");
            if (friendly == null) friendly = extract(xml, "<device:friendlyName>", "</device:friendlyName>");
            if (friendly == null || friendly.isEmpty()) friendly = "DLNA Cihazı";

            String udn = extract(xml, "<UDN>", "</UDN>");
            if (udn == null) udn = extract(xml, "<device:UDN>", "</device:UDN>");
            if (udn == null || udn.isEmpty()) udn = locationUrl.toString();

            String controlUrl = null;
            int idx = 0;
            while (true) {
                int svcStart = xml.indexOf("<service", idx);
                if (svcStart < 0) break;
                int svcEnd = xml.indexOf("</service>", svcStart);
                if (svcEnd < 0) break;
                String svc = xml.substring(svcStart, svcEnd);
                String type = extract(svc, "<serviceType>", "</serviceType>");
                if (type == null) type = extract(svc, "<service:serviceType>", "</service:serviceType>");
                String cUrl = extract(svc, "<controlURL>", "</controlURL>");
                if (cUrl == null) cUrl = extract(svc, "<service:controlURL>", "</service:controlURL>");

                if (type != null && type.toLowerCase(Locale.US).contains("avtransport")) {
                    controlUrl = (cUrl != null) ? cUrl.trim() : null;
                    break;
                }
                idx = svcEnd + 10;
            }

            if (controlUrl == null) {
                controlUrl = findLooseControlUrlForAvTransport(xml);
            }

            URL controlAbs = null;
            if (controlUrl != null && !controlUrl.isEmpty()) {
                try { controlAbs = new URL(locationUrl, controlUrl.trim()); } catch (Exception ignore) {}
            }

            Log.d(TAG, "Found device: " + friendly + " (" + udn + ")");
            Log.d(TAG, "AVTransport controlURL: " + (controlAbs != null ? controlAbs : "NONE"));

            return new DlnaDevice(udn, friendly, locationUrl, controlAbs);

        } catch (Exception e) {
            Log.w(TAG, "parseDevice failed: " + e.getMessage());
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

    private String findLooseControlUrlForAvTransport(String xml) {
        int pos = 0;
        while (true) {
            int svcStart = xml.indexOf("<service", pos);
            if (svcStart < 0) break;
            int svcEnd = xml.indexOf("</service>", svcStart);
            if (svcEnd < 0) break;
            String svc = xml.substring(svcStart, svcEnd);
            if (svc.toLowerCase(Locale.US).contains("avtransport")) {
                String cUrl = extract(svc, "<controlURL>", "</controlURL>");
                if (cUrl == null) cUrl = extract(svc, "<service:controlURL>", "</service:controlURL>");
                if (cUrl != null && !cUrl.trim().isEmpty()) return cUrl.trim();
            }
            pos = svcEnd + 10;
        }
        return null;
    }

    public static boolean setUriAndPlay(URL controlUrl, String mediaUrl) {
        if (controlUrl == null || mediaUrl == null || mediaUrl.isEmpty()) return false;
        try {
            String setBody =
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                  + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                  + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                  + "<s:Body>"
                  + "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                  + "<InstanceID>0</InstanceID>"
                  + "<CurrentURI>" + xmlEscape(mediaUrl) + "</CurrentURI>"
                  + "<CurrentURIMetaData></CurrentURIMetaData>"
                  + "</u:SetAVTransportURI>"
                  + "</s:Body></s:Envelope>";

            if (!soapPost(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", setBody))
                return false;

            String playBody =
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                  + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                  + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                  + "<s:Body>"
                  + "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                  + "<InstanceID>0</InstanceID>"
                  + "<Speed>1</Speed>"
                  + "</u:Play>"
                  + "</s:Body></s:Envelope>";

            return soapPost(controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", playBody);

        } catch (Exception e) {
            Log.e(TAG, "DLNA play failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean soapPost(URL controlUrl, String action, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) controlUrl.openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + action + "\"");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        c.disconnect();
        return code >= 200 && code < 300;
    }

    private static String xmlEscape(String s) {
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;")
                .replace("'","&apos;");
    }
            }
