import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile


SOURCE = Path(__file__).resolve().parents[1]
IGNORED = {'.git', 'runtime', 'backups', 'node_modules', 'dist', 'build', 'tooling', '__pycache__'}


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_OS') != 'Linux' or platform.system() != 'Linux':
        raise SystemExit('This isolated smoke build is only for the GitHub-hosted Linux CI job.')
    temporary_root = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    sdk = Path(os.environ['ANDROID_HOME']).resolve(strict=True)
    if not (sdk / 'platforms/android-22/android.jar').is_file():
        raise SystemExit('The CI Android API 22 package is missing.')
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='home-panel-android-', dir=temporary_root) as directory:
        project = Path(directory) / 'source'
        shutil.copytree(SOURCE, project, ignore=shutil.ignore_patterns(*IGNORED, '*.apk', '*.apk.sha256', '*.pyc'))
        commands = [
            ['node', 'scripts/setup.mjs', '--ha-url', 'http://ha.example.test:8123',
             '--host', '10.77.20.10', '--device', '10.77.20.20',
             '--room-one', 'Test Living', '--room-two', 'Test Bedroom'],
            ['python3', '-B', 'scripts/android_setup.py'],
            ['python3', '-B', 'hardware/panel-app/build.py'],
        ]
        for command in commands:
            subprocess.run(command, cwd=project, check=True, timeout=600)
        for name in ('SmartFactory.apk', 'HomePanel.apk'):
            artifact = project / 'hardware/panel-app' / name
            if not artifact.is_file() or not 1024 <= artifact.stat().st_size <= 2 * 1024 * 1024:
                raise SystemExit('Expected signed Android artifact was not produced.')
        print('Fresh Linux Android build and embedded policy/signature checks passed. Private test outputs are deleted; no APK is uploaded.')


if __name__ == '__main__':
    main()
