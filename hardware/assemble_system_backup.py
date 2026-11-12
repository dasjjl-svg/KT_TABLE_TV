import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import stat
import sys


_SPEC = importlib.util.spec_from_file_location('_system_backup_format', Path(__file__).with_name('system_backup_receiver.py'))
_FORMAT = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_FORMAT)
_BASE = _FORMAT._BASE
NOFOLLOW = _FORMAT.NOFOLLOW
MIN_FREE = 512 * 1024 * 1024


def stable(first, second):
    return all(getattr(first, name) == getattr(second, name) for name in
               ('st_dev', 'st_ino', 'st_mode', 'st_uid', 'st_gid', 'st_nlink', 'st_size', 'st_mtime_ns', 'st_ctime_ns'))


def private_directory(parent, name):
    descriptor = os.open(name, os.O_RDONLY | os.O_DIRECTORY | NOFOLLOW, dir_fd=parent)
    info = os.fstat(descriptor)
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        os.close(descriptor)
        raise ValueError('directory is not private and owned')
    return descriptor


def consume_file(parent, name, size, expected, output=None, combined=None):
    descriptor = os.open(name, os.O_RDONLY | os.O_NONBLOCK | NOFOLLOW, dir_fd=parent)
    try:
        before = _BASE.private_file_info(descriptor, size)
        hashed = hashlib.sha256()
        remaining = size
        while remaining:
            raw = os.read(descriptor, min(1048576, remaining))
            if not raw:
                raise ValueError('source file is shorter than expected')
            remaining -= len(raw)
            hashed.update(raw)
            if combined is not None:
                combined.update(raw)
            if output is not None:
                output.write(raw)
        after = _BASE.private_file_info(descriptor, size)
        named = os.stat(name, dir_fd=parent, follow_symlinks=False)
        if not stable(before, after) or not stable(before, named) or os.read(descriptor, 1):
            raise ValueError('source file changed while reading')
        if hashed.hexdigest() != expected:
            raise ValueError('file SHA-256 mismatch')
    finally:
        os.close(descriptor)


def validate_manifest(raw, spec, capture_id, inventory_sha):
    data = _BASE.strict_json(raw)
    required = {'version', 'captureId', 'id', 'device', 'size', 'chunkSize', 'sha256',
                'inventorySha256', 'sourceConsistency', 'transportVerified', 'restoreTested',
                'mountedWritableAtInventory', 'completedAt'}
    if not isinstance(data, dict) or set(data) != required:
        raise ValueError('manifest shape')
    if (type(data['version']) is not int or data['version'] != 1 or data['captureId'] != capture_id
            or data['inventorySha256'] != inventory_sha or data['id'] != spec['id']
            or data['device'] != spec['device'] or type(data['size']) is not int or data['size'] != spec['size']
            or type(data['chunkSize']) is not int or data['chunkSize'] != spec['chunkSize']
            or data['sourceConsistency'] != 'live_non_atomic' or data['transportVerified'] is not True
            or data['restoreTested'] is not False or data['mountedWritableAtInventory'] is not False
            or not isinstance(data['sha256'], str) or not re.fullmatch(_FORMAT.DIGEST, data['sha256'])):
        raise ValueError('manifest does not match inventory and plan')
    _BASE.bounded_text(data['completedAt'], 64, nonempty=True)
    return data


def chunk_records(parent, spec):
    count = (spec['size'] + _FORMAT.CHUNK_SIZE - 1) // _FORMAT.CHUNK_SIZE
    records = {}
    for name in os.listdir(parent):
        if name == 'manifest.json' or name.startswith('.incoming-'):
            continue
        match = re.fullmatch(r'([0-9]{8})-(' + _FORMAT.DIGEST + r')\.bin', name)
        if match is None:
            raise ValueError('unexpected source chunk filename')
        index = int(match[1])
        if index >= count or index in records:
            raise ValueError('duplicate or unexpected chunk index')
        records[index] = (name, min(_FORMAT.CHUNK_SIZE, spec['size'] - index * _FORMAT.CHUNK_SIZE), match[2])
    if len(records) != count:
        raise ValueError('completed partition has missing chunks')
    return [records[index] for index in range(count)]


def ensure_space(descriptor, needed, reserve):
    info = os.fstatvfs(descriptor)
    if info.f_bavail * info.f_frsize < needed + reserve:
        raise ValueError('insufficient free space for assembled images')


def ensure_unchanged(parent, name, raw, limit):
    if _FORMAT.read_private(parent, name, limit) != raw:
        raise ValueError('source metadata changed')


def index_before(parent, capture_id, inventory_sha, plan_sha):
    try:
        raw = _FORMAT.read_private(parent, 'index.json', 262144)
    except FileNotFoundError:
        return None
    data = _BASE.strict_json(raw)
    if (not isinstance(data, dict) or data.get('version') != 1 or data.get('captureId') != capture_id
            or data.get('inventorySha256') != inventory_sha or data.get('planSha256') != plan_sha
            or data.get('sourceConsistency') != 'live_non_atomic' or data.get('restoreTested') is not False
            or not isinstance(data.get('images'), list)):
        raise ValueError('existing image index belongs to different or invalid metadata')
    return raw


def save_index(parent, raw, previous):
    name = '.index-' + secrets.token_hex(16)
    descriptor = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW, 0o600, dir_fd=parent)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(raw)
            output.flush()
            os.fsync(output.fileno())
        if previous is None:
            os.link(name, 'index.json', src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
            os.unlink(name, dir_fd=parent)
        else:
            ensure_unchanged(parent, 'index.json', previous, 262144)
            os.replace(name, 'index.json', src_dir_fd=parent, dst_dir_fd=parent)
        os.fsync(parent)
    finally:
        try:
            os.unlink(name, dir_fd=parent)
        except FileNotFoundError:
            pass


def assemble(capture_path, partial=False, min_free=MIN_FREE):
    capture_path = Path(capture_path).absolute()
    capture_id = capture_path.name
    if not re.fullmatch(_FORMAT.CAPTURE, capture_id):
        raise ValueError('capture directory must retain its capture ID')
    capture = _BASE.directory_fd(capture_path, private=True)
    files_fd = chunks_fd = images_fd = None
    selected = []
    try:
        inventory_raw = _FORMAT.read_private(capture, 'inventory.json', _FORMAT.MAX_INVENTORY)
        inventory_sha = _FORMAT.digest(inventory_raw)
        files = _FORMAT.validate_inventory(_BASE.strict_json(inventory_raw))
        files_fd = private_directory(capture, 'files')
        for name, item in files.items():
            consume_file(files_fd, name + '.bin', len(item['raw']), _FORMAT.digest(item['raw']))
        plan_raw = _FORMAT.read_private(capture, 'plan.json', 65536)
        plan = _FORMAT.validate_plan(_BASE.strict_json(plan_raw), capture_id, inventory_sha, files)
        plan_sha = _FORMAT.digest(plan_raw)
        chunks_fd = private_directory(capture, 'chunks')
        pending = []
        for spec in plan['partitions']:
            descriptor = None
            try:
                descriptor = private_directory(chunks_fd, spec['id'])
                manifest_raw = _FORMAT.read_private(descriptor, 'manifest.json', 4096)
            except FileNotFoundError:
                if descriptor is not None:
                    os.close(descriptor)
                pending.append(spec['id'])
                continue
            except Exception:
                if descriptor is not None:
                    os.close(descriptor)
                raise
            try:
                manifest = validate_manifest(manifest_raw, spec, capture_id, inventory_sha)
                records = chunk_records(descriptor, spec)
                selected.append({'fd': descriptor, 'spec': spec, 'manifest': manifest,
                                 'manifestRaw': manifest_raw, 'records': records})
            except Exception:
                os.close(descriptor)
                raise
        if pending and not partial:
            raise ValueError('not all planned partitions are complete; use --partial for completed partitions only')
        if not selected:
            raise ValueError('no completed partition is available')
        ensure_unchanged(capture, 'inventory.json', inventory_raw, _FORMAT.MAX_INVENTORY)
        ensure_unchanged(capture, 'plan.json', plan_raw, 65536)
        images_fd = _BASE.private_child(capture, 'images')
        previous = index_before(images_fd, capture_id, inventory_sha, plan_sha)
        if previous is not None:
            current = {item['spec']['id']: item for item in selected}
            seen = set()
            for record in _BASE.strict_json(previous)['images']:
                if not isinstance(record, dict) or not isinstance(record.get('id'), str):
                    raise ValueError('existing image index record is invalid')
                name = record['id']
                source = current.get(name)
                if (name in seen or source is None or record.get('sha256') != source['manifest']['sha256']
                        or record.get('manifestSha256') != _FORMAT.digest(source['manifestRaw'])):
                    raise ValueError('previously assembled source metadata changed or disappeared')
                seen.add(name)
        needed = 0
        for item in selected:
            spec, manifest = item['spec'], item['manifest']
            try:
                consume_file(images_fd, spec['id'] + '.img', spec['size'], manifest['sha256'])
                item['existing'] = True
            except FileNotFoundError:
                item['existing'] = False
                needed += spec['size']
        ensure_space(images_fd, needed + 1048576, min_free)
        records = []
        for item in selected:
            spec, manifest, source_fd = item['spec'], item['manifest'], item['fd']
            name = spec['id'] + '.img'
            temp = None
            output = None
            try:
                if not item['existing']:
                    ensure_space(images_fd, spec['size'] + 1048576, min_free)
                    temp = '.assembling-' + secrets.token_hex(16)
                    descriptor = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW, 0o600, dir_fd=images_fd)
                    output = os.fdopen(descriptor, 'wb')
                combined = hashlib.sha256()
                for chunk_name, size, checksum in item['records']:
                    consume_file(source_fd, chunk_name, size, checksum, output, combined)
                if combined.hexdigest() != manifest['sha256']:
                    raise ValueError('partition SHA-256 does not match source manifest')
                ensure_unchanged(source_fd, 'manifest.json', item['manifestRaw'], 4096)
                ensure_unchanged(capture, 'inventory.json', inventory_raw, _FORMAT.MAX_INVENTORY)
                ensure_unchanged(capture, 'plan.json', plan_raw, 65536)
                if output is not None:
                    output.flush()
                    os.fsync(output.fileno())
                    output.close()
                    output = None
                    consume_file(images_fd, temp, spec['size'], manifest['sha256'])
                    try:
                        os.link(temp, name, src_dir_fd=images_fd, dst_dir_fd=images_fd, follow_symlinks=False)
                    except FileExistsError:
                        consume_file(images_fd, name, spec['size'], manifest['sha256'])
                    os.unlink(temp, dir_fd=images_fd)
                    temp = None
                    os.fsync(images_fd)
                sidecar = (manifest['sha256'] + '  ' + name + '\n').encode('ascii')
                _FORMAT.save_once(images_fd, name + '.sha256', sidecar)
                records.append({'id': spec['id'], 'file': name, 'size': spec['size'], 'sha256': manifest['sha256'],
                                'manifestSha256': _FORMAT.digest(item['manifestRaw']), 'checksumFile': name + '.sha256',
                                'sourceConsistency': 'live_non_atomic', 'restoreTested': False})
            finally:
                if output is not None:
                    output.close()
                if temp is not None:
                    try:
                        os.unlink(temp, dir_fd=images_fd)
                    except FileNotFoundError:
                        pass
        ensure_unchanged(capture, 'inventory.json', inventory_raw, _FORMAT.MAX_INVENTORY)
        ensure_unchanged(capture, 'plan.json', plan_raw, 65536)
        for item in selected:
            ensure_unchanged(item['fd'], 'manifest.json', item['manifestRaw'], 4096)
        mapped = _FORMAT.parse_block_map(files['block_map']['raw'])
        writable = _FORMAT.mounted_writable(files['mountinfo']['raw']) | _FORMAT.mounted_writable(files['app_mountinfo']['raw'])
        planned = {part['id'] for part in plan['partitions']}
        excluded = sorted(name for name, info in mapped.items()
                          if re.fullmatch(_FORMAT.PARTITION, name) and name not in planned and info['device'] in writable)
        result = {'version': 1, 'captureId': capture_id, 'inventorySha256': inventory_sha, 'planSha256': plan_sha,
                  'scope': 'planned_partitions', 'sourceConsistency': 'live_non_atomic', 'restoreTested': False,
                  'allPlannedPartitionsAssembled': not pending, 'pendingPartitions': pending,
                  'excludedWritablePartitions': excluded, 'excludedWritableBasis': 'inventory_direct_major_minor',
                  'images': records}
        save_index(images_fd, _FORMAT.encoded(result), previous)
        return result
    finally:
        for item in selected:
            os.close(item['fd'])
        for descriptor in (images_fd, chunks_fd, files_fd, capture):
            if descriptor is not None:
                os.close(descriptor)


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--partial', action='store_true')
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        result = assemble(args.capture, args.partial)
    except (ValueError, TypeError, OSError, _FORMAT.RequestRejected) as error:
        print('Assembly refused or failed: ' + str(error), file=sys.stderr)
        return 1
    print(json.dumps({'ok': True, 'captureId': result['captureId'], 'images': len(result['images']),
                      'bytes': sum(item['size'] for item in result['images']),
                      'allPlannedPartitionsAssembled': result['allPlannedPartitionsAssembled']}, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
