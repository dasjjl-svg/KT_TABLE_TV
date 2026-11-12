import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import struct
import tempfile
import time
import unittest
import warnings
from unittest import mock
import zipfile


ROOT = Path(__file__).resolve().parents[2]
(ROOT / 'runtime').mkdir(mode=0o700, exist_ok=True)
SPEC = importlib.util.spec_from_file_location('panel_recovery_test', ROOT / 'hardware/panel_recovery.py')
R = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(R)


def manifest(version=43, utf8=False, package=R.M.PACKAGE, attributes=None):
    strings = ['versionCode', 'versionName', R.ANDROID, 'android', 'manifest', 'package', package, '1.36']
    table, offsets = bytearray(), []
    for value in strings:
        offsets.append(len(table))
        encoded = value.encode('utf-8' if utf8 else 'utf-16le')
        units = len(value.encode('utf-16le')) // 2
        table += bytes((units, len(encoded))) + encoded + b'\0' if utf8 else struct.pack('<H', units) + encoded + b'\0\0'
    table += b'\0' * (-len(table) % 4)
    start = 28 + len(offsets) * 4
    pool = struct.pack('<HHIIIIII', 1, 28, start + len(table), len(strings), 0, 0x100 if utf8 else 0, start, 0)
    pool += struct.pack('<' + 'I' * len(offsets), *offsets) + table
    resources = struct.pack('<HHIII', 0x180, 8, 16, 0x0101021b, 0x0101021c)

    def node(kind, body):
        return struct.pack('<HHIII', kind, 16, 16 + len(body), 1, R.NONE) + body

    def attribute(namespace, name, raw, kind, value):
        return struct.pack('<IIIHBBI', namespace, name, raw, 8, 0, kind, value)

    attrs = [(2, 0, R.NONE, 0x10, version), (2, 1, 7, 3, 7), (R.NONE, 5, 6, 3, 6)] if attributes is None else attributes
    start_node = struct.pack('<IIHHHHHH', R.NONE, 4, 20, 20, len(attrs), 0, 0, 0)
    start_node += b''.join(attribute(*item) for item in attrs)
    nodes = (node(0x100, struct.pack('<II', 3, 2)) + node(0x102, start_node)
             + node(0x103, struct.pack('<II', R.NONE, 4)) + node(0x101, struct.pack('<II', 3, 2)))
    return struct.pack('<HHI', 3, 8, 8 + len(pool) + len(resources) + len(nodes)) + pool + resources + nodes


def apk(xml=None, additional=()):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('AndroidManifest.xml', manifest() if xml is None else xml)
        archive.writestr('classes.dex', b'dex\n035\0fixture executable bytes')
        archive.writestr('lib/armeabi-v7a/panel.so', b'native fixture bytes')
        archive.writestr('assets/offline-panel.html', b'<html>retained offline page</html>')
        archive.writestr('META-INF/PLATFORM.SF', b'old signature')
        archive.writestr('META-INF/PLATFORM.RSA', b'old certificate')
        archive.writestr('META-INF/MANIFEST.MF', b'old jar manifest')
        for name, data in additional:
            archive.writestr(name, data)
    return out.getvalue()


def chunk_offset(raw, kind):
    offset = 8
    while offset < len(raw):
        actual, _, size = struct.unpack_from('<HHI', raw, offset)
        if actual == kind:
            return offset
        offset += size
    raise AssertionError('fixture chunk absent')


class BinaryManifestTests(unittest.TestCase):
    def test_patch_only_the_integer_preserves_name_package_and_other_bytes(self):
        for utf8 in (False, True):
            for target in (44, 256, 65536, 2147483647):
                with self.subTest(utf8=utf8, target=target):
                    source = manifest(utf8=utf8)
                    changed, info = R.patch_manifest(source, target)
                    offset = info['offset']
                    self.assertEqual(changed[:offset], source[:offset])
                    self.assertEqual(changed[offset + 4:], source[offset + 4:])
                    self.assertEqual(struct.unpack_from('<I', changed, offset)[0], target)
                    self.assertEqual(R.manifest_version(changed)['versionName'], '1.36')
                    self.assertEqual(info['versionCode'], 43)

    def test_cannot_relabel_old_or_unknown_manifest(self):
        for raw in (b'', b'<manifest/>', manifest(version=39), manifest(version=0xffffffff), manifest(package='other.package')):
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                R.patch_manifest(raw, 44)
        for version in (False, 43, 42, 0, -1, 2147483648, '44', None):
            with self.subTest(version=version), self.assertRaises(ValueError):
                R.patch_manifest(manifest(), version)

    def test_unambiguous_single_integer_required(self):
        ordinary = [(2, 0, R.NONE, 0x10, 43), (2, 1, 7, 3, 7), (R.NONE, 5, 6, 3, 6)]
        cases = [ordinary[1:], ordinary + [ordinary[0]],
                 [(2, 0, 7, 3, 7)] + ordinary[1:], [(2, 0, 7, 0x10, 43)] + ordinary[1:],
                 [(R.NONE, 0, R.NONE, 0x10, 43)] + ordinary[1:],
                 [(2, 0, R.NONE, 0x11, 43)] + ordinary[1:],
                 [ordinary[0], (2, 1, R.NONE, 0x10, 7), ordinary[2]],
                 [ordinary[0], ordinary[1], (R.NONE, 5, R.NONE, 0x10, 6)]]
        for attrs in cases:
            with self.subTest(attrs=attrs), self.assertRaises(ValueError):
                R.manifest_version(manifest(attributes=attrs))

    def test_corrupt_binary_headers_offsets_maps_and_types_are_rejected(self):
        original = manifest()
        pool, resources, start, end = [chunk_offset(original, kind) for kind in (1, 0x180, 0x102, 0x103)]
        mutations = [(0, '<H', 2), (4, '<I', len(original) - 4), (pool + 4, '<I', 0),
                     (pool + 8, '<I', 8193), (pool + 12, '<I', 1), (pool + 16, '<I', 0x8000),
                     (pool + 20, '<I', 4), (pool + 28, '<I', 0xffffff00),
                     (resources + 8, '<I', 0x0101021c), (start, '<H', 0x104),
                     (start + 24, '<H', 0), (start + 26, '<H', 24), (start + 28, '<H', 1000),
                     (start + 36 + 12, '<H', 16), (start + 36 + 14, '<B', 1),
                     (start + 36 + 15, '<B', 0x7f), (start + 36 + 4, '<I', 100000),
                     (end + 20, '<I', 5)]
        for offset, kind, value in mutations:
            with self.subTest(offset=offset, value=value):
                changed = bytearray(original)
                struct.pack_into(kind, changed, offset, value)
                with self.assertRaises(ValueError):
                    R.manifest_version(bytes(changed))
        with self.assertRaises(ValueError):
            R.manifest_version(original + b'\0\0\0\0')
        with self.assertRaises(ValueError):
            R.manifest_version(b'\0' * (R.MAX_MANIFEST + 1))


class ArchiveTests(unittest.TestCase):
    def test_remove_only_signatures_and_preserve_every_other_entry(self):
        original = R.zip_entries(apk(additional=[('META-INF/NOTICE.txt', b'license')]))
        changed, _ = R.patch_manifest(dict((i.filename, data) for i, data in original)['AndroidManifest.xml'], 44)
        unsigned = R.zip_entries(R.unsigned_apk(original, changed))
        self.assertFalse(any(R.signature_entry(i.filename) for i, _ in unsigned))
        self.assertIn('META-INF/NOTICE.txt', R.compare_payloads(original, unsigned, changed))
        tampered = [(item, data + b'changed' if item.filename == 'classes.dex' else data) for item, data in unsigned]
        with self.assertRaises(ValueError):
            R.compare_payloads(original, tampered, changed)
        with self.assertRaises(ValueError):
            R.compare_payloads(original, unsigned[:-1], changed)

    def test_reject_zip_slip_duplicates_symlinks_and_noncanonical_names(self):
        for name in ('../escaped', '/absolute', 'a/../b', 'a\\b', 'a//b', 'a//', 'bad:name'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                R.zip_entries(apk(additional=[(name, b'x')]))
        nul_name = apk(additional=[('badXname', b'x')]).replace(b'badXname', b'bad\0name')
        with self.assertRaises(ValueError):
            R.zip_entries(nul_name)
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            duplicate = apk(additional=[('classes.dex', b'second')])
        with self.assertRaises(ValueError):
            R.zip_entries(duplicate)
        link = zipfile.ZipInfo('symlink')
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaises(ValueError):
            R.zip_entries(apk(additional=[(link, b'/outside')]))

    def test_reject_archive_bombs_and_entry_limits(self):
        for raw in (apk(additional=[('huge', b'0' * (R.MAX_ENTRY + 1))]),
                    apk(additional=[('large' + str(i), b'0' * (6 * 1024 * 1024)) for i in range(3)]),
                    apk(additional=[('file' + str(i), b'0') for i in range(129)]),
                    b'PK\x03\x04' + b'0' * R.M.MAX_APK):
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                R.zip_entries(raw)
        with self.assertRaises((ValueError, zipfile.BadZipFile)):
            R.zip_entries(apk()[:-15])


class PreparationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.private = R.M.command([R.M.OPENSSL, 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048'])
        cls.public = R.M.command([R.M.OPENSSL, 'pkey', '-pubout', '-outform', 'DER'], cls.private)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='panel-recovery-test-', dir=ROOT / 'runtime')
        self.project = Path(self.temporary.name)
        self.runtime = self.project / 'runtime/panel-maintenance'
        for name in R.CREDENTIAL_INPUTS:
            self.write(name, self.public if name.endswith('public.der') else b'{"token":"fixture-secret"}')
        self.write('runtime/panel-maintenance/.publish.lock', b'')
        (self.runtime / 'releases').mkdir(mode=0o700)
        self.original = apk()
        self.source_sha = R.digest(self.original)
        self.write('runtime/panel-maintenance/prepared-v43/HomePanel.apk', self.original)
        self.catalog = {'schema': 1, 'package': R.M.PACKAGE, 'artifacts': [{
            'sha256': self.source_sha, 'versionCode': 43, 'path': 'prepared-v43/HomePanel.apk',
            'credentialFingerprint': R.credential_fingerprint(self.project), 'verifiedOnDevice': True}]}
        self.write_catalog()
        self.report(43)

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, name, raw):
        path = self.project / name
        path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        path.write_bytes(raw)
        path.chmod(0o600)
        return path

    def write_catalog(self):
        self.write(R.CATALOG, R.M.encoded(self.catalog))

    def report(self, version):
        report = {'schema': 1, 'versionCode': version, 'package': R.M.PACKAGE, 'status': 'idle',
                  'reason': 'no_release', 'releaseId': '', 'home': 'panel', 'rootStatus': 'ok'}
        self.write('runtime/panel-maintenance/latest-report.json', R.M.encoded({'receivedAt': int(time.time()), 'report': report}))

    def release(self, version, identifier='a' * 32, current=False):
        value = {'schema': 1, 'action': 'update', 'id': identifier, 'package': R.M.PACKAGE,
                 'versionCode': version, 'size': len(self.original), 'sha256': self.source_sha, 'expiresAt': 1}
        payload = R.M.encoded(value)
        key = self.write('release-signing.pem', self.private)
        signature = R.M.command([R.M.OPENSSL, 'dgst', '-sha256', '-sign', key], payload)
        envelope = R.M.encoded({'payload': R.M.b64(payload), 'signature': R.M.b64(signature)})
        self.write('runtime/panel-maintenance/releases/' + identifier + '.json', envelope)
        if current:
            self.write('runtime/panel-maintenance/current.json', envelope)

    @staticmethod
    def verify(path):
        entries = R.zip_entries(path.read_bytes())
        return R.manifest_version(dict((i.filename, data) for i, data in entries)['AndroidManifest.xml'])['versionCode']

    @staticmethod
    def sign(unsigned, scratch, project):
        result = scratch / 'signed.apk'
        R.private_write(result, unsigned)
        return result

    def prepare(self, installed=43, target=None):
        with mock.patch.object(R.M, 'verify_apk', side_effect=self.verify), mock.patch.object(R, 'sign_apk', side_effect=self.sign):
            return R.prepare(self.source_sha, installed, target, self.project)

    def test_prepare_does_not_publish_and_preserves_all_executable_payloads(self):
        self.release(55, current=True)
        self.report(51)
        before = {path.relative_to(self.runtime): path.read_bytes() for path in self.runtime.rglob('*') if path.is_file()}
        result = self.prepare()
        self.assertFalse(result['published'])
        self.assertEqual(result['targetVersionCode'], 56)
        self.assertEqual(result['versionName'], '1.36')
        for name, raw in before.items():
            self.assertEqual((self.runtime / name).read_bytes(), raw)
        metadata = json.loads(Path(result['metadata']).read_bytes())
        self.assertEqual(metadata['sourceSha256'], self.source_sha)
        self.assertEqual(metadata['targetSha256'], R.digest(Path(result['apk']).read_bytes()))
        self.assertEqual(metadata['manifestChange']['length'], 4)
        self.assertEqual(metadata['versionFloor']['publishedMaxVersion'], 55)
        self.assertTrue(metadata['validations']['payloadsUnchangedExceptVersionCode'])
        self.assertEqual(stat.S_IMODE(Path(result['apk']).stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(Path(result['apk']).parent.stat().st_mode), 0o700)
        self.assertFalse(list((self.project / R.OUTPUT).glob('.prepare-*')))

    def test_floor_includes_withdrawn_expired_releases_and_installed_report(self):
        self.release(60)
        self.report(63)
        self.assertEqual(self.prepare(installed=64)['targetVersionCode'], 65)
        for target in (43, 60, 63, 64, True, 2147483648):
            with self.subTest(target=target), self.assertRaises(ValueError):
                self.prepare(installed=64, target=target)
        self.report(2147483647)
        with self.assertRaises(ValueError):
            self.prepare()

    def test_all_seven_credential_inputs_required_and_rotation_rejected(self):
        original = R.credential_fingerprint(self.project)
        for name in R.CREDENTIAL_INPUTS:
            path = self.project / name
            raw = path.read_bytes()
            path.unlink()
            with self.subTest(name=name), self.assertRaises(FileNotFoundError):
                R.credential_fingerprint(self.project)
            self.write(name, raw)
        self.write(R.CREDENTIAL_INPUTS[0], b'rotated-token')
        self.assertNotEqual(R.credential_fingerprint(self.project), original)
        with self.assertRaisesRegex(ValueError, 'credentials'):
            self.prepare()

    def test_approval_catalog_and_source_tampering_fail_before_signing(self):
        base = copy.deepcopy(self.catalog)
        for changes in ({'verifiedOnDevice': False}, {'verifiedOnDevice': 1}, {'path': '../outside.apk'},
                        {'versionCode': True}, {'versionCode': 39}, {'sha256': 'f' * 64}):
            self.catalog = copy.deepcopy(base)
            self.catalog['artifacts'][0].update(changes)
            self.write_catalog()
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.prepare()
        self.catalog = base
        self.write_catalog()
        self.write('runtime/panel-maintenance/prepared-v43/HomePanel.apk', apk(manifest(version=44)))
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            self.prepare()

    def test_wrong_source_signature_or_modified_result_never_produces_output(self):
        with mock.patch.object(R.M, 'verify_apk', side_effect=ValueError('APK signer')), mock.patch.object(R, 'sign_apk') as signer:
            with self.assertRaises(ValueError):
                R.prepare(self.source_sha, 43, project=self.project)
            signer.assert_not_called()
        def tamper(unsigned, scratch, project):
            path = self.sign(unsigned, scratch, project)
            with zipfile.ZipFile(path, 'a') as archive:
                archive.writestr('extra-permission.xml', b'unexpected')
            return path
        with mock.patch.object(R.M, 'verify_apk', side_effect=self.verify), mock.patch.object(R, 'sign_apk', side_effect=tamper):
            with self.assertRaisesRegex(ValueError, 'entry set'):
                R.prepare(self.source_sha, 43, project=self.project)
        self.assertFalse(list((self.project / R.OUTPUT).iterdir()))

    def test_configuration_race_is_rechecked_after_signing(self):
        def rotate(unsigned, scratch, project):
            path = self.sign(unsigned, scratch, project)
            self.write(R.CREDENTIAL_INPUTS[1], b'rotated-during-prepare')
            return path
        with mock.patch.object(R.M, 'verify_apk', side_effect=self.verify), mock.patch.object(R, 'sign_apk', side_effect=rotate):
            with self.assertRaisesRegex(ValueError, 'configuration changed'):
                R.prepare(self.source_sha, 43, project=self.project)
        self.assertFalse(list((self.project / R.OUTPUT).iterdir()))

    def test_history_signature_corruption_and_duplicate_json_keys_rejected(self):
        self.release(50)
        path = self.runtime / ('releases/' + 'a' * 32 + '.json')
        envelope = json.loads(path.read_bytes())
        envelope['signature'] = R.M.b64(b'fake')
        path.write_bytes(R.M.encoded(envelope))
        with self.assertRaises(ValueError):
            self.prepare()
        (self.project / R.CATALOG).write_bytes(b'{"schema":1,"schema":1,"package":"dev.homepanel.community","artifacts":[]}')
        with self.assertRaises(ValueError):
            R.read_catalog(self.project)

    def test_symlinked_private_inputs_or_world_readable_catalog_are_rejected(self):
        catalog = self.project / R.CATALOG
        raw = catalog.read_bytes()
        other = self.write('other.json', raw)
        catalog.unlink()
        catalog.symlink_to(other)
        with self.assertRaises((OSError, ValueError)):
            self.prepare()
        catalog.unlink()
        self.write_catalog()
        catalog.chmod(0o644)
        with self.assertRaises(ValueError):
            self.prepare()

    def test_publication_lock_blocks_parallel_prepare(self):
        descriptor = os.open(self.runtime / '.publish.lock', os.O_RDWR)
        try:
            R.fcntl.flock(descriptor, R.fcntl.LOCK_EX | R.fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError):
                self.prepare()
        finally:
            os.close(descriptor)


if __name__ == '__main__':
    unittest.main()
