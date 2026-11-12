package net.quber.afactorymode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MaintenanceDownloadTest {
    private static final String ORIGIN = "http://192.168.10.20:4186";
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private interface Attempt { void run() throws Exception; }
    private static void reject(Attempt attempt, String message) throws Exception {
        try { attempt.run(); throw new AssertionError(message); }
        catch (IOException | IllegalArgumentException expected) {
            check(!(expected instanceof MaintenanceDownload.RetryableException), "invalid response was retryable");
        }
    }
    private static void retryable(Attempt attempt, String message) throws Exception {
        try { attempt.run(); throw new AssertionError(message); }
        catch (MaintenanceDownload.RetryableException expected) {
            check("download_timeout".equals(expected.getMessage()) || "download_unavailable".equals(expected.getMessage()),
                    "network failure exposed an arbitrary retry reason");
        }
    }

    private static final class Clock implements MaintenanceDownload.Clock {
        long value = 100;
        @Override public long now() { return value; }
    }
    private static class Connection extends HttpURLConnection {
        final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        byte[] body = new byte[]{1, 2, 3};
        int status = 200, reads, disconnects;
        Clock trickle;
        Connection(URL url) { super(url); }
        @Override public void connect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnects++; }
        @Override public int getResponseCode() throws IOException { return status; }
        @Override public Map<String, List<String>> getHeaderFields() { return headers; }
        @Override public InputStream getInputStream() throws IOException {
            reads++;
            if (trickle == null) return new ByteArrayInputStream(body);
            return new ByteArrayInputStream(body) {
                @Override public int read(byte[] value, int offset, int size) {
                    trickle.value += 200;
                    return super.read(value, offset, Math.min(1, size));
                }
            };
        }
    }
    private static final class Factory implements MaintenanceDownload.Connections {
        Connection next;
        final List<URL> urls = new ArrayList<URL>();
        @Override public HttpURLConnection open(URL url) {
            urls.add(url);
            if (next == null) next = new Connection(url);
            return next;
        }
    }
    private static MaintenanceDownload downloader(Clock clock, Factory factory) throws Exception {
        return new MaintenanceDownload(ORIGIN, TOKEN, clock, 1000, factory);
    }

    public static void main(String[] args) throws Exception {
        Clock clock = new Clock();
        Factory factory = new Factory();
        MaintenanceDownload download = downloader(clock, factory);
        check(Arrays.equals(download.release(), new byte[]{1, 2, 3}), "release bytes changed");
        check(factory.urls.get(0).toString().equals(ORIGIN + "/v1/release"), "release escaped fixed endpoint");
        check(!factory.next.getInstanceFollowRedirects() && !factory.next.getUseCaches(), "redirect/cache enabled");
        check(factory.next.getRequestMethod().equals("GET"), "unexpected HTTP method");
        check(factory.next.getRequestProperty("Authorization").equals("Bearer " + TOKEN), "missing bearer");
        check(factory.next.getRequestProperty("Accept-Encoding").equals("identity"), "compressed transport requested");
        check(factory.next.getConnectTimeout() > 0 && factory.next.getReadTimeout() > 0, "unbounded socket timeout");
        check(factory.next.disconnects > 0, "connection leaked");

        for (final int status : new int[]{301, 302, 307, 308, 401, 403, 404, 501, 505}) {
            final Factory rejected = new Factory();
            rejected.next = new Connection(new URL(ORIGIN)); rejected.next.status = status;
            reject(new Attempt() { public void run() throws Exception { downloader(new Clock(), rejected).release(); } }, "HTTP status accepted");
            check(rejected.urls.size() == 1 && rejected.next.reads == 0 && rejected.next.disconnects > 0,
                    "rejected HTTP status followed or read");
        }
        for (final int status : new int[]{408, 429, 500, 502, 503, 504}) {
            final Factory unavailable = new Factory();
            unavailable.next = new Connection(new URL(ORIGIN)); unavailable.next.status = status;
            retryable(new Attempt() { public void run() throws Exception { downloader(new Clock(), unavailable).release(); } },
                    "temporary HTTP status accepted");
            check(unavailable.urls.size() == 1 && unavailable.next.reads == 0 && unavailable.next.disconnects > 0,
                    "temporary HTTP response followed or read");
        }
        Factory empty = new Factory(); empty.next = new Connection(new URL(ORIGIN)); empty.next.status = 204;
        check(downloader(clock, empty).release() == null && empty.next.reads == 0, "no-release response rejected");
        final Factory emptyApk = empty;
        reject(new Attempt() { public void run() throws Exception { downloader(new Clock(), emptyApk).apk(HASH, 3); } }, "empty APK accepted");

        Factory apk = new Factory();
        check(downloader(clock, apk).apk(HASH, 3).length == 3, "valid APK body rejected");
        check(apk.urls.get(0).toString().equals(ORIGIN + "/v1/apk/" + HASH), "APK endpoint not hash bound");
        final String[] badHashes = {null, "", "../secret", HASH + "?redirect=evil", HASH.toUpperCase()};
        for (final String value : badHashes) reject(new Attempt() { public void run() throws Exception {
            downloader(new Clock(), new Factory()).apk(value, 3);
        } }, "malformed artifact path accepted");
        for (final int size : new int[]{0, -1, 2 * 1024 * 1024 + 1}) reject(new Attempt() { public void run() throws Exception {
            downloader(new Clock(), new Factory()).apk(HASH, size);
        } }, "invalid artifact size accepted");

        for (final String value : new String[]{"gzip", "deflate", "identity,gzip"}) {
            final Factory encoded = new Factory(); encoded.next = new Connection(new URL(ORIGIN));
            encoded.next.headers.put("Content-Encoding", Arrays.asList(value));
            reject(new Attempt() { public void run() throws Exception { downloader(new Clock(), encoded).release(); } }, "encoded response accepted");
            check(encoded.next.reads == 0, "encoded response decompressed");
        }
        for (final String length : new String[]{"-1", "3,3", "9223372036854775807", "8193"}) {
            final Factory invalid = new Factory(); invalid.next = new Connection(new URL(ORIGIN));
            invalid.next.headers.put("Content-Length", Arrays.asList(length));
            reject(new Attempt() { public void run() throws Exception { downloader(new Clock(), invalid).release(); } }, "invalid length accepted");
        }
        final Factory duplicate = new Factory(); duplicate.next = new Connection(new URL(ORIGIN));
        duplicate.next.headers.put("Content-Length", Arrays.asList("3"));
        duplicate.next.headers.put("content-length", Arrays.asList("3"));
        reject(new Attempt() { public void run() throws Exception { downloader(new Clock(), duplicate).release(); } }, "duplicate length accepted");
        final Factory oversized = new Factory(); oversized.next = new Connection(new URL(ORIGIN)); oversized.next.body = new byte[8193];
        reject(new Attempt() { public void run() throws Exception { downloader(new Clock(), oversized).release(); } }, "unannounced oversized release accepted");
        final Factory shortBody = new Factory(); shortBody.next = new Connection(new URL(ORIGIN));
        shortBody.next.headers.put("Content-Length", Arrays.asList("4"));
        reject(new Attempt() { public void run() throws Exception { downloader(new Clock(), shortBody).release(); } }, "truncated declared body accepted");
        final Factory wrongSize = new Factory();
        reject(new Attempt() { public void run() throws Exception { downloader(new Clock(), wrongSize).apk(HASH, 4); } }, "APK length mismatch accepted");

        final Clock slowClock = new Clock(); final Factory slow = new Factory(); slow.next = new Connection(new URL(ORIGIN));
        slow.next.body = new byte[12]; slow.next.trickle = slowClock;
        retryable(new Attempt() { public void run() throws Exception { downloader(slowClock, slow).release(); } }, "slow trickle exceeded total deadline");
        final Clock expired = new Clock(); expired.value = 1001; final Factory notOpened = new Factory();
        retryable(new Attempt() { public void run() throws Exception { downloader(expired, notOpened).release(); } }, "expired download opened");
        check(notOpened.urls.isEmpty(), "expired budget made a request");

        final Clock sharedClock = new Clock(); final Factory sharedFactory = new Factory();
        final MaintenanceDownload sharedBudget = downloader(sharedClock, sharedFactory);
        sharedBudget.release(); sharedClock.value = 1001;
        retryable(new Attempt() { public void run() throws Exception { sharedBudget.apk(HASH, 3); } }, "APK reset release deadline");
        check(sharedFactory.urls.size() == 1, "expired shared budget opened an APK request");

        for (final int failureStage : new int[]{0, 1, 2}) {
            final Connection broken = new Connection(new URL(ORIGIN)) {
                @Override public int getResponseCode() throws IOException {
                    if (failureStage == 1) throw new java.net.SocketTimeoutException("private network detail");
                    return super.getResponseCode();
                }
                @Override public InputStream getInputStream() throws IOException {
                    if (failureStage == 2) throw new java.net.SocketException("private network detail");
                    return super.getInputStream();
                }
            };
            final MaintenanceDownload networkFailure = new MaintenanceDownload(ORIGIN, TOKEN, new Clock(), 1000,
                    new MaintenanceDownload.Connections() {
                        @Override public HttpURLConnection open(URL url) throws IOException {
                            if (failureStage == 0) throw new java.net.ConnectException("private network detail");
                            return broken;
                        }
                    });
            retryable(new Attempt() { public void run() throws Exception { networkFailure.release(); } }, "network failure accepted");
            if (failureStage > 0) check(broken.disconnects > 0, "failed network connection leaked");
        }

        final MaintenanceDownload.Clock realClock = new MaintenanceDownload.Clock() {
            @Override public long now() { return System.nanoTime() / 1000000L; }
        };
        final Connection stalled = new Connection(new URL(ORIGIN)) {
            @Override public synchronized int getResponseCode() throws IOException {
                long end = realClock.now() + 5000;
                while (disconnects == 0 && realClock.now() < end) {
                    try { wait(100); } catch (InterruptedException error) { throw new IOException(error); }
                }
                throw new IOException("stalled_response");
            }
            @Override public synchronized void disconnect() { super.disconnect(); notifyAll(); }
        };
        long began = realClock.now();
        final MaintenanceDownload stalledDownload = new MaintenanceDownload(ORIGIN, TOKEN, realClock, began + 150,
                new MaintenanceDownload.Connections() {
                    @Override public HttpURLConnection open(URL url) { return stalled; }
                });
        retryable(new Attempt() { public void run() throws Exception { stalledDownload.release(); } }, "stalled headers accepted");
        check(stalled.disconnects > 0 && realClock.now() - began < 3000, "total deadline failed to disconnect stalled headers");

        for (final String origin : new String[]{"https://192.168.10.20:4186", "http://user@192.168.10.20:4186",
                "http://192.168.10.20:4186/path", "http://192.168.10.20:4186?x=y", "http://192.168.10.20:4186#x"}) {
            reject(new Attempt() { public void run() throws Exception {
                new MaintenanceDownload(origin, TOKEN, new Clock(), 1000, new Factory());
            } }, "non-origin configured");
        }
        System.out.println("Bounded update transport checks passed: " + checks);
    }
}
