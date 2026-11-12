import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('community_verify_install', Path(__file__).resolve().parents[1] / 'scripts/verify-install.py')
V = importlib.util.module_from_spec(spec)
spec.loader.exec_module(V)
R = V.recovery()


def manifest(version=43):
    strings = ['versionCode', 'versionName', R.ANDROID, 'android', 'manifest', 'package', R.M.PACKAGE, '1.36']
    table, offsets = bytearray(), []
    for text in strings:
        offsets.append(len(table))
        encoded = text.encode('utf-16le')
        table += struct.pack('<H', len(encoded) // 2) + encoded + b'\0\0'
    table += b'\0' * (-len(table) % 4)
    start = 28 + len(offsets) * 4
    pool = struct.pack('<HHIIIIII', 1, 28, start + len(table), len(strings), 0, 0, start, 0)
    pool += struct.pack('<' + 'I' * len(offsets), *offsets) + table
    resources = struct.pack('<HHIII', 0x180, 8, 16, 0x0101021b, 0x0101021c)
    def node(kind, body):
        return struct.pack('<HHIII', kind, 16, 16 + len(body), 1, R.NONE) + body
    attrs = [(2, 0, R.NONE, 0x10, version), (2, 1, 7, 3, 7), (R.NONE, 5, 6, 3, 6)]
    body = struct.pack('<IIHHHHHH', R.NONE, 4, 20, 20, len(attrs), 0, 0, 0)
    body += b''.join(struct.pack('<IIIHBBI', namespace, name, raw, 8, 0, kind, value) for namespace, name, raw, kind, value in attrs)
    nodes = node(0x100, struct.pack('<II', 3, 2)) + node(0x102, body)
    nodes += node(0x103, struct.pack('<II', R.NONE, 4)) + node(0x101, struct.pack('<II', 3, 2))
    return struct.pack('<HHI', 3, 8, 8 + len(pool) + len(resources) + len(nodes)) + pool + resources + nodes


class VerifyInstallTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.private = subprocess.run(['/usr/bin/openssl', 'genrsa', '2048'], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
        cls.public = subprocess.run(['/usr/bin/openssl', 'pkey', '-pubout', '-outform', 'DER'], input=cls.private, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.now = 1700000000
        self.version = 43
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED) as output:
            output.writestr('AndroidManifest.xml', manifest(self.version))
            output.writestr('classes.dex', b'fixture bytecode')
        self.apk = buffer.getvalue()
        for name in R.CREDENTIAL_INPUTS:
            self.write(name, R.M.encoded({'fixture': name}))
        self.write('runtime/panel-maintenance/public.der', self.public)
        self.write('runtime/panel-maintenance/signing.pem', self.private)
        self.write('runtime/panel-maintenance/.publish.lock', b'')
        self.write(str(V.APK), self.apk)
        self.release = self.publish(self.version, R.digest(self.apk), len(self.apk))
        self.report = {'receivedAt': self.now, 'report': {'schema': 1, 'package': R.M.PACKAGE,
                       'versionCode': self.version, 'status': 'idle', 'reason': 'no_release',
                       'releaseId': '', 'home': 'panel', 'rootStatus': 'ok'}}
        self.save_report()
        self.apk_verification = mock.patch.object(R.M, 'verify_apk', return_value=self.version)
        self.verify_mock = self.apk_verification.start()

    def tearDown(self):
        self.apk_verification.stop()
        self.temporary.cleanup()

    def write(self, name, raw):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        for parent in (path.parent, *path.parent.parents):
            if parent == self.root or self.root in parent.parents:
                parent.chmod(0o700)
        path.write_bytes(raw)
        path.chmod(0o600)
        return path

    def save_report(self):
        self.write('runtime/panel-maintenance/latest-report.json', R.M.encoded(self.report))

    def publish(self, version, checksum, size):
        identifier = secrets.token_hex(16)
        payload = R.M.encoded({'schema': 1, 'action': 'update', 'id': identifier, 'package': R.M.PACKAGE,
                              'versionCode': version, 'size': size, 'sha256': checksum, 'expiresAt': self.now + 3600})
        signature = subprocess.run(['/usr/bin/openssl', 'dgst', '-sha256', '-sign', str(self.root / 'runtime/panel-maintenance/signing.pem')],
                                   input=payload, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
        envelope = R.M.encoded({'payload': R.M.b64(payload), 'signature': R.M.b64(signature)})
        self.write('runtime/panel-maintenance/releases/' + identifier + '.json', envelope)
        self.write('runtime/panel-maintenance/current.json', envelope)
        return identifier

    def verify(self):
        return V.verify_install(True, project=self.root, now=self.now)

    def no_approval(self):
        self.assertFalse((self.root / R.CATALOG).exists())
        self.assertFalse((self.root / 'runtime/panel-maintenance/prepared-v43').exists())

    def test_success_uses_real_signed_release_and_archives_api22_verified_apk(self):
        result = self.verify()
        self.assertTrue(result['ok'])
        self.verify_mock.assert_called_once()
        catalog, _ = R.read_catalog(self.root)
        item = catalog['artifacts'][0]
        self.assertEqual(item['credentialFingerprint'], R.credential_fingerprint(self.root))
        self.assertEqual(item['sha256'], R.digest(self.apk))
        folder = self.root / 'runtime/panel-maintenance/prepared-v43'
        self.assertEqual((folder / 'HomePanel.apk').read_bytes(), self.apk)
        meta = json.loads((folder / 'verification.json').read_text())
        self.assertEqual(meta['versionName'], '1.36')
        self.assertIs(meta['physicalConfirmed'], True)
        self.assertIs(meta['deviceVerified'], True)
        self.assertEqual(meta['androidApi'], 22)
        self.assertEqual(stat.S_IMODE(folder.stat().st_mode), 0o700)
        for path in [folder / 'HomePanel.apk', folder / 'verification.json', self.root / R.CATALOG]:
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_confirmation_missing_bad_report_state_or_stale_timestamp_cannot_approve(self):
        with self.assertRaises(ValueError):
            V.verify_install(False, project=self.root, now=self.now)
        original = copy.deepcopy(self.report)
        changes = [{'status': 'error'}, {'status': 'checking'}, {'home': 'other'}, {'rootStatus': 'unavailable'},
                   {'package': 'net.quber.afactorymode'}, {'versionCode': 42}, {'releaseId': '1' * 32}]
        for change in changes:
            self.report = copy.deepcopy(original)
            self.report['report'].update(change)
            self.save_report()
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.verify()
            self.no_approval()
        for age in (121, -1):
            self.report = copy.deepcopy(original)
            self.report['receivedAt'] = self.now - age
            self.save_report()
            with self.assertRaises(ValueError):
                self.verify()
            self.no_approval()

    def test_apk_verifier_failure_or_version_mismatch_cannot_approve(self):
        self.verify_mock.side_effect = ValueError('fixture signer failure')
        with self.assertRaises(ValueError):
            self.verify()
        self.no_approval()
        self.verify_mock.side_effect = None
        self.verify_mock.return_value = 44
        with self.assertRaises(ValueError):
            self.verify()
        self.no_approval()

    def test_unsigned_tampered_or_ambiguous_signed_history_is_rejected(self):
        release_path = self.root / 'runtime/panel-maintenance/releases' / (self.release + '.json')
        original = release_path.read_bytes()
        envelope = json.loads(original)
        self.write(str(release_path.relative_to(self.root)), R.M.encoded({'payload': envelope['payload']}))
        with self.assertRaises(ValueError):
            self.verify()
        envelope['signature'] = R.M.b64(b'\0' * 256)
        self.write(str(release_path.relative_to(self.root)), R.M.encoded(envelope))
        with self.assertRaises(ValueError):
            self.verify()
        self.write(str(release_path.relative_to(self.root)), original)
        self.publish(self.version, 'a' * 64, len(self.apk))
        with self.assertRaises(ValueError):
            self.verify()
        self.no_approval()

    def test_duplicate_publications_of_same_verified_bytes_are_unambiguous(self):
        self.publish(self.version, R.digest(self.apk), len(self.apk))
        self.assertTrue(self.verify()['ok'])
        meta = json.loads((self.root / 'runtime/panel-maintenance/prepared-v43/verification.json').read_text())
        self.assertEqual(len(meta['releaseIds']), 2)

    def test_older_catalog_entries_are_preserved_and_repeat_is_immutable(self):
        previous = {'sha256': 'a' * 64, 'versionCode': 42, 'path': 'prepared-v42/HomePanel.apk',
                    'credentialFingerprint': 'b' * 64, 'verifiedOnDevice': True}
        self.write(R.CATALOG, R.M.encoded({'schema': 1, 'package': R.M.PACKAGE, 'artifacts': [previous]}))
        self.verify()
        meta_path = self.root / 'runtime/panel-maintenance/prepared-v43/verification.json'
        original_meta, original_catalog = meta_path.read_bytes(), (self.root / R.CATALOG).read_bytes()
        self.now += 5
        self.report['receivedAt'] = self.now
        self.save_report()
        self.verify()
        self.assertEqual(meta_path.read_bytes(), original_meta)
        self.assertEqual((self.root / R.CATALOG).read_bytes(), original_catalog)
        self.assertEqual(R.read_catalog(self.root)[0]['artifacts'][0], previous)

    def test_conflicting_archive_or_catalog_is_never_overwritten(self):
        folder = 'runtime/panel-maintenance/prepared-v43/'
        self.write(folder + 'HomePanel.apk', self.apk + b'changed')
        self.write(folder + 'verification.json', b'{}')
        with self.assertRaises(ValueError):
            self.verify()
        self.assertEqual((self.root / folder / 'HomePanel.apk').read_bytes(), self.apk + b'changed')
        self.assertFalse((self.root / R.CATALOG).exists())

    def test_public_permissions_or_symlink_inputs_cannot_approve(self):
        path = self.root / 'runtime/panel-maintenance/latest-report.json'
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            self.verify()
        path.chmod(0o600)
        path.rename(path.with_name('saved.json'))
        path.symlink_to('saved.json')
        with self.assertRaises((OSError, ValueError)):
            self.verify()
        self.no_approval()

    def test_changed_credentials_during_verification_cannot_approve(self):
        initial = R.credential_fingerprint(self.root)
        with mock.patch.object(R, 'credential_fingerprint', side_effect=[initial, 'b' * 64]):
            with self.assertRaises(ValueError):
                self.verify()
        self.no_approval()

    def test_cli_errors_are_generic_and_never_include_underlying_secret(self):
        secret = secrets.token_urlsafe(24)
        output = io.StringIO()
        with mock.patch.object(V, 'verify_install', side_effect=ValueError(secret)), contextlib.redirect_stderr(output):
            code = V.main(['--confirm-screen'])
        self.assertEqual(code, 1)
        self.assertNotIn(secret, output.getvalue())
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as stopped:
            V.main([])
        self.assertEqual(stopped.exception.code, 2)
        output = io.StringIO()
        with contextlib.redirect_stderr(output), self.assertRaises(SystemExit):
            V.main(['--confirm-screen', secret])
        self.assertNotIn(secret, output.getvalue())


if __name__ == '__main__':
    unittest.main()
