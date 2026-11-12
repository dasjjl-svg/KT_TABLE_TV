import argparse
import base64
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
UNIT = 'local.kttabletv.community.service'


def run(*args, check=True):
    return subprocess.run(args, check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def quote(value, command=False):
    value = str(value)
    if not value or any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise ValueError('경로에 제어 문자가 있습니다.')
    value = value.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%')
    if command:
        value = value.replace('$', '$$')
    return '"' + value + '"'


def marker(root):
    return '# HomePanelRoot=' + base64.b64encode(str(root).encode()).decode()


def render_unit(root, node, python):
    root_path = str(root)
    quote(root_path)
    if root_path != root_path.rstrip() or '\\' in root_path or '$' in str(node) or any(':' in str(Path(executable).parent) for executable in (node, python)):
        raise ValueError('자동 실행 경로에는 끝 공백·역슬래시를, Node.js 경로에는 달러 문자를 사용할 수 없습니다.')
    search = ':'.join(dict.fromkeys([str(Path(node).parent), str(Path(python).parent), '/usr/local/bin', '/usr/bin', '/bin']))
    return '\n'.join([
        '# Managed by KT_TABLE_TV linux-service.py', marker(root),
        '[Unit]', 'Description=CT1105-SB Home Assistant panel server', '',
        '[Service]', 'Type=simple',
        'WorkingDirectory=' + root_path.replace('%', '%%'),
        'ExecStart=' + quote(node) + ' ' + quote(root / 'scripts/start.mjs', command=True),
        'Environment=' + quote('PATH=' + search),
        'Restart=always', 'RestartSec=15', 'KillMode=control-group', 'TimeoutStopSec=10', 'UMask=0077', '',
        '[Install]', 'WantedBy=default.target', '',
    ])


def check_owned(target):
    if target.is_symlink():
        raise ValueError('서비스 파일이 심볼릭 링크입니다.')
    if target.exists():
        info = target.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_size > 16384:
            raise ValueError('서비스 파일 소유자 또는 형식이 올바르지 않습니다.')
        lines = target.read_text().splitlines()
        if len(lines) < 2 or lines[:2] != ['# Managed by KT_TABLE_TV linux-service.py', marker(ROOT)]:
            raise ValueError('다른 설치의 서비스 파일이 있습니다. 해당 설치에서 먼저 uninstall 하세요.')


def main():
    parser = argparse.ArgumentParser(description='Linux 사용자 로그인 후 홈 패널 서버 자동 실행')
    parser.add_argument('action', choices=['install', 'status', 'uninstall'])
    args = parser.parse_args()
    if sys.platform != 'linux' or not hasattr(os, 'getuid'):
        parser.error('이 도우미는 Linux 또는 WSL 2 Ubuntu 안에서 실행하세요.')
    if os.geteuid() == 0:
        raise ValueError('sudo 없이 일반 사용자 계정으로 실행하세요.')
    if not shutil.which('systemctl'):
        raise ValueError('systemd가 없습니다. Start.sh를 직접 실행하거나 WSL의 systemd를 먼저 설정하세요.')
    folder = Path.home() / '.config/systemd/user'
    target = folder / UNIT
    if args.action == 'status':
        result = run('systemctl', '--user', 'is-active', UNIT, check=False)
        print('자동 실행 서비스가 실행 중입니다.' if result.returncode == 0 else '자동 실행 서비스가 실행 중이지 않습니다.')
        return
    check_owned(target)
    if args.action == 'uninstall':
        if target.exists():
            run('systemctl', '--user', 'disable', '--now', UNIT)
            target.unlink()
            run('systemctl', '--user', 'daemon-reload')
        print('이 설치의 자동 실행을 해제했습니다. 로컬 인증정보와 앱은 보존됩니다.')
        return
    node, python = shutil.which('node'), shutil.which('python3')
    if not node or not python or not (ROOT / 'panel/dist/index.html').is_file() or not (ROOT / 'scripts/start.mjs').is_file():
        raise ValueError('Node.js·Python 설치, Setup.sh 설정과 웹 빌드를 먼저 완료하세요.')
    if not (ROOT / 'runtime/setup.json').is_file():
        raise ValueError('Setup.sh로 초기 설정을 먼저 완료하세요.')
    run('systemctl', '--user', 'show-environment')
    content = render_unit(ROOT, node, python)
    folder.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.' + UNIT + '-', dir=folder)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            stream.write(content); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    run('systemctl', '--user', 'daemon-reload')
    run('systemctl', '--user', 'enable', '--now', UNIT)
    run('systemctl', '--user', 'restart', UNIT)
    print('현재 사용자 로그인 후 자동 실행을 등록했습니다. WSL은 배포판이 실행 중이어야 합니다. Windows 부팅이나 WSL 종료 후 재시작을 자동 설정하지 않습니다.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        detail = str(error) if isinstance(error, ValueError) else type(error).__name__
        print('자동 실행 설정 실패: ' + detail, file=sys.stderr)
        raise SystemExit(1)
