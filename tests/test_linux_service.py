import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location('linux_service', Path(__file__).parents[1] / 'scripts/linux-service.py')
SERVICE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SERVICE)


class LinuxServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'project with spaces'
        self.home = Path(self.temp.name) / 'home'
        for name in ('panel/dist/index.html', 'scripts/start.mjs', 'runtime/setup.json', 'runtime/private-example.json'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('{}')
        self.target = self.home / '.config/systemd/user' / SERVICE.UNIT

    def invoke(self, action, failure=None):
        calls = []
        def fake_run(*args, **kwargs):
            calls.append(args)
            if failure and failure in args:
                raise subprocess.CalledProcessError(1, args)
            return subprocess.CompletedProcess(args, 0, b'', b'')
        def which(name):
            return '/usr/bin/' + name
        with patch.object(SERVICE, 'ROOT', self.root), patch.object(Path, 'home', return_value=self.home), \
             patch.object(SERVICE.sys, 'platform', 'linux'), patch.object(SERVICE.sys, 'argv', ['linux-service.py', action]), \
             patch.object(SERVICE.os, 'geteuid', return_value=1000), \
             patch.object(SERVICE.shutil, 'which', side_effect=which), patch.object(SERVICE, 'run', side_effect=fake_run), \
             contextlib.redirect_stdout(io.StringIO()):
            SERVICE.main()
        return calls

    def test_install_configures_only_user_service_and_private_unit(self):
        calls = self.invoke('install')
        text = self.target.read_text()
        self.assertIn('WorkingDirectory=' + str(self.root) + '\n', text)
        self.assertIn('ExecStart="/usr/bin/node" "' + str(self.root / 'scripts/start.mjs') + '"', text)
        self.assertIn('UMask=0077', text)
        self.assertEqual(self.target.stat().st_mode & 0o777, 0o600)
        self.assertTrue(all(call[:2] == ('systemctl', '--user') for call in calls))
        self.assertEqual(calls[-1], ('systemctl', '--user', 'restart', SERVICE.UNIT))

    def test_uninstall_preserves_credentials_and_app(self):
        self.invoke('install')
        self.invoke('uninstall')
        self.assertFalse(self.target.exists())
        self.assertTrue((self.root / 'runtime/private-example.json').exists())
        self.assertTrue((self.root / 'panel/dist/index.html').exists())

    def test_foreign_installation_or_symlink_is_never_replaced(self):
        self.target.parent.mkdir(parents=True)
        foreign = SERVICE.render_unit(self.root / 'another', '/usr/bin/node', '/usr/bin/python3')
        self.target.write_text(foreign)
        for action in ('install', 'uninstall'):
            with self.assertRaises(ValueError): self.invoke(action)
            self.assertEqual(self.target.read_text(), foreign)
        self.target.unlink()
        original = self.root / 'runtime/private-example.json'
        self.target.symlink_to(original)
        with self.assertRaises(ValueError): self.invoke('install')
        self.assertEqual(original.read_text(), '{}')

    def test_missing_setup_and_unavailable_user_bus_leave_no_unit(self):
        with self.assertRaises(subprocess.CalledProcessError): self.invoke('install', failure='show-environment')
        self.assertFalse(self.target.exists())
        (self.root / 'runtime/setup.json').unlink()
        with self.assertRaises(ValueError): self.invoke('install')
        self.assertFalse(self.target.exists())

    def test_stop_failure_preserves_unit_and_configuration(self):
        self.invoke('install')
        before = self.target.read_bytes()
        with self.assertRaises(subprocess.CalledProcessError): self.invoke('uninstall', failure='disable')
        self.assertEqual(self.target.read_bytes(), before)

    def test_unit_escapes_command_environment_and_systemd_expansions(self):
        root = Path('/home/example/a %n $TEST "quote"')
        value = SERVICE.render_unit(root, '/usr/bin/node', '/usr/bin/python3')
        self.assertIn('%%n', value)
        self.assertIn('$$TEST', value)
        self.assertIn('\\"quote\\"', value)
        self.assertNotIn('\n[Service]\nExecStart=/bin/sh', value)
        with self.assertRaises(ValueError): SERVICE.render_unit(Path('/home/example/bad\nExecStart=evil'), '/usr/bin/node', '/usr/bin/python3')
        with self.assertRaises(ValueError): SERVICE.render_unit(Path('/home/example/project'), '/home/example/$BIN/node', '/usr/bin/python3')
        with self.assertRaises(ValueError): SERVICE.render_unit(Path('/home/example/trailing '), '/usr/bin/node', '/usr/bin/python3')


if __name__ == '__main__': unittest.main()
