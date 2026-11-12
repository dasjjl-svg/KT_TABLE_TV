package net.quber.afactorymode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

final class MaintenanceDownload {
    interface Clock { long now(); }
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    static final class RetryableException extends IOException {
        RetryableException(String message) { super(message); }
        RetryableException(IOException cause) { super("download_unavailable", cause); }
    }
    private static final class ResponseException extends IOException {
        ResponseException(String message) { super(message); }
    }

    private final String origin, token;
    private final Clock clock;
    private final long deadline;
    private final Connections connections;

    MaintenanceDownload(String origin, String token, Clock clock, long deadline) throws Exception {
        this(origin, token, clock, deadline, new Connections() {
            @Override public HttpURLConnection open(URL url) throws IOException {
                return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            }
        });
    }

    MaintenanceDownload(String origin, String token, Clock clock, long deadline, Connections connections) throws Exception {
        URI parsed = new URI(origin);
        if (!"http".equals(parsed.getScheme()) || parsed.getHost() == null || parsed.getPort() <= 0
                || parsed.getPort() > 65535 || parsed.getRawUserInfo() != null || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null || !"".equals(parsed.getRawPath())
                || token == null || !token.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("download_config");
        }
        this.origin = origin; this.token = token; this.clock = clock; this.deadline = deadline;
        this.connections = connections;
    }

    byte[] release() throws Exception { return get("/v1/release", 8192, -1, true); }

    byte[] apk(String sha256, int size) throws Exception {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || size < 1 || size > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("download_artifact");
        }
        return get("/v1/apk/" + sha256, size, size, false);
    }

    private int remaining() throws IOException {
        long left = deadline - clock.now();
        if (left <= 0) throw new RetryableException("download_timeout");
        return (int) Math.min(left, Integer.MAX_VALUE);
    }

    private byte[] get(String path, int maximum, int exact, boolean allowEmpty) throws Exception {
        remaining();
        final HttpURLConnection connection;
        try { connection = connections.open(new URL(origin + path)); }
        catch (IOException unavailable) { throw new RetryableException(unavailable); }
        final AtomicBoolean expired = new AtomicBoolean();
        Timer timer = new Timer("panel-download-deadline", true);
        try {
            timer.schedule(new TimerTask() {
                @Override public void run() { expired.set(true); connection.disconnect(); }
            }, remaining());
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(Math.min(4000, remaining()));
            connection.setReadTimeout(Math.min(4000, remaining()));
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            remaining();
            if (expired.get()) throw new RetryableException("download_timeout");
            if (status == 204 && allowEmpty) return null;
            if (status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
                throw new RetryableException("download_unavailable");
            }
            if (status != 200) throw new ResponseException("download_http_status");
            String encoding = uniqueHeader(connection, "Content-Encoding");
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new ResponseException("download_encoding");
            String length = uniqueHeader(connection, "Content-Length");
            int declared = -1;
            if (length != null) {
                if (!length.matches("[0-9]{1,10}")) throw new ResponseException("download_length");
                long value = Long.parseLong(length);
                if (value > maximum || exact >= 0 && value != exact) throw new ResponseException("download_size");
                declared = (int) value;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
            try (InputStream input = connection.getInputStream()) {
                byte[] block = new byte[8192];
                int count;
                while ((count = input.read(block)) != -1) {
                    remaining();
                    if (expired.get()) throw new RetryableException("download_timeout");
                    if (count > maximum - output.size()) throw new ResponseException("download_size");
                    output.write(block, 0, count);
                }
            }
            remaining();
            if (expired.get()) throw new RetryableException("download_timeout");
            if (declared >= 0 && output.size() != declared || exact >= 0 && output.size() != exact) {
                throw new ResponseException("download_size");
            }
            return output.toByteArray();
        } catch (ResponseException invalid) {
            throw invalid;
        } catch (RetryableException unavailable) {
            throw unavailable;
        } catch (IOException unavailable) {
            throw new RetryableException(unavailable);
        } finally {
            timer.cancel();
            connection.disconnect();
        }
    }

    private static String uniqueHeader(HttpURLConnection connection, String name) throws IOException {
        String found = null;
        for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if (header.getKey() == null || !name.equalsIgnoreCase(header.getKey())) continue;
            if (found != null || header.getValue() == null || header.getValue().size() != 1
                    || header.getValue().get(0) == null) throw new ResponseException("download_header");
            found = header.getValue().get(0).trim();
        }
        return found;
    }
}
