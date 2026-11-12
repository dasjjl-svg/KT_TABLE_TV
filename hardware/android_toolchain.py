import json
import os
from pathlib import Path
import platform
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS_VERSION = '35.0.0'
NDK_VERSION = '29.0.14206865'


def host_info(system=None, machine=None):
    system = platform.system() if system is None else system
    machine = (platform.machine() if machine is None else machine).lower()
    if system == 'Darwin' and machine in {'x86_64', 'amd64', 'arm64', 'aarch64'}:
        return {'repository': 'macosx', 'archive': 'mac', 'ndk': 'darwin-x86_64'}
    if system == 'Linux' and machine in {'x86_64', 'amd64'}:
        return {'repository': 'linux', 'archive': 'linux', 'ndk': 'linux-x86_64'}
    if system == 'Windows':
        raise ValueError('Use Ubuntu in WSL2 on Windows; native Windows is not supported.')
    raise ValueError('Android builds require macOS Intel/Apple Silicon or x86_64 Linux (including WSL2).')


def find_jdk():
    executable = shutil.which('javac')
    if not executable:
        return Path('/usr/bin')
    candidate = Path(executable)
    if platform.system() == 'Linux':
        candidate = candidate.resolve()
    return candidate.parent


def require_host_programs(host_clang):
    required = {'clang': host_clang, 'OpenSSL': Path('/usr/bin/openssl'), 'ksh': Path('/bin/ksh')}
    for name, path in required.items():
        if not path.is_file() or not os.access(path, os.X_OK):
            raise ValueError('Install ' + name + ' before building Android apps. Linux/WSL: sudo apt install clang openssl ksh')
    probe = subprocess.run(['/bin/ksh', '-c', 'print -rn -- home-panel-shell-check'],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
    if probe.returncode or probe.stdout != b'home-panel-shell-check' or probe.stderr:
        raise ValueError('A working /bin/ksh with print -rn support is required for Android shell compatibility tests.')


def settings():
    path = ROOT / 'runtime/android-toolchain.json'
    if not path.exists():
        return {}
    if path.is_symlink() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
        raise ValueError('Android toolchain settings must be private')
    value = json.loads(path.read_text())
    if set(value) != {'schema', 'sdkRoot', 'jdkBin', 'ndkRoot'} or value['schema'] != 1:
        raise ValueError('Android toolchain settings shape')
    if any(not isinstance(value[name], str) or not Path(value[name]).is_absolute() for name in ('sdkRoot', 'jdkBin', 'ndkRoot')):
        raise ValueError('Android toolchain paths must be absolute')
    return value


def paths(required=False):
    host = host_info()
    value = settings()
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or value.get('sdkRoot') or ROOT / 'hardware/tooling/android/sdk').expanduser().absolute()
    java_home = os.environ.get('JAVA_HOME')
    jdk = Path(os.environ.get('HOME_PANEL_JDK_BIN') or (str(Path(java_home) / 'bin') if java_home else '')
               or value.get('jdkBin') or str(find_jdk())).expanduser().absolute()
    tools = Path(os.environ.get('HOME_PANEL_BUILD_TOOLS') or sdk / 'build-tools' / BUILD_TOOLS_VERSION).expanduser().absolute()
    jar = Path(os.environ.get('HOME_PANEL_ANDROID_JAR') or sdk / 'platforms/android-22/android.jar').expanduser().absolute()
    d8 = Path(os.environ.get('HOME_PANEL_D8_JAR') or tools / 'lib/d8.jar').expanduser().absolute()
    signer = Path(os.environ.get('HOME_PANEL_APKSIGNER_JAR') or tools / 'lib/apksigner.jar').expanduser().absolute()
    ndk = Path(os.environ.get('ANDROID_NDK_HOME') or value.get('ndkRoot') or sdk / 'ndk' / NDK_VERSION).expanduser().absolute()
    native = ndk / 'toolchains/llvm/prebuilt' / host['ndk'] / 'bin'
    host_clang = Path(shutil.which('clang') or '/usr/bin/clang').absolute()
    result = {'sdk': sdk, 'jdk': jdk, 'tools': tools, 'jar': jar, 'd8': d8, 'signer': signer, 'ndk': ndk,
              'native': native, 'host_clang': host_clang}
    if required:
        require_host_programs(host_clang)
        required_files = [jdk / name for name in ('java', 'javac', 'keytool')]
        required_files += [tools / 'aapt', tools / 'zipalign', jar, d8, signer, host_clang, Path('/usr/bin/openssl')]
        required_files += [native / name for name in ('clang', 'llvm-readelf', 'llvm-objdump')]
        if any(not path.is_file() for path in required_files):
            raise ValueError('Android tools missing: run python3 scripts/android_tools.py --install')
    return result
