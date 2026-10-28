#!/usr/bin/env python3
"""Host-driven restore checks on an explicitly allowed disposable emulator.

Create/start the AVD and install/launch the app with Android CLI first. Build and
install the normal and fixture androidTest APKs separately. This driver refuses physical devices and
checks the exact AVD name, installed APK hashes, and a per-run private marker.
It never installs, uninstalls, invokes pm clear, or uses Gradle device tasks.
The guarded fixtures replace the default database/preferences on that disposable
installation; they are not suitable for an existing user installation.

Fixture/fault phases use RestoreFixtureRunner (a bare Application) so startup
cannot consume the journal before a deterministic checkpoint. Verification uses
AndroidJUnitRunner and the actual production App, including attachBaseContext.
The two choice cases use the real settings dialog/system document picker and
production ProcessPhoenix restart through Android CLI outside target instrumentation.
Other nonterminal phases stay alive until the host sends SIGKILL; their expected
instrumentation termination is not counted as a passing JUnit result.
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
FIXTURE_PACKAGE = PACKAGE + ".restorefixture"
TEST = "org.schabi.newpipe.settings.RestoreEmulatorTest#runPhase"
NORMAL = "androidx.test.runner.AndroidJUnitRunner"
BARE = "org.schabi.newpipe.settings.RestoreFixtureRunner"
CASES = (
    "choice-db", "choice-settings", "staged-db", "reject", "legacy-reject",
    "activation-replace", "activation-wal", "activation-preferences",
    "rollback-main", "rollback-wal", "committed-later",
)


class Driver:
    def __init__(self, args):
        self.args = args
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.nonce = uuid.uuid4().hex
        self.events = (self.output / "events.jsonl").open("x", encoding="utf-8")
        self.active = []
        self.cli_count = 0
        self.journal_present = {}

    def event(self, kind, **fields):
        self.events.write(json.dumps(dict(kind=kind, monotonic=time.monotonic(),
                                         **fields), sort_keys=True) + "\n")
        self.events.flush()

    def adb(self, *args, check=True, data=None):
        result = subprocess.run(["adb", "-s", self.args.serial, *args], input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=45)
        if check and result.returncode:
            raise RuntimeError(f"ADB failed: {args!r}: {result.stderr.decode(errors='replace')}")
        return result

    def shell(self, *args, check=True, data=None):
        return self.adb("shell", shlex.join(args), check=check, data=data)

    def private(self, path, check=True):
        # exec-out does not propagate a failed remote cat's exit status on this ADB version.
        # Shell v2 without a PTY preserves bytes and reports remote failures separately.
        return self.adb("shell", "-T", shlex.join(["run-as", PACKAGE, "cat", path]), check=check)

    def validate_target(self):
        assert re.fullmatch(r"emulator-\d+", self.args.serial), "Physical devices are refused"
        assert self.args.allow_disposable, "Explicit disposable environment acknowledgement required"
        name = self.adb("emu", "avd", "name").stdout.decode().splitlines()[0].strip()
        assert name == self.args.avd_name, (name, self.args.avd_name)
        assert self.shell("getprop", "ro.kernel.qemu").stdout.strip() == b"1"
        assert self.shell("getprop", "sys.boot_completed").stdout.strip() == b"1"
        versions = {}
        for package, apk in [(PACKAGE, self.args.app_apk), (TEST_PACKAGE, self.args.test_apk),
                             (FIXTURE_PACKAGE, self.args.fixture_apk)]:
            paths = self.shell("pm", "path", package).stdout.decode().splitlines()
            assert len(paths) == 1 and paths[0].startswith("package:"), paths
            installed = self.adb("exec-out", "cat", paths[0][8:]).stdout
            expected = hashlib.sha256(apk.read_bytes()).hexdigest()
            assert hashlib.sha256(installed).hexdigest() == expected, package + " APK mismatch"
            versions[package] = expected
        self.event("target", avd=name, serial=self.args.serial, apk_sha256=versions,
                   sdk=self.shell("getprop", "ro.build.version.sdk").stdout.decode().strip(),
                   fingerprint=self.shell("getprop", "ro.build.fingerprint").stdout.decode().strip())
        self.shell("run-as", PACKAGE, "mkdir", "-p", "files")
        self.shell("run-as", PACKAGE, "tee", "files/restore-emulator-allow", data=self.nonce.encode())

    def start(self, case, phase, bare=True, step=None, occurrence=1):
        runner = BARE if bare else NORMAL
        command = ["am", "instrument", "-w", "-r", "-e", "class", TEST,
                   "-e", "restoreDisposable", "true", "-e", "restoreNonce", self.nonce,
                   "-e", "restoreCase", case, "-e", "restorePhase", phase]
        if step:
            command += ["-e", "restoreStep", step, "-e", "restoreOccurrence", str(occurrence)]
        command += [(FIXTURE_PACKAGE if bare else TEST_PACKAGE) + "/" + runner]
        log = self.output / f"{case}-{phase}.txt"
        stream = log.open("wb")
        process = subprocess.Popen(["adb", "-s", self.args.serial, "shell", shlex.join(command)],
                                   stdout=stream, stderr=subprocess.STDOUT)
        self.active.append((process, stream))
        self.event("phase-start", case=case, phase=phase, runner=runner, command=command)
        return process, log

    def marker(self, case, phase, process, log):
        path = f"files/restore-emulator-{self.nonce}/{case}/{phase}.json"
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            result = self.private(path, check=False)
            if result.returncode == 0:
                try:
                    value = json.loads(result.stdout)
                except json.JSONDecodeError:
                    time.sleep(0.2)
                    continue
                assert value["nonce"] == self.nonce and value["case"] == case
                (self.output / f"{case}-{phase}.json").write_text(json.dumps(value, indent=2) + "\n")
                self.event("checkpoint", **value)
                return value
            if process.poll() is not None:
                raise AssertionError(f"{case}/{phase} ended without marker:\n{log.read_text()}")
            time.sleep(0.3)
        raise TimeoutError(f"No checkpoint for {case}/{phase}: {log.read_text()}")

    def kill(self, value):
        pid = str(int(value["pid"]))
        command_line = self.private(f"/proc/{pid}/cmdline").stdout.split(b"\0", 1)[0]
        assert command_line == PACKAGE.encode(), command_line
        self.shell("run-as", PACKAGE, "kill", "-9", pid)
        self.await_exit(pid)
        self.event("sigkill", pid=int(pid), command_line=command_line.decode())

    def await_exit(self, pid):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            result = self.private(f"/proc/{pid}/cmdline", check=False)
            if result.returncode != 0:
                return
            time.sleep(0.25)
        raise AssertionError("Process did not exit: " + str(pid))

    def held(self, case, phase, step=None, occurrence=1):
        process, log = self.start(case, phase, step=step, occurrence=occurrence)
        value = self.marker(case, phase, process, log)
        assert value["state"] == "ready-for-kill"
        if phase == "prepare":
            assert value["mainSha256"] == value["savedMainSha256"]
            assert value["walSha256"] == value["savedWalSha256"]
            assert value["preferenceTag"] == "old"
            self.journal_present[case] = self.private("databases/newpipe.db-journal", check=False).returncode == 0
            self.event("seed-family", case=case, journal_present=self.journal_present[case])
        if phase == "activate":
            assert value["activation"]
            assert value["complete"] == (step == "AFTER_COMMIT")
            assert value["preferenceTag"] == ("new" if step in ("AFTER_PREFERENCES", "AFTER_COMMIT") else "old")
        if phase == "rollback":
            assert value["mainSha256"] == value["savedMainSha256"]
            assert value["preferenceTag"] == "new"
            if case == "rollback-wal":
                assert value["walSha256"] == value["savedWalSha256"]
        self.kill(value)
        process.wait(timeout=15)

    def verify(self, case):
        process, log = self.start(case, "verify", bare=False)
        value = self.marker(case, "verify", process, log)
        process.wait(timeout=45)
        output = log.read_text()
        assert value["state"] == "passed"
        assert "OK (1 test)" in output and "FAILURES" not in output, output
        assert value["productionApp"]
        assert value["application"] == "org.schabi.newpipe.DebugApp"
        self.event("case-pass", case=case)
        print(f"PASS {case}", flush=True)

    def android(self, *args, check=True):
        command = ["android", *map(str, args)]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=90)
        self.cli_count += 1
        self.output.joinpath(f"android-cli-{self.cli_count:03d}.txt").write_bytes(result.stdout)
        self.event("android-cli", command=command, returncode=result.returncode)
        if check:
            assert result.returncode == 0, result.stdout.decode(errors="replace")
        return result

    def layout(self, case, label):
        for attempt in range(6):
            target = self.output / f"{case}-ui-{label}-{attempt}.json"
            result = self.android("layout", "--device", self.args.serial,
                                  "--flat", "--output", target, check=False)
            if result.returncode == 0:
                return json.loads(target.read_text())
            time.sleep(0.5)
        raise AssertionError("Cannot obtain UI layout: " + label)

    def tap(self, selector):
        self.android("input", "--device", self.args.serial, "tap", selector,
                     "--wait", "10000")

    def restore_with_ui(self, case):
        name = f"{case}-{self.nonce}.zip"
        source = f"files/restore-emulator-{self.nonce}/{case}/source.zip"
        local = self.output / name
        local.write_bytes(self.private(source).stdout)
        self.shell("mkdir", "-p", "/sdcard/Download")
        self.adb("push", str(local), "/sdcard/Download/" + name)
        # Run outside target instrumentation: Android force-stops an instrumented app when
        # its process dies, which otherwise interferes with ProcessPhoenix's new activity.
        self.android("run", "--device", self.args.serial, "--apks", self.args.app_apk,
                     "--activity", "org.schabi.newpipe.MainActivity")
        self.layout(case, "main")
        self.tap("desc=Open drawer")
        self.tap("text=/^Settings$/i")
        self.tap("text=/^Backup and restore$/i")
        self.tap("text=/^Import database$/i")
        self.layout(case, "picker")
        self.tap("desc=Show roots")
        self.tap("text=/^Downloads$/i,id=android:id/title")
        self.layout(case, "downloads")
        self.tap("text=/^" + re.escape(name) + "$/")
        self.tap("text=/^OK$/i")
        self.layout(case, "restore-choice")
        pids = self.shell("pidof", PACKAGE).stdout.decode().split()
        assert len(pids) == 1, pids
        choice = "Restore without settings" if case == "choice-db" else "Restore with settings"
        self.tap("text=/^" + choice + "$/i")
        self.await_exit(pids[0])
        self.await_production_restart(case, int(pids[0]))
        self.layout(case, "restarted")

    def await_production_restart(self, case, old_pid):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            pids = self.shell("pidof", PACKAGE, check=False).stdout.decode().split()
            activities = self.shell("dumpsys", "activity", "activities").stdout.decode()
            resumed = [line.strip() for line in activities.splitlines()
                       if "mResumedActivity" in line and PACKAGE in line
                       and "org.schabi.newpipe.MainActivity" in line]
            if pids and str(old_pid) not in pids and resumed:
                self.event("production-restart", case=case, old_pid=old_pid,
                           new_pids=[int(pid) for pid in pids], resumed=resumed)
                return
            time.sleep(0.3)
        raise AssertionError("Production restart did not launch a new MainActivity process")

    def run(self):
        self.validate_target()
        passed = []
        try:
            for case in CASES:
                self.held(case, "prepare")
                if case.startswith("choice-"):
                    self.restore_with_ui(case)
                elif case.startswith("activation-"):
                    step, count = {"activation-replace": ("AFTER_REPLACE", 1),
                                   "activation-wal": ("AFTER_REMOVE_SIDECAR", 2),
                                   "activation-preferences": ("AFTER_PREFERENCES", 1)}[case]
                    self.held(case, "activate", step, count)
                elif case.startswith("rollback-"):
                    self.held(case, "activate", "AFTER_PREFERENCES")
                    occurrence = 1 if case == "rollback-main" else 2 + int(self.journal_present[case])
                    self.held(case, "rollback", "AFTER_REPLACE", occurrence)
                elif case == "committed-later":
                    self.held(case, "activate", "AFTER_COMMIT")
                    self.held(case, "later")
                self.verify(case)
                passed.append(case)
        finally:
            for process, stream in self.active:
                if process.poll() is None:
                    process.terminate()  # Stop the host client, not an arbitrary Android process.
                    process.wait(timeout=10)
                stream.close()
            self.output.joinpath("summary.json").write_text(json.dumps(
                dict(nonce=self.nonce, serial=self.args.serial, avd=self.args.avd_name,
                     passed=passed, expected=list(CASES)), indent=2) + "\n")
            self.events.close()
        assert len(passed) == len(CASES)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--allow-disposable", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--app-apk", type=Path, default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--test-apk", type=Path, default=Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    parser.add_argument("--fixture-apk", type=Path, required=True)
    Driver(parser.parse_args()).run()


if __name__ == "__main__":
    main()
