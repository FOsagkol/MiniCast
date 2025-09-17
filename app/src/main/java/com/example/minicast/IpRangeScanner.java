package com.example.minicast;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class IpRangeScanner {
    private static final String TAG = "MiniCast-IPScan";

    // Denenecek yaygın DLNA portları
    private static final int[] PORTS = {55000, 49152, 8895};

    // Description path listesi
    private static final String[] PATHS = {
            "/description.xml",
            "/rootDesc.xml",
            "/DeviceDescription.xml",
            "/RenderingControl/desc.xml"
    };

    public interface ResultListener {
        void onDeviceFound(String ip, int port, String url, String friendlyName);
        void onFinished();
    }

    public static void scanRange(String subnet, int start, int end, ResultListener listener) {
        new Thread(() -> {
            List<String> found = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                String ip = subnet + "." + i;
                for (int port : PORTS) {
                    for (String path : PATHS) {
                        String url = "http://" + ip + ":" + port + path;
                        try {
                            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                            c.setConnectTimeout(800);
                            c.setReadTimeout(800);
                            int code = c.getResponseCode();
                            if (code == 200) {
                                // FriendlyName çıkar
                                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = br.readLine()) != null) {
                                    sb.append(line);
                                    int s = sb.indexOf("<friendlyName>");
                                    if (s >= 0) {
                                        int e = sb.indexOf("</friendlyName>", s);
                                        if (e > s) {
                                            String fname = sb.substring(s + 14, e).trim();
                                            listener.onDeviceFound(ip, port, url, fname);
                                        }
                                    }
                                }
                                br.close();
                            }
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
            listener.onFinished();
        }).start();
    }
}
