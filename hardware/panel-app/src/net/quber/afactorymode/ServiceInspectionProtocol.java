package net.quber.afactorymode;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;

final class ServiceInspectionProtocol {
    static final String KEY = "debug.homepanel.inspect";

    enum Query { UID, CAPS, INIT_MAIN, INIT_BOARD, INIT_USB, ADB_FILES }

    private ServiceInspectionProtocol() {}

    static byte[] query(Query query, String nonce) {
        requireNonce(nonce);
        if (query == null) throw new IllegalArgumentException("missing_query");
        String value = "\"" + nonce + ":$(" + script(query) + ")\"";
        if (utf8("setprop " + KEY + " " + value).length + 1 > 900) {
            throw new IllegalArgumentException("command_too_long");
        }
        return pair(KEY, value);
    }

    static byte[] clear() {
        return pair(KEY, "\"\"");
    }

    static String parse(Query query, String nonce, String propertyValue) {
        requireNonce(nonce);
        if (query == null || propertyValue == null || utf8(propertyValue).length > 91
                || !propertyValue.startsWith(nonce + ":")) {
            throw new IllegalArgumentException("invalid_result");
        }
        String value = propertyValue.substring(9);
        boolean valid;
        switch (query) {
            case UID:
                valid = "unreadable".equals(value) || "invalid".equals(value) || validUids(value);
                break;
            case CAPS:
                valid = "unreadable".equals(value) || "invalid".equals(value)
                        || value.matches("[0-9a-fA-F]{16}");
                break;
            case INIT_MAIN:
            case INIT_BOARD:
            case INIT_USB:
                valid = validInit(value);
                break;
            case ADB_FILES:
                valid = value.length() <= 70
                        && value.matches("(?:absent|unreadable|p[01][01])(?:,(?:absent|unreadable|p[01][01])){2}");
                break;
            default:
                valid = false;
        }
        if (!valid) throw new IllegalArgumentException("invalid_payload");
        return value;
    }

    private static boolean validUids(String value) {
        if (!value.matches("[0-9]{1,10}(?:,[0-9]{1,10}){3}")) return false;
        String[] parts = value.split(",");
        for (String part : parts) {
            if (Long.parseLong(part) > 4294967295L) return false;
        }
        return true;
    }

    private static boolean validInit(String value) {
        if ("absent".equals(value) || "unreadable".equals(value) || "no_entry".equals(value)
                || "truncated".equals(value) || "invalid".equals(value)) return true;
        String[] fields = value.split(",", -1);
        if (fields.length != 3 || !"ok".equals(fields[0])
                || !fields[1].matches("[A-Za-z0-9_.-]{1,24}")
                || fields[2].length() > 48 || !fields[2].matches("/[A-Za-z0-9_./-]+")) return false;
        if (fields[2].contains("//") || fields[2].contains("/../") || fields[2].contains("/./")
                || fields[2].endsWith("/..") || fields[2].endsWith("/.") || fields[2].endsWith("/")) return false;
        return "adbd".equals(fields[1]) || "/sbin/adbd".equals(fields[2])
                || "/system/bin/adbd".equals(fields[2]);
    }

    private static void requireNonce(String nonce) {
        if (nonce == null || !nonce.matches("[0-9a-f]{8}")) {
            throw new IllegalArgumentException("invalid_nonce");
        }
    }

    private static String script(Query query) {
        switch (query) {
            case UID:
                return "[ -r /proc/self/status ]||{ echo unreadable;exit;};n=0;"
                        + "while read -r k a b c d e;do n=$((n+1));[ $n -le 256 ]||break;"
                        + "[ \"$k\" = Uid: ]||continue;[ -z \"$e\" ]||break;"
                        + "for v in \"$a\" \"$b\" \"$c\" \"$d\";do "
                        + "case $v in (''|*[!0-9]*) echo invalid;exit;;esac;"
                        + "[ ${#v} -le 10 ]||{ echo invalid;exit;};done;"
                        + "echo \"$a,$b,$c,$d\";exit;"
                        + "done </proc/self/status 2>/dev/null;echo invalid";
            case CAPS:
                return "[ -r /proc/self/status ]||{ echo unreadable;exit;};n=0;"
                        + "while read -r k v e;do n=$((n+1));[ $n -le 256 ]||break;"
                        + "[ \"$k\" = CapEff: ]||continue;[ -z \"$e\" ]||break;"
                        + "case $v in (*[!0-9a-fA-F]*) break;;esac;"
                        + "[ ${#v} -eq 16 ]||break;echo \"$v\";exit;"
                        + "done </proc/self/status 2>/dev/null;echo invalid";
            case INIT_MAIN:
                return initScript("/init.rc");
            case INIT_BOARD:
                return initScript("/init.bigfish.rc");
            case INIT_USB:
                return initScript("/init.usb.rc");
            case ADB_FILES:
                return "r=;for p in /sbin/adbd /system/bin/adbd /system/xbin/adbd;do "
                        + "d=${p%/*};a=0;b=0;if [ ! -x \"$d\" ];then s=unreadable;"
                        + "elif [ ! -e \"$p\" ]&&[ ! -L \"$p\" ];then s=absent;"
                        + "else [ -r \"$p\" ]&&a=1;[ -x \"$p\" ]&&b=1;s=p$a$b;fi;"
                        + "r=$r,$s;done;echo \"${r#,}\"";
            default:
                throw new IllegalArgumentException("unsupported_query");
        }
    }

    private static String initScript(String path) {
        return "LC_ALL=C;p=" + path + ";x(){ echo \"$1\";exit;};[ -x / ]||x unreadable;"
                + "[ -e \"$p\" ]||{ [ -L \"$p\" ]&&x unreadable;x absent;};"
                + "[ -f \"$p\" ]||x invalid;[ -r \"$p\" ]||x unreadable;"
                + "t=$(dd if=\"$p\" ibs=1 obs=4096 count=65536 2>/dev/null&&echo .)||x unreadable;"
                + "[ ${#t} -lt 65537 ]||x truncated;t=${t%.};"
                + "print -rn -- \"$t\"|{ n=0;while read -r a b c d||[ -n \"$a$b$c$d\" ];do "
                + "n=$((n+1));[ $n -le 1024 ]||x truncated;"
                + "[ \"$a\" = service ]||continue;case $b:$c in (adbd:*|*:/sbin/adbd|*:/system/bin/adbd) "
                + "case $b in (''|*[!A-Za-z0-9_.-]*) x invalid;;esac;"
                + "case $c in (/*) ;;(*) x invalid;;esac;"
                + "case $c in (*[!A-Za-z0-9_./-]*) x invalid;;esac;"
                + "[ ${#b} -le 24 ]&&[ ${#c} -le 48 ]||x invalid;"
                + "echo \"ok,$b,$c\";exit;;esac;done;echo no_entry;}";
    }

    private static byte[] pair(String key, String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        string8(output, key);
        string8(output, value);
        return output.toByteArray();
    }

    private static void string8(ByteArrayOutputStream output, String value) {
        byte[] bytes = utf8(value);
        int size = bytes.length;
        for (int shift = 0; shift < 32; shift += 8) output.write((size >>> shift) & 255);
        if (size == 0) return;
        output.write(bytes, 0, size);
        output.write(0);
        for (int padding = (4 - ((size + 1) & 3)) & 3; padding > 0; padding--) output.write(0);
    }

    private static byte[] utf8(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
