import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('community_android_tools', ROOT / 'hardware/android_toolchain.py')
TOOLS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOLS)
REPOSITORY = 'https://dl.google.com/android/repository/'
COMMAND_TOOLS = '19.0'


def child(element, name):
    return next((item for item in element if item.tag.rsplit('}', 1)[-1] == name), None)


def fetch(url, maximum):
    request = urllib.request.Request(url, headers={'User-Agent': 'HomePanelCommunity-Setup/1'})
    with urllib.request.urlopen(request, timeout=60) as response:
        if not response.url.startswith(REPOSITORY) or response.status != 200:
            raise ValueError('Unexpected Android download destination')
        raw = response.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError('Android download exceeds size limit')
    return raw


def command_tools_archive(repository, host_info=None):
    selected = TOOLS.host_info() if host_info is None else host_info
    tree = ET.fromstring(repository)
    packages = [item for item in tree if item.attrib.get('path') == 'cmdline-tools;' + COMMAND_TOOLS]
    if len(packages) != 1:
        raise ValueError('Pinned Android command-line tools not found')
    archives = child(packages[0], 'archives')
    if archives is None:
        raise ValueError('Android command-line tools archive metadata is missing')
    matches = []
    for archive in archives:
        host = child(archive, 'host-os')
        if host is None or host.text != selected['repository']:
            continue
        complete = child(archive, 'complete')
        if complete is None:
            raise ValueError('Incomplete Android archive metadata')
        url, checksum, size = [child(complete, name) for name in ('url', 'checksum', 'size')]
        if any(value is None for value in (url, checksum, size)):
            raise ValueError('Incomplete Android archive metadata')
        algorithm = checksum.attrib.get('type', 'sha1').lower().replace('-', '')
        if (not re.fullmatch(r'commandlinetools-' + re.escape(selected['archive']) + r'-[0-9]+_latest\.zip', url.text or '')
                or algorithm not in ('sha1', 'sha256')
                or not re.fullmatch(r'[0-9a-f]{' + str(40 if algorithm == 'sha1' else 64) + r'}', checksum.text or '')
                or not re.fullmatch(r'[0-9]+', size.text or '') or not 1 <= int(size.text) <= 256 * 1024 * 1024):
            raise ValueError('Unexpected Android archive metadata')
        matches.append((url.text, algorithm, checksum.text, int(size.text)))
    if len(matches) != 1:
        raise ValueError('Exactly one command-line tools archive for this host is required')
    return matches[0]


def unpack(archive, destination):
    with zipfile.ZipFile(archive) as source:
        entries, total, names = source.infolist(), 0, set()
        if len(entries) > 4096:
            raise ValueError('Android archive entry limit')
        for item in entries:
            parts = item.filename.rstrip('/').split('/')
            if (parts[0] != 'cmdline-tools' or any(value in ('', '.', '..') for value in parts)
                    or '\\' in item.filename or '\0' in item.filename or item.filename in names
                    or stat.S_ISLNK(item.external_attr >> 16) or item.flag_bits & 1):
                raise ValueError('Unsafe Android archive path')
            names.add(item.filename)
            total += item.file_size
            if total > 512 * 1024 * 1024:
                raise ValueError('Android archive expansion limit')
            target = destination.joinpath(*parts[1:])
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(item) as incoming, target.open('xb') as output:
                    shutil.copyfileobj(incoming, output)
                target.chmod(0o700 if '/bin/' in item.filename else 0o600)


def main():
    parser = argparse.ArgumentParser(description='Install official Android SDK tools on macOS or x86_64 Linux/WSL2. License acceptance remains interactive.')
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--sdk-root', type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        TOOLS.host_info()
    except ValueError as error:
        parser.error(str(error))
    current = TOOLS.paths()
    sdk = (args.sdk_root or current['sdk']).expanduser().absolute()
    jdk = current['jdk']
    try:
        TOOLS.require_host_programs(current['host_clang'])
    except ValueError as error:
        parser.error(str(error))
    if not (jdk / 'javac').is_file():
        parser.error('Install JDK 17 or newer, then set JAVA_HOME to that JDK.')
    probe = subprocess.run([str(jdk / 'javac'), '-version'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
    version = re.search(rb'javac ([0-9]+)', probe.stdout + probe.stderr)
    if probe.returncode or not version or int(version[1]) < 17:
        parser.error('A working JDK 17 or newer is required. Set JAVA_HOME before installing Android tools.')
    env = dict(os.environ, JAVA_HOME=str(jdk.parent))
    if args.install:
        sdk.mkdir(parents=True, exist_ok=True, mode=0o700)
        cli = sdk / 'cmdline-tools' / COMMAND_TOOLS
        manager = cli / 'bin/sdkmanager'
        if not manager.is_file():
            url, algorithm, checksum, expected_size = command_tools_archive(fetch(REPOSITORY + 'repository2-1.xml', 16 * 1024 * 1024))
            raw = fetch(REPOSITORY + url, expected_size)
            if len(raw) != expected_size or hashlib.new(algorithm, raw).hexdigest() != checksum:
                raise ValueError('Android archive checksum mismatch')
            cli.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix='.command-tools-', dir=cli.parent) as temporary:
                staging = Path(temporary)
                archive = staging / 'download.zip'
                archive.write_bytes(raw)
                expanded = staging / 'expanded'
                unpack(archive, expanded)
                if cli.exists():
                    raise ValueError('Command-line tools destination already exists but is incomplete')
                expanded.rename(cli)
        subprocess.run([str(manager), '--sdk_root=' + str(sdk), '--licenses'], env=env, check=True)
        subprocess.run([str(manager), '--sdk_root=' + str(sdk), '--install', 'platforms;android-22',
                        'build-tools;' + TOOLS.BUILD_TOOLS_VERSION, 'ndk;' + TOOLS.NDK_VERSION], env=env, check=True)
    runtime = ROOT / 'runtime'
    runtime.mkdir(mode=0o700, exist_ok=True)
    config = runtime / 'android-toolchain.json'
    value = {'schema': 1, 'sdkRoot': str(sdk), 'jdkBin': str(jdk), 'ndkRoot': str(sdk / 'ndk' / TOOLS.NDK_VERSION)}
    if config.is_symlink():
        raise ValueError('Toolchain config must not be a symlink')
    config.write_text(json.dumps(value, indent=2) + '\n')
    config.chmod(0o600)
    TOOLS.paths(required=True)
    print('Android API 22 / build-tools 35 / NDK toolchain verified.')


if __name__ == '__main__':
    main()
