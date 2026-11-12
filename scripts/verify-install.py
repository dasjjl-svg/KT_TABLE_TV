#!/usr/bin/env python3
import argparse
import contextlib
import fcntl
import importlib.util
import os
from pathlib import Path
import re
import secrets
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = Path('runtime/panel-maintenance')
APK = Path('hardware/panel-app/HomePanel.apk')
RECOVERY = None


class PrivateArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        self.print_usage(sys.stderr)
        self.exit(2, '인자를 확인해 주세요. 화면 확인 후 --confirm-screen만 지정합니다.\n')


def recovery():
    global RECOVERY
    if RECOVERY is None:
        spec = importlib.util.spec_from_file_location('community_install_recovery', ROOT / 'hardware/panel_recovery.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        RECOVERY = module
    return RECOVERY


def healthy_report(runtime, version, now):
    r = recovery()
    saved = r.M.BASE.strict_json(r.private_read(runtime / 'latest-report.json', r.M.MAX_REPORT))
    if (not isinstance(saved, dict) or set(saved) != {'receivedAt', 'report'}
            or type(saved['receivedAt']) is not int or not 0 <= now - saved['receivedAt'] <= 120):
        raise ValueError('fresh device report required')
    report = r.M.validate_report(saved['report'])
    if (report['package'] != r.M.PACKAGE or report['versionCode'] != version
            or report['home'] != 'panel' or report['rootStatus'] != 'ok'
            or report['status'] not in {'idle', 'installed'}):
        raise ValueError('installed managed panel is not ready')
    return saved


def signed_releases(runtime, version, checksum, size, scratch):
    r = recovery()
    public = scratch / 'release-public.der'
    r.private_write(public, r.private_read(runtime / 'public.der', 4096))
    directory = r.M.BASE.directory_fd(runtime / 'releases', private=True)
    records = []
    try:
        names = sorted(os.listdir(directory))
        if not 1 <= len(names) <= 4096:
            raise ValueError('published release history required')
        for name in names:
            if not re.fullmatch(r.M.IDENTIFIER + r'\.json', name):
                raise ValueError('unexpected release file')
            data = r.verify_release(r.M.read_private(directory, name, r.M.MAX_RELEASE), public, scratch)
            if data['id'] + '.json' != name:
                raise ValueError('release identity mismatch')
            records.append(data)
    finally:
        os.close(directory)
    try:
        current = r.private_read(runtime / 'current.json', r.M.MAX_RELEASE)
    except FileNotFoundError:
        current = None
    if current is not None:
        current_data = r.verify_release(current, public, scratch)
        if current_data not in records:
            raise ValueError('current release missing from signed history')
    matches = [data for data in records if data['versionCode'] == version]
    if not matches or {data['sha256'] for data in matches} != {checksum} or any(data['size'] != size for data in matches):
        raise ValueError('published version does not uniquely match APK')
    return sorted({data['id'] for data in matches})


@contextlib.contextmanager
def acceptance_lock(runtime):
    r = recovery()
    parent = r.M.BASE.directory_fd(runtime, private=True)
    descriptor = None
    try:
        descriptor = os.open('.verify-install.lock', os.O_RDWR | os.O_CREAT | r.M.NOFOLLOW, 0o600, dir_fd=parent)
        r.M.BASE.private_file_info(descriptor)
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield parent
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(parent)


def existing_catalog(project):
    r = recovery()
    try:
        value, checksum = r.read_catalog(project)
        return value, checksum
    except FileNotFoundError:
        return {'schema': 1, 'package': r.M.PACKAGE, 'artifacts': []}, None


def update_catalog(parent, value, previous):
    r = recovery()
    raw = r.M.encoded(value)
    if len(raw) > 65536:
        raise ValueError('catalog size limit')
    try:
        current = r.M.read_private(parent, 'known-good-catalog.json', 65536)
        checksum = r.digest(current)
    except FileNotFoundError:
        checksum = None
    if checksum != previous:
        raise ValueError('catalog changed during verification')
    if checksum == r.digest(raw):
        return
    temporary = '.catalog-' + secrets.token_hex(16)
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | r.M.NOFOLLOW, 0o600, dir_fd=parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, 'known-good-catalog.json', src_dir_fd=parent, dst_dir_fd=parent)
        os.fsync(parent)
    finally:
        try:
            os.unlink(temporary, dir_fd=parent)
        except FileNotFoundError:
            pass


def archive_verified(runtime, parent, name, raw, metadata, scratch):
    r = recovery()
    destination = runtime / name
    if name in os.listdir(parent):
        directory = r.M.BASE.directory_fd(destination, private=True)
        try:
            if set(os.listdir(directory)) != {'HomePanel.apk', 'verification.json'}:
                raise ValueError('existing archive shape')
            if r.M.read_private(directory, 'HomePanel.apk', r.M.MAX_APK) != raw:
                raise ValueError('existing archive APK differs')
            saved = r.M.BASE.strict_json(r.M.read_private(directory, 'verification.json', 8192))
            stable = set(metadata) - {'verifiedAt', 'reportReceivedAt', 'releaseIds'}
            if (not isinstance(saved, dict) or set(saved) != set(metadata)
                    or any(type(saved[key]) is not type(metadata[key]) or saved[key] != metadata[key] for key in stable)
                    or any(type(saved[key]) is not int or not 0 < saved[key] <= metadata['verifiedAt'] for key in ('verifiedAt', 'reportReceivedAt'))
                    or not 0 <= saved['verifiedAt'] - saved['reportReceivedAt'] <= 120
                    or not isinstance(saved['releaseIds'], list) or not saved['releaseIds']
                    or len(saved['releaseIds']) != len(set(saved['releaseIds']))
                    or any(identifier not in metadata['releaseIds'] for identifier in saved['releaseIds'])):
                raise ValueError('existing archive verification differs')
        finally:
            os.close(directory)
        return
    staging = scratch / 'archive'
    staging.mkdir(mode=0o700)
    r.private_write(staging / 'HomePanel.apk', raw)
    r.private_write(staging / 'verification.json', r.M.encoded(metadata))
    folder = r.M.BASE.directory_fd(staging, private=True)
    try:
        os.fsync(folder)
    finally:
        os.close(folder)
    if name in os.listdir(parent):
        raise ValueError('archive appeared during verification')
    os.rename(staging, name, dst_dir_fd=parent)
    os.fsync(parent)


def verify_install(confirm_screen, project=ROOT, now=None):
    if confirm_screen is not True:
        raise ValueError('physical screen confirmation required')
    r = recovery()
    project = Path(project).absolute()
    runtime = project / RUNTIME
    fixed_now = now
    now = int(time.time()) if now is None else now
    if type(now) is not int or now <= 0:
        raise ValueError('clock unavailable')
    with acceptance_lock(runtime) as parent, r.publication_lock(runtime):
        original = r.M.input_apk(project / APK)
        checksum = r.digest(original)
        entries = r.zip_entries(original)
        info = r.manifest_version(dict((entry.filename, raw) for entry, raw in entries)['AndroidManifest.xml'])
        version = info['versionCode']
        if version < r.M.PLATFORM_FIRST_VERSION:
            raise ValueError('managed version required')
        saved = healthy_report(runtime, version, now)
        fingerprint = r.credential_fingerprint(project)
        catalog, catalog_sha = existing_catalog(project)
        artifact = {'sha256': checksum, 'versionCode': version, 'path': 'prepared-v' + str(version) + '/HomePanel.apk',
                    'credentialFingerprint': fingerprint, 'verifiedOnDevice': True}
        for previous in catalog['artifacts']:
            if (previous['versionCode'] == version or previous['sha256'] == checksum) and previous != artifact:
                raise ValueError('catalog has a different approved artifact')
        if artifact not in catalog['artifacts']:
            if len(catalog['artifacts']) >= 100:
                raise ValueError('catalog is full')
            catalog['artifacts'].append(artifact)
        with tempfile.TemporaryDirectory(prefix='.verify-install-', dir=runtime) as temporary:
            scratch = Path(temporary)
            stage_apk = scratch / 'HomePanel.apk'
            r.private_write(stage_apk, original)
            if r.M.verify_apk(stage_apk) != version or r.private_read(stage_apk, r.M.MAX_APK) != original:
                raise ValueError('API 22 APK verification failed')
            releases = signed_releases(runtime, version, checksum, len(original), scratch)
            verified_at = int(time.time()) if fixed_now is None else now
            final_report = healthy_report(runtime, version, verified_at)
            for report in (saved['report'], final_report['report']):
                if report['releaseId'] and report['releaseId'] not in releases:
                    raise ValueError('reported release does not match APK')
            if r.credential_fingerprint(project) != fingerprint or r.M.input_apk(project / APK) != original:
                raise ValueError('installation inputs changed during verification')
            metadata = {'schema': 1, 'package': r.M.PACKAGE, 'versionCode': version, 'versionName': info['versionName'],
                        'sha256': checksum, 'size': len(original), 'credentialFingerprint': fingerprint,
                        'androidApi': 22, 'deviceVerified': True, 'physicalConfirmed': True,
                        'verifiedAt': verified_at, 'reportReceivedAt': final_report['receivedAt'], 'releaseIds': releases}
            archive_verified(runtime, parent, 'prepared-v' + str(version), original, metadata, scratch)
            update_catalog(parent, catalog, catalog_sha)
        r.read_catalog(project)
    return {'ok': True, 'versionCode': version, 'archived': artifact['path'], 'catalogUpdated': True}


def main(argv=None):
    parser = PrivateArgumentParser(description='USB 없이 부팅한 홈 패널의 설치 결과를 확인하고 복구용 정상본을 등록합니다.')
    parser.add_argument('--confirm-screen', action='store_true', required=True,
                        help='USB를 뺀 부팅 후 홈 패널·메뉴·대기 화면을 직접 확인했을 때만 지정하세요.')
    args = parser.parse_args(argv)
    try:
        result = verify_install(args.confirm_screen)
        print('설치 확인 완료: 빌드 ' + str(result['versionCode']) + '. 정상 APK와 복구 목록을 저장했습니다.')
        return 0
    except Exception:
        print('설치 확인 실패: 최신 관리 상태, 게시한 APK, 서명 도구와 비공개 파일 권한을 확인해 주세요.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
