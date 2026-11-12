import concurrent.futures
import contextlib
import hashlib
import http.client
import importlib.util
import io
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


SPEC = importlib.util.spec_from_file_location('kt_apk_receiver', Path(__file__).resolve().parents[1] / 'kt_apk_receiver.py')
receiver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(receiver)
TOKEN = 'ab' * 32
APK = b'PK\x03\x04test-apk-data'
ELF = b'\x7fELF\x01\x01\x01' + bytes(9) + b'\x03\x00\x28\x00' + bytes(32)


def collection(artifacts=None):
    return {'version': 1, 'model': 'CT1105_SB', 'appUid': 10044,
            'packageName': receiver.PACKAGE, 'targetFound': True,
            'packageVersion': '1.0 (1)', 'diagnostics': 'Read-only collection\ncomplete',
            'nativeLibraryDir': '/system/lib', 'artifacts': artifacts or []}


def artifact(body=APK, kind='apk-0'):
    return {'kind': kind, 'path': '/data/app/com.kt.gigagenie.ollehtv/base.apk',
            'splitName': 'base', 'size': len(body), 'sha256': hashlib.sha256(body).hexdigest()}


@contextlib.contextmanager
def running_server(**options):
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary).resolve()
        reservation = socket.socket()
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
        reservation.close()
        config = {'version': 1, 'host': '127.0.0.1', 'device': options.pop('device', '127.0.0.1'),
                  'port': port, 'token': TOKEN}
        server = receiver.ReceiverServer(config, root / 'capture', **options)
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.01})
        thread.start()
        try:
            yield server, root
        finally:
            server.stop()
            thread.join(timeout=2)
            server.server_close()


def request(server, path='/artifact/apk-0', body=APK, headers=None, method='POST'):
    connection = http.client.HTTPConnection(server.config['host'], server.config['port'], timeout=2)
    initial = {'Authorization': 'Bearer ' + TOKEN,
               'Content-Type': 'application/json' if path == '/collection' else 'application/octet-stream'}
    if headers:
        initial.update(headers)
    try:
        connection.request(method, path, body=body, headers=initial)
        response = connection.getresponse()
        data = json.loads(response.read())
        return response.status, data, response.getheader('Connection')
    finally:
        connection.close()


def raw_request(server, extra_headers, body=APK, length=None):
    sock = socket.create_connection((server.config['host'], server.config['port']), timeout=2)
    headers = ['POST /artifact/apk-0 HTTP/1.1', 'Host: ' + server.authority,
               'Authorization: Bearer ' + TOKEN, 'Content-Type: application/octet-stream',
               'Content-Length: ' + str(len(body) if length is None else length)] + extra_headers
    try:
        sock.sendall(('\r\n'.join(headers) + '\r\n\r\n').encode() + body)
        sock.shutdown(socket.SHUT_WR)
        response = http.client.HTTPResponse(sock)
        response.begin()
        result = response.status, json.loads(response.read()), response.getheader('Connection')
        return result
    finally:
        sock.close()


class ReceiverTests(unittest.TestCase):
    def test_service_library_can_be_the_fourth_verified_artifact(self):
        with running_server(package='com.android.settings') as (server, _):
            self.assertEqual(request(server)[0], 200)
            items = [artifact()]
            for kind, name in receiver.LIBRARIES.items():
                self.assertEqual(request(server, '/artifact/' + kind, ELF)[0], 200)
                items.append({**artifact(ELF, kind), 'path': '/system/lib/' + name, 'splitName': name})
            report = {**collection(items), 'packageName': 'com.android.settings'}
            self.assertEqual(request(server, '/collection', json.dumps(report).encode())[0], 200)
            self.assertEqual(server.store.status()['artifacts'], 4)
            self.assertEqual(request(server, '/artifact/apk-1')[0], 413)

    def test_four_artifact_limit_includes_inflight_libraries_and_allows_retries(self):
        with running_server(package='com.android.settings') as (server, _):
            for index in range(3):
                self.assertEqual(request(server, '/artifact/apk-' + str(index))[0], 200)
            server.store.reserve('elf-0', len(ELF))
            try:
                self.assertEqual(request(server, '/artifact/elf-1', ELF)[0], 413)
                self.assertEqual(request(server)[0], 200)
            finally:
                server.store.release('elf-0')
            self.assertEqual(request(server, '/artifact/elf-1', ELF)[0], 200)
            self.assertEqual(request(server, '/artifact/apk-3')[0], 413)

    def test_libraries_are_settings_only_and_require_arm_shared_elf(self):
        with running_server() as (server, _):
            self.assertEqual(request(server, '/artifact/elf-0', ELF)[0], 404)
        with running_server(package='com.android.settings') as (server, root):
            for invalid in [APK, ELF[:19], ELF[:18] + b'\x3e\x00' + ELF[20:]]:
                self.assertEqual(request(server, '/artifact/elf-0', invalid)[0], 400)
            status, ack, _ = request(server, '/artifact/elf-0', ELF)
            self.assertEqual(status, 200)
            item = {**artifact(ELF, 'elf-0'), 'path': '/system/lib/libsysmanagerservice_jni.so',
                    'splitName': 'libsysmanagerservice_jni.so'}
            report = {**collection([item]), 'packageName': 'com.android.settings'}
            self.assertEqual(request(server, '/collection', json.dumps(report).encode())[0], 200)
            for change in [{'path': '/data/data/private.so'}, {'splitName': 'unexpected.so'},
                           {'path': '/system/../data/libsysmanagerservice_jni.so'}, {'size': receiver.MAX_ELF + 1}]:
                invalid = {**report, 'artifacts': [{**item, **change}]}
                with self.assertRaises(ValueError):
                    receiver.validate_collection(invalid, 'com.android.settings')
            saved = root / 'capture' / 'artifacts' / ('elf-0-' + ack['sha256'] + '.so')
            self.assertEqual(saved.read_bytes(), ELF)
            self.assertEqual(stat.S_IMODE(saved.stat().st_mode), 0o600)

    def test_each_receiver_accepts_only_its_selected_allowlisted_package(self):
        for package in receiver.PACKAGES:
            with self.subTest(package=package), running_server(package=package) as (server, root):
                self.assertEqual(request(server)[0], 200)
                valid = {**collection([artifact()]), 'packageName': package}
                self.assertEqual(request(server, '/collection', json.dumps(valid).encode())[0], 200)
                for other in receiver.PACKAGES | {'com.other.app'}:
                    if other != package:
                        self.assertEqual(request(server, '/collection', json.dumps({**valid, 'packageName': other}).encode())[0], 400)
                captures = list((root / 'capture' / 'collections').glob('*.json'))
                self.assertEqual(len(captures), 1)
                self.assertEqual(json.loads(captures[0].read_text())['result']['packageName'], package)

    def test_cli_keeps_old_defaults_and_selects_isolated_package_runtimes(self):
        original_umask = os.umask(0o077)
        os.umask(original_umask)
        try:
            for arguments, package, runtime in [
                    ([], receiver.PACKAGE, receiver.RUNTIME),
                    (['--package', 'com.kt.gigagenie.mc', '--runtime', str(receiver.ROOT / 'runtime' / 'kt-mc-apk')],
                     'com.kt.gigagenie.mc', receiver.ROOT / 'runtime' / 'kt-mc-apk'),
                    (['--package', 'com.kt.gigagenie.launcher', '--runtime', str(receiver.ROOT / 'runtime' / 'kt-home-apk')],
                     'com.kt.gigagenie.launcher', receiver.ROOT / 'runtime' / 'kt-home-apk'),
                    (['--package', 'com.android.settings', '--runtime', str(receiver.ROOT / 'runtime' / 'android-settings-apk')],
                     'com.android.settings', receiver.ROOT / 'runtime' / 'android-settings-apk')]:
                with self.subTest(package=package), mock.patch.object(receiver, 'load_config', return_value={}) as load, \
                        mock.patch.object(receiver, 'ReceiverServer') as constructor, \
                        mock.patch.object(receiver.threading, 'Timer'), mock.patch.object(receiver.signal, 'signal'), \
                        contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(receiver.main(arguments), 0)
                    load.assert_called_once_with(runtime / 'config.json')
                    constructor.assert_called_once_with({}, runtime=runtime, package=package)
                    constructor.return_value.server_close.assert_called_once()
            for arguments in [['--package', 'com.other.app'], ['--runtime', '/tmp/kt-mc-apk'],
                              ['--runtime', str(receiver.ROOT / 'runtime')],
                              ['--runtime', str(receiver.ROOT / 'runtime' / '..' / 'outside')]]:
                with self.subTest(arguments=arguments), mock.patch.object(receiver, 'ReceiverServer') as constructor, \
                        contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    receiver.main(arguments)
                self.assertEqual(error.exception.code, 2)
                constructor.assert_not_called()
        finally:
            os.umask(original_umask)

    def test_config_rejects_duplicates_links_and_broad_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            config = root / 'config.json'
            data = {'version': 1, 'host': '192.168.10.20', 'device': '192.168.10.30',
                    'port': 4182, 'token': TOKEN}
            config.write_text(json.dumps(data))
            config.chmod(0o600)
            self.assertEqual(receiver.load_config(config)['port'], 4182)
            config.chmod(0o644)
            with self.assertRaises(ValueError):
                receiver.load_config(config)
            config.chmod(0o600)
            linked = root / 'linked.json'
            linked.symlink_to(config)
            with self.assertRaises(OSError):
                receiver.load_config(linked)
            linked.unlink()
            os.link(config, linked)
            with self.assertRaises(ValueError):
                receiver.load_config(config)
            linked.unlink()
            config.write_text(json.dumps(data)[:-1] + ',"version":1}')
            with self.assertRaises(ValueError):
                receiver.load_config(config)

    def test_upload_retry_conflict_and_collection_history_remain_private(self):
        with running_server() as (server, root):
            status, ack, close = request(server)
            self.assertEqual((status, close), (200, 'close'))
            self.assertEqual(ack, {'ok': True, 'size': len(APK), 'sha256': artifact()['sha256']})
            self.assertEqual(request(server)[1], ack)
            different = b'PK\x03\x04different-apk'
            self.assertEqual(request(server, body=different)[0], 409)
            destination = root / 'capture' / 'artifacts' / ('apk-0-' + ack['sha256'] + '.apk')
            self.assertEqual(destination.read_bytes(), APK)
            self.assertEqual(stat.S_IMODE(destination.stat().st_mode), 0o600)
            payload = json.dumps(collection([artifact()])).encode()
            for _ in range(2):
                self.assertEqual(request(server, '/collection', payload)[0:2], (200, {'ok': True}))
            captures = list((root / 'capture' / 'collections').glob('*.json'))
            self.assertEqual(len(captures), 2)
            for capture in captures:
                envelope = json.loads(capture.read_text())
                self.assertEqual(envelope['result'], collection([artifact()]))
                self.assertIn('received_at', envelope)
                self.assertNotIn(TOKEN, capture.read_text())
                self.assertEqual(stat.S_IMODE(capture.stat().st_mode), 0o600)
            status, summary, _ = request(server, '/internal/status', None, method='GET')
            self.assertEqual(status, 200)
            self.assertEqual(summary['artifacts'], 1)
            self.assertEqual(summary['collections'], 2)
            self.assertEqual(summary['bytesReceived'], len(APK) * 2 + len(different))
            self.assertNotIn(TOKEN, json.dumps(summary))

    def test_authority_authentication_origin_and_framing_are_exact(self):
        with running_server() as (server, root):
            for headers in [{'Authorization': 'Bearer wrong'}, {'Origin': ''},
                            {'Origin': 'http://example.test'}, {'Host': 'elsewhere:4182'}]:
                with self.subTest(headers=list(headers)):
                    self.assertEqual(request(server, headers=headers)[0], 403)
            for headers in [['Host: ' + server.authority], ['Authorization: Bearer ' + TOKEN],
                            ['Content-Length: ' + str(len(APK))], ['Transfer-Encoding: chunked'],
                            ['Content-Type: application/octet-stream'], ['Expect: 100-continue']]:
                with self.subTest(header=headers[0].split(':')[0]):
                    status, result, close = raw_request(server, headers)
                    self.assertGreaterEqual(status, 400)
                    self.assertEqual((result, close), ({'ok': False}, 'close'))
            self.assertEqual(request(server, '/artifact/apk-4')[0], 404)
            self.assertEqual(request(server, '/artifact/../outside')[0], 404)
            self.assertEqual(request(server, '/artifact/apk-0?other')[0], 404)
            self.assertEqual(list((root / 'capture' / 'artifacts').iterdir()), [])
        with running_server(device='127.0.0.2') as (server, _root):
            self.assertEqual(request(server)[0], 403)
            self.assertEqual(request(server, '/internal/status', None, method='GET')[0], 200)

    def test_size_total_budget_and_failed_bytes_are_bounded(self):
        with running_server(max_apk=16, max_total=24) as (server, root):
            self.assertEqual(request(server, body=b'x' * 17)[0], 413)
            self.assertEqual(request(server, body=b'not-zip!')[0], 400)
            self.assertEqual(request(server, body=b'PK\x03\x04' + b'a' * 12)[0], 200)
            self.assertEqual(request(server, '/artifact/apk-1', b'PK\x03\x04')[0], 413)
            self.assertEqual(server.store.status()['bytesReceived'], 24)
            self.assertEqual(server.store.status()['reservedBytes'], 0)
            self.assertEqual(len(list((root / 'capture' / 'artifacts').glob('*.apk'))), 1)

    def test_partial_upload_is_removed_and_its_budget_is_not_erased(self):
        with running_server() as (server, root):
            self.assertEqual(raw_request(server, [], b'PK\x03\x04', length=20)[0], 400)
            self.assertEqual(list((root / 'capture' / 'artifacts').iterdir()), [])
            self.assertEqual(server.store.status()['bytesReceived'], 4)
            self.assertEqual(server.store.status()['reservedBytes'], 0)
            self.assertEqual(request(server)[0], 200)

    def test_collection_rejects_unreceived_changed_and_invalid_metadata(self):
        with running_server() as (server, root):
            valid = collection([artifact()])
            self.assertEqual(request(server, '/collection', json.dumps(valid).encode())[0], 400)
            self.assertEqual(request(server)[0], 200)
            for patch in [{'packageName': 'com.other.app'}, {'appUid': True},
                          {'diagnostics': 'x' * 8193}, {'targetFound': False},
                          {'artifacts': [artifact(), artifact()]}, {'diagnostics': TOKEN}]:
                with self.subTest(keys=list(patch)):
                    self.assertEqual(request(server, '/collection', json.dumps({**valid, **patch}).encode())[0], 400)
            for field, value in [('sha256', '0' * 64), ('size', len(APK) + 1)]:
                wrong = collection([{**artifact(), field: value}])
                self.assertEqual(request(server, '/collection', json.dumps(wrong).encode())[0], 400)
            duplicate = json.dumps(valid)[:-1] + ',"version":1}'
            self.assertEqual(request(server, '/collection', duplicate.encode())[0], 400)
            self.assertEqual(request(server, '/collection', b' ' * 32769)[0], 413)
            destination = next((root / 'capture' / 'artifacts').glob('*.apk'))
            destination.write_bytes(b'x' * len(APK))
            self.assertEqual(request(server, '/collection', json.dumps(valid).encode())[0], 400)
            self.assertEqual(list((root / 'capture' / 'collections').iterdir()), [])

    def test_existing_artifact_symlink_is_not_followed_or_overwritten(self):
        with running_server() as (server, root):
            outside = root / 'outside'
            outside.write_bytes(b'untouched')
            destination = root / 'capture' / 'artifacts' / ('apk-0-' + artifact()['sha256'] + '.apk')
            destination.symlink_to(outside)
            self.assertEqual(request(server)[0], 400)
            self.assertEqual(outside.read_bytes(), b'untouched')
            self.assertTrue(destination.is_symlink())
            self.assertEqual(server.store.status()['artifacts'], 0)

    def test_concurrent_reservations_cannot_overbook_total_or_replace_a_kind(self):
        with running_server(max_apk=16, max_total=24) as (server, _root):
            barrier = threading.Barrier(2)
            def reserve(kind):
                barrier.wait()
                try:
                    server.store.reserve(kind, 16)
                    return kind
                except receiver.RequestRejected as error:
                    return error.status
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
                results = list(executor.map(reserve, ['apk-0', 'apk-1']))
            self.assertEqual(results.count(413), 1)
            chosen = next(value for value in results if isinstance(value, str))
            with self.assertRaises(receiver.RequestRejected) as failure:
                server.store.reserve(chosen, 4)
            self.assertEqual(failure.exception.status, 409)
            self.assertEqual(server.store.status()['reservedBytes'], 16)
            server.store.release(chosen)

    def test_stalled_body_times_out_and_cleans_up(self):
        with running_server(read_timeout=0.05, request_timeout=0.2) as (server, root):
            sock = socket.create_connection((server.config['host'], server.config['port']), timeout=2)
            try:
                headers = ['POST /artifact/apk-0 HTTP/1.1', 'Host: ' + server.authority,
                           'Authorization: Bearer ' + TOKEN, 'Content-Type: application/octet-stream',
                           'Content-Length: 20']
                sock.sendall(('\r\n'.join(headers) + '\r\n\r\n').encode() + b'PK\x03\x04')
                response = http.client.HTTPResponse(sock)
                response.begin()
                self.assertEqual(response.status, 408)
                response.read()
            finally:
                sock.close()
            deadline = time.monotonic() + 1
            while server.store.status()['reservedBytes'] and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertEqual(server.store.status()['reservedBytes'], 0)
            self.assertEqual(list((root / 'capture' / 'artifacts').iterdir()), [])


if __name__ == '__main__':
    unittest.main()
