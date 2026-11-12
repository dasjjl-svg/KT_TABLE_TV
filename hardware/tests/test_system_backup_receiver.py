import base64
import contextlib
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
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location('system_backup_receiver', Path(__file__).resolve().parents[1] / 'system_backup_receiver.py')
receiver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(receiver)
TOKEN = 'ab' * 32
CAPTURE = '12' * 16
PART = 'mmcblk0p1'
BODY = b'a' * receiver.CHUNK_SIZE + b'b' * 512


def file_record(name, raw=b'', status='ok'):
    return {'id': name, 'source': receiver.FILES[name], 'status': status,
            'data': base64.b64encode(raw).decode('ascii'), 'sha256': hashlib.sha256(raw).hexdigest()}


def inventory():
    mounts = b'26 20 179:1 / /system ro,relatime - ext4 /dev/block/mmcblk0p1 ro\n'
    return {'version': 1, 'model': 'CT1105_SB', 'appUid': 10050, 'diagnostics': 'identity.uid=0\n',
            'files': [file_record('block_map', b'mmcblk0|179:0|100000|0|0\nmmcblk0p1|179:1|2049|2048|0\nmmcblk0p2|179:2|512|4097|0\nmmcblk0boot0|179:8|8192|-1|1\n'),
                      file_record('mountinfo', mounts), file_record('app_mountinfo', mounts),
                      file_record('init_rc', b'import /init.bigfish.rc\n')]
            }


def json_bytes(data):
    return json.dumps(data, separators=(',', ':')).encode('utf-8')


@contextlib.contextmanager
def running_server(root=None, **options):
    with contextlib.ExitStack() as stack:
        if root is None:
            root = Path(stack.enter_context(tempfile.TemporaryDirectory())).resolve() / 'capture'
        reservation = socket.socket()
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
        reservation.close()
        config = {'version': 1, 'host': '127.0.0.1', 'device': options.pop('device', '127.0.0.1'), 'port': port, 'token': TOKEN}
        server = receiver.BackupServer(config, root, min_free=0, **options)
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.01})
        thread.start()
        try:
            yield server, root
        finally:
            server.stop()
            thread.join(timeout=2)
            server.server_close()


def request(server, path, body=None, method='POST', headers=None):
    connection = http.client.HTTPConnection(server.config['host'], server.config['port'], timeout=4)
    initial = {'Authorization': 'Bearer ' + TOKEN,
               'Content-Type': 'application/octet-stream' if path.startswith('/chunk/') else 'application/json'}
    if path.startswith('/chunk/') and body is not None:
        initial['X-Content-SHA256'] = hashlib.sha256(body).hexdigest()
    initial.update(headers or {})
    try:
        try:
            connection.request(method, path, body=body, headers=initial)
        except BrokenPipeError:
            pass
        response = connection.getresponse()
        raw = response.read()
        return response.status, json.loads(raw) if raw else None
    finally:
        connection.close()


def setup_capture(server, root, data=None):
    data = inventory() if data is None else data
    raw = json_bytes(data)
    status, answer = request(server, '/inventory/' + CAPTURE, raw)
    if status != 200:
        raise AssertionError('inventory not accepted: ' + str(status))
    plan = {'version': 1, 'captureId': CAPTURE, 'inventorySha256': answer['inventorySha256'],
            'partitions': [{'id': PART, 'device': '/dev/block/' + PART, 'size': len(BODY), 'chunkSize': receiver.CHUNK_SIZE}]}
    path = root / 'plan.json'
    path.write_bytes(json_bytes(plan))
    path.chmod(0o600)
    return plan


class BackupReceiverTests(unittest.TestCase):
    def test_spi_read_samples_roundtrip_verifies_37_files_and_reloads(self):
        samples = bytes(range(256)) * 8
        evidence = b'status=ok\nreason=samples_read\naddress_space_mode=unverified\n'
        data = {'version': 1, 'model': 'CT1105_SB', 'appUid': 10050, 'files': [
            file_record(name, samples if name == 'spi_read_samples' else evidence if name == 'spi_read_samples_status' else b'',
                        'ok' if name in {'spi_read_samples', 'spi_read_samples_status'} else 'absent')
            for name in receiver.FILES if name not in {'spi_identity', 'spi_identity_status', 'spi_nor_main', 'spi_nor_main_status'}]}
        self.assertEqual(len(data['files']), 37)
        raw = json_bytes(data)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / 'capture'
            with running_server(root) as (server, _):
                status, answer = request(server, '/inventory/' + CAPTURE, raw)
                self.assertEqual(status, 200)
                self.assertEqual(answer['inventorySha256'], hashlib.sha256(raw).hexdigest())
                folder = root / 'captures' / CAPTURE / 'files'
                for item in data['files']:
                    saved = folder / (item['id'] + '.bin')
                    content = saved.read_bytes()
                    self.assertEqual(content, base64.b64decode(item['data']))
                    self.assertEqual(hashlib.sha256(content).hexdigest(), item['sha256'])
                    info = saved.stat()
                    self.assertEqual(info.st_uid, os.getuid())
                    self.assertEqual(info.st_nlink, 1)
                    self.assertEqual(stat.S_IMODE(info.st_mode), 0o600)
            with running_server(root) as (server, _):
                status, answer = request(server, '/status/' + CAPTURE, method='GET')
                self.assertEqual(status, 200)
                self.assertEqual(answer['inventorySha256'], hashlib.sha256(raw).hexdigest())
            with (folder / 'spi_read_samples.bin').open('r+b') as stream:
                stream.seek(2047)
                stream.write(b'!')
            with running_server(root) as (server, _):
                self.assertEqual(request(server, '/status/' + CAPTURE, method='GET')[0], 400)

    def test_spi_read_samples_require_exact_size_source_digest_and_empty_failures(self):
        base = {'version': 1, 'model': 'CT1105_SB', 'appUid': 10050, 'files': []}
        self.assertEqual(receiver.FILES['spi_read_samples'], 'native:spi-read-samples')
        self.assertEqual(receiver.FILES['spi_read_samples_status'], 'builtin:spi-read-samples-status')
        self.assertEqual(receiver.file_limit('spi_read_samples'), 2048)
        self.assertEqual(receiver.file_limit('spi_read_samples_status'), 65536)
        for length in [0, 1, 256, 2047, 2049, 65536]:
            with self.subTest(length=length), self.assertRaises(ValueError):
                receiver.validate_inventory({**base, 'files': [file_record('spi_read_samples', b'x' * length)]})
        valid = file_record('spi_read_samples', b'x' * 2048)
        for field, value in [('source', 'native:arbitrary-read'), ('sha256', '0' * 64)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                receiver.validate_inventory({**base, 'files': [{**valid, field: value}]})
        for status in receiver.STATUSES - {'ok'}:
            self.assertEqual(receiver.validate_inventory({**base, 'files': [file_record('spi_read_samples', status=status)]})
                             ['spi_read_samples']['raw'], b'')
            with self.subTest(status=status), self.assertRaises(ValueError):
                receiver.validate_inventory({**base, 'files': [file_record('spi_read_samples', b'x', status)]})
        for record in [file_record('spi_read_samples_status', b'x' * 65537),
                       {**file_record('spi_read_samples_status'), 'source': 'builtin:other-status'}]:
            with self.assertRaises(ValueError):
                receiver.validate_inventory({**base, 'files': [record]})

    def test_spi_read_samples_failure_preserves_status_without_payload(self):
        evidence = b'status=error\nreason=operation_timeout\nrestoration_incomplete=1\n'
        data = inventory()
        data['files'].extend([file_record('spi_read_samples', b'', 'error'),
                              file_record('spi_read_samples_status', evidence)])
        with running_server() as (server, root):
            self.assertEqual(request(server, '/inventory/' + CAPTURE, json_bytes(data))[0], 200)
            folder = root / 'captures' / CAPTURE / 'files'
            self.assertEqual((folder / 'spi_read_samples.bin').read_bytes(), b'')
            self.assertEqual((folder / 'spi_read_samples_status.bin').read_bytes(), evidence)

    def test_four_mib_spi_main_array_roundtrip_reload_and_tamper_detection(self):
        image = bytes(range(256)) * (receiver.MAX_SPI_MAIN // 256)
        evidence = b'status=ok\nreason=main_array_read\nreads_match=1\nconfig_restored=1\n'
        data = {'version': 1, 'model': 'CT1105_SB', 'appUid': 10050, 'files': [
            file_record(name, image if name == 'spi_nor_main' else evidence if name == 'spi_nor_main_status' else b'',
                        'ok' if name in {'spi_nor_main', 'spi_nor_main_status'} else 'absent')
            for name in receiver.FILES if name not in {'spi_identity', 'spi_identity_status', 'spi_read_samples', 'spi_read_samples_status'}]}
        self.assertEqual(len(data['files']), 37)
        raw = json_bytes(data)
        self.assertLess(len(raw), receiver.MAX_INVENTORY)
        self.assertEqual(receiver.MAX_INVENTORY, 8 * 1024 * 1024)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / 'capture'
            with running_server(root) as (server, _):
                status, answer = request(server, '/inventory/' + CAPTURE, raw)
                self.assertEqual(status, 200)
                self.assertEqual(answer['inventorySha256'], hashlib.sha256(raw).hexdigest())
                folder = root / 'captures' / CAPTURE / 'files'
                for name, content in [('spi_nor_main', image), ('spi_nor_main_status', evidence)]:
                    saved = folder / (name + '.bin')
                    self.assertEqual(saved.read_bytes(), content)
                    info = saved.stat()
                    self.assertEqual(info.st_uid, os.getuid())
                    self.assertEqual(info.st_nlink, 1)
                    self.assertEqual(stat.S_IMODE(info.st_mode), 0o600)
                    self.assertEqual(hashlib.sha256(saved.read_bytes()).hexdigest(), hashlib.sha256(content).hexdigest())
            with running_server(root) as (server, _):
                status, answer = request(server, '/status/' + CAPTURE, method='GET')
                self.assertEqual(status, 200)
                self.assertEqual(answer['inventorySha256'], hashlib.sha256(raw).hexdigest())
            with (folder / 'spi_nor_main.bin').open('r+b') as stream:
                stream.seek(len(image) - 1)
                stream.write(b'!')
            with running_server(root) as (server, _):
                self.assertEqual(request(server, '/status/' + CAPTURE, method='GET')[0], 400)

    def test_spi_main_array_requires_exact_size_digest_source_and_empty_failures(self):
        base = {'version': 1, 'model': 'CT1105_SB', 'appUid': 10050, 'files': []}
        self.assertEqual(receiver.FILES['spi_nor_main'], 'native:spi-nor-main-array')
        self.assertEqual(receiver.FILES['spi_nor_main_status'], 'builtin:spi-nor-main-status')
        for size in [0, receiver.MAX_SPI_MAIN - 1, receiver.MAX_SPI_MAIN + 1]:
            with self.subTest(size=size), self.assertRaises(ValueError):
                receiver.validate_inventory({**base, 'files': [file_record('spi_nor_main', b'x' * size)]})
        for status in receiver.STATUSES - {'ok'}:
            self.assertEqual(receiver.validate_inventory({**base, 'files': [file_record('spi_nor_main', status=status)]})
                             ['spi_nor_main']['raw'], b'')
            with self.subTest(status=status), self.assertRaises(ValueError):
                receiver.validate_inventory({**base, 'files': [file_record('spi_nor_main', b'prefix', status)]})
        valid = file_record('spi_nor_main', b'x' * receiver.MAX_SPI_MAIN)
        for field, value in [('source', 'native:arbitrary-read'), ('sha256', '0' * 64)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                receiver.validate_inventory({**base, 'files': [{**valid, field: value}]})
        for name in receiver.FILES:
            limit = receiver.file_limit(name)
            self.assertEqual(limit, receiver.MAX_SPI_MAIN if name == 'spi_nor_main' else
                             receiver.MAX_SPI_SAMPLES if name == 'spi_read_samples' else
                             receiver.MAX_SPI if name in receiver.SPI_FILES else receiver.MAX_FILE)
        for name in ['spi_nor_main_status', 'spi_identity', 'spi_controller', 'init_rc', 'spi_fastboot', 'spi_bootargs']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                receiver.validate_inventory({**base, 'files': [file_record(name, b'x' * (receiver.file_limit(name) + 1))]})

    def test_spi_main_failure_evidence_is_preserved_and_source_is_fixed(self):
        evidence = b'status=error\nreason=operation_timeout\nrestoration_incomplete=1\n'
        data = inventory()
        data['files'].extend([file_record('spi_nor_main', b'', 'error'), file_record('spi_nor_main_status', evidence)])
        with running_server() as (server, root):
            self.assertEqual(request(server, '/inventory/' + CAPTURE, json_bytes(data))[0], 200)
            folder = root / 'captures' / CAPTURE / 'files'
            self.assertEqual((folder / 'spi_nor_main.bin').read_bytes(), b'')
            self.assertEqual((folder / 'spi_nor_main_status.bin').read_bytes(), evidence)
            self.assertEqual(stat.S_IMODE((folder / 'spi_nor_main_status.bin').stat().st_mode), 0o600)
        data['files'][-1]['source'] = 'builtin:arbitrary-status'
        with self.assertRaises(ValueError):
            receiver.validate_inventory(data)

    def test_spi_controller_snapshot_and_failure_evidence_roundtrip(self):
        data = inventory()
        snapshot = b'version=1\nsource=spi_controller_state\ncrg224_before=0x00000000\ncontroller_access=skipped\nflash_read=not_performed\n'
        evidence = b'status=ok\nreason=clock_off_or_reset_no_controller_read\ncaller_uid=0\n'
        data['files'].extend([file_record('spi_controller', snapshot), file_record('spi_controller_status', evidence)])
        with running_server() as (server, root):
            status, _ = request(server, '/inventory/' + CAPTURE, json_bytes(data))
            self.assertEqual(status, 200)
            for name, raw in [('spi_controller', snapshot), ('spi_controller_status', evidence)]:
                path = root / 'captures' / CAPTURE / 'files' / (name + '.bin')
                self.assertEqual(path.read_bytes(), raw)
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        data['files'][-2]['source'] = 'mmap:arbitrary-address'
        with self.assertRaises(ValueError):
            receiver.validate_inventory(data)
        data['files'][-2] = file_record('spi_controller', snapshot, 'error')
        with self.assertRaises(ValueError):
            receiver.validate_inventory(data)
        data['files'][-2] = file_record('spi_controller', b'', 'error')
        self.assertEqual(receiver.validate_inventory(data)['spi_controller']['raw'], b'')

    def test_spi_identity_result_and_failure_evidence_are_preserved(self):
        data = inventory()
        raw = b'status=error\nreason=operation_timeout\nrestoration_incomplete=1\n'
        data['files'].extend([file_record('spi_identity', b'', 'error'), file_record('spi_identity_status', raw)])
        with running_server() as (server, root):
            status, _ = request(server, '/inventory/' + CAPTURE, json_bytes(data))
            self.assertEqual(status, 200)
            target = root / 'captures' / CAPTURE / 'files/spi_identity_status.bin'
            self.assertEqual(target.read_bytes(), raw)
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
        data['files'][-2] = file_record('spi_identity', b'id1=ef40150000000000\n')
        self.assertEqual(receiver.validate_inventory(data)['spi_identity']['status'], 'ok')
        data['files'][-2]['source'] = 'native:arbitrary-command'
        with self.assertRaises(ValueError):
            receiver.validate_inventory(data)
        data['files'][-2] = file_record('spi_identity', b'id1=ef40150000000000\n', 'error')
        with self.assertRaises(ValueError):
            receiver.validate_inventory(data)

    def test_boot_media_evidence_roundtrip_and_source_restriction(self):
        raw = b'version=1\nsource=hi_ca_boot_getters\nscs_active_raw=1\nboot_select_control_raw=0\nboot_selection=chipset_pin\nboot_mode_raw=not_queried\nconfigured_medium=unknown\nactual_boot_medium=unverified\n'
        data = inventory()
        data['files'].append(file_record('boot_media', raw))
        with running_server() as (server, root):
            status, ack = request(server, '/inventory/' + CAPTURE, json_bytes(data))
            self.assertEqual(status, 200)
            target = root / 'captures' / CAPTURE / 'files/boot_media.bin'
            self.assertEqual(target.read_bytes(), raw)
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
            plan = {'version': 1, 'captureId': CAPTURE, 'inventorySha256': ack['inventorySha256'], 'partitions': []}
            proposal = root / 'plan.json'
            proposal.write_bytes(json_bytes(plan))
            proposal.chmod(0o600)
            self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET'), (200, plan))
        data['files'][-1]['source'] = 'ioctl:arbitrary'
        with self.assertRaises(ValueError):
            receiver.validate_inventory(data)
        data['files'][-1] = file_record('boot_media', raw, 'error')
        with self.assertRaises(ValueError):
            receiver.validate_inventory(data)
        data['files'][-1] = file_record('boot_media', b'', 'unreadable')
        self.assertEqual(receiver.validate_inventory(data)['boot_media']['raw'], b'')

    def test_boot_media_failure_reason_survives_metadata_upload(self):
        data = inventory()
        raw = b'status=unreadable\nreason=hi_ca_unreadable\n'
        data['files'].extend([file_record('boot_media', b'', 'unreadable'),
                              file_record('boot_media_status', raw)])
        with running_server() as (server, root):
            status, _ = request(server, '/inventory/' + CAPTURE, json_bytes(data))
            self.assertEqual(status, 200)
            target = root / 'captures' / CAPTURE / 'files/boot_media_status.bin'
            self.assertEqual(target.read_bytes(), raw)
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
        data['files'][-1]['source'] = 'builtin:other-status'
        with self.assertRaises(ValueError):
            receiver.validate_inventory(data)

    def test_inventory_and_partition_backup_verify_private_files_and_manifest(self):
        with running_server() as (server, root):
            self.assertEqual(request(server, '/ready', method='GET'), (200, {'ok': True, 'version': 1}))
            data = inventory()
            raw = json_bytes(data)
            status, ack = request(server, '/inventory/' + CAPTURE, raw)
            self.assertEqual(status, 200)
            self.assertEqual(ack['inventorySha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET'), (204, None))
            setup_capture(server, root, data)
            self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET')[0], 200)
            for index, body in enumerate([BODY[:receiver.CHUNK_SIZE], BODY[receiver.CHUNK_SIZE:]]):
                status, ack = request(server, '/chunk/' + CAPTURE + '/' + PART + '/' + str(index), body)
                self.assertEqual(status, 200)
                self.assertEqual(ack, {'ok': True, 'index': index, 'size': len(body), 'sha256': hashlib.sha256(body).hexdigest()})
            full = hashlib.sha256(BODY).hexdigest()
            status, ack = request(server, '/complete/' + CAPTURE + '/' + PART, json_bytes({'size': len(BODY), 'sha256': full}))
            self.assertEqual(status, 200)
            self.assertTrue(ack['complete'])
            status, info = request(server, '/status/' + CAPTURE, method='GET')
            self.assertEqual(status, 200)
            self.assertEqual(info['partitions'][0]['nextMissing'], 2)
            self.assertTrue(info['partitions'][0]['complete'])
            folder = root / 'captures' / CAPTURE
            self.assertEqual((folder / 'files/init_rc.bin').read_bytes(), b'import /init.bigfish.rc\n')
            manifest = json.loads((folder / 'chunks' / PART / 'manifest.json').read_bytes())
            self.assertEqual(manifest['sourceConsistency'], 'live_non_atomic')
            self.assertFalse(manifest['restoreTested'])
            self.assertFalse(manifest['mountedWritableAtInventory'])
            for path in folder.rglob('*'):
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700 if path.is_dir() else 0o600)

    def test_chunk_retries_are_idempotent_but_conflicts_and_bad_digests_fail(self):
        with running_server() as (server, root):
            setup_capture(server, root)
            path = '/chunk/' + CAPTURE + '/' + PART + '/0'
            chunk = BODY[:receiver.CHUNK_SIZE]
            self.assertEqual(request(server, path, chunk)[0], 200)
            self.assertEqual(request(server, path, chunk)[0], 200)
            self.assertEqual(request(server, path, b'c' * len(chunk))[0], 409)
            self.assertEqual(request(server, path, chunk, headers={'X-Content-SHA256': '0' * 64})[0], 400)
            self.assertEqual(request(server, path, headers={
                'Content-Length': str(len(chunk) - 1),
                'X-Content-SHA256': hashlib.sha256(chunk).hexdigest()})[0], 400)
            self.assertEqual(request(server, path[:-1] + '2', b'a' * 512)[0], 400)
            part = root / 'captures' / CAPTURE / 'chunks' / PART
            self.assertEqual(len(list(part.glob('*.bin'))), 1)
            self.assertEqual(list(part.glob('.incoming-*')), [])
            self.assertEqual(server.store.status()['reservedBytes'], 0)

    def test_completion_requires_all_chunks_and_matching_full_source_hash(self):
        with running_server() as (server, root):
            setup_capture(server, root)
            endpoint = '/complete/' + CAPTURE + '/' + PART
            valid = {'size': len(BODY), 'sha256': hashlib.sha256(BODY).hexdigest()}
            self.assertEqual(request(server, endpoint, json_bytes(valid))[0], 409)
            for index, chunk in enumerate([BODY[:receiver.CHUNK_SIZE], BODY[receiver.CHUNK_SIZE:]]):
                self.assertEqual(request(server, '/chunk/' + CAPTURE + '/' + PART + '/' + str(index), chunk)[0], 200)
            self.assertEqual(request(server, endpoint, json_bytes({**valid, 'sha256': '0' * 64}))[0], 409)
            self.assertEqual(request(server, endpoint, json_bytes({**valid, 'size': len(BODY) - 1}))[0], 400)
            self.assertEqual(request(server, endpoint, json_bytes(valid))[0], 200)
            self.assertEqual(request(server, endpoint, json_bytes(valid))[0], 200)

    def test_restart_verifies_resume_and_rejects_tampered_chunks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / 'backup'
            with running_server(root) as (server, _):
                setup_capture(server, root)
                self.assertEqual(request(server, '/chunk/' + CAPTURE + '/' + PART + '/0', BODY[:receiver.CHUNK_SIZE])[0], 200)
            with running_server(root) as (server, _):
                status, info = request(server, '/status/' + CAPTURE, method='GET')
                self.assertEqual(status, 200)
                self.assertEqual(info['partitions'][0]['nextMissing'], 1)
                self.assertEqual(info['partitions'][0]['receivedChunks'], 1)
                self.assertEqual(request(server, '/chunk/' + CAPTURE + '/' + PART + '/1', BODY[receiver.CHUNK_SIZE:])[0], 200)
                valid = {'size': len(BODY), 'sha256': hashlib.sha256(BODY).hexdigest()}
                self.assertEqual(request(server, '/complete/' + CAPTURE + '/' + PART, json_bytes(valid))[0], 200)
            with running_server(root) as (server, _):
                self.assertTrue(request(server, '/status/' + CAPTURE, method='GET')[1]['partitions'][0]['complete'])
            chunk = next((root / 'captures' / CAPTURE / 'chunks' / PART).glob('00000000-*.bin'))
            with chunk.open('r+b') as stream:
                stream.write(b'!')
            with running_server(root) as (server, _):
                self.assertEqual(request(server, '/status/' + CAPTURE, method='GET')[0], 400)

    def test_inventory_shape_sources_digests_duplicates_and_empty_failures_are_strict(self):
        base = inventory()
        for mutate in [lambda d: d.update(model='other'), lambda d: d.update(appUid=True),
                       lambda d: d['files'].append(d['files'][0]),
                       lambda d: d['files'][0].update(source='/data/private'),
                       lambda d: d['files'][0].update(sha256='0' * 64),
                       lambda d: d['files'][0].update(status='unreadable'),
                       lambda d: d['files'][0].update(data='!')]:
            data = json.loads(json_bytes(base))
            mutate(data)
            with self.subTest(data=data), self.assertRaises((ValueError, TypeError)):
                receiver.validate_inventory(data)
        with running_server() as (server, root):
            raw = json_bytes(base)
            self.assertEqual(request(server, '/inventory/' + CAPTURE, raw)[0], 200)
            self.assertEqual(request(server, '/inventory/' + CAPTURE, raw)[0], 200)
            self.assertEqual(request(server, '/inventory/' + CAPTURE, json_bytes({**base, 'diagnostics': 'changed'}))[0], 409)
            invalid = raw[:-1] + b',"version":1}'
            self.assertEqual(request(server, '/inventory/' + '34' * 16, invalid)[0], 400)
            for path in ['/inventory/../config.json', '/inventory/' + 'A' * 32, '/inventory/' + CAPTURE + '?x=1']:
                self.assertEqual(request(server, path, raw)[0], 404)

    def test_plan_requires_exact_inventory_and_rejects_rw_or_unknown_targets(self):
        with running_server() as (server, root):
            original = setup_capture(server, root)
            for update in [{'inventorySha256': '0' * 64},
                           {'partitions': [{**original['partitions'][0], 'size': 512}]},
                           {'partitions': [{**original['partitions'][0], 'id': 'mmcblk0rpmb', 'device': '/dev/block/mmcblk0rpmb'}]},
                           {'partitions': [{**original['partitions'][0], 'device': '/dev/block/../sda'}]}]:
                (root / 'plan.json').write_bytes(json_bytes({**original, **update}))
                self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET')[0], 400)
            (root / 'plan.json').write_bytes(json_bytes(original))
            self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET')[0], 200)
        for key in ('mountinfo', 'app_mountinfo'):
            data = inventory()
            data['files'] = [file_record(key, b'26 20 179:1 / /system rw - ext4 /dev/block/mmcblk0p1 rw\n') if item['id'] == key else item for item in data['files']]
            with running_server() as (server, root):
                setup_capture(server, root, data)
                self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET')[0], 400)
        data = inventory()
        data['files'] = [file_record('app_mountinfo', status='unreadable') if item['id'] == 'app_mountinfo' else item for item in data['files']]
        with running_server() as (server, root):
            setup_capture(server, root, data)
            self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET')[0], 400)

    def test_auth_host_origin_transfer_and_source_ip_are_enforced(self):
        with running_server() as (server, _):
            for headers in [{'Authorization': 'Bearer wrong'}, {'Host': 'other.invalid'}, {'Origin': 'http://other.invalid'},
                            {'Transfer-Encoding': 'chunked'}, {'Expect': '100-continue'}]:
                with self.subTest(headers=headers):
                    self.assertEqual(request(server, '/ready', method='GET', headers=headers)[0], 417 if 'Expect' in headers else 403)
            self.assertEqual(request(server, '/internal/status', method='GET', headers={'Authorization': 'wrong'})[0], 200)
        with running_server(device='127.0.0.2') as (server, _):
            self.assertEqual(request(server, '/ready', method='GET')[0], 403)

    def test_symlinks_and_nonprivate_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            destination = root / 'real'
            destination.mkdir(mode=0o700)
            link = root / 'link'
            link.symlink_to(destination, target_is_directory=True)
            with self.assertRaises(OSError):
                receiver.BackupStore(link)
        with running_server() as (server, root):
            setup_capture(server, root)
            (root / 'plan.json').chmod(0o644)
            self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET')[0], 400)
            (root / 'plan.json').chmod(0o600)
            part = root / 'captures' / CAPTURE / 'chunks'
            part.symlink_to(root, target_is_directory=True)
            self.assertEqual(request(server, '/status/' + CAPTURE, method='GET')[0], 400)

    def test_low_space_and_inflight_reservations_prevent_new_uploads(self):
        with running_server() as (server, root):
            setup_capture(server, root)
            key, _ = server.store.reserve(CAPTURE, PART, 1, 512)
            try:
                self.assertEqual(request(server, '/chunk/' + CAPTURE + '/' + PART + '/1', BODY[receiver.CHUNK_SIZE:])[0], 409)
            finally:
                server.store.release(key)
            fake = type('Space', (), {'f_bavail': 0, 'f_frsize': 4096})()
            with mock.patch.object(receiver.os, 'fstatvfs', return_value=fake):
                self.assertEqual(request(server, '/chunk/' + CAPTURE + '/' + PART + '/1', BODY[receiver.CHUNK_SIZE:])[0], 507)
            self.assertEqual(server.store.status()['reservedBytes'], 0)

    def test_backup_file_tampering_is_detected_before_resume(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / 'capture'
            with running_server(root) as (server, _):
                setup_capture(server, root)
            (root / 'captures' / CAPTURE / 'files/init_rc.bin').write_bytes(b'changed')
            with running_server(root) as (server, _):
                self.assertEqual(request(server, '/status/' + CAPTURE, method='GET')[0], 400)

    def test_one_mib_spi_inventory_files_are_verified_and_reloaded(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / 'capture'
            spi = b'\x00\xff' * (receiver.MAX_SPI // 2)
            data = {'version': 1, 'model': 'CT1105_SB', 'appUid': 10050,
                    'files': [file_record('spi_fastboot', spi), file_record('spi_bootargs', spi),
                              file_record('proc_mtd', b'dev: size erasesize name\n'),
                              file_record('mtd_map', b'mtd0|nor|fastboot|1048576\n')]}
            raw = json_bytes(data)
            self.assertGreater(len(raw), 2 * 1024 * 1024)
            with running_server(root) as (server, _):
                status, answer = request(server, '/inventory/' + CAPTURE, raw)
                self.assertEqual(status, 200)
                self.assertEqual(answer['inventorySha256'], hashlib.sha256(raw).hexdigest())
                saved = root / 'captures' / CAPTURE / 'files'
                for name in receiver.SPI_FILES:
                    self.assertEqual((saved / (name + '.bin')).read_bytes(), spi)
                    self.assertEqual(stat.S_IMODE((saved / (name + '.bin')).stat().st_mode), 0o600)
            with running_server(root) as (server, _):
                status, response = request(server, '/status/' + CAPTURE, method='GET')
                self.assertEqual(status, 200)
                self.assertEqual(response['inventorySha256'], hashlib.sha256(raw).hexdigest())
            path = root / 'captures' / CAPTURE / 'files/spi_fastboot.bin'
            with path.open('r+b') as stream:
                stream.write(b'!')
            with running_server(root) as (server, _):
                self.assertEqual(request(server, '/status/' + CAPTURE, method='GET')[0], 400)

    def test_spi_sizes_hashes_and_normal_file_limits_stay_strict(self):
        base = {'version': 1, 'model': 'CT1105_SB', 'appUid': 10050, 'files': []}
        for record in [file_record('spi_fastboot', b'a' * (receiver.MAX_SPI - 1)),
                       file_record('spi_fastboot', b'a' * (receiver.MAX_SPI + 1)),
                       file_record('spi_bootargs', b'prefix', status='truncated'),
                       file_record('init_rc', b'a' * (receiver.MAX_FILE + 1)),
                       {**file_record('spi_fastboot', b'a' * receiver.MAX_SPI), 'sha256': '0' * 64}]:
            with self.subTest(name=record['id'], status=record['status']), self.assertRaises(ValueError):
                receiver.validate_inventory({**base, 'files': [record]})
        for status in receiver.STATUSES - {'ok'}:
            receiver.validate_inventory({**base, 'files': [file_record('spi_fastboot', status=status)]})
        with running_server() as (server, root):
            setup_capture(server, root)
            with self.assertRaises(receiver.RequestRejected) as error:
                server.store.reserve(CAPTURE, PART, 0, receiver.CHUNK_SIZE + 512)
            self.assertEqual(error.exception.status, 400)
            self.assertEqual(receiver.CHUNK_SIZE, 1048576)

    def test_empty_plan_finishes_metadata_only_without_partition_evidence(self):
        data = {'version': 1, 'model': 'CT1105_SB', 'appUid': 10050,
                'files': [file_record('init_env', b'export PATH /system/bin\n'),
                          file_record('spi_fastboot', status='absent'), file_record('spi_bootargs', status='absent')]}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / 'capture'
            with running_server(root) as (server, _):
                raw = json_bytes(data)
                status, answer = request(server, '/inventory/' + CAPTURE, raw)
                self.assertEqual(status, 200)
                plan = {'version': 1, 'captureId': CAPTURE, 'inventorySha256': answer['inventorySha256'], 'partitions': []}
                wrong = {**plan, 'inventorySha256': '0' * 64}
                path = root / 'plan.json'
                path.write_bytes(json_bytes(wrong))
                path.chmod(0o600)
                self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET')[0], 400)
                path.write_bytes(json_bytes(plan))
                self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET'), (200, plan))
                self.assertEqual(request(server, '/status/' + CAPTURE, method='GET')[1]['partitions'], [])
                self.assertEqual(request(server, '/chunk/' + CAPTURE + '/' + PART + '/0', b'a' * 512)[0], 404)
            with running_server(root) as (server, _):
                self.assertEqual(request(server, '/plan/' + CAPTURE, method='GET'), (200, plan))
                self.assertEqual(request(server, '/status/' + CAPTURE, method='GET')[1]['partitions'], [])

    def test_v113_boot_storage_inventory_keeps_fixed_sources_and_general_limit(self):
        sources = {
            'proc_devices': '/proc/devices', 'proc_modules': '/proc/modules',
            'proc_iomem': '/proc/iomem', 'kernel_config': '/proc/config.gz',
            'kernel_log': 'builtin:dmesg', 'flash_sysfs': 'sysfs:flash-platform',
            'mmc_info': 'sysfs:mmc-info',
        }
        data = {'version': 1, 'model': 'CT1105_SB', 'appUid': 10050, 'files': []}
        for name, source in sources.items():
            self.assertEqual(receiver.FILES[name], source)
            self.assertEqual(receiver.file_limit(name), 65536)
            raw = b'\x1f\x8b\x00\xff' * 16384 if name == 'kernel_config' else b'fixed-device-metadata\n'
            data['files'].append(file_record(name, raw))
            with self.subTest(name=name):
                wrong = file_record(name, b'example')
                wrong['source'] = '/proc/other'
                with self.assertRaises(ValueError):
                    receiver.validate_inventory({**data, 'files': [wrong]})
                with self.assertRaises(ValueError):
                    receiver.validate_inventory({**data, 'files': [file_record(name, b'x' * 65537)]})
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / 'capture'
            raw_inventory = json_bytes(data)
            with running_server(root) as (server, _):
                status, response = request(server, '/inventory/' + CAPTURE, raw_inventory)
                self.assertEqual(status, 200)
                self.assertEqual(response['inventorySha256'], hashlib.sha256(raw_inventory).hexdigest())
                for item in data['files']:
                    saved = root / 'captures' / CAPTURE / 'files' / (item['id'] + '.bin')
                    self.assertEqual(saved.read_bytes(), base64.b64decode(item['data']))
                    self.assertEqual(stat.S_IMODE(saved.stat().st_mode), 0o600)
            with running_server(root) as (server, _):
                status, response = request(server, '/status/' + CAPTURE, method='GET')
                self.assertEqual(status, 200)
                self.assertEqual(response['inventorySha256'], hashlib.sha256(raw_inventory).hexdigest())


if __name__ == '__main__':
    unittest.main()
