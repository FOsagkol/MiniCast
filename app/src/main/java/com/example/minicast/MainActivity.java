private static final String TEST_URL = "https://filesamples.com/samples/video/mp4/sample_960x400_ocean_with_audio.mp4";
private static final String TEST_MIME = "video/mp4";


private void showRoutePicker() {
    final CharSequence[] items = new CharSequence[]{
            "DLNA ile gönder (test klip)",
            "Ekranı yansıt (Smart View)",
            "Chromecast"
    };
    new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Nasıl oynatılsın?")
            .setItems(items, (d, which) -> {
                if (which == 0) startDlnaTestFlow();
                else if (which == 1) openSmartViewSettings();
                else openCastChooser();
            })
            .setNegativeButton("İptal", null)
            .show();
}

private void startDlnaTestFlow() {
    Toast.makeText(this, "DLNA cihazları aranıyor…", Toast.LENGTH_SHORT).show();
    new Thread(() -> {
        try {
            List<DlnaDevice> list = DlnaScanner.scanAndFilterDmrs(3200);
            runOnUiThread(() -> {
                if (list.isEmpty()) {
                    Toast.makeText(this, "Uygun DLNA MediaRenderer bulunamadı. Smart View deneyin.", Toast.LENGTH_LONG).show();
                    return;
                }
                showDlnaPickerForTest(list);
            });
        } catch (Throwable e) {
            dbg("DLNA scan error", e);
            runOnUiThread(() -> Toast.makeText(this, "DLNA tarama hatası.", Toast.LENGTH_LONG).show());
        }
    }).start();
}

private void showDlnaPickerForTest(List<DlnaDevice> devices) {
    String[] items = new String[devices.size()];
    for (int i = 0; i < devices.size(); i++) items[i] = devices.get(i).displayName();
    new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("DLNA Aygıtı Seç (önce test klip)")
            .setItems(items, (d, which) -> {
                DlnaDevice sel = devices.get(which);
                Toast.makeText(this, "Seçildi: " + sel.displayName(), Toast.LENGTH_SHORT).show();
                playTestClip(sel);
            })
            .setNegativeButton("İptal", null)
            .show();
}

private void playTestClip(DlnaDevice dev) {
    Toast.makeText(this, "TV’ye test videosu gönderiliyor…", Toast.LENGTH_SHORT).show();
    new Thread(() -> {
        try {
            DlnaControl ctl = DlnaControl.fromDevice(dev);
            boolean ok = (ctl != null) && ctl.playUrl(TEST_URL, TEST_MIME);
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this, "Tamam! TV’de test video oynuyor.", Toast.LENGTH_LONG).show();
                    // Buradan sonra: sayfa içi video yakalama/push aşamasına geçeceğiz (ayrı adımda ekleyeceğiz).
                } else {
                    Toast.makeText(this, "OLMADI: Cihaz AVTransport desteklemiyor ya da reddetti.", Toast.LENGTH_LONG).show();
                }
            });
        } catch (Throwable e) {
            dbg("playTestClip error", e);
            runOnUiThread(() -> Toast.makeText(this, "Push sırasında hata.", Toast.LENGTH_LONG).show());
        }
    }).start();
}

private void openSmartViewSettings() {
    try {
        Intent i = new Intent(android.provider.Settings.ACTION_CAST_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        dbg("opened Smart View / Cast settings");
    } catch (Throwable e1) {
        try {
            Intent i2 = new Intent("android.settings.WIFI_DISPLAY_SETTINGS");
            i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i2);
            dbg("opened WIFI_DISPLAY_SETTINGS");
        } catch (Throwable e2) {
            dbg("openSmartViewSettings failed", e2);
            Toast.makeText(this, "Cihaz yansıtma ayarı açılamadı.", Toast.LENGTH_SHORT).show();
        }
    }
}
private void openCastChooser() {
    try {
        androidx.fragment.app.DialogFragment f =
                androidx.mediarouter.app.MediaRouteDialogFactory.getDefault()
                        .onCreateChooserDialogFragment();
        f.show(getSupportFragmentManager(), "mr_chooser_dialog");
        dbg("cast chooser shown");
    } catch (Throwable e) { dbg("cast chooser failed", e); }
}

static class DlnaDevice {
    String usn, st, server, location, friendlyName;
    String avTransportCtrl, avTransportUrn; // urn:schemas-upnp-org:service:AVTransport:X
    String displayName() { return friendlyName != null ? friendlyName : (server != null ? server : (usn != null ? usn : "DLNA Aygıtı")); }
}

static class DlnaScanner {
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int    SSDP_PORT = 1900;

    static List<DlnaDevice> scanAndFilterDmrs(int timeoutMs) throws Exception {
        List<DlnaDevice> all = scan(timeoutMs);
        List<DlnaDevice> good = new ArrayList<>();
        for (DlnaDevice d : all) {
            DlnaControl.fillServiceInfo(d);
            if (d.avTransportCtrl != null && d.avTransportUrn != null) good.add(d);
        }
        return good;
    }

    static List<DlnaDevice> scan(int timeoutMs) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        Map<String, DlnaDevice> map = new LinkedHashMap<>();
        String[] sts = new String[]{
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:service:AVTransport:2"
        };
        try (java.net.DatagramSocket sock = new java.net.DatagramSocket()) {
            sock.setReuseAddress(true); sock.setSoTimeout(800);
            for (String st : sts) {
                String msearch = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                        "ST: " + st + "\r\n\r\n";
                byte[] data = msearch.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                sock.send(new java.net.DatagramPacket(data, data.length,
                        java.net.InetAddress.getByName(SSDP_ADDR), SSDP_PORT));
                Thread.sleep(120);
            }
            byte[] buf = new byte[4096];
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                java.net.DatagramPacket resp = new java.net.DatagramPacket(buf, buf.length);
                try {
                    sock.receive(resp);
                    String txt = new String(resp.getData(), 0, resp.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                    DlnaDevice d = parseResponse(txt);
                    if (d != null && d.usn != null) {
                        if (d.location != null && d.friendlyName == null) d.friendlyName = fetchFriendlyName(d.location, 600);
                        map.put(d.usn, d);
                    }
                } catch (java.net.SocketTimeoutException ignore) {}
            }
        }
        return new ArrayList<>(map.values());
    }

    private static DlnaDevice parseResponse(String txt) {
        String[] lines = txt.split("\r?\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/1.1 200")) return null;
        DlnaDevice d = new DlnaDevice();
        for (String l : lines) {
            int i = l.indexOf(':'); if (i <= 0) continue;
            String k = l.substring(0, i).trim().toUpperCase(java.util.Locale.ROOT);
            String v = l.substring(i + 1).trim();
            if ("USN".equals(k)) d.usn = v;
            else if ("ST".equals(k)) d.st = v;
            else if ("SERVER".equals(k)) d.server = v;
            else if ("LOCATION".equals(k)) d.location = v;
        }
        return d;
    }
    private static String fetchFriendlyName(String locationUrl, int timeoutMs) {
        java.io.BufferedReader br = null;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(locationUrl).openConnection();
            c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs); c.setInstanceFollowRedirects(true); c.connect();
            br = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                int s = sb.indexOf("<friendlyName>"); if (s >= 0) { int e = sb.indexOf("</friendlyName>", s);
                    if (e > s) return sb.substring(s + 14, e).trim(); }
            }
        } catch (Throwable ignored) {} finally { try { if (br != null) br.close(); } catch (Throwable ignore) {} }
        return null;
    }
}

static class DlnaControl {
    static DlnaControl fromDevice(DlnaDevice d) { fillServiceInfo(d); return (d.avTransportCtrl!=null)? new DlnaControl(d): null; }
    final DlnaDevice dev; DlnaControl(DlnaDevice d){ this.dev=d; }

    static void fillServiceInfo(DlnaDevice d) {
        if (d == null || d.location == null || d.avTransportCtrl != null) return;
        try {
            String desc = httpGet(d.location, 4500); if (desc == null) return;
            String base = baseUrlOf(d.location);
            ServiceInfo avt = findService(desc, "AVTransport"); // versiyon neyse onu al
            if (avt != null && avt.controlURL != null && avt.serviceType != null) {
                d.avTransportCtrl = join(base, avt.controlURL);
                d.avTransportUrn  = avt.serviceType; // urn:schemas-upnp-org:service:AVTransport:X
            }
        } catch (Throwable ignored) {}
    }

    boolean playUrl(String mediaUrl, String mime) {
        try {
            String meta = didlLiteFor(mediaUrl, mime);
            String setBody = "<u:SetAVTransportURI xmlns:u=\"" + dev.avTransportUrn + "\">" +
                    "<InstanceID>0</InstanceID><CurrentURI>" + xmlEsc(mediaUrl) + "</CurrentURI>" +
                    "<CurrentURIMetaData>" + xmlEsc(meta) + "</CurrentURIMetaData></u:SetAVTransportURI>";
            if (!soap(dev.avTransportCtrl, dev.avTransportUrn, "SetAVTransportURI", setBody)) return false;
            String playBody = "<u:Play xmlns:u=\"" + dev.avTransportUrn + "\">" +
                    "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>";
            return soap(dev.avTransportCtrl, dev.avTransportUrn, "Play", playBody);
        } catch (Throwable ignored) { return false; }
    }

    private static class ServiceInfo { String serviceType; String controlURL; }
    private static ServiceInfo findService(String descXml, String name) {
        String needle = "urn:schemas-upnp-org:service:" + name + ":";
        int pos = descXml.indexOf(needle); if (pos < 0) return null;
        int st1 = descXml.lastIndexOf("<serviceType>", pos), st2 = descXml.indexOf("</serviceType>", pos);
        int c1  = descXml.indexOf("<controlURL>", pos),  c2  = descXml.indexOf("</controlURL>", c1);
        if (st1<0 || st2<0 || c1<0 || c2<0) return null;
        ServiceInfo s = new ServiceInfo();
        s.serviceType = descXml.substring(st1 + 13, st2).trim();
        s.controlURL  = descXml.substring(c1  + 11, c2).trim();
        return s;
    }
    private static boolean soap(String ctrlUrl, String urn, String action, String inner) throws Exception {
        if (ctrlUrl == null || urn == null) return false;
        byte[] body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                inner + "</s:Body></s:Envelope>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(ctrlUrl).openConnection();
        c.setConnectTimeout(4500); c.setReadTimeout(4500);
        c.setDoOutput(true); c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + urn + "#" + action + "\"");
        try (java.io.OutputStream os = c.getOutputStream()) { os.write(body); }
        int code = c.getResponseCode();
        return (code >= 200 && code < 300);
    }
    private static String didlLiteFor(String url, String mime) {
        String prot = "http-get:*:" + (mime != null? mime : "video/*") + ":*";
        return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
                "<item id=\"0\" parentID=\"0\" restricted=\"1\">" +
                "<dc:title>MiniCast Test</dc:title>" +
                "<res protocolInfo=\"" + prot + "\">" + xmlEsc(url) + "</res>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "</item></DIDL-Lite>";
    }
    private static String httpGet(String url, int timeout) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(timeout); c.setReadTimeout(timeout); c.setInstanceFollowRedirects(true);
        c.addRequestProperty("User-Agent","Mozilla/5.0");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream is = c.getInputStream()) {
            byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String baseUrlOf(String url) throws Exception {
        java.net.URL u = new java.net.URL(url);
        int p = (u.getPort() >= 0) ? u.getPort() : u.getDefaultPort();
        return u.getProtocol() + "://" + u.getHost() + (p>0? (":" + p): "");
    }
    private static String join(String base, String path) {
        if (path == null) return null;
        try { return new java.net.URL(new java.net.URL(base + "/"), path).toString(); }
        catch (Throwable e) { return path; }
    }
    private static String xmlEsc(String s) {
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
    }
}

private void dbg(String msg) { android.util.Log.e("MiniCastCrash", msg); }
private void dbg(String msg, Throwable e) { android.util.Log.e("MiniCastCrash", msg, e); }

