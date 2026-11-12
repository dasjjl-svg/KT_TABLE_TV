package net.quber.afactorymode;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import org.json.JSONObject;

final class MaintenancePolicy {
    static final String PACKAGE = "dev.homepanel.community";
    static final String BOOTSTRAP_PACKAGE = "net.quber.afactorymode";
    static final String ACTIVITY = "net.quber.afactorymode.AFactoryModeMainActivity";
    static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Release {
        final String id, sha256;
        final int versionCode, size;
        final long expiresAt;
        Release(String id, int version, int size, String hash, long expires) {
            this.id = id; this.versionCode = version; this.size = size; this.sha256 = hash; this.expiresAt = expires;
        }
    }

    static Release verify(byte[] wire, long now) throws Exception {
        if (wire.length < 2 || wire.length > 8192) throw new IllegalArgumentException("envelope_size");
        JSONObject envelope = new JSONObject(new String(wire, UTF8));
        if (envelope.length() != 2) throw new IllegalArgumentException("envelope_shape");
        byte[] payload = decode(envelope.get("payload"));
        byte[] signature = decode(envelope.get("signature"));
        MaintenanceTrust.verify(payload, signature, Base64.decode(PanelBuildConfig.MAINTENANCE_PUBLIC_KEY, Base64.NO_WRAP));
        JSONObject data = new JSONObject(new String(payload, UTF8));
        if (data.length() != 8 || integer(data, "schema") != 1
                || !"update".equals(data.get("action")) || !PACKAGE.equals(data.get("package"))) {
            throw new IllegalArgumentException("release_target");
        }
        Object id = data.get("id"), hash = data.get("sha256");
        if (!(id instanceof String) || !(hash instanceof String)) throw new IllegalArgumentException("release_fields");
        long version = integer(data, "versionCode"), size = integer(data, "size"), expires = integer(data, "expiresAt");
        MaintenanceTrust.fields((String) id, version, size, (String) hash, expires, now);
        return new Release((String) id, (int) version, (int) size, (String) hash, expires);
    }

    static void verifyApk(Context context, File file, Release release) throws Exception {
        StructStat node = Os.lstat(file.getAbsolutePath());
        if (!OsConstants.S_ISREG(node.st_mode) || node.st_nlink != 1 || node.st_size != release.size
                || !file.getAbsolutePath().equals(file.getCanonicalPath())) throw new IllegalArgumentException("apk_file");
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(file)) { bytes = read(input, MaintenanceTrust.MAX_APK); }
        if (bytes.length != release.size || !release.sha256.equals(MaintenanceTrust.sha256(bytes))) {
            throw new IllegalArgumentException("apk_hash");
        }
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (archive == null || !PACKAGE.equals(archive.packageName) || archive.versionCode != release.versionCode
                || archive.signatures == null || archive.signatures.length != 1
                || !MaintenanceSignerPolicy.accepts(archive.packageName, archive.versionCode,
                        MaintenanceTrust.sha256(archive.signatures[0].toByteArray()))) {
            throw new IllegalArgumentException("apk_identity");
        }
    }

    static int installedVersion(Context context) throws Exception {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES);
            if (info.signatures == null || info.signatures.length != 1
                    || !MaintenanceSignerPolicy.accepts(PACKAGE, info.versionCode,
                            MaintenanceTrust.sha256(info.signatures[0].toByteArray()))) {
                throw new IllegalArgumentException("installed_identity");
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException absent) { return 0; }
    }

    static byte[] read(InputStream input, int maximum) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() > maximum - count) throw new IllegalArgumentException("response_size");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static byte[] decode(Object value) {
        if (!(value instanceof String) || !((String) value).matches("[A-Za-z0-9+/]+={0,2}")) {
            throw new IllegalArgumentException("envelope_encoding");
        }
        String text = (String) value;
        byte[] result = Base64.decode(text, Base64.NO_WRAP);
        if (!text.equals(Base64.encodeToString(result, Base64.NO_WRAP))) throw new IllegalArgumentException("envelope_encoding");
        return result;
    }

    private static long integer(JSONObject value, String key) throws Exception {
        Object item = value.get(key);
        if (!(item instanceof Integer) && !(item instanceof Long)) throw new IllegalArgumentException("release_number");
        return ((Number) item).longValue();
    }
}
