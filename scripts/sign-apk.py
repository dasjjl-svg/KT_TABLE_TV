#!/usr/bin/env python3
"""Re-sign a third-party Android APK with the AOSP 5.1.1 public platform test key (v1 JAR)
so stock KT CT1105-SB KTFirmwareUpdate app verification accepts it without firmware changes."""

import argparse
import base64
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
AOSP_SECURITY_URL = 'https://android.googlesource.com/platform/build/+/refs/tags/android-5.1.1_r38/target/product/security/'
PUBLIC_SIGNING_FILES = {
    'platform.pk8': '1ad8ef556870edb70f69a9d3c112544c07de5162ba440d84d33f8bb0c5962875',
    'platform.x509.pem': '9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14',
}
APPROVED_CERT_SHA256 = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'
APPROVED_CERT_SHA1 = '27196e386b875e76adf700e7ea84e4c6eee33dfa'
APPROVED_CERT_SHA1_BASE64 = 'JxluOGuHXnat9wDn6oTkxu7jPfo='
APPROVED_SPKI_SHA256 = '3d3df7dc9bf26e02d4cd76256d41d45e41a4dedebe7feb95c40e3697681be8a7'
CT1105_API_LEVEL = 22
CT1105_ABIS = {'armeabi-v7a', 'armeabi'}
MAX_APK_BYTES = 512 * 1024 * 1024


def load_toolchain():
    spec = importlib.util.spec_from_file_location('community_android_toolchain', ROOT / 'hardware/android_toolchain.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def sha1_hex(data):
    return hashlib.sha1(data).hexdigest()


def checked_output(args, data=None, timeout=180):
    result = subprocess.run([str(arg) for arg in args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).decode('utf-8', errors='replace').strip()
        raise ValueError(f'Command failed ({Path(str(args[0])).name}): {detail or result.returncode}')
    return result.stdout


def find_openssl():
    candidates = [Path('/usr/bin/openssl')]
    found = shutil.which('openssl')
    if found:
        candidates.append(Path(found))
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise ValueError('OpenSSL executable not found')


def resolve_tools():
    toolchain = load_toolchain()
    paths = toolchain.paths(required=False)
    tools = {
        'java': paths['jdk'] / 'java',
        'aapt': paths['tools'] / 'aapt',
        'zipalign': paths['tools'] / 'zipalign',
        'apksigner': paths['signer'],
        'openssl': find_openssl(),
    }
    missing = [name for name, path in tools.items() if not path.is_file()]
    if missing:
        raise ValueError(
            'Android signing tools missing (' + ', '.join(missing) + '). '
            'Run Build-Android (or python3 scripts/android_tools.py --install) first.'
        )
    return tools


def read_regular_file(path, maximum):
    if path.is_symlink():
        raise ValueError(f'Refusing symbolic link: {path}')
    raw = path.read_bytes()
    if not 1 <= len(raw) <= maximum:
        raise ValueError(f'Unexpected file size: {path}')
    return raw


def write_private_file(path, raw):
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        path.parent.chmod(0o700)
    except OSError:
        pass
    if path.is_symlink():
        raise ValueError(f'Refusing symbolic link: {path}')
    descriptor, temporary = tempfile.mkstemp(prefix='.tmp-', dir=str(path.parent))
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def fetch_public_platform_key(key_dir):
    key_dir.mkdir(parents=True, exist_ok=True)
    key_dir.chmod(0o700)
    for name, expected in PUBLIC_SIGNING_FILES.items():
        path = key_dir / name
        if path.exists():
            raw = read_regular_file(path, 16384)
            if sha256_hex(raw) == expected:
                continue
        request = urllib.request.Request(
            AOSP_SECURITY_URL + name + '?format=TEXT',
            headers={'User-Agent': 'HomePanelCommunity-SignApk/1'},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            if not response.url.startswith(AOSP_SECURITY_URL) or response.status != 200:
                raise ValueError('Unexpected public signing download response')
            encoded = response.read(65537)
        if len(encoded) > 65536:
            raise ValueError('Public signing download exceeded size limit')
        raw = base64.b64decode(encoded, validate=True)
        if sha256_hex(raw) != expected:
            raise ValueError(f'Downloaded {name} checksum mismatch')
        write_private_file(path, raw)


def prepare_platform_key(key_dir, openssl, fetch=False):
    pk8 = key_dir / 'platform.pk8'
    pem = key_dir / 'platform.x509.pem'
    if not (pk8.is_file() and pem.is_file()):
        if not fetch:
            raise ValueError(
                f'AOSP platform signing key not found in {key_dir}. '
                'Run Build-Android / python3 scripts/android_setup.py first, or pass --fetch-key.'
            )
        fetch_public_platform_key(key_dir)
    pk8_bytes = read_regular_file(pk8, 16384)
    pem_bytes = read_regular_file(pem, 16384)
    if sha256_hex(pk8_bytes) != PUBLIC_SIGNING_FILES['platform.pk8']:
        raise ValueError('platform.pk8 checksum mismatch')
    if sha256_hex(pem_bytes) != PUBLIC_SIGNING_FILES['platform.x509.pem']:
        raise ValueError('platform.x509.pem checksum mismatch')
    cert_der = checked_output([openssl, 'x509', '-outform', 'DER'], pem_bytes)
    if sha256_hex(cert_der) != APPROVED_CERT_SHA256 or sha1_hex(cert_der) != APPROVED_CERT_SHA1:
        raise ValueError('AOSP platform certificate digest mismatch')
    if base64.b64encode(hashlib.sha1(cert_der).digest()).decode('ascii') != APPROVED_CERT_SHA1_BASE64:
        raise ValueError('AOSP platform certificate base64 SHA-1 mismatch')
    cert_pub_pem = checked_output([openssl, 'x509', '-pubkey', '-noout'], pem_bytes)
    cert_pub_der = checked_output([openssl, 'pkey', '-pubin', '-outform', 'DER'], cert_pub_pem)
    key_pub_der = checked_output([openssl, 'pkey', '-inform', 'DER', '-pubout', '-outform', 'DER'], pk8_bytes)
    if sha256_hex(cert_pub_der) != APPROVED_SPKI_SHA256 or key_pub_der != cert_pub_der:
        raise ValueError('AOSP platform private key does not match certificate')
    return pk8, pem


def parse_badging(text):
    info = {
        'package': None,
        'version_code': None,
        'version_name': None,
        'split': None,
        'min_sdk': None,
        'target_sdk': None,
        'native_abis': [],
    }
    pkg = re.search(r"^package:\s+name='([^']+)'(?:\s+versionCode='([^']*)')?(?:\s+versionName='([^']*)')?", text, re.MULTILINE)
    if pkg:
        info['package'] = pkg.group(1)
        info['version_code'] = pkg.group(2) or None
        info['version_name'] = pkg.group(3) or None
    split = re.search(r"^package:.*\bsplit='([^']+)'", text, re.MULTILINE)
    if split:
        info['split'] = split.group(1)
    min_sdk = re.search(r"^sdkVersion:'(\d+)'", text, re.MULTILINE)
    if min_sdk:
        info['min_sdk'] = int(min_sdk.group(1))
    target_sdk = re.search(r"^targetSdkVersion:'(\d+)'", text, re.MULTILINE)
    if target_sdk:
        info['target_sdk'] = int(target_sdk.group(1))
    native = re.search(r'^native-code:\s*(.+)$', text, re.MULTILINE)
    if native:
        info['native_abis'] = re.findall(r"'([^']+)'", native.group(1))
    return info


def has_shared_user_id(xmltree_text):
    return bool(re.search(r'^\s*A:\s+android:sharedUserId(?:\(0x[0-9a-fA-F]+\))?=', xmltree_text, re.MULTILINE))


def inspect_apk(input_apk, aapt, allow_newer_sdk=False, allow_incompatible_abi=False):
    badging = checked_output([aapt, 'dump', 'badging', input_apk]).decode('utf-8', errors='replace')
    info = parse_badging(badging)
    if not info['package']:
        raise ValueError('Could not read package name from APK (aapt dump badging failed)')
    xmltree = checked_output([aapt, 'dump', 'xmltree', input_apk, 'AndroidManifest.xml']).decode('utf-8', errors='replace')
    if has_shared_user_id(xmltree):
        raise ValueError(
            'Refusing to sign APK with android:sharedUserId using the platform key '
            '(third-party user apps must not declare sharedUserId).'
        )
    if info['min_sdk'] is not None and info['min_sdk'] > CT1105_API_LEVEL and not allow_newer_sdk:
        raise ValueError(
            f"APK minSdkVersion is API {info['min_sdk']}, but CT1105-SB runs Android 5.1.1 (API {CT1105_API_LEVEL}). "
            'Use an Android 5.1-compatible APK version, or pass --allow-newer-sdk to sign anyway.'
        )
    if info['native_abis'] and not (set(info['native_abis']) & CT1105_ABIS) and not allow_incompatible_abi:
        abis = ', '.join(info['native_abis'])
        raise ValueError(
            f'APK only includes native libraries for [{abis}], without 32-bit ARM (armeabi-v7a/armeabi) '
            'required by CT1105-SB. Use an armeabi-v7a or universal APK, or pass --allow-incompatible-abi.'
        )
    return info


def is_v1_signature_entry(name):
    if not name.startswith('META-INF/'):
        return False
    remainder = name[len('META-INF/'):]
    if not remainder or '/' in remainder:
        return False
    upper = remainder.upper()
    return (
        upper == 'MANIFEST.MF'
        or upper.endswith(('.SF', '.RSA', '.DSA', '.EC'))
        or upper.startswith('SIG-')
    )


def safe_zip_entry_name(name):
    if not isinstance(name, str) or not name or '\\' in name or '\x00' in name:
        return False
    if name.startswith('/') or re.match(r'^[a-zA-Z]:', name):
        return False
    parts = name.rstrip('/').split('/')
    return all(part not in {'', '.', '..'} for part in parts)


def safe_zip_date_time(date_time):
    if (not isinstance(date_time, tuple) or len(date_time) != 6
            or date_time[0] < 1980 or date_time[0] > 2107
            or not 1 <= date_time[1] <= 12 or not 1 <= date_time[2] <= 31
            or not 0 <= date_time[3] <= 23 or not 0 <= date_time[4] <= 59 or not 0 <= date_time[5] <= 59):
        return (1980, 1, 1, 0, 0, 0)
    return date_time


def strip_apk_signatures(source_apk, destination_apk):
    has_manifest = False
    seen = set()
    stripped_count = 0
    preserved_meta_inf = []
    with zipfile.ZipFile(source_apk, 'r') as src, zipfile.ZipFile(destination_apk, 'w') as dst:
        for entry in src.infolist():
            name = entry.filename
            if not safe_zip_entry_name(name):
                raise ValueError(f'Unsafe APK ZIP entry path: {name!r}')
            if name in seen:
                raise ValueError(f'Duplicate APK ZIP entry: {name!r}')
            seen.add(name)
            mode = entry.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise ValueError(f'Symbolic link entry is not allowed inside APK: {name!r}')
            if is_v1_signature_entry(name):
                stripped_count += 1
                continue
            if entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ValueError(f'Unsupported ZIP compression method ({entry.compress_type}) for {name!r}')
            if name == 'AndroidManifest.xml':
                has_manifest = True
            if name.startswith('META-INF/') and not entry.is_dir():
                preserved_meta_inf.append(name)
            data = b'' if entry.is_dir() else src.read(name)
            new_info = zipfile.ZipInfo(name, date_time=safe_zip_date_time(entry.date_time))
            new_info.compress_type = zipfile.ZIP_STORED if entry.is_dir() else entry.compress_type
            new_info.external_attr = entry.external_attr
            new_info.flag_bits = entry.flag_bits & 0x800
            dst.writestr(new_info, data)
    if not has_manifest:
        raise ValueError('APK is missing AndroidManifest.xml')
    return {'stripped': stripped_count, 'preserved_meta_inf': preserved_meta_inf}


def parse_manifest_entries(manifest_bytes):
    unfolded = re.sub(rb'\r?\n ', b'', manifest_bytes)
    text = unfolded.decode('utf-8', errors='strict').replace('\r\n', '\n').replace('\r', '\n')
    sections = []
    current = {}
    for line in text.split('\n'):
        if not line:
            if current:
                sections.append(current)
                current = {}
            continue
        if ':' not in line:
            continue
        key, _, value = line.partition(':')
        current[key.strip()] = value.strip()
    if current:
        sections.append(current)
    entries = {}
    for section in sections:
        name = section.get('Name')
        if not name:
            continue
        digests = {k: v for k, v in section.items() if k.endswith('-Digest') and v}
        if digests:
            entries[name] = digests
    return entries


def verify_signed_apk(signed_apk, tools):
    verification = checked_output([
        tools['java'], '-jar', tools['apksigner'], 'verify',
        '--verbose', '--print-certs',
        '--min-sdk-version', '21', '--max-sdk-version', '22',
        signed_apk,
    ]).decode('utf-8', errors='replace')
    sha256_digests = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)$', verification, re.MULTILINE)
    sha1_digests = re.findall(r'^Signer #[0-9]+ certificate SHA-1 digest: ([0-9a-f]+)$', verification, re.MULTILINE)
    if (sha256_digests != [APPROVED_CERT_SHA256]
            or sha1_digests != [APPROVED_CERT_SHA1]
            or 'Verified using v1 scheme (JAR signing): true' not in verification
            or 'Verified using v2 scheme (APK Signature Scheme v2): false' not in verification):
        raise ValueError('Signed APK verification failed: unexpected signer or signature scheme')
    checked_output([tools['zipalign'], '-c', '-p', '4', signed_apk])
    with zipfile.ZipFile(signed_apk, 'r') as archive:
        names = [entry.filename for entry in archive.infolist() if not entry.is_dir()]
        if 'META-INF/MANIFEST.MF' not in names:
            raise ValueError('Signed APK is missing META-INF/MANIFEST.MF')
        manifest_entries = parse_manifest_entries(archive.read('META-INF/MANIFEST.MF'))
        missing = [name for name in names if not name.startswith('META-INF/') and name not in manifest_entries]
        if missing:
            raise ValueError('Signed APK has unsigned entries outside META-INF/: ' + ', '.join(missing[:5]))
        sig_blocks = [name for name in names if name.startswith('META-INF/') and '/' not in name[9:]
                      and name.upper().endswith(('.RSA', '.DSA', '.EC'))]
        if len(sig_blocks) != 1:
            raise ValueError(f'Expected exactly one v1 signature block in META-INF/, found {sig_blocks}')


def default_output_path(input_apk):
    stem = input_apk.stem
    if stem.endswith('-kt-signed'):
        return input_apk.with_name(stem + '.apk')
    return input_apk.with_name(stem + '-kt-signed.apk')


def sign_apk(input_apk, output_apk=None, key_dir=None, fetch_key=False,
             allow_newer_sdk=False, allow_incompatible_abi=False, force=False, tools=None):
    source = Path(input_apk).resolve(strict=True)
    if not source.is_file():
        raise ValueError(f'Input APK is not a regular file: {source}')
    if not 1 <= source.stat().st_size <= MAX_APK_BYTES:
        raise ValueError(f'Input APK size is out of supported range: {source}')
    destination = Path(output_apk).expanduser().resolve() if output_apk else default_output_path(source)
    if destination == source:
        raise ValueError('Output path must differ from input APK path')
    if destination.exists() and not force:
        raise ValueError(f'Output file already exists: {destination} (pass --force to overwrite)')
    if not destination.parent.exists():
        destination.parent.mkdir(parents=True, exist_ok=True)
    tools = tools or resolve_tools()
    resolved_key_dir = Path(key_dir).expanduser().resolve() if key_dir else ROOT / 'runtime/panel-signing-transition'
    pk8, pem = prepare_platform_key(resolved_key_dir, tools['openssl'], fetch=fetch_key)
    metadata = inspect_apk(source, tools['aapt'],
                           allow_newer_sdk=allow_newer_sdk,
                           allow_incompatible_abi=allow_incompatible_abi)
    with tempfile.TemporaryDirectory(prefix='kt-sign-apk-') as work_dir:
        work = Path(work_dir)
        work.chmod(0o700)
        stripped = work / 'stripped.apk'
        aligned = work / 'aligned.apk'
        signed = work / 'signed.apk'
        strip_stats = strip_apk_signatures(source, stripped)
        checked_output([tools['zipalign'], '-f', '-p', '4', stripped, aligned])
        checked_output([
            tools['java'], '-jar', tools['apksigner'], 'sign',
            '--key', pk8, '--cert', pem,
            '--min-sdk-version', '21', '--max-sdk-version', '22',
            '--v1-signing-enabled', 'true',
            '--v2-signing-enabled', 'false',
            '--v3-signing-enabled', 'false',
            '--v4-signing-enabled', 'false',
            '--out', signed, aligned,
        ])
        verify_signed_apk(signed, tools)
        fd, stage = tempfile.mkstemp(prefix='.kt-signed-', suffix='.apk', dir=str(destination.parent))
        os.close(fd)
        try:
            shutil.copyfile(signed, stage)
            os.chmod(stage, 0o644)
            os.replace(stage, destination)
        except Exception:
            try:
                os.unlink(stage)
            except OSError:
                pass
            raise
    return {
        'input': str(source),
        'output': str(destination),
        'size': destination.stat().st_size,
        'sha256': sha256_hex(destination.read_bytes()),
        'metadata': metadata,
        'strip': strip_stats,
        'signer_sha1': APPROVED_CERT_SHA1,
        'signer_sha1_base64': APPROVED_CERT_SHA1_BASE64,
        'signer_sha256': APPROVED_CERT_SHA256,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(
        description='Re-sign a third-party APK with the AOSP 5.1.1 platform key (v1 JAR) for stock KT CT1105-SB.'
    )
    parser.add_argument('input_apk', help='Path to the input third-party .apk file (e.g. Projectivy.apk)')
    parser.add_argument('-o', '--out', dest='output_apk', help='Output APK path (default: <name>-kt-signed.apk)')
    parser.add_argument('--key-dir', help='Directory containing platform.pk8 and platform.x509.pem')
    parser.add_argument('--fetch-key', action='store_true',
                        help='Download and verify the public AOSP 5.1.1 platform test key if not yet present')
    parser.add_argument('--allow-newer-sdk', action='store_true',
                        help='Sign even if minSdkVersion is higher than Android 5.1.1 (API 22)')
    parser.add_argument('--allow-incompatible-abi', action='store_true',
                        help='Sign even if native libraries do not include armeabi-v7a/armeabi')
    parser.add_argument('-f', '--force', action='store_true', help='Overwrite existing output APK')
    args = parser.parse_args(argv)
    try:
        result = sign_apk(
            args.input_apk,
            output_apk=args.output_apk,
            key_dir=args.key_dir,
            fetch_key=args.fetch_key,
            allow_newer_sdk=args.allow_newer_sdk,
            allow_incompatible_abi=args.allow_incompatible_abi,
            force=args.force,
        )
    except Exception as error:
        print(f'ERROR: {error}', file=sys.stderr)
        return 1
    meta = result['metadata']
    print('APK re-signed and verified for stock KT CT1105-SB (v1 JAR, AOSP platform key):')
    print(f"  Output      : {result['output']} ({result['size']} bytes)")
    print(f"  Package     : {meta['package']} (versionName={meta['version_name'] or '-'}, versionCode={meta['version_code'] or '-'})")
    print(f"  SDK / ABI   : minSdk={meta['min_sdk'] or '-'}, targetSdk={meta['target_sdk'] or '-'}, native={', '.join(meta['native_abis']) or 'none (Java/Kotlin only)'}")
    print(f"  Signer SHA1 : {result['signer_sha1']} (KT Base64: {result['signer_sha1_base64']})")
    if meta['split']:
        print(f"  WARNING     : Split APK detected (split={meta['split']}). Re-sign all split APKs with this tool, or prefer a universal APK.")
    if result['strip']['preserved_meta_inf']:
        print(f"  Preserved   : {len(result['strip']['preserved_meta_inf'])} non-signature META-INF metadata file(s)")
    print('  Note        : If an original developer-signed version is still installed, uninstall it first before installing this APK.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
