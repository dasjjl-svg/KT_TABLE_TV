import argparse
import contextlib
import copy
import fcntl
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import struct
import tempfile
import time
import zipfile


ROOT = Path(__file__).resolve().parents[1]
_SPEC = importlib.util.spec_from_file_location('panel_recovery_maintenance', ROOT / 'hardware/panel_maintenance_server.py')
M = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(M)
CATALOG = 'runtime/panel-maintenance/known-good-catalog.json'
OUTPUT = 'runtime/panel-maintenance/recovery-prepared'
SIGNING = 'runtime/panel-signing-transition'
APPROVED_SPKI = '3d3df7dc9bf26e02d4cd76256d41d45e41a4dedebe7feb95c40e3697681be8a7'
CREDENTIAL_INPUTS = (
    'runtime/legacy/device.json', 'runtime/kt-apk/config.json', 'runtime/kt-home-apk/config.json',
    'runtime/android-settings-apk/config.json', 'runtime/system-backup/config.json',
    'runtime/panel-maintenance/config.json', 'runtime/panel-maintenance/public.der',
)
ANDROID = 'http://schemas.android.com/apk/res/android'
NONE = 0xffffffff
MAX_MANIFEST = 256 * 1024
MAX_EXPANDED = 16 * 1024 * 1024
MAX_ENTRY = 8 * 1024 * 1024


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def private_read(path, maximum):
    path = Path(path).absolute()
    directory = M.BASE.directory_fd(path.parent, private=True)
    try:
        return M.read_private(directory, path.name, maximum)
    finally:
        os.close(directory)


def private_write(path, raw):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | M.NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


def credential_fingerprint(project=ROOT):
    files = [{'path': name, 'sha256': digest(private_read(Path(project) / name, 4096))}
             for name in CREDENTIAL_INPUTS]
    return digest(M.encoded({'schema': 1, 'files': files}))


def read_catalog(project=ROOT):
    raw = private_read(Path(project) / CATALOG, 65536)
    value = M.BASE.strict_json(raw)
    if (not isinstance(value, dict) or set(value) != {'schema', 'package', 'artifacts'}
            or type(value['schema']) is not int or value['schema'] != 1 or value['package'] != M.PACKAGE
            or not isinstance(value['artifacts'], list) or not 1 <= len(value['artifacts']) <= 100):
        raise ValueError('recovery catalog shape')
    seen = set()
    for item in value['artifacts']:
        if not isinstance(item, dict) or set(item) != {'sha256', 'versionCode', 'path', 'credentialFingerprint', 'verifiedOnDevice'}:
            raise ValueError('recovery catalog artifact')
        for field in ('sha256', 'credentialFingerprint'):
            if not isinstance(item[field], str) or not re.fullmatch(M.DIGEST, item[field]):
                raise ValueError('recovery catalog digest')
        if (type(item['versionCode']) is not int or not 40 <= item['versionCode'] <= 2147483647
                or item['verifiedOnDevice'] is not True or not isinstance(item['path'], str)
                or item['path'] != 'prepared-v' + str(item['versionCode']) + '/HomePanel.apk'
                or item['sha256'] in seen):
            raise ValueError('recovery catalog approval')
        seen.add(item['sha256'])
    return value, digest(raw)


def signature_entry(name):
    upper = name.upper()
    if not upper.startswith('META-INF/') or '/' in upper[len('META-INF/'):]:
        return False
    leaf = upper[len('META-INF/'):]
    return leaf == 'MANIFEST.MF' or leaf.startswith('SIG-') or leaf.endswith(('.SF', '.RSA', '.DSA', '.EC'))


def zip_entries(raw):
    if not 4 <= len(raw) <= M.MAX_APK or not raw.startswith(b'PK\x03\x04'):
        raise ValueError('APK compressed size')
    entries, names, total = [], set(), 0
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        items = archive.infolist()
        if not 1 <= len(items) <= 128:
            raise ValueError('APK entry count')
        for item in items:
            name = item.filename
            parts = name.rstrip('/').split('/')
            mode = item.external_attr >> 16
            if (name != item.orig_filename or not parts or any(part in ('', '.', '..') for part in parts)
                    or name != '/'.join(parts) + ('/' if item.is_dir() else '')
                    or any(not re.fullmatch(r'[A-Za-z0-9_.-]+', part) for part in parts)
                    or name in names or item.flag_bits & 1
                    or item.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)
                    or bool(stat.S_ISDIR(mode)) and not item.is_dir()
                    or not 0 <= item.file_size <= MAX_ENTRY):
                raise ValueError('APK entry shape')
            names.add(name)
            total += item.file_size
            if total > MAX_EXPANDED:
                raise ValueError('APK expanded size')
            with archive.open(item) as stream:
                data = stream.read(item.file_size + 1)
            if len(data) != item.file_size or item.is_dir() and data:
                raise ValueError('APK entry size')
            entries.append((item, data))
    if 'AndroidManifest.xml' not in names or 'classes.dex' not in names:
        raise ValueError('APK required entries')
    return entries


def _chunk(raw, offset, limit):
    if offset + 8 > limit:
        raise ValueError('AXML chunk header')
    kind, header, size = struct.unpack_from('<HHI', raw, offset)
    if header < 8 or size < header or size % 4 or offset + size > limit:
        raise ValueError('AXML chunk bounds')
    return kind, header, size


def _strings(raw, offset, header, size):
    if header != 28:
        raise ValueError('AXML string header')
    count, styles, flags, start, style_start = struct.unpack_from('<IIIII', raw, offset + 8)
    if (not 1 <= count <= 8192 or styles or style_start or flags & ~0x101
            or start < header + count * 4 or start >= size):
        raise ValueError('AXML string pool')
    end = offset + size

    def length(position, utf8):
        width, mask = (1, 0x80) if utf8 else (2, 0x8000)
        if position + width > end:
            raise ValueError('AXML string length')
        first = raw[position] if utf8 else struct.unpack_from('<H', raw, position)[0]
        position += width
        if first & mask:
            if position + width > end:
                raise ValueError('AXML string length')
            second = raw[position] if utf8 else struct.unpack_from('<H', raw, position)[0]
            first = ((first & ~mask) << (width * 8)) | second
            position += width
        return first, position

    result = []
    for index in range(count):
        relative = struct.unpack_from('<I', raw, offset + header + index * 4)[0]
        position = offset + start + relative
        if not offset + start <= position < end:
            raise ValueError('AXML string offset')
        units, position = length(position, bool(flags & 0x100))
        if units > 65535:
            raise ValueError('AXML string size')
        if flags & 0x100:
            byte_count, position = length(position, True)
            terminator = 1
            encoding = 'utf-8'
        else:
            byte_count, terminator, encoding = units * 2, 2, 'utf-16le'
        if position + byte_count + terminator > end or raw[position + byte_count:position + byte_count + terminator] != b'\0' * terminator:
            raise ValueError('AXML string terminator')
        value = raw[position:position + byte_count].decode(encoding)
        if '\0' in value or len(value.encode('utf-16le')) // 2 != units:
            raise ValueError('AXML string encoding')
        result.append(value)
    return result


def manifest_version(raw):
    if len(raw) < 8 or len(raw) > MAX_MANIFEST or _chunk(raw, 0, len(raw)) != (3, 8, len(raw)):
        raise ValueError('AXML document')
    strings = resources = None
    namespaces, elements = [], []
    root_seen = False
    version = version_offset = version_name = package = None
    offset = 8

    def string(index, optional=False):
        if optional and index == NONE:
            return None
        if strings is None or not 0 <= index < len(strings):
            raise ValueError('AXML string reference')
        return strings[index]

    while offset < len(raw):
        kind, header, size = _chunk(raw, offset, len(raw))
        if kind == 1:
            if strings is not None or offset != 8:
                raise ValueError('AXML duplicate string pool')
            strings = _strings(raw, offset, header, size)
        elif kind == 0x180:
            if strings is None or resources is not None or root_seen or namespaces or header != 8 or (size - 8) // 4 > len(strings):
                raise ValueError('AXML resource map')
            resources = struct.unpack_from('<' + 'I' * ((size - 8) // 4), raw, offset + 8)
            for identifier, expected in ((0x0101021b, 'versionCode'), (0x0101021c, 'versionName')):
                indices = [index for index, value in enumerate(resources) if value == identifier]
                if len(indices) != 1 or strings[indices[0]] != expected:
                    raise ValueError('AXML version resource identity')
        elif kind in (0x100, 0x101, 0x102, 0x103):
            if strings is None or resources is None or header != 16 or size < 24:
                raise ValueError('AXML node')
            string(struct.unpack_from('<I', raw, offset + 12)[0], True)
            namespace, name = struct.unpack_from('<II', raw, offset + 16)
            if kind in (0x100, 0x101):
                if size != 24:
                    raise ValueError('AXML namespace size')
                string(namespace, True)
                string(name)
                pair = (namespace, name)
                if kind == 0x100:
                    namespaces.append(pair)
                elif not namespaces or namespaces.pop() != pair:
                    raise ValueError('AXML namespace pairing')
            elif kind == 0x103:
                if size != 24 or not elements or elements.pop() != (namespace, name):
                    raise ValueError('AXML element pairing')
            else:
                ns, tag = string(namespace, True), string(name)
                is_root = not elements
                if is_root:
                    if root_seen or ns is not None or tag != 'manifest':
                        raise ValueError('AXML root')
                    root_seen = True
                if size < 36:
                    raise ValueError('AXML attributes')
                start, width, count, id_index, class_index, style_index = struct.unpack_from('<HHHHHH', raw, offset + 24)
                if start != 20 or width != 20 or count > 256 or size != 36 + count * 20 or max(id_index, class_index, style_index) > count:
                    raise ValueError('AXML attribute bounds')
                attributes = set()
                for index in range(count):
                    location = offset + 36 + index * 20
                    attr_ns, attr_name, raw_value, value_size, reserved, value_type, data = struct.unpack_from('<IIIHBBI', raw, location)
                    key = (string(attr_ns, True), string(attr_name))
                    if key[0] is not None and not any(string(pair[1]) == key[0] for pair in namespaces):
                        raise ValueError('AXML undeclared attribute namespace')
                    string(raw_value, True)
                    if key in attributes or value_size != 8 or reserved or value_type not in {0, 1, 2, 3, 4, 5, 6, 0x10, 0x11, 0x12, 0x1c, 0x1d, 0x1e, 0x1f}:
                        raise ValueError('AXML attribute value')
                    attributes.add(key)
                    if value_type == 3:
                        string(data)
                    if key == (ANDROID, 'versionCode'):
                        if (not is_root or version is not None or attr_name >= len(resources)
                                or resources[attr_name] != 0x0101021b or raw_value != NONE
                                or value_type != 0x10 or not 40 <= data <= 2147483647):
                            raise ValueError('AXML versionCode must be one integer')
                        version, version_offset = data, location + 16
                    if is_root and key == (ANDROID, 'versionName'):
                        if value_type != 3 or attr_name >= len(resources) or resources[attr_name] != 0x0101021c:
                            raise ValueError('AXML versionName')
                        version_name = string(data)
                    if is_root and key == (None, 'package'):
                        if value_type != 3:
                            raise ValueError('AXML package')
                        package = string(data)
                elements.append((namespace, name))
        else:
            raise ValueError('AXML unsupported chunk')
        offset += size
    if (not root_seen or elements or namespaces or version is None or package != M.PACKAGE
            or not isinstance(version_name, str) or not 1 <= len(version_name) <= 100):
        raise ValueError('AXML incomplete manifest')
    return {'versionCode': version, 'versionName': version_name, 'offset': version_offset}


def patch_manifest(raw, version):
    info = manifest_version(raw)
    if type(version) is not int or not info['versionCode'] < version <= 2147483647:
        raise ValueError('recovery version must advance')
    offset = info['offset']
    changed = raw[:offset] + struct.pack('<I', version) + raw[offset + 4:]
    if manifest_version(changed) != dict(info, versionCode=version):
        raise ValueError('AXML patch verification')
    return changed, info


def unsigned_apk(entries, manifest):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        for item, data in entries:
            if not signature_entry(item.filename):
                archive.writestr(copy.copy(item), manifest if item.filename == 'AndroidManifest.xml' else data)
    return output.getvalue()


def compare_payloads(before, after, patched_manifest):
    original = {item.filename: data for item, data in before if not signature_entry(item.filename)}
    result = {item.filename: data for item, data in after if not signature_entry(item.filename)}
    if set(original) != set(result):
        raise ValueError('recovery payload entry set changed')
    expected = dict(original, **{'AndroidManifest.xml': patched_manifest})
    if expected != result:
        raise ValueError('recovery payload bytes changed')
    return {name: digest(data) for name, data in sorted(result.items())}


def approved_signing(project, scratch):
    directory = Path(project) / SIGNING
    certificate = private_read(directory / 'platform.x509.pem', 16384)
    private = private_read(directory / 'platform.pk8', 16384)
    public = private_read(directory / 'platform-public-key.der', 4096)
    if digest(public) != APPROVED_SPKI:
        raise ValueError('approved public key mismatch')
    der = M.command([M.OPENSSL, 'x509', '-outform', 'DER'], certificate)
    pem = M.command([M.OPENSSL, 'x509', '-pubkey', '-noout'], certificate)
    certificate_public = M.command([M.OPENSSL, 'pkey', '-pubin', '-outform', 'DER'], pem)
    key_public = M.command([M.OPENSSL, 'pkey', '-inform', 'DER', '-pubout', '-outform', 'DER'], private)
    if digest(der) != M.PLATFORM_SIGNER or certificate_public != public or key_public != public:
        raise ValueError('approved signing key mismatch')
    key_path, cert_path = scratch / 'platform.pk8', scratch / 'platform.x509.pem'
    private_write(key_path, private)
    private_write(cert_path, certificate)
    return key_path, cert_path


def sign_apk(unsigned, scratch, project):
    unsigned_path, aligned, signed = scratch / 'unsigned.apk', scratch / 'aligned.apk', scratch / 'signed.apk'
    private_write(unsigned_path, unsigned)
    key, certificate = approved_signing(project, scratch)
    M.command([M.TOOLS / 'zipalign', '-f', '4', unsigned_path, aligned])
    aligned.chmod(0o600)
    M.command([M.JAVA, '-jar', M.SIGNER_JAR, 'sign', '--key', key, '--cert', certificate,
               '--min-sdk-version', '21', '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'false',
               '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false', '--out', signed, aligned])
    signed.chmod(0o600)
    M.command([M.TOOLS / 'zipalign', '-c', '4', signed])
    return signed


def verify_release(raw, public_path, scratch):
    envelope = M.BASE.strict_json(raw)
    if not isinstance(envelope, dict) or set(envelope) != {'payload', 'signature'}:
        raise ValueError('history envelope')
    payload = M.unb64(envelope['payload'], 4096)
    signature = M.unb64(envelope['signature'], 1024)
    data = M.validate_payload(M.BASE.strict_json(payload))
    signature_path = scratch / ('release-signature-' + secrets.token_hex(8))
    private_write(signature_path, signature)
    try:
        M.command([M.OPENSSL, 'dgst', '-sha256', '-verify', public_path, '-keyform', 'DER', '-signature', signature_path], payload)
    finally:
        signature_path.unlink()
    return data


def version_floor(runtime, installed, scratch):
    if type(installed) is not int or not 40 <= installed <= 2147483647:
        raise ValueError('current installed version required')
    saved = M.BASE.strict_json(private_read(runtime / 'latest-report.json', M.MAX_REPORT))
    if (not isinstance(saved, dict) or set(saved) != {'receivedAt', 'report'}
            or type(saved['receivedAt']) is not int or not 1 <= saved['receivedAt'] <= int(time.time()) + 300):
        raise ValueError('last installed report')
    report = M.validate_report(saved['report'])
    if report['package'] != M.PACKAGE:
        raise ValueError('managed app report required')
    public = scratch / 'release-public.der'
    private_write(public, private_read(runtime / 'public.der', 4096))
    versions, hashes = [], {}
    directory = M.BASE.directory_fd(runtime / 'releases', private=True)
    try:
        names = sorted(os.listdir(directory))
        if len(names) > 4096:
            raise ValueError('release history limit')
        for name in names:
            if not re.fullmatch(M.IDENTIFIER + r'\.json', name):
                raise ValueError('release history filename')
            raw = M.read_private(directory, name, M.MAX_RELEASE)
            data = verify_release(raw, public, scratch)
            if data['id'] + '.json' != name:
                raise ValueError('release history identity')
            versions.append(data['versionCode'])
            hashes[name] = digest(raw)
    finally:
        os.close(directory)
    try:
        current = private_read(runtime / 'current.json', M.MAX_RELEASE)
    except FileNotFoundError:
        current = None
    if current is not None:
        versions.append(verify_release(current, public, scratch)['versionCode'])
        hashes['current.json'] = digest(current)
    return {'installedVersionProvided': installed, 'lastReportedVersion': report['versionCode'],
            'lastReportReceivedAt': saved['receivedAt'], 'publishedMaxVersion': max(versions, default=0),
            'releaseHistorySha256': digest(M.encoded(hashes))}


@contextlib.contextmanager
def publication_lock(runtime):
    directory = M.BASE.directory_fd(runtime, private=True)
    descriptor = None
    try:
        descriptor = os.open('.publish.lock', os.O_RDONLY | M.NOFOLLOW, dir_fd=directory)
        M.BASE.private_file_info(descriptor)
        fcntl.flock(descriptor, fcntl.LOCK_SH | fcntl.LOCK_NB)
        yield
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(directory)


def prepare(source_sha, installed_version, target_version=None, project=ROOT):
    project = Path(project).absolute()
    if not isinstance(source_sha, str) or not re.fullmatch(M.DIGEST, source_sha):
        raise ValueError('source SHA256 required')
    catalog, catalog_sha = read_catalog(project)
    matches = [entry for entry in catalog['artifacts'] if entry['sha256'] == source_sha]
    if len(matches) != 1:
        raise ValueError('source is not approved as known good')
    approved = matches[0]
    fingerprint = credential_fingerprint(project)
    if approved['credentialFingerprint'] != fingerprint:
        raise ValueError('known-good credentials differ from current configuration')
    runtime = project / 'runtime/panel-maintenance'
    original = private_read(runtime / approved['path'], M.MAX_APK)
    if digest(original) != source_sha:
        raise ValueError('known-good APK hash mismatch')
    entries = zip_entries(original)
    manifest = dict((item.filename, data) for item, data in entries)['AndroidManifest.xml']
    source_info = manifest_version(manifest)
    if source_info['versionCode'] != approved['versionCode']:
        raise ValueError('known-good version mismatch')
    output = project / OUTPUT
    with publication_lock(runtime):
        output_fd = M.BASE.directory_fd(output, create=True, private=True)
        os.close(output_fd)
        with tempfile.TemporaryDirectory(prefix='.prepare-', dir=output) as temporary:
            scratch = Path(temporary)
            scratch.chmod(0o700)
            source = scratch / 'source.apk'
            private_write(source, original)
            if M.verify_apk(source) != approved['versionCode']:
                raise ValueError('source APK verification version')
            floor = version_floor(runtime, installed_version, scratch)
            minimum = max(installed_version, floor['lastReportedVersion'], floor['publishedMaxVersion'], source_info['versionCode'])
            version = minimum + 1 if target_version is None else target_version
            if type(version) is not int or not minimum < version <= 2147483647:
                raise ValueError('target must exceed installed and every published version')
            patched, source_info = patch_manifest(manifest, version)
            signed = sign_apk(unsigned_apk(entries, patched), scratch, project)
            if M.verify_apk(signed) != version:
                raise ValueError('recovery APK verification version')
            result = private_read(signed, M.MAX_APK)
            payloads = compare_payloads(entries, zip_entries(result), patched)
            if read_catalog(project)[1] != catalog_sha or credential_fingerprint(project) != fingerprint:
                raise ValueError('recovery approval or configuration changed')
            name = 'v' + str(version) + '-from-v' + str(source_info['versionCode']) + '-' + secrets.token_hex(8)
            destination = output / name
            destination.mkdir(mode=0o700)
            metadata = {
                'schema': 1, 'preparedAt': int(time.time()), 'package': M.PACKAGE,
                'sourceSha256': source_sha, 'targetSha256': digest(result),
                'sourceVersionCode': source_info['versionCode'], 'targetVersionCode': version,
                'versionName': source_info['versionName'], 'size': len(result),
                'credentialFingerprint': fingerprint, 'catalogSha256': catalog_sha, 'versionFloor': floor,
                'manifestChange': {'offset': source_info['offset'], 'length': 4, 'field': 'android:versionCode'},
                'payloadSha256': payloads,
                'validations': {'sourceKnownGood': True, 'sourceSignatureApi22': True, 'targetSignatureApi22': True,
                                'approvedSigner': M.PLATFORM_SIGNER, 'payloadsUnchangedExceptVersionCode': True,
                                'credentialsCurrent': True, 'versionAdvanced': True, 'zipAligned': True},
                'published': False,
            }
            try:
                private_write(destination / 'HomePanel.apk', result)
                private_write(destination / 'metadata.json', M.encoded(metadata))
            except Exception:
                shutil.rmtree(destination)
                raise
            return {'ok': True, 'prepared': True, 'published': False, 'apk': str(destination / 'HomePanel.apk'),
                    'metadata': str(destination / 'metadata.json'), 'sourceVersionCode': source_info['versionCode'],
                    'targetVersionCode': version, 'versionName': source_info['versionName'], 'targetSha256': digest(result)}


def main(argv=None):
    parser = argparse.ArgumentParser(description='Prepare a verified earlier panel payload with a new increasing version; never publish or install.')
    actions = parser.add_subparsers(dest='action', required=True)
    actions.add_parser('fingerprint', help='Print only the fingerprint of all seven required current configuration inputs.')
    prepare_parser = actions.add_parser('prepare', help='Prepare an approved known-good APK without publishing it.')
    prepare_parser.add_argument('--source-sha', required=True)
    prepare_parser.add_argument('--installed-version', type=int, required=True)
    prepare_parser.add_argument('--target-version', type=int)
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        value = ({'ok': True, 'credentialFingerprint': credential_fingerprint()}
                 if args.action == 'fingerprint' else prepare(args.source_sha, args.installed_version, args.target_version))
        print(json.dumps(value, ensure_ascii=False, sort_keys=True))
        return 0
    except Exception:
        print(json.dumps({'ok': False, 'error': 'recovery_preparation_failed', 'published': False}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
