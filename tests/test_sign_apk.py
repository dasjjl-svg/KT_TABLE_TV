import importlib.util
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('sign_apk_script', ROOT / 'scripts/sign-apk.py')
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class SignApkUnitTests(unittest.TestCase):
    def test_v1_signature_entry_filter_preserves_runtime_metadata(self):
        for sig in (
            'META-INF/MANIFEST.MF',
            'META-INF/manifest.mf',
            'META-INF/CERT.SF',
            'META-INF/CERT.RSA',
            'META-INF/SPOCKY.DSA',
            'META-INF/KEY.EC',
            'META-INF/SIG-CUSTOM',
            'META-INF/sig-v1',
        ):
            self.assertTrue(MOD.is_v1_signature_entry(sig), sig)
        for keep in (
            'META-INF/',
            'META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory',
            'META-INF/androidx.core_core.version',
            'META-INF/app_release.kotlin_module',
            'META-INF/com/android/build/gradle/app-metadata.properties',
            'META-INF/nested/CERT.RSA',
            'AndroidManifest.xml',
            'classes.dex',
        ):
            self.assertFalse(MOD.is_v1_signature_entry(keep), keep)

    def test_strip_apk_signatures_preserves_compression_and_services(self):
        with tempfile.TemporaryDirectory() as directory:
            src = Path(directory) / 'in.apk'
            dst = Path(directory) / 'out.apk'
            with zipfile.ZipFile(src, 'w') as zf:
                zf.writestr('AndroidManifest.xml', b'manifest', compress_type=zipfile.ZIP_DEFLATED)
                zf.writestr('resources.arsc', b'arsc-uncompressed', compress_type=zipfile.ZIP_STORED)
                zf.writestr('lib/armeabi-v7a/libdemo.so', b'\x7fELFdemo', compress_type=zipfile.ZIP_STORED)
                zf.writestr('META-INF/', b'', compress_type=zipfile.ZIP_STORED)
                zf.writestr('META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory', b'demo.Factory\n')
                zf.writestr('META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\r\n')
                zf.writestr('META-INF/DEVKEY.SF', b'sf')
                zf.writestr('META-INF/DEVKEY.RSA', b'rsa')
            stats = MOD.strip_apk_signatures(src, dst)
            self.assertEqual(stats['stripped'], 3)
            self.assertEqual(stats['preserved_meta_inf'], ['META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory'])
            with zipfile.ZipFile(dst, 'r') as zf:
                infos = {i.filename: i for i in zf.infolist()}
                self.assertIn('AndroidManifest.xml', infos)
                self.assertIn('META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory', infos)
                self.assertNotIn('META-INF/MANIFEST.MF', infos)
                self.assertNotIn('META-INF/DEVKEY.SF', infos)
                self.assertNotIn('META-INF/DEVKEY.RSA', infos)
                self.assertEqual(infos['resources.arsc'].compress_type, zipfile.ZIP_STORED)
                self.assertEqual(infos['lib/armeabi-v7a/libdemo.so'].compress_type, zipfile.ZIP_STORED)
                self.assertEqual(infos['AndroidManifest.xml'].compress_type, zipfile.ZIP_DEFLATED)

    def test_strip_rejects_missing_manifest_traversal_and_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            no_manifest = Path(directory) / 'no-manifest.apk'
            with zipfile.ZipFile(no_manifest, 'w') as zf:
                zf.writestr('classes.dex', b'dex')
            with self.assertRaisesRegex(ValueError, 'AndroidManifest.xml'):
                MOD.strip_apk_signatures(no_manifest, Path(directory) / 'out1.apk')

            traversal = Path(directory) / 'traversal.apk'
            with zipfile.ZipFile(traversal, 'w') as zf:
                zf.writestr('AndroidManifest.xml', b'm')
                zf.writestr('../evil.txt', b'bad')
            with self.assertRaisesRegex(ValueError, 'Unsafe APK ZIP entry'):
                MOD.strip_apk_signatures(traversal, Path(directory) / 'out2.apk')

            symlink_apk = Path(directory) / 'symlink.apk'
            with zipfile.ZipFile(symlink_apk, 'w') as zf:
                zf.writestr('AndroidManifest.xml', b'm')
                sym = zipfile.ZipInfo('assets/link')
                sym.external_attr = (stat.S_IFLNK | 0o777) << 16
                zf.writestr(sym, b'/etc/hosts')
            with self.assertRaisesRegex(ValueError, 'Symbolic link'):
                MOD.strip_apk_signatures(symlink_apk, Path(directory) / 'out3.apk')

    def test_parse_badging_and_shared_user_id(self):
        badging = "\n".join([
            "package: name='com.example.launcher' versionCode='450' versionName='4.50' platformBuildVersionName='14'",
            "sdkVersion:'21'",
            "targetSdkVersion:'34'",
            "native-code: 'armeabi-v7a' 'arm64-v8a'",
        ])
        parsed = MOD.parse_badging(badging)
        self.assertEqual(parsed['package'], 'com.example.launcher')
        self.assertEqual(parsed['version_code'], '450')
        self.assertEqual(parsed['version_name'], '4.50')
        self.assertEqual(parsed['min_sdk'], 21)
        self.assertEqual(parsed['target_sdk'], 34)
        self.assertEqual(parsed['native_abis'], ['armeabi-v7a', 'arm64-v8a'])
        self.assertIsNone(parsed['split'])

        self.assertTrue(MOD.has_shared_user_id('  A: android:sharedUserId(0x0101000b)="android.uid.system"'))
        self.assertTrue(MOD.has_shared_user_id('  A: android:sharedUserId="com.example.shared"'))
        self.assertFalse(MOD.has_shared_user_id('  A: android:versionCode(0x0101021b)=(type 0x10)0x1'))

    def test_inspect_apk_policies(self):
        def run_inspect(badging, xmltree, allow_newer_sdk=False, allow_incompatible_abi=False):
            with mock.patch.object(MOD, 'checked_output', side_effect=[badging.encode(), xmltree.encode()]):
                return MOD.inspect_apk(Path('fixture.apk'), Path('/bin/aapt'),
                                       allow_newer_sdk=allow_newer_sdk,
                                       allow_incompatible_abi=allow_incompatible_abi)

        ok_badging = "package: name='com.example.tv' versionCode='1' versionName='1.0'\nsdkVersion:'21'\ntargetSdkVersion:'34'\nnative-code: 'armeabi-v7a' 'arm64-v8a'\n"
        self.assertEqual(run_inspect(ok_badging, 'E: manifest')['package'], 'com.example.tv')

        with self.assertRaisesRegex(ValueError, 'sharedUserId'):
            run_inspect(ok_badging, 'E: manifest\n  A: android:sharedUserId(0x0101000b)="android.uid.system"\n')

        newer_sdk = "package: name='com.example.tv' versionCode='1' versionName='1.0'\nsdkVersion:'23'\n"
        with self.assertRaisesRegex(ValueError, 'API 23'):
            run_inspect(newer_sdk, 'E: manifest')
        self.assertEqual(run_inspect(newer_sdk, 'E: manifest', allow_newer_sdk=True)['min_sdk'], 23)

        arm64_only = "package: name='com.example.tv' versionCode='1' versionName='1.0'\nsdkVersion:'21'\nnative-code: 'arm64-v8a'\n"
        with self.assertRaisesRegex(ValueError, 'armeabi-v7a'):
            run_inspect(arm64_only, 'E: manifest')
        self.assertEqual(run_inspect(arm64_only, 'E: manifest', allow_incompatible_abi=True)['native_abis'], ['arm64-v8a'])

    def test_parse_manifest_entries_handles_wrapped_and_utf8_names(self):
        long_path = 'res/drawable-xhdpi-v4/very_long_resource_filename_한글_0123456789_abcdef.png'
        raw_line = ('Name: ' + long_path).encode('utf-8')
        # Simulate JAR 72-byte folding right in the middle of a multi-byte UTF-8 sequence.
        folded = raw_line[:50] + b'\r\n ' + raw_line[50:]
        manifest = b'Manifest-Version: 1.0\r\n\r\n' + folded + b'\r\nSHA-256-Digest: AAAA\r\n\r\n'
        entries = MOD.parse_manifest_entries(manifest)
        self.assertIn(long_path, entries)
        self.assertEqual(entries[long_path]['SHA-256-Digest'], 'AAAA')

    def test_safe_zip_date_time_clamps_invalid_dates(self):
        self.assertEqual(MOD.safe_zip_date_time((1970, 1, 1, 0, 0, 0)), (1980, 1, 1, 0, 0, 0))
        self.assertEqual(MOD.safe_zip_date_time((1980, 0, 0, 0, 0, 0)), (1980, 1, 1, 0, 0, 0))
        self.assertEqual(MOD.safe_zip_date_time((2024, 5, 6, 7, 8, 9)), (2024, 5, 6, 7, 8, 9))

    def test_end_to_end_sign_when_tools_and_key_available(self):
        try:
            tools = MOD.resolve_tools()
            key_dir = Path(os.environ.get('TEST_PLATFORM_KEY_DIR') or ROOT / 'runtime/panel-signing-transition')
            if not (key_dir / 'platform.pk8').is_file() or not (key_dir / 'platform.x509.pem').is_file():
                self.skipTest('AOSP platform key not present locally')
            toolchain = MOD.load_toolchain()
            android_jar = toolchain.paths(required=False)['jar']
            if not android_jar.is_file():
                self.skipTest('android-22 android.jar not present locally')
        except ValueError as error:
            self.skipTest(str(error))

        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            manifest = work / 'AndroidManifest.xml'
            manifest.write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    package="com.example.projectivytest" android:versionCode="7" android:versionName="1.7">\n'
                '    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="34" />\n'
                '    <application android:label="LauncherTest" />\n'
                '</manifest>\n',
                encoding='utf-8',
            )
            source_apk = work / 'launcher.apk'
            subprocess.run([str(tools['aapt']), 'package', '-f', '-M', str(manifest),
                            '-I', str(android_jar), '-F', str(source_apk)], check=True)
            with zipfile.ZipFile(source_apk, 'a') as zf:
                zf.writestr('META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory', 'test.Main\n')
                zf.writestr('META-INF/OLDDEV.SF', 'stale-sf')
                zf.writestr('META-INF/OLDDEV.RSA', 'stale-rsa')
                zf.writestr('lib/armeabi-v7a/libsample.so', b'\x7fELF' + b'\x00' * 64, compress_type=zipfile.ZIP_STORED)

            output_apk = work / 'launcher-kt-signed.apk'
            result = MOD.sign_apk(source_apk, output_apk=output_apk, key_dir=key_dir, tools=tools)
            self.assertEqual(result['metadata']['package'], 'com.example.projectivytest')
            self.assertEqual(result['signer_sha1'], MOD.APPROVED_CERT_SHA1)
            self.assertEqual(result['signer_sha1_base64'], MOD.APPROVED_CERT_SHA1_BASE64)
            self.assertTrue(output_apk.is_file())
            with zipfile.ZipFile(output_apk, 'r') as zf:
                names = set(zf.namelist())
                self.assertIn('META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory', names)
                self.assertNotIn('META-INF/OLDDEV.SF', names)
                self.assertNotIn('META-INF/OLDDEV.RSA', names)


if __name__ == '__main__':
    unittest.main()
