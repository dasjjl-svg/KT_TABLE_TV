import contextlib
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import stat
import sys
import tarfile
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/privacy-audit.py'
spec = importlib.util.spec_from_file_location('community_privacy_audit', SCRIPT)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class PrivacyAuditTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name)
        self.root = self.base / 'export'
        self.root.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content.encode() if isinstance(content, str) else content)
        return path

    def scan(self, **kwargs):
        result = audit.Auditor(**kwargs)
        result.scan(self.root)
        return result.result()

    def categories(self, result):
        return {item['category'] for item in result['findings']}

    def archive(self, entries):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w', compression=zipfile.ZIP_DEFLATED) as output:
            for name, content in entries:
                output.writestr(name, content)
        return data.getvalue()

    def test_public_source_product_ids_and_public_certificate_are_allowed(self):
        self.write('src/product.txt', 'CT1105_SB Android 5.1.1 Hi3798CV200 SDK 22\n')
        self.write('examples/local.json', '{"host":"192.168.1.10","device":"192.168.1.20","token":"replace-with-your-token"}')
        self.write('tests/fixture.txt', 'password="fixture-password"\ntoken="abcdefghijklmnopqrstuvwxyz0123456789"\n')
        self.write('public.pem', '-----BEGIN CERTIFICATE-----\nZXhhbXBsZQ==\n-----END CERTIFICATE-----\n')
        self.write('src/validator.txt', '10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n100.64.0.0/10\n')
        self.write('.gitignore', 'runtime/\nbuild/\n*.apk\n')
        self.assertTrue(self.scan()['ok'])

    def test_private_reference_tokens_override_fixture_exemptions_and_are_redacted(self):
        original = self.base / 'original'
        (original / 'runtime/panel').mkdir(parents=True)
        secret = secrets.token_hex(24)
        (original / 'runtime/panel/auth.json').write_text(json.dumps({'tokens': {'access_token': secret, 'token_type': 'Bearer', 'ha_auth_provider': 'homeassistant'}}))
        self.write('tests/' + secret + '.txt', secret)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = audit.main([str(self.root), '--private-reference', str(original)])
        self.assertEqual(code, 1)
        self.assertNotIn(secret, output.getvalue())
        result = json.loads(output.getvalue())
        self.assertIn('private_reference_value', self.categories(result))
        self.assertTrue(any('[redacted]' in item['file'] for item in result['findings']))
        self.assertNotIn(b'Bearer', audit.private_values(original))
        self.assertNotIn(b'homeassistant', audit.private_values(original))

    def test_external_private_patterns_match_escaped_text_and_original_fixture_ip(self):
        location = '비공개' + secrets.token_hex(4)
        address = '192.168.1.20'
        path = self.base / 'private.json'
        path.write_text(json.dumps({'values': [location, address]}))
        self.write('examples/config.json', json.dumps({'place': location, 'host': address}, ensure_ascii=True))
        result = self.scan(secrets=audit.private_values(patterns=path, audit_root=self.root))
        self.assertIn('private_reference_value', self.categories(result))
        self.assertNotIn(location, json.dumps(result))

    def test_private_patterns_cannot_be_inside_the_distribution(self):
        path = self.write('patterns.json', json.dumps(['some-sensitive-identifier']))
        with self.assertRaises(audit.AuditError):
            audit.private_values(patterns=path, audit_root=self.root)

    def test_fingerprint_format_changes_are_still_exact_private_matches(self):
        fingerprint = secrets.token_hex(32)
        path = self.base / 'private.json'
        path.write_text(json.dumps([fingerprint]))
        formatted = ':'.join(fingerprint[i:i + 2] for i in range(0, len(fingerprint), 2)).upper()
        self.write('tests/certificate.txt', formatted)
        result = self.scan(secrets=audit.private_values(patterns=path, audit_root=self.root))
        self.assertIn('private_reference_value', self.categories(result))

    def test_nonfixture_private_addresses_and_machine_paths_are_rejected(self):
        self.write('src/settings.js', 'host="192.168.1.20"; vpn="100.65.2.3"; path="/Users/' + 'a-person/work"; host="fixture-device.tailabcdef.ts.net"; mac="12:34:56:78:9a:bc";')
        self.assertTrue({'private_network_address', 'personal_filesystem_path', 'tailnet_hostname', 'device_mac_address'} <= self.categories(self.scan()))

    def test_realistic_secret_literals_and_private_key_material_are_rejected(self):
        secret = secrets.token_urlsafe(32)
        self.write(secret + '.py', 'access_token="' + secret + '"\n')
        self.write('material.txt', '-----BEGIN ' + 'PRIVATE KEY-----\n' + secrets.token_hex(20))
        result = self.scan()
        self.assertTrue({'literal_credential', 'private_key'} <= self.categories(result))
        self.assertNotIn(secret, json.dumps(result))

    def test_forbidden_directory_and_artifact_names_fail_closed(self):
        self.write('runtime/config.json', '{}')
        self.write('app.apk', 'not-a-real-apk')
        self.write('photo.jpg', 'not-a-real-photo')
        self.write('key.pk8', b'not-a-real-key')
        self.assertTrue({'forbidden_directory', 'forbidden_artifact'} <= self.categories(self.scan()))

    def test_filesystem_symlinks_and_hardlinks_are_not_followed(self):
        outside = self.base / 'outside.txt'
        outside.write_text(secrets.token_hex(20))
        (self.root / 'linked.txt').symlink_to(outside)
        os.link(outside, self.root / 'hard.txt')
        self.assertIn('filesystem_link_or_special_file', self.categories(self.scan()))

    def test_nested_archive_secret_is_found(self):
        secret = secrets.token_hex(24).encode()
        inner = self.archive([('src/config.txt', secret)])
        self.write('bundle.zip', self.archive([('inner.zip', inner)]))
        result = self.scan(secrets=(secret,))
        self.assertIn('private_reference_value', self.categories(result))
        self.assertTrue(any('!inner.zip!src/config.txt' in item['file'] for item in result['findings']))

    def test_archive_traversal_absolute_and_windows_paths_fail_without_extraction(self):
        self.write('bad.zip', self.archive([('../escape.txt', 'x'), ('/absolute.txt', 'x'), ('C:\\escape.txt', 'x'), ('a//b', 'x')]))
        self.assertIn('unsafe_archive_path', self.categories(self.scan()))
        self.assertFalse((self.base / 'escape.txt').exists())

    def test_archive_links_and_forbidden_nested_content_fail(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as output:
            entry = zipfile.ZipInfo('link')
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            output.writestr(entry, '../outside')
            output.writestr('nested/backups/backup.txt', 'x')
        self.write('bad.zip', data.getvalue())
        self.assertTrue({'archive_special_file', 'forbidden_directory'} <= self.categories(self.scan()))

    def test_tar_links_are_rejected_and_plain_gzip_is_inspected(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w:gz') as output:
            entry = tarfile.TarInfo('hard-link')
            entry.type = tarfile.LNKTYPE
            entry.linkname = '../outside'
            output.addfile(entry)
        secret = secrets.token_hex(24).encode()
        self.write('bad.tar.gz', data.getvalue())
        self.write('nested.gz', gzip.compress(secret))
        self.assertTrue({'archive_special_file', 'private_reference_value'} <= self.categories(self.scan(secrets=(secret,))))

    def test_size_depth_malformed_and_binary_inputs_fail_closed(self):
        self.write('huge.gz', gzip.compress(b'x' * 10000))
        self.assertIn('scan_limit', self.categories(self.scan(max_file=256)))
        (self.root / 'huge.gz').unlink()
        self.write('bad.zip', b'not a zip')
        self.write('binary.txt', b'abc\x00def')
        self.assertTrue({'unreadable_archive', 'opaque_binary'} <= self.categories(self.scan()))
        (self.root / 'bad.zip').unlink()
        (self.root / 'binary.txt').unlink()
        value = b'leaf'
        for index in range(5):
            value = self.archive([('nested.zip' if index else 'leaf.txt', value)])
        self.write('nested.zip', value)
        self.assertIn('archive_depth_limit', self.categories(self.scan(max_depth=2)))

    def test_only_explicit_root_git_metadata_exemption_is_allowed(self):
        self.write('.git/config', 'metadata')
        self.assertIn('forbidden_directory', self.categories(self.scan()))
        self.assertTrue(self.scan(ignore_git=True)['ok'])
        self.write('bundle.zip', self.archive([('.git/config', 'metadata')]))
        self.assertIn('forbidden_directory', self.categories(self.scan(ignore_git=True)))

    def test_reference_errors_never_print_the_private_input_or_exception(self):
        value = secrets.token_hex(20)
        private = self.base / (value + '.json')
        private.write_text('{invalid ' + value)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = audit.main([str(self.root), '--private-patterns', str(private)])
        self.assertEqual(code, 1)
        self.assertNotIn(value, output.getvalue())
        self.assertEqual(self.categories(json.loads(output.getvalue())), {'private_reference_or_input_error'})


if __name__ == '__main__':
    unittest.main()
