"""PTY-only checks: never open real /dev/cu.* serial devices."""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import pty
import select
import tempfile
import termios
import threading
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "uart_capture.py"
spec = importlib.util.spec_from_file_location("uart_capture", SCRIPT)
uart = importlib.util.module_from_spec(spec)
spec.loader.exec_module(uart)


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.output = Path(self.directory.name) / "boot.bin"
        self.master, self.slave = pty.openpty()
        self.port = os.ttyname(self.slave)
        self.original = termios.tcgetattr(self.slave)

    def tearDown(self):
        for fd in (self.master, self.slave):
            if fd is not None:
                os.close(fd)
        self.directory.cleanup()

    def validate_pty(self, port):
        # The production CLI rejects PTY paths; replace only that boundary.
        self.assertEqual(port, self.port)
        return os.stat(port)

    def assert_tty_restored(self):
        restored = termios.tcgetattr(self.slave)
        original = list(self.original)
        # Darwin sets the transient PENDIN flag when returning to canonical
        # input. All configured persistent flags, speeds and CCs must match.
        restored[3] &= ~getattr(termios, "PENDIN", 0)
        original[3] &= ~getattr(termios, "PENDIN", 0)
        self.assertEqual(restored, original)

    def run_capture(self, payload, *, byte_limit=4096, unplug=False):
        ready = threading.Event()
        results = []
        errors = []

        def worker():
            try:
                results.append(uart.capture(self.port, 9600, self.output,
                    seconds=0.35, max_bytes=byte_limit, _on_ready=ready.set))
            except BaseException as error:
                errors.append(error)

        with mock.patch.object(uart, "validate_port", side_effect=self.validate_pty):
            thread = threading.Thread(target=worker, daemon=True)
            thread.start()
            self.assertTrue(ready.wait(3), "PTY receiver did not become ready")
            os.write(self.master, payload)
            if unplug:
                # Wait until the read was persisted, then simulate unplug.
                for _ in range(100):
                    if self.output.stat().st_size == len(payload):
                        break
                    threading.Event().wait(0.005)
                os.close(self.master)
                self.master = None
            thread.join(3)
            self.assertFalse(thread.is_alive(), "Capture exceeded bounded time")
        if errors:
            raise errors[0]
        self.assertEqual(len(results), 1)
        return results[0]

    def test_exact_binary_capture_no_outbound_and_restore(self):
        payload = bytes(range(256)) * 8
        result, code = self.run_capture(payload)
        self.assertEqual(code, 0)
        self.assertEqual(result["stopReason"], "timeout")
        self.assertEqual(self.output.read_bytes(), payload)
        self.assertEqual(result["bytesCaptured"], len(payload))
        self.assertEqual(result["sha256"], hashlib.sha256(payload).hexdigest())
        self.assert_tty_restored()
        if hasattr(termios, "TIOCNXCL"):
            self.assertTrue(result.get("exclusiveAccessReleased"))
            reopened = os.open(self.port, os.O_RDONLY | os.O_NOCTTY | os.O_NONBLOCK)
            os.close(reopened)
        # Input written on the master must not be echoed or answered by the tool.
        self.assertEqual(select.select([self.master], [], [], 0.05)[0], [])
        stored = json.loads(Path(str(self.output) + ".json").read_text())
        self.assertEqual(stored, result)
        self.assertEqual(sum(e["length"] for e in stored["readEvents"]), len(payload))
        self.assertTrue(all(e["receivedAtUtc"].endswith("+00:00") for e in stored["readEvents"]))
        self.assertTrue(Path(str(self.output) + ".sha256").read_text().startswith(result["sha256"]))
        for suffix in ("", ".json", ".sha256"):
            self.assertEqual(Path(str(self.output) + suffix).stat().st_mode & 0o777, 0o600)

    def test_byte_limit_preserves_exact_prefix(self):
        result, code = self.run_capture(b"0123456789abcdef", byte_limit=7)
        self.assertEqual(code, 0)
        self.assertEqual(result["stopReason"], "byte-limit")
        self.assertEqual(self.output.read_bytes(), b"0123456")
        self.assertEqual(result["bytesCaptured"], 7)

    def test_unplug_is_failure_and_finalizes_received_bytes(self):
        result, code = self.run_capture(b"boot log\r\n", unplug=True)
        self.assertEqual(code, 1)
        self.assertIn(result["stopReason"], ("disconnected", "io-error"))
        self.assertTrue(result["error"])
        self.assertEqual(self.output.read_bytes(), b"boot log\r\n")
        self.assertEqual(result["sha256"], hashlib.sha256(b"boot log\r\n").hexdigest())
        self.assertTrue(Path(str(self.output) + ".json").is_file())

    def test_ctrl_c_restores_and_writes_metadata(self):
        with mock.patch.object(uart, "validate_port", side_effect=self.validate_pty), \
             mock.patch.object(uart.select, "select", side_effect=KeyboardInterrupt):
            result, code = uart.capture(self.port, 9600, self.output, seconds=1)
        self.assertEqual(code, 130)
        self.assertEqual(result["stopReason"], "interrupted")
        self.assertTrue(result["ttyRestored"])
        self.assert_tty_restored()
        self.assertEqual(result["sha256"], hashlib.sha256(b"").hexdigest())

    def test_existing_sidecar_is_never_overwritten_or_port_opened(self):
        sidecar = Path(str(self.output) + ".json")
        sidecar.write_text("keep me")
        with mock.patch.object(uart, "validate_port", side_effect=self.validate_pty), \
             mock.patch.object(uart, "configure_receiver") as configure:
            with self.assertRaises(FileExistsError):
                uart.capture(self.port, 9600, self.output)
        configure.assert_not_called()
        self.assertFalse(self.output.exists())
        self.assertEqual(sidecar.read_text(), "keep me")

    def test_list_only_names_never_opens(self):
        with mock.patch.object(uart.glob, "glob", return_value=["/dev/cu.mock"]), \
             mock.patch.object(uart.os, "open", side_effect=AssertionError("port opened")), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(uart.main(["--list"]), 0)
        self.assertEqual(output.getvalue(), "/dev/cu.mock\n")

    def test_invalid_inputs_fail_before_open(self):
        with mock.patch.object(uart.os, "open", side_effect=AssertionError("port opened")):
            for port in ("relative", "/dev/tty.fake", "/dev/cu.a/../disk", "/tmp/file"):
                with self.subTest(port=port), self.assertRaises(ValueError):
                    uart.capture(port, 9600, self.output)
            for baud in (0, -1, 123456789):
                with self.subTest(baud=baud), self.assertRaises(ValueError):
                    uart.capture("/dev/cu.fake", baud, self.output)
            for seconds in (0, -1, float("nan"), float("inf"), 3601):
                with self.subTest(seconds=seconds), self.assertRaises(ValueError):
                    uart.capture("/dev/cu.fake", 9600, self.output, seconds=seconds)
            for max_bytes in (0, -1, uart.MAX_BYTES + 1):
                with self.subTest(max_bytes=max_bytes), self.assertRaises(ValueError):
                    uart.capture("/dev/cu.fake", 9600, self.output, max_bytes=max_bytes)
        self.assertEqual(list(Path(self.directory.name).iterdir()), [])


if __name__ == "__main__":
    unittest.main()
