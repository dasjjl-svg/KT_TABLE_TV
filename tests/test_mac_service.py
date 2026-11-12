import contextlib
import importlib.util
import io
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location('mac_service', Path(__file__).parents[1] / 'scripts/mac-service.py')
SERVICE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SERVICE)


class MacServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'project'
        self.home = Path(self.temp.name) / 'home'
        for name in ['panel/dist/index.html', 'scripts/start.mjs', 'runtime/setup.json', 'runtime/private-secret.json']:
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('{}')
        self.target = self.home / 'Library/LaunchAgents' / (SERVICE.LABEL + '.plist')

    def invoke(self, action):
        calls = []
        def fake_run(*args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args, 0, b'', b'')
        with patch.object(SERVICE, 'ROOT', self.root), patch.object(Path, 'home', return_value=self.home), \
             patch.object(SERVICE.sys, 'platform', 'darwin'), patch.object(SERVICE.sys, 'argv', ['mac-service.py', action]), \
             patch.object(SERVICE.shutil, 'which', return_value='/usr/local/bin/node'), patch.object(SERVICE, 'run', side_effect=fake_run), \
             contextlib.redirect_stdout(io.StringIO()):
            SERVICE.main()
        return calls

    def test_install_only_uses_own_launcher_and_private_runtime_logs(self):
        calls = self.invoke('install')
        data = plistlib.loads(self.target.read_bytes())
        self.assertEqual(data['ProgramArguments'], ['/usr/local/bin/node', str(self.root / 'scripts/start.mjs')])
        self.assertEqual(data['WorkingDirectory'], str(self.root))
        self.assertTrue(data['KeepAlive'])
        self.assertEqual(self.target.stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.root / 'runtime/logs').stat().st_mode & 0o777, 0o700)
        self.assertEqual(calls[-1][1], 'bootstrap')

    def test_uninstall_keeps_credentials_and_app(self):
        self.invoke('install')
        self.invoke('uninstall')
        self.assertFalse(self.target.exists())
        self.assertTrue((self.root / 'runtime/private-secret.json').exists())
        self.assertTrue((self.root / 'panel/dist/index.html').exists())

    def test_other_installation_is_not_overwritten_or_removed(self):
        self.target.parent.mkdir(parents=True)
        raw = plistlib.dumps({'Label': SERVICE.LABEL, 'WorkingDirectory': str(self.root / 'another')})
        self.target.write_bytes(raw)
        for action in ['install', 'uninstall']:
            with self.assertRaises(ValueError): self.invoke(action)
            self.assertEqual(self.target.read_bytes(), raw)

    def test_symlink_and_missing_setup_are_rejected(self):
        (self.root / 'runtime/setup.json').unlink()
        with self.assertRaises(ValueError): self.invoke('install')
        self.assertFalse(self.target.exists())
        self.target.parent.mkdir(parents=True)
        original = self.root / 'runtime/private-secret.json'
        self.target.symlink_to(original)
        with self.assertRaises(ValueError): self.invoke('uninstall')
        self.assertEqual(original.read_text(), '{}')


if __name__ == '__main__': unittest.main()
