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
import zipfile


ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


S = load('community_setup_test', 'scripts/android_setup.py')
T = load('community_tools_test', 'scripts/android_tools.py')


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


class ToolArchiveTests(unittest.TestCase):
    def metadata(self, filename='commandlinetools-mac-12345678_latest.zip'):
        return ('<repository><remotePackage path="cmdline-tools;19.0"><archives><archive>'
                '<host-os>macosx</host-os><complete><url>' + filename + '</url><checksum type="sha1">'
                + 'a' * 40 + '</checksum><size>100</size></complete></archive></archives>'
                '</remotePackage></repository>').encode()

    def test_only_the_pinned_official_archive_is_selected(self):
        self.assertEqual(T.command_tools_archive(self.metadata()),
                         ('commandlinetools-mac-12345678_latest.zip', 'sha1', 'a' * 40, 100))
        for name in ('../outside.zip', 'https://elsewhere.invalid/file.zip', 'commandlinetools-linux-12345678_latest.zip'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                T.command_tools_archive(self.metadata(name))

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


if __name__ == '__main__':
    unittest.main()
