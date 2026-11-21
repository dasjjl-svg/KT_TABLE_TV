import base64
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


S = load('community_setup_test', 'scripts/android_setup.py')
T = load('community_tools_test', 'scripts/android_tools.py')
B = load('community_build_test', 'hardware/panel-app/build.py')


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='community-setup-')
        self.root = Path(self.temporary.name).resolve()
        (self.root / 'runtime').mkdir(mode=0o700)
        self.patch = mock.patch.object(S, 'ROOT', self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.addCleanup(self.temporary.cleanup)

    def test_collector_rerun_keeps_credentials_and_updates_only_addresses(self):
        S.collector('test-collector', 4185, '192.168.10.20', '192.168.10.30')
        path = self.root / 'runtime/test-collector/config.json'
        before = json.loads(path.read_bytes())
        S.collector('test-collector', 4185, '192.168.10.21', '192.168.10.31')
        after = json.loads(path.read_bytes())
        self.assertEqual(before['token'], after['token'])
        self.assertEqual(after['host'], '192.168.10.21')
        self.assertEqual(after['device'], '192.168.10.31')
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        with self.assertRaises(ValueError):
            S.collector('test-collector', 4184, '192.168.10.21', '192.168.10.31')

    def test_bootstrap_key_is_generated_once_and_identity_matches(self):
        jdk = S.T.paths()['jdk']
        if not (jdk / 'keytool').exists():
            self.skipTest('JDK keytool is required for the signing fixture')
        probe = subprocess.run([str(jdk / 'keytool'), '-help'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        if probe.returncode:
            self.skipTest('Set JAVA_HOME to a working JDK to run the signing fixture')
        first = S.bootstrap_signing(jdk)
        directory = self.root / 'runtime/usb-probe-signing'
        before = {path.name: path.read_bytes() for path in directory.iterdir()}
        second = S.bootstrap_signing(jdk)
        self.assertEqual(first, second)
        self.assertEqual(before, {path.name: path.read_bytes() for path in directory.iterdir()})
        identity = json.loads(before['identity.json'])
        self.assertEqual(S.digest(first), identity['bootstrapSpkiSha256'])
        self.assertEqual(set(before), {'diagnostic.p12', 'password', 'identity.json'})
        self.assertTrue(all(stat.S_IMODE(path.stat().st_mode) == 0o600 for path in directory.iterdir()))

    def test_partial_signing_setup_is_not_replaced_with_new_keys(self):
        directory = self.root / 'runtime/usb-probe-signing'
        directory.mkdir(mode=0o700)
        (directory / 'diagnostic.p12').write_bytes(b'preserve an existing key')
        with self.assertRaises(ValueError):
            S.bootstrap_signing(Path('/unused'))
        self.assertEqual((directory / 'diagnostic.p12').read_bytes(), b'preserve an existing key')

    def test_public_signing_downloads_are_pinned_to_official_source(self):
        self.assertTrue(S.AOSP.startswith('https://android.googlesource.com/platform/build/'))
        self.assertIn('/refs/tags/android-5.1.1_r38/', S.AOSP)
        self.assertEqual(set(S.PUBLIC_SIGNING_FILES), {'platform.pk8', 'platform.x509.pem'})
        self.assertTrue(all(len(value) == 64 for value in S.PUBLIC_SIGNING_FILES.values()))

    def test_current_manifest_signs_both_apks_with_approved_aosp_platform_key(self):
        android = '{http://schemas.android.com/apk/res/android}'
        version = int(ET.parse(ROOT / 'hardware/panel-app/AndroidManifest.xml').getroot().get(android + 'versionCode'))
        self.assertGreaterEqual(version, B.BOOTSTRAP_APPROVED_FIRST_VERSION)
        self.assertTrue(B.uses_approved_signer('net.quber.afactorymode', version))
        self.assertTrue(B.uses_approved_signer('dev.homepanel.community', version))
        self.assertFalse(B.uses_approved_signer('net.quber.afactorymode', B.BOOTSTRAP_APPROVED_FIRST_VERSION - 1))
        self.assertFalse(B.uses_approved_signer('dev.homepanel.community', B.MANAGED_APPROVED_FIRST_VERSION - 1))
        self.assertEqual(base64.b64encode(bytes.fromhex(B.APPROVED_CERT_SHA1)).decode('ascii'), 'JxluOGuHXnat9wDn6oTkxu7jPfo=')
        for bad_package, bad_version in (('com.example.other', 47), ('net.quber.afactorymode', 0), ('dev.homepanel.community', True)):
            with self.subTest(package=bad_package, version=bad_version), self.assertRaises(ValueError):
                B.uses_approved_signer(bad_package, bad_version)


class ToolArchiveTests(unittest.TestCase):
    def metadata(self, filename='commandlinetools-mac-12345678_latest.zip', host='macosx'):
        return ('<repository><remotePackage path="cmdline-tools;19.0"><archives><archive>'
                '<host-os>' + host + '</host-os><complete><url>' + filename + '</url><checksum type="sha1">'
                + 'a' * 40 + '</checksum><size>100</size></complete></archive></archives>'
                '</remotePackage></repository>').encode()

    def test_only_the_pinned_official_archive_is_selected(self):
        host = T.TOOLS.host_info('Darwin', 'arm64')
        self.assertEqual(T.command_tools_archive(self.metadata(), host),
                         ('commandlinetools-mac-12345678_latest.zip', 'sha1', 'a' * 40, 100))
        for name in ('../outside.zip', 'https://elsewhere.invalid/file.zip', 'commandlinetools-linux-12345678_latest.zip'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                T.command_tools_archive(self.metadata(name), host)

    def test_linux_and_wsl_use_linux_archive_not_macos_or_windows(self):
        host = T.TOOLS.host_info('Linux', 'x86_64')
        raw = self.metadata('commandlinetools-linux-12345678_latest.zip', 'linux')
        self.assertEqual(T.command_tools_archive(raw, host)[0], 'commandlinetools-linux-12345678_latest.zip')
        for wrong in (self.metadata(), self.metadata('commandlinetools-win-12345678_latest.zip', 'windows'),
                      self.metadata('commandlinetools-mac-12345678_latest.zip', 'linux')):
            with self.subTest(metadata=wrong), self.assertRaises(ValueError):
                T.command_tools_archive(wrong, host)

    def test_mixed_archive_metadata_selects_matching_os(self):
        mac = self.metadata().decode().split('<archive>')[1].split('</archive>')[0]
        linux = self.metadata('commandlinetools-linux-12345678_latest.zip', 'linux').decode().split('<archive>')[1].split('</archive>')[0]
        raw = ('<repository><remotePackage path="cmdline-tools;19.0"><archives><archive>' + mac
               + '</archive><archive>' + linux + '</archive></archives></remotePackage></repository>').encode()
        self.assertIn('-mac-', T.command_tools_archive(raw, T.TOOLS.host_info('Darwin', 'x86_64'))[0])
        self.assertIn('-linux-', T.command_tools_archive(raw, T.TOOLS.host_info('Linux', 'x86_64'))[0])

    def test_duplicate_or_malformed_host_archive_is_rejected(self):
        host = T.TOOLS.host_info('Linux', 'x86_64')
        raw = self.metadata('commandlinetools-linux-12345678_latest.zip', 'linux')
        duplicate = raw.replace(b'</archives>', raw.split(b'<archive>')[1].split(b'</archive>')[0].join([b'<archive>', b'</archive></archives>']))
        invalid = [duplicate, raw.replace(b'a' * 40, b'a' * 38), raw.replace(b'<size>100</size>', b'<size>-1</size>'),
                   raw.replace(b'<complete>', b'<other>').replace(b'</complete>', b'</other>')]
        for value in invalid:
            with self.subTest(metadata=value), self.assertRaises(ValueError):
                T.command_tools_archive(value, host)

    def test_zip_escape_and_symlink_are_rejected(self):
        for filename, symlink in [('cmdline-tools/../../escape', False), ('/absolute', False), ('cmdline-tools/bin/link', True)]:
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / 'tools.zip'
                with zipfile.ZipFile(archive, 'w') as output:
                    entry = zipfile.ZipInfo(filename)
                    if symlink:
                        entry.create_system = 3
                        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                    output.writestr(entry, b'content')
                with self.assertRaises(ValueError):
                    T.unpack(archive, Path(temporary) / 'expanded')


class HostToolchainTests(unittest.TestCase):
    def test_supported_host_architectures_choose_explicit_ndk_tag(self):
        for machine in ('x86_64', 'arm64'):
            with self.subTest(machine=machine):
                self.assertEqual(T.TOOLS.host_info('Darwin', machine)['ndk'], 'darwin-x86_64')
        for machine in ('x86_64', 'AMD64'):
            with self.subTest(machine=machine):
                self.assertEqual(T.TOOLS.host_info('Linux', machine)['ndk'], 'linux-x86_64')

    def test_native_windows_and_unsupported_linux_arm_stop_explicitly(self):
        with self.assertRaisesRegex(ValueError, 'WSL2'):
            T.TOOLS.host_info('Windows', 'AMD64')
        for system, machine in (('Linux', 'aarch64'), ('Linux', 'i686'), ('FreeBSD', 'x86_64')):
            with self.subTest(system=system, machine=machine), self.assertRaises(ValueError):
                T.TOOLS.host_info(system, machine)

    def test_linux_jdk_follows_distribution_alternatives_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            actual = root / 'jdk/bin/javac'
            actual.parent.mkdir(parents=True)
            actual.touch()
            link = root / 'javac'
            link.symlink_to(actual)
            with mock.patch.object(T.TOOLS.shutil, 'which', return_value=str(link)), mock.patch.object(T.TOOLS.platform, 'system', return_value='Linux'):
                self.assertEqual(T.TOOLS.find_jdk(), actual.resolve().parent)

    def test_linux_paths_keep_configuration_overrides_and_require_linux_native_tools(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            environment = {'ANDROID_SDK_ROOT': str(root / 'sdk'), 'JAVA_HOME': str(root / 'jdk'),
                           'ANDROID_NDK_HOME': str(root / 'ndk')}
            with mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(T.TOOLS, 'settings', return_value={}), \
                    mock.patch.object(T.TOOLS.platform, 'system', return_value='Linux'), mock.patch.object(T.TOOLS.platform, 'machine', return_value='x86_64'):
                paths = T.TOOLS.paths()
                self.assertEqual(paths['jdk'], root / 'jdk/bin')
                self.assertEqual(paths['native'], root / 'ndk/toolchains/llvm/prebuilt/linux-x86_64/bin')
                with mock.patch.object(T.TOOLS, 'require_host_programs'), self.assertRaisesRegex(ValueError, 'Android tools missing'):
                    T.TOOLS.paths(required=True)

    def test_missing_ksh_fails_before_any_shell_or_download_runs(self):
        with mock.patch.object(Path, 'is_file', lambda path: path != Path('/bin/ksh')), \
                mock.patch.object(T.TOOLS.os, 'access', return_value=True), mock.patch.object(T.TOOLS.subprocess, 'run') as command:
            with self.assertRaisesRegex(ValueError, 'Install ksh'):
                T.TOOLS.require_host_programs(Path('/usr/bin/clang'))
            command.assert_not_called()

    def test_ksh_requires_android_compatible_print_behavior(self):
        with mock.patch.object(Path, 'is_file', return_value=True), mock.patch.object(T.TOOLS.os, 'access', return_value=True), \
                mock.patch.object(T.TOOLS.subprocess, 'run') as command:
            command.return_value = subprocess.CompletedProcess([], 0, b'home-panel-shell-check', b'')
            T.TOOLS.require_host_programs(Path('/usr/bin/clang'))
            self.assertEqual(command.call_args[0][0], ['/bin/ksh', '-c', 'print -rn -- home-panel-shell-check'])
            for result in (subprocess.CompletedProcess([], 127, b'', b''),
                           subprocess.CompletedProcess([], 0, b'home-panel-shell-check\n', b'')):
                command.return_value = result
                with self.subTest(result=result), self.assertRaisesRegex(ValueError, 'working /bin/ksh'):
                    T.TOOLS.require_host_programs(Path('/usr/bin/clang'))


if __name__ == '__main__':
    unittest.main()
