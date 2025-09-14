package com.example.minicast.dlna;

import android.util.Log;

import com.example.minicast.devices.DlnaDevice;
import com.example.minicast.devices.TargetDevice;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.net.*;
import java.util.*;
import javax.net.SocketFactory;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public class DlnaDiscovery {

  public interface Listener {
    void onDeviceFound(TargetDevice device);
    void onDone();
  }

  private static final String TAG = "DlnaDiscovery";
  private volatile boolean running = false;

  public void discoverAsync(Listener listener, int timeoutMs) {
    new Thread(() -> {
      running = true;
      try {
        Set<String> seen = new HashSet<>();

        // 1) SSDP M-SEARCH: MediaRenderer ara
        List<String> responses = ssdpSearch("urn:schemas-upnp-org:device:MediaRenderer:1", timeoutMs);

        for (String resp : responses) {
          if (!running) break;
          Map<String,String> headers = parseHeaders(resp);
          String location = headers.get("location");
          if (location == null) continue;
          // Tekrarlayanları geç
          if (!seen.add(location)) continue;

          try {
            // 2) Cihaz açıklamasını indir
            URL loc = new URL(location);
            String deviceXml = httpGet(loc);
            // 3) serviceList içinde AVTransport bul, controlURL çıkar
            DlnaDevice dev = parseDevice(deviceXml, loc);
            if (dev != null && listener != null) {
              listener.onDeviceFound(dev);
            }
          } catch (Exception e) {
            Log.w(TAG, "DLNA parse failed: " + e);
          }
        }
      } catch (Exception e) {
        Log.e(TAG, "SSDP error: " + e);
      } finally {
        running = false;
        if (listener != null) listener.onDone();
      }
    }).start();
  }

  public void stop() { running = false; }

  private List<String> ssdpSearch(String st, int timeoutMs) throws Exception {
    String req =
        "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: " + st + "\r\n\r\n";

    InetAddress group = InetAddress.getByName("239.255.255.250");
    int port = 1900;
    DatagramSocket socket = new DatagramSocket();
    socket.setReuseAddress(true);
    socket.setSoTimeout(1000);

    byte[] data = req.getBytes();
    DatagramPacket packet = new DatagramPacket(data, data.length, group, port);
    socket.send(packet);

    long end = System.currentTimeMillis() + timeoutMs;
    List<String> results = new ArrayList<>();

    byte[] buf = new byte[8192];
    while (System.currentTimeMillis() < end) {
      try {
        DatagramPacket resp = new DatagramPacket(buf, buf.length);
        socket.receive(resp);
        String s = new String(resp.getData(), 0, resp.getLength());
        results.add(s);
      } catch (SocketTimeoutException ignore) {}
    }
    socket.close();
    return results;
  }

  private Map<String,String> parseHeaders(String raw) {
    Map<String,String> map = new HashMap<>();
    String[] lines = raw.split("\r\n");
    for (String line : lines) {
      int i = line.indexOf(':');
      if (i > 0) {
        String k = line.substring(0, i).trim().toLowerCase(Locale.US);
        String v = line.substring(i+1).trim();
        map.put(k, v);
      }
    }
    return map;
  }

  private String httpGet(URL url) throws Exception {
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setConnectTimeout(2500);
    c.setReadTimeout(2500);
    try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) sb.append(line);
      return sb.toString();
    }
  }

  private DlnaDevice parseDevice(String xml, URL locationUrl) throws Exception {
    Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new java.io.ByteArrayInputStream(xml.getBytes()));
    doc.getDocumentElement().normalize();

    // Friendly name
    NodeList fr = doc.getElementsByTagName("friendlyName");
    String name = (fr.getLength() > 0) ? fr.item(0).getTextContent() : "DLNA Renderer";

    // Services
    NodeList services = doc.getElementsByTagName("service");
    String controlUrl = null;
    for (int i=0;i<services.getLength();i++) {
      Element s = (Element) services.item(i);
      String serviceType = getText(s, "serviceType");
      if (serviceType != null && serviceType.contains("AVTransport")) {
        controlUrl = getText(s, "controlURL");
        break;
      }
    }
    if (controlUrl == null) return null;

    String base = locationUrl.getProtocol() + "://" + locationUrl.getHost()
        + ((locationUrl.getPort() > 0) ? (":" + locationUrl.getPort()) : "");
    if (!controlUrl.startsWith("http")) {
      if (!controlUrl.startsWith("/")) controlUrl = "/" + controlUrl;
      controlUrl = base + controlUrl;
    }

    // device UDN
    NodeList udnList = doc.getElementsByTagName("UDN");
    String udn = (udnList.getLength() > 0) ? udnList.item(0).getTextContent() : UUID.randomUUID().toString();

    return new DlnaDevice(udn, name, controlUrl, base);
  }

  private String getText(Element parent, String tag) {
    NodeList l = parent.getElementsByTagName(tag);
    return (l.getLength() > 0) ? l.item(0).getTextContent() : null;
  }

  /* --- DLNA Control: SetAVTransportURI + Play --- */

  public static boolean setUriAndPlay(String controlUrl, String mediaUrl) {
    try {
      // 1) SetAVTransportURI
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

      // 2) Play
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
      Log.e(TAG, "DLNA play failed: " + e);
      return false;
    }
  }

  private static boolean soapPost(String controlUrl, String action, String body) throws Exception {
    URL url = new URL(controlUrl);
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
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
    return (code >= 200 && code < 300);
  }

  private static String xmlEscape(String s) {
    return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("\"","&quot;").replace("'","&apos;");
  }
}
