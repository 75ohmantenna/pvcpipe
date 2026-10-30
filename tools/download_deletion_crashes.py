#!/usr/bin/env python3
"""Kill only an explicitly allowed disposable emulator's debug app at deletion barriers.

Build/install APKs with Android CLI first. This driver verifies both installed APK
hashes, exact AVD identity and each PID's command line. It never installs,
uninstalls, clears app data or invokes connected Gradle tests. Every case creates
new private fixtures; output and fixtures are retained, including on failure.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid


PACKAGE = "org.seventyfiveohmantenna.pvcpipe.debug"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
TEST = "us.shandian.giga.service.DownloadDeletionCrashTest#runPhase"
CASES = tuple(f"{kind}-{boundary}" for kind in ("finished", "pending")
              for boundary in ("before", "reply", "receipt", "committed"))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


class Driver:
    def __init__(self, args):
        self.args = args
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.events = (self.output / "events.jsonl").open("x", encoding="utf-8")
        self.active = None

    def event(self, kind, **fields):
        self.events.write(json.dumps(dict(kind=kind, monotonic=time.monotonic(), **fields)) + "\n")
        self.events.flush()

    def adb(self, *args, data=None, check=True):
        result = subprocess.run(["adb", "-s", self.args.serial, *args], input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
        if check:
            require(result.returncode == 0, f"ADB {args!r}: {result.stderr!r} {result.stdout!r}")
        return result

    def shell(self, *args, **kwargs):
        return self.adb("shell", "-T", shlex.join(args), **kwargs)

    def private(self, package, path, check=True):
        return self.shell("run-as", package, "/system/bin/cat", path, check=check)

    def validate(self):
        require(self.args.allow_disposable, "Explicit disposable acknowledgement required")
        require(re.fullmatch(r"emulator-\d+", self.args.serial), "Physical devices refused")
        name = self.adb("emu", "avd", "name").stdout.decode().splitlines()[0].strip()
        require(name == self.args.avd_name, "AVD name mismatch")
        for prop, value in [("ro.kernel.qemu", b"1"), ("ro.build.version.sdk", b"26"),
                            ("sys.boot_completed", b"1")]:
            require(self.shell("getprop", prop).stdout.strip() == value, "Unexpected " + prop)
        hashes = {}
        for package, apk in [(PACKAGE, self.args.app_apk), (TEST_PACKAGE, self.args.test_apk)]:
            paths = self.shell("pm", "path", package).stdout.decode().splitlines()
            require(len(paths) == 1 and paths[0].startswith("package:/data/app/"),
                    "Expected one installed APK for " + package)
            installed = self.adb("exec-out", "cat", paths[0][8:]).stdout
            hashes[package] = digest(installed)
            require(hashes[package] == digest(apk.read_bytes()), package + " APK mismatch")
        require(("instrumentation:" + RUNNER + " (target=" + PACKAGE + ")").encode()
                in self.shell("pm", "list", "instrumentation").stdout,
                "Expected normal instrumentation runner")
        self.shell("run-as", PACKAGE, "mkdir", "-p", "files")
        self.event("validated", avd=name, serial=self.args.serial, apk_sha256=hashes)
        return hashes

    def start(self, case, nonce, phase):
        command = ["am", "instrument", "-w", "-r", "-e", "class", TEST,
                   "-e", "deleteCrashDisposable", "true", "-e", "deleteCrashNonce", nonce,
                   "-e", "deleteCrashCase", case, "-e", "deleteCrashPhase", phase, RUNNER]
        log = self.output / f"{case}-{phase}.txt"
        stream = log.open("xb")
        process = subprocess.Popen(["adb", "-s", self.args.serial, "shell", shlex.join(command)],
                                   stdout=stream, stderr=subprocess.STDOUT)
        self.active = (process, stream)
        self.event("start", case=case, nonce=nonce, phase=phase, command=command)
        return process, stream, log

    def wait_marker(self, case, nonce, phase, process, log):
        path = f"files/download-delete-crashes/{nonce}/{phase}.json"
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            result = self.private(PACKAGE, path, check=False)
            if result.returncode == 0:
                value = json.loads(result.stdout)
                require(value["case"] == case and value["nonce"] == nonce
                        and value["phase"] == phase, "Marker mismatch")
                self.event("marker", **value)
                return value
            require(process.poll() is None, f"No {phase} marker:\n{log.read_text()}")
            time.sleep(0.2)
        raise TimeoutError(f"No {phase} marker: {log.read_text()}")

    def finish(self, process, stream, log):
        process.wait(timeout=30)
        stream.close()
        self.active = None
        output = log.read_text()
        require(process.returncode == 0 and "OK (1 test)" in output
                and "FAILURES" not in output and "shortMsg=" not in output
                and "INSTRUMENTATION_STATUS_CODE: -3" not in output,
                "Native verification failed:\n" + output)

    def preserved(self, nonce):
        prefix = "files/download-delete-crashes/" + nonce + "/"
        result = {}
        for name in ("unrelated-finished.bin", "unrelated-pending.bin", "pending_downloads/8765",
                     "sentinel.db", "sentinel.db-wal"):
            data = self.private(PACKAGE, prefix + name).stdout
            require(bool(data), "Empty preservation fixture: " + name)
            result[name] = digest(data)
        return result

    def kill(self, marker):
        pid = str(int(marker["pid"]))
        cmdline = self.private(PACKAGE, f"/proc/{pid}/cmdline").stdout.split(b"\0", 1)[0]
        require(cmdline == PACKAGE.encode(), "Refusing to kill unexpected process")
        self.shell("run-as", PACKAGE, "kill", "-9", pid)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if self.private(PACKAGE, f"/proc/{pid}/cmdline", check=False).returncode != 0:
                self.event("sigkill", pid=int(pid), cmdline=cmdline.decode())
                return
            time.sleep(0.1)
        raise TimeoutError("Killed PID still present")

    def run_case(self, case):
        nonce = uuid.uuid4().hex
        self.shell("run-as", PACKAGE, "tee", "files/download-delete-crash-allow", data=nonce.encode())
        process, stream, log = self.start(case, nonce, "prepare")
        self.wait_marker(case, nonce, "prepared", process, log)
        self.finish(process, stream, log)
        process, stream, log = self.start(case, nonce, "delete")
        marker = self.wait_marker(case, nonce, "started", process, log)
        kind, boundary = case.split("-")
        provider = f"files/download-delete/{nonce}-{kind}/"
        if boundary in ("before", "reply"):
            deadline = time.monotonic() + 45
            while self.private(TEST_PACKAGE, provider + "entered", check=False).returncode != 0:
                require(process.poll() is None, "Delete ended before provider barrier")
                require(time.monotonic() < deadline, "Provider barrier timeout")
                time.sleep(0.2)
        else:
            marker = self.wait_marker(case, nonce, "held", process, log)
        before = self.preserved(nonce)
        listing = self.shell("run-as", TEST_PACKAGE, "/system/bin/ls", "-1", provider).stdout.decode().splitlines()
        require(("media.bin" in listing) == (boundary == "before"), "Wrong provider barrier state")
        if boundary == "before":
            require(self.private(TEST_PACKAGE, provider + "media.bin").stdout
                    == b"controlled deletion fixture\n", "Original media bytes changed")
        self.kill(marker)
        process.wait(timeout=20)
        stream.close()
        self.active = None
        require("OK (1 test)" not in log.read_text(), "Killed phase unexpectedly passed")
        after = self.preserved(nonce)
        require(before == after, "Unrelated bytes or committed WAL changed across SIGKILL")
        self.event("preserved-across-kill", case=case, nonce=nonce, sha256=after)
        if boundary in ("before", "reply"):
            self.shell("run-as", TEST_PACKAGE, "touch", provider + "release")
        process, stream, log = self.start(case, nonce, "verify")
        verified = self.wait_marker(case, nonce, "verified", process, log)
        require(verified["pid"] != marker["pid"], "Verification reused killed PID")
        self.finish(process, stream, log)
        for name in ("unrelated-finished.bin", "unrelated-pending.bin", "pending_downloads/8765"):
            current = self.private(PACKAGE, f"files/download-delete-crashes/{nonce}/{name}").stdout
            require(digest(current) == before[name], "Verification changed unrelated " + name)
        listing = self.shell("run-as", TEST_PACKAGE, "/system/bin/ls", "-1", provider).stdout.decode().splitlines()
        require("media.bin" not in listing, "Media unexpectedly recreated")
        outcome = "retained-until-regrant" if boundary == "reply" else "removed"
        self.event("case-pass", case=case, nonce=nonce, outcome=outcome,
                   killed_pid=marker["pid"], verify_pid=verified["pid"])
        print(f"PASS {case}: {outcome}", flush=True)
        return dict(case=case, nonce=nonce, outcome=outcome)

    def run(self):
        hashes = self.validate()
        cases = []
        cleanup = []
        try:
            for case in self.args.cases:
                cases.append(self.run_case(case))
        finally:
            # Only the validated disposable debug/test packages; no uninstall or app-data clearing.
            for package in (PACKAGE, TEST_PACKAGE):
                stopped = self.shell("am", "force-stop", package, check=False)
                cleanup.append(stopped.returncode == 0)
                self.event("force-stop", package=package, returncode=stopped.returncode)
            if self.active:
                process, stream = self.active
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=5)
                stream.close()
            self.events.close()
        require(all(cleanup), "Disposable package cleanup failed; no passing result published")
        result = dict(passed=True, cases=cases, sigkills=len(cases), apk_sha256=hashes,
                      package_cleanup_passed=True,
                      note="Reply cases reproduce retained uncertainty before receipt persistence; "
                           "no claim of atomic provider/database commit or power-loss coverage.")
        (self.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--allow-disposable", action="store_true", required=True)
    parser.add_argument("--app-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cases", nargs="+", choices=CASES, default=CASES)
    Driver(parser.parse_args()).run()


if __name__ == "__main__":
    main()
