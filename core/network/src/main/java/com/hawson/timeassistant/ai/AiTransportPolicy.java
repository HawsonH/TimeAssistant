package com.hawson.timeassistant.ai;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/** Shared connection policy. Approval is checked again for every request. */
public final class AiTransportPolicy {
    private final Predicate<String> approvedOrigin;

    public AiTransportPolicy(Predicate<String> approvedOrigin) {
        this.approvedOrigin = Objects.requireNonNull(approvedOrigin);
    }

    public static AiTransportPolicy httpsOnly() {
        return new AiTransportPolicy(origin -> false);
    }

    public void requireAllowed(String url) {
        URI uri = URI.create(url);
        if (uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawFragment() != null || uri.getRawQuery() != null
                || uri.getPort() > 65535 || uri.getPort() == 0) {
            throw new IllegalArgumentException("AI 服务地址无效");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) return;
        String origin = privateHttpOrigin(url);
        if (!approvedOrigin.test(origin)) {
            throw new IllegalArgumentException("局域网 HTTP 地址尚未单独授权：" + origin);
        }
    }

    public static String privateHttpOrigin(String url) {
        URI uri = URI.create(url);
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getHost() == null || !isPrivateLiteral(uri.getHost())
                || uri.getPort() == 0 || uri.getPort() > 65535
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("公网 AI 必须使用 HTTPS；HTTP 仅允许私网或回环 IP 字面量");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        return "http://" + host + ":" + port;
    }

    public HttpURLConnection open(URL endpoint) throws IOException {
        requireAllowed(endpoint.toExternalForm());
        // Explicit direct connection prevents a private HTTP request leaking through a proxy.
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    public static boolean isPrivateLiteral(String raw) {
        String host = raw;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.contains(":")) {
            if (!host.matches("[0-9A-Fa-f:]+")) return false;
            try {
                InetAddress address = InetAddress.getByName(host);
                if (!(address instanceof Inet6Address)) return false;
                byte[] bytes = address.getAddress();
                return address.isLoopbackAddress() || (bytes[0] & 0xfe) == 0xfc;
            } catch (IOException invalid) {
                return false;
            }
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            if (!parts[index].matches("0|[1-9][0-9]{0,2}")) return false;
            octets[index] = Integer.parseInt(parts[index]);
            if (octets[index] > 255) return false;
        }
        return octets[0] == 10 || octets[0] == 127
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }
}
