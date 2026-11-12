import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location('assemble_system_backup', Path(__file__).resolve().parents[1] / 'assemble_system_backup.py')
assembler = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(assembler)
CAPTURE = '12' * 16
PART = 'mmcblk0p1'
BODY = b'a' * 1048576 + b'b' * 512
ENCODE = assembler._FORMAT.encoded


def private_write(path, raw):
    path.write_bytes(raw)
    path.chmod(0o600)


def file_record(name, raw):
    return {'id': name, 'source': assembler._FORMAT.FILES[name], 'status': 'ok',
            'data': base64.b64encode(raw).decode('ascii'), 'sha256': hashlib.sha256(raw).hexdigest()}


def fixture(root, second=False):
    capture = root / CAPTURE
    capture.mkdir(mode=0o700)
    files = capture / 'files'
    files.mkdir(mode=0o700)
    mounts = b'1 0 179:1 / /system ro - ext4 /dev/block/mmcblk0p1 ro\n2 0 179:3 / /data rw - ext4 /dev/block/mmcblk0p3 rw\n'
    entries = [file_record('block_map', b'mmcblk0|179:0|100000|0|0\nmmcblk0p1|179:1|2049|2048|0\nmmcblk0p2|179:2|1|4097|0\nmmcblk0p3|179:3|1024|4098|0\n'),
               file_record('mountinfo', mounts), file_record('app_mountinfo', mounts),
               file_record('init_rc', b'import /init.bigfish.rc\n')]
    inventory = ENCODE({'version': 1, 'model': 'CT1105_SB', 'appUid': 10050, 'files': entries})
    private_write(capture / 'inventory.json', inventory)
    for entry in entries:
        private_write(files / (entry['id'] + '.bin'), base64.b64decode(entry['data']))
    plan = {'version': 1, 'captureId': CAPTURE, 'inventorySha256': hashlib.sha256(inventory).hexdigest(),
            'partitions': [{'id': PART, 'device': '/dev/block/' + PART, 'size': len(BODY), 'chunkSize': 1048576}]}
    if second:
        plan['partitions'].append({'id': 'mmcblk0p2', 'device': '/dev/block/mmcblk0p2', 'size': 512, 'chunkSize': 1048576})
    private_write(capture / 'plan.json', ENCODE(plan))
    (capture / 'chunks').mkdir(mode=0o700)
    add_partition(capture, plan, plan['partitions'][0], BODY)
    return capture, plan


def add_partition(capture, plan, spec, body):
    parent = capture / 'chunks' / spec['id']
    parent.mkdir(mode=0o700)
    for index, offset in enumerate(range(0, len(body), 1048576)):
        chunk = body[offset:offset + 1048576]
        private_write(parent / (str(index).zfill(8) + '-' + hashlib.sha256(chunk).hexdigest() + '.bin'), chunk)
    manifest = {'version': 1, 'captureId': CAPTURE, 'id': spec['id'], 'device': spec['device'],
                'size': len(body), 'chunkSize': 1048576, 'sha256': hashlib.sha256(body).hexdigest(),
                'inventorySha256': plan['inventorySha256'], 'sourceConsistency': 'live_non_atomic',
                'transportVerified': True, 'restoreTested': False, 'mountedWritableAtInventory': False,
                'completedAt': '2026-11-13T01:00:00+00:00'}
    private_write(parent / 'manifest.json', ENCODE(manifest))


def snapshots(capture):
    return {str(path.relative_to(capture)): (path.stat().st_mtime_ns, hashlib.sha256(path.read_bytes()).hexdigest())
            for path in capture.rglob('*') if path.is_file() and 'images' not in path.relative_to(capture).parts}


class AssembleTests(unittest.TestCase):
    def test_complete_image_sidecar_index_permissions_and_source_immutability(self):
        with tempfile.TemporaryDirectory() as temporary:
            capture, _ = fixture(Path(temporary).resolve())
            before = snapshots(capture)
            result = assembler.assemble(capture, min_free=0)
            self.assertEqual(snapshots(capture), before)
            images = capture / 'images'
            image = images / (PART + '.img')
            self.assertEqual(image.read_bytes(), BODY)
            self.assertEqual((images / (PART + '.img.sha256')).read_text(), hashlib.sha256(BODY).hexdigest() + '  ' + PART + '.img\n')
            self.assertEqual(json.loads((images / 'index.json').read_bytes()), result)
            self.assertTrue(result['allPlannedPartitionsAssembled'])
            self.assertEqual(result['excludedWritablePartitions'], ['mmcblk0p3'])
            self.assertEqual(result['sourceConsistency'], 'live_non_atomic')
            self.assertFalse(result['restoreTested'])
            self.assertEqual(stat.S_IMODE(images.stat().st_mode), 0o700)
            for path in images.iterdir():
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
                self.assertEqual(path.stat().st_nlink, 1)
            original = image.stat().st_mtime_ns
            self.assertEqual(assembler.assemble(capture, min_free=0), result)
            self.assertEqual(image.stat().st_mtime_ns, original)

    def test_partial_skips_incomplete_and_later_extends_index(self):
        with tempfile.TemporaryDirectory() as temporary:
            capture, plan = fixture(Path(temporary).resolve(), second=True)
            with self.assertRaisesRegex(ValueError, 'not all planned'):
                assembler.assemble(capture, min_free=0)
            self.assertFalse((capture / 'images').exists())
            partial = assembler.assemble(capture, partial=True, min_free=0)
            self.assertEqual(partial['pendingPartitions'], ['mmcblk0p2'])
            self.assertFalse(partial['allPlannedPartitionsAssembled'])
            add_partition(capture, plan, plan['partitions'][1], b'z' * 512)
            complete = assembler.assemble(capture, min_free=0)
            self.assertTrue(complete['allPlannedPartitionsAssembled'])
            self.assertEqual(len(complete['images']), 2)
            self.assertEqual((capture / 'images/mmcblk0p2.img').read_bytes(), b'z' * 512)

    def test_changed_existing_image_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            capture, _ = fixture(Path(temporary).resolve())
            assembler.assemble(capture, min_free=0)
            path = capture / 'images' / (PART + '.img')
            with path.open('r+b') as stream:
                stream.write(b'!')
            before = path.read_bytes()
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                assembler.assemble(capture, min_free=0)
            self.assertEqual(path.read_bytes(), before)

    def test_corrupt_chunk_and_missing_chunk_do_not_publish_image(self):
        for corrupt in (True, False):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory() as temporary:
                capture, _ = fixture(Path(temporary).resolve())
                path = next((capture / 'chunks' / PART).glob('00000000-*.bin'))
                if corrupt:
                    with path.open('r+b') as stream:
                        stream.write(b'!')
                else:
                    path.unlink()
                with self.assertRaises(ValueError):
                    assembler.assemble(capture, min_free=0)
                images = capture / 'images'
                self.assertFalse((images / (PART + '.img')).exists())
                self.assertEqual(list(images.glob('.assembling-*')), [])
                self.assertFalse((images / 'index.json').exists())

    def test_manifest_inventory_plan_digest_mismatches_are_rejected(self):
        for target in ('manifest', 'inventory', 'plan'):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                capture, _ = fixture(Path(temporary).resolve())
                if target == 'manifest':
                    path = capture / 'chunks' / PART / 'manifest.json'
                    data = json.loads(path.read_bytes())
                    data['sha256'] = '0' * 64
                    private_write(path, ENCODE(data))
                elif target == 'inventory':
                    path = capture / 'inventory.json'
                    data = json.loads(path.read_bytes())
                    data['appUid'] += 1
                    private_write(path, ENCODE(data))
                else:
                    path = capture / 'plan.json'
                    data = json.loads(path.read_bytes())
                    data['partitions'][0]['size'] -= 512
                    private_write(path, ENCODE(data))
                with self.assertRaises(ValueError):
                    assembler.assemble(capture, min_free=0)
                self.assertFalse((capture / 'images' / (PART + '.img')).exists())

    def test_symlink_and_hardlink_sources_and_outputs_are_rejected(self):
        for mode in ('chunk_symlink', 'chunk_hardlink', 'images_symlink', 'capture_symlink'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                capture, _ = fixture(root)
                path = next((capture / 'chunks' / PART).glob('00000000-*.bin'))
                selected = capture
                if mode == 'chunk_symlink':
                    saved = root / 'outside.bin'
                    path.rename(saved)
                    path.symlink_to(saved)
                elif mode == 'chunk_hardlink':
                    os.link(path, root / 'other.bin')
                elif mode == 'images_symlink':
                    outside = root / 'outside'
                    outside.mkdir(mode=0o700)
                    (capture / 'images').symlink_to(outside, target_is_directory=True)
                else:
                    parent = root / 'link'
                    parent.symlink_to(root, target_is_directory=True)
                    selected = parent / CAPTURE
                with self.assertRaises((ValueError, OSError)):
                    assembler.assemble(selected, min_free=0)

    def test_low_space_leaves_no_image_or_temporary_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            capture, _ = fixture(Path(temporary).resolve())
            space = type('Space', (), {'f_bavail': 0, 'f_frsize': 4096})()
            with mock.patch.object(assembler.os, 'fstatvfs', return_value=space), self.assertRaisesRegex(ValueError, 'free space'):
                assembler.assemble(capture, min_free=0)
            self.assertEqual(list((capture / 'images').iterdir()), [])

    def test_changed_plan_or_disappeared_completed_source_cannot_rewrite_existing_index(self):
        for target in ('plan', 'manifest'):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                capture, _ = fixture(Path(temporary).resolve())
                assembler.assemble(capture, min_free=0)
                index = (capture / 'images/index.json').read_bytes()
                if target == 'plan':
                    path = capture / 'plan.json'
                    path.write_bytes(json.dumps(json.loads(path.read_bytes()), indent=2).encode())
                else:
                    (capture / 'chunks' / PART / 'manifest.json').unlink()
                with self.assertRaises(ValueError):
                    assembler.assemble(capture, partial=True, min_free=0)
                self.assertEqual((capture / 'images/index.json').read_bytes(), index)

    def test_extended_spi_inventory_is_verified_without_changing_image_assembly(self):
        with tempfile.TemporaryDirectory() as temporary:
            capture, plan = fixture(Path(temporary).resolve())
            spi = b's' * 1048576
            inventory = json.loads((capture / 'inventory.json').read_bytes())
            inventory['files'].append(file_record('spi_fastboot', spi))
            raw = ENCODE(inventory)
            private_write(capture / 'inventory.json', raw)
            private_write(capture / 'files/spi_fastboot.bin', spi)
            plan['inventorySha256'] = hashlib.sha256(raw).hexdigest()
            private_write(capture / 'plan.json', ENCODE(plan))
            path = capture / 'chunks' / PART / 'manifest.json'
            manifest = json.loads(path.read_bytes())
            manifest['inventorySha256'] = plan['inventorySha256']
            private_write(path, ENCODE(manifest))
            before = snapshots(capture)
            result = assembler.assemble(capture, min_free=0)
            self.assertEqual(result['inventorySha256'], plan['inventorySha256'])
            self.assertEqual((capture / 'images' / (PART + '.img')).read_bytes(), BODY)
            self.assertEqual(snapshots(capture), before)


if __name__ == '__main__':
    unittest.main()
