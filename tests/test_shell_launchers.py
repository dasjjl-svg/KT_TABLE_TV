import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).parents[1]


class ShellLauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'example project'
        self.root.mkdir()
        self.bin = Path(self.temp.name) / 'bin'
        self.bin.mkdir()
        self.log = Path(self.temp.name) / 'calls'
        for name in ('Setup.sh', 'Start.sh', 'Build-Android.sh'):
            shutil.copyfile(ROOT / name, self.root / name)
        for name in ('node', 'npm', 'python3'):
            script = self.bin / name
            script.write_text('#!/bin/sh\nprintf "%s\\0" "$0" "$PWD" "$@" >> "$CALL_LOG"\nprintf "\\n" >> "$CALL_LOG"\nif [ "${FAIL_EXEC:-}" = "${0##*/}" ]; then exit 17; fi\n')
            script.chmod(0o700)

    def invoke(self, script, fail=''):
        environment = {**os.environ, 'PATH': str(self.bin) + ':/usr/bin:/bin', 'CALL_LOG': str(self.log), 'FAIL_EXEC': fail}
        result = subprocess.run(['/bin/sh', str(self.root / script)], cwd=self.temp.name, env=environment, capture_output=True)
        calls = [line.split('\0')[:-1] for line in self.log.read_text().splitlines()]
        self.assertTrue(all(call[1] == str(self.root) for call in calls))
        return result, [[Path(call[0]).name, *call[2:]] for call in calls]

    def test_setup_uses_own_directory_and_runs_guard_before_writes_and_build(self):
        result, calls = self.invoke('Setup.sh')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, [['node', 'scripts/host-check.mjs'], ['node', 'scripts/setup.mjs'], ['npm', '--prefix', 'panel', 'ci'], ['npm', '--prefix', 'panel', 'run', 'build']])

    def test_failed_guard_stops_setup_before_package_install(self):
        result, calls = self.invoke('Setup.sh', fail='node')
        self.assertEqual(result.returncode, 17)
        self.assertEqual(calls, [['node', 'scripts/host-check.mjs']])

    def test_android_reuses_configuration_and_preserves_build_order(self):
        (self.root / 'runtime').mkdir()
        (self.root / 'runtime/setup.json').write_text('{}')
        result, calls = self.invoke('Build-Android.sh')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, [['node', 'scripts/host-check.mjs'], ['python3', 'scripts/android_tools.py', '--install'], ['python3', 'scripts/android_setup.py'], ['python3', 'hardware/panel-app/build.py']])

    def test_start_propagates_child_failure(self):
        result, calls = self.invoke('Start.sh', fail='node')
        self.assertEqual(result.returncode, 17)
        self.assertEqual(calls, [['node', 'scripts/start.mjs']])


if __name__ == '__main__': unittest.main()
