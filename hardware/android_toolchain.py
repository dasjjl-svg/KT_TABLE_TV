import json
import os
from pathlib import Path
import platform
import shutil


ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS_VERSION = '35.0.0'
NDK_VERSION = '29.0.14206865'


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
    value = settings()
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or value.get('sdkRoot') or ROOT / 'hardware/tooling/android/sdk').expanduser().absolute()
    java_home = os.environ.get('JAVA_HOME')
    jdk = Path(os.environ.get('HOME_PANEL_JDK_BIN') or (str(Path(java_home) / 'bin') if java_home else '')
               or value.get('jdkBin') or str(Path(shutil.which('javac') or '/usr/bin/javac').parent)).expanduser().absolute()
    tools = Path(os.environ.get('HOME_PANEL_BUILD_TOOLS') or sdk / 'build-tools' / BUILD_TOOLS_VERSION).expanduser().absolute()
    jar = Path(os.environ.get('HOME_PANEL_ANDROID_JAR') or sdk / 'platforms/android-22/android.jar').expanduser().absolute()
    d8 = Path(os.environ.get('HOME_PANEL_D8_JAR') or tools / 'lib/d8.jar').expanduser().absolute()
    signer = Path(os.environ.get('HOME_PANEL_APKSIGNER_JAR') or tools / 'lib/apksigner.jar').expanduser().absolute()
    ndk = Path(os.environ.get('ANDROID_NDK_HOME') or value.get('ndkRoot') or sdk / 'ndk' / NDK_VERSION).expanduser().absolute()
    host = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
    native = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    result = {'sdk': sdk, 'jdk': jdk, 'tools': tools, 'jar': jar, 'd8': d8, 'signer': signer, 'ndk': ndk, 'native': native}
    if required:
        if platform.system() != 'Darwin':
            raise ValueError('This distribution is tested for macOS hosts only')
        required_files = [jdk / name for name in ('java', 'javac', 'keytool')]
        required_files += [tools / 'aapt', tools / 'zipalign', jar, d8, signer]
        required_files += [native / name for name in ('clang', 'llvm-readelf', 'llvm-objdump')]
        if any(not path.is_file() for path in required_files):
            raise ValueError('Android tools missing: run python3 scripts/android_tools.py --install')
    return result
