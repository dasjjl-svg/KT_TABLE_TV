#!/usr/bin/env python3
"""Bounded UART receive capture. Uses only Python's standard library."""

import argparse
import copy
from datetime import datetime, timezone
import errno
import fcntl
import glob
import hashlib
import json
import os
from pathlib import Path
import re
import select
import stat
import sys
import termios
import time


DEFAULT_SECONDS = 120.0
DEFAULT_MAX_BYTES = 16 * 1024 * 1024
MAX_SECONDS = 3600.0
MAX_BYTES = 256 * 1024 * 1024
MAX_READ_EVENTS = 4096


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="microseconds")


def supported_bauds():
    return sorted(int(name[1:]) for name in dir(termios)
                  if re.fullmatch(r"B[1-9][0-9]*", name))


def validate_baud(baud):
    if not isinstance(baud, int) or baud not in supported_bauds():
        raise ValueError("이 시스템에서 지원하는 baud를 지정하세요: " +
                         ", ".join(map(str, supported_bauds())))
    return getattr(termios, f"B{baud}")


def validate_limits(seconds, max_bytes):
    if not 0 < seconds <= MAX_SECONDS:
        raise ValueError(f"--seconds는 0보다 크고 {MAX_SECONDS:g} 이하여야 합니다.")
    if not isinstance(max_bytes, int) or not 0 < max_bytes <= MAX_BYTES:
        raise ValueError(f"--max-bytes는 1부터 {MAX_BYTES}까지여야 합니다.")


def validate_port(port):
    """Only explicit macOS callout character devices, never symlinks/files."""
    if not re.fullmatch(r"/dev/cu\.[A-Za-z0-9_.-]+", port):
        raise ValueError("--port에 명시적인 /dev/cu.* 장치 경로를 지정하세요.")
    info = os.lstat(port)
    if not stat.S_ISCHR(info.st_mode):
        raise ValueError("포트는 심볼릭 링크나 일반 파일이 아닌 문자 장치여야 합니다.")
    return info


def list_ports():
    """Only enumerate names. Does not open a port or probe a device."""
    return sorted(glob.glob("/dev/cu.*"))


def configure_receiver(fd, speed):
    original = termios.tcgetattr(fd)
    settings = copy.deepcopy(original)
    settings[0] = 0  # No input conversions or software flow control.
    settings[1] = 0
    settings[3] = 0  # No echo, canonical processing, or signal characters.
    clear_flags = ("CSIZE", "PARENB", "PARODD", "CSTOPB", "CRTSCTS",
                   "CCTS_OFLOW", "CRTS_IFLOW", "CDTR_IFLOW", "CDSR_OFLOW",
                   "CCAR_OFLOW", "HUPCL")
    for flag in clear_flags:
        settings[2] &= ~getattr(termios, flag, 0)
    settings[2] |= termios.CS8 | termios.CREAD | termios.CLOCAL
    settings[4] = settings[5] = speed
    settings[6][termios.VMIN] = 0
    settings[6][termios.VTIME] = 0
    # Save before changing, including when tcsetattr itself fails.
    try:
        termios.tcsetattr(fd, termios.TCSANOW, settings)
    except BaseException:
        try:
            termios.tcsetattr(fd, termios.TCSANOW, original)
        except termios.error:
            pass
        raise
    return original


def create_outputs(output):
    path = Path(output).expanduser().absolute()
    if any(ord(c) < 32 for c in str(path)):
        raise ValueError("출력 경로에 제어 문자를 사용할 수 없습니다.")
    paths = [path, Path(str(path) + ".json"), Path(str(path) + ".sha256")]
    handles = []
    created = []
    try:
        for target in paths:
            fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL |
                         getattr(os, "O_CLOEXEC", 0), 0o600)
            created.append(target)
            handles.append(os.fdopen(fd, "wb", buffering=0))
        return paths, handles
    except BaseException:
        for handle in handles:
            handle.close()
        for target in created:
            target.unlink()
        raise


def write_all(handle, data):
    position = 0
    while position < len(data):
        written = handle.write(data[position:])
        if not written:
            raise OSError("출력 파일에 기록하지 못했습니다.")
        position += written


def capture(port, baud, output, seconds=DEFAULT_SECONDS,
            max_bytes=DEFAULT_MAX_BYTES, *, _on_ready=None):
    """Capture, returning (metadata, exit_code). _on_ready is a PTY test hook."""
    speed = validate_baud(baud)
    validate_limits(seconds, max_bytes)
    expected = validate_port(port)
    paths, handles = create_outputs(output)
    raw, metadata_file, checksum_file = handles
    fd = None
    original = None
    started = time.monotonic()
    checksum = hashlib.sha256()
    code = 0
    metadata = {
        "schemaVersion": 1, "sourcePort": Path(port).name,
        "baud": baud, "serialFormat": "8N1", "flowControl": "none",
        "startedAtUtc": utc_now(), "endedAtUtc": None,
        "timeLimitSeconds": seconds, "byteLimit": max_bytes,
        "bytesCaptured": 0, "stopReason": None, "error": None,
        "exclusiveAccess": False, "ttyRestored": None,
        "readEvents": [], "omittedReadEvents": 0,
        "timestampMeaning": "Host receipt time of each read, not each UART byte.",
    }
    try:
        # Read-only descriptor. No serial write, flush, break, or modem ioctl.
        fd = os.open(port, os.O_RDONLY | os.O_NOCTTY | os.O_NONBLOCK |
                     getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0))
        actual = os.fstat(fd)
        if not stat.S_ISCHR(actual.st_mode) or (actual.st_dev, actual.st_ino,
                actual.st_rdev) != (expected.st_dev, expected.st_ino, expected.st_rdev):
            raise OSError("검사한 포트와 열린 장치가 다릅니다.")
        if hasattr(termios, "TIOCEXCL"):
            try:
                fcntl.ioctl(fd, termios.TIOCEXCL)
                metadata["exclusiveAccess"] = True
            except OSError as error:
                if error.errno not in (errno.ENOTTY, errno.EINVAL,
                                        getattr(errno, "ENOTSUP", errno.EINVAL)):
                    raise
        original = configure_receiver(fd, speed)
        if _on_ready:
            _on_ready()
        deadline = time.monotonic() + seconds
        while metadata["bytesCaptured"] < max_bytes:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                metadata["stopReason"] = "timeout"
                break
            ready, _, _ = select.select([fd], [], [], min(remaining, 0.25))
            if not ready:
                continue
            try:
                data = os.read(fd, min(65536, max_bytes - metadata["bytesCaptured"]))
            except BlockingIOError:
                continue
            if not data:
                metadata["stopReason"] = "disconnected"
                metadata["error"] = "시리얼 장치가 연결 해제되었거나 EOF를 반환했습니다."
                code = 1
                break
            event = {"offset": metadata["bytesCaptured"], "length": 0,
                     "receivedAtUtc": utc_now(),
                     "elapsedSeconds": round(time.monotonic() - started, 6)}
            try:
                position = 0
                while position < len(data):
                    written = raw.write(data[position:])
                    if not written:
                        raise OSError("원본 파일에 기록하지 못했습니다.")
                    checksum.update(data[position:position + written])
                    position += written
                    metadata["bytesCaptured"] += written
                    event["length"] += written
            finally:
                if len(metadata["readEvents"]) < MAX_READ_EVENTS:
                    metadata["readEvents"].append(event)
                else:
                    metadata["omittedReadEvents"] += 1
        else:
            metadata["stopReason"] = "byte-limit"
    except KeyboardInterrupt:
        metadata["stopReason"] = "interrupted"
        code = 130
    except (OSError, termios.error) as error:
        metadata["stopReason"] = "io-error"
        metadata["error"] = f"시리얼 설정·수신 또는 파일 기록 실패: {error}"
        code = 1
    finally:
        if fd is not None:
            if original is not None:
                try:
                    termios.tcsetattr(fd, termios.TCSANOW, original)
                    metadata["ttyRestored"] = True
                except (OSError, termios.error):
                    metadata["ttyRestored"] = False
                    metadata["restoreWarning"] = "TTY 설정 복원 실패: 장치 분리 여부를 확인하세요."
                    code = code or 1
            if metadata["exclusiveAccess"] and hasattr(termios, "TIOCNXCL"):
                try:
                    fcntl.ioctl(fd, termios.TIOCNXCL)
                    metadata["exclusiveAccessReleased"] = True
                except OSError:
                    metadata["exclusiveAccessReleased"] = False
            os.close(fd)
        metadata["endedAtUtc"] = utc_now()
        metadata["durationSeconds"] = round(time.monotonic() - started, 6)
        metadata["sha256"] = checksum.hexdigest()
        try:
            raw.flush()
            os.fsync(raw.fileno())
            write_all(metadata_file, (json.dumps(metadata, ensure_ascii=False,
                      indent=2) + "\n").encode("utf-8"))
            write_all(checksum_file, f"{metadata['sha256']}  {paths[0].name}\n".encode("utf-8"))
            for handle in (metadata_file, checksum_file):
                handle.flush()
                os.fsync(handle.fileno())
        finally:
            for handle in handles:
                handle.close()
    return metadata, code


def main(argv=None):
    parser = argparse.ArgumentParser(description="macOS UART 수신 전용 원본 캡처 (8N1)")
    parser.add_argument("--list", action="store_true", help="/dev/cu.* 이름만 나열; 포트를 열지 않음")
    parser.add_argument("--port", help="확인한 /dev/cu.* 장치 경로")
    parser.add_argument("--baud", type=int, help="확인한 통신 속도; 자동 추정하지 않음")
    parser.add_argument("--output", help="새 원본 파일 경로; 기존 파일 덮어쓰기 금지")
    parser.add_argument("--seconds", type=float, default=DEFAULT_SECONDS,
                        help=f"최대 캡처 시간, 기본 {DEFAULT_SECONDS:g}초 / 최대 {MAX_SECONDS:g}초")
    parser.add_argument("--max-bytes", type=int, default=DEFAULT_MAX_BYTES,
                        help="최대 원본 바이트, 기본 16 MiB / 최대 256 MiB")
    args = parser.parse_args(argv)
    if args.list:
        if args.port is not None or args.baud is not None or args.output is not None:
            parser.error("--list는 캡처 옵션 --port/--baud/--output과 함께 사용할 수 없습니다.")
        ports = list_ports()
        print("\n".join(ports) if ports else "발견된 /dev/cu.* 포트가 없습니다.")
        return 0
    if args.port is None or args.baud is None or args.output is None:
        parser.error("캡처에는 --port, --baud, --output을 모두 명시해야 합니다.")
    try:
        result, code = capture(args.port, args.baud, args.output,
                               args.seconds, args.max_bytes)
    except (ValueError, OSError, termios.error) as error:
        print(f"캡처를 완료하지 못했습니다: {error}", file=sys.stderr)
        return 1
    print(f"종료: {result['stopReason']} / {result['bytesCaptured']} bytes")
    print(f"SHA-256: {result['sha256']}")
    print(f"원본: {args.output}\n기록: {args.output}.json\n해시: {args.output}.sha256")
    if result["error"]:
        print(result["error"], file=sys.stderr)
    if result.get("restoreWarning"):
        print(result["restoreWarning"], file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
