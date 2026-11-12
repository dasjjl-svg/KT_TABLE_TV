import importlib.util
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(platform.system() == 'Linux' and shutil.which('systemd-analyze'), 'Linux systemd unit parser required')
class LinuxUnitParserTest(unittest.TestCase):
    def test_generated_service_parses_without_installing_or_starting_it(self):
        specification = importlib.util.spec_from_file_location('unit_parser_service', Path(__file__).parents[1] / 'scripts/linux-service.py')
        service = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(service)
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory) / 'project with spaces'
            (project / 'scripts').mkdir(parents=True)
            (project / 'scripts/start.mjs').write_text('process.exit(0);\n')
            unit = Path(directory) / service.UNIT
            unit.write_text(service.render_unit(project, shutil.which('node'), shutil.which('python3')))
            result = subprocess.run(['systemd-analyze', 'verify', str(unit)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
