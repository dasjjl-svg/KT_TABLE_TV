import base64
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('community_maintenance', ROOT / 'hardware/panel_maintenance_server.py')
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
TOOL_SPEC = importlib.util.spec_from_file_location('community_toolchain', ROOT / 'hardware/android_toolchain.py')
T = importlib.util.module_from_spec(TOOL_SPEC)
TOOL_SPEC.loader.exec_module(T)
AOSP = 'https://android.googlesource.com/platform/build/+/refs/tags/android-5.1.1_r38/target/product/security/'
PUBLIC_SIGNING_FILES = {
    'platform.pk8': '1ad8ef556870edb70f69a9d3c112544c07de5162ba440d84d33f8bb0c5962875',
    'platform.x509.pem': '9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14',
}
APPROVED_SPKI = '3d3df7dc9bf26e02d4cd76256d41d45e41a4dedebe7feb95c40e3697681be8a7'


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def private(path, maximum=65536):
    directory = M.BASE.directory_fd(path.parent, private=True)
    try:
        return M.read_private(directory, path.name, maximum)
    finally:
        os.close(directory)


def save(path, raw, replace=False):
    directory = M.BASE.directory_fd(path.parent, create=True, private=True)
    try:
        M.write_file(directory, path.name, raw, replace=replace)
    finally:
        os.close(directory)


def bootstrap_signing(jdk):
    directory = ROOT / 'runtime/usb-probe-signing'
    names = {'diagnostic.p12', 'password', 'identity.json'}
    if directory.exists():
        found = set(os.listdir(directory))
        if found != names:
            raise ValueError('Bootstrap signing directory is incomplete; preserve it and restore its backup')
        identity = M.BASE.strict_json(private(directory / 'identity.json'))
    else:
        directory.parent.mkdir(mode=0o700, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.bootstrap-', dir=directory.parent) as temporary:
            stage = Path(temporary)
            stage.chmod(0o700)
            password = stage / 'password'
            password.write_text(secrets.token_hex(32) + '\n')
            password.chmod(0o600)
            subprocess.run([str(jdk / 'keytool'), '-genkeypair', '-alias', 'genie-probe', '-keyalg', 'RSA', '-keysize', '2048',
                            '-validity', '10000', '-dname', 'CN=Home Panel Owner', '-keystore', str(stage / 'diagnostic.p12'),
                            '-storetype', 'PKCS12', '-storepass:file', str(password), '-keypass:file', str(password)],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
            certificate = M.command([jdk / 'keytool', '-exportcert', '-alias', 'genie-probe', '-keystore', stage / 'diagnostic.p12',
                                     '-storetype', 'PKCS12', '-storepass:file', password])
            public_pem = M.command([M.OPENSSL, 'x509', '-inform', 'DER', '-pubkey', '-noout'], certificate)
            public = M.command([M.OPENSSL, 'pkey', '-pubin', '-outform', 'DER'], public_pem)
            identity = {'schema': 1, 'bootstrapCertificateSha256': digest(certificate), 'bootstrapSpkiSha256': digest(public)}
            (stage / 'identity.json').write_bytes(M.encoded(identity))
            for path in stage.iterdir():
                path.chmod(0o600)
            directory.mkdir(mode=0o700)
            for name in names:
                save(directory / name, (stage / name).read_bytes())
    if (not isinstance(identity, dict) or set(identity) != {'schema', 'bootstrapCertificateSha256', 'bootstrapSpkiSha256'}
            or identity['schema'] != 1 or any(not re.fullmatch(M.DIGEST, identity[field])
                                           for field in ('bootstrapCertificateSha256', 'bootstrapSpkiSha256'))):
        raise ValueError('Bootstrap identity is invalid')
    private(directory / 'diagnostic.p12')
    private(directory / 'password')
    certificate = M.command([jdk / 'keytool', '-exportcert', '-alias', 'genie-probe', '-keystore', directory / 'diagnostic.p12',
                             '-storetype', 'PKCS12', '-storepass:file', directory / 'password'])
    pem = M.command([M.OPENSSL, 'x509', '-inform', 'DER', '-pubkey', '-noout'], certificate)
    public = M.command([M.OPENSSL, 'pkey', '-pubin', '-outform', 'DER'], pem)
    if digest(certificate) != identity['bootstrapCertificateSha256'] or digest(public) != identity['bootstrapSpkiSha256']:
        raise ValueError('Bootstrap signing key differs from saved identity')
    return public


def approved_signing(bootstrap_public):
    directory = ROOT / 'runtime/panel-signing-transition'
    directory.mkdir(mode=0o700, exist_ok=True)
    for name, expected in PUBLIC_SIGNING_FILES.items():
        path = directory / name
        if path.exists():
            raw = private(path, 16384)
        else:
            request = urllib.request.Request(AOSP + name + '?format=TEXT', headers={'User-Agent': 'HomePanelCommunity-Setup/1'})
            with urllib.request.urlopen(request, timeout=30) as response:
                if not response.url.startswith(AOSP) or response.status != 200:
                    raise ValueError('Unexpected public signing download')
                encoded = response.read(65537)
            if len(encoded) > 65536:
                raise ValueError('Public signing download size')
            raw = base64.b64decode(encoded, validate=True)
            if digest(raw) != expected:
                raise ValueError('Public signing download checksum mismatch')
            save(path, raw)
        if digest(raw) != expected:
            raise ValueError('Saved public signing file checksum mismatch')
    certificate = private(directory / 'platform.x509.pem', 16384)
    der = M.command([M.OPENSSL, 'x509', '-outform', 'DER'], certificate)
    pem = M.command([M.OPENSSL, 'x509', '-pubkey', '-noout'], certificate)
    public = M.command([M.OPENSSL, 'pkey', '-pubin', '-outform', 'DER'], pem)
    key_public = M.command([M.OPENSSL, 'pkey', '-inform', 'DER', '-pubout', '-outform', 'DER'], private(directory / 'platform.pk8', 16384))
    if digest(der) != M.PLATFORM_SIGNER or digest(public) != APPROVED_SPKI or public != key_public:
        raise ValueError('Public test certificate and key do not match')
    save(directory / 'platform-public-key.der', public)
    save(directory / 'original-public-key.der', bootstrap_public)
    save(directory / 'provenance.json', M.encoded({'schema': 1, 'upstream': AOSP, 'publicTestKeys': True, 'files': PUBLIC_SIGNING_FILES}))


def collector(name, port, host, device):
    directory = ROOT / 'runtime' / name
    path = directory / 'config.json'
    if path.exists():
        value = M.BASE.validate_config(M.BASE.strict_json(private(path, 4096)))
        if value['port'] != port:
            raise ValueError('Collector port mismatch')
        value.update(host=host, device=device)
        save(path, M.encoded(value), replace=True)
    else:
        value = {'version': 1, 'host': host, 'device': device, 'port': port, 'token': secrets.token_hex(32)}
        M.BASE.validate_config(value)
        save(path, M.encoded(value))


def main():
    os.umask(0o077)
    setup = M.BASE.strict_json(private(ROOT / 'runtime/setup.json', 16384))
    if setup.get('schema') != 1 or setup['server']['port'] != 4173 or setup['server']['legacyPort'] != 4181:
        raise ValueError('Run setup first; panel ports must be 4173 and 4181')
    host, device = setup['server']['address'], setup['device']['address']
    for value in (host, device):
        address = ipaddress.IPv4Address(value)
        if str(address) != value or not address.is_private or address.is_loopback or address.is_link_local:
            raise ValueError('Server and device require LAN IPv4 addresses')
    if host == device:
        raise ValueError('Server and device addresses must differ')
    panel = M.BASE.strict_json(private(ROOT / 'runtime/legacy/device.json', 4096))
    if panel.get('version') != 1 or panel.get('origin') != 'http://' + host + ':4181' or not re.fullmatch(M.DIGEST, panel.get('token', '')):
        raise ValueError('Legacy panel configuration does not match setup')
    jdk = T.paths()['jdk']
    bootstrap_public = bootstrap_signing(jdk)
    approved_signing(bootstrap_public)
    for name, port in (('kt-apk', 4182), ('kt-home-apk', 4183), ('android-settings-apk', 4184), ('system-backup', 4185)):
        collector(name, port, host, device)
    M.initialize(ROOT / 'runtime/panel-maintenance', ROOT / 'runtime/system-backup/config.json')
    collector('panel-maintenance', 4186, host, device)
    print('Android credentials and signing identity verified. Existing keys and tokens were preserved.')


if __name__ == '__main__':
    main()
