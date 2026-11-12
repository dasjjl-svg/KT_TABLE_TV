package net.quber.afactorymode;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Base64OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class SystemBackupExport {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CHUNK = 1048576;
    private static final long BUDGET = 7200000;
    private final Context context;
    private enum Mode { SYSTEM, SPI_IDENTITY, SPI_BACKUP, SPI_READ }
    private final Mode mode;
    private final long deadline = SystemClock.elapsedRealtime() + BUDGET;
    private final String capture = UUID.randomUUID().toString().replace("-", "");
    private final JSONArray files = new JSONArray();
    private final Map<String, byte[]> contents = new LinkedHashMap<String, byte[]>();
    private final StringBuilder diagnostics = new StringBuilder();
    private boolean inventorySent;
    private String inventoryHash;
    private volatile HttpURLConnection activeConnection;
    private volatile boolean expired;

    private SystemBackupExport(Context context, Mode mode) { this.context = context; this.mode = mode; }

    static KtApkExport.Result run(Context context) {
        return new SystemBackupExport(context, Mode.SYSTEM).run();
    }

    static KtApkExport.Result runSpiIdentity(Context context) {
        return new SystemBackupExport(context, Mode.SPI_IDENTITY).run();
    }

    static KtApkExport.Result runSpiBackup(Context context) {
        return new SystemBackupExport(context, Mode.SPI_BACKUP).run();
    }

    static KtApkExport.Result runSpiSamples(Context context) {
        return new SystemBackupExport(context, Mode.SPI_READ).run();
    }

    private KtApkExport.Result run() {
        PowerManager.WakeLock awake = null;
        Timer timer = new Timer("system-backup-deadline", true);
        timer.schedule(new TimerTask() {
            public void run() {
                expired = true;
                HttpURLConnection connection = activeConnection;
                if (connection != null) connection.disconnect();
            }
        }, BUDGET);
        try {
            JSONObject ready = json(request("GET", "/ready", null, null, false));
            if (!ready.getBoolean("ok") || ready.getInt("version") != 1) throw new IOException("receiver_not_ready");
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (manager == null) throw new IOException("power_service_unavailable");
            awake = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "home-panel:system-backup");
            awake.acquire(BUDGET);
            addAppMountInfo();
            try (AdbNetworkSetup.BackupAccess access = AdbNetworkSetup.beginBackup(context)) {
                SystemBackupSource.Result pipe = access.read(SystemBackupProtocol.Query.PIPE_TEST);
                add(SystemBackupProtocol.Query.PIPE_TEST, pipe);
                if (!"ok".equals(pipe.status) || !"home-panel-pipe-ok".equals(new String(pipe.data, UTF8).trim())) {
                    throw new IOException("pipe_test_failed");
                }
                SystemBackupSource.Result root = access.read(SystemBackupProtocol.Query.ROOT_STATUS);
                add(SystemBackupProtocol.Query.ROOT_STATUS, root);
                if (!"ok".equals(root.status) || !SystemBackupPolicy.rootIdentity(new String(root.data, UTF8))) {
                    throw new IOException("root_identity_unverified");
                }
                for (SystemBackupProtocol.Query query : SystemBackupProtocol.Query.values()) {
                    if (query == SystemBackupProtocol.Query.PIPE_TEST || query == SystemBackupProtocol.Query.ROOT_STATUS) continue;
                    checkTime();
                    add(query, access.read(query));
                }
                checkTime();
                BootMediaClient.Result bootMedia = BootMediaClient.read(context, access);
                addFile("boot_media", "ioctl:hi_ca-boot", bootMedia.status, bootMedia.data);
                addFile("boot_media_status", "builtin:boot-media-status", "ok",
                        ("status=" + bootMedia.status + "\nreason=" + bootMedia.reason + "\n" + bootMedia.evidence).getBytes(UTF8));
                log("boot_media.reason=" + bootMedia.reason);
                checkTime();
                BootMediaClient.Result controller = BootMediaClient.readSpiController(context, access);
                addFile("spi_controller", "mmap:spi-controller-state", controller.status, controller.data);
                addFile("spi_controller_status", "builtin:spi-controller-status", "ok",
                        ("status=" + controller.status + "\nreason=" + controller.reason + "\n" + controller.evidence).getBytes(UTF8));
                log("spi_controller.reason=" + controller.reason);
                if (mode == Mode.SPI_IDENTITY) {
                    checkTime();
                    BootMediaClient.Result identity = BootMediaClient.readSpiIdentity(context, access);
                    addFile("spi_identity", "native:spi-nor-identity", identity.status, identity.data);
                    addFile("spi_identity_status", "builtin:spi-identity-status", "ok",
                            ("status=" + identity.status + "\nreason=" + identity.reason + "\n" + identity.evidence).getBytes(UTF8));
                    log("spi_identity.reason=" + identity.reason);
                }
                BootMediaClient.Result spiBackup = null;
                if (mode == Mode.SPI_BACKUP) {
                    checkTime();
                    spiBackup = BootMediaClient.readSpiBackup(context, access);
                    addFile("spi_nor_main", "native:spi-nor-main-array", spiBackup.status, spiBackup.data);
                    addFile("spi_nor_main_status", "builtin:spi-nor-main-status", "ok",
                            ("status=" + spiBackup.status + "\nreason=" + spiBackup.reason + "\n" + spiBackup.evidence).getBytes(UTF8));
                    log("spi_nor_main.reason=" + spiBackup.reason);
                    log("source_consistency=live_non_atomic");
                    log("restore_tested=false");
                    log("address_space_mode=unverified");
                    log("read_opcode=0x03");
                    log("read_chunk_bytes=256");
                    log("address_range=0x000000_0x3fffff");
                }
                BootMediaClient.Result spiSamples = null;
                if (mode == Mode.SPI_READ) {
                    checkTime();
                    spiSamples = BootMediaClient.readSpiSamples(context, access);
                    addFile("spi_read_samples", "native:spi-read-samples", spiSamples.status, spiSamples.data);
                    addFile("spi_read_samples_status", "builtin:spi-read-samples-status", "ok",
                            ("status=" + spiSamples.status + "\nreason=" + spiSamples.reason + "\n" + spiSamples.evidence).getBytes(UTF8));
                    log("spi_read_samples.reason=" + spiSamples.reason);
                    log("sample_addresses=0,100,200,1000,10000,1ff000,3ff000,0");
                    log("sample_size=256");
                    log("long_hex_offsets=0,800,fe0");
                    log("full_backup_performed=false");
                    log("address_space_mode=unverified");
                }
                sendInventory();
                if (mode == Mode.SPI_READ) return result(spiSamples != null && "ok".equals(spiSamples.status)
                        ? "SPI 읽기 진단을 Mac으로 전송했습니다.\n주소별 원본 샘플과 읽기 길이 비교 결과를 보냈습니다. 전체 백업은 수행하지 않았습니다."
                        : "SPI 읽기 진단 결과를 Mac으로 전송했습니다.\n샘플 수집은 완료되지 않았습니다. 아래 결과를 확인해 주세요.");
                if (mode == Mode.SPI_IDENTITY) return result("SPI 칩 식별 진단을 Mac으로 전송했습니다.\n부트로더 본문 읽기는 아직 수행하지 않았습니다. 아래에 수집 결과가 표시됩니다.");
                if (mode == Mode.SPI_BACKUP) return result(spiBackup != null && "ok".equals(spiBackup.status)
                        ? "SPI 4MiB 백업을 Mac으로 전송했습니다.\n두 번 읽은 내용과 전송 파일을 검증했습니다. 이미지 내용 분석과 복구 시험은 아직 수행하지 않았습니다."
                        : "SPI 백업 진단을 Mac으로 전송했습니다.\n4MiB 백업은 완료되지 않았습니다. 아래 결과를 확인해 주세요.");
                JSONObject plan = awaitPlan();
                if (plan == null) return result("시스템 구성 파일을 Mac에 백업했습니다.\n파티션 백업 계획이 없어 이미지 읽기는 시작하지 않았습니다.");
                JSONArray partitions = validatePlan(plan, plan.getJSONArray("partitions").length() == 0 ? null : currentMap(access));
                for (int p = 0; p < partitions.length(); p++) {
                    JSONObject part = partitions.getJSONObject(p);
                    String name = part.getString("id");
                    long size = number(part, "size");
                    verifyPartition(part, currentMap(access));
                    MessageDigest full = MessageDigest.getInstance("SHA-256");
                    long offset = 0;
                    int index = 0;
                    while (offset < size) {
                        checkTime();
                        if (index > 0 && index % 64 == 0) verifyPartition(part, currentMap(access));
                        int length = (int) Math.min(CHUNK, size - offset);
                        SystemBackupSource.Result source = access.readBlock(name, offset, length);
                        if (!source.propertyCleared || !"ok".equals(source.status) || source.data.length != length) {
                            throw new IOException("block_read_failed");
                        }
                        String hash = hash(source.data);
                        JSONObject ack = json(request("POST", "/chunk/" + capture + "/" + name + "/" + index,
                                source.data, hash, true));
                        if (!ack.getBoolean("ok") || ack.getInt("index") != index
                                || ack.getLong("size") != length || !hash.equals(ack.getString("sha256"))) {
                            throw new IOException("chunk_ack_mismatch");
                        }
                        full.update(source.data);
                        offset += length;
                        index++;
                    }
                    verifyPartition(part, currentMap(access));
                    String digest = hex(full.digest());
                    byte[] completion = new JSONObject().put("sha256", digest).put("size", size).toString().getBytes(UTF8);
                    JSONObject ack = json(request("POST", "/complete/" + capture + "/" + name, completion, null, true));
                    if (!ack.getBoolean("complete") || !ack.getBoolean("ok") || !name.equals(ack.getString("id"))
                            || ack.getLong("size") != size || !digest.equals(ack.getString("sha256"))) {
                        throw new IOException("partition_ack_mismatch");
                    }
                    log(name + ".verified_bytes=" + size);
                }
                log("source_consistency=live_non_atomic");
                return result(partitions.length() == 0
                        ? "시스템 구성과 읽을 수 있는 추가 파일을 Mac에 백업했습니다.\n각 파일의 수집 결과는 아래에 표시됩니다."
                        : "시스템 구성과 지정한 파티션을 Mac에 백업했습니다.\n전송 크기와 SHA-256 검증을 마쳤습니다. 실행 중인 기기에서 읽은 백업입니다.");
            }
        } catch (Exception error) {
            log("error=" + error.getClass().getSimpleName());
            String message = error.getMessage();
            if (message != null && message.matches("[a-z0-9_]{1,80}")) log("reason=" + message);
            if (!inventorySent && files.length() > 0) {
                try { sendInventory(); } catch (Exception ignored) { log("partial_inventory_upload=failed"); }
            }
            return result(inventorySent
                    ? "수집한 시스템 구성은 Mac에 저장했습니다.\n파티션 백업은 완료되지 않았습니다. 아래 결과를 확인해 주세요."
                    : "시스템 백업을 완료하지 못했습니다.\nMac 수신기와 아래 결과를 확인해 주세요.");
        } finally {
            timer.cancel();
            if (awake != null && awake.isHeld()) awake.release();
        }
    }

    private void add(SystemBackupProtocol.Query query, SystemBackupSource.Result result) throws Exception {
        if (!result.propertyCleared) throw new IOException("diagnostic_property_not_cleared");
        String id = query.name().toLowerCase(Locale.US);
        addFile(id, source(query), result.status, result.data);
    }

    private void addFile(String id, String source, String status, byte[] data) throws Exception {
        if ("error".equals(status) || "absent".equals(status) || "unreadable".equals(status)) data = new byte[0];
        boolean spi = "spi_fastboot".equals(id) || "spi_bootargs".equals(id);
        boolean spiMain = "spi_nor_main".equals(id);
        if (data.length > (spiMain ? SpiBackupFrame.PAYLOAD_SIZE : spi ? CHUNK : 65536)
                || (spi && "ok".equals(status) && data.length != CHUNK)
                || (spiMain && "ok".equals(status) && data.length != SpiBackupFrame.PAYLOAD_SIZE)) {
            throw new IOException("inventory_file_limit");
        }
        files.put(new JSONObject().put("id", id).put("source", source).put("status", status)
                .put("data", spiMain ? "" : Base64.encodeToString(data, Base64.NO_WRAP)).put("sha256", hash(data)));
        if ("ok".equals(status)) contents.put(id, data);
        log(id + "=" + status + " bytes=" + data.length);
    }

    private void addAppMountInfo() throws Exception {
        try (InputStream input = new FileInputStream("/proc/self/mountinfo")) {
            byte[] data = readBounded(input, 65536);
            addFile("app_mountinfo", "/proc/self/mountinfo", "ok", data);
        } catch (IOException error) {
            addFile("app_mountinfo", "/proc/self/mountinfo", "error", new byte[0]);
        }
    }

    private void sendInventory() throws Exception {
        byte[] body;
        if (mode == Mode.SPI_BACKUP) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(8 * CHUNK);
            writeUtf8(output, "{\"version\":1,\"model\":" + JSONObject.quote(Build.MODEL)
                    + ",\"appUid\":" + Process.myUid() + ",\"files\":[");
            for (int i = 0; i < files.length(); i++) {
                if (i != 0) output.write(',');
                JSONObject file = files.getJSONObject(i);
                if ("spi_nor_main".equals(file.getString("id")) && "ok".equals(file.getString("status"))) {
                    byte[] data = contents.get("spi_nor_main");
                    if (data == null || data.length != SpiBackupFrame.PAYLOAD_SIZE
                            || !hash(data).equals(file.getString("sha256"))) throw new IOException("spi_inventory_data_invalid");
                    writeUtf8(output, "{\"id\":\"spi_nor_main\",\"source\":\"native:spi-nor-main-array\",\"status\":\"ok\",\"data\":\"");
                    Base64OutputStream encoder = new Base64OutputStream(output, Base64.NO_WRAP | Base64.NO_CLOSE);
                    for (int offset = 0; offset < data.length; offset += 8192) {
                        encoder.write(data, offset, Math.min(8192, data.length - offset));
                    }
                    encoder.close();
                    writeUtf8(output, "\",\"sha256\":" + JSONObject.quote(file.getString("sha256")) + "}");
                } else writeUtf8(output, file.toString());
                if (output.size() > 8 * CHUNK) throw new IOException("inventory_limit");
            }
            writeUtf8(output, "],\"diagnostics\":" + JSONObject.quote(diagnostics.toString()) + "}");
            body = output.toByteArray();
        } else {
            JSONObject inventory = new JSONObject().put("version", 1).put("model", Build.MODEL)
                    .put("appUid", Process.myUid()).put("files", files).put("diagnostics", diagnostics.toString());
            body = inventory.toString().getBytes(UTF8);
        }
        if (body.length > 8 * CHUNK) throw new IOException("inventory_limit");
        String expected = hash(body);
        JSONObject ack = json(request("POST", "/inventory/" + capture, body, null, true));
        if (!ack.getBoolean("ok") || !capture.equals(ack.getString("captureId"))
                || !expected.equals(ack.getString("inventorySha256"))) throw new IOException("inventory_ack_mismatch");
        inventorySent = true;
        inventoryHash = expected;
    }

    private static void writeUtf8(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(UTF8));
    }

    private JSONObject awaitPlan() throws Exception {
        long until = Math.min(deadline, SystemClock.elapsedRealtime() + 1200000);
        while (SystemClock.elapsedRealtime() < until) {
            byte[] raw = request("GET", "/plan/" + capture, null, null, true);
            if (raw.length > 0) return json(raw);
            Thread.sleep(3000);
        }
        return null;
    }

    private Snapshot currentMap(AdbNetworkSetup.BackupAccess access) throws Exception {
        SystemBackupSource.Result blocks = access.read(SystemBackupProtocol.Query.BLOCK_MAP);
        SystemBackupSource.Result mounts = access.read(SystemBackupProtocol.Query.MOUNTINFO);
        if (!"ok".equals(blocks.status) || !"ok".equals(mounts.status)
                || !blocks.propertyCleared || !mounts.propertyCleared) throw new IOException("live_map_unavailable");
        Set<String> writable = new HashSet<String>(SystemBackupPolicy.writableMounts(new String(mounts.data, UTF8)));
        try (InputStream input = new FileInputStream("/proc/self/mountinfo")) {
            writable.addAll(SystemBackupPolicy.writableMounts(new String(readBounded(input, 65536), UTF8)));
        }
        Map<String, SystemBackupPolicy.Block> live = SystemBackupPolicy.blocks(new String(blocks.data, UTF8));
        byte[] original = contents.get("block_map");
        if (original == null) throw new IOException("initial_map_unavailable");
        Map<String, SystemBackupPolicy.Block> initial = SystemBackupPolicy.blocks(new String(original, UTF8));
        if (!initial.keySet().equals(live.keySet())) throw new IOException("block_map_changed");
        for (String name : initial.keySet()) {
            SystemBackupPolicy.Block first = initial.get(name), now = live.get(name);
            if (!first.deviceNumber.equals(now.deviceNumber) || first.sectors != now.sectors || first.start != now.start) {
                throw new IOException("block_map_changed");
            }
        }
        return new Snapshot(live, writable);
    }

    private JSONArray validatePlan(JSONObject plan, Snapshot snapshot) throws Exception {
        if (number(plan, "version") != 1 || !capture.equals(plan.getString("captureId"))
                || !inventoryHash.equals(plan.getString("inventorySha256"))) throw new IOException("plan_identity_mismatch");
        JSONArray parts = plan.getJSONArray("partitions");
        if (parts.length() > 128) throw new IOException("plan_count");
        Set<String> names = new HashSet<String>();
        long total = 0;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            verifyPartition(part, snapshot);
            if (!names.add(part.getString("id"))) throw new IOException("duplicate_partition");
            total += number(part, "size");
            if (total > 64L * 1024 * CHUNK) throw new IOException("plan_size");
        }
        return parts;
    }

    private void verifyPartition(JSONObject part, Snapshot snapshot) throws Exception {
        String name = part.getString("id");
        if (!("/dev/block/" + name).equals(part.getString("device"))) throw new IOException("plan_device");
        long chunk = number(part, "chunkSize");
        if (chunk != CHUNK) throw new IOException("plan_chunk_size");
        SystemBackupPolicy.validatePartition(name, number(part, "size"), CHUNK, snapshot.blocks, snapshot.writable);
    }

    private byte[] request(String method, String path, byte[] body, String digest, boolean retry) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < (retry ? 3 : 1); attempt++) {
            checkTime();
            HttpURLConnection connection = null;
            try {
                URL origin = new URL(PanelBuildConfig.BACKUP_ORIGIN);
                URL panel = new URL(PanelBuildConfig.ORIGIN);
                if (!"http".equals(origin.getProtocol()) || origin.getPort() != 4185
                        || !origin.getHost().equals(panel.getHost()) || origin.getUserInfo() != null
                        || !PanelBuildConfig.BACKUP_TOKEN.matches("[0-9a-f]{64}") || !path.matches("/[A-Za-z0-9/_-]+")) {
                    throw new IOException("backup_origin");
                }
                connection = (HttpURLConnection) new URL(origin, path).openConnection(Proxy.NO_PROXY);
                activeConnection = connection;
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(path.startsWith("/complete/") ? 180000 : 30000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Authorization", "Bearer " + PanelBuildConfig.BACKUP_TOKEN);
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    connection.setRequestProperty("Content-Type", digest == null ? "application/json" : "application/octet-stream");
                    if (digest != null) connection.setRequestProperty("X-Content-SHA256", digest);
                    try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                }
                int status = connection.getResponseCode();
                if (status == 204 && "GET".equals(method) && path.startsWith("/plan/")) return new byte[0];
                if (status != 200) {
                    if (status >= 400 && status < 500) throw new IllegalStateException("receiver_http_" + status);
                    throw new IOException("receiver_http_" + status);
                }
                try (InputStream input = connection.getInputStream()) { return readBounded(input, 262144); }
            } catch (IOException error) {
                last = error;
                if (attempt + 1 < (retry ? 3 : 1)) Thread.sleep(1000);
            } finally {
                if (activeConnection == connection) activeConnection = null;
                if (connection != null) connection.disconnect();
            }
        }
        throw last;
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > maximum - output.size()) throw new IOException("read_limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void checkTime() throws IOException {
        if (expired || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline) throw new IOException("backup_deadline");
    }

    private static JSONObject json(byte[] bytes) throws Exception { return new JSONObject(new String(bytes, UTF8)); }
    private static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(Locale.US, "%02x", value & 255));
        return text.toString();
    }
    private static long number(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("invalid_integer");
        return ((Number) value).longValue();
    }
    private void log(String value) { if (diagnostics.length() + value.length() < 3800) diagnostics.append(value).append('\n'); }
    private KtApkExport.Result result(String message) {
        return new KtApkExport.Result(message, "capture=" + capture + "\n" + diagnostics);
    }
    private static final class Snapshot {
        final Map<String, SystemBackupPolicy.Block> blocks;
        final Set<String> writable;
        Snapshot(Map<String, SystemBackupPolicy.Block> blocks, Set<String> writable) { this.blocks = blocks; this.writable = writable; }
    }

    private static String source(SystemBackupProtocol.Query query) {
        switch (query) {
            case PIPE_TEST: return "builtin:pipe-test";
            case ROOT_STATUS: return "/proc/self/status";
            case PARTITIONS: return "/proc/partitions";
            case MOUNTS: return "/proc/mounts";
            case MOUNTINFO: return "/proc/self/mountinfo";
            case CMDLINE: return "/proc/cmdline";
            case CPUINFO: return "/proc/cpuinfo";
            case INIT_RC: return "/init.rc";
            case INIT_BOARD: return "/init.bigfish.rc";
            case INIT_USB: return "/init.usb.rc";
            case FSTAB_BOARD: return "/fstab.bigfish";
            case BUILD_PROP: return "/system/build.prop";
            case DEFAULT_PROP: return "/default.prop";
            case BLOCK_MAP: return "sysfs:mmcblk0";
            case PARTITION_HEADER: return "/dev/block/mmcblk0@0+17408";
            case PROC_MTD: return "/proc/mtd";
            case MTD_MAP: return "sysfs:mtd";
            case INIT_ENV: return "/init.environ.rc";
            case INIT_ZYGOTE: return "/init.zygote32.rc";
            case INIT_TRACE: return "/init.trace.rc";
            case INIT_HIDOLPHIN: return "/init.hidolphin.rc";
            case SPI_FASTBOOT: return "mtd:nor:fastboot";
            case SPI_BOOTARGS: return "mtd:nor:bootargs";
            case PROC_DEVICES: return "/proc/devices";
            case PROC_MODULES: return "/proc/modules";
            case PROC_IOMEM: return "/proc/iomem";
            case KERNEL_CONFIG: return "/proc/config.gz";
            case KERNEL_LOG: return "builtin:dmesg";
            case FLASH_SYSFS: return "sysfs:flash-platform";
            case MMC_INFO: return "sysfs:mmc-info";
            default: throw new IllegalArgumentException("unknown_query");
        }
    }
}
