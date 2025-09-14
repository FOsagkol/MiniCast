package com.example.minicast.devices;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class DlnaDiscovery {

    public interface Listener {
        void onDeviceFound(DlnaDevice device);
        void onDone();
        void onError(Exception e);
    }

    private static final String TAG = "DlnaDiscovery";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private static String msearch(String st, int mx) {
        return "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: " + mx + "\r\n" +
                "ST: " + st + "\r\n\r\n";
    }

    /** Parametresiz, statik ve thread’li keşif — MainActivity burayı çağırıyor. */
    public static void discover(Context ctx, Listener listener) {
        new Thread(() -> {
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    lock = wm.createMulticastLock("minicast-ssdp");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }

                String[] targets = new String[] {
                        "urn:schemas-upnp-org:device:MediaRenderer:1",
                        "urn:schemas-upnp-org:service:AVTransport:1",
                        "ssdp:all"
                };

                Set<String> seen = new HashSet<>();
                try (DatagramSocket sock = new DatagramSocket()) {
                    sock.setReuseAddress(true);
                    sock.setSoTimeout(1500);

                    byte[] buf = new byte[8192];

                    long end = System.currentTimeMillis() + 8_000;
                    int round = 0;
                    while (System.currentTimeMillis() < end) {
                        // Her tur hedefleri yolla
                        if (round < 3) {
                            for (String st : targets) {
                                byte[] req = msearch(st, 2).getBytes(StandardCharsets.UTF_8);
                                DatagramPacket dp = new DatagramPacket(
                                        req, req.length, InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
                                sock.send(dp);
                            }
                            round++;
                        }

                        // Yanıtları topla (timeout’la döngü)
                        DatagramPacket resp = new DatagramPacket(buf, buf.length);
                        try {
                            sock.receive(resp);
                            String msg = new String(resp.getData(), 0, resp.getLength());
                            String location = headerValue(msg, "location");
                            if (location == null) continue;
                            if (!seen.add(location)) continue;

                            try {
                                URL locUrl = new URL(location);
                                String xml = httpGet(locUrl);
                                DlnaDevice dev = parseDevice(xml, locUrl);
                                if (dev != null && listener != null) listener.onDeviceFound(dev);
                            } catch (Exception ex) {
                                Log.w(TAG, "parse error: " + ex.getMessage());
                            }
                        } catch (Exception ignore) {
                            // timeout vb.
                        }
                    }
                }
                if (listener != null) listener.onDone();
            } catch (Exception e) {
                if (listener != null) listener.onError(e);
            } finally {
                // lock release
                try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable ignore) {}
            }
        }).start();
    }

    private static String headerValue(String raw, String keyLower) {
        String[] lines = raw.split("\r?\n");
        for (String line : lines) {
            int i = line.indexOf(':');
            if (i > 0) {
                String k = line.substring(0, i).trim().toLowerCase(Locale.US);
                if (k.equals(keyLower)) return line.substring(i + 1).trim();
            }
        }
        return null;
    }

    private static String httpGet(URL url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** Philips-dostu parse: AVTransport controlURL’ü esnek yakalar, absolute URL’e çevirir. */
    private static DlnaDevice parseDevice(String xml, URL locationUrl) {
        String friendly = extract(xml, "<friendlyName>", "</friendlyName>");
        if (friendly == null) friendly = extract(xml, "<device:friendlyName>", "</device:friendlyName>");
        if (friendly == null || friendly.isEmpty()) friendly = "DLNA Cihazı";

        String udn = extract(xml, "<UDN>", "</UDN>");
        if (udn == null) udn = extract(xml, "<device:UDN>", "</device:UDN>");
        if (udn == null || udn.isEmpty()) udn = locationUrl.toString();

        String controlUrl = null;
        int idx = 0;
        while (true) {
            int a = xml.indexOf("<service", idx);
            if (a < 0) break;
            int b = xml.indexOf("</service>", a);
            if (b < 0) break;
            String svc = xml.substring(a, b);
            String type = extract(svc, "<serviceType>", "</serviceType>");
            if (type == null) type = extract(svc, "<service:serviceType>", "</service:serviceType>");
            String cUrl = extract(svc, "<controlURL>", "</controlURL>");
            if (cUrl == null) cUrl = extract(svc, "<service:controlURL>", "</service:controlURL>");

            if (type != null && type.toLowerCase(Locale.US).contains("avtransport")) {
                controlUrl = (cUrl != null ? cUrl.trim() : null);
                break;
            }
            idx = b + 10;
        }
        if (controlUrl == null) {
            // Loose fallback
            controlUrl = findLooseControlUrlForAvTransport(xml);
        }

        URL abs = null;
        if (controlUrl != null && !controlUrl.isEmpty()) {
            try { abs = new URL(locationUrl, controlUrl); } catch (Exception ignore) {}
        }

        Log.d(TAG, "Found device: " + friendly + " (" + udn + ")");
        Log.d(TAG, "AVTransport controlURL: " + (abs != null ? abs : "NONE"));

        return new DlnaDevice(udn, friendly, locationUrl, abs);
    }

    private static String findLooseControlUrlForAvTransport(String xml) {
        int pos = 0;
        while (true) {
            int a = xml.indexOf("<service", pos);
            if (a < 0) break;
            int b = xml.indexOf("</service>", a);
            if (b < 0) break;
            String svc = xml.substring(a, b);
            if (svc.toLowerCase(Locale.US).contains("avtransport")) {
                String cUrl = extract(svc, "<controlURL>", "</controlURL>");
                if (cUrl == null) cUrl = extract(svc, "<service:controlURL>", "</service:controlURL>");
                if (cUrl != null && !cUrl.trim().isEmpty()) return cUrl.trim();
            }
            pos = b + 10;
        }
        return null;
    }

    private static String extract(String xml, String a, String b) {
        int i = xml.indexOf(a);
        if (i < 0) return null;
        int j = xml.indexOf(b, i + a.length());
        if (j < 0) return null;
        return xml.substring(i + a.length(), j).trim();
    }

    /** Basit AVTransport SetURI + Play */
    public static boolean setUriAndPlay(URL controlUrl, String mediaUrl) {
        if (controlUrl == null || mediaUrl == null || mediaUrl.isEmpty()) return false;
        try {
            // SetAVTransportURI
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

            // Play
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
            os.write(body.getBytes(StandardCharsets.UTF_8));
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
