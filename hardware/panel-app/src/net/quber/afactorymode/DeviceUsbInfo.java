package net.quber.afactorymode;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;

public final class DeviceUsbInfo {
    private static final int MAX_RESULT = 2200;
    private static final int MAX_READ = 256;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private DeviceUsbInfo() {}

    public static String collect() {
        Collector collector = new Collector();
        collector.collect();
        return collector.result.toString();
    }

    private static final class Collector {
        final StringBuilder result = new StringBuilder();
        final long deadline = SystemClock.elapsedRealtime() + 2000;
        boolean full;

        void collect() {
            for (String name : new String[]{"f98c0000.hiudc", "f9880000.hi3798cv200.hiusbotg"}) {
                String path = "/sys/bus/platform/devices/" + name;
                add("platform." + name + "=" + exists(path));
                try { add("platform." + name + ".driver=" + canonical(path + "/driver")); }
                catch (Exception error) { add("platform." + name + ".driver=" + errorCode(error)); }
            }
            for (String name : new String[]{"hiudc", "hiotg", "hiusbotg"}) {
                String path = "/sys/bus/platform/drivers/" + name;
                try {
                    String[] names = names(path, "[A-Za-z0-9_.:@+\\-]{1,80}", 24);
                    int bindings = 0;
                    for (String entry : names) {
                        check();
                        if (!OsConstants.S_ISLNK(Os.lstat(path + "/" + entry).st_mode)) continue;
                        String target = canonical(path + "/" + entry);
                        if (!target.startsWith("/sys/devices/")) continue;
                        if (bindings++ >= 3) break;
                        add("driver." + name + "." + entry + "=" + target);
                        add("driver." + name + "." + entry + ".gadget=" + exists(target + "/gadget"));
                    }
                    add("driver." + name + "=present shown=" + Math.min(bindings, 3));
                } catch (Exception error) {
                    add("driver." + name + "=" + errorCode(error));
                }
            }
            String androidUsb = "/sys/class/android_usb/android0/";
            for (String node : new String[]{"functions", "enable", "state"}) {
                add("android0." + node + "=" + value(androidUsb + node, "[A-Za-z0-9_,. \\-]{1,96}"));
            }
            try {
                String path = "/sys/class/udc";
                String[] controllers = names(path, "[A-Za-z0-9_.:@+\\-]{1,80}", 4);
                add("udc=present shown=" + controllers.length);
                for (String name : controllers) {
                    add("udc." + name + ".state=" + value(path + "/" + name + "/state", "[A-Za-z0-9_ \\-]{1,48}"));
                    add("udc." + name + ".speed=" + value(path + "/" + name + "/current_speed", "[A-Za-z0-9_. \\-]{1,32}"));
                }
            } catch (Exception error) {
                add("udc=" + errorCode(error));
            }
            for (String name : new String[]{"hiudc", "hiotg", "usb_otg", "udc_hisi"}) {
                add("module." + name + "=" + exists("/sys/module/" + name));
            }
            try {
                String path = "/sys/bus/usb/devices";
                String[] devices = names(path, "(?:usb[0-9]{1,3}|[0-9]{1,3}-[0-9]{1,3}(?:\\.[0-9]{1,3}){0,7})", 12);
                add("usb.devices=present shown=" + devices.length);
                for (String name : devices) {
                    try {
                        String target = canonical(path + "/" + name);
                        String deviceClass = value(target + "/bDeviceClass", "[A-Fa-f0-9]{2}");
                        String port = value(target + "/devpath", "[0-9]{1,3}(?:\\.[0-9]{1,3}){0,7}");
                        add("usb." + name + "=" + target + " class=" + deviceClass + " port=" + port);
                    } catch (Exception error) {
                        add("usb." + name + "=" + errorCode(error));
                    }
                }
            } catch (Exception error) {
                add("usb.devices=" + errorCode(error));
            }
        }

        String canonical(String path) throws IOException, ErrnoException {
            check();
            Os.lstat(path);
            String target = new File(path).getCanonicalPath();
            if (!target.startsWith("/sys/") || target.length() > 192 || !target.matches("[A-Za-z0-9_./:@+\\-]+")) {
                throw new IOException("path_refused");
            }
            return target;
        }

        String[] names(String path, final String pattern, final int maximum) throws IOException, ErrnoException {
            final String target = canonical(path);
            StructStat before = Os.stat(target);
            if (!OsConstants.S_ISDIR(before.st_mode)) throw new IOException("not_directory");
            if (!Os.access(target, OsConstants.R_OK | OsConstants.X_OK)) throw new IOException("access_denied");
            final int[] selected = {0};
            String[] names = new File(target).list(new FilenameFilter() {
                @Override public boolean accept(File directory, String name) {
                    if (SystemClock.elapsedRealtime() >= deadline || selected[0] >= maximum || !name.matches(pattern)) return false;
                    selected[0]++;
                    return true;
                }
            });
            check();
            if (names == null) throw new IOException("list_failed");
            if (!same(before, Os.stat(target)) || !target.equals(canonical(path))) throw new IOException("path_changed");
            Arrays.sort(names);
            return names;
        }

        String exists(String path) {
            try {
                String target = canonical(path);
                StructStat stat = Os.stat(target);
                return OsConstants.S_ISDIR(stat.st_mode) ? "directory" : OsConstants.S_ISREG(stat.st_mode) ? "file" : "other";
            } catch (Exception error) {
                return errorCode(error);
            }
        }

        String value(String path, String pattern) {
            try {
                String target = canonical(path);
                StructStat before = Os.lstat(target);
                if (!OsConstants.S_ISREG(before.st_mode)) throw new IOException("not_regular");
                FileDescriptor descriptor = Os.open(target, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
                try (FileInputStream input = new FileInputStream(descriptor)) {
                    if (!same(before, Os.fstat(descriptor)) || !target.equals(canonical(path))) throw new IOException("path_changed");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[64];
                    boolean ended = false;
                    while (bytes.size() < MAX_READ) {
                        check();
                        int count = input.read(buffer, 0, Math.min(buffer.length, MAX_READ - bytes.size()));
                        if (count < 0) { ended = true; break; }
                        if (count == 0) throw new IOException("read_unavailable");
                        bytes.write(buffer, 0, count);
                    }
                    check();
                    if (!ended) throw new IOException("read_limit");
                    if (!same(before, Os.fstat(descriptor)) || !same(before, Os.lstat(target)) || !target.equals(canonical(path))) {
                        throw new IOException("path_changed");
                    }
                    String text = new String(bytes.toByteArray(), UTF8).trim();
                    if (text.length() == 0) return "empty";
                    return text.matches(pattern) ? text : "unsupported_value";
                }
            } catch (Exception error) {
                return errorCode(error);
            }
        }

        void check() throws IOException {
            if (full) throw new IOException("output_limit");
            if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline) throw new IOException("deadline");
        }

        void add(String line) {
            if (full) return;
            if (result.length() + line.length() + 1 > MAX_RESULT - 20) {
                result.append("output=limit\n");
                full = true;
            } else {
                result.append(line).append('\n');
            }
        }
    }

    private static boolean same(StructStat first, StructStat second) {
        return first.st_dev == second.st_dev && first.st_ino == second.st_ino && first.st_mode == second.st_mode
                && first.st_uid == second.st_uid && first.st_gid == second.st_gid;
    }

    private static String errorCode(Exception error) {
        if (error instanceof ErrnoException) {
            int errno = ((ErrnoException) error).errno;
            if (errno == OsConstants.ENOENT) return "ENOENT";
            if (errno == OsConstants.EACCES || errno == OsConstants.EPERM) return "EACCES";
            if (errno == OsConstants.ELOOP) return "symlink_changed";
            return "errno_" + errno;
        }
        if (error instanceof SecurityException) return "EACCES";
        String code = error instanceof IOException ? error.getMessage() : null;
        if ("access_denied".equals(code)) return "EACCES";
        return code != null && code.matches("[a-z_]{1,32}") ? code : "unavailable";
    }
}
