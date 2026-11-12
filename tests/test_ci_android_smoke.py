import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location('ci_android_smoke', Path(__file__).with_name('ci_android_smoke.py'))
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class AndroidSmokeTest(unittest.TestCase):
    def test_refuses_local_execution_before_reading_or_copying_files(self):
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(SMOKE.shutil, 'copytree') as copy:
            with self.assertRaisesRegex(SystemExit, 'GitHub-hosted Linux'):
                SMOKE.main()
            copy.assert_not_called()

    def run_fixture(self, fail=False):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source, runner, sdk = base / 'source', base / 'runner', base / 'sdk'
            source.mkdir(); runner.mkdir()
            (source / 'runtime').mkdir()
            (source / 'runtime/private-marker').write_text('fixture-do-not-copy')
            (source / 'source-marker').write_text('public-source')
            (sdk / 'platforms/android-22').mkdir(parents=True)
            (sdk / 'platforms/android-22/android.jar').write_bytes(b'fixture')
            calls = []

            def execute(command, cwd, **kwargs):
                calls.append(command)
                self.assertEqual((cwd / 'source-marker').read_text(), 'public-source')
                self.assertFalse((cwd / 'runtime/private-marker').exists())
                self.assertTrue(kwargs['check'])
                if fail:
                    raise subprocess.CalledProcessError(1, command)
                if command[-1] == 'hardware/panel-app/build.py':
                    folder = cwd / 'hardware/panel-app'
                    folder.mkdir(parents=True)
                    for name in ('SmartFactory.apk', 'HomePanel.apk'):
                        (folder / name).write_bytes(b'fixture' * 256)

            environment = {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_TEMP': str(runner), 'ANDROID_HOME': str(sdk)}
            previous_mask = os.umask(0o077)
            try:
                with mock.patch.object(SMOKE, 'SOURCE', source), mock.patch.object(SMOKE.platform, 'system', return_value='Linux'), mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(SMOKE.subprocess, 'run', side_effect=execute):
                    if fail:
                        with self.assertRaises(subprocess.CalledProcessError):
                            SMOKE.main()
                    else:
                        SMOKE.main()
            finally:
                os.umask(previous_mask)
            self.assertEqual(list(runner.iterdir()), [])
            self.assertTrue((source / 'runtime/private-marker').is_file())
            return calls

    def test_uses_synthetic_copy_and_discards_apks_after_success(self):
        calls = self.run_fixture()
        self.assertEqual(len(calls), 3)
        self.assertIn('http://ha.example.test:8123', calls[0])

    def test_failed_build_propagates_and_cleans_temporary_files(self):
        self.assertEqual(len(self.run_fixture(fail=True)), 1)


if __name__ == '__main__':
    unittest.main()
