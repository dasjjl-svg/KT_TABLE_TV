import argparse
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile


LABEL = 'local.kttabletv.community'
ROOT = Path(__file__).resolve().parent.parent


def run(*args, check=True):
    return subprocess.run(args, check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def main():
    parser = argparse.ArgumentParser(description='Mac 로그인 후 홈 패널 서버 자동 실행')
    parser.add_argument('action', choices=['install', 'status', 'uninstall'])
    args = parser.parse_args()
    if sys.platform != 'darwin':
        parser.error('이 도우미는 macOS용입니다.')
    folder = Path.home() / 'Library/LaunchAgents'
    target = folder / (LABEL + '.plist')
    service = 'gui/' + str(os.getuid()) + '/' + LABEL
    if args.action == 'status':
        result = run('launchctl', 'print', service, check=False)
        print('자동 실행 서비스가 등록되어 있습니다.' if result.returncode == 0 else '자동 실행 서비스가 등록되어 있지 않습니다.')
        return
    if target.is_symlink():
        raise ValueError('LaunchAgent 파일이 심볼릭 링크입니다.')
    if target.exists():
        old = plistlib.loads(target.read_bytes())
        if old.get('Label') != LABEL or old.get('WorkingDirectory') != str(ROOT):
            raise ValueError('다른 설치 폴더의 자동 실행 설정이 있습니다. 그 폴더에서 먼저 uninstall 하세요.')
    if args.action == 'uninstall':
        if target.exists():
            run('launchctl', 'bootout', service, check=False)
            target.unlink()
        print('이 설치의 자동 실행을 해제했습니다. 로컬 인증정보와 앱은 보존됩니다.')
        return
    node = shutil.which('node')
    if not node or not (ROOT / 'panel/dist/index.html').is_file() or not (ROOT / 'scripts/start.mjs').is_file():
        raise ValueError('Node.js 설치, 초기 설정과 패널 빌드를 먼저 완료하세요.')
    if not (ROOT / 'runtime/setup.json').is_file():
        raise ValueError('Setup.command로 초기 설정을 먼저 완료하세요.')
    folder.mkdir(parents=True, exist_ok=True)
    logs = ROOT / 'runtime/logs'
    logs.mkdir(parents=True, exist_ok=True, mode=0o700)
    logs.chmod(0o700)
    environment = {'PATH': str(Path(node).parent) + ':/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'}
    value = {'Label': LABEL, 'ProgramArguments': [node, str(ROOT / 'scripts/start.mjs')],
             'WorkingDirectory': str(ROOT), 'RunAtLoad': True, 'KeepAlive': True,
             'ThrottleInterval': 15, 'EnvironmentVariables': environment,
             'StandardOutPath': str(logs / 'service.log'), 'StandardErrorPath': str(logs / 'service-error.log')}
    descriptor, temp = tempfile.mkstemp(prefix='.' + LABEL + '-', dir=folder)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(plistlib.dumps(value)); stream.flush(); os.fsync(stream.fileno())
        os.replace(temp, target)
    finally:
        if os.path.exists(temp): os.unlink(temp)
    run('launchctl', 'bootout', service, check=False)
    run('launchctl', 'bootstrap', 'gui/' + str(os.getuid()), str(target))
    print('로그인 후 자동 실행을 등록했습니다. FileVault 잠금 해제는 재부팅 후 직접 해야 합니다.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print('자동 실행 설정 실패: ' + (str(error) if isinstance(error, ValueError) else type(error).__name__), file=sys.stderr)
        raise SystemExit(1)
