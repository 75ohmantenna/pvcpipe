#!/usr/bin/env python3
"""Run guarded bookmark UI/SQLite checks on an explicitly disposable emulator.

Use Android CLI to start the AVD and install the built debug/test APKs first.
This driver never installs, uninstalls, clears app data, or invokes Gradle device
tasks. The instrumentation substitutes an isolated in-memory Room database and
restores the singleton, its three preference overrides and image-quality state.
With --visual, the final metadata case waits for external Android CLI inspection;
write the printed visual-release path only after inspecting its screenshot.
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
TEST = "org.schabi.newpipe.local.bookmark.BookmarkEmulatorTest"
CASES = (
    "rapid-edits", "save-without-view", "pending-view-recreation",
    "replacement-fragment-owner", "remote-failure-retry", "local-failure-retry",
    "metadata-preservation",
)


class Driver:
    def __init__(self, args):
        self.args = args
        self.nonce = uuid.uuid4().hex
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.private_directory = f"files/bookmark-emulator-{self.nonce}"

    def adb(self, *args, check=True, data=None):
        result = subprocess.run(["adb", "-s", self.args.serial, *args], input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=45)
        if check and result.returncode:
            raise RuntimeError(f"ADB failed: {args!r}: {result.stderr.decode(errors='replace')}")
        return result

    def shell(self, *args, check=True, data=None):
        return self.adb("shell", "-T", shlex.join(args), check=check, data=data)

    def private(self, path, check=True):
        return self.shell("run-as", PACKAGE, "cat", path, check=check)

    def database_family(self):
        result = {}
        for suffix in ("", "-wal", "-shm", "-journal"):
            name = "newpipe.db" + suffix
            contents = self.private("databases/" + name, check=False)
            result[name] = hashlib.sha256(contents.stdout).hexdigest() if contents.returncode == 0 else None
        return result

    def validate_target(self):
        assert re.fullmatch(r"emulator-\d+", self.args.serial), "Physical devices are refused"
        assert self.args.allow_disposable, "Explicit disposable environment acknowledgement required"
        name = self.adb("emu", "avd", "name").stdout.decode().splitlines()[0].strip()
        assert name == self.args.avd_name, (name, self.args.avd_name)
        assert self.shell("getprop", "ro.kernel.qemu").stdout.strip() == b"1"
        assert self.shell("getprop", "sys.boot_completed").stdout.strip() == b"1"
        hashes = {}
        for package, apk in ((PACKAGE, self.args.app_apk), (TEST_PACKAGE, self.args.test_apk)):
            paths = self.shell("pm", "path", package).stdout.decode().splitlines()
            assert len(paths) == 1 and paths[0].startswith("package:"), paths
            installed = self.shell("sha256sum", paths[0][8:]).stdout.decode().split()[0]
            expected = hashlib.sha256(apk.read_bytes()).hexdigest()
            assert installed == expected, package + " APK mismatch"
            hashes[package] = expected
        target = dict(avd=name, serial=self.args.serial, nonce=self.nonce, apk_sha256=hashes,
                      sdk=self.shell("getprop", "ro.build.version.sdk").stdout.decode().strip(),
                      fingerprint=self.shell("getprop", "ro.build.fingerprint").stdout.decode().strip())
        self.output.joinpath("target.json").write_text(json.dumps(target, indent=2) + "\n")
        self.shell("run-as", PACKAGE, "mkdir", "-p", "files")
        self.shell("run-as", PACKAGE, "tee", "files/bookmark-emulator-allow", data=self.nonce.encode())

    def run(self):
        self.validate_target()
        before = self.database_family()
        self.output.joinpath("default-database-before.json").write_text(json.dumps(before, indent=2) + "\n")
        command = ["am", "instrument", "-w", "-r", "-e", "class", TEST,
                   "-e", "bookmarkDisposable", "true", "-e", "bookmarkNonce", self.nonce]
        if self.args.visual:
            command += ["-e", "bookmarkVisual", "true"]
        command += [RUNNER]
        self.output.joinpath("command.json").write_text(json.dumps(command, indent=2) + "\n")
        print(f"Evidence directory: {self.output}", flush=True)
        print(f"Private evidence: {self.private_directory}", flush=True)
        with self.output.joinpath("instrumentation.txt").open("wb") as stream:
            process = subprocess.Popen(["adb", "-s", self.args.serial, "shell", shlex.join(command)],
                                       stdout=stream, stderr=subprocess.STDOUT)
            visual_reported = False
            deadline = time.monotonic() + 600
            try:
                while process.poll() is None and time.monotonic() < deadline:
                    if self.args.visual and not visual_reported:
                        ready = self.private(self.private_directory + "/visual-ready", check=False)
                        if ready.returncode == 0:
                            print("VISUAL READY: inspect with Android CLI, then release " +
                                  self.private_directory + "/visual-release", flush=True)
                            visual_reported = True
                    time.sleep(0.5)
                assert process.poll() is not None, "Instrumentation timed out"
                assert process.returncode == 0, "ADB instrumentation client failed"
            finally:
                if process.poll() is None:
                    process.terminate()  # Stop this host client; never select an Android process by default.
                    process.wait(timeout=10)
        output = self.output.joinpath("instrumentation.txt").read_text()
        assert "OK (7 tests)" in output and "FAILURES" not in output, output
        results = {}
        for case in CASES:
            result = json.loads(self.private(f"{self.private_directory}/{case}.json").stdout)
            assert result["case"] == case
            assert not result["dirty"] and result["pendingDeletions"] == 0, result
            assert result["adapterOrder"] == result["storedOrder"], result
            self.output.joinpath(case + ".json").write_text(json.dumps(result, indent=2) + "\n")
            results[case] = result
            print("PASS " + case, flush=True)
        after = self.database_family()
        summary = dict(passed=list(results), expected=list(CASES), serial=self.args.serial,
                       avd=self.args.avd_name, nonce=self.nonce, visual_checkpoint_reached=visual_reported,
                       default_database_family_before=before, default_database_family_after=after,
                       default_database_family_unchanged=before == after)
        self.output.joinpath("summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        print("Default database family unchanged:", before == after, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--allow-disposable", action="store_true")
    parser.add_argument("--visual", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--app-apk", type=Path, default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--test-apk", type=Path, default=Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    Driver(parser.parse_args()).run()


if __name__ == "__main__":
    main()
