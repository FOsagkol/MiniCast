package com.example.minicast.devices;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class DlnaDiscovery {

    public interface Listener {
        void onDeviceFound(DlnaDevice device);
        void onFinished(List<DlnaDevice> devices);
        void onError(Exception e);
    }

    private static final String MSEARCH =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: upnp:rootdevice\r\n\r\n";

    public static void discover(Context ctx, Listener listener) {
        new Thread(() -> {
            List<DlnaDevice> found = new ArrayList<>();
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    lock = wm.createMulticastLock("minicast-ssdp");
                    lock.setReferenceCounted(true);
                    lock.acquire();
                }

                // UDP üzerinden M-SEARCH yayınla
                try (DatagramSocket sock = new DatagramSocket()) {
                    sock.setReuseAddress(true);
                    sock.setSoTimeout(2000);
                    byte[] data = MSEARCH.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket dp = new DatagramPacket(
                            data, data.length, InetAddress.getByName("239.255.255.250"), 1900);
                    for (int i = 0; i < 3; i++) sock.send(dp);

                    // Cevapları topla
                    byte[] buf = new byte[2048];
                    long until = System.currentTimeMillis() + 2500;
                    while (System.currentTimeMillis() < until) {
                        DatagramPacket resp = new DatagramPacket(buf, buf.length);
                        try {
                            sock.receive(resp);
                            String s = new String(resp.getData(), 0, resp.getLength());
                            URL loc = parseLocation(s);
                            if (loc != null) {
                                DlnaDevice dev = fetchDevice(loc);
                                if (dev != null) {
                                    found.add(dev);
                                    if (listener != null) listener.onDeviceFound(dev);
                                }
                            }
                        } catch (Exception ignore) { /* timeout vb. */ }
                    }
                }

                if (listener != null) listener.onFinished(found);
            } catch (Exception e) {
                if (listener != null) listener.onError(e);
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
            }
        }).start();
    }

    private static URL parseLocation(String ssdpResponse) {
        for (String line : ssdpResponse.split("\r\n")) {
            int ix = line.toLowerCase().indexOf("location:");
            if (ix == 0) {
                String url = line.substring("location:".length()).trim();
                try { return new URL(url); } catch (Exception ignore) {}
            }
        }
        return null;
    }

    private static DlnaDevice fetchDevice(URL location) {
        try {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(location.openStream()))) {
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln);
                String xml = sb.toString();
                String fn = extract(xml, "<friendlyName>", "</friendlyName>");
                String ctrl = extract(xml, "<controlURL>", "</controlURL>");
                URL ctrlUrl = ctrl != null ? new URL(location, ctrl) : null;
                return new DlnaDevice(location.toString(), fn, location, ctrlUrl);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String extract(String xml, String start, String end) {
        int a = xml.indexOf(start);
        if (a < 0) return null;
        int b = xml.indexOf(end, a + start.length());
        if (b < 0) return null;
        return xml.substring(a + start.length(), b).trim();
    }

    // (opsiyonel) Basit Play örneği (AVTransport:SetAVTransportURI)
    public static boolean setUriAndPlay(URL controlUrl, String mediaUrl) {
        try {
            String soap =
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body>"
                    + "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                    + "<InstanceID>0</InstanceID>"
                    + "<CurrentURI>"+ mediaUrl +"</CurrentURI>"
                    + "<CurrentURIMetaData></CurrentURIMetaData>"
                    + "</u:SetAVTransportURI>"
                    + "</s:Body></s:Envelope>";

            java.net.HttpURLConnection c = (java.net.HttpURLConnection) controlUrl.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"");
            try (OutputStream os = c.getOutputStream()) {
                os.write(soap.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }
}
