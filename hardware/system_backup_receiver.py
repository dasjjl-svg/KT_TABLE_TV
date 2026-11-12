import argparse
import base64
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import stat
import sys
import threading
import time
from http.server import ThreadingHTTPServer


_SPEC = importlib.util.spec_from_file_location('_panel_backup_transport', Path(__file__).with_name('kt_apk_receiver.py'))
_BASE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_BASE)
ROOT = Path(__file__).resolve().parent.parent
RUNTIME = ROOT / 'runtime' / 'system-backup'
NOFOLLOW = _BASE.NOFOLLOW
RequestRejected = _BASE.RequestRejected
MAX_INVENTORY = 8 * 1024 * 1024
MAX_FILE = 65536
MAX_SPI = 1048576
MAX_SPI_MAIN = 4 * 1048576
MAX_SPI_SAMPLES = 2048
CHUNK_SIZE = 1048576
MAX_PARTITION = 32 * 1024 * 1024 * 1024
MAX_TOTAL = 64 * 1024 * 1024 * 1024
MIN_FREE = 512 * 1024 * 1024
CAPTURE = r'[0-9a-f]{32}'
PARTITION = r'mmcblk0(?:p[1-9][0-9]{0,2}|boot[01])'
DIGEST = r'[0-9a-f]{64}'
FILES = {
    'pipe_test': 'builtin:pipe-test', 'root_status': '/proc/self/status',
    'partitions': '/proc/partitions', 'mounts': '/proc/mounts',
    'mountinfo': '/proc/self/mountinfo', 'app_mountinfo': '/proc/self/mountinfo',
    'cmdline': '/proc/cmdline', 'cpuinfo': '/proc/cpuinfo', 'init_rc': '/init.rc',
    'init_board': '/init.bigfish.rc', 'init_usb': '/init.usb.rc',
    'fstab_board': '/fstab.bigfish', 'build_prop': '/system/build.prop',
    'default_prop': '/default.prop', 'block_map': 'sysfs:mmcblk0',
    'partition_header': '/dev/block/mmcblk0@0+17408',
    'proc_mtd': '/proc/mtd', 'mtd_map': 'sysfs:mtd', 'init_env': '/init.environ.rc',
    'init_zygote': '/init.zygote32.rc', 'init_trace': '/init.trace.rc',
    'init_hidolphin': '/init.hidolphin.rc', 'spi_fastboot': 'mtd:nor:fastboot',
    'spi_bootargs': 'mtd:nor:bootargs',
    'proc_devices': '/proc/devices', 'proc_modules': '/proc/modules',
    'proc_iomem': '/proc/iomem', 'kernel_config': '/proc/config.gz',
    'kernel_log': 'builtin:dmesg', 'flash_sysfs': 'sysfs:flash-platform',
    'mmc_info': 'sysfs:mmc-info',
    'boot_media': 'ioctl:hi_ca-boot',
    'boot_media_status': 'builtin:boot-media-status',
    'spi_controller': 'mmap:spi-controller-state',
    'spi_controller_status': 'builtin:spi-controller-status',
    'spi_identity': 'native:spi-nor-identity',
    'spi_identity_status': 'builtin:spi-identity-status',
    'spi_nor_main': 'native:spi-nor-main-array',
    'spi_nor_main_status': 'builtin:spi-nor-main-status',
    'spi_read_samples': 'native:spi-read-samples',
    'spi_read_samples_status': 'builtin:spi-read-samples-status',
}
SPI_FILES = frozenset({'spi_fastboot', 'spi_bootargs'})
STATUSES = {'ok', 'absent', 'unreadable', 'truncated', 'error'}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def encoded(data):
    return json.dumps(data, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode('utf-8')


def file_limit(name):
    if name == 'spi_nor_main':
        return MAX_SPI_MAIN
    if name == 'spi_read_samples':
        return MAX_SPI_SAMPLES
    return MAX_SPI if name in SPI_FILES else MAX_FILE


def validate_inventory(data):
    required = {'version', 'model', 'appUid', 'files'}
    if not isinstance(data, dict) or not required <= data.keys() or data.keys() - required - {'diagnostics'}:
        raise ValueError('inventory shape')
    if type(data['version']) is not int or data['version'] != 1 or data['model'] != 'CT1105_SB':
        raise ValueError('inventory device')
    if type(data['appUid']) is not int or not 0 <= data['appUid'] <= 2147483647:
        raise ValueError('inventory uid')
    _BASE.bounded_text(data.get('diagnostics', ''), 4096, lines=True)
    if not isinstance(data['files'], list) or len(data['files']) > len(FILES):
        raise ValueError('inventory files')
    decoded = {}
    for item in data['files']:
        if not isinstance(item, dict) or set(item) != {'id', 'source', 'status', 'data', 'sha256'}:
            raise ValueError('inventory file shape')
        name = item['id']
        if not isinstance(name, str) or name not in FILES or name in decoded or item['source'] != FILES[name]:
            raise ValueError('inventory file identity')
        limit = file_limit(name)
        if (item['status'] not in STATUSES or not isinstance(item['data'], str)
                or len(item['data']) > ((limit + 2) // 3) * 4):
            raise ValueError('inventory file state')
        raw = base64.b64decode(item['data'], validate=True)
        if len(raw) > limit or base64.b64encode(raw).decode('ascii') != item['data']:
            raise ValueError('inventory file encoding')
        if not isinstance(item['sha256'], str) or not re.fullmatch(DIGEST, item['sha256']) or digest(raw) != item['sha256']:
            raise ValueError('inventory file digest')
        if item['status'] in {'absent', 'unreadable', 'error'} and raw:
            raise ValueError('failed file contains data')
        if (name in SPI_FILES or name in {'spi_nor_main', 'spi_read_samples'}) and (len(raw) != limit if item['status'] == 'ok' else bool(raw)):
            raise ValueError('SPI backup must be complete or empty')
        decoded[name] = {'status': item['status'], 'raw': raw}
    return decoded


def read_private(parent, name, maximum):
    descriptor = os.open(name, os.O_RDONLY | os.O_NONBLOCK | NOFOLLOW, dir_fd=parent)
    try:
        before = _BASE.private_file_info(descriptor)
        if before.st_size > maximum:
            raise ValueError('private file too large')
        chunks, remaining = [], before.st_size
        while remaining:
            raw = os.read(descriptor, min(65536, remaining))
            if not raw:
                raise ValueError('private file shortened')
            chunks.append(raw)
            remaining -= len(raw)
        after = _BASE.private_file_info(descriptor, before.st_size)
        if before.st_mtime_ns != after.st_mtime_ns or os.read(descriptor, 1):
            raise ValueError('private file changed')
        return b''.join(chunks)
    finally:
        os.close(descriptor)


def temporary(parent):
    name = '.incoming-' + secrets.token_hex(16)
    descriptor = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW, 0o600, dir_fd=parent)
    return name, descriptor


def save_once(parent, name, raw):
    temp, descriptor = temporary(parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.link(temp, name, src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
        except FileExistsError:
            if read_private(parent, name, len(raw)) != raw:
                raise RequestRejected(409)
        else:
            os.fsync(parent)
    finally:
        os.unlink(temp, dir_fd=parent)


def parse_block_map(raw):
    result = {}
    text = raw.decode('ascii')
    for line in text.splitlines():
        if not line:
            continue
        fields = line.split('|')
        if len(fields) != 5:
            raise ValueError('block map format')
        name, device, sectors, start, readonly = fields
        if (not re.fullmatch(r'mmcblk0(?:p[1-9][0-9]{0,2}|boot[01]|rpmb)?', name)
                or not re.fullmatch(r'[0-9]{1,5}:[0-9]{1,5}', device)
                or not re.fullmatch(r'[1-9][0-9]{0,11}', sectors)
                or not (start == '-1' or re.fullmatch(r'[0-9]{1,12}', start))
                or readonly not in ('0', '1')
                or name in result):
            raise ValueError('block map value')
        result[name] = {'device': device, 'size': int(sectors) * 512}
    if not result:
        raise ValueError('empty block map')
    return result


def mounted_writable(raw):
    result = set()
    text = raw.decode('ascii')
    lines = text.splitlines()
    if not lines:
        raise ValueError('empty mount information')
    for line in lines:
        fields = line.split()
        if len(fields) < 10 or '-' not in fields[6:]:
            raise ValueError('mount information format')
        separator = fields.index('-', 6)
        if len(fields) != separator + 4 or not re.fullmatch(r'[0-9]+:[0-9]+', fields[2]):
            raise ValueError('mount information fields')
        flags = set(fields[5].split(',')) | set(fields[separator + 3].split(','))
        if 'rw' in flags:
            result.add(fields[2])
    return result


def validate_plan(data, capture_id, inventory_sha, files):
    if (not isinstance(data, dict) or set(data) != {'version', 'captureId', 'inventorySha256', 'partitions'}
            or type(data['version']) is not int or data['version'] != 1
            or data['captureId'] != capture_id or data['inventorySha256'] != inventory_sha):
        raise ValueError('plan identity')
    if not isinstance(data['partitions'], list) or len(data['partitions']) > 128:
        raise ValueError('plan partitions')
    if not data['partitions']:
        return data
    for key in ('block_map', 'mountinfo', 'app_mountinfo'):
        if key not in files or files[key]['status'] != 'ok':
            raise ValueError('incomplete partition evidence')
    devices = parse_block_map(files['block_map']['raw'])
    writable = mounted_writable(files['mountinfo']['raw']) | mounted_writable(files['app_mountinfo']['raw'])
    seen, total = set(), 0
    for part in data['partitions']:
        if not isinstance(part, dict) or set(part) != {'id', 'device', 'size', 'chunkSize'}:
            raise ValueError('plan partition shape')
        name = part['id']
        if (not isinstance(name, str) or not re.fullmatch(PARTITION, name) or name in seen
                or part['device'] != '/dev/block/' + name):
            raise ValueError('plan partition identity')
        if (type(part['size']) is not int or not 512 <= part['size'] <= MAX_PARTITION or part['size'] % 512
                or type(part['chunkSize']) is not int or part['chunkSize'] != CHUNK_SIZE):
            raise ValueError('plan partition size')
        source = devices.get(name)
        if source is None or source['size'] != part['size'] or source['device'] in writable:
            raise ValueError('partition evidence mismatch or writable mount')
        seen.add(name)
        total += part['size']
    if total > MAX_TOTAL:
        raise ValueError('plan total size')
    return data


class BackupStore:
    def __init__(self, runtime, min_free=MIN_FREE):
        self.root_fd = self.captures_fd = None
        try:
            self.root_fd = _BASE.directory_fd(runtime, create=True, private=True)
            self.captures_fd = _BASE.private_child(self.root_fd, 'captures')
        except Exception:
            self.close()
            raise
        self.lock = threading.RLock()
        self.cache = {}
        self.inflight = {}
        self.errors = self.received = 0
        self.min_free = min_free
        self.last_capture = None

    def close(self):
        for capture in getattr(self, 'cache', {}).values():
            for part in capture.get('parts', {}).values():
                os.close(part['fd'])
            os.close(capture['fd'])
        self.cache = {}
        for key in ('captures_fd', 'root_fd'):
            value = getattr(self, key, None)
            if value is not None:
                os.close(value)
                setattr(self, key, None)

    def error(self):
        with self.lock:
            self.errors += 1

    def free_space(self, needed):
        info = os.fstatvfs(self.root_fd)
        reserved = sum(self.inflight.values())
        if info.f_bavail * info.f_frsize < needed + reserved + self.min_free:
            raise RequestRejected(507)

    def status(self):
        with self.lock:
            return {'ok': True, 'captures': len(self.cache), 'latestCapture': self.last_capture,
                    'errors': self.errors, 'bytesReceived': self.received,
                    'reservedBytes': sum(self.inflight.values())}

    def capture(self, capture_id):
        if not re.fullmatch(CAPTURE, capture_id):
            raise RequestRejected(400)
        if capture_id in self.cache:
            return self.cache[capture_id]
        descriptor = os.open(capture_id, os.O_RDONLY | os.O_DIRECTORY | NOFOLLOW, dir_fd=self.captures_fd)
        try:
            info = os.fstat(descriptor)
            if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise ValueError('capture directory permissions')
            raw = read_private(descriptor, 'inventory.json', MAX_INVENTORY)
            data = _BASE.strict_json(raw)
            files = validate_inventory(data)
            files_fd = os.open('files', os.O_RDONLY | os.O_DIRECTORY | NOFOLLOW, dir_fd=descriptor)
            try:
                for name, item in files.items():
                    if read_private(files_fd, name + '.bin', file_limit(name)) != item['raw']:
                        raise ValueError('inventory backup file changed')
            finally:
                os.close(files_fd)
            capture = {'fd': descriptor, 'inventorySha256': digest(raw), 'files': files, 'parts': {}, 'plan': None}
            self.cache[capture_id] = capture
            descriptor = None
            return capture
        finally:
            if descriptor is not None:
                os.close(descriptor)

    def save_inventory(self, capture_id, raw):
        if not re.fullmatch(CAPTURE, capture_id) or len(raw) > MAX_INVENTORY:
            raise ValueError('inventory identity')
        data = _BASE.strict_json(raw)
        files = validate_inventory(data)
        with self.lock:
            self.free_space(len(raw) + sum(len(item['raw']) for item in files.values()))
            if len(os.listdir(self.captures_fd)) >= 32 and capture_id not in os.listdir(self.captures_fd):
                raise RequestRejected(413)
            parent = _BASE.private_child(self.captures_fd, capture_id)
            files_fd = None
            try:
                try:
                    previous = read_private(parent, 'inventory.json', MAX_INVENTORY)
                except FileNotFoundError:
                    previous = None
                if previous is not None and previous != raw:
                    raise RequestRejected(409)
                files_fd = _BASE.private_child(parent, 'files')
                for name, item in files.items():
                    save_once(files_fd, name + '.bin', item['raw'])
                save_once(parent, 'inventory.json', raw)
            finally:
                if files_fd is not None:
                    os.close(files_fd)
                os.close(parent)
            capture = self.capture(capture_id)
            self.last_capture = capture_id
            return {'ok': True, 'captureId': capture_id, 'inventorySha256': capture['inventorySha256']}

    def plan(self, capture_id):
        with self.lock:
            capture = self.capture(capture_id)
            if capture['plan'] is not None:
                return capture['plan']
            try:
                raw = read_private(capture['fd'], 'plan.json', 65536)
            except FileNotFoundError:
                try:
                    raw = read_private(self.root_fd, 'plan.json', 65536)
                except FileNotFoundError:
                    return None
                proposal = _BASE.strict_json(raw)
                if isinstance(proposal, dict) and proposal.get('captureId') != capture_id:
                    return None
            data = validate_plan(_BASE.strict_json(raw), capture_id, capture['inventorySha256'], capture['files'])
            save_once(capture['fd'], 'plan.json', encoded(data))
            capture['plan'] = data
            return data

    def partition(self, capture_id, name):
        capture = self.capture(capture_id)
        plan = self.plan(capture_id)
        if plan is None:
            raise RequestRejected(409)
        spec = next((part for part in plan['partitions'] if part['id'] == name), None)
        if spec is None:
            raise RequestRejected(404)
        if name not in capture['parts']:
            parent = _BASE.private_child(capture['fd'], 'chunks')
            try:
                descriptor = _BASE.private_child(parent, name)
            finally:
                os.close(parent)
            part = {'fd': descriptor, 'spec': spec, 'chunks': {}, 'complete': None}
            try:
                for filename in os.listdir(descriptor):
                    match = re.fullmatch(r'([0-9]{8})-(' + DIGEST + r')\.bin', filename)
                    if match is None:
                        if filename.startswith('.incoming-') or filename == 'manifest.json':
                            continue
                        raise ValueError('unexpected backup file')
                    index = int(match[1])
                    length = self.chunk_length(spec, index)
                    self.verify_chunk(descriptor, filename, length, match[2])
                    if index in part['chunks']:
                        raise ValueError('duplicate backup chunk')
                    part['chunks'][index] = {'name': filename, 'size': length, 'sha256': match[2]}
                try:
                    manifest = _BASE.strict_json(read_private(descriptor, 'manifest.json', 4096))
                except FileNotFoundError:
                    pass
                else:
                    if (not isinstance(manifest, dict) or manifest.get('version') != 1
                            or manifest.get('captureId') != capture_id or manifest.get('id') != name
                            or manifest.get('inventorySha256') != capture['inventorySha256']
                            or manifest.get('device') != spec['device'] or manifest.get('chunkSize') != CHUNK_SIZE
                            or manifest.get('transportVerified') is not True or manifest.get('restoreTested') is not False
                            or manifest.get('sourceConsistency') != 'live_non_atomic'
                            or manifest.get('size') != spec['size'] or not re.fullmatch(DIGEST, manifest.get('sha256', ''))
                            or self.partition_digest(part) != manifest['sha256']):
                        raise ValueError('backup manifest mismatch')
                    part['complete'] = manifest
                capture['parts'][name] = part
            except Exception:
                os.close(descriptor)
                raise
        return capture['parts'][name]

    @staticmethod
    def chunk_length(spec, index):
        if type(index) is not int or not 0 <= index < (spec['size'] + CHUNK_SIZE - 1) // CHUNK_SIZE:
            raise RequestRejected(400)
        return min(CHUNK_SIZE, spec['size'] - index * CHUNK_SIZE)

    @staticmethod
    def verify_chunk(parent, name, length, checksum, combined=None):
        descriptor = os.open(name, os.O_RDONLY | os.O_NONBLOCK | NOFOLLOW, dir_fd=parent)
        try:
            before = _BASE.private_file_info(descriptor, length)
            hashed, remaining = hashlib.sha256(), length
            while remaining:
                raw = os.read(descriptor, min(65536, remaining))
                if not raw:
                    raise ValueError('backup chunk shortened')
                remaining -= len(raw)
                hashed.update(raw)
                if combined is not None:
                    combined.update(raw)
            after = _BASE.private_file_info(descriptor, length)
            if before.st_mtime_ns != after.st_mtime_ns or hashed.hexdigest() != checksum:
                raise ValueError('backup chunk digest mismatch')
        finally:
            os.close(descriptor)

    def partition_digest(self, part):
        spec = part['spec']
        count = (spec['size'] + CHUNK_SIZE - 1) // CHUNK_SIZE
        if len(part['chunks']) != count:
            raise RequestRejected(409)
        combined = hashlib.sha256()
        for index in range(count):
            chunk = part['chunks'][index]
            self.verify_chunk(part['fd'], chunk['name'], chunk['size'], chunk['sha256'], combined)
        return combined.hexdigest()

    def reserve(self, capture_id, name, index, length):
        with self.lock:
            part = self.partition(capture_id, name)
            if length != self.chunk_length(part['spec'], index):
                raise RequestRejected(400)
            key = capture_id, name, index
            if key in self.inflight:
                raise RequestRejected(409)
            self.free_space(length)
            self.inflight[key] = length
            return key, part

    def release(self, key):
        with self.lock:
            self.inflight.pop(key, None)

    def commit(self, part, index, temp, length, checksum):
        with self.lock:
            existing = part['chunks'].get(index)
            if existing is not None:
                if existing['sha256'] != checksum or existing['size'] != length:
                    raise RequestRejected(409)
                self.verify_chunk(part['fd'], existing['name'], length, checksum)
            else:
                filename = str(index).zfill(8) + '-' + checksum + '.bin'
                os.link(temp, filename, src_dir_fd=part['fd'], dst_dir_fd=part['fd'], follow_symlinks=False)
                os.unlink(temp, dir_fd=part['fd'])
                os.fsync(part['fd'])
                part['chunks'][index] = {'name': filename, 'size': length, 'sha256': checksum}
            self.received += length
            return {'ok': True, 'index': index, 'size': length, 'sha256': checksum}

    def complete(self, capture_id, name, data):
        if not isinstance(data, dict) or set(data) != {'sha256', 'size'} or not re.fullmatch(DIGEST, data.get('sha256', '')):
            raise ValueError('completion shape')
        with self.lock:
            part = self.partition(capture_id, name)
            spec = part['spec']
            if type(data['size']) is not int or data['size'] != spec['size']:
                raise ValueError('completion size')
            if any(key[:2] == (capture_id, name) for key in self.inflight):
                raise RequestRejected(409)
            checksum = self.partition_digest(part)
            if checksum != data['sha256']:
                raise RequestRejected(409)
            manifest = part['complete'] or {
                'version': 1, 'captureId': capture_id, 'id': name, 'device': spec['device'],
                'size': spec['size'], 'chunkSize': CHUNK_SIZE, 'sha256': checksum,
                'inventorySha256': self.capture(capture_id)['inventorySha256'],
                'sourceConsistency': 'live_non_atomic', 'transportVerified': True,
                'restoreTested': False, 'mountedWritableAtInventory': False,
                'completedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            }
            save_once(part['fd'], 'manifest.json', encoded(manifest))
            part['complete'] = manifest
            return {'ok': True, 'id': name, 'size': spec['size'], 'sha256': checksum, 'complete': True}

    def capture_status(self, capture_id):
        with self.lock:
            capture = self.capture(capture_id)
            plan = self.plan(capture_id)
            records = []
            for spec in plan['partitions'] if plan else []:
                part = self.partition(capture_id, spec['id'])
                count = (spec['size'] + CHUNK_SIZE - 1) // CHUNK_SIZE
                missing = next((index for index in range(count) if index not in part['chunks']), count)
                records.append({'id': spec['id'], 'size': spec['size'], 'chunkSize': CHUNK_SIZE,
                                'chunkCount': count, 'receivedChunks': len(part['chunks']), 'nextMissing': missing,
                                'complete': part['complete'] is not None,
                                'sha256': part['complete']['sha256'] if part['complete'] else None})
            return {'ok': True, 'captureId': capture_id, 'inventorySha256': capture['inventorySha256'], 'partitions': records}


class BackupHandler(_BASE.ReceiverHandler):
    server_version = 'SystemBackupReceiver'

    def authorized(self):
        value = self.single_header('Authorization')
        return (self.trusted_request(self.server.config['device']) and value is not None and value.isascii()
                and secrets.compare_digest(value, 'Bearer ' + self.server.config['token']))

    def do_GET(self):
        if self.path == '/internal/status':
            if not self.trusted_request(self.server.config['host']):
                return self.reject(403)
            return self.reply(200, self.server.store.status())
        if not self.authorized():
            return self.reject(403)
        if self.headers.get_all('Content-Length') not in (None, ['0']):
            return self.reject(400)
        if self.path == '/ready':
            return self.reply(200, {'ok': True, 'version': 1})
        try:
            match = re.fullmatch(r'/(plan|status)/(' + CAPTURE + ')', self.path)
            if match is None:
                raise RequestRejected(404)
            if match[1] == 'status':
                return self.reply(200, self.server.store.capture_status(match[2]))
            plan = self.server.store.plan(match[2])
            if plan is not None:
                return self.reply(200, plan)
            self.close_connection = True
            self.send_response(204)
            self.send_header('Content-Length', '0')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close')
            self.end_headers()
        except RequestRejected as error:
            self.reject(error.status)
        except FileNotFoundError:
            self.reject(404)
        except (ValueError, TypeError, UnicodeError, OSError):
            self.reject(400)

    def json_body(self, maximum):
        length = self.body_length('application/json', maximum)
        chunks, remaining = [], length
        while remaining:
            raw = self.read_chunk(remaining)
            chunks.append(raw)
            remaining -= len(raw)
        return b''.join(chunks)

    def do_POST(self):
        if not self.authorized():
            return self.reject(403)
        try:
            match = re.fullmatch(r'/inventory/(' + CAPTURE + ')', self.path)
            if match:
                raw = self.json_body(MAX_INVENTORY)
                if self.server.config['token'].encode('ascii') in raw:
                    raise ValueError('credential in inventory')
                return self.reply(200, self.server.store.save_inventory(match[1], raw))
            match = re.fullmatch(r'/chunk/(' + CAPTURE + ')/(' + PARTITION + r')/(0|[1-9][0-9]{0,4})', self.path)
            if match:
                length = self.body_length('application/octet-stream', CHUNK_SIZE)
                checksum = self.single_header('X-Content-SHA256')
                if checksum is None or not re.fullmatch(DIGEST, checksum):
                    raise ValueError('chunk digest header')
                return self.receive_chunk(match[1], match[2], int(match[3]), length, checksum)
            match = re.fullmatch(r'/complete/(' + CAPTURE + ')/(' + PARTITION + ')', self.path)
            if match:
                data = _BASE.strict_json(self.json_body(4096))
                return self.reply(200, self.server.store.complete(match[1], match[2], data))
            raise RequestRejected(404)
        except RequestRejected as error:
            self.reject(error.status)
        except FileNotFoundError:
            self.reject(404)
        except (TimeoutError, socket.timeout):
            self.reject(408)
        except (ValueError, TypeError, UnicodeError, OSError):
            self.reject(400)

    def receive_chunk(self, capture_id, name, index, length, checksum):
        store = self.server.store
        key, part = store.reserve(capture_id, name, index, length)
        temp = None
        try:
            temp, descriptor = temporary(part['fd'])
            hashed, remaining = hashlib.sha256(), length
            with os.fdopen(descriptor, 'wb') as stream:
                while remaining:
                    raw = self.read_chunk(remaining)
                    remaining -= len(raw)
                    hashed.update(raw)
                    stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            if hashed.hexdigest() != checksum:
                raise RequestRejected(400)
            if time.monotonic() >= self.deadline or self.server.stopping.is_set():
                raise RequestRejected(408)
            self.reply(200, store.commit(part, index, temp, length, checksum))
        finally:
            store.release(key)
            if temp is not None:
                try:
                    os.unlink(temp, dir_fd=part['fd'])
                except FileNotFoundError:
                    pass


class BackupServer(_BASE.ReceiverServer):
    def __init__(self, config, runtime=RUNTIME, read_timeout=5, request_timeout=180, min_free=MIN_FREE):
        self.config = _BASE.validate_config(config)
        self.authority = config['host'] + ':' + str(config['port'])
        self.read_timeout, self.request_timeout = read_timeout, request_timeout
        self.store = BackupStore(runtime, min_free)
        self.stopping = threading.Event()
        self.workers = threading.BoundedSemaphore(4)
        self.connections = set()
        self.connection_lock = threading.Lock()
        try:
            ThreadingHTTPServer.__init__(self, (config['host'], config['port']), BackupHandler)
        except Exception:
            self.store.close()
            raise


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=_BASE.runtime_path, default=RUNTIME)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--lifetime', type=int, default=3600)
    args = parser.parse_args(argv)
    if not 1 <= args.lifetime <= 14400:
        parser.error('lifetime must be between 1 and 14400 seconds')
    os.umask(0o077)
    try:
        server = BackupServer(_BASE.load_config(args.config or args.runtime / 'config.json'), args.runtime)
    except (ValueError, TypeError, OSError):
        print('System backup receiver could not start; check private configuration and directories.', file=sys.stderr)
        return 1
    timer = threading.Timer(args.lifetime, server.stop)
    timer.daemon = True
    timer.start()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: threading.Thread(target=server.stop, daemon=True).start())
    print('System backup receiver ready.', flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        timer.cancel()
        server.server_close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
