import base64
import contextlib
import copy
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import socket
import stat
import tempfile
import threading
import time
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
(ROOT / 'runtime').mkdir(mode=0o700, exist_ok=True)
SPEC = importlib.util.spec_from_file_location('maintenance', ROOT / 'hardware/panel_maintenance_server.py')
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
LEGACY_SIGNER = '1' * 64
CONFIG = {'version': 1, 'host': '192.168.10.20', 'device': '192.168.10.30',
          'port': 4186, 'token': 'a' * 64}


def report(**changes):
    value = {'schema': 1, 'versionCode': 30, 'package': M.PACKAGE, 'status': 'installed',
             'reason': 'install_complete', 'releaseId': 'b' * 32, 'home': 'panel', 'rootStatus': 'ok'}
    value.update(changes)
    return value


def home_report(**changes):
    value = {'schema': 1, 'package': M.PACKAGE, 'versionCode': 33,
             'home': {'resolved': 'com.kt.gigagenie.launcher/.ui.GreetingActivity', 'greetingEnabled': 0,
                      'candidates': ['dev.homepanel.community/net.quber.afactorymode.AFactoryModeMainActivity'],
                      'preferences': [{'component': 'com.kt.gigagenie.launcher/.ui.GreetingActivity',
                                       'filter': '<filter>\n\t<action name="android.intent.action.MAIN" />\n</filter>'}]}}
    value.update(changes)
    return value


def startup_report(**changes):
    value = {'schema': 1, 'package': M.PACKAGE, 'versionCode': 36,
             'currentPrefs': {'enabled': True, 'homeAttempted': True, 'homeConfigurationVersion': 2,
                              'status': 'idle', 'reason': 'no_release'},
             'startup': {'uptimeMs': 5000, 'bootId': 'unknown',
                         'properties': {name: 'unknown' for name in M.STARTUP_PROPERTIES},
                         'packages': [{'name': name, 'installed': False, 'versionCode': -1,
                                       'firstInstallTime': -1, 'lastUpdateTime': -1,
                                       'enabledState': -1, 'sourceDir': 'unknown'}
                                      for name in sorted(M.STARTUP_PACKAGES)],
                         'components': [{'name': name, 'state': -1} for name in sorted(M.STARTUP_COMPONENTS)],
                         'topActivities': [], 'factoryMarker': {'exists': False, 'uid': -1, 'mode': -1, 'size': -1},
                         'serviceComponents': [], 'error': 'unknown'}}
    value.update(changes)
    return value


def crash_report(**changes):
    value = {'schema': 1, 'package': M.PACKAGE, 'versionCode': 37,
             'crash': {'entries': [{'timeMs': 1794516058000, 'package': M.PACKAGE, 'tag': 'data_app_crash',
                                   'exception': 'java.lang.RuntimeException',
                                   'frames': [{'className': 'android.webkit.WebViewFactory',
                                               'methodName': 'getProvider', 'fileName': 'WebViewFactory.java',
                                               'lineNumber': 100}]}], 'error': 'none'}}
    value.update(changes)
    return value


@contextlib.contextmanager
def running_server(runtime):
    original = M.ThreadingHTTPServer.server_bind

    def bind_local(server):
        server.server_address = ('127.0.0.1', 0)
        original(server)

    with mock.patch.object(M.ThreadingHTTPServer, 'server_bind', bind_local):
        server = M.MaintenanceServer(CONFIG, runtime, read_timeout=0.5, request_timeout=2)
    server.config['device'] = '127.0.0.1'
    worker = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.02})
    worker.start()
    try:
        yield server
    finally:
        server.stop()
        worker.join(3)
        server.server_close()
        if worker.is_alive():
            raise AssertionError('server worker did not finish')


def request(server, method='GET', path='/v1/release', body=None, headers=None):
    connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
    values = {'Host': server.authority, 'Authorization': 'Bearer ' + CONFIG['token']}
    values.update(headers or {})
    try:
        connection.request(method, path, body=body, headers=values)
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()
    finally:
        connection.close()


def raw_request(server, lines, body=b'', shutdown=False):
    connection = socket.create_connection(('127.0.0.1', server.server_port), timeout=3)
    try:
        connection.sendall(('\r\n'.join(lines) + '\r\n\r\n').encode('ascii') + body)
        if shutdown:
            connection.shutdown(socket.SHUT_WR)
        response = http.client.HTTPResponse(connection)
        response.begin()
        return response.status, response.read()
    finally:
        connection.close()


class MaintenanceTests(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(M, 'bootstrap_signer', return_value=LEGACY_SIGNER)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.temporary = tempfile.TemporaryDirectory(prefix='maintenance-tests-', dir=ROOT / 'runtime')
        self.base = Path(self.temporary.name)
        self.runtime = self.base / 'server'
        self.backup = self.base / 'backup.json'
        backup = dict(CONFIG, port=4185)
        self.backup.write_bytes(M.encoded(backup))
        self.backup.chmod(0o600)
        M.initialize(self.runtime, self.backup)

    def tearDown(self):
        self.temporary.cleanup()

    def store(self):
        value = M.MaintenanceStore(self.runtime)
        self.addCleanup(value.close)
        return value

    def apk(self, name='panel.apk'):
        path = self.base / name
        path.write_bytes(b'PK\x03\x04' + bytes(range(256)) * 7)
        path.chmod(0o600)
        return path

    def publish(self, store, version=40):
        with mock.patch.object(M, 'verify_apk', return_value=version):
            return store.publish(self.apk())

    def test_initialize_private_idempotent_and_fresh_credential(self):
        before = {name: (self.runtime / name).read_bytes() for name in ('config.json', 'signing.pem', 'public.der')}
        config = M.load_config(self.runtime)
        self.assertNotEqual(config['token'], CONFIG['token'])
        self.assertEqual(config['host'], CONFIG['host'])
        self.assertEqual(config['device'], CONFIG['device'])
        self.assertEqual(stat.S_IMODE(self.runtime.stat().st_mode), 0o700)
        self.assertFalse(M.initialize(self.runtime, self.backup)['created'])
        for name, raw in before.items():
            self.assertEqual((self.runtime / name).read_bytes(), raw)
            self.assertEqual(stat.S_IMODE((self.runtime / name).stat().st_mode), 0o600)

    def test_config_and_partial_initialization_fail_closed(self):
        for change in ({'port': 4185}, {'host': '127.0.0.1'}, {'host': '8.8.8.8'},
                       {'device': '169.254.1.1'}, {'device': CONFIG['host']}, {'version': True}, {'token': 'x'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                M.validate_config(dict(CONFIG, **change))
        (self.runtime / 'public.der').unlink()
        with self.assertRaises(ValueError):
            M.initialize(self.runtime, self.backup)

    def test_publish_real_rsa_signature_immutable_artifact_and_expiry(self):
        store = self.store()
        before = int(time.time())
        result = self.publish(store)
        envelope = store.release()
        payload = M.BASE.strict_json(base64.b64decode(envelope['payload']))
        self.assertEqual(payload, result['release'])
        self.assertEqual(set(payload), {'schema', 'action', 'id', 'package', 'versionCode', 'size', 'sha256', 'expiresAt'})
        self.assertEqual(payload['package'], 'dev.homepanel.community')
        self.assertEqual(payload['versionCode'], 40)
        self.assertTrue(before + 86400 <= payload['expiresAt'] <= int(time.time()) + 86400)
        self.assertEqual(len(base64.b64decode(envelope['signature'])), 256)
        self.assertEqual(store.apk(payload['sha256']), self.apk().read_bytes())
        for path in self.runtime.rglob('*'):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700 if path.is_dir() else 0o600)
            if path.is_file():
                self.assertEqual(path.stat().st_nlink, 1)
        with mock.patch.object(M.time, 'time', return_value=payload['expiresAt']):
            self.assertIsNone(store.release())

    def test_signature_or_apk_tampering_rejected(self):
        store = self.store()
        release = self.publish(store)['release']
        envelope = store.release()
        changed = dict(release, versionCode=99)
        envelope['payload'] = M.b64(M.encoded(changed))
        with self.assertRaises(ValueError):
            store.verify_envelope(M.encoded(envelope))
        artifact = self.runtime / 'artifacts' / (release['sha256'] + '.apk')
        artifact.write_bytes(b'changed')
        with self.assertRaises(ValueError):
            store.release()

    def test_versions_do_not_regress_even_after_expiry(self):
        store = self.store()
        release = self.publish(store, 41)['release']
        for version in (40, 41):
            with mock.patch.object(M.time, 'time', return_value=release['expiresAt'] + 1), \
                    mock.patch.object(M, 'verify_apk', return_value=version), self.assertRaises(ValueError):
                store.publish(self.apk())
        self.assertEqual(self.publish(store, 42)['release']['versionCode'], 42)

    def test_withdraw_does_not_allow_legacy_bridge_publication(self):
        store = self.store()
        self.publish(store, 40)
        self.assertTrue(store.withdraw()['withdrawn'])
        self.assertIsNone(store.current())
        preserved = {path.relative_to(self.runtime): path.read_bytes()
                     for directory in ('artifacts', 'releases')
                     for path in (self.runtime / directory).iterdir()}
        badge = b"package: name='dev.homepanel.community' versionCode='39' versionName='1.32'\nsdkVersion:'21'\n"
        signed = ('Verified using v1 scheme (JAR signing): true\nSigner #1 certificate SHA-256 digest: '
                  + LEGACY_SIGNER + '\n').encode('ascii')
        with mock.patch.object(M, 'command', side_effect=[badge, signed]) as invoke, \
                self.assertRaisesRegex(ValueError, 'legacy release publication disabled'):
            store.publish(self.apk())
        self.assertEqual(invoke.call_count, 2)
        self.assertIsNone(store.current())
        self.assertEqual({path.relative_to(self.runtime): path.read_bytes()
                          for directory in ('artifacts', 'releases')
                          for path in (self.runtime / directory).iterdir()}, preserved)
        self.assertFalse(any(path.name.startswith('.apk-') for path in self.runtime.iterdir()))

    def test_apk_verification_failure_publishes_nothing(self):
        store = self.store()
        with mock.patch.object(M, 'verify_apk', side_effect=ValueError('rejected')), self.assertRaises(ValueError):
            store.publish(self.apk())
        self.assertIsNone(store.release())
        self.assertEqual(list((self.runtime / 'artifacts').iterdir()), [])
        self.assertEqual(list((self.runtime / 'releases').iterdir()), [])
        self.assertFalse(any(path.name.startswith('.apk-') for path in self.runtime.iterdir()))

    def test_apksigner_pin_package_api_and_v1_required(self):
        signed = ('Verified using v1 scheme (JAR signing): true\nSigner #1 certificate SHA-256 digest: '
                  + LEGACY_SIGNER + '\n').encode('ascii')
        badge = b"package: name='dev.homepanel.community' versionCode='30' versionName='1.23'\nsdkVersion:'21'\n"
        with mock.patch.object(M, 'command', side_effect=[badge, signed]) as invoke:
            self.assertEqual(M.verify_apk(self.apk()), 30)
            self.assertEqual(invoke.call_args_list[0].args[0][1:3], ['dump', 'badging'])
            self.assertIn('--max-sdk-version', invoke.call_args_list[1].args[0])
        for bad in (signed.replace(LEGACY_SIGNER.encode(), b'0' * 64), signed.replace(b'true', b'false'),
                    signed + b'Signer #2 certificate SHA-256 digest: ' + LEGACY_SIGNER.encode() + b'\n'):
            with mock.patch.object(M, 'command', side_effect=[badge, bad]), self.assertRaises(ValueError):
                M.verify_apk(self.apk())
        for bad in (badge.replace(b'dev.homepanel.community', b'net.quber.afactorymode'),
                    badge.replace(b"sdkVersion:'21'", b"sdkVersion:'23'"),
                    badge.replace(b"versionCode='30'", b"versionCode='0'"),
                    badge + b"maxSdkVersion:'21'\n"):
            with mock.patch.object(M, 'command', return_value=bad) as invoke, self.assertRaises(ValueError):
                M.verify_apk(self.apk())
            self.assertEqual(invoke.call_count, 1)

    def test_apksigner_transition_requires_exact_version_signer(self):
        for version, expected in ((1, LEGACY_SIGNER), (39, LEGACY_SIGNER),
                                  (40, M.PLATFORM_SIGNER), (41, M.PLATFORM_SIGNER),
                                  (2147483647, M.PLATFORM_SIGNER)):
            badge = ("package: name='dev.homepanel.community' versionCode='%d' versionName='test'\n"
                     "sdkVersion:'21'\n" % version).encode('ascii')
            for signer in (LEGACY_SIGNER, M.PLATFORM_SIGNER, '0' * 64):
                signed = ('Verified using v1 scheme (JAR signing): true\n'
                          'Signer #1 certificate SHA-256 digest: ' + signer + '\n').encode('ascii')
                with self.subTest(version=version, signer=signer), \
                        mock.patch.object(M, 'command', side_effect=[badge, signed]):
                    if signer == expected:
                        self.assertEqual(M.verify_apk(self.apk()), version)
                    else:
                        with self.assertRaises(ValueError):
                            M.verify_apk(self.apk())
            signed = ('Verified using v1 scheme (JAR signing): true\n'
                      'Signer #1 certificate SHA-256 digest: ' + expected + '\n'
                      'Signer #2 certificate SHA-256 digest: ' + expected + '\n').encode('ascii')
            with self.subTest(version=version, multiple=True), \
                    mock.patch.object(M, 'command', side_effect=[badge, signed]), self.assertRaises(ValueError):
                M.verify_apk(self.apk())

    def test_transition_signer_mismatch_does_not_publish(self):
        store = self.store()
        existing = self.publish(store, 40)
        before = (self.runtime / 'current.json').read_bytes()
        artifacts = sorted(path.name for path in (self.runtime / 'artifacts').iterdir())
        releases = sorted(path.name for path in (self.runtime / 'releases').iterdir())
        badge = b"package: name='dev.homepanel.community' versionCode='41' versionName='test'\nsdkVersion:'21'\n"
        signed = ('Verified using v1 scheme (JAR signing): true\nSigner #1 certificate SHA-256 digest: '
                  + LEGACY_SIGNER + '\n').encode('ascii')
        with mock.patch.object(M, 'command', side_effect=[badge, signed]), self.assertRaises(ValueError):
            store.publish(self.apk())
        self.assertEqual((self.runtime / 'current.json').read_bytes(), before)
        self.assertEqual(sorted(path.name for path in (self.runtime / 'artifacts').iterdir()), artifacts)
        self.assertEqual(sorted(path.name for path in (self.runtime / 'releases').iterdir()), releases)
        self.assertEqual(store.release()['payload'], M.b64(M.encoded(existing['release'])))

    def test_input_and_storage_reject_links_permissions_and_size(self):
        apk = self.apk()
        symlink = self.base / 'link.apk'
        symlink.symlink_to(apk)
        with self.assertRaises(OSError):
            M.input_apk(symlink)
        linked_dir = self.base / 'directory-link'
        linked_dir.symlink_to(self.base, target_is_directory=True)
        with self.assertRaises(OSError):
            M.input_apk(linked_dir / apk.name)
        hardlink = self.base / 'hard.apk'
        os.link(apk, hardlink)
        with self.assertRaises(ValueError):
            M.input_apk(apk)
        hardlink.unlink()
        apk.chmod(0o620)
        with self.assertRaises(ValueError):
            M.input_apk(apk)
        apk.chmod(0o600)
        with apk.open('r+b') as stream:
            stream.truncate(M.MAX_APK + 1)
        with self.assertRaises(ValueError):
            M.input_apk(apk)
        config = self.runtime / 'config.json'
        config.chmod(0o644)
        with self.assertRaises(ValueError):
            M.load_config(self.runtime)

    def test_payload_and_report_shapes_are_exact_and_bounded(self):
        valid = {'schema': 1, 'action': 'update', 'id': 'a' * 32, 'package': M.PACKAGE,
                 'versionCode': 30, 'size': 4, 'sha256': 'a' * 64, 'expiresAt': 1800000000}
        self.assertEqual(M.validate_payload(valid), valid)
        for change in ({'schema': True}, {'action': 'command'}, {'package': 'net.quber.afactorymode'},
                       {'versionCode': True}, {'size': M.MAX_APK + 1}, {'sha256': '../apk'},
                       {'url': 'http://example.invalid/'}, {'expiresAt': '1800000000'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                M.validate_payload(dict(valid, **change))
        self.assertEqual(M.validate_report(report()), report())
        self.assertEqual(M.validate_report(report(package='net.quber.afactorymode', releaseId=''))['releaseId'], '')
        for change in ({'schema': True}, {'extra': 'ignored'}, {'reason': 'private data'}, {'reason': 'x' * 121},
                       {'releaseId': '/tmp/file'}, {'versionCode': 0}, {'status': 'exec'}, {'home': 'yes'},
                       {'package': 'other.app'}, {'rootStatus': []}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                M.validate_report(report(**change))

    def test_http_release_apk_report_and_internal_status(self):
        with running_server(self.runtime) as server:
            self.assertEqual(request(server)[0], 204)
            self.assertEqual(request(server)[2], b'')
            release = self.publish(server.store)['release']
            code, headers, raw = request(server)
            self.assertEqual(code, 200)
            self.assertEqual(headers['Cache-Control'], 'no-store')
            self.assertEqual(M.BASE.strict_json(base64.b64decode(json.loads(raw)['payload'])), release)
            code, _, raw = request(server, path='/v1/apk/' + release['sha256'])
            self.assertEqual(code, 200)
            self.assertEqual(hashlib.sha256(raw).hexdigest(), release['sha256'])
            code, _, raw = request(server, 'POST', '/v1/report', M.encoded(report()), {'Content-Type': 'application/json'})
            self.assertEqual(code, 200)
            self.assertEqual(json.loads(raw), {'ok': True})
            code, _, raw = request(server, path='/internal/status', headers={'Authorization': 'not-needed'})
            self.assertEqual(code, 200)
            self.assertNotIn(CONFIG['token'].encode(), raw)
            self.assertNotIn(b'PRIVATE KEY', raw)
            self.assertEqual(json.loads(raw)['latestReport']['report'], report())
            self.assertEqual(stat.S_IMODE((self.runtime / 'latest-report.json').stat().st_mode), 0o600)

    def test_http_source_bearer_host_origin_transfer_and_duplicate_headers(self):
        with running_server(self.runtime) as server:
            for headers in ({'Authorization': 'Bearer bad'}, {'Host': 'attacker.invalid'},
                            {'Origin': 'http://192.168.10.20'}, {'Transfer-Encoding': 'chunked'}):
                self.assertEqual(request(server, headers=headers)[0], 403)
            server.config['device'] = '192.168.10.30'
            self.assertEqual(request(server)[0], 403)
            server.config['device'] = '127.0.0.1'
            common = ['GET /v1/release HTTP/1.1', 'Host: ' + server.authority,
                      'Authorization: Bearer ' + CONFIG['token']]
            for extra in ('Host: ' + server.authority, 'Authorization: Bearer ' + CONFIG['token']):
                self.assertEqual(raw_request(server, common + [extra])[0], 403)
            self.assertEqual(raw_request(server, common + ['Content-Length: 0', 'Content-Length: 0'])[0], 400)
            self.assertEqual(raw_request(server, common + ['Expect: 100-continue'])[0], 417)

    def test_http_routes_no_redirects_or_arbitrary_paths(self):
        with running_server(self.runtime) as server:
            for path in ('/v1/release?x=1', '/v1/apk/../config.json', '/v1/apk/%2e%2e/config.json',
                         '/v1/apk/' + 'a' * 64 + '/extra', 'http://other.invalid/v1/release', '/ready'):
                code, headers, _ = request(server, path=path)
                self.assertEqual(code, 404)
                self.assertNotIn('Location', headers)
            self.assertEqual(request(server, 'POST', '/v1/release', b'')[0], 404)
            self.assertEqual(request(server, 'HEAD')[0], 501)
            self.assertEqual(request(server, 'GET', body=b'x')[0], 400)

    def test_http_body_limits_content_type_and_truncation(self):
        with running_server(self.runtime) as server:
            common = ['POST /v1/report HTTP/1.1', 'Host: ' + server.authority,
                      'Authorization: Bearer ' + CONFIG['token'], 'Content-Type: application/json']
            for length, expected in (('32769', 413), ('0', 413), ('-1', 400), ('1e3', 400)):
                self.assertEqual(raw_request(server, common + ['Content-Length: ' + length])[0], expected)
            self.assertEqual(raw_request(server, common)[0], 400)
            self.assertEqual(raw_request(server, common + ['Content-Length: 2', 'Content-Length: 2'])[0], 400)
            self.assertEqual(raw_request(server, common + ['Content-Length: 100'], b'{}', shutdown=True)[0], 400)
            self.assertEqual(request(server, 'POST', '/v1/report', b'{}', {'Content-Type': 'text/plain'})[0], 415)
            for raw in (b'{"schema":1,"schema":1}', b'{"number":NaN}', b'\xff', M.encoded(report(reason=CONFIG['token']))):
                self.assertEqual(request(server, 'POST', '/v1/report', raw, {'Content-Type': 'application/json'})[0], 400)
            self.assertFalse((self.runtime / 'latest-report.json').exists())

    def test_latest_report_symlink_not_followed(self):
        store = self.store()
        target = self.base / 'unrelated'
        target.write_bytes(b'preserve')
        target.chmod(0o600)
        (self.runtime / 'latest-report.json').symlink_to(target)
        with self.assertRaises(OSError):
            store.report(report())
        self.assertEqual(target.read_bytes(), b'preserve')

    def test_local_withdraw_removes_only_current_release(self):
        store = self.store()
        release = self.publish(store)['release']
        artifact = store.apk(release['sha256'])
        store.report(report())
        self.assertIsNotNone(store.release())
        result = store.withdraw()
        self.assertTrue(result['withdrawn'])
        self.assertEqual(result['releaseId'], release['id'])
        self.assertIsNone(store.release())
        self.assertEqual(store.apk(release['sha256']), artifact)
        self.assertTrue((self.runtime / 'releases' / (release['id'] + '.json')).exists())
        self.assertTrue((self.runtime / 'latest-report.json').exists())
        self.assertFalse(store.withdraw()['withdrawn'])
        with running_server(self.runtime) as server:
            self.assertEqual(request(server, 'POST', '/v1/withdraw', b'{}')[0], 404)

    def test_home_report_roundtrip_keeps_regular_status_backwards_compatible(self):
        with running_server(self.runtime) as server:
            headers = {'Content-Type': 'application/json'}
            self.assertEqual(request(server, 'POST', '/v1/report', M.encoded(report()), headers)[0], 200)
            before = server.store.status()
            evidence = home_report()
            evidence['home']['preferences'][0]['filter'] = '<filter>home-private-evidence</filter>'
            code, _, raw = request(server, 'POST', '/v1/home-report', M.encoded(evidence), headers)
            self.assertEqual(code, 200)
            self.assertEqual(json.loads(raw), {'ok': True})
            path = self.runtime / 'latest-home-report.json'
            saved = json.loads(path.read_bytes())
            self.assertEqual(set(saved), {'receivedAt', 'report'})
            self.assertIs(type(saved['receivedAt']), int)
            self.assertEqual(saved['report'], evidence)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(path.stat().st_nlink, 1)
            self.assertEqual(server.store.status(), before)
            code, _, raw = request(server, path='/internal/status')
            self.assertEqual(code, 200)
            self.assertNotIn(b'home-private-evidence', raw)
            self.assertNotIn(b'homeReport', raw)
            self.assertEqual(request(server, path='/v1/home-report')[0], 404)
            self.assertEqual(request(server, 'POST', '/v1/report', M.encoded(report(status='idle')), headers)[0], 200)
            self.assertEqual(json.loads(path.read_bytes()), saved)

    def test_home_report_requires_same_device_and_bearer(self):
        with running_server(self.runtime) as server:
            headers = {'Content-Type': 'application/json'}
            raw = M.encoded(home_report())
            self.assertEqual(request(server, 'POST', '/v1/home-report', raw,
                                     dict(headers, Authorization='Bearer wrong'))[0], 403)
            server.config['device'] = '192.168.10.30'
            self.assertEqual(request(server, 'POST', '/v1/home-report', raw, headers)[0], 403)
            self.assertFalse((self.runtime / 'latest-home-report.json').exists())

    def test_home_report_rejects_extra_fields_bad_types_and_limits(self):
        cases = [home_report(schema=True), home_report(package='net.quber.afactorymode'),
                 home_report(versionCode=True), home_report(versionCode=0), home_report(extra=1)]
        changes = ({'extra': 1}, {'resolved': 'x' * 257}, {'resolved': 'x\n'},
                   {'greetingEnabled': True}, {'greetingEnabled': -2}, {'greetingEnabled': 5},
                   {'candidates': ['x'] * 17}, {'candidates': [7]}, {'candidates': ['x' * 257]},
                   {'preferences': [{}]}, {'preferences': [{'component': 'x', 'filter': 'x', 'extra': 1}]},
                   {'preferences': [{'component': 'x', 'filter': 'x' * 2049}]},
                   {'preferences': [{'component': 'x' * 257, 'filter': 'x'}]},
                   {'preferences': [{'component': 'x', 'filter': '\x00'}]},
                   {'preferences': [{'component': 'x', 'filter': 'x'}] * 17},
                   {'preferences': [{'component': 'x', 'filter': 'x' * 2048}] * 5})
        for change in changes:
            value = home_report()
            value['home'].update(change)
            cases.append(value)
        with running_server(self.runtime) as server:
            for value in cases:
                with self.subTest(value=value):
                    code, _, _ = request(server, 'POST', '/v1/home-report', M.encoded(value),
                                         {'Content-Type': 'application/json'})
                    self.assertIn(code, (400, 413))
            self.assertFalse((self.runtime / 'latest-home-report.json').exists())

    def test_home_report_strict_json_transport_and_credential_rejection(self):
        with running_server(self.runtime) as server:
            common = ['POST /v1/home-report HTTP/1.1', 'Host: ' + server.authority,
                      'Authorization: Bearer ' + CONFIG['token'], 'Content-Type: application/json']
            self.assertEqual(raw_request(server, common + ['Content-Length: 8193'])[0], 413)
            self.assertEqual(raw_request(server, common + ['Content-Length: 2', 'Content-Length: 2'])[0], 400)
            self.assertEqual(raw_request(server, common + ['Content-Length: 100'], b'{}', shutdown=True)[0], 400)
            credential = home_report()
            credential['home']['resolved'] = CONFIG['token']
            for raw in (b'{"schema":1,"schema":1}', b'{"home":NaN}', M.encoded(credential)):
                self.assertEqual(request(server, 'POST', '/v1/home-report', raw,
                                         {'Content-Type': 'application/json'})[0], 400)
            self.assertFalse((self.runtime / 'latest-home-report.json').exists())

    def test_home_report_private_target_does_not_follow_symlinks(self):
        store = self.store()
        target = self.base / 'home-unrelated'
        target.write_bytes(b'unchanged')
        target.chmod(0o600)
        (self.runtime / 'latest-home-report.json').symlink_to(target)
        with self.assertRaises(OSError):
            store.home_report(home_report())
        self.assertEqual(target.read_bytes(), b'unchanged')

    def test_startup_report_roundtrip_private_without_changing_old_endpoints(self):
        with running_server(self.runtime) as server:
            headers = {'Content-Type': 'application/json'}
            self.assertEqual(request(server, 'POST', '/v1/report', M.encoded(report(status='checking')), headers)[0], 200)
            self.assertEqual(request(server, 'POST', '/v1/home-report', M.encoded(home_report()), headers)[0], 200)
            before = server.store.status()
            before_home = (self.runtime / 'latest-home-report.json').read_bytes()
            evidence = startup_report()
            evidence['startup']['bootId'] = 'private-boot-evidence'
            code, _, raw = request(server, 'POST', '/v1/startup-report', M.encoded(evidence), headers)
            self.assertEqual(code, 200)
            self.assertEqual(json.loads(raw), {'ok': True})
            path = self.runtime / 'latest-startup-report.json'
            saved = json.loads(path.read_bytes())
            self.assertEqual(set(saved), {'receivedAt', 'report'})
            self.assertIs(type(saved['receivedAt']), int)
            self.assertEqual(saved['report'], evidence)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(path.stat().st_nlink, 1)
            self.assertEqual(server.store.status(), before)
            self.assertEqual((self.runtime / 'latest-home-report.json').read_bytes(), before_home)
            self.assertNotIn(b'private-boot-evidence', request(server, path='/internal/status')[2])
            self.assertEqual(request(server, path='/v1/startup-report')[0], 404)

    def test_startup_report_rejects_wrong_shapes_numbers_names_and_control_characters(self):
        changes = [(('schema',), True), (('package',), 'net.quber.afactorymode'), (('versionCode',), 0),
                   (('currentPrefs', 'enabled'), 1), (('currentPrefs', 'homeAttempted'), 'true'),
                   (('currentPrefs', 'homeConfigurationVersion'), -2), (('currentPrefs', 'status'), 'x' * 121),
                   (('currentPrefs', 'reason'), 'x\n'), (('startup', 'uptimeMs'), -1),
                   (('startup', 'uptimeMs'), True), (('startup', 'bootId'), 'x' * 65),
                   (('startup', 'properties', 'sys.boot_completed'), 'x' * 129),
                   (('startup', 'properties', 'unknown.property'), '1'),
                   (('startup', 'packages', 0, 'name'), 'arbitrary.package'),
                   (('startup', 'packages', 0, 'installed'), 0),
                   (('startup', 'packages', 0, 'versionCode'), -2),
                   (('startup', 'packages', 0, 'firstInstallTime'), 9223372036854775808),
                   (('startup', 'packages', 0, 'enabledState'), 5),
                   (('startup', 'packages', 0, 'sourceDir'), 'x\x00y'),
                   (('startup', 'components', 0, 'name'), 'arbitrary/Component'),
                   (('startup', 'components', 0, 'state'), 5),
                   (('startup', 'components', 0, 'state'), False),
                   (('startup', 'topActivities'), ['x'] * 6),
                   (('startup', 'topActivities'), ['x' * 257]),
                   (('startup', 'factoryMarker', 'exists'), 1),
                   (('startup', 'factoryMarker', 'uid'), -2),
                   (('startup', 'factoryMarker', 'mode'), 65536),
                   (('startup', 'factoryMarker', 'size'), -2),
                   (('startup', 'serviceComponents'), ['x'] * 17),
                   (('startup', 'serviceComponents'), ['x\t']),
                   (('startup', 'error'), 'x' * 121)]
        cases = [startup_report(extra=1)]
        for path, replacement in changes:
            value = startup_report()
            container = value
            for key in path[:-1]:
                container = container[key]
            container[path[-1]] = replacement
            cases.append(value)
        for field in ('packages', 'components'):
            value = startup_report()
            value['startup'][field][1] = copy.deepcopy(value['startup'][field][0])
            cases.append(value)
        for field in ('currentPrefs', 'startup'):
            value = startup_report()
            value[field]['extra'] = 'forbidden'
            cases.append(value)
        with running_server(self.runtime) as server:
            for index, value in enumerate(cases):
                with self.subTest(index=index):
                    self.assertEqual(request(server, 'POST', '/v1/startup-report', M.encoded(value),
                                             {'Content-Type': 'application/json'})[0], 400)
            self.assertFalse((self.runtime / 'latest-startup-report.json').exists())

    def test_startup_report_auth_body_limit_json_and_credential_rejection(self):
        with running_server(self.runtime) as server:
            headers = {'Content-Type': 'application/json'}
            value = M.encoded(startup_report())
            self.assertEqual(request(server, 'POST', '/v1/startup-report', value,
                                     dict(headers, Authorization='Bearer wrong'))[0], 403)
            server.config['device'] = '192.168.10.30'
            self.assertEqual(request(server, 'POST', '/v1/startup-report', value, headers)[0], 403)
            server.config['device'] = '127.0.0.1'
            common = ['POST /v1/startup-report HTTP/1.1', 'Host: ' + server.authority,
                      'Authorization: Bearer ' + CONFIG['token'], 'Content-Type: application/json']
            self.assertEqual(raw_request(server, common + ['Content-Length: 8193'])[0], 413)
            self.assertEqual(raw_request(server, common + ['Content-Length: 2', 'Content-Length: 2'])[0], 400)
            self.assertEqual(raw_request(server, common + ['Content-Length: 100'], b'{}', shutdown=True)[0], 400)
            secret = startup_report()
            secret['startup']['bootId'] = CONFIG['token']
            for raw in (b'{"schema":1,"schema":1}', b'{"startup":NaN}', M.encoded(secret)):
                self.assertEqual(request(server, 'POST', '/v1/startup-report', raw, headers)[0], 400)
            self.assertFalse((self.runtime / 'latest-startup-report.json').exists())

    def test_startup_report_allows_partial_unknown_evidence_and_bounded_modes(self):
        evidence = startup_report()
        evidence['startup']['packages'] = []
        evidence['startup']['components'] = []
        evidence['startup']['factoryMarker']['mode'] = 33152
        evidence['startup']['factoryMarker']['uid'] = 0
        self.assertEqual(M.validate_startup_report(evidence), evidence)
        store = self.store()
        store.startup_report(evidence)
        target = self.runtime / 'latest-startup-report.json'
        self.assertEqual(json.loads(target.read_bytes())['report'], evidence)

    def test_startup_report_private_target_rejects_symlinks(self):
        store = self.store()
        target = self.base / 'startup-unrelated'
        target.write_bytes(b'unchanged')
        target.chmod(0o600)
        (self.runtime / 'latest-startup-report.json').symlink_to(target)
        with self.assertRaises(OSError):
            store.startup_report(startup_report())
        self.assertEqual(target.read_bytes(), b'unchanged')

    def test_startup_package_flags_support_old_and_extended_exact_shapes(self):
        with running_server(self.runtime) as server:
            headers = {'Content-Type': 'application/json'}
            old = startup_report()
            self.assertEqual(request(server, 'POST', '/v1/startup-report', M.encoded(old), headers)[0], 200)
            extended = copy.deepcopy(old)
            for item, flags in zip(extended['startup']['packages'], (-2147483648, -1, 2147483647)):
                item.update(flags=flags, bootPermissionGranted=True)
            self.assertEqual(request(server, 'POST', '/v1/startup-report', M.encoded(extended), headers)[0], 200)
            for change in ({'flags': 0}, {'bootPermissionGranted': True},
                           {'flags': True, 'bootPermissionGranted': True},
                           {'flags': -2147483649, 'bootPermissionGranted': True},
                           {'flags': 2147483648, 'bootPermissionGranted': True},
                           {'flags': 1, 'bootPermissionGranted': 1}):
                malformed = copy.deepcopy(old)
                malformed['startup']['packages'][0].update(change)
                self.assertEqual(request(server, 'POST', '/v1/startup-report', M.encoded(malformed), headers)[0], 400)

    def test_crash_report_private_roundtrip_with_no_status_exposure(self):
        with running_server(self.runtime) as server:
            before = server.store.status()
            evidence = crash_report()
            evidence['crash']['entries'][0]['frames'].extend([
                {'className': 'net.quber.afactorymode.AFactoryModeMainActivity', 'methodName': '<init>',
                 'fileName': 'unknown', 'lineNumber': -2},
                {'className': 'net.quber.afactorymode.AFactoryModeMainActivity', 'methodName': '<clinit>',
                 'fileName': 'SourceFile', 'lineNumber': -1}])
            code, _, raw = request(server, 'POST', '/v1/crash-report', M.encoded(evidence), {'Content-Type': 'application/json'})
            self.assertEqual(code, 200)
            self.assertEqual(json.loads(raw), {'ok': True})
            path = self.runtime / 'latest-crash-report.json'
            saved = json.loads(path.read_bytes())
            self.assertEqual(set(saved), {'receivedAt', 'report'})
            self.assertEqual(saved['report'], evidence)
            self.assertIs(type(saved['receivedAt']), int)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(path.stat().st_nlink, 1)
            self.assertEqual(server.store.status(), before)
            self.assertNotIn(b'RuntimeException', request(server, path='/internal/status')[2])
            self.assertEqual(request(server, path='/v1/crash-report')[0], 404)

    def test_crash_report_schema_identifier_and_evidence_limits(self):
        changes = [(('schema',), True), (('versionCode',), 0), (('package',), 'net.quber.afactorymode'),
                   (('crash', 'error'), 'x' * 121), (('crash', 'error'), 'x\x00'),
                   (('crash', 'entries', 0, 'timeMs'), -1), (('crash', 'entries', 0, 'timeMs'), True),
                   (('crash', 'entries', 0, 'timeMs'), 9223372036854775808),
                   (('crash', 'entries', 0, 'package'), 'other.package'),
                   (('crash', 'entries', 0, 'tag'), 'system_app_crash'),
                   (('crash', 'entries', 0, 'exception'), 'RuntimeException: private message'),
                   (('crash', 'entries', 0, 'exception'), 'a' * 161),
                   (('crash', 'entries', 0, 'frames', 0, 'className'), 'a..b'),
                   (('crash', 'entries', 0, 'frames', 0, 'className'), 'a/b'),
                   (('crash', 'entries', 0, 'frames', 0, 'methodName'), 'run()'),
                   (('crash', 'entries', 0, 'frames', 0, 'fileName'), '/private/WebView.java'),
                   (('crash', 'entries', 0, 'frames', 0, 'fileName'), '../WebView.java'),
                   (('crash', 'entries', 0, 'frames', 0, 'lineNumber'), -3),
                   (('crash', 'entries', 0, 'frames', 0, 'lineNumber'), 2147483648),
                   (('crash', 'entries', 0, 'frames', 0, 'lineNumber'), False)]
        cases = [crash_report(extra=1)]
        for path, replacement in changes:
            value = crash_report(); container = value
            for key in path[:-1]:
                container = container[key]
            container[path[-1]] = replacement
            cases.append(value)
        many = crash_report(); many['crash']['entries'] *= 5; cases.append(many)
        many = crash_report(); many['crash']['entries'][0]['frames'] *= 13; cases.append(many)
        extra = crash_report(); extra['crash']['entries'][0]['message'] = 'not accepted'; cases.append(extra)
        extra = crash_report(); extra['crash']['entries'][0]['frames'][0]['raw'] = 'not accepted'; cases.append(extra)
        oversized = crash_report(); frame = oversized['crash']['entries'][0]['frames'][0]
        frame.update(className='a' * 160, methodName='b' * 160, fileName='c' * 155 + '.java')
        oversized['crash']['entries'][0]['frames'] *= 8
        self.assertGreater(len(M.encoded(oversized['crash'])), 4096)
        self.assertLess(len(M.encoded(oversized)), 8192)
        cases.append(oversized)
        with running_server(self.runtime) as server:
            for index, value in enumerate(cases):
                with self.subTest(index=index):
                    self.assertEqual(request(server, 'POST', '/v1/crash-report', M.encoded(value),
                                             {'Content-Type': 'application/json'})[0], 400)
            self.assertFalse((self.runtime / 'latest-crash-report.json').exists())

    def test_crash_report_auth_transport_and_duplicate_json(self):
        with running_server(self.runtime) as server:
            headers = {'Content-Type': 'application/json'}
            raw = M.encoded(crash_report())
            self.assertEqual(request(server, 'POST', '/v1/crash-report', raw,
                                     dict(headers, Authorization='Bearer wrong'))[0], 403)
            server.config['device'] = '192.168.10.30'
            self.assertEqual(request(server, 'POST', '/v1/crash-report', raw, headers)[0], 403)
            server.config['device'] = '127.0.0.1'
            common = ['POST /v1/crash-report HTTP/1.1', 'Host: ' + server.authority,
                      'Authorization: Bearer ' + CONFIG['token'], 'Content-Type: application/json']
            self.assertEqual(raw_request(server, common + ['Content-Length: 8193'])[0], 413)
            self.assertEqual(raw_request(server, common + ['Content-Length: 2', 'Content-Length: 2'])[0], 400)
            self.assertEqual(raw_request(server, common + ['Transfer-Encoding: chunked', 'Content-Length: 2'])[0], 403)
            self.assertEqual(raw_request(server, common + ['Content-Length: 100'], b'{}', shutdown=True)[0], 400)
            repeated = raw.replace(b'"lineNumber":100', b'"lineNumber":100,"lineNumber":101')
            self.assertNotEqual(raw, repeated)
            secret = crash_report(); secret['crash']['error'] = CONFIG['token']
            for value in (repeated, b'{"schema":NaN}', M.encoded(secret)):
                self.assertEqual(request(server, 'POST', '/v1/crash-report', value, headers)[0], 400)
            self.assertFalse((self.runtime / 'latest-crash-report.json').exists())

    def test_crash_report_empty_and_native_entries_and_symlink_rejection(self):
        value = crash_report()
        value['crash']['entries'] = []
        value['crash']['error'] = 'none_found'
        self.assertEqual(M.validate_crash_report(value), value)
        native = crash_report()
        native['crash']['entries'][0].update(package='net.quber.afactorymode', tag='data_app_native_crash', exception='unknown', frames=[])
        self.assertEqual(M.validate_crash_report(native), native)
        store = self.store()
        target = self.base / 'crash-unrelated'
        target.write_bytes(b'unchanged'); target.chmod(0o600)
        (self.runtime / 'latest-crash-report.json').symlink_to(target)
        with self.assertRaises(OSError):
            store.crash_report(value)
        self.assertEqual(target.read_bytes(), b'unchanged')


if __name__ == '__main__':
    unittest.main()
