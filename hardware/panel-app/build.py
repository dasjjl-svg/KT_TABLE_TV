import hashlib
import argparse
import base64
import ipaddress
import importlib.util
import json
import os
import re
import shutil
import stat
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'hardware/panel-app'
_SPEC = importlib.util.spec_from_file_location('community_android_toolchain', ROOT / 'hardware/android_toolchain.py')
TOOLCHAIN = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(TOOLCHAIN)
PATHS = TOOLCHAIN.paths()
TOOLS, JAR, JDK = PATHS['tools'], PATHS['jar'], PATHS['jdk']
D8, APK_SIGNER = PATHS['d8'], PATHS['signer']
BUILD = APP / 'build'
SIGNING = ROOT / 'runtime/usb-probe-signing'
TRANSITION = ROOT / 'runtime/panel-signing-transition'
NATIVE_TOOLS = PATHS['native']
APPROVED_CERT_SHA256 = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'
APPROVED_SPKI_SHA256 = '3d3df7dc9bf26e02d4cd76256d41d45e41a4dedebe7feb95c40e3697681be8a7'


def run(args):
    subprocess.run([str(arg) for arg in args], check=True)


def private_bytes(path, maximum):
    if path.parent.resolve() != path.parent:
        raise ValueError('Signing directory must not use symbolic links')
    parent = path.parent.lstat()
    if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.getuid() or stat.S_IMODE(parent.st_mode) != 0o700:
        raise ValueError('Signing directory must be owner-only')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid()
                or stat.S_IMODE(before.st_mode) != 0o600 or before.st_nlink != 1
                or not 1 <= before.st_size <= maximum):
            raise ValueError('Signing input must be a private regular file')
        value = stream.read(maximum + 1)
        after = os.fstat(stream.fileno())
    fields = ('st_dev', 'st_ino', 'st_size', 'st_mode', 'st_nlink', 'st_mtime_ns', 'st_ctime_ns')
    if len(value) != before.st_size or any(getattr(before, key) != getattr(after, key) for key in fields):
        raise ValueError('Signing input changed while reading')
    return value


def checked_output(args, data=None):
    result = subprocess.run([str(arg) for arg in args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise ValueError('Signing material validation failed')
    return result.stdout


def require_digest(data, expected):
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError('Signing material digest mismatch')


def prepare_signing(with_approved):
    inputs = {
        'identity.json': private_bytes(SIGNING / 'identity.json', 4096),
        'diagnostic.p12': private_bytes(SIGNING / 'diagnostic.p12', 65536),
        'password': private_bytes(SIGNING / 'password', 4096),
        'original-public-key.der': private_bytes(TRANSITION / 'original-public-key.der', 4096),
        'platform-public-key.der': private_bytes(TRANSITION / 'platform-public-key.der', 4096),
        'platform.x509.pem': private_bytes(TRANSITION / 'platform.x509.pem', 16384),
    }
    identity = json.loads(inputs['identity.json'])
    if (set(identity) != {'schema', 'bootstrapCertificateSha256', 'bootstrapSpkiSha256'} or identity['schema'] != 1
            or any(not re.fullmatch(r'[0-9a-f]{64}', identity[name]) for name in ('bootstrapCertificateSha256', 'bootstrapSpkiSha256'))):
        raise ValueError('Run scripts/android_setup.py to create a private signing identity')
    require_digest(inputs['original-public-key.der'], identity['bootstrapSpkiSha256'])
    require_digest(inputs['platform-public-key.der'], APPROVED_SPKI_SHA256)
    certificate = checked_output(['/usr/bin/openssl', 'x509', '-outform', 'DER'], inputs['platform.x509.pem'])
    require_digest(certificate, APPROVED_CERT_SHA256)
    public_pem = checked_output(['/usr/bin/openssl', 'x509', '-pubkey', '-noout'], inputs['platform.x509.pem'])
    public_der = checked_output(['/usr/bin/openssl', 'pkey', '-pubin', '-outform', 'DER'], public_pem)
    if public_der != inputs['platform-public-key.der']:
        raise ValueError('Approved certificate public key mismatch')
    if with_approved:
        inputs['platform.pk8'] = private_bytes(TRANSITION / 'platform.pk8', 16384)
        key_public = checked_output(['/usr/bin/openssl', 'pkey', '-inform', 'DER', '-pubout', '-outform', 'DER'], inputs['platform.pk8'])
        if key_public != public_der:
            raise ValueError('Approved private key does not match certificate')
    directory = BUILD / 'signing'
    if directory.exists():
        if directory.is_symlink() or not directory.is_dir():
            raise ValueError('Invalid signing snapshot directory')
        shutil.rmtree(directory)
    directory.mkdir(mode=0o700)
    for name, data in inputs.items():
        destination = directory / name
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    original_certificate = checked_output([JDK / 'keytool', '-exportcert', '-alias', 'genie-probe',
                                           '-keystore', directory / 'diagnostic.p12', '-storetype', 'PKCS12',
                                           '-storepass:file', directory / 'password'])
    require_digest(original_certificate, identity['bootstrapCertificateSha256'])
    original_pem = checked_output(['/usr/bin/openssl', 'x509', '-inform', 'DER', '-pubkey', '-noout'], original_certificate)
    original_public = checked_output(['/usr/bin/openssl', 'pkey', '-pubin', '-outform', 'DER'], original_pem)
    if original_public != inputs['original-public-key.der']:
        raise ValueError('Original signing public key mismatch')
    return directory, inputs


def verify_signing_snapshot(directory, inputs, names):
    for name in names:
        if private_bytes(directory / name, len(inputs[name])) != inputs[name]:
            raise ValueError('Signing snapshot changed before use')


def add_upgrade_keys(root, package, version, inputs):
    if root.findall('key-sets'):
        raise ValueError('Base manifest must not contain signing transition keys')
    if package != 'dev.homepanel.community' or version < 39:
        return
    android = '{http://schemas.android.com/apk/res/android}'
    keys = ET.SubElement(root, 'key-sets')
    aliases = [('approved-panel', 'platform-public-key.der')]
    if version == 39:
        aliases.insert(0, ('original-panel', 'original-public-key.der'))
    for alias, name in aliases:
        keyset = ET.SubElement(keys, 'key-set', {android + 'name': alias})
        ET.SubElement(keyset, 'public-key', {android + 'name': alias + '-key',
                                            android + 'value': base64.b64encode(inputs[name]).decode('ascii')})
        ET.SubElement(keys, 'upgrade-key-set', {android + 'name': alias})


def offline_asset():
    template = (APP / 'assets/offline-panel.html').read_text()
    source = (ROOT / 'panel/src/offline-snapshot.mjs').read_text()
    if not source.startswith('export function createPanelSnapshotCache('):
        raise ValueError('Unexpected offline snapshot module')
    script = source.replace('export ', '', 1)
    if re.search(r'^\s*(?:import|export)\s', script, re.MULTILINE) or '</script' in script.lower():
        raise ValueError('Invalid offline snapshot module')
    if template.count('/* SNAPSHOT_SCRIPT */') != 1:
        raise ValueError('Invalid offline page template')
    page = template.replace('/* SNAPSHOT_SCRIPT */', script).encode('utf-8')
    if len(page) > 512 * 1024:
        raise ValueError('Offline page too large')
    return page


def build_native():
    directory = BUILD / 'native'
    directory.mkdir(mode=0o700, exist_ok=True)
    output = directory / 'libhome_panel_spi.so'
    native_test = directory / 'spi_identity_test'
    run(['/usr/bin/clang', '-std=c11', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
         '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
         APP / 'native/tests/spi_identity_test.c', '-o', native_test])
    run([native_test])
    run([NATIVE_TOOLS / 'clang', '--target=armv7a-linux-androideabi22', '-std=c11', '-O2',
         '-marm', '-shared', '-fPIC', '-fvisibility=hidden', '-fstack-protector-strong',
         '-Wall', '-Wextra', '-Werror', '-Wl,--no-undefined', '-Wl,-z,relro,-z,now',
         '-Wl,--hash-style=both', '-Wl,--pack-dyn-relocs=none', '-Wl,-soname,libhome_panel_spi.so',
         APP / 'native/spi_identity.c', '-o', output])
    output.chmod(0o600)
    header = subprocess.check_output([str(NATIVE_TOOLS / 'llvm-readelf'), '-h', str(output)], text=True)
    dynamic = subprocess.check_output([str(NATIVE_TOOLS / 'llvm-readelf'), '-d', str(output)], text=True)
    symbols = subprocess.check_output([str(NATIVE_TOOLS / 'llvm-readelf'), '--dyn-syms', str(output)], text=True)
    assembly = subprocess.check_output([str(NATIVE_TOOLS / 'llvm-objdump'), '-d', str(output)], text=True)
    if not re.search(r'Class:\s+ELF32', header) or not re.search(r'Machine:\s+ARM\b', header) or 'little endian' not in header:
        raise ValueError('Native helper must be ARM32 little endian')
    if 'TEXTREL' in dynamic or 'RELR' in dynamic or 'ANDROID_REL' in dynamic:
        raise ValueError('Native relocation format is not compatible with API 22')
    if any(name not in symbols for name in (
            'Java_net_quber_afactorymode_SpiIdentityProbe_runIdentity',
            'Java_net_quber_afactorymode_SpiBackupProbe_runBackup',
            'Java_net_quber_afactorymode_SpiReadProbe_runReadProbe')):
        raise ValueError('Native entry point missing')
    if not re.search(r'\bdsb\s+st\b', assembly) or not re.search(r'\bdsb\s+sy\b', assembly):
        raise ValueError('Native MMIO barriers missing')
    for dependency in re.findall(r'\(NEEDED\).*?\[([^]]+)\]', dynamic):
        if dependency not in {'libc.so', 'libm.so', 'libdl.so'}:
            raise ValueError('Unexpected native dependency')
    generated = BUILD / 'generated/SpiIdentityBuildConfig.java'
    generated.write_text('package net.quber.afactorymode;\nfinal class SpiIdentityBuildConfig {\n'
                         + 'static final String SHA256 = "' + hashlib.sha256(output.read_bytes()).hexdigest() + '";\n}\n')
    print('Native ARMv7/API22 library and MMIO barriers verified:', output.stat().st_size, 'bytes')
    return output, generated


def load_config(path):
    if path.is_symlink() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
        raise ValueError('Panel configuration must be owner-only')
    value = json.loads(path.read_text())
    if set(value) != {'version', 'origin', 'token'} or value['version'] != 1:
        raise ValueError('Invalid panel configuration')
    parsed = urlsplit(value['origin'])
    host = ipaddress.ip_address(parsed.hostname)
    if parsed.scheme != 'http' or host.version != 4 or not host.is_private or host.is_loopback:
        raise ValueError('Invalid panel origin')
    if parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment or parsed.port != 4181:
        raise ValueError('Invalid panel origin')
    if not isinstance(value['token'], str) or not re.fullmatch(r'[0-9a-f]{64}', value['token']):
        raise ValueError('Invalid panel credential')
    return value


def load_collector_config(path, panel_origin, expected_port=4182):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'r') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077 or info.st_nlink != 1 or info.st_size > 4096:
            raise ValueError('APK collector configuration must be a private regular file')
        value = json.load(stream)
    if expected_port not in (4182, 4183, 4184, 4185, 4186) or set(value) != {'version', 'host', 'device', 'port', 'token'} or type(value['version']) is not int or value['version'] != 1 or type(value['port']) is not int or value['port'] != expected_port:
        raise ValueError('Invalid APK collector configuration')
    networks = [ipaddress.ip_network(item) for item in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')]
    for key in ('host', 'device'):
        address = ipaddress.ip_address(value[key])
        if address.version != 4 or not any(address in network for network in networks):
            raise ValueError('APK collector requires private IPv4 addresses')
    if value['host'] != urlsplit(panel_origin).hostname or value['device'] == value['host']:
        raise ValueError('APK collector does not match the panel network')
    if not isinstance(value['token'], str) or not re.fullmatch(r'[0-9a-f]{64}', value['token']):
        raise ValueError('Invalid APK collector credential')
    return {'origin': 'http://' + value['host'] + ':' + str(expected_port), 'token': value['token']}


def main():
    parser = argparse.ArgumentParser()
    variants = parser.add_mutually_exclusive_group()
    variants.add_argument('--with-update-test', action='store_true')
    variants.add_argument('--with-approved-update', action='store_true')
    arguments = parser.parse_args()
    TOOLCHAIN.paths(required=True)
    android = '{http://schemas.android.com/apk/res/android}'
    base_version = int(ET.parse(APP / 'AndroidManifest.xml').getroot().get(android + 'versionCode'))
    if arguments.with_approved_update and base_version != 39:
        parser.error('--with-approved-update requires the version 39 bridge manifest')
    if arguments.with_update_test and base_version >= 39:
        parser.error('--with-update-test cannot sign approved versions; use --with-approved-update for the bridge')
    os.umask(0o077)
    config = load_config(ROOT / 'runtime/legacy/device.json')
    collector = load_collector_config(ROOT / 'runtime/kt-apk/config.json', config['origin'])
    home_collector = load_collector_config(ROOT / 'runtime/kt-home-apk/config.json', config['origin'], 4183)
    settings_collector = load_collector_config(ROOT / 'runtime/android-settings-apk/config.json', config['origin'], 4184)
    backup_collector = load_collector_config(ROOT / 'runtime/system-backup/config.json', config['origin'], 4185)
    maintenance = load_collector_config(ROOT / 'runtime/panel-maintenance/config.json', config['origin'], 4186)
    public_path = ROOT / 'runtime/panel-maintenance/public.der'
    descriptor = os.open(public_path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077 or info.st_nlink != 1 or not 256 <= info.st_size <= 1024:
            raise ValueError('Invalid maintenance public key')
        public_key = base64.b64encode(stream.read()).decode('ascii')
    BUILD.mkdir(parents=True, exist_ok=True, mode=0o700)
    BUILD.chmod(0o700)
    signing_directory, signing_inputs = prepare_signing(arguments.with_approved_update or base_version >= 40)
    recipient_identity = json.loads(signing_inputs['identity.json'])
    key, password = signing_directory / 'diagnostic.p12', signing_directory / 'password'
    for folder in ['classes', 'dex', 'generated', 'tests']:
        directory = BUILD / folder
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(mode=0o700)
    signing_config = BUILD / 'generated/RecipientSigningConfig.java'
    signing_config.write_text('package net.quber.afactorymode;\nfinal class RecipientSigningConfig {\n'
                              + 'static final String BOOTSTRAP_SHA256 = "' + recipient_identity['bootstrapCertificateSha256'] + '";\n}\n')
    config_source = BUILD / 'generated/PanelBuildConfig.java'
    config_source.write_text('package net.quber.afactorymode;\nfinal class PanelBuildConfig {\n'
                             + 'static final String ORIGIN = ' + json.dumps(config['origin']) + ';\n'
                             + 'static final String TOKEN = ' + json.dumps(config['token']) + ';\n'
                             + 'static final String COLLECTOR_ORIGIN = ' + json.dumps(collector['origin']) + ';\n'
                             + 'static final String COLLECTOR_TOKEN = ' + json.dumps(collector['token']) + ';\n'
                             + 'static final String HOME_COLLECTOR_ORIGIN = ' + json.dumps(home_collector['origin']) + ';\n'
                             + 'static final String HOME_COLLECTOR_TOKEN = ' + json.dumps(home_collector['token']) + ';\n'
                             + 'static final String SETTINGS_COLLECTOR_ORIGIN = ' + json.dumps(settings_collector['origin']) + ';\n'
                             + 'static final String SETTINGS_COLLECTOR_TOKEN = ' + json.dumps(settings_collector['token']) + ';\n'
                             + 'static final String BACKUP_ORIGIN = ' + json.dumps(backup_collector['origin']) + ';\n'
                             + 'static final String BACKUP_TOKEN = ' + json.dumps(backup_collector['token']) + ';\n'
                             + 'static final String MAINTENANCE_ORIGIN = ' + json.dumps(maintenance['origin']) + ';\n'
                             + 'static final String MAINTENANCE_TOKEN = ' + json.dumps(maintenance['token']) + ';\n'
                             + 'static final String MAINTENANCE_PUBLIC_KEY = ' + json.dumps(public_key) + ';\n}\n')
    policy = APP / 'src/net/quber/afactorymode/PanelPolicy.java'
    init_parser = APP / 'src/net/quber/afactorymode/DeviceInitParser.java'
    adb_protocol = APP / 'src/net/quber/afactorymode/AdbNetworkProtocol.java'
    inspection_protocol = APP / 'src/net/quber/afactorymode/ServiceInspectionProtocol.java'
    inspection_runner = APP / 'src/net/quber/afactorymode/ServiceInspectionRunner.java'
    backup_protocol = APP / 'src/net/quber/afactorymode/SystemBackupProtocol.java'
    backup_policy = APP / 'src/net/quber/afactorymode/SystemBackupPolicy.java'
    boot_media_policy = APP / 'src/net/quber/afactorymode/BootMediaPolicy.java'
    spi_controller_policy = APP / 'src/net/quber/afactorymode/SpiControllerPolicy.java'
    spi_identity_policy = APP / 'src/net/quber/afactorymode/SpiIdentityPolicy.java'
    spi_backup_policy = APP / 'src/net/quber/afactorymode/SpiBackupPolicy.java'
    spi_backup_frame = APP / 'src/net/quber/afactorymode/SpiBackupFrame.java'
    spi_read_policy = APP / 'src/net/quber/afactorymode/SpiReadPolicy.java'
    run([JDK / 'javac', '-encoding', 'UTF-8', '-d', BUILD / 'tests', signing_config, policy, APP / 'tests/PanelPolicyTest.java',
         init_parser, APP / 'tests/DeviceInitParserTest.java', adb_protocol, APP / 'tests/AdbNetworkProtocolTest.java',
         inspection_protocol, APP / 'tests/ServiceInspectionProtocolTest.java',
         inspection_runner, APP / 'tests/ServiceInspectionRunnerTest.java',
         backup_protocol, APP / 'tests/SystemBackupProtocolTest.java',
         backup_policy, APP / 'tests/SystemBackupPolicyTest.java',
         boot_media_policy, APP / 'tests/BootMediaPolicyTest.java',
         spi_controller_policy, APP / 'tests/SpiControllerPolicyTest.java',
         spi_identity_policy, APP / 'tests/SpiIdentityPolicyTest.java',
         spi_backup_policy, APP / 'tests/SpiBackupPolicyTest.java',
         spi_backup_frame, APP / 'tests/SpiBackupFrameTest.java',
         spi_read_policy, APP / 'tests/SpiReadPolicyTest.java',
         APP / 'src/net/quber/afactorymode/MaintenanceTrust.java', APP / 'tests/MaintenanceTrustTest.java',
         APP / 'src/net/quber/afactorymode/MaintenanceHomePolicy.java', APP / 'tests/MaintenanceHomePolicyTest.java',
         APP / 'src/net/quber/afactorymode/MaintenanceLegacyHomePolicy.java', APP / 'tests/MaintenanceLegacyHomePolicyTest.java',
         APP / 'src/net/quber/afactorymode/MaintenanceInstallProtocol.java', APP / 'tests/MaintenanceInstallProtocolTest.java',
         APP / 'src/net/quber/afactorymode/MaintenanceStagingPolicy.java', APP / 'tests/MaintenanceStagingPolicyTest.java',
         APP / 'src/net/quber/afactorymode/ReconnectScheduler.java', APP / 'tests/ReconnectSchedulerTest.java',
         APP / 'src/net/quber/afactorymode/MaintenanceDownload.java', APP / 'tests/MaintenanceDownloadTest.java',
         APP / 'src/net/quber/afactorymode/MaintenanceAttemptPolicy.java', APP / 'tests/MaintenanceAttemptPolicyTest.java',
         APP / 'src/net/quber/afactorymode/HomeSetupPolicy.java', APP / 'tests/HomeSetupPolicyTest.java',
         APP / 'src/net/quber/afactorymode/MaintenanceSignerPolicy.java', APP / 'tests/MaintenanceSignerPolicyTest.java',
         APP / 'src/net/quber/afactorymode/MaintenancePathPolicy.java', APP / 'tests/MaintenancePathPolicyTest.java'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.PanelPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.DeviceInitParserTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.AdbNetworkProtocolTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.ServiceInspectionProtocolTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.ServiceInspectionRunnerTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.SystemBackupProtocolTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.SystemBackupPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.BootMediaPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.SpiControllerPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.SpiIdentityPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.SpiBackupPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.SpiBackupFrameTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.SpiReadPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.MaintenanceTrustTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.MaintenanceHomePolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.MaintenanceLegacyHomePolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.MaintenanceInstallProtocolTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.MaintenanceStagingPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.ReconnectSchedulerTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.MaintenanceDownloadTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.MaintenanceAttemptPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.HomeSetupPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.MaintenanceSignerPolicyTest'])
    run([JDK / 'java', '-cp', BUILD / 'tests', 'net.quber.afactorymode.MaintenancePathPolicyTest', APP / 'src/net/quber/afactorymode'])
    native_library, native_config = build_native()
    run([JDK / 'javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', JAR,
         '-d', BUILD / 'classes', *sorted((APP / 'src').rglob('*.java')), config_source, signing_config, native_config])
    run([JDK / 'java', '-cp', D8, 'com.android.tools.r8.D8', '--release', '--min-api', '21',
         '--lib', JAR, '--output', BUILD / 'dex', *sorted((BUILD / 'classes').rglob('*.class'))])
    variants = [('SmartFactory.apk', 'net.quber.afactorymode', False), ('HomePanel.apk', 'dev.homepanel.community', False)]
    offline = offline_asset()
    if arguments.with_update_test:
        variants.append(('HomePanel-update-test.apk', 'dev.homepanel.community', True))
    if arguments.with_approved_update:
        variants.append(('HomePanel-approved.apk', 'dev.homepanel.community', True))
    ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
    for filename, package, update_test in variants:
        manifest = ET.parse(APP / 'AndroidManifest.xml')
        manifest.getroot().set('package', package)
        if update_test:
            version = int(manifest.getroot().get(android + 'versionCode')) + 1
            manifest.getroot().set(android + 'versionCode', str(version))
            current = manifest.getroot().get(android + 'versionName').split('.')
            manifest.getroot().set(android + 'versionName', current[0] + '.' + str(int(current[1]) + 1))
        version = int(manifest.getroot().get(android + 'versionCode'))
        approved = package == 'dev.homepanel.community' and version >= 40
        add_upgrade_keys(manifest.getroot(), package, version, signing_inputs)
        if package == 'net.quber.afactorymode':
            manifest.find('application').set(android + 'label', '홈 패널 설치')
            activity = manifest.find('application/activity')
            activity.set(android + 'label', '홈 패널 설치')
            for intent in list(activity.findall('intent-filter')):
                if any(category.get(android + 'name') == 'android.intent.category.HOME' for category in intent.findall('category')):
                    activity.remove(intent)
        manifest_directory = BUILD / 'manifests' / filename
        manifest_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        variant_manifest = manifest_directory / 'AndroidManifest.xml'
        manifest.write(variant_manifest, encoding='utf-8', xml_declaration=True)
        run([TOOLS / 'aapt', 'package', '-f', '-M', variant_manifest, '-I', JAR, '-F', BUILD / 'unsigned.apk'])
        with zipfile.ZipFile(BUILD / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as apk:
            apk.write(BUILD / 'dex/classes.dex', 'classes.dex')
            apk.write(native_library, 'lib/armeabi-v7a/libhome_panel_spi.so')
            apk.writestr('assets/offline-panel.html', offline)
        run([TOOLS / 'zipalign', '-f', '4', BUILD / 'unsigned.apk', BUILD / 'aligned.apk'])
        output = APP / filename
        if approved:
            verify_signing_snapshot(signing_directory, signing_inputs, ['platform.pk8', 'platform.x509.pem'])
            signer = ['--key', signing_directory / 'platform.pk8', '--cert', signing_directory / 'platform.x509.pem']
        else:
            verify_signing_snapshot(signing_directory, signing_inputs, ['diagnostic.p12', 'password'])
            signer = ['--ks', key, '--ks-key-alias', 'genie-probe', '--ks-pass', 'file:' + str(password)]
        run([JDK / 'java', '-jar', APK_SIGNER, 'sign', *signer,
             '--min-sdk-version', '21', '--v1-signing-enabled', 'true',
             '--v2-signing-enabled', 'false', '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false',
             '--out', output, BUILD / 'aligned.apk'])
        output.chmod(0o600)
        verification = checked_output([JDK / 'java', '-jar', APK_SIGNER, 'verify', '--verbose',
                                       '--print-certs', '--min-sdk-version', '21', output]).decode('utf-8')
        certificates = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)$', verification, re.MULTILINE)
        expected_certificate = APPROVED_CERT_SHA256 if approved else recipient_identity['bootstrapCertificateSha256']
        if certificates != [expected_certificate] or 'Verified using v1 scheme (JAR signing): true' not in verification:
            raise ValueError('Final APK signer or legacy signature mismatch')
        run([TOOLS / 'aapt', 'dump', 'badging', output])
        output.with_suffix('.apk.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest() + '  ' + filename + '\n')
        print('Panel APK compiled, signed and verified:', filename, output.stat().st_size, 'bytes')


if __name__ == '__main__':
    main()
