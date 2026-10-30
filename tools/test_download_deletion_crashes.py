"""Local host-driver safety regressions; no ADB, emulator or Android behavior is simulated."""

import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest

from download_deletion_crashes import Driver, PACKAGE, TEST_PACKAGE


class RecordingDriver(Driver):
    def __init__(self, output, *, cleanup_failure=False, phase_failure=False, invalid=False):
        super().__init__(SimpleNamespace(output=output, cases=("fixture",)))
        self.cleanup_failure = cleanup_failure
        self.phase_failure = phase_failure
        self.invalid = invalid
        self.stops = []

    def validate(self):
        if self.invalid:
            raise RuntimeError("Unvalidated target")
        return {PACKAGE: "controlled hash"}

    def run_case(self, case):
        if self.phase_failure:
            raise RuntimeError("Native phase failed")
        return dict(case=case)

    def shell(self, *args, **kwargs):
        if args[:2] != ("am", "force-stop"):
            raise AssertionError("Unexpected host mutation")
        self.stops.append(args[2])
        # A partial cleanup must still attempt the second package and publish no pass.
        failed = self.cleanup_failure and args[2] == PACKAGE
        return subprocess.CompletedProcess(args, int(failed), b"", b"")


class DownloadDeletionDriverSafetyTest(unittest.TestCase):
    def test_failed_cleanup_cannot_publish_a_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            driver = RecordingDriver(Path(temporary) / "run", cleanup_failure=True)
            with self.assertRaisesRegex(RuntimeError, "cleanup failed"):
                driver.run()
            self.assertEqual([PACKAGE, TEST_PACKAGE], driver.stops)
            self.assertFalse((driver.output / "result.json").exists())

    def test_failed_native_phase_still_cleans_both_packages(self):
        with tempfile.TemporaryDirectory() as temporary:
            driver = RecordingDriver(Path(temporary) / "run", phase_failure=True)
            with self.assertRaisesRegex(RuntimeError, "Native phase failed"):
                driver.run()
            self.assertEqual([PACKAGE, TEST_PACKAGE], driver.stops)
            self.assertFalse((driver.output / "result.json").exists())

    def test_unvalidated_target_is_never_force_stopped(self):
        with tempfile.TemporaryDirectory() as temporary:
            driver = RecordingDriver(Path(temporary) / "run", invalid=True)
            try:
                with self.assertRaisesRegex(RuntimeError, "Unvalidated target"):
                    driver.run()
                self.assertEqual([], driver.stops)
                self.assertFalse((driver.output / "result.json").exists())
            finally:
                driver.events.close()

    def test_success_requires_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            driver = RecordingDriver(Path(temporary) / "run")
            driver.run()
            result = json.loads((driver.output / "result.json").read_text())
            self.assertEqual([PACKAGE, TEST_PACKAGE], driver.stops)
            self.assertTrue(result["passed"])
            self.assertTrue(result["package_cleanup_passed"])


if __name__ == "__main__":
    unittest.main()
