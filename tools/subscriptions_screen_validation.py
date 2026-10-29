#!/usr/bin/env python3
"""Check the production Subscriptions screen on an acknowledged disposable AVD.

Build and install debug/test APKs with Android CLI first. This driver refuses
physical devices and APK mismatches; its instrumentation refuses existing library
records. It never installs/uninstalls packages or clears app data. Real default
Room records are seeded, checked across fresh processes, and selectively removed.
At each VISUAL checkpoint, inspect Android CLI layout/screenshots and create the
printed local approval file. At the TalkBack checkpoint, also select Headings.
The CLI layout inspector is stopped before native accessibility assertions.
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
TEST = "org.schabi.newpipe.local.subscription.SubscriptionScreenEmulatorTest"
RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
TALKBACK = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"


class Driver:
    def __init__(self, args):
        if not __debug__:
            raise RuntimeError("Run without Python optimization: safety and verification assertions must stay enabled")
        self.args = args
        self.nonce = uuid.uuid4().hex
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.private_directory = "files/subscription-screen-" + self.nonce
        self.results = {}

    def adb(self, *args, check=True, data=None):
        with self.output.joinpath("commands.jsonl").open("a") as stream:
            stream.write(json.dumps(list(args)) + "\n")
        return subprocess.run([self.args.adb, "-s", self.args.serial, *args], input=data,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              check=check, timeout=45)

    def shell(self, *args, check=True, data=None):
        return self.adb("shell", "-T", shlex.join(args), check=check, data=data)

    def private(self, name, check=True):
        return self.shell("run-as", PACKAGE, "cat", self.private_directory + "/" + name, check=check)

    def validate_target(self):
        assert self.args.allow_disposable, "Disposable environment acknowledgement required"
        assert re.fullmatch(r"emulator-\d+", self.args.serial), "Physical devices refused"
        name = self.adb("emu", "avd", "name").stdout.decode().splitlines()[0].strip()
        assert name == self.args.avd_name, (name, self.args.avd_name)
        assert self.shell("getprop", "ro.kernel.qemu").stdout.strip() == b"1"
        assert self.shell("getprop", "sys.boot_completed").stdout.strip() == b"1"
        hashes = {}
        for package, apk in ((PACKAGE, self.args.app_apk), (TEST_PACKAGE, self.args.test_apk)):
            path = self.shell("pm", "path", package).stdout.decode().strip()
            assert path.startswith("package:") and "\n" not in path
            installed = self.shell("sha256sum", path[8:]).stdout.decode().split()[0]
            expected = hashlib.sha256(apk.read_bytes()).hexdigest()
            assert installed == expected, package + " APK mismatch"
            hashes[package] = expected
        self.output.joinpath("target.json").write_text(json.dumps(dict(
            avd=name, serial=self.args.serial, nonce=self.nonce, apk_sha256=hashes,
            fingerprint=self.shell("getprop", "ro.build.fingerprint").stdout.decode().strip()
        ), indent=2) + "\n")
        self.shell("run-as", PACKAGE, "mkdir", "-p", "files")
        self.shell("run-as", PACKAGE, "tee", "files/subscription-screen-allow", data=self.nonce.encode())

    def save_settings(self):
        self.saved = {}
        for namespace, key in (("system", "font_scale"), ("secure", "enabled_accessibility_services"),
                               ("secure", "accessibility_enabled"),
                               ("secure", "accessibility_interactive_ui_timeout_ms")):
            self.saved[namespace + ":" + key] = self.shell("settings", "get", namespace, key).stdout.decode().strip()
        size = self.shell("wm", "size").stdout.decode()
        match = re.search(r"Override size: (\d+x\d+)", size)
        self.original_size = match[1] if match else None
        self.output.joinpath("settings-before.json").write_text(json.dumps(dict(settings=self.saved, wm_size=size), indent=2) + "\n")

    def restore_accessibility(self):
        for name, value in self.saved.items():
            namespace, key = name.split(":")
            if namespace != "secure":
                continue
            if value == "null":
                self.shell("settings", "delete", namespace, key)
            else:
                self.shell("settings", "put", namespace, key, value)

    def restore_settings(self):
        self.restore_accessibility()
        value = self.saved["system:font_scale"]
        if value == "null":
            self.shell("settings", "delete", "system", "font_scale")
        else:
            self.shell("settings", "put", "system", "font_scale", value)
        self.shell("wm", "size", self.original_size or "reset")
        restored_size = self.shell("wm", "size").stdout.decode()
        match = re.search(r"Override size: (\d+x\d+)", restored_size)
        assert (match[1] if match else None) == self.original_size
        for name, expected in self.saved.items():
            namespace, key = name.split(":")
            assert self.shell("settings", "get", namespace, key).stdout.decode().strip() == expected

    def stop_inspector(self):
        self.shell("am", "force-stop", "com.android.cli.interact.instrumentation")

    def event(self, x, y, buttons):
        result = self.adb("emu", "event", "mouse", str(x), str(y), "0", str(buttons))
        assert b"OK" in result.stdout and b"KO" not in result.stdout

    def tap(self, point):
        self.event(point["x"], point["y"], 1)
        time.sleep(.06)
        self.event(point["x"], point["y"], 0)

    def heading_gesture(self):
        # Kernel-level touchscreen input reaches TalkBack's gesture recognition.
        self.event(300, 350, 1)
        for step in range(1, 7):
            self.event(300, 350 + 450 * step // 6, 1)
            time.sleep(.015)
        self.event(300, 800, 0)

    def run_case(self, case, phase, mode="list", visual=False, large=False):
        self.stop_inspector()
        self.shell("am", "force-stop", PACKAGE)
        command = ["am", "instrument", "-w", "-r", "-e", "class", TEST,
                   "-e", "subscriptionScreenDisposable", "true",
                   "-e", "subscriptionScreenNonce", self.nonce,
                   "-e", "subscriptionScreenCase", case,
                   "-e", "subscriptionScreenPhase", phase,
                   "-e", "subscriptionScreenMode", mode,
                   "-e", "subscriptionScreenVisual", str(visual).lower(),
                   "-e", "subscriptionScreenLarge", str(large).lower(), RUNNER]
        ready = None
        approved = False
        gestures = set()
        with self.output.joinpath(case + "-instrumentation.txt").open("wb") as stream:
            process = subprocess.Popen([self.args.adb, "-s", self.args.serial, "shell", shlex.join(command)], stdout=stream, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 300
            try:
                while process.poll() is None and time.monotonic() < deadline:
                    if visual and ready is None:
                        probe = self.private(case + "-ready.json", check=False)
                        if probe.returncode == 0:
                            ready = json.loads(probe.stdout)
                            self.output.joinpath(case + "-ready.json").write_text(json.dumps(ready, indent=2) + "\n")
                            print(f"VISUAL {case}: inspect actual screen; create {self.output / (case + '-approved')}", flush=True)
                    if ready is not None and not approved and self.output.joinpath(case + "-approved").is_file():
                        self.stop_inspector()
                        self.shell("run-as", PACKAGE, "touch", self.private_directory + "/" + case + "-release")
                        approved = True
                    if phase == "talkback" and approved:
                        for service in (3, 4):
                            if service in gestures:
                                continue
                            probe = self.private(f"{case}-next-{service}", check=False)
                            if probe.returncode == 0:
                                if service == 3:
                                    self.tap(ready["focusStart"])
                                    time.sleep(.7)
                                self.heading_gesture()
                                gestures.add(service)
                                print(f"TalkBack heading gesture -> ID {service}", flush=True)
                    time.sleep(.3)
                assert process.poll() is not None, "Instrumentation timeout: " + case
                assert process.returncode == 0, "ADB client failed: " + case
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=10)
        output = self.output.joinpath(case + "-instrumentation.txt").read_text()
        assert "OK (1 test)" in output and "FAILURES" not in output and "Process crashed" not in output, output
        result = json.loads(self.private(case + ".json").stdout)
        assert result["case"] == case
        self.output.joinpath(case + ".json").write_text(json.dumps(result, indent=2) + "\n")
        self.results[case] = result
        print("PASS " + case, flush=True)

    def run(self):
        self.validate_target()
        self.save_settings()
        success = False
        try:
            self.run_case("seed", "seed")
            self.shell("settings", "put", "system", "font_scale", "1.0")
            self.shell("wm", "size", "reset")
            self.run_case("list", "inspect", visual=True)
            self.run_case("grid", "inspect", mode="grid", visual=True)
            self.shell("settings", "put", "system", "font_scale", "2.0")
            self.shell("wm", "size", "440x1280")
            self.run_case("large", "inspect", visual=True, large=True)
            self.shell("settings", "put", "system", "font_scale", "1.0")
            self.shell("wm", "size", "reset")
            self.shell("settings", "put", "secure", "enabled_accessibility_services", TALKBACK)
            self.shell("settings", "put", "secure", "accessibility_enabled", "1")
            self.shell("settings", "put", "secure", "accessibility_interactive_ui_timeout_ms", "30000")
            self.run_case("talkback", "talkback", visual=True)
            self.restore_accessibility()
            self.run_case("delete", "delete", visual=True)
            self.run_case("relaunch", "inspect", visual=True)
            assert len({self.results[c]["pid"] for c in self.results}) == len(self.results), "Expected fresh application processes"
            original = self.results["seed"]["rows"]
            surviving = [row for row in original if row["serviceId"] != 99]
            for case in ("list", "grid", "large", "talkback"):
                assert self.results[case]["rows"] == original, case
            for case in ("delete", "relaunch"):
                assert self.results[case]["rows"] == surviving, case
            assert self.results["talkback"]["focusedHeadings"] == ["Unavailable service (ID 3)", "Unavailable service (ID 4)"]
            success = True
        finally:
            try:
                # A partially seeded run may still have saved its original preferences.
                if self.private("state.json", check=False).returncode == 0:
                    self.run_case("cleanup", "cleanup")
                    assert self.results["cleanup"]["preferencesRestored"]
            except BaseException:
                success = False
                raise
            finally:
                try:
                    self.restore_settings()
                except BaseException:
                    success = False
                    raise
                finally:
                    self.output.joinpath("summary.json").write_text(json.dumps(dict(
                        passed=success, cases=self.results, nonce=self.nonce,
                        avd=self.args.avd_name, serial=self.args.serial), indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--allow-disposable", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--app-apk", type=Path, default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--test-apk", type=Path, default=Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    Driver(parser.parse_args()).run()


if __name__ == "__main__":
    main()
