package net.quber.afactorymode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class SystemBackupProtocolTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String NONCE = "12ab34cd";
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (SystemBackupProtocol.Query query : SystemBackupProtocol.Query.values()) {
            if (SystemBackupProtocol.isSpi(query)) {
                final SystemBackupProtocol.Query selected = query;
                reject(new Action() { public void run() { SystemBackupProtocol.query(selected, NONCE, 1234, 21); }});
                for (boolean nested : new boolean[]{false, true}) {
                    String value = wire(SystemBackupProtocol.spi(query, nested, "90:2", NONCE, 1234, 21));
                    commandChecks(value);
                    check(value.contains("[ ! -L \"$p\" ]&&[ -c \"$p\" ]"), "spi_character_no_symlink");
                    check(value.contains("[ \"$t\" = nor ]&&[ \"$z\" = 1048576 ]"), "spi_fixed_geometry");
                    check(value.contains("bs=4096 count=256"), "spi_exact_count");
                }
            } else commandChecks(wire(SystemBackupProtocol.query(query, NONCE, 1234, 21)));
        }
        for (final String dev : new String[]{null, "", "90:2;id", "90:2\n", "090:2", "90:02", "-1:0", "4096:0", "1:1048576"}) {
            reject(new Action() { public void run() { SystemBackupProtocol.spi(SystemBackupProtocol.Query.SPI_BOOTARGS, true, dev, NONCE, 1234, 21); }});
            check(!SystemBackupProtocol.deviceMatches(dev, 23042), "invalid_device_identity");
        }
        reject(new Action() { public void run() { SystemBackupProtocol.spi(SystemBackupProtocol.Query.INIT_RC, true, "90:0", NONCE, 1234, 21); }});
        check(SystemBackupProtocol.deviceMatches("90:0", 23040), "mtd0_rdev");
        check(SystemBackupProtocol.deviceMatches("90:2", 23042), "mtd1_rdev");
        check(!SystemBackupProtocol.deviceMatches("90:2", 23041), "mtd_readonly_node_rejected");
        check(!SystemBackupProtocol.deviceMatches("90:2", -1), "negative_rdev");
        check(!SystemBackupProtocol.deviceMatches("0:0", 0x100000000L), "rdev_overflow");
        check(SystemBackupProtocol.deviceMatches("4095:1048575", 0xffffffffL), "linux_dev_t_maximum");
        check(SystemBackupProtocol.deviceMatches("90:256", 0x105a00L), "linux_dev_t_high_minor");
        expect("\"\"", wire(SystemBackupProtocol.clear()));
        String block = wire(SystemBackupProtocol.readBlock("mmcblk0p999", 1024, 1048576, NONCE, 1234, 21));
        check(block.contains("bs=512 skip=2 count=2048"), "block_range_units");
        for (String name : new String[]{"mmcblk0p1", "mmcblk0p999", "mmcblk0boot0", "mmcblk0boot1"}) {
            SystemBackupProtocol.readBlock(name, 0, 512, NONCE, 1234, 21);
        }
        for (final String name : new String[]{null, "", "mmcblk0", "mmcblk0p0", "mmcblk0p01", "mmcblk0p1000", "mmcblk1p1", "mmcblk0boot2", "mmcblk0p1;id", "../mmcblk0p1"}) {
            reject(new Action() { public void run() { SystemBackupProtocol.readBlock(name, 0, 512, NONCE, 1234, 21); }});
        }
        for (final long offset : new long[]{-512, 1, Long.MAX_VALUE - 511}) {
            reject(new Action() { public void run() { SystemBackupProtocol.readBlock("mmcblk0p1", offset, 512, NONCE, 1234, 21); }});
        }
        for (final int length : new int[]{-512, 0, 1, 513, 1049088}) {
            reject(new Action() { public void run() { SystemBackupProtocol.readBlock("mmcblk0p1", 0, length, NONCE, 1234, 21); }});
        }
        for (final String nonce : new String[]{null, "", "ABCDEF01", "1234567", "123456789", "1234567;"}) {
            reject(new Action() { public void run() { SystemBackupProtocol.query(SystemBackupProtocol.Query.ROOT_STATUS, nonce, 1234, 21); }});
        }
        reject(new Action() { public void run() { SystemBackupProtocol.query(SystemBackupProtocol.Query.PIPE_TEST, NONCE, 0, 21); }});
        reject(new Action() { public void run() { SystemBackupProtocol.query(SystemBackupProtocol.Query.PIPE_TEST, NONCE, 1234, 2); }});
        for (String status : new String[]{"ok", "absent", "unreadable", "truncated", "error"}) {
            expect(status, SystemBackupProtocol.parseStatus(NONCE, NONCE + ":" + status));
        }
        for (final String status : new String[]{"", NONCE + ":ok\n", "deadbeef:ok", NONCE + ":ok;id", NONCE + ":"}) {
            reject(new Action() { public void run() { SystemBackupProtocol.parseStatus(NONCE, status); }});
        }
        fixtures();
        bootHelperFixtures();
        System.out.println("SystemBackupProtocolTest passed: " + checks + " checks");
    }

    private static void commandChecks(String value) {
        check(("setprop " + SystemBackupProtocol.KEY + " " + value).getBytes(UTF8).length + 1 <= 900, "command_bound");
        check(!value.contains("printf"), "legacy_printf");
        check(value.contains("/proc/1234/fd/21"), "pipe_destination");
    }

    private static void bootHelperFixtures() throws Exception {
        final String apk = "/data/app/net.quber.afactorymode-1/base.apk";
        String value = wire(SystemBackupProtocol.bootMedia(apk, NONCE, 1234, 21));
        commandChecks(value);
        for (final String invalid : new String[]{null, "", "/data/local/tmp/probe.apk",
                "/data/app/net.other-1/base.apk", "/data/app/net.quber.afactorymode-0/base.apk",
                "/data/app/net.quber.afactorymode-1/../base.apk", apk + ";id", apk + "\n",
                apk + "$(id)", apk + "`id`", apk + " --flag", "'" + apk + "'"}) {
            reject(new Action() { public void run() { SystemBackupProtocol.bootMedia(invalid, NONCE, 1234, 21); }});
        }
        Path root = Files.createTempDirectory("panel-boot-helper-");
        try {
            Path output = root.resolve("output");
            value = value.replace("/system/bin/app_process32", "helper_fixture");
            String function = "helper_fixture(){ [ \"$CLASSPATH\" = " + apk + " ]||return 5;"
                    + "[ $# -eq 2 ]&&[ \"$1\" = /system/bin ]"
                    + "&&[ \"$2\" = net.quber.afactorymode.BootMediaMain ]||return 6;"
                    + "echo helper-output; };";
            expect("ok", runValue(root, value, output, function));
            expect("helper-output\n", new String(Files.readAllBytes(output), UTF8));
            expect("error", runValue(root, value, output, "helper_fixture(){ return 1; };"));
            String controller = wire(SystemBackupProtocol.spiController(apk, NONCE, 1234, 21));
            commandChecks(controller);
            controller = controller.replace("/system/bin/app_process32", "helper_fixture");
            String controllerFunction = function.replace("BootMediaMain", "SpiControllerMain");
            expect("ok", runValue(root, controller, output, controllerFunction));
            expect("helper-output\n", new String(Files.readAllBytes(output), UTF8));
            expect("error", runValue(root, controller, output, "helper_fixture(){ return 1; };"));
            reject(new Action() { public void run() { SystemBackupProtocol.spiController(apk + ";id", NONCE, 1234, 21); }});
            String identity = wire(SystemBackupProtocol.spiIdentity(apk, NONCE, 1234, 21));
            commandChecks(identity);
            identity = identity.replace("/system/bin/app_process32", "helper_fixture");
            expect("ok", runValue(root, identity, output, function.replace("BootMediaMain", "SpiIdentityMain")));
            expect("helper-output\n", new String(Files.readAllBytes(output), UTF8));
            expect("error", runValue(root, identity, output, "helper_fixture(){ return 1; };"));
            reject(new Action() { public void run() { SystemBackupProtocol.spiIdentity(apk + ";id", NONCE, 1234, 21); }});
            String backup = wire(SystemBackupProtocol.spiBackup(apk, NONCE, 1234, 21));
            commandChecks(backup);
            backup = backup.replace("/system/bin/app_process32", "helper_fixture");
            expect("ok", runValue(root, backup, output, function.replace("BootMediaMain", "SpiBackupMain")));
            expect("helper-output\n", new String(Files.readAllBytes(output), UTF8));
            expect("error", runValue(root, backup, output, "helper_fixture(){ return 1; };"));
            reject(new Action() { public void run() { SystemBackupProtocol.spiBackup(apk + ";id", NONCE, 1234, 21); }});
            String samples = wire(SystemBackupProtocol.spiSamples(apk, NONCE, 1234, 21));
            commandChecks(samples);
            samples = samples.replace("/system/bin/app_process32", "helper_fixture");
            expect("ok", runValue(root, samples, output, function.replace("BootMediaMain", "SpiReadMain")));
            expect("helper-output\n", new String(Files.readAllBytes(output), UTF8));
            expect("error", runValue(root, samples, output, "helper_fixture(){ return 1; };"));
            reject(new Action() { public void run() { SystemBackupProtocol.spiSamples(apk + ";id", NONCE, 1234, 21); }});
            reject(new Action() { public void run() { SystemBackupProtocol.rootProbe(apk, NONCE, 1234, 21, null); }});
            for (SystemBackupProtocol.Helper helper : new SystemBackupProtocol.Helper[]{
                    SystemBackupProtocol.Helper.MAINTENANCE_STATUS, SystemBackupProtocol.Helper.MAINTENANCE_START_UPDATE,
                    SystemBackupProtocol.Helper.MAINTENANCE_CONFIGURE_HOME, SystemBackupProtocol.Helper.MAINTENANCE_RESTORE_HOME,
                    SystemBackupProtocol.Helper.MAINTENANCE_REPAIR_HOME}) {
                for (String selectedApk : new String[]{apk, "/data/app/dev.homepanel.community-2/base.apk"}) {
                    String maintenance = wire(SystemBackupProtocol.rootProbe(selectedApk, NONCE, 1234, 21, helper));
                    commandChecks(maintenance);
                    check(maintenance.contains("PANEL_REQUEST_NONCE=" + NONCE), "maintenance_nonce");
                    String mode = SystemBackupProtocol.maintenanceMode(helper);
                    check(mode.matches("status|startUpdate|configureHome|restoreHome|repairHome"), "maintenance_fixed_mode");
                    if (helper == SystemBackupProtocol.Helper.MAINTENANCE_REPAIR_HOME) expect("repairHome", mode);
                    maintenance = maintenance.replace("/system/bin/app_process32", "helper_fixture");
                    String fixed = "helper_fixture(){ [ \"$CLASSPATH\" = " + selectedApk + " ]||return 5;"
                            + "[ \"$PANEL_REQUEST_NONCE\" = " + NONCE + " ]||return 6;"
                            + "[ $# -eq 3 ]&&[ \"$1\" = /system/bin ]&&[ \"$2\" = net.quber.afactorymode.MaintenanceRoot ]"
                            + "&&[ \"$3\" = " + mode + " ]||return 7;echo maintenance-ok; };";
                    expect("ok", runValue(root, maintenance, output, fixed));
                    expect("maintenance-ok\n", new String(Files.readAllBytes(output), UTF8));
                }
            }
            check(SystemBackupProtocol.maintenanceMode(SystemBackupProtocol.Helper.SPI_BACKUP) == null, "nonmaintenance_helper");
            check(SystemBackupProtocol.maintenanceMode(null) == null, "null_maintenance_helper");
            for (final String invalid : new String[]{"/data/app/dev.homepanel.community-0/base.apk", "/data/app/dev.homepanel.community-2/base.apk;id", "/data/app/dev.homepanel.community-2/base.apk\n"}) {
                reject(new Action() { public void run() { SystemBackupProtocol.rootProbe(invalid, NONCE, 1234, 21, SystemBackupProtocol.Helper.MAINTENANCE_START_UPDATE); }});
            }
        } finally { delete(root); }
    }

    private static void fixtures() throws Exception {
        Path root = Files.createTempDirectory("panel-system-backup-");
        try {
            Path source = root.resolve("input");
            Path output = root.resolve("output");
            expect("ok", run(root, SystemBackupProtocol.Query.PIPE_TEST, source, output, ""));
            check(Arrays.equals("home-panel-pipe-ok\n".getBytes(UTF8), Files.readAllBytes(output)), "pipe_marker");
            byte[] bytes = "safe\\ntext\n$(touch SHOULD_NOT_EXIST)\nuid=0\n".getBytes(UTF8);
            Files.write(source, bytes);
            expect("ok", run(root, SystemBackupProtocol.Query.INIT_RC, source, output, ""));
            check(Arrays.equals(bytes, Files.readAllBytes(output)), "exact_metadata_bytes");
            check(!Files.exists(root.resolve("SHOULD_NOT_EXIST")), "metadata_not_executed");
            byte[] large = new byte[70000];
            Arrays.fill(large, (byte) 'x');
            Files.write(source, large);
            expect("ok", run(root, SystemBackupProtocol.Query.INIT_RC, source, output, ""));
            check(Files.size(output) == 65537, "metadata_sentinel_byte");
            expect("error", run(root, SystemBackupProtocol.Query.INIT_RC, source, output, "dd(){ return 1; };"));
            Files.delete(source);
            expect("absent", run(root, SystemBackupProtocol.Query.INIT_RC, source, output, ""));
            Files.createDirectory(source);
            expect("unreadable", run(root, SystemBackupProtocol.Query.INIT_RC, source, output, ""));
            Files.delete(source);
            Path sys = root.resolve("block");
            for (String name : new String[]{"mmcblk0", "mmcblk0p1", "mmcblk0boot0", "mmcblk0p1bad", "mmcblk0p01"}) {
                Path d = sys.resolve(name);
                Files.createDirectories(d);
                Files.write(d.resolve("dev"), "179:0\n".getBytes(UTF8));
                Files.write(d.resolve("size"), "4096\n".getBytes(UTF8));
                Files.write(d.resolve("ro"), "1\n".getBytes(UTF8));
                if (name.equals("mmcblk0p1")) Files.write(d.resolve("start"), "2048\n".getBytes(UTF8));
            }
            expect("ok", run(root, SystemBackupProtocol.Query.BLOCK_MAP, sys, output, ""));
            String map = new String(Files.readAllBytes(output), UTF8);
            check(map.contains("mmcblk0|179:0|4096|-1|1\n"), "main_block");
            check(map.contains("mmcblk0p1|179:0|4096|2048|1\n"), "partition_start");
            check(map.contains("mmcblk0boot0|179:0|4096|-1|1\n"), "boot_area");
            check(!map.contains("bad") && !map.contains("p01"), "strict_block_name");
            Path mtd = root.resolve("mtd");
            for (String name : new String[]{"mtd0", "mtd1", "mtd99", "mtd00", "mtd100", "mtd0ro", "mtdbad"}) {
                Path d = mtd.resolve(name);
                Files.createDirectories(d);
                Files.write(d.resolve("dev"), "90:0\n".getBytes(UTF8));
                Files.write(d.resolve("type"), "nor\n".getBytes(UTF8));
                Files.write(d.resolve("size"), "1048576\n".getBytes(UTF8));
                Files.write(d.resolve("erasesize"), "65536\n".getBytes(UTF8));
                Files.write(d.resolve("name"), (name.equals("mtd1") ? "bootargs\n" : "fastboot\n").getBytes(UTF8));
            }
            expect("ok", run(root, SystemBackupProtocol.Query.MTD_MAP, mtd, output, ""));
            map = new String(Files.readAllBytes(output), UTF8);
            check(map.contains("mtd0|nor|1048576|65536|fastboot|90:0\n"), "mtd_fixed_fields");
            check(map.contains("mtd1|nor|1048576|65536|bootargs|90:0\n"), "mtd_bootargs");
            check(map.contains("mtd99|") && map.split("\n").length == 3, "mtd_name_filter");
            Files.write(mtd.resolve("mtd99/name"), "name with-space_1.2\n".getBytes(UTF8));
            expect("ok", run(root, SystemBackupProtocol.Query.MTD_MAP, mtd, output, ""));
            check(new String(Files.readAllBytes(output), UTF8).contains("name with-space_1.2"), "mtd_safe_name_space");
            bootMetadataFixtures(root, output);
            spiFixtures(root, mtd, output);
        } finally {
            delete(root);
        }
    }

    private static void bootMetadataFixtures(Path root, Path output) throws Exception {
        Path input = root.resolve("boot-input");
        byte[] binary = new byte[]{31, -117, 8, 0, 10, 13, -1, 0};
        for (SystemBackupProtocol.Query query : new SystemBackupProtocol.Query[]{
                SystemBackupProtocol.Query.PROC_DEVICES, SystemBackupProtocol.Query.PROC_MODULES,
                SystemBackupProtocol.Query.PROC_IOMEM, SystemBackupProtocol.Query.KERNEL_CONFIG}) {
            Files.write(input, binary);
            expect("ok", run(root, query, input, output, ""));
            check(Arrays.equals(binary, Files.readAllBytes(output)), "boot_metadata_binary_preserved");
            Files.delete(input);
            expect("absent", run(root, query, input, output, ""));
        }
        expect("ok", run(root, SystemBackupProtocol.Query.KERNEL_LOG, input, output,
                "panel_dmesg_fixture(){ [ $# -eq 0 ]||return 2;echo kernel-log; };"));
        check(new String(Files.readAllBytes(output), UTF8).equals("kernel-log\n"), "dmesg_read_without_clear");
        expect("error", run(root, SystemBackupProtocol.Query.KERNEL_LOG, input, output,
                "panel_dmesg_fixture(){ return 1; };"));
        Path sys = root.resolve("boot-sys");
        for (String name : new String[]{"platform/10000000.hifmc100", "platform/ignored", "spi/spi0.0"}) {
            Path d = sys.resolve(name);
            Files.createDirectories(d);
            Files.write(d.resolve("uevent"), ("DRIVER=fixture\nNAME=" + name + "\n$(touch SHOULD_NOT_EXIST)\n").getBytes(UTF8));
        }
        expect("ok", run(root, SystemBackupProtocol.Query.FLASH_SYSFS, sys, output, ""));
        String data = new String(Files.readAllBytes(output), UTF8);
        check(data.contains("10000000.hifmc100") && data.contains("spi0.0"), "flash_devices_collected");
        check(!data.contains("ignored"), "unrelated_platform_skipped");
        check(!Files.exists(root.resolve("SHOULD_NOT_EXIST")), "sysfs_values_not_executed");
        expect("error", run(root, SystemBackupProtocol.Query.FLASH_SYSFS, sys, output, "cat(){ return 1; };"));
        Path device = sys.resolve("block/mmcblk0/device");
        Files.createDirectories(device);
        Files.write(device.resolve("name"), "EMMC\n".getBytes(UTF8));
        Files.write(device.resolve("type"), "MMC\n".getBytes(UTF8));
        Files.write(device.resolve("cid"), "MUST_NOT_COLLECT\n".getBytes(UTF8));
        Path ext = sys.resolve("debug/mmc0:0001");
        Files.createDirectories(ext);
        Files.write(ext.resolve("ext_csd"), "0123456789abcdef\n".getBytes(UTF8));
        Path boot = sys.resolve("block/mmcblk0boot0");
        Files.createDirectories(boot);
        Files.write(boot.resolve("force_ro"), "1\n".getBytes(UTF8));
        expect("ok", run(root, SystemBackupProtocol.Query.MMC_INFO, sys, output, ""));
        data = new String(Files.readAllBytes(output), UTF8);
        check(data.contains("EMMC\n") && data.contains("0123456789abcdef\n"), "mmc_existing_fields_read");
        check(!data.contains("MUST_NOT_COLLECT"), "mmc_identifier_not_collected");
        check(new String(Files.readAllBytes(boot.resolve("force_ro")), UTF8).equals("1\n"), "force_ro_unchanged");
        expect("ok", run(root, SystemBackupProtocol.Query.FLASH_SYSFS, root.resolve("missing-sys"), output, ""));
        check(Files.size(output) == 0, "missing_flash_map_empty");
        expect("ok", run(root, SystemBackupProtocol.Query.MMC_INFO, root.resolve("missing-sys"), output, ""));
        check(Files.size(output) == 0, "missing_mmc_map_empty");
    }

    private static void spiFixtures(Path root, Path mtd, Path output) throws Exception {
        for (SystemBackupProtocol.Query query : new SystemBackupProtocol.Query[]{SystemBackupProtocol.Query.SPI_FASTBOOT, SystemBackupProtocol.Query.SPI_BOOTARGS}) {
            int index = query == SystemBackupProtocol.Query.SPI_FASTBOOT ? 0 : 1;
            for (boolean nested : new boolean[]{false, true}) {
                expect("ok", runSpi(root, query, nested, mtd, "/dev/zero", output, ""));
                byte[] data = Files.readAllBytes(output);
                check(data.length == 1048576, "spi_one_mebibyte");
                check(Arrays.equals(data, new byte[1048576]), "spi_exact_bytes");
            }
            Path sys = mtd.resolve("mtd" + index);
            for (String[] invalid : new String[][]{{"type", "nand\n"}, {"name", "wrong\n"}, {"size", "2097152\n"}, {"dev", "90:2\n"}}) {
                Path attribute = sys.resolve(invalid[0]);
                byte[] previous = Files.readAllBytes(attribute);
                Files.write(attribute, invalid[1].getBytes(UTF8));
                Files.deleteIfExists(output);
                expect("unreadable", runSpi(root, query, true, mtd, "/dev/zero", output, ""));
                check(!Files.exists(output), "spi_invalid_identity_no_read");
                Files.write(attribute, previous);
            }
        }
        Path regular = root.resolve("regular-node");
        Files.write(regular, new byte[]{1, 2, 3});
        Files.deleteIfExists(output);
        expect("unreadable", runSpi(root, SystemBackupProtocol.Query.SPI_FASTBOOT, true, mtd, regular.toString(), output, ""));
        check(!Files.exists(output), "spi_regular_file_no_read");
        Path symlink = root.resolve("symlink-node");
        Files.createSymbolicLink(symlink, java.nio.file.Paths.get("/dev/zero"));
        expect("unreadable", runSpi(root, SystemBackupProtocol.Query.SPI_FASTBOOT, true, mtd, symlink.toString(), output, ""));
        check(!Files.exists(output), "spi_symlink_no_read");
        expect("error", runSpi(root, SystemBackupProtocol.Query.SPI_FASTBOOT, true, mtd, "/dev/zero", output, "dd(){ return 1; };"));
    }

    private static String runSpi(Path directory, SystemBackupProtocol.Query query, boolean nested, Path sys,
            String node, Path output, String prefix) throws Exception {
        String index = query == SystemBackupProtocol.Query.SPI_FASTBOOT ? "0" : "1";
        String value = wire(SystemBackupProtocol.spi(query, nested, "90:0", NONCE, 1234, 21));
        value = value.replace("/sys/class/mtd/mtd" + index, sys.resolve("mtd" + index).toString());
        value = value.replace("p=/dev/" + (nested ? "mtd/" : "") + "mtd" + index + ";", "p=" + node + ";");
        return runValue(directory, value, output, prefix);
    }

    private static String run(Path directory, SystemBackupProtocol.Query query, Path source, Path output, String prefix) throws Exception {
        String value = wire(SystemBackupProtocol.query(query, NONCE, 1234, 21));
        value = value.replace("p=/init.rc;", "p=" + source + ";");
        value = value.replace("/sys/class/block/mmcblk0*", source + "/mmcblk0*");
        value = value.replace("/sys/class/mtd/mtd[0-9]*", source + "/mtd[0-9]*");
        for (String path : new String[]{"/proc/devices", "/proc/modules", "/proc/iomem", "/proc/config.gz"}) {
            value = value.replace("p=" + path + ";", "p=" + source + ";");
        }
        value = value.replace("/system/bin/dmesg", "panel_dmesg_fixture");
        value = value.replace("/sys/bus/platform/devices/", source + "/platform/");
        value = value.replace("/sys/bus/spi/devices/", source + "/spi/");
        value = value.replace("/sys/class/block/", source + "/block/");
        value = value.replace("/sys/kernel/debug/mmc0/", source + "/debug/");
        return runValue(directory, value, output, prefix);
    }

    private static String runValue(Path directory, String value, Path output, String prefix) throws Exception {
        value = value.replace("/proc/1234/fd/21", output.toString());
        Process process = new ProcessBuilder("/bin/ksh", "-c", "printf(){ return 127; };" + prefix + "print -rn -- " + value)
                .directory(directory.toFile()).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("fixture_timeout");
        }
        String status = read(process.getInputStream());
        String error = read(process.getErrorStream());
        check(process.exitValue() == 0 && error.length() == 0, "fixture_exit");
        return SystemBackupProtocol.parseStatus(NONCE, status);
    }

    private static String wire(byte[] bytes) {
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        expect(SystemBackupProtocol.KEY, string(input));
        String value = string(input);
        check(!input.hasRemaining(), "wire_end");
        return value;
    }

    private static String string(ByteBuffer input) {
        int length = input.getInt();
        check(length >= 0 && length <= 900, "wire_length");
        byte[] value = new byte[length];
        input.get(value);
        if (length != 0) {
            check(input.get() == 0, "wire_nul");
            while ((input.position() & 3) != 0) check(input.get() == 0, "wire_padding");
        }
        return new String(value, UTF8);
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[128];
        int count;
        while ((count = input.read(bytes)) != -1) {
            if (output.size() + count > 1024) throw new AssertionError("fixture_output_limit");
            output.write(bytes, 0, count);
        }
        return new String(output.toByteArray(), UTF8);
    }

    private static void delete(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) delete(child);
            }
        }
        Files.deleteIfExists(path);
    }

    private interface Action { void run(); }

    private static void reject(Action action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid_request_accepted");
    }

    private static void expect(String expected, String actual) {
        check(expected.equals(actual), "expected_" + expected + "_got_" + actual);
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
