#!/usr/bin/env python3
import argparse
import gzip
import io
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import string
import sys
import tarfile
import zipfile


FORBIDDEN_DIRS = {
    'runtime', 'backups', 'captures', '.codex-attachments', '.git',
    'node_modules', 'build', 'generated', 'tooling', 'tool-cache',
    '__pycache__', '.venv', 'venv', '.ssh', '.aws', '.config',
}
FORBIDDEN_EXTENSIONS = {
    '.apk', '.aab', '.dex', '.odex', '.vdex', '.img', '.bin', '.dtb', '.dtbo',
    '.elf', '.so', '.o', '.a', '.class', '.pyc', '.pyo', '.pcap', '.pcapng',
    '.cap', '.jks', '.keystore', '.p12', '.pfx', '.pk8', '.p8', '.key', '.db', '.sqlite',
    '.sqlite3', '.jpg', '.jpeg', '.png', '.gif', '.webp', '.heic', '.tiff',
    '.bmp', '.ico', '.mp4', '.mov', '.mp3', '.wav', '.7z', '.rar',
}
SENSITIVE_KEY = re.compile(r'(?:token|password|passwd|secret|api.?key|user.?key|authorization|cookie|credential)', re.I)
PUBLIC_METADATA_KEYS = {'token_type', 'expires', 'expires_in', 'expiry', 'scope', 'scopes', 'ha_auth_provider', 'provider', 'type', 'version', 'created', 'created_at', 'updated_at', 'algorithm', 'format'}
IDENTITY_KEY = re.compile(r'(?:ssid|serial|mac.?address|fingerprint|signer.?sha|email|username|user.?name)$', re.I)
JWT = re.compile(rb'\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\b')
PRIVATE_KEY = re.compile(rb'-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----')
IPV4 = re.compile(rb'(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])')
MAC = re.compile(rb'(?i)(?<![a-z0-9])(?:[a-f0-9]{2}:){5}[a-f0-9]{2}(?![a-z0-9])')
TAILNET = re.compile(rb'(?i)\b[a-z0-9][a-z0-9.-]*\.ts\.net\b')
HOME_PATH = re.compile(rb'/(?:Users|home|Volumes)/([^/\s\x00\x22\x27]+)')
CREDENTIAL = re.compile(rb'(?i)[\x22\x27]?(?:access[_-]?token|refresh[_-]?token|api[_-]?key|password|passwd|client[_-]?secret|token)[\x22\x27]?\s*[:=]\s*[\x22\x27]([^\x22\x27\r\n]{12,})[\x22\x27]')
EMAIL = re.compile(rb'(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b')
PLACEHOLDER = re.compile(r'(?i)^(?:example|fixture|test|dummy|placeholder|replace|your[_ -]|changeme|sample|redacted|not[_-]?(?:a[_-]?)?real|insert|<|\$\{|process\.|os\.|getenv\()')
REFERENCE_SKIP = FORBIDDEN_DIRS | {'analysis', 'extracted-system', 'images', 'files', 'releases', 'logs', 'rom-analysis', 'bootloader-analysis', 'home-autostart-analysis'}
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in ((0x0A000000, 8), (0xAC100000, 12), (0xC0A80000, 16), (0x64400000, 10)))


class AuditError(Exception):
    pass


def placeholder(value):
    return bool(PLACEHOLDER.search(value)) or len(set(value)) <= 2 or value.lower() in {'none', 'null', 'undefined'}


def safe_member(name):
    if not isinstance(name, str) or not name or '\\' in name or '\x00' in name or any(ord(c) < 32 for c in name):
        return False
    if name.startswith('/') or re.match(r'^[a-zA-Z]:', name):
        return False
    return all(part not in {'..', '.', ''} for part in name.rstrip('/').split('/'))


def fixture_path(name):
    parts = PurePosixPath(name.replace('!', '/')).parts
    return any(part in {'test', 'tests', 'examples'} for part in parts)


def private_values(reference=None, patterns=None, audit_root=None):
    values = set()
    audit_root = Path(audit_root).resolve() if audit_root else None
    def add(value):
        if isinstance(value, str) and len(value) >= 6 and not placeholder(value):
            values.add(value.encode('utf-8'))

    def visit(value, inherited=False):
        if isinstance(value, dict):
            for key, child in value.items():
                if str(key).lower() in PUBLIC_METADATA_KEYS:
                    continue
                visit(child, inherited or bool(SENSITIVE_KEY.search(str(key))) or bool(IDENTITY_KEY.search(str(key))))
        elif isinstance(value, list):
            for child in value:
                visit(child, inherited)
        elif inherited:
            add(value)

    if reference:
        base = Path(reference).resolve(strict=True)
        if not base.is_dir():
            raise AuditError('private_reference_invalid')
        if audit_root and (base == audit_root or audit_root in base.parents):
            raise AuditError('private_reference_must_be_external')
        runtime = base / 'runtime'
        if not runtime.is_dir():
            raise AuditError('private_reference_runtime_missing')
        count = 0
        for current, dirs, files in os.walk(runtime, followlinks=False):
            dirs[:] = [d for d in dirs if d not in REFERENCE_SKIP and not (Path(current) / d).is_symlink()]
            for name in files:
                path = Path(current) / name
                if path.is_symlink() or path.suffix != '.json' or path.stat().st_size > 2 * 1024 * 1024:
                    continue
                count += 1
                if count > 10000:
                    raise AuditError('private_reference_limit')
                try:
                    visit(json.loads(path.read_text(encoding='utf-8')))
                except (ValueError, UnicodeError):
                    continue
        policy = base / 'hardware/panel-app/src/net/quber/afactorymode/MaintenancePolicy.java'
        if policy.is_file() and not policy.is_symlink():
            for match in re.findall(r'\bPACKAGE\s*=\s*"([^"]+)"', policy.read_text(encoding='utf-8')):
                add(match)
        for path in [base / '.env', base / 'panel/.env', base / 'hardware/.env']:
            if path.is_file() and not path.is_symlink():
                for line in path.read_text(encoding='utf-8').splitlines():
                    key, sep, value = line.partition('=')
                    if sep and SENSITIVE_KEY.search(key):
                        add(value.strip().strip('\"\''))
        signing_password = runtime / 'usb-probe-signing/password'
        if signing_password.is_file() and not signing_password.is_symlink() and signing_password.stat().st_size <= 4096:
            add(signing_password.read_text(encoding='utf-8').strip())
        if not values:
            raise AuditError('private_reference_empty')
    if patterns:
        path = Path(patterns).resolve(strict=True)
        if audit_root and (path == audit_root or audit_root in path.parents):
            raise AuditError('private_patterns_must_be_external')
        if path.stat().st_size > 2 * 1024 * 1024:
            raise AuditError('private_patterns_limit')
        obj = json.loads(path.read_text(encoding='utf-8'))
        entries = obj.get('values') if isinstance(obj, dict) else obj
        if not isinstance(entries, list) or not entries or any(not isinstance(v, str) or len(v) < 3 for v in entries):
            raise AuditError('private_patterns_invalid')
        values.update(v.encode('utf-8') for v in entries)
    expanded = set(values)
    for value in values:
        try:
            string = value.decode('utf-8')
            expanded.add(json.dumps(string, ensure_ascii=True)[1:-1].encode())
            if re.fullmatch(r'(?:[a-fA-F0-9]{2}:){5}[a-fA-F0-9]{2}', string):
                for separator in (':', '-', ''):
                    changed = string.replace(':', separator)
                    expanded.update({changed.lower().encode(), changed.upper().encode()})
            compact = string.replace(':', '')
            if len(compact) in (40, 64) and re.fullmatch(r'[a-fA-F0-9]+', compact):
                separated = ':'.join(compact[i:i + 2] for i in range(0, len(compact), 2))
                expanded.update({compact.lower().encode(), compact.upper().encode(), separated.lower().encode(), separated.upper().encode()})
        except UnicodeError:
            pass
    return tuple(sorted(expanded, key=len, reverse=True))


class Auditor:
    def __init__(self, secrets=(), max_file=8 * 1024 * 1024, max_total=128 * 1024 * 1024, max_entries=12000, max_depth=4, ignore_git=False):
        self.secrets = secrets
        self.max_file, self.max_total = max_file, max_total
        self.max_entries, self.max_depth = max_entries, max_depth
        self.total = 0
        self.entries = 0
        self.files = 0
        self.findings = set()
        self.ignore_git = ignore_git
        self.hidden_values = set(secrets)

    def redact(self, value):
        data = value.encode('utf-8', errors='replace')
        for secret in sorted(self.hidden_values, key=len, reverse=True):
            data = data.replace(secret, b'[redacted]')
        for rule in (JWT, MAC, IPV4, EMAIL, TAILNET):
            data = rule.sub(b'[redacted]', data)
        return ''.join(c if ord(c) >= 32 else '?' for c in data.decode('utf-8', errors='replace'))[:500]

    def flag(self, name, category):
        self.findings.add((self.redact(name), category))

    def content(self, name, data):
        if any(value in data for value in self.secrets):
            self.flag(name, 'private_reference_value')
        if PRIVATE_KEY.search(data):
            self.flag(name, 'private_key')
        if JWT.search(data):
            self.hidden_values.update(match[0] for match in JWT.finditer(data))
            self.flag(name, 'jwt_credential')
        fixture = fixture_path(name)
        for match in CREDENTIAL.finditer(data):
            value = match[1].decode('utf-8', errors='replace')
            template = re.match(r'^\s*\+\s*json\.dumps\(', value)
            dummy = fixture and (bool(re.fullmatch(r'[a-z][a-z_-]{0,39}', value)) or value == string.ascii_lowercase + string.digits)
            if not placeholder(value) and not template and not dummy:
                self.hidden_values.add(match[1])
                self.flag(name, 'literal_credential')
        if not fixture:
            for match in IPV4.finditer(data):
                try:
                    address = ipaddress.ip_address(match[0].decode('ascii'))
                    suffix = re.match(rb'/\d{1,2}(?!\d)', data[match.end():])
                    if suffix:
                        network = ipaddress.ip_network((match[0] + suffix[0]).decode('ascii'), strict=False)
                        if network in PRIVATE_NETWORKS and address == network.network_address:
                            continue
                    if any(address in network for network in PRIVATE_NETWORKS):
                        self.flag(name, 'private_network_address')
                except ValueError:
                    pass
            for match in MAC.finditer(data):
                if match[0].lower() not in {b'00:00:00:00:00:00', b'ff:ff:ff:ff:ff:ff'}:
                    self.flag(name, 'device_mac_address')
        for match in TAILNET.finditer(data):
            dummy = fixture and any(word in match[0].lower() for word in (b'fixture', b'test', b'dummy'))
            if b'example' not in match[0].lower() and b'tailnet' not in match[0].lower() and not dummy:
                self.flag(name, 'tailnet_hostname')
        for match in HOME_PATH.finditer(data):
            value = match[1].decode('utf-8', errors='replace')
            if not placeholder(value) and value.lower() not in {'user', 'username', '$user', '${user}', '<user>'}:
                self.flag(name, 'personal_filesystem_path')

    def path(self, name):
        self.content(name, name.encode('utf-8', errors='replace'))
        parts = PurePosixPath(name.replace('!', '/')).parts
        if any(part.lower() in FORBIDDEN_DIRS for part in parts):
            self.flag(name, 'forbidden_directory')
            return False
        leaf = parts[-1] if parts else ''
        if PurePosixPath(leaf).suffix.lower() in FORBIDDEN_EXTENSIONS or leaf in {'.env', '.DS_Store', 'id_rsa', 'id_ed25519', 'tailscaled.state'}:
            self.flag(name, 'forbidden_artifact')
            return False
        return True

    def budget(self, name, size):
        self.entries += 1
        if self.entries > self.max_entries or size > self.max_file or size < 0 or self.total + size > self.max_total:
            self.flag(name, 'scan_limit')
            return False
        self.total += size
        return True

    def payload(self, name, data, depth=0):
        self.files += 1
        self.content(name, data)
        if depth > self.max_depth:
            self.flag(name, 'archive_depth_limit')
            return
        lower = name.lower()
        try:
            if data.startswith((b'PK\x03\x04', b'PK\x05\x06', b'PK\x07\x08')) or lower.endswith('.zip'):
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    seen = set()
                    for index, entry in enumerate(archive.infolist()):
                        label = name + '!' + entry.filename
                        if index >= self.max_entries:
                            self.flag(name, 'scan_limit')
                            break
                        if not safe_member(entry.filename):
                            self.flag(label, 'unsafe_archive_path')
                            continue
                        if entry.filename in seen:
                            self.flag(label, 'duplicate_archive_member')
                            continue
                        seen.add(entry.filename)
                        mode = entry.external_attr >> 16
                        if stat.S_ISLNK(mode) or (stat.S_IFMT(mode) and not stat.S_ISREG(mode) and not stat.S_ISDIR(mode)):
                            self.flag(label, 'archive_special_file')
                            continue
                        if entry.flag_bits & 1:
                            self.flag(label, 'encrypted_archive')
                            continue
                        if not self.path(label) or entry.is_dir():
                            continue
                        if not self.budget(label, entry.file_size):
                            continue
                        with archive.open(entry) as stream:
                            value = stream.read(self.max_file + 1)
                        if len(value) != entry.file_size:
                            self.flag(label, 'archive_size_mismatch')
                            continue
                        self.payload(label, value, depth + 1)
                return
            if lower.endswith(('.tar', '.tar.gz', '.tgz', '.tar.bz2', '.tbz2', '.tar.xz', '.txz')) or data[257:262] == b'ustar':
                with tarfile.open(fileobj=io.BytesIO(data), mode='r:*') as archive:
                    seen = set()
                    for index, entry in enumerate(archive):
                        label = name + '!' + entry.name
                        if index >= self.max_entries:
                            self.flag(name, 'scan_limit')
                            break
                        if not safe_member(entry.name):
                            self.flag(label, 'unsafe_archive_path')
                            continue
                        if entry.name in seen:
                            self.flag(label, 'duplicate_archive_member')
                            continue
                        seen.add(entry.name)
                        if not entry.isfile() and not entry.isdir():
                            self.flag(label, 'archive_special_file')
                            continue
                        if not self.path(label) or entry.isdir():
                            continue
                        if not self.budget(label, entry.size):
                            continue
                        stream = archive.extractfile(entry)
                        if stream is None:
                            self.flag(label, 'unreadable_archive_member')
                            continue
                        with stream:
                            value = stream.read(self.max_file + 1)
                        if len(value) != entry.size:
                            self.flag(label, 'archive_size_mismatch')
                            continue
                        self.payload(label, value, depth + 1)
                return
            if data.startswith(b'\x1f\x8b') or lower.endswith('.gz'):
                with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:
                    value = stream.read(self.max_file + 1)
                label = name + '!decompressed'
                if self.budget(label, len(value)):
                    self.payload(label, value, depth + 1)
                return
        except Exception:
            self.flag(name, 'unreadable_archive')
            return
        if b'\x00' in data:
            self.flag(name, 'opaque_binary')
        else:
            try:
                data.decode('utf-8')
            except UnicodeError:
                self.flag(name, 'non_utf8_binary')

    def scan(self, root):
        root = Path(root)
        if root.is_symlink():
            self.flag(root.name, 'filesystem_link')
            return
        if root.is_file():
            self.scan_file(root, root.name)
            return
        if not root.is_dir():
            self.flag('.', 'missing_input')
            return
        for current, dirs, files in os.walk(root, followlinks=False, onerror=lambda error: self.flag('.', 'unreadable_directory')):
            kept = []
            for name in sorted(dirs):
                path = Path(current) / name
                label = path.relative_to(root).as_posix()
                if path.is_symlink():
                    self.flag(label, 'filesystem_link')
                elif self.ignore_git and Path(current) == root and name == '.git':
                    continue
                elif self.path(label):
                    kept.append(name)
            dirs[:] = kept
            for name in sorted(files):
                path = Path(current) / name
                self.scan_file(path, path.relative_to(root).as_posix())

    def scan_file(self, path, name):
        try:
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                self.flag(name, 'filesystem_link_or_special_file')
                return
            if not self.path(name) or not self.budget(name, info.st_size):
                return
            descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
            with os.fdopen(descriptor, 'rb') as stream:
                opened = os.fstat(stream.fileno())
                if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino):
                    self.flag(name, 'file_changed_during_scan')
                    return
                data = stream.read(self.max_file + 1)
                after = os.fstat(stream.fileno())
            if len(data) != info.st_size or (after.st_size, after.st_mtime_ns) != (info.st_size, info.st_mtime_ns):
                self.flag(name, 'file_changed_during_scan')
                return
            self.payload(name, data)
        except OSError:
            self.flag(name, 'unreadable_file')

    def result(self):
        return {'ok': not self.findings, 'files_scanned': self.files,
                'findings': [{'file': self.redact(name), 'category': category} for name, category in sorted(self.findings)]}


def main(argv=None):
    parser = argparse.ArgumentParser(description='Audit a source directory or archive without printing matched content.')
    parser.add_argument('path', help='Source directory or archive to audit')
    parser.add_argument('--private-reference', help='Original project root, used only on the private audit machine')
    parser.add_argument('--private-patterns', help='External JSON list of exact private values, or an object with a values list')
    parser.add_argument('--ignore-git', action='store_true', help='Ignore only root Git metadata in a source directory; archive Git metadata is always rejected')
    args = parser.parse_args(argv)
    try:
        root = Path(args.path).absolute()
        secrets = private_values(args.private_reference, args.private_patterns, root.resolve())
        auditor = Auditor(secrets=secrets, ignore_git=args.ignore_git)
        auditor.scan(root)
        result = auditor.result()
    except (OSError, ValueError, AuditError):
        result = {'ok': False, 'files_scanned': 0, 'findings': [{'file': '.', 'category': 'private_reference_or_input_error'}]}
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
