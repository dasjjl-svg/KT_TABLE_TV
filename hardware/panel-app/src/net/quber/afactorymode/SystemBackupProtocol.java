package net.quber.afactorymode;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;

public final class SystemBackupProtocol {
    public static final String KEY = "debug.homepanel.backup";
    public static final int MAX_METADATA = 65536;
    public static final int MAX_BLOCK = 1048576;
    public enum Helper { BOOT_MEDIA, SPI_CONTROLLER, SPI_IDENTITY, SPI_BACKUP, SPI_READ,
        MAINTENANCE_STATUS, MAINTENANCE_START_UPDATE, MAINTENANCE_CONFIGURE_HOME, MAINTENANCE_RESTORE_HOME,
        MAINTENANCE_REPAIR_HOME }

    public enum Query {
        PIPE_TEST, ROOT_STATUS, PARTITIONS, MOUNTS, MOUNTINFO, CMDLINE, CPUINFO,
        INIT_RC, INIT_BOARD, INIT_USB, FSTAB_BOARD, BUILD_PROP, DEFAULT_PROP, BLOCK_MAP, PARTITION_HEADER,
        PROC_MTD, MTD_MAP, INIT_ENV, INIT_ZYGOTE, INIT_TRACE, INIT_HIDOLPHIN, SPI_FASTBOOT, SPI_BOOTARGS,
        PROC_DEVICES, PROC_MODULES, PROC_IOMEM, KERNEL_CONFIG, KERNEL_LOG, FLASH_SYSFS, MMC_INFO
    }

    private SystemBackupProtocol() {}

    public static byte[] query(Query query, String nonce, int pid, int fd) {
        requireNonce(nonce);
        String destination = destination(pid, fd);
        if (query == null) throw new IllegalArgumentException("missing_query");
        if (isSpi(query)) throw new IllegalArgumentException("spi_requires_verified_node");
        String script;
        if (query == Query.PIPE_TEST) {
            script = "if echo home-panel-pipe-ok >" + destination + ";then echo ok;else echo error;fi";
        } else if (query == Query.PARTITION_HEADER) {
            script = "p=/dev/block/mmcblk0;if [ ! -b \"$p\" ];then echo absent;"
                    + "elif [ ! -r \"$p\" ];then echo unreadable;"
                    + "elif dd if=\"$p\" bs=512 count=34 >" + destination
                    + " 2>/dev/null;then echo ok;else echo error;fi";
        } else if (query == Query.BLOCK_MAP) {
            script = "if { " + blockMap() + ";} >" + destination + ";then echo ok;else echo error;fi";
        } else if (query == Query.MTD_MAP) {
            script = "if { " + mtdMap() + ";} >" + destination + ";then echo ok;else echo error;fi";
        } else if (query == Query.KERNEL_LOG) {
            script = "if /system/bin/dmesg >" + destination + " 2>/dev/null;then echo ok;else echo error;fi";
        } else if (query == Query.FLASH_SYSFS) {
            script = "if ( " + flashSysfs() + ";) >" + destination + ";then echo ok;else echo error;fi";
        } else if (query == Query.MMC_INFO) {
            script = "if ( " + mmcInfo() + ";) >" + destination + ";then echo ok;else echo error;fi";
        } else {
            String path = source(query);
            script = "p=" + path + ";if [ ! -e \"$p\" ];then "
                    + "if [ -L \"$p\" ];then echo unreadable;else echo absent;fi;"
                    + "elif [ ! -f \"$p\" ]||[ ! -r \"$p\" ];then echo unreadable;"
                    + "elif dd if=\"$p\" ibs=1 obs=4096 count=65537 >" + destination
                    + " 2>/dev/null;then echo ok;else echo error;fi";
        }
        return command(nonce, script);
    }

    public static boolean isSpi(Query query) {
        return query == Query.SPI_FASTBOOT || query == Query.SPI_BOOTARGS;
    }

    public static byte[] bootMedia(String apk, String nonce, int pid, int fd) {
        return rootProbe(apk, nonce, pid, fd, Helper.BOOT_MEDIA);
    }

    public static byte[] spiController(String apk, String nonce, int pid, int fd) {
        return rootProbe(apk, nonce, pid, fd, Helper.SPI_CONTROLLER);
    }

    public static byte[] spiIdentity(String apk, String nonce, int pid, int fd) {
        return rootProbe(apk, nonce, pid, fd, Helper.SPI_IDENTITY);
    }

    public static byte[] spiBackup(String apk, String nonce, int pid, int fd) {
        return rootProbe(apk, nonce, pid, fd, Helper.SPI_BACKUP);
    }

    public static byte[] spiSamples(String apk, String nonce, int pid, int fd) {
        return rootProbe(apk, nonce, pid, fd, Helper.SPI_READ);
    }

    public static byte[] rootProbe(String apk, String nonce, int pid, int fd, Helper helper) {
        requireNonce(nonce);
        requirePanelApk(apk);
        if (helper == null) throw new IllegalArgumentException("missing_helper");
        String maintenance = maintenanceMode(helper);
        String entry = maintenance != null ? "MaintenanceRoot"
                : helper == Helper.BOOT_MEDIA ? "BootMediaMain"
                : helper == Helper.SPI_CONTROLLER ? "SpiControllerMain"
                : helper == Helper.SPI_IDENTITY ? "SpiIdentityMain"
                : helper == Helper.SPI_BACKUP ? "SpiBackupMain" : "SpiReadMain";
        String script = "if " + (maintenance == null ? "" : "PANEL_REQUEST_NONCE=" + nonce + " ") + "CLASSPATH=" + apk
                + " /system/bin/app_process32 /system/bin net.quber.afactorymode."
                + entry + (maintenance == null ? "" : " " + maintenance) + " >"
                + destination(pid, fd) + " 2>/dev/null;then echo ok;else echo error;fi";
        return command(nonce, script);
    }

    public static void requirePanelApk(String apk) {
        if (apk == null || !apk.matches("/data/app/(net\\.quber\\.afactorymode|dev\\.homepanel\\.community)-[1-9][0-9]{0,2}/base\\.apk")) {
            throw new IllegalArgumentException("invalid_panel_apk_path");
        }
    }

    public static String maintenanceMode(Helper helper) {
        if (helper == Helper.MAINTENANCE_STATUS) return "status";
        if (helper == Helper.MAINTENANCE_START_UPDATE) return "startUpdate";
        if (helper == Helper.MAINTENANCE_CONFIGURE_HOME) return "configureHome";
        if (helper == Helper.MAINTENANCE_RESTORE_HOME) return "restoreHome";
        if (helper == Helper.MAINTENANCE_REPAIR_HOME) return "repairHome";
        return null;
    }

    public static byte[] spi(Query query, boolean nested, String verifiedDev, String nonce, int pid, int fd) {
        requireNonce(nonce);
        if (!isSpi(query) || !validDevice(verifiedDev)) throw new IllegalArgumentException("invalid_spi_identity");
        String index = query == Query.SPI_FASTBOOT ? "0" : "1";
        String label = query == Query.SPI_FASTBOOT ? "fastboot" : "bootargs";
        String script = "s=/sys/class/mtd/mtd" + index + ";p=/dev/" + (nested ? "mtd/" : "") + "mtd" + index + ";"
                + "if read -r n <\"$s/name\"&&read -r t <\"$s/type\"&&read -r z <\"$s/size\"&&read -r d <\"$s/dev\""
                + "&&[ \"$n\" = " + label + " ]&&[ \"$t\" = nor ]&&[ \"$z\" = 1048576 ]&&[ \"$d\" = " + verifiedDev + " ]"
                + "&&[ ! -L \"$p\" ]&&[ -c \"$p\" ]&&[ -r \"$p\" ];then "
                + "if dd if=\"$p\" bs=4096 count=256 >" + destination(pid, fd)
                + " 2>/dev/null;then echo ok;else echo error;fi;else echo unreadable;fi";
        return command(nonce, script);
    }

    public static boolean deviceMatches(String value, long device) {
        if (!validDevice(value) || device < 0 || device > 0xffffffffL) return false;
        String[] parts = value.split(":");
        long major = Long.parseLong(parts[0]);
        long minor = Long.parseLong(parts[1]);
        long encoded = (minor & 255L) | (major << 8) | ((minor & ~255L) << 12);
        return encoded == device;
    }

    private static boolean validDevice(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,3}):(?:0|[1-9][0-9]{0,6})")) return false;
        String[] parts = value.split(":");
        return Long.parseLong(parts[0]) <= 4095 && Long.parseLong(parts[1]) <= 1048575;
    }

    public static byte[] readBlock(String name, long offset, int length, String nonce, int pid, int fd) {
        requireNonce(nonce);
        requireBlock(name, offset, length);
        String script = "p=/dev/block/" + name + ";if [ ! -b \"$p\" ];then echo absent;"
                + "elif [ ! -r \"$p\" ];then echo unreadable;"
                + "elif dd if=\"$p\" bs=512 skip=" + (offset / 512) + " count=" + (length / 512)
                + " >" + destination(pid, fd) + " 2>/dev/null;then echo ok;else echo error;fi";
        return command(nonce, script);
    }

    public static void requireBlock(String name, long offset, int length) {
        if (name == null || !name.matches("mmcblk0(?:p[1-9][0-9]{0,2}|boot[01])")
                || offset < 0 || (offset % 512) != 0 || length <= 0 || length > MAX_BLOCK
                || (length % 512) != 0 || offset > Long.MAX_VALUE - length) {
            throw new IllegalArgumentException("invalid_block_range");
        }
    }

    public static byte[] clear() {
        return pair(KEY, "\"\"");
    }

    public static String parseStatus(String nonce, String value) {
        requireNonce(nonce);
        if (value == null || value.length() > 91 || !value.startsWith(nonce + ":")) {
            throw new IllegalArgumentException("invalid_status");
        }
        String status = value.substring(9);
        if (!status.matches("ok|absent|unreadable|truncated|error")) {
            throw new IllegalArgumentException("invalid_status");
        }
        return status;
    }

    private static String blockMap() {
        return "for p in /sys/class/block/mmcblk0*;do n=${p##*/};"
                + "case $n in (mmcblk0|mmcblk0boot[01]) ;;(mmcblk0p*) v=${n#mmcblk0p};"
                + "case $v in (''|0*|*[!0-9]*) continue;;esac;[ ${#v} -le 3 ]||continue;;(*) continue;;esac;"
                + "[ -d \"$p\" ]||continue;d=;s=;r=;a=-1;"
                + "read -r d <\"$p/dev\"||exit 1;read -r s <\"$p/size\"||exit 1;"
                + "read -r r <\"$p/ro\"||exit 1;"
                + "if [ -f \"$p/start\" ];then read -r a <\"$p/start\"||exit 1;fi;"
                + "echo \"$n|$d|$s|$a|$r\"||exit 1;done";
    }

    private static String mtdMap() {
        return "for p in /sys/class/mtd/mtd[0-9]*;do n=${p##*/};v=${n#mtd};"
                + "case $v in (0|[1-9]|[1-9][0-9]) ;;(*) continue;;esac;"
                + "[ -d \"$p\" ]||continue;t=;s=;e=;a=;d=;"
                + "read -r t <\"$p/type\"||exit 1;read -r s <\"$p/size\"||exit 1;"
                + "read -r e <\"$p/erasesize\"||exit 1;read -r a <\"$p/name\"||exit 1;"
                + "read -r d <\"$p/dev\"||exit 1;"
                + "case $t$s$e$d in (*[!A-Za-z0-9_:-]*) exit 1;;esac;"
                + "case $a in (*[!A-Za-z0-9_.\\ -]*) exit 1;;esac;"
                + "echo \"$n|$t|$s|$e|$a|$d\"||exit 1;done";
    }

    private static String flashSysfs() {
        return "for p in /sys/bus/platform/devices/* /sys/bus/spi/devices/*;do n=${p##*/};"
                + "case $n in (*fmc*|*sfc*|*spi*|*nand*|nor|*.nor) ;;(*) continue;;esac;"
                + "[ -d \"$p\" ]||continue;echo \"node=$p\"||exit 1;"
                + "for a in modalias resource uevent;do f=$p/$a;[ -f \"$f\" ]||continue;"
                + "echo \"field=$a\"||exit 1;"
                + "if [ -r \"$f\" ];then cat \"$f\"||exit 1;else echo unreadable;fi;done;done";
    }

    private static String mmcInfo() {
        return "for p in /sys/class/block/mmcblk0/device/name /sys/class/block/mmcblk0/device/type "
                + "/sys/class/block/mmcblk0/device/manfid /sys/class/block/mmcblk0/device/oemid "
                + "/sys/class/block/mmcblk0/device/hwrev /sys/class/block/mmcblk0/device/fwrev "
                + "/sys/class/block/mmcblk0/device/ext_csd /sys/kernel/debug/mmc0/mmc0:*/ext_csd "
                + "/sys/class/block/mmcblk0boot[01]/force_ro;do [ -f \"$p\" ]||continue;"
                + "echo \"field=$p\"||exit 1;"
                + "if [ -r \"$p\" ];then cat \"$p\"||exit 1;else echo unreadable;fi;done";
    }

    private static String source(Query query) {
        switch (query) {
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
            case PROC_MTD: return "/proc/mtd";
            case INIT_ENV: return "/init.environ.rc";
            case INIT_ZYGOTE: return "/init.zygote32.rc";
            case INIT_TRACE: return "/init.trace.rc";
            case INIT_HIDOLPHIN: return "/init.hidolphin.rc";
            case PROC_DEVICES: return "/proc/devices";
            case PROC_MODULES: return "/proc/modules";
            case PROC_IOMEM: return "/proc/iomem";
            case KERNEL_CONFIG: return "/proc/config.gz";
            default: throw new IllegalArgumentException("unsupported_query");
        }
    }

    private static String destination(int pid, int fd) {
        if (pid <= 0 || fd < 3 || fd > 1048575) throw new IllegalArgumentException("invalid_pipe");
        return "/proc/" + pid + "/fd/" + fd;
    }

    private static byte[] command(String nonce, String script) {
        String value = "\"" + nonce + ":$(" + script + ")\"";
        if (utf8("setprop " + KEY + " " + value).length + 1 > 900) {
            throw new IllegalArgumentException("command_too_long");
        }
        return pair(KEY, value);
    }

    private static void requireNonce(String nonce) {
        if (nonce == null || !nonce.matches("[0-9a-f]{8}")) throw new IllegalArgumentException("invalid_nonce");
    }

    private static byte[] pair(String key, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string8(out, key);
        string8(out, value);
        return out.toByteArray();
    }

    private static void string8(ByteArrayOutputStream out, String value) {
        byte[] bytes = utf8(value);
        for (int shift = 0; shift < 32; shift += 8) out.write((bytes.length >>> shift) & 255);
        if (bytes.length == 0) return;
        out.write(bytes, 0, bytes.length);
        out.write(0);
        for (int p = (4 - ((bytes.length + 1) & 3)) & 3; p > 0; p--) out.write(0);
    }

    private static byte[] utf8(String value) {
        try { return value.getBytes("UTF-8"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
