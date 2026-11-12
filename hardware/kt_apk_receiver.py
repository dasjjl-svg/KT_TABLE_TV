import argparse
import datetime
import hashlib
import ipaddress
import json
import os
import re
import secrets
import signal
import socket
import stat
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
RUNTIME = ROOT / 'runtime' / 'kt-apk'
MAX_APK = 96 * 1024 * 1024
MAX_TOTAL = 192 * 1024 * 1024
MAX_COLLECTION = 32768
LIBRARIES = {'elf-0': 'libsysmanagerservice_jni.so', 'elf-1': 'libhisysmanagerclient.so',
             'elf-2': 'libhisysmanagerservice.so'}
MAX_ELF = 16 * 1024 * 1024
KINDS = frozenset('apk-' + str(index) for index in range(4)) | LIBRARIES.keys()
PACKAGE = 'com.kt.gigagenie.ollehtv'
PACKAGES = frozenset({PACKAGE, 'com.kt.gigagenie.mc', 'com.kt.gigagenie.launcher', 'com.android.settings'})
NOFOLLOW = os.O_NOFOLLOW | os.O_CLOEXEC


class RequestRejected(Exception):
    def __init__(self, status=400):
        self.status = status


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON key')
        result[key] = value
    return result


def strict_json(raw):
    def invalid_constant(_value):
        raise ValueError('non-finite JSON number')
    return json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object,
                      parse_constant=invalid_constant)


def directory_fd(path, create=False, private=False):
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts:
        raise ValueError('absolute directory required')
    descriptor = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    try:
        parts = path.parts[1:]
        for index, part in enumerate(parts):
            if create and index == len(parts) - 1:
                try:
                    os.mkdir(part, 0o700, dir_fd=descriptor)
                except FileExistsError:
                    pass
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | NOFOLLOW, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        info = os.fstat(descriptor)
        if private and (info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700):
            raise ValueError('private owned directory required')
        result, descriptor = descriptor, None
        return result
    finally:
        if descriptor is not None:
            os.close(descriptor)


def private_child(parent_fd, name):
    try:
        os.mkdir(name, 0o700, dir_fd=parent_fd)
    except FileExistsError:
        pass
    descriptor = os.open(name, os.O_RDONLY | os.O_DIRECTORY | NOFOLLOW, dir_fd=parent_fd)
    info = os.fstat(descriptor)
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        os.close(descriptor)
        raise ValueError('private owned directory required')
    return descriptor


def private_file_info(descriptor, size=None):
    info = os.fstat(descriptor)
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1
            or (size is not None and info.st_size != size)):
        raise ValueError('private owned regular file required')
    return info


def validate_config(data):
    if not isinstance(data, dict) or set(data) != {'version', 'host', 'device', 'port', 'token'}:
        raise ValueError('configuration shape')
    if type(data['version']) is not int or data['version'] != 1:
        raise ValueError('configuration version')
    for key in ('host', 'device'):
        address = ipaddress.IPv4Address(data[key])
        if str(address) != data[key] or address.is_unspecified or address.is_multicast:
            raise ValueError('unicast IPv4 address required')
    if type(data['port']) is not int or not 1 <= data['port'] <= 65535:
        raise ValueError('configuration port')
    if not isinstance(data['token'], str) or not re.fullmatch(r'[0-9a-f]{64}', data['token']):
        raise ValueError('configuration credential')
    return dict(data)


def load_config(path):
    path = Path(path)
    parent = directory_fd(path.parent)
    descriptor = None
    try:
        descriptor = os.open(path.name, os.O_RDONLY | os.O_NONBLOCK | NOFOLLOW, dir_fd=parent)
        info = private_file_info(descriptor)
        if not 1 <= info.st_size <= 4096:
            raise ValueError('configuration size')
        raw = os.read(descriptor, 4097)
        if len(raw) != info.st_size:
            raise ValueError('configuration changed')
        after = private_file_info(descriptor, len(raw))
        if after.st_mtime_ns != info.st_mtime_ns:
            raise ValueError('configuration changed')
        return validate_config(strict_json(raw))
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(parent)


def bounded_text(value, limit, lines=False, nonempty=False):
    if not isinstance(value, str) or len(value) > limit or (nonempty and not value):
        raise ValueError('invalid text')
    forbidden = r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]' if lines else r'[\x00-\x1f\x7f]'
    if re.search(forbidden, value):
        raise ValueError('invalid text')


def validate_collection(data, package=PACKAGE):
    keys = {'version', 'model', 'appUid', 'packageName', 'targetFound', 'packageVersion',
            'diagnostics', 'artifacts', 'nativeLibraryDir'}
    if not isinstance(data, dict) or set(data) != keys:
        raise ValueError('collection shape')
    if type(data['version']) is not int or data['version'] != 1 or data['model'] != 'CT1105_SB':
        raise ValueError('collection version')
    if type(data['appUid']) is not int or not 0 <= data['appUid'] <= 2147483647:
        raise ValueError('collection uid')
    if package not in PACKAGES or data['packageName'] != package or type(data['targetFound']) is not bool:
        raise ValueError('collection target')
    bounded_text(data['packageVersion'], 256)
    bounded_text(data['diagnostics'], 8192, lines=True)
    bounded_text(data['nativeLibraryDir'], 1024)
    if not isinstance(data['artifacts'], list) or len(data['artifacts']) > 4:
        raise ValueError('collection artifacts')
    if not data['targetFound'] and data['artifacts']:
        raise ValueError('collection target not found')
    seen = set()
    for item in data['artifacts']:
        if not isinstance(item, dict) or set(item) != {'kind', 'path', 'splitName', 'size', 'sha256'}:
            raise ValueError('artifact shape')
        kind = item['kind']
        if not isinstance(kind, str) or kind not in KINDS or kind in seen:
            raise ValueError('artifact kind')
        seen.add(kind)
        bounded_text(item['path'], 1024, nonempty=True)
        if not item['path'].startswith('/'):
            raise ValueError('artifact source path')
        bounded_text(item['splitName'], 128)
        if type(item['size']) is not int or not 4 <= item['size'] <= MAX_APK:
            raise ValueError('artifact size')
        if kind in LIBRARIES:
            source = Path(item['path'])
            if (package != 'com.android.settings' or source.name != LIBRARIES[kind]
                    or item['splitName'] != source.name or '..' in source.parts
                    or not item['path'].startswith(('/system/', '/vendor/'))
                    or not 52 <= item['size'] <= MAX_ELF):
                raise ValueError('library artifact')
        if not isinstance(item['sha256'], str) or not re.fullmatch(r'[0-9a-f]{64}', item['sha256']):
            raise ValueError('artifact digest')
    return data


class CaptureStore:
    def __init__(self, runtime, max_apk=MAX_APK, max_total=MAX_TOTAL):
        self.root_fd = self.artifacts_fd = self.collections_fd = None
        try:
            self.root_fd = directory_fd(runtime, create=True, private=True)
            self.artifacts_fd = private_child(self.root_fd, 'artifacts')
            self.collections_fd = private_child(self.root_fd, 'collections')
        except Exception:
            self.close()
            raise
        self.max_apk, self.max_total = max_apk, max_total
        self.lock = threading.Lock()
        self.run_id = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '-' + secrets.token_hex(4)
        self.records = {}
        self.inflight = {}
        self.received = self.reserved = self.collections = self.errors = 0
        self.latest_collection = None

    def close(self):
        for attribute in ('collections_fd', 'artifacts_fd', 'root_fd'):
            descriptor = getattr(self, attribute, None)
            if descriptor is not None:
                os.close(descriptor)
                setattr(self, attribute, None)

    def error(self):
        with self.lock:
            self.errors += 1

    def status(self):
        with self.lock:
            return {'ok': True, 'runId': self.run_id, 'artifacts': len(self.records),
                    'collections': self.collections, 'errors': self.errors,
                    'bytesReceived': self.received, 'bytesStored': sum(item['size'] for item in self.records.values()),
                    'reservedBytes': self.reserved, 'latestCollection': self.latest_collection}

    def reserve(self, kind, length):
        with self.lock:
            if kind not in KINDS or not 4 <= length <= self.max_apk:
                raise RequestRejected(413)
            if kind in self.inflight:
                raise RequestRejected(409)
            occupied = self.records.keys() | self.inflight.keys()
            if kind not in occupied and len(occupied) >= 4:
                raise RequestRejected(413)
            if self.received + self.reserved + length > self.max_total:
                raise RequestRejected(413)
            self.inflight[kind] = length
            self.reserved += length

    def consumed(self, kind, size):
        with self.lock:
            if size > self.inflight[kind]:
                raise ValueError('reservation exceeded')
            self.inflight[kind] -= size
            self.reserved -= size
            self.received += size

    def release(self, kind):
        with self.lock:
            self.reserved -= self.inflight.pop(kind, 0)

    def temporary(self, parent_fd):
        name = '.incoming-' + secrets.token_hex(16)
        descriptor = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW, 0o600, dir_fd=parent_fd)
        return name, descriptor

    def verify_file(self, name, size, checksum):
        descriptor = os.open(name, os.O_RDONLY | os.O_NONBLOCK | NOFOLLOW, dir_fd=self.artifacts_fd)
        try:
            before = private_file_info(descriptor, size)
            digest = hashlib.sha256()
            remaining = size
            while remaining:
                chunk = os.read(descriptor, min(65536, remaining))
                if not chunk:
                    raise ValueError('artifact changed')
                digest.update(chunk)
                remaining -= len(chunk)
            after = private_file_info(descriptor, size)
            if after.st_mtime_ns != before.st_mtime_ns or digest.hexdigest() != checksum:
                raise ValueError('artifact digest mismatch')
        finally:
            os.close(descriptor)

    def commit_artifact(self, kind, temporary, size, checksum):
        name = kind + '-' + checksum + ('.so' if kind in LIBRARIES else '.apk')
        with self.lock:
            existing = self.records.get(kind)
            if existing is not None:
                if existing['size'] != size or existing['sha256'] != checksum:
                    raise RequestRejected(409)
                self.verify_file(existing['name'], size, checksum)
                return {'ok': True, 'size': size, 'sha256': checksum}
            try:
                os.link(temporary, name, src_dir_fd=self.artifacts_fd, dst_dir_fd=self.artifacts_fd,
                        follow_symlinks=False)
            except FileExistsError:
                self.verify_file(name, size, checksum)
            else:
                os.unlink(temporary, dir_fd=self.artifacts_fd)
                os.fsync(self.artifacts_fd)
            self.records[kind] = {'name': name, 'size': size, 'sha256': checksum}
            return {'ok': True, 'size': size, 'sha256': checksum}

    def save_collection(self, data):
        with self.lock:
            for artifact in data['artifacts']:
                record = self.records.get(artifact['kind'])
                if record is None or record['size'] != artifact['size'] or record['sha256'] != artifact['sha256']:
                    raise ValueError('artifact not received in current run')
                self.verify_file(record['name'], record['size'], record['sha256'])
            envelope = {'received_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                        'run_id': self.run_id, 'result': data}
            raw = json.dumps(envelope, ensure_ascii=False, indent=2, allow_nan=False).encode('utf-8')
            name = self.run_id + '-' + str(self.collections + 1).zfill(4) + '.json'
            temporary, descriptor = self.temporary(self.collections_fd)
            try:
                with os.fdopen(descriptor, 'wb') as stream:
                    stream.write(raw)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.link(temporary, name, src_dir_fd=self.collections_fd, dst_dir_fd=self.collections_fd,
                        follow_symlinks=False)
                os.unlink(temporary, dir_fd=self.collections_fd)
                os.fsync(self.collections_fd)
                self.collections += 1
                self.latest_collection = name
            finally:
                try:
                    os.unlink(temporary, dir_fd=self.collections_fd)
                except FileNotFoundError:
                    pass


class ReceiverHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'KTAPKReceiver'
    sys_version = ''

    def setup(self):
        super().setup()
        self.connection.settimeout(self.server.read_timeout)
        self.deadline = time.monotonic() + self.server.request_timeout
        self.watchdog = threading.Timer(self.server.request_timeout, self.abort)
        self.watchdog.daemon = True
        self.watchdog.start()

    def abort(self):
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.connection.close()

    def finish(self):
        self.watchdog.cancel()
        super().finish()

    def log_message(self, *_args):
        pass

    def reply(self, status, data):
        self.close_connection = True
        raw = json.dumps(data, separators=(',', ':'), allow_nan=False).encode('utf-8')
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(raw)
        except OSError:
            pass

    def reject(self, status):
        self.server.store.error()
        self.reply(status, {'ok': False})

    def send_error(self, code, message=None, explain=None):
        self.reject(code)

    def handle_expect_100(self):
        self.reject(417)
        return False

    def single_header(self, name):
        values = self.headers.get_all(name, [])
        return values[0] if len(values) == 1 else None

    def trusted_request(self, address):
        return (self.client_address[0] == address
                and self.single_header('Host') == self.server.authority
                and self.headers.get_all('Origin') is None
                and self.headers.get_all('Transfer-Encoding') is None
                and self.headers.get_all('Expect') is None)

    def do_GET(self):
        if not self.trusted_request(self.server.config['host']) or self.path != '/internal/status':
            return self.reject(403)
        lengths = self.headers.get_all('Content-Length', [])
        if lengths and lengths != ['0']:
            return self.reject(400)
        self.reply(200, self.server.store.status())

    def body_length(self, expected_type, maximum):
        value = self.single_header('Content-Length')
        if value is None or not re.fullmatch(r'[0-9]{1,10}', value):
            raise RequestRejected(400)
        length = int(value)
        if not 0 < length <= maximum:
            raise RequestRejected(413)
        content_type = self.single_header('Content-Type')
        if content_type is None or content_type.split(';', 1)[0].strip().lower() != expected_type:
            raise RequestRejected(415)
        return length

    def read_chunk(self, remaining):
        available = self.deadline - time.monotonic()
        if available <= 0 or self.server.stopping.is_set():
            raise RequestRejected(408)
        self.connection.settimeout(min(self.server.read_timeout, available))
        chunk = self.rfile.read1(min(65536, remaining))
        if not chunk:
            raise RequestRejected(400)
        return chunk

    def do_POST(self):
        expected_auth = 'Bearer ' + self.server.config['token']
        authorization = self.single_header('Authorization')
        if (not self.trusted_request(self.server.config['device']) or authorization is None
                or not authorization.isascii() or not secrets.compare_digest(authorization, expected_auth)):
            return self.reject(403)
        try:
            match = re.fullmatch(r'/artifact/(apk-[0-3]|elf-[012])', self.path)
            if match:
                if match[1] in LIBRARIES and self.server.package != 'com.android.settings':
                    raise RequestRejected(404)
                limit = min(self.server.store.max_apk, MAX_ELF) if match[1] in LIBRARIES else self.server.store.max_apk
                length = self.body_length('application/octet-stream', limit)
                return self.receive_artifact(match[1], length)
            if self.path != '/collection':
                raise RequestRejected(404)
            length = self.body_length('application/json', MAX_COLLECTION)
            chunks, remaining = [], length
            while remaining:
                chunk = self.read_chunk(remaining)
                chunks.append(chunk)
                remaining -= len(chunk)
            raw = b''.join(chunks)
            if self.server.config['token'].encode('ascii') in raw:
                raise ValueError('credential in collection')
            data = validate_collection(strict_json(raw), self.server.package)
            self.server.store.save_collection(data)
            self.reply(200, {'ok': True})
        except RequestRejected as error:
            self.reject(error.status)
        except (TimeoutError, socket.timeout):
            self.reject(408)
        except (ValueError, TypeError, UnicodeError, OSError):
            self.reject(400)

    def receive_artifact(self, kind, length):
        store = self.server.store
        store.reserve(kind, length)
        temporary = None
        try:
            temporary, descriptor = store.temporary(store.artifacts_fd)
            digest, received, prefix = hashlib.sha256(), 0, b''
            with os.fdopen(descriptor, 'wb') as stream:
                while received < length:
                    chunk = self.read_chunk(length - received)
                    store.consumed(kind, len(chunk))
                    received += len(chunk)
                    needed = 20 if kind in LIBRARIES else 4
                    if len(prefix) < needed:
                        prefix += chunk[:needed - len(prefix)]
                        if len(prefix) == needed:
                            valid = (prefix[:7] == b'\x7fELF\x01\x01\x01'
                                     and prefix[16:20] == b'\x03\x00\x28\x00') if kind in LIBRARIES else prefix == b'PK\x03\x04'
                            if not valid:
                                raise RequestRejected(400)
                    digest.update(chunk)
                    stream.write(chunk)
                stream.flush()
                os.fsync(stream.fileno())
            if kind in LIBRARIES and (received < 52 or len(prefix) != 20):
                raise RequestRejected(400)
            if time.monotonic() >= self.deadline or self.server.stopping.is_set():
                raise RequestRejected(408)
            acknowledgement = store.commit_artifact(kind, temporary, received, digest.hexdigest())
            self.reply(200, acknowledgement)
        finally:
            store.release(kind)
            if temporary is not None:
                try:
                    os.unlink(temporary, dir_fd=store.artifacts_fd)
                except FileNotFoundError:
                    pass


class ReceiverServer(ThreadingHTTPServer):
    daemon_threads = False
    block_on_close = True
    allow_reuse_address = True
    request_queue_size = 8

    def __init__(self, config, runtime=RUNTIME, max_apk=MAX_APK, max_total=MAX_TOTAL,
                 read_timeout=5, request_timeout=150, package=PACKAGE):
        if package not in PACKAGES:
            raise ValueError('unsupported package')
        self.package = package
        self.config = validate_config(config)
        self.authority = config['host'] + ':' + str(config['port'])
        self.read_timeout, self.request_timeout = read_timeout, request_timeout
        self.store = CaptureStore(runtime, max_apk, max_total)
        self.stopping = threading.Event()
        self.workers = threading.BoundedSemaphore(4)
        self.connections = set()
        self.connection_lock = threading.Lock()
        try:
            super().__init__((config['host'], config['port']), ReceiverHandler)
        except Exception:
            self.store.close()
            raise

    def process_request(self, request, client_address):
        if self.stopping.is_set() or not self.workers.acquire(blocking=False):
            request.close()
            return
        with self.connection_lock:
            self.connections.add(request)
        try:
            super().process_request(request, client_address)
        except Exception:
            with self.connection_lock:
                self.connections.discard(request)
            self.workers.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            with self.connection_lock:
                self.connections.discard(request)
            self.workers.release()

    def handle_error(self, _request, _client_address):
        self.store.error()

    def stop(self):
        self.stopping.set()
        with self.connection_lock:
            for connection in self.connections:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connection.close()
        self.shutdown()

    def server_close(self):
        super().server_close()
        self.store.close()


def runtime_path(value):
    path = Path(value).absolute()
    try:
        relative = path.relative_to(ROOT / 'runtime')
    except ValueError:
        raise argparse.ArgumentTypeError('runtime must be a dedicated directory inside the project runtime')
    if not relative.parts or '..' in path.parts:
        raise argparse.ArgumentTypeError('runtime must be a dedicated directory inside the project runtime')
    return path


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path)
    parser.add_argument('--runtime', type=runtime_path, default=RUNTIME)
    parser.add_argument('--package', choices=sorted(PACKAGES), default=PACKAGE)
    parser.add_argument('--lifetime', type=int, default=1200)
    args = parser.parse_args(argv)
    if not 1 <= args.lifetime <= 3600:
        parser.error('lifetime must be between 1 and 3600 seconds')
    os.umask(0o077)
    try:
        server = ReceiverServer(load_config(args.config or args.runtime / 'config.json'),
                                runtime=args.runtime, package=args.package)
    except (OSError, ValueError, TypeError):
        print('KT APK receiver could not start; check the private configuration and directories.', file=sys.stderr)
        return 1
    timer = threading.Timer(args.lifetime, server.stop)
    timer.daemon = True
    timer.start()
    for signum in (signal.SIGTERM, signal.SIGINT):
        signal.signal(signum, lambda *_: threading.Thread(target=server.stop, daemon=True).start())
    print('KT APK receiver ready.', flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        timer.cancel()
        server.server_close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
