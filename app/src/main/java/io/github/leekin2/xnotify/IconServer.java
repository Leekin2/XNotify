package io.github.leekin2.xnotify;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * Icons are too large for Xiaomi's notification link, so the phone serves them on the local
 * network for a few minutes. The tablet learns the address and a one-time key from the app list
 * and fetches the icons once. Only the icon list is served, and only for the right key.
 */
final class IconServer {
    private static final int LIFETIME_MS = 10 * 60 * 1000;
    private static ServerSocket server;
    private static String token;
    private static byte[] payload;

    /** Starts (or refreshes) serving and returns "host:port/token", or null without Wi-Fi. */
    static synchronized String serve(String icons) {
        String host = wifiAddress();
        if (host == null) return null;
        payload = icons.getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder t = new StringBuilder();
        for (byte b : raw) t.append(String.format("%02x", b));
        token = t.toString();
        try {
            if (server == null || server.isClosed()) {
                server = new ServerSocket();
                server.bind(new InetSocketAddress(InetAddress.getByName(host), 0));
                ServerSocket current = server;
                Thread accept = new Thread(() -> acceptLoop(current), "xnotify-icons");
                accept.setDaemon(true);
                accept.start();
            }
            server.setSoTimeout(LIFETIME_MS);
            return host + ":" + server.getLocalPort() + "/" + token;
        } catch (Exception e) {
            return null;
        }
    }

    private static void acceptLoop(ServerSocket socket) {
        try {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(10_000);
                    String asked = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    byte[] body;
                    synchronized (IconServer.class) { body = token != null && token.equals(asked) ? payload : null; }
                    if (body != null) {
                        OutputStream out = client.getOutputStream();
                        out.write(body);
                        out.flush();
                    }
                } catch (java.net.SocketTimeoutException idle) {
                    break; // nobody asked for a while
                } catch (Exception ignored) { }
            }
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    /** Fetches the icons from "host:port/token"; null on any failure. */
    static String fetch(String address) {
        try {
            int slash = address.indexOf('/'), colon = address.lastIndexOf(':', slash);
            String host = address.substring(0, colon);
            int port = Integer.parseInt(address.substring(colon + 1, slash));
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5_000);
                socket.setSoTimeout(15_000);
                OutputStream out = socket.getOutputStream();
                out.write((address.substring(slash + 1) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                InputStream in = socket.getInputStream();
                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
                return body.size() == 0 ? null : body.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String wifiAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || !nif.getName().startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private IconServer() { }
}
