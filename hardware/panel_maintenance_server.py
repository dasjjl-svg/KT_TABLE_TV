import argparse
import base64
import fcntl
import hashlib
import importlib.util
import ipaddress
import json
import os
import re
import secrets
import signal
import socket
import stat
import subprocess
import sys
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
RUNTIME = ROOT / 'runtime/panel-maintenance'
BACKUP_CONFIG = ROOT / 'runtime/system-backup/config.json'
_TOOL_SPEC = importlib.util.spec_from_file_location('community_maintenance_tools', ROOT / 'hardware/android_toolchain.py')
TOOLCHAIN = importlib.util.module_from_spec(_TOOL_SPEC)
_TOOL_SPEC.loader.exec_module(TOOLCHAIN)
_TOOL_PATHS = TOOLCHAIN.paths()
TOOLS = _TOOL_PATHS['tools']
JAVA = _TOOL_PATHS['jdk'] / 'java'
SIGNER_JAR = _TOOL_PATHS['signer']
OPENSSL = '/usr/bin/openssl'
PORT = 4186
PACKAGE = 'dev.homepanel.community'
REPORT_PACKAGES = {PACKAGE, 'net.quber.afactorymode'}
PLATFORM_SIGNER = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'
PLATFORM_FIRST_VERSION = 40
MAX_APK = 2 * 1024 * 1024
MAX_REPORT = 32768
MAX_HOME_REPORT = 8192
MAX_STARTUP_REPORT = 8192
MAX_CRASH_REPORT = 8192
MAX_CRASH_EVIDENCE = 4096
MAX_RELEASE = 8192
MAX_RETURN_HOME = 1024
RETURN_HOME_TTL = 120
RETURN_HOME_SOURCES = frozenset({'web', 'ha', 'voice', 'cli'})
VOICE_WAKE_PREFIXES = ('기가지니야', '기가지니', '지니야', '헤이구글', '오케이구글', '시리야')
VOICE_SUFFIXES = frozenset({'', '해', '해줘', '해요', '해주세요', '하자', '해라', '실행', '실행해', '실행해줘', '이동'})
VOICE_IGNORED_PUNCTUATION = frozenset('.,!?~·\'"()[]<>-_/\\:;“”‘’')
STARTUP_PROPERTIES = {'sys.boot_completed', 'persist.sys.qb.enable', 'persist.sys.qb.flag',
                      'persist.sys.firstboot.flag', 'ro.boot.bootreason'}
STARTUP_PACKAGES = {'dev.homepanel.community', 'net.quber.afactorymode', 'com.kt.gigagenie.launcher'}
STARTUP_COMPONENTS = {'dev.homepanel.community/net.quber.afactorymode.AFactoryModeMainActivity',
                      'dev.homepanel.community/net.quber.afactorymode.MaintenanceService',
                      'dev.homepanel.community/net.quber.afactorymode.PanelBootReceiver',
                      'com.kt.gigagenie.launcher/com.kt.gigagenie.launcher.ui.GreetingActivity'}
CRASH_TAGS = {'data_app_crash', 'data_app_anr', 'data_app_native_crash'}
JAVA_IDENTIFIER = r'[A-Za-z_$][A-Za-z0-9_$]*'
QUALIFIED_IDENTIFIER = JAVA_IDENTIFIER + r'(?:\.' + JAVA_IDENTIFIER + r')*'
DIGEST = r'[0-9a-f]{64}'
IDENTIFIER = r'[0-9a-f]{32}'
_SPEC = importlib.util.spec_from_file_location('panel_receiver_base', ROOT / 'hardware/kt_apk_receiver.py')
BASE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(BASE)
NOFOLLOW = BASE.NOFOLLOW | os.O_NONBLOCK


def encoded(value):
    return json.dumps(value, separators=(',', ':'), sort_keys=True, allow_nan=False).encode('utf-8')


def read_private(parent, name, maximum):
    descriptor = os.open(name, os.O_RDONLY | NOFOLLOW, dir_fd=parent)
    try:
        before = BASE.private_file_info(descriptor)
        if not 0 <= before.st_size <= maximum:
            raise ValueError('file size')
        chunks, remaining = [], before.st_size + 1
        while remaining:
            chunk = os.read(descriptor, min(65536, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        raw = b''.join(chunks)
        after = BASE.private_file_info(descriptor, len(raw))
        if len(raw) != before.st_size or before.st_mtime_ns != after.st_mtime_ns:
            raise ValueError('file changed')
        return raw
    finally:
        os.close(descriptor)


def write_file(parent, name, raw, replace=False):
    temporary = '.pending-' + secrets.token_hex(16)
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW, 0o600, dir_fd=parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        if replace:
            try:
                read_private(parent, name, MAX_REPORT)
            except FileNotFoundError:
                pass
            os.replace(temporary, name, src_dir_fd=parent, dst_dir_fd=parent)
        else:
            try:
                os.link(temporary, name, src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
            except FileExistsError:
                if read_private(parent, name, len(raw)) != raw:
                    raise ValueError('immutable file conflict')
        os.fsync(parent)
    finally:
        try:
            os.unlink(temporary, dir_fd=parent)
        except FileNotFoundError:
            pass


def validate_config(data):
    data = BASE.validate_config(data)
    if data['port'] != PORT:
        raise ValueError('maintenance port')
    for key in ('host', 'device'):
        address = ipaddress.IPv4Address(data[key])
        if not address.is_private or address.is_loopback or address.is_link_local or address.is_reserved:
            raise ValueError('private LAN IPv4 required')
    if data['host'] == data['device']:
        raise ValueError('host and device must differ')
    return data


def load_config(runtime):
    parent = BASE.directory_fd(Path(runtime).absolute(), private=True)
    try:
        return validate_config(BASE.strict_json(read_private(parent, 'config.json', 4096)))
    finally:
        os.close(parent)


def command(args, data=None, descriptors=(), timeout=30):
    result = subprocess.run([str(arg) for arg in args], input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, pass_fds=descriptors, timeout=timeout,
                            check=False)
    if result.returncode or len(result.stdout) > 1024 * 1024:
        raise ValueError('verification tool failed')
    return result.stdout


def key_pair(runtime_fd):
    private = read_private(runtime_fd, 'signing.pem', 16384)
    public = read_private(runtime_fd, 'public.der', 4096)
    derived = command([OPENSSL, 'pkey', '-pubout', '-outform', 'DER'], private)
    if not secrets.compare_digest(derived, public):
        raise ValueError('signing key mismatch')
    description = command([OPENSSL, 'pkey', '-pubin', '-inform', 'DER', '-text', '-noout'], public)
    match = re.search(rb'Public-Key:\s*\((\d+) bit\)', description)
    if not match or not 2048 <= int(match[1]) <= 8192 or b'Modulus:' not in description:
        raise ValueError('RSA key required')
    return private, public


def initialize(runtime=RUNTIME, backup_config=BACKUP_CONFIG):
    runtime = Path(runtime).absolute()
    config = BASE.load_config(Path(backup_config).absolute())
    config.update(port=PORT, token=secrets.token_hex(32))
    validate_config(config)
    directory = BASE.directory_fd(runtime, create=True, private=True)
    try:
        existing = set(os.listdir(directory))
        if {'config.json', 'signing.pem', 'public.der'} <= existing:
            validate_config(BASE.strict_json(read_private(directory, 'config.json', 4096)))
            key_pair(directory)
            ensure_ha_return_config(directory)
            return {'ok': True, 'initialized': True, 'created': False}
        if existing:
            raise ValueError('initialization requires empty private directory')
        private = command([OPENSSL, 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048'])
        public = command([OPENSSL, 'pkey', '-pubout', '-outform', 'DER'], private)
        write_file(directory, 'signing.pem', private)
        write_file(directory, 'public.der', public)
        write_file(directory, 'config.json', encoded(config))
        key_pair(directory)
        ensure_ha_return_config(directory)
        return {'ok': True, 'initialized': True, 'created': True}
    finally:
        os.close(directory)


def matches_voice_return_command(raw):
    if not isinstance(raw, str) or not raw or len(raw) > 96:
        return False
    chars = []
    for ch in raw:
        code = ord(ch)
        if (code < 32 and ch not in ('\n', '\r', '\t')) or code == 127:
            return False
        if ch.isspace() or ch in VOICE_IGNORED_PUNCTUATION:
            continue
        chars.append(ch.lower())
    text = ''.join(chars)
    if not text or len(text) > 24:
        return False
    for prefix in VOICE_WAKE_PREFIXES:
        if text.startswith(prefix):
            text = text[len(prefix):]
            break
    if not text.startswith('홈패널로복귀'):
        return False
    return text[len('홈패널로복귀'):] in VOICE_SUFFIXES


def validate_ha_return_config(data):
    if not isinstance(data, dict) or set(data) != {'version', 'token'}:
        raise ValueError('ha return config shape')
    if type(data['version']) is not int or data['version'] != 1:
        raise ValueError('ha return config version')
    if not isinstance(data['token'], str) or re.fullmatch(DIGEST, data['token']) is None:
        raise ValueError('ha return config token')
    return dict(data)


def ensure_ha_return_config(directory):
    try:
        return validate_ha_return_config(BASE.strict_json(read_private(directory, 'ha-return.json', MAX_RETURN_HOME)))
    except FileNotFoundError:
        value = {'version': 1, 'token': secrets.token_hex(32)}
        write_file(directory, 'ha-return.json', encoded(value))
        return value


def validate_return_home_record(data):
    keys = {'schema', 'action', 'id', 'source', 'requestedAt', 'expiresAt'}
    if not isinstance(data, dict) or set(data) != keys:
        raise ValueError('return home shape')
    if type(data['schema']) is not int or data['schema'] != 1 or data['action'] != 'return_home':
        raise ValueError('return home target')
    if not isinstance(data['id'], str) or re.fullmatch(IDENTIFIER, data['id']) is None:
        raise ValueError('return home id')
    if not isinstance(data['source'], str) or data['source'] not in RETURN_HOME_SOURCES:
        raise ValueError('return home source')
    for field in ('requestedAt', 'expiresAt'):
        if type(data[field]) is not int or not 1 <= data[field] <= 4102444800:
            raise ValueError('return home timestamp')
    if data['expiresAt'] <= data['requestedAt'] or data['expiresAt'] - data['requestedAt'] > 600:
        raise ValueError('return home ttl')
    return data


def validate_return_home_request(data, allowed_sources, default_source):
    if not isinstance(data, dict) or not set(data) <= {'source', 'command'}:
        raise ValueError('return request shape')
    command_text = data.get('command')
    if 'command' in data and not matches_voice_return_command(command_text):
        raise ValueError('return voice command mismatch')
    inferred = 'voice' if 'command' in data and 'voice' in allowed_sources else default_source
    source = data.get('source', inferred)
    if not isinstance(source, str) or source not in allowed_sources:
        raise ValueError('return request source')
    return source, command_text


def validate_return_home_ack(data):
    if not isinstance(data, dict) or set(data) != {'schema', 'id'}:
        raise ValueError('return ack shape')
    if type(data['schema']) is not int or data['schema'] != 1:
        raise ValueError('return ack schema')
    if not isinstance(data['id'], str) or re.fullmatch(IDENTIFIER, data['id']) is None:
        raise ValueError('return ack id')
    return data['id']


def is_private_lan_or_loopback(address):
    try:
        parsed = ipaddress.IPv4Address(address)
    except ipaddress.AddressValueError:
        return False
    if (str(parsed) != address or parsed.is_unspecified or parsed.is_multicast
            or parsed.is_link_local or parsed.is_reserved):
        return False
    return parsed.is_private or parsed.is_loopback


def validate_payload(data):
    keys = {'schema', 'action', 'id', 'package', 'versionCode', 'size', 'sha256', 'expiresAt'}
    if not isinstance(data, dict) or set(data) != keys:
        raise ValueError('release shape')
    if type(data['schema']) is not int or data['schema'] != 1 or data['action'] != 'update' or data['package'] != PACKAGE:
        raise ValueError('release target')
    if not isinstance(data['id'], str) or re.fullmatch(IDENTIFIER, data['id']) is None:
        raise ValueError('release id')
    if type(data['versionCode']) is not int or not 1 <= data['versionCode'] <= 2147483647:
        raise ValueError('release version')
    if type(data['size']) is not int or not 4 <= data['size'] <= MAX_APK:
        raise ValueError('release size')
    if not isinstance(data['sha256'], str) or re.fullmatch(DIGEST, data['sha256']) is None:
        raise ValueError('release hash')
    if type(data['expiresAt']) is not int or not 1 <= data['expiresAt'] <= 4102444800:
        raise ValueError('release expiry')
    return data


def validate_report(data):
    keys = {'schema', 'versionCode', 'package', 'status', 'reason', 'releaseId', 'home', 'rootStatus'}
    if not isinstance(data, dict) or set(data) != keys:
        raise ValueError('report shape')
    if type(data['schema']) is not int or data['schema'] != 1:
        raise ValueError('report schema')
    if type(data['versionCode']) is not int or not 1 <= data['versionCode'] <= 2147483647:
        raise ValueError('report version')
    if not isinstance(data['package'], str) or data['package'] not in REPORT_PACKAGES:
        raise ValueError('report package')
    for key, choices in (
            ('status', {'idle', 'checking', 'downloading', 'accepted', 'installed', 'error'}),
            ('home', {'unknown', 'panel', 'other'}), ('rootStatus', {'unknown', 'ok', 'unavailable'})):
        if not isinstance(data[key], str) or data[key] not in choices:
            raise ValueError('report state')
    if not isinstance(data['reason'], str) or re.fullmatch(r'[a-z0-9_]{1,120}', data['reason']) is None:
        raise ValueError('report reason')
    if not isinstance(data['releaseId'], str) or re.fullmatch(r'(?:' + IDENTIFIER + r')?', data['releaseId']) is None:
        raise ValueError('report release')
    return data


def validate_home_report(data):
    if not isinstance(data, dict) or set(data) != {'schema', 'package', 'versionCode', 'home'}:
        raise ValueError('home report shape')
    if type(data['schema']) is not int or data['schema'] != 1 or data['package'] != PACKAGE:
        raise ValueError('home report target')
    if type(data['versionCode']) is not int or not 1 <= data['versionCode'] <= 2147483647:
        raise ValueError('home report version')
    home = data['home']
    if not isinstance(home, dict) or set(home) != {'resolved', 'greetingEnabled', 'candidates', 'preferences'}:
        raise ValueError('home evidence shape')
    BASE.bounded_text(home['resolved'], 256)
    if type(home['greetingEnabled']) is not int or not -1 <= home['greetingEnabled'] <= 4:
        raise ValueError('home greeting state')
    if not isinstance(home['candidates'], list) or len(home['candidates']) > 16:
        raise ValueError('home candidates')
    for component in home['candidates']:
        BASE.bounded_text(component, 256)
    if not isinstance(home['preferences'], list) or len(home['preferences']) > 16:
        raise ValueError('home preferences')
    for preference in home['preferences']:
        if not isinstance(preference, dict) or set(preference) != {'component', 'filter'}:
            raise ValueError('home preference shape')
        BASE.bounded_text(preference['component'], 256)
        BASE.bounded_text(preference['filter'], 2048, lines=True)
    if len(encoded(data)) > MAX_HOME_REPORT:
        raise ValueError('home report size')
    return data


def bounded_integer(value, minimum=-1, maximum=2147483647):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError('evidence integer')


def bounded_strings(value, maximum, length):
    if not isinstance(value, list) or len(value) > maximum:
        raise ValueError('evidence string list')
    for item in value:
        BASE.bounded_text(item, length)


def validate_startup_report(data):
    if not isinstance(data, dict) or set(data) != {'schema', 'package', 'versionCode', 'currentPrefs', 'startup'}:
        raise ValueError('startup report shape')
    if type(data['schema']) is not int or data['schema'] != 1 or data['package'] != PACKAGE:
        raise ValueError('startup report target')
    bounded_integer(data['versionCode'], 1)
    preferences = data['currentPrefs']
    if not isinstance(preferences, dict) or set(preferences) != {'enabled', 'homeAttempted', 'homeConfigurationVersion', 'status', 'reason'}:
        raise ValueError('startup preferences shape')
    if type(preferences['enabled']) is not bool or type(preferences['homeAttempted']) is not bool:
        raise ValueError('startup preference booleans')
    bounded_integer(preferences['homeConfigurationVersion'])
    for name in ('status', 'reason'):
        BASE.bounded_text(preferences[name], 120)
    startup = data['startup']
    if not isinstance(startup, dict) or set(startup) != {'uptimeMs', 'bootId', 'properties', 'packages', 'components',
                                                      'topActivities', 'factoryMarker', 'serviceComponents', 'error'}:
        raise ValueError('startup evidence shape')
    bounded_integer(startup['uptimeMs'], 0, 9223372036854775807)
    BASE.bounded_text(startup['bootId'], 64)
    BASE.bounded_text(startup['error'], 120)
    properties = startup['properties']
    if not isinstance(properties, dict) or set(properties) != STARTUP_PROPERTIES:
        raise ValueError('startup properties')
    for value in properties.values():
        BASE.bounded_text(value, 128)
    packages = startup['packages']
    if not isinstance(packages, list) or len(packages) > 3:
        raise ValueError('startup packages')
    seen = set()
    for package in packages:
        original_keys = {'name', 'installed', 'versionCode', 'firstInstallTime', 'lastUpdateTime', 'enabledState', 'sourceDir'}
        extended_keys = original_keys | {'flags', 'bootPermissionGranted'}
        if not isinstance(package, dict) or set(package) not in (original_keys, extended_keys):
            raise ValueError('startup package shape')
        name = package['name']
        if not isinstance(name, str) or name not in STARTUP_PACKAGES or name in seen:
            raise ValueError('startup package name')
        seen.add(name)
        if type(package['installed']) is not bool:
            raise ValueError('startup installed boolean')
        bounded_integer(package['versionCode'])
        for field in ('firstInstallTime', 'lastUpdateTime'):
            bounded_integer(package[field], -1, 9223372036854775807)
        bounded_integer(package['enabledState'], -1, 4)
        BASE.bounded_text(package['sourceDir'], 256)
        if 'flags' in package:
            bounded_integer(package['flags'], -2147483648)
            if type(package['bootPermissionGranted']) is not bool:
                raise ValueError('startup boot permission')
    components = startup['components']
    if not isinstance(components, list) or len(components) > 4:
        raise ValueError('startup components')
    seen = set()
    for component in components:
        if not isinstance(component, dict) or set(component) != {'name', 'state'}:
            raise ValueError('startup component shape')
        name = component['name']
        if not isinstance(name, str) or name not in STARTUP_COMPONENTS or name in seen:
            raise ValueError('startup component name')
        seen.add(name)
        bounded_integer(component['state'], -1, 4)
    bounded_strings(startup['topActivities'], 5, 256)
    bounded_strings(startup['serviceComponents'], 16, 256)
    marker = startup['factoryMarker']
    if not isinstance(marker, dict) or set(marker) != {'exists', 'uid', 'mode', 'size'}:
        raise ValueError('startup marker shape')
    if type(marker['exists']) is not bool:
        raise ValueError('startup marker boolean')
    bounded_integer(marker['uid'])
    bounded_integer(marker['mode'], -1, 65535)
    bounded_integer(marker['size'], -1, 9223372036854775807)
    if len(encoded(data)) > MAX_STARTUP_REPORT:
        raise ValueError('startup report size')
    return data


def crash_identifier(value, pattern):
    if not isinstance(value, str) or len(value) > 160 or re.fullmatch(pattern, value) is None:
        raise ValueError('crash identifier')


def validate_crash_report(data):
    if not isinstance(data, dict) or set(data) != {'schema', 'package', 'versionCode', 'crash'}:
        raise ValueError('crash report shape')
    if type(data['schema']) is not int or data['schema'] != 1 or data['package'] != PACKAGE:
        raise ValueError('crash report target')
    bounded_integer(data['versionCode'], 1)
    crash = data['crash']
    if not isinstance(crash, dict) or set(crash) != {'entries', 'error'}:
        raise ValueError('crash evidence shape')
    BASE.bounded_text(crash['error'], 120)
    entries = crash['entries']
    if not isinstance(entries, list) or len(entries) > 4:
        raise ValueError('crash entries')
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {'timeMs', 'package', 'tag', 'exception', 'frames'}:
            raise ValueError('crash entry shape')
        bounded_integer(entry['timeMs'], 0, 9223372036854775807)
        if not isinstance(entry['package'], str) or entry['package'] not in REPORT_PACKAGES:
            raise ValueError('crash entry package')
        if not isinstance(entry['tag'], str) or entry['tag'] not in CRASH_TAGS:
            raise ValueError('crash tag')
        crash_identifier(entry['exception'], QUALIFIED_IDENTIFIER)
        frames = entry['frames']
        if not isinstance(frames, list) or len(frames) > 12:
            raise ValueError('crash frames')
        for frame in frames:
            if not isinstance(frame, dict) or set(frame) != {'className', 'methodName', 'fileName', 'lineNumber'}:
                raise ValueError('crash frame shape')
            crash_identifier(frame['className'], QUALIFIED_IDENTIFIER)
            crash_identifier(frame['methodName'], r'(?:' + JAVA_IDENTIFIER + r'|<init>|<clinit>)')
            crash_identifier(frame['fileName'], r'(?:SourceFile|unknown|[A-Za-z0-9_$][A-Za-z0-9_$.-]*\.(?:java|kt|scala))')
            bounded_integer(frame['lineNumber'], -2)
    if len(encoded(crash)) > MAX_CRASH_EVIDENCE or len(encoded(data)) > MAX_CRASH_REPORT:
        raise ValueError('crash report size')
    return data


def bootstrap_signer():
    directory = BASE.directory_fd(ROOT / 'runtime/usb-probe-signing', private=True)
    try:
        identity = BASE.strict_json(read_private(directory, 'identity.json', 4096))
        value = identity.get('bootstrapCertificateSha256')
        if identity.get('schema') != 1 or not isinstance(value, str) or re.fullmatch(DIGEST, value) is None:
            raise ValueError('bootstrap signer identity')
        return value
    finally:
        os.close(directory)


def verify_apk(path):
    badging = command([TOOLS / 'aapt', 'dump', 'badging', path]).decode('utf-8')
    package = re.findall(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.M)
    if len(package) != 1 or package[0][0] != PACKAGE:
        raise ValueError('APK package')
    version = int(package[0][1])
    sdk = re.findall(r"^sdkVersion:'([0-9]+)'$", badging, re.M)
    maximum = re.findall(r"^maxSdkVersion:'([0-9]+)'$", badging, re.M)
    if (not 1 <= version <= 2147483647 or len(sdk) != 1 or not 1 <= int(sdk[0]) <= 22
            or len(maximum) > 1 or (maximum and int(maximum[0]) < 22)):
        raise ValueError('APK version or minimum SDK')
    signed = command([JAVA, '-jar', SIGNER_JAR, 'verify', '--verbose', '--print-certs',
                      '--min-sdk-version', '22', '--max-sdk-version', '22', path])
    certificates = re.findall(rb'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$', signed, re.M)
    expected = PLATFORM_SIGNER if version >= PLATFORM_FIRST_VERSION else bootstrap_signer()
    if certificates != [expected.encode('ascii')] or b'Verified using v1 scheme (JAR signing): true' not in signed:
        raise ValueError('APK signer or Android 22 verification')
    return version


def input_apk(path):
    path = Path(path).absolute()
    parent = BASE.directory_fd(path.parent)
    descriptor = None
    try:
        descriptor = os.open(path.name, os.O_RDONLY | NOFOLLOW, dir_fd=parent)
        before = os.fstat(descriptor)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid() or before.st_nlink != 1
                or stat.S_IMODE(before.st_mode) & 0o022 or not 4 <= before.st_size <= MAX_APK):
            raise ValueError('owned APK required')
        with os.fdopen(descriptor, 'rb') as stream:
            descriptor = None
            raw = stream.read(MAX_APK + 1)
            after = os.fstat(stream.fileno())
        if len(raw) != before.st_size or before.st_mtime_ns != after.st_mtime_ns or not raw.startswith(b'PK\x03\x04'):
            raise ValueError('APK changed or invalid')
        return raw
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(parent)


def b64(raw):
    return base64.b64encode(raw).decode('ascii')


def unb64(value, maximum):
    if not isinstance(value, str) or len(value) > 4 * ((maximum + 2) // 3):
        raise ValueError('base64 size')
    raw = base64.b64decode(value.encode('ascii'), validate=True)
    if len(raw) > maximum or b64(raw) != value:
        raise ValueError('base64 encoding')
    return raw


class MaintenanceStore:
    def __init__(self, runtime):
        self.runtime = Path(runtime).absolute()
        self.root = self.artifacts = self.releases = None
        self.lock = threading.Lock()
        self.errors = 0
        try:
            self.root = BASE.directory_fd(self.runtime, private=True)
            key_pair(self.root)
            self._ha_return_token = ensure_ha_return_config(self.root)['token']
            self.artifacts = BASE.private_child(self.root, 'artifacts')
            self.releases = BASE.private_child(self.root, 'releases')
        except Exception:
            self.close()
            raise

    def close(self):
        for name in ('artifacts', 'releases', 'root'):
            descriptor = getattr(self, name)
            if descriptor is not None:
                os.close(descriptor)
                setattr(self, name, None)

    def error(self):
        with self.lock:
            self.errors += 1

    def verify_envelope(self, raw):
        envelope = BASE.strict_json(raw)
        if not isinstance(envelope, dict) or set(envelope) != {'payload', 'signature'}:
            raise ValueError('release envelope')
        payload = unb64(envelope['payload'], 4096)
        signature = unb64(envelope['signature'], 1024)
        data = validate_payload(BASE.strict_json(payload))
        temporary = '.verify-' + secrets.token_hex(16)
        write_file(self.root, temporary, signature)
        key_fd = signature_fd = None
        try:
            key_fd = os.open('public.der', os.O_RDONLY | NOFOLLOW, dir_fd=self.root)
            BASE.private_file_info(key_fd)
            signature_fd = os.open(temporary, os.O_RDONLY | NOFOLLOW, dir_fd=self.root)
            command([OPENSSL, 'dgst', '-sha256', '-verify', '/dev/fd/' + str(key_fd), '-keyform', 'DER',
                     '-signature', '/dev/fd/' + str(signature_fd)], payload, (key_fd, signature_fd))
        finally:
            for descriptor in (key_fd, signature_fd):
                if descriptor is not None:
                    os.close(descriptor)
            os.unlink(temporary, dir_fd=self.root)
        return envelope, data

    def current(self):
        try:
            raw = read_private(self.root, 'current.json', MAX_RELEASE)
        except FileNotFoundError:
            return None
        return self.verify_envelope(raw)

    def release(self):
        current = self.current()
        if current is None:
            return None
        envelope, data = current
        if data['expiresAt'] <= int(time.time()):
            return None
        apk = self.apk(data['sha256'])
        if len(apk) != data['size']:
            raise ValueError('release artifact size')
        return envelope

    def apk(self, checksum):
        if not isinstance(checksum, str) or re.fullmatch(DIGEST, checksum) is None:
            raise ValueError('artifact hash')
        raw = read_private(self.artifacts, checksum + '.apk', MAX_APK)
        if not 4 <= len(raw) <= MAX_APK or hashlib.sha256(raw).hexdigest() != checksum:
            raise ValueError('artifact integrity')
        return raw

    def report(self, data):
        data = validate_report(data)
        value = {'receivedAt': int(time.time()), 'report': data}
        with self.lock:
            write_file(self.root, 'latest-report.json', encoded(value), replace=True)

    def home_report(self, data):
        value = {'receivedAt': int(time.time()), 'report': validate_home_report(data)}
        with self.lock:
            write_file(self.root, 'latest-home-report.json', encoded(value), replace=True)

    def startup_report(self, data):
        value = {'receivedAt': int(time.time()), 'report': validate_startup_report(data)}
        with self.lock:
            write_file(self.root, 'latest-startup-report.json', encoded(value), replace=True)

    def crash_report(self, data):
        value = {'receivedAt': int(time.time()), 'report': validate_crash_report(data)}
        with self.lock:
            write_file(self.root, 'latest-crash-report.json', encoded(value), replace=True)

    def ha_return_token(self):
        return self._ha_return_token

    def request_return_home(self, source='web', command=None):
        if not isinstance(source, str) or source not in RETURN_HOME_SOURCES:
            raise ValueError('return home source')
        if command is not None and not matches_voice_return_command(command):
            raise ValueError('return voice command mismatch')
        now = int(time.time())
        record = validate_return_home_record({
            'schema': 1,
            'action': 'return_home',
            'id': secrets.token_hex(16),
            'source': source,
            'requestedAt': now,
            'expiresAt': now + RETURN_HOME_TTL,
        })
        with self.lock:
            write_file(self.root, 'return-home.json', encoded(record), replace=True)
        return {'ok': True, 'request': record}

    def pending_return_home(self):
        try:
            raw = read_private(self.root, 'return-home.json', MAX_RETURN_HOME)
        except FileNotFoundError:
            return None
        record = validate_return_home_record(BASE.strict_json(raw))
        if record['expiresAt'] <= int(time.time()):
            return None
        return record

    def acknowledge_return_home(self, request_id):
        if not isinstance(request_id, str) or re.fullmatch(IDENTIFIER, request_id) is None:
            raise ValueError('return ack id')
        with self.lock:
            try:
                raw = read_private(self.root, 'return-home.json', MAX_RETURN_HOME)
            except FileNotFoundError:
                return {'ok': True, 'cleared': False}
            record = validate_return_home_record(BASE.strict_json(raw))
            matched = record['id'] == request_id
            if matched or record['expiresAt'] <= int(time.time()):
                try:
                    os.unlink('return-home.json', dir_fd=self.root)
                    os.fsync(self.root)
                except FileNotFoundError:
                    pass
                return {'ok': True, 'cleared': matched}
            return {'ok': True, 'cleared': False}

    def status(self):
        report = None
        try:
            saved = BASE.strict_json(read_private(self.root, 'latest-report.json', MAX_REPORT))
            if set(saved) != {'receivedAt', 'report'} or type(saved['receivedAt']) is not int:
                raise ValueError('stored report')
            report = {'receivedAt': saved['receivedAt'], 'report': validate_report(saved['report'])}
        except FileNotFoundError:
            pass
        envelope = self.release()
        release = BASE.strict_json(unb64(envelope['payload'], 4096)) if envelope else None
        return {'ok': True, 'release': release, 'latestReport': report, 'errors': self.errors}

    def publish(self, source):
        descriptor = os.open('.publish.lock', os.O_RDWR | os.O_CREAT | NOFOLLOW, 0o600, dir_fd=self.root)
        try:
            BASE.private_file_info(descriptor)
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return self.publish_locked(source)
        finally:
            os.close(descriptor)

    def withdraw(self):
        descriptor = os.open('.publish.lock', os.O_RDWR | os.O_CREAT | NOFOLLOW, 0o600, dir_fd=self.root)
        try:
            BASE.private_file_info(descriptor)
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            current = self.current()
            if current is None:
                return {'ok': True, 'withdrawn': False}
            os.unlink('current.json', dir_fd=self.root)
            os.fsync(self.root)
            return {'ok': True, 'withdrawn': True, 'releaseId': current[1]['id']}
        finally:
            os.close(descriptor)

    def publish_locked(self, source):
        raw = input_apk(source)
        info = os.fstatvfs(self.root)
        if info.f_bavail * info.f_frsize < len(raw) + 16 * 1024 * 1024:
            raise ValueError('insufficient disk space')
        staging = '.apk-' + secrets.token_hex(16)
        write_file(self.root, staging, raw)
        try:
            version = verify_apk(self.runtime / staging)
            if version < PLATFORM_FIRST_VERSION:
                raise ValueError('legacy release publication disabled')
            if read_private(self.root, staging, MAX_APK) != raw:
                raise ValueError('staged APK changed')
            previous = self.current()
            if previous is not None:
                _, previous_data = previous
                if version <= previous_data['versionCode']:
                    raise ValueError('release version must increase')
            checksum = hashlib.sha256(raw).hexdigest()
            payload = encoded(validate_payload({'schema': 1, 'action': 'update', 'id': secrets.token_hex(16),
                      'package': PACKAGE, 'versionCode': version, 'size': len(raw), 'sha256': checksum,
                      'expiresAt': int(time.time()) + 86400}))
            descriptor = os.open('signing.pem', os.O_RDONLY | NOFOLLOW, dir_fd=self.root)
            try:
                BASE.private_file_info(descriptor)
                signature = command([OPENSSL, 'dgst', '-sha256', '-sign', '/dev/fd/' + str(descriptor)],
                                    payload, (descriptor,))
            finally:
                os.close(descriptor)
            envelope = encoded({'payload': b64(payload), 'signature': b64(signature)})
            _, data = self.verify_envelope(envelope)
            write_file(self.artifacts, checksum + '.apk', raw)
            write_file(self.releases, data['id'] + '.json', envelope)
            write_file(self.root, 'current.json', envelope, replace=True)
            return {'ok': True, 'release': data}
        finally:
            os.unlink(staging, dir_fd=self.root)


class MaintenanceHandler(BASE.ReceiverHandler):
    server_version = 'PanelMaintenance'

    def authorized(self):
        value = self.single_header('Authorization')
        return (self.trusted_request(self.server.config['device']) and value is not None and value.isascii()
                and secrets.compare_digest(value, 'Bearer ' + self.server.config['token']))

    def local_authorized(self):
        return any(self.trusted_request(address) for address in (self.server.config['host'], '127.0.0.1'))

    def ha_authorized(self):
        value = self.single_header('Authorization')
        return (is_private_lan_or_loopback(self.client_address[0])
                and self.trusted_request(self.client_address[0])
                and value is not None and value.isascii()
                and secrets.compare_digest(value, 'Bearer ' + self.server.store.ha_return_token()))

    def bytes_reply(self, code, raw=b'', content_type='application/octet-stream'):
        self.close_connection = True
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.headers.get_all('Content-Length') not in (None, ['0']):
            return self.reject(400)
        try:
            if self.path == '/internal/status':
                if not self.local_authorized():
                    return self.reject(403)
                return self.reply(200, self.server.store.status())
            if not self.authorized():
                return self.reject(403)
            if self.path == '/v1/release':
                release = self.server.store.release()
                return self.reply(200, release) if release else self.bytes_reply(204)
            if self.path == '/v1/return-home':
                pending = self.server.store.pending_return_home()
                return self.reply(200, pending) if pending else self.bytes_reply(204)
            match = re.fullmatch(r'/v1/apk/(' + DIGEST + ')', self.path)
            if match:
                return self.bytes_reply(200, self.server.store.apk(match[1]))
            self.reject(404)
        except FileNotFoundError:
            self.reject(404)
        except (ValueError, TypeError, UnicodeError, OSError, subprocess.SubprocessError):
            self.reject(400)

    def read_json_body(self, maximum):
        remaining = self.body_length('application/json', maximum)
        chunks = []
        while remaining:
            raw = self.read_chunk(remaining)
            chunks.append(raw)
            remaining -= len(raw)
        raw = b''.join(chunks)
        if (self.server.config['token'].encode('ascii') in raw
                or self.server.store.ha_return_token().encode('ascii') in raw):
            raise ValueError('credential in report')
        return BASE.strict_json(raw)

    def do_POST(self):
        try:
            if self.path == '/internal/return-home':
                if not self.local_authorized():
                    return self.reject(403)
                source, command_text = validate_return_home_request(
                    self.read_json_body(MAX_RETURN_HOME), {'web', 'cli', 'voice'}, 'web')
                created = self.server.store.request_return_home(source, command_text)['request']
                return self.reply(200, {'ok': True, 'id': created['id'], 'expiresAt': created['expiresAt']})
            if self.path == '/v1/return-home':
                if not self.ha_authorized():
                    return self.reject(403)
                source, command_text = validate_return_home_request(
                    self.read_json_body(MAX_RETURN_HOME), {'ha', 'voice'}, 'ha')
                created = self.server.store.request_return_home(source, command_text)['request']
                return self.reply(200, {'ok': True, 'id': created['id'], 'expiresAt': created['expiresAt']})
            if not self.authorized():
                return self.reject(403)
            if self.path not in {'/v1/report', '/v1/home-report', '/v1/startup-report',
                                 '/v1/crash-report', '/v1/return-home/ack'}:
                return self.reject(404)
            maximum = {'/v1/report': MAX_REPORT, '/v1/home-report': MAX_HOME_REPORT,
                       '/v1/startup-report': MAX_STARTUP_REPORT, '/v1/crash-report': MAX_CRASH_REPORT,
                       '/v1/return-home/ack': MAX_RETURN_HOME}[self.path]
            payload = self.read_json_body(maximum)
            if self.path == '/v1/home-report':
                self.server.store.home_report(payload)
            elif self.path == '/v1/startup-report':
                self.server.store.startup_report(payload)
            elif self.path == '/v1/crash-report':
                self.server.store.crash_report(payload)
            elif self.path == '/v1/return-home/ack':
                self.server.store.acknowledge_return_home(validate_return_home_ack(payload))
            else:
                self.server.store.report(payload)
            self.reply(200, {'ok': True})
        except BASE.RequestRejected as error:
            self.reject(error.status)
        except (TimeoutError, socket.timeout):
            self.reject(408)
        except (ValueError, TypeError, UnicodeError, OSError):
            self.reject(400)


class MaintenanceServer(BASE.ReceiverServer):
    def __init__(self, config, runtime, read_timeout=5, request_timeout=30):
        self.config = validate_config(config)
        self.authority = config['host'] + ':' + str(config['port'])
        self.read_timeout, self.request_timeout = read_timeout, request_timeout
        self.store = MaintenanceStore(runtime)
        self.stopping = threading.Event()
        self.workers = threading.BoundedSemaphore(4)
        self.connections = set()
        self.connection_lock = threading.Lock()
        try:
            ThreadingHTTPServer.__init__(self, (config['host'], config['port']), MaintenanceHandler)
        except Exception:
            self.store.close()
            raise


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=BASE.runtime_path, default=RUNTIME)
    commands = parser.add_subparsers(dest='action', required=True)
    init = commands.add_parser('init')
    init.add_argument('--backup-config', type=Path, default=BACKUP_CONFIG)
    serve = commands.add_parser('serve')
    serve.add_argument('--lifetime', type=int, default=86400)
    publish = commands.add_parser('publish')
    publish.add_argument('apk', type=Path)
    commands.add_parser('status')
    commands.add_parser('withdraw')
    return_home = commands.add_parser('return-home')
    return_home.add_argument('--command')
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        if args.action == 'init':
            print(json.dumps(initialize(args.runtime, args.backup_config)))
            return 0
        config = load_config(args.runtime)
        if args.action in {'publish', 'status', 'withdraw', 'return-home'}:
            store = MaintenanceStore(args.runtime)
            try:
                if args.action == 'publish':
                    result = store.publish(args.apk)
                elif args.action == 'withdraw':
                    result = store.withdraw()
                elif args.action == 'return-home':
                    source = 'voice' if args.command is not None else 'cli'
                    result = store.request_return_home(source, args.command)
                else:
                    result = store.status()
                print(json.dumps(result))
            finally:
                store.close()
            return 0
        if not 1 <= args.lifetime <= 604800:
            raise ValueError('server lifetime')
        server = MaintenanceServer(config, args.runtime)
    except (ValueError, TypeError, OSError, subprocess.SubprocessError):
        print('Panel maintenance operation failed; check private files and verification tools.', file=sys.stderr)
        return 1
    timer = threading.Timer(args.lifetime, server.stop)
    timer.daemon = True
    timer.start()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: threading.Thread(target=server.stop, daemon=True).start())
    print('Panel maintenance server ready.', flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        timer.cancel()
        server.server_close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
