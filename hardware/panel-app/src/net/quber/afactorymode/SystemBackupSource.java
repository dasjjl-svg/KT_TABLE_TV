package net.quber.afactorymode;

import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class SystemBackupSource {
    public interface Channel {
        int transact(byte[] payload) throws Exception;
        String property(String key) throws Exception;
    }

    public static final class Result {
        public final String status;
        public final byte[] data;
        public final boolean propertyCleared;

        Result(String status, byte[] data, boolean propertyCleared) {
            this.status = status;
            this.data = data;
            this.propertyCleared = propertyCleared;
        }
    }

    private static volatile HeldPipe quarantined;

    private SystemBackupSource() {}

    public static Result read(Channel channel, SystemBackupProtocol.Query query) throws Exception {
        if (query == null) throw new IllegalArgumentException("missing_query");
        if (SystemBackupProtocol.isSpi(query)) {
            requireReady(channel);
            SpiSelection selected;
            try { selected = SpiSelection.inspect(query); }
            catch (IOException | ErrnoException | SecurityException unreadable) { selected = null; }
            if (selected == null) {
                requireReady(channel);
                return new Result("unreadable", new byte[0], true);
            }
            Result result = transfer(channel, query, null, 0, SystemBackupProtocol.MAX_BLOCK, selected, null, null);
            if (!"ok".equals(result.status) || !selected.unchanged()) {
                return new Result("ok".equals(result.status) ? "error" : result.status, new byte[0], result.propertyCleared);
            }
            return result;
        }
        int expected = query == SystemBackupProtocol.Query.PARTITION_HEADER ? 17408 : -1;
        return transfer(channel, query, null, 0, expected, null, null, null);
    }

    public static Result readBlock(Channel channel, String name, long offset, int length) throws Exception {
        SystemBackupProtocol.requireBlock(name, offset, length);
        return transfer(channel, null, name, offset, length, null, null, null);
    }

    public static Result readBootMedia(Channel channel, String apk) throws Exception {
        SystemBackupProtocol.requirePanelApk(apk);
        return transfer(channel, null, null, 0, -1, null, apk, SystemBackupProtocol.Helper.BOOT_MEDIA);
    }

    public static Result readSpiController(Channel channel, String apk) throws Exception {
        SystemBackupProtocol.requirePanelApk(apk);
        return transfer(channel, null, null, 0, -1, null, apk, SystemBackupProtocol.Helper.SPI_CONTROLLER);
    }

    public static Result readSpiIdentity(Channel channel, String apk) throws Exception {
        SystemBackupProtocol.requirePanelApk(apk);
        return transfer(channel, null, null, 0, -1, null, apk, SystemBackupProtocol.Helper.SPI_IDENTITY);
    }

    public static Result readSpiBackup(Channel channel, String apk) throws Exception {
        SystemBackupProtocol.requirePanelApk(apk);
        return transfer(channel, null, null, 0, -1, null, apk, SystemBackupProtocol.Helper.SPI_BACKUP);
    }

    public static Result readSpiSamples(Channel channel, String apk) throws Exception {
        SystemBackupProtocol.requirePanelApk(apk);
        return transfer(channel, null, null, 0, -1, null, apk, SystemBackupProtocol.Helper.SPI_READ);
    }

    public static Result maintenance(Channel channel, String apk, SystemBackupProtocol.Helper helper) throws Exception {
        SystemBackupProtocol.requirePanelApk(apk);
        if (SystemBackupProtocol.maintenanceMode(helper) == null) throw new IllegalArgumentException("maintenance_mode");
        return transfer(channel, null, null, 0, -1, null, apk, helper);
    }

    private static void requireReady(Channel channel) throws Exception {
        if (channel == null) throw new IllegalArgumentException("missing_channel");
        if (quarantined != null) throw new IOException("prior_binder_completion_unknown");
        if (!"".equals(channel.property(SystemBackupProtocol.KEY))) throw new IOException("diagnostic_property_busy");
    }

    private static Result transfer(Channel channel, SystemBackupProtocol.Query query,
            String name, long offset, int expected, SpiSelection selected, String bootApk, SystemBackupProtocol.Helper helper) throws Exception {
        requireReady(channel);
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        boolean largeHelper = bootApk != null && helper == SystemBackupProtocol.Helper.SPI_BACKUP;
        int maximum = largeHelper ? SpiBackupFrame.MAX_WIRE : expected >= 0 ? expected : SystemBackupProtocol.MAX_METADATA;
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        final Reader reader = new Reader(pipe[0], maximum + 1, largeHelper);
        final FutureTask<ReadResult> readTask = new FutureTask<ReadResult>(reader);
        Thread thread = new Thread(readTask, "panel-system-backup-pipe");
        thread.setDaemon(true);
        byte[] request;
        try {
            request = bootApk != null
                    ? SystemBackupProtocol.rootProbe(bootApk, nonce, Process.myPid(), pipe[1].getFd(), helper)
                    : selected != null
                    ? SystemBackupProtocol.spi(query, selected.nested, selected.device, nonce, Process.myPid(), pipe[1].getFd())
                    : query == null
                    ? SystemBackupProtocol.readBlock(name, offset, expected, nonce, Process.myPid(), pipe[1].getFd())
                    : SystemBackupProtocol.query(query, nonce, Process.myPid(), pipe[1].getFd());
            thread.start();
        } catch (Throwable error) {
            close(pipe[1]);
            close(pipe[0]);
            throw error;
        }
        int rawStatus;
        try {
            rawStatus = channel.transact(request);
        } catch (Throwable error) {
            quarantined = new HeldPipe(pipe, readTask);
            throw error;
        }
        close(pipe[1]);
        ReadResult received = readTask.get(5000, TimeUnit.MILLISECONDS);
        String observed = channel.property(SystemBackupProtocol.KEY);
        if (observed == null || !observed.startsWith(nonce + ":")) throw new IOException("unowned_diagnostic_result");
        String status;
        try { status = SystemBackupProtocol.parseStatus(nonce, observed); }
        catch (IllegalArgumentException invalid) { status = "error"; }
        if (!observed.equals(channel.property(SystemBackupProtocol.KEY))) throw new IOException("diagnostic_result_changed");
        int clearStatus = channel.transact(SystemBackupProtocol.clear());
        boolean cleared = "".equals(channel.property(SystemBackupProtocol.KEY));
        if (!cleared || clearStatus != 0) throw new IOException("diagnostic_clear_unverified");
        if (rawStatus != 0 || received.failed) status = "error";
        if ("ok".equals(status)) {
            if (expected >= 0) {
                if (received.overflow || received.data.length != expected) status = "error";
            } else if (received.overflow || received.data.length > maximum) {
                status = "truncated";
            }
        } else if (("absent".equals(status) || "unreadable".equals(status)) && received.data.length != 0) {
            status = "error";
        }
        byte[] data = received.data.length > maximum ? Arrays.copyOf(received.data, maximum) : received.data;
        return new Result(status, data, true);
    }

    private static final class SpiSelection {
        final SystemBackupProtocol.Query query;
        final boolean nested;
        final String device;
        final StructStat node;

        SpiSelection(SystemBackupProtocol.Query query, boolean nested, String device, StructStat node) {
            this.query = query;
            this.nested = nested;
            this.device = device;
            this.node = node;
        }

        static SpiSelection inspect(SystemBackupProtocol.Query query) throws IOException, ErrnoException {
            String sys = sysPath(query);
            if (!label(query).equals(scalar(sys + "/name")) || !"nor".equals(scalar(sys + "/type"))
                    || !"1048576".equals(scalar(sys + "/size"))) return null;
            String device = scalar(sys + "/dev");
            boolean nested = true;
            StructStat node;
            try { node = Os.lstat(nodePath(query, true)); }
            catch (ErrnoException error) {
                if (error.errno != OsConstants.ENOENT) throw error;
                nested = false;
                node = Os.lstat(nodePath(query, false));
            }
            if (!OsConstants.S_ISCHR(node.st_mode) || !SystemBackupProtocol.deviceMatches(device, node.st_rdev)) return null;
            return new SpiSelection(query, nested, device, node);
        }

        boolean unchanged() {
            try {
                String sys = sysPath(query);
                StructStat current = Os.lstat(nodePath(query, nested));
                return OsConstants.S_ISCHR(current.st_mode) && current.st_dev == node.st_dev
                        && current.st_ino == node.st_ino && current.st_rdev == node.st_rdev
                        && current.st_mode == node.st_mode && SystemBackupProtocol.deviceMatches(device, current.st_rdev)
                        && label(query).equals(scalar(sys + "/name")) && "nor".equals(scalar(sys + "/type"))
                        && "1048576".equals(scalar(sys + "/size")) && device.equals(scalar(sys + "/dev"));
            } catch (IOException | ErrnoException | SecurityException changed) { return false; }
        }

        private static String index(SystemBackupProtocol.Query query) {
            if (query == SystemBackupProtocol.Query.SPI_FASTBOOT) return "0";
            if (query == SystemBackupProtocol.Query.SPI_BOOTARGS) return "1";
            throw new IllegalArgumentException("invalid_spi_query");
        }

        private static String label(SystemBackupProtocol.Query query) {
            return query == SystemBackupProtocol.Query.SPI_FASTBOOT ? "fastboot" : "bootargs";
        }

        private static String sysPath(SystemBackupProtocol.Query query) { return "/sys/class/mtd/mtd" + index(query); }

        private static String nodePath(SystemBackupProtocol.Query query, boolean nested) {
            return "/dev/" + (nested ? "mtd/" : "") + "mtd" + index(query);
        }

        private static String scalar(String path) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            try (InputStream input = new FileInputStream(path)) {
                int next;
                while ((next = input.read()) != -1) {
                    if (bytes.size() >= 128) throw new IOException("spi_metadata_limit");
                    bytes.write(next);
                }
            }
            byte[] value = bytes.toByteArray();
            int length = value.length;
            if (length > 0 && value[length - 1] == 10) length--;
            if (length == 0) throw new IOException("spi_metadata_empty");
            for (int i = 0; i < length; i++) {
                if (value[i] < 32 || value[i] > 126) throw new IOException("spi_metadata_invalid");
            }
            return new String(value, 0, length, "US-ASCII");
        }
    }

    private static final class Reader implements Callable<ReadResult> {
        private final ParcelFileDescriptor descriptor;
        private final int maximum;
        private final boolean largeHelper;

        Reader(ParcelFileDescriptor descriptor, int maximum, boolean largeHelper) {
            this.descriptor = descriptor;
            this.maximum = maximum;
            this.largeHelper = largeHelper;
        }

        public ReadResult call() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(largeHelper ? maximum : Math.min(maximum, 8192));
            boolean overflow = false;
            boolean failed = false;
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    int accepted = Math.min(count, maximum - bytes.size());
                    if (accepted > 0) bytes.write(buffer, 0, accepted);
                    if (accepted < count) overflow = true;
                }
            } catch (IOException error) {
                failed = true;
            }
            return new ReadResult(bytes.toByteArray(), overflow, failed);
        }
    }

    private static final class ReadResult {
        final byte[] data;
        final boolean overflow;
        final boolean failed;

        ReadResult(byte[] data, boolean overflow, boolean failed) {
            this.data = data;
            this.overflow = overflow;
            this.failed = failed;
        }
    }

    private static final class HeldPipe {
        final ParcelFileDescriptor[] pipe;
        final FutureTask<ReadResult> reader;

        HeldPipe(ParcelFileDescriptor[] pipe, FutureTask<ReadResult> reader) {
            this.pipe = pipe;
            this.reader = reader;
        }
    }

    private static void close(ParcelFileDescriptor descriptor) {
        try { descriptor.close(); } catch (IOException ignored) {}
    }
}
