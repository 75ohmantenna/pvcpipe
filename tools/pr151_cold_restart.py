#!/usr/bin/env python3
"""PR151 disposable-API26 cold-death integration orchestrator (Python stdlib only).

Build/install the debug app and androidTest APK separately. This program never
builds, installs, uninstalls, clears data, pauses a mission, or edits checkpoints.
The only simulated component is the HTTP peer; instrumentation uses the real
service, manager, checkpoint loader and output storage.

Example:
  python3 tools/pr151_cold_restart.py --serial emulator-5554 \
      --disposable-avd-name pvcpipe-pr151-api26 --allow-disposable

Artifacts include every HTTP request/response, instrumentation output, checkpoint
results, process-death evidence and a final summary. A failed assertion exits 1.
"""

import argparse
import contextlib
import hashlib
import http.server
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time
import urllib.parse


PACKAGE = "org.seventyfiveohmantenna.pvcpipe.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = "us.shandian.giga.service.DownloadColdRestartIntegrationTest#runPhase"
SCENARIOS = ("stable", "replacement-bytes", "replacement-before-bytes",
             "unsolicited206", "earlier-resource")
FIXTURE_SIZE = 8 * 1024 * 1024
PARTIAL_BUDGET = 2 * 1024 * 1024
LAST_MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT"


def fixture(size, seed):
    """Must match the Android phase driver's byte-for-byte fixture generator."""
    return bytes((i * 31 + (i >> 8) * 17 + seed) & 255 for i in range(size))


class Evidence:
    def __init__(self, directory):
        self.directory = directory
        self.lock = threading.Lock()
        self.stream = (directory / "events.jsonl").open("x", encoding="utf-8")

    def record(self, kind, **fields):
        event = dict(kind=kind, monotonic=time.monotonic(), **fields)
        with self.lock:
            self.stream.write(json.dumps(event, sort_keys=True) + "\n")
            self.stream.flush()
        return event


class PeerState:
    """One bounded transfer gate, shared by all requests in a single phase."""
    def __init__(self, evidence, timeout):
        self.evidence = evidence
        self.timeout = timeout
        self.condition = threading.Condition()
        self.generation = 0
        self.scenario = ""
        self.epoch = 0
        self.policy = "normal"
        self.budget = 0
        self.hold_headers = False
        self.phase = ""
        self.requests = []
        self.fixtures = {}
        self.closed = False

    def configure(self, scenario, phase, epoch, budget, policy="normal", hold_headers=False):
        with self.condition:
            self.generation += 1
            self.scenario, self.phase, self.epoch = scenario, phase, epoch
            self.budget, self.policy = budget, policy
            self.hold_headers = hold_headers
            self.condition.notify_all()
        self.evidence.record("gate", scenario=scenario, phase=phase, epoch=epoch,
                             budget=budget, policy=policy, hold_headers=hold_headers)

    def stop(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()


class PeerHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass

    def do_HEAD(self):
        self.respond(False)

    def do_GET(self):
        self.respond(True)

    def respond(self, body):
        state = self.server.state
        self.connection.settimeout(state.timeout)
        path = urllib.parse.urlsplit(self.path).path
        with state.condition:
            generation = state.generation
            scenario, phase, epoch, policy = (state.scenario, state.phase,
                                             state.epoch, state.policy)
            request = dict(id=len(state.requests) + 1, scenario=scenario,
                           phase=phase, method=self.command, path=path,
                           range=self.headers.get("Range"),
                           if_range=self.headers.get("If-Range"),
                           accept_encoding=self.headers.get("Accept-Encoding"),
                           generation=generation, bytes_sent=0)
            state.requests.append(request)
        state.evidence.record("http-request", **request)
        try:
            parts = path.strip("/").split("/")
            if len(parts) != 2 or parts[0] != scenario:
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                request["status"] = 404
                return
            if parts[1] in ("media", "media2"):
                suffix = "2" if parts[1] == "media2" else ""
                self.send_response(302)
                self.send_header("Location", "/" + scenario + "/object" + suffix)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
                request["status"] = 302
                return
            if parts[1] not in ("object", "object2"):
                self.send_error(404)
                request["status"] = 404
                return
            if self.command == "HEAD":
                # Range has no meaning on HEAD. Exercise production's real
                # HTTP405 -> GET probe fallback instead of inventing HEAD206.
                self.send_response(405)
                self.send_header("Allow", "GET")
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
                request["status"] = 405
                return
            resource = 1 if parts[1] == "object2" else 0
            request["resource"] = resource
            payload = state.fixtures[(scenario, resource)]
            start, end = 0, len(payload) - 1
            ranged = request["range"] is not None
            if ranged:
                match = re.fullmatch(r"bytes=(\d+)-(\d*)", request["range"])
                if not match:
                    self.send_error(400, "Only one explicit byte range is supported")
                    request["status"] = 400
                    return
                start = int(match.group(1))
                end = min(int(match.group(2)), end) if match.group(2) else end
                if request["range"] == "bytes=0-" and request["if_range"] is None:
                    # The initializer wants headers, not a full body. A valid
                    # short range avoids blocking its close/drain on our gate.
                    end = 0
                if start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", "bytes */" + str(len(payload)))
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    request["status"] = 416
                    return
            # Mismatch deliberately returns 206 with a changed strong identity,
            # rather than honoring If-Range: this is the hostile integrity case.
            status = 206 if ranged or policy == "unsolicited206" else 200
            request.update(status=status, start=start, end=end,
                           etag='"cold-' + scenario + '-' + str(epoch) + '"')
            # A cleared checkpoint precedes even representation binding. Holding
            # only the body is insufficient: response headers bind the identity.
            probe = ranged and end - start + 1 <= 10
            gated = not probe and not (scenario == "earlier-resource" and resource == 0)
            deadline = time.monotonic() + state.timeout
            if body and gated:
                with state.condition:
                    if state.hold_headers:
                        request["header_gated"] = True
                        state.evidence.record("http-response-held", **request)
                    while state.hold_headers and not state.closed \
                            and generation == state.generation:
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            request["gate_timeout"] = True
                            return
                        state.condition.wait(remaining)
                    if state.closed or generation != state.generation:
                        return
            self.send_response(status)
            self.send_header("Content-Length", str(end - start + 1))
            if status == 206:
                self.send_header("Content-Range", "bytes %d-%d/%d" %
                                 (start, end, len(payload)))
            self.send_header("ETag", request["etag"])
            self.send_header("Last-Modified", LAST_MODIFIED)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Encoding", "identity")
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Connection", "close")
            self.end_headers()
            state.evidence.record("http-response-headers", **request)
            if not body:
                return
            position = start
            while position <= end:
                with state.condition:
                    while gated and state.budget == 0 and not state.closed \
                            and generation == state.generation:
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            request["gate_timeout"] = True
                            return
                        state.condition.wait(remaining)
                    if state.closed or generation != state.generation:
                        return
                    count = min(64 * 1024, end - position + 1)
                    if gated:
                        if state.budget is not None:
                            count = min(count, state.budget)
                            state.budget -= count
                self.wfile.write(payload[position:position + count])
                self.wfile.flush()
                request["bytes_sent"] += count
                position += count
        except (BrokenPipeError, ConnectionResetError, TimeoutError, OSError) as error:
            request["disconnect"] = str(error)
        finally:
            self.close_connection = True
            state.evidence.record("http-request-end", **request)


class PeerServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False


class Adb:
    def __init__(self, executable, serial, evidence, timeout):
        self.prefix = [executable, "-s", serial]
        self.evidence = evidence
        self.timeout = timeout

    def run(self, *args, check=True):
        completed = subprocess.run(self.prefix + list(args), text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   timeout=self.timeout)
        self.evidence.record("adb", args=list(args), returncode=completed.returncode,
                             output=completed.stdout)
        if check and completed.returncode:
            raise RuntimeError("adb %s failed: %s" % (" ".join(args), completed.stdout))
        return completed.stdout.strip()

    def pids(self):
        output = self.run("shell", "pidof", PACKAGE, check=False)
        if not output:
            return set()
        if not re.fullmatch(r"\d+(?:\s+\d+)*", output):
            raise RuntimeError("Cannot establish target PID: " + output)
        return {int(value) for value in output.split()}

    def require_disposable(self, avd_name):
        if self.run("get-state") != "device":
            raise RuntimeError("Selected adb transport is not ready")
        if self.run("shell", "getprop", "ro.kernel.qemu") != "1":
            raise RuntimeError("Refusing a non-emulator target")
        if self.run("shell", "getprop", "ro.build.version.sdk") != "26":
            raise RuntimeError("This harness requires API26, not another API level")
        lines = self.run("emu", "avd", "name").splitlines()
        actual = [line.strip() for line in lines if line.strip() and line.strip() != "OK"]
        if actual != [avd_name]:
            raise RuntimeError("Disposable AVD name mismatch: " + repr(actual))
        self.run("shell", "run-as", PACKAGE, "id")

    def kill(self, expected_pid):
        before = self.pids()
        if before != {expected_pid}:
            raise AssertionError("Expected only PID %d before SIGKILL, found %r" %
                                 (expected_pid, before))
        self.run("shell", "run-as", PACKAGE, "kill", "-9", str(expected_pid), check=False)
        deadline = time.monotonic() + 5
        pause = threading.Event()
        while expected_pid in self.pids():
            if time.monotonic() >= deadline:
                # No adb-root restart: use an already available root shell only.
                if self.run("shell", "id", "-u") == "0":
                    self.run("shell", "kill", "-9", str(expected_pid))
                else:
                    self.run("shell", "su", "0", "kill", "-9", str(expected_pid))
                break
            pause.wait(0.05)
        deadline = time.monotonic() + 10
        while expected_pid in self.pids():
            if time.monotonic() >= deadline:
                raise AssertionError("SIGKILL did not remove PID " + str(expected_pid))
            pause.wait(0.05)
        self.evidence.record("cold-death", old_pid=expected_pid, remaining=sorted(self.pids()))


class Instrumentation:
    def __init__(self, adb, evidence, timeout, label, arguments):
        self.adb, self.evidence, self.timeout, self.label = adb, evidence, timeout, label
        command = adb.prefix + ["shell", "am", "instrument", "-w", "-r",
                                "-e", "class", TEST]
        for key, value in arguments.items():
            command.extend(["-e", key, str(value)])
        command.append(RUNNER)
        self.process = subprocess.Popen(command, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.lines = queue.Queue()
        self.output = (evidence.directory / (label + ".instrumentation.txt")).open(
            "x", encoding="utf-8")
        self.thread = threading.Thread(target=self.consume, daemon=True)
        self.thread.start()
        self.evidence.record("instrumentation-start", label=label, args=arguments)

    def consume(self):
        try:
            for line in self.process.stdout:
                self.output.write(line)
                self.output.flush()
                self.lines.put(line.rstrip("\r\n"))
        finally:
            self.lines.put(None)
            self.output.close()

    def result(self):
        deadline = time.monotonic() + self.timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Instrumentation phase timed out: " + self.label)
            try:
                line = self.lines.get(timeout=remaining)
            except queue.Empty:
                raise TimeoutError("No phase result: " + self.label) from None
            if line is None:
                raise AssertionError("Instrumentation ended without coldResult: " + self.label)
            marker = "coldResult="
            if marker in line:
                result = json.loads(line.split(marker, 1)[1])
                self.evidence.record("checkpoint-result", label=self.label, result=result)
                return result

    def finish(self, killed):
        try:
            self.process.wait(timeout=15 if killed else self.timeout)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
            if not killed:
                raise TimeoutError("Instrumentation did not finish: " + self.label)
        self.thread.join(timeout=5)
        if not killed:
            text = (self.evidence.directory / (self.label + ".instrumentation.txt")).read_text()
            if self.process.returncode or "FAILURES!!!" in text \
                    or "INSTRUMENTATION_FAILED" in text \
                    or "INSTRUMENTATION_CODE: -1" not in text:
                raise AssertionError("Instrumentation failure: " + self.label)


class Run:
    def __init__(self, args, evidence, adb, peer, port):
        self.args, self.evidence, self.adb, self.peer, self.port = args, evidence, adb, peer, port
        self.counter = 0
        self.previous_pid = None
        self.active = None
        self.results = []
        self.run_id = str(time.time_ns())

    def phase(self, scenario, seed, phase, expectation, epoch, budget,
              policy="normal", complete=False):
        self.counter += 1
        label = "%02d-%s-%s-%s" % (self.counter, scenario, phase, expectation)
        if self.adb.pids():
            raise AssertionError("A prior target process is still running before " + label)
        self.peer.configure(scenario, label, epoch, budget, policy,
                            hold_headers=expectation == "cleared")
        arguments = dict(coldPhase=phase, coldExpectation=expectation,
                         coldScenario=scenario,
                         coldUrl="http://127.0.0.1:%d/%s/media" % (self.port, scenario),
                         coldName="cold-" + self.run_id + "-" + scenario,
                         coldFixtureSize=FIXTURE_SIZE, coldFixtureSeed=seed,
                         coldThreads=2,
                         coldHoldSeconds=int(self.args.timeout + 30))
        if epoch > 0:
            arguments["coldReplacementSeed"] = seed + 97
        if scenario == "earlier-resource":
            arguments["coldUrl2"] = arguments["coldUrl"].replace("/media", "/media2")
            arguments["coldCurrent"] = 1
        self.active = Instrumentation(self.adb, self.evidence, self.args.timeout,
                                      label, arguments)
        result = self.active.result()
        pid = int(result["pid"])
        if self.previous_pid is not None and pid == self.previous_pid:
            raise AssertionError("Cold phase reused the killed PID: " + str(pid))
        if not complete and pid not in self.adb.pids():
            raise AssertionError("Reported instrumentation PID is not alive")
        self.evidence.record("new-process", label=label, pid=pid,
                             previous_pid=self.previous_pid)
        # Driver assertions establish checkpoint semantics; the host independently
        # establishes wire semantics and actual cold process death.
        self.assert_wire(label, phase, expectation, epoch, result)
        if complete:
            self.active.finish(killed=False)
        else:
            self.adb.kill(pid)
            self.active.finish(killed=True)
            self.previous_pid = pid
        if complete:
            self.assert_output(scenario, arguments["coldName"], label)
        if expectation == "rejected":
            if result.get("state") != "pending" or result.get("running"):
                raise AssertionError("Integrity rejection did not remain stopped and pending")
            if policy == "unsolicited206":
                saved = result.get("checkpoint") or {}
                if not result.get("forceFullDownload") or not saved.get("forceFullDownload"):
                    raise AssertionError("Unsolicited206 discarded durable full-only mode")
                after = self.output_digest(arguments["coldName"], label)
                if after != self.rejected_digest:
                    raise AssertionError("Unsolicited206 changed output bytes")
            else:
                self.rejected_digest = self.output_digest(arguments["coldName"], label)
        if expectation == "cleared":
            after = self.output_digest(arguments["coldName"], label)
            if after != self.rejected_digest:
                raise AssertionError("Cleared checkpoint changed bytes before response acceptance")
        self.active = None
        self.results.append(dict(label=label, pid=pid, result=result))
        return result

    def output_digest(self, name, label):
        command = self.adb.prefix + ["exec-out", "run-as", PACKAGE, "cat",
                                     "files/cold_restart/" + name + ".bin"]
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   timeout=self.args.timeout)
        if completed.returncode:
            raise RuntimeError("Cannot pull real output: " +
                               completed.stderr.decode("utf-8", errors="replace"))
        (self.evidence.directory / (label + ".output.bin")).write_bytes(completed.stdout)
        result = dict(size=len(completed.stdout),
                      sha256=hashlib.sha256(completed.stdout).hexdigest())
        self.evidence.record("output", label=label, **result)
        return result

    def assert_output(self, scenario, name, label):
        digest = hashlib.sha256()
        resources = 2 if scenario == "earlier-resource" else 1
        for resource in range(resources):
            digest.update(self.peer.fixtures[(scenario, resource)])
        expected = dict(size=FIXTURE_SIZE * resources, sha256=digest.hexdigest())
        actual = self.output_digest(name, label)
        if actual != expected:
            raise AssertionError("Independent final output mismatch: %r != %r" % (actual, expected))

    def assert_wire(self, label, phase, expectation, epoch, result):
        deadline = time.monotonic() + self.args.timeout
        with self.peer.condition:
            while True:
                downloads = [dict(item) for item in self.peer.requests
                             if item["phase"] == label and item["method"] == "GET"
                             and item["path"].rsplit("/", 1)[-1] in ("object", "object2")
                             and "start" in item and item["end"] - item["start"] + 1 > 10
                             and (self.peer.scenario != "earlier-resource"
                                  or item.get("resource") == 1)]
                ready = downloads and (expectation != "cleared"
                                       or any(item.get("header_gated") for item in downloads))
                if ready:
                    break
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AssertionError("No expected actual media GET/gate in phase " + label)
                self.peer.condition.wait(min(remaining, 0.05))
        loaded = result.get("loaded") or {}
        intervals = sorted((item["start"], item["end"]) for item in downloads)
        if any(right[0] <= left[1] for left, right in zip(intervals, intervals[1:])):
            raise AssertionError("Duplicate or overlapping media workers: " + repr(downloads))
        if any(item["accept_encoding"] != "identity" for item in downloads):
            raise AssertionError("Media request did not require identity encoding")
        if phase == "startReplacement" or expectation in ("cleared", "clearedPartial") \
                or loaded.get("forceFullDownload"):
            if len(downloads) != 1:
                raise AssertionError("Full-only recovery created duplicate workers or retries")
            if any(item["range"] is not None or item["if_range"] is not None
                   for item in downloads):
                raise AssertionError("Full-only replacement sent Range/If-Range: " + repr(downloads))
        if phase != "startReplacement" and loaded and not loaded.get("forceFullDownload"):
            saved = loaded.get("checkpoint") or loaded
            blocks = saved.get("blocks") or []
            for request in downloads:
                if blocks:
                    index = request["start"] // (512 * 1024)
                    if index >= len(blocks) or blocks[index] < 0:
                        raise AssertionError("Requested a block already complete in the checkpoint")
                    start = index * 512 * 1024 + blocks[index]
                    end = min((index + 1) * 512 * 1024, FIXTURE_SIZE) - 1
                    expected = "bytes=%d-%d" % (start, end)
                else:
                    expected = "bytes=%d-" % saved.get("fallbackResumeOffset", 0)
                if request["range"] != expected:
                    raise AssertionError("Range did not match saved checkpoint: %r != %r" %
                                         (request["range"], expected))
        if phase == "resume" and expectation in ("partial", "rejected") \
                and not loaded.get("forceFullDownload"):
            if not any(item["start"] > 0 and item["range"]
                       and item["if_range"] for item in downloads):
                raise AssertionError("Resume did not validate a nonzero byte range")
        if expectation == "cleared" and any(item["bytes_sent"] for item in downloads):
            raise AssertionError("Body bytes escaped the pre-response checkpoint gate")
        if expectation == "cleared" and not any(item.get("header_gated") for item in downloads):
            raise AssertionError("Cleared checkpoint lacked a held response-header request")
        if expectation in ("partial", "clearedPartial") \
                and not any(item["bytes_sent"] > 0 for item in downloads):
            raise AssertionError("Partial checkpoint was reported before peer sent bytes")
        if phase == "resume" and expectation == "partial":
            wanted = '"cold-' + self.peer.scenario + '-' + str(epoch) + '"'
            if any(item["if_range"] != wanted for item in downloads):
                raise AssertionError("Stable resume lost the persisted strong validator")

    def scenario(self, name, seed):
        payload = fixture(FIXTURE_SIZE, seed)
        self.peer.fixtures[(name, 0)] = payload
        if name == "earlier-resource":
            second = fixture(FIXTURE_SIZE, seed + 1)
            self.peer.fixtures[(name, 1)] = second
            self.evidence.record("fixture", scenario=name, resource=1, seed=seed + 1,
                                 size=len(second), sha256=hashlib.sha256(second).hexdigest())
        self.evidence.record("fixture", scenario=name, seed=seed, size=len(payload),
                             sha256=hashlib.sha256(payload).hexdigest(),
                             formula="(i*31 + (i>>8)*17 + seed) & 255")
        self.phase(name, seed, "start", "partial", 0, PARTIAL_BUDGET)
        if name == "stable":
            for _ in range(self.args.cold_restarts):
                self.phase(name, seed, "resume", "partial", 0, PARTIAL_BUDGET)
            self.phase(name, seed, "assertComplete", "complete", 0, None, complete=True)
            return
        # Reject a different same-length representation, not merely a changed
        # validator on identical bytes. The earlier resource stays unchanged.
        resource = 1 if name == "earlier-resource" else 0
        replacement = fixture(FIXTURE_SIZE, seed + 97 + resource)
        self.peer.fixtures[(name, resource)] = replacement
        self.evidence.record("replacement-fixture", scenario=name, resource=resource,
                             size=len(replacement), seed=seed + 97 + resource,
                             sha256=hashlib.sha256(replacement).hexdigest())
        self.phase(name, seed, "resume", "rejected", 1, 0)
        if name == "unsolicited206":
            self.phase(name, seed, "startReplacement", "cleared", 1, 0)
            self.phase(name, seed, "resume", "rejected", 1, 0, policy="unsolicited206")
            # A subsequent explicit Start must still recover to a full response.
            self.phase(name, seed, "startReplacement", "clearedPartial", 1, PARTIAL_BUDGET)
            self.phase(name, seed, "assertComplete", "complete", 1, None, complete=True)
            return
        expectation = "clearedPartial" if name == "replacement-bytes" else "cleared"
        budget = PARTIAL_BUDGET if name == "replacement-bytes" else 0
        self.phase(name, seed, "startReplacement", expectation, 1, budget)
        # Full-only state must survive the actual process death, not merely an
        # orderly pause/restart of the in-memory mission.
        self.phase(name, seed, "resume", "clearedPartial", 1, PARTIAL_BUDGET)
        self.phase(name, seed, "assertComplete", "complete", 1, None, complete=True)


def arguments():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True, help="Explicit adb emulator serial")
    parser.add_argument("--disposable-avd-name", required=True,
                        help="Exact name returned by adb emu avd name")
    parser.add_argument("--allow-disposable", action="store_true", required=True,
                        help="Acknowledge that this named API26 AVD is disposable")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--scenario", choices=SCENARIOS, action="append",
                        help="Repeat to select scenarios (default: all)")
    parser.add_argument("--cold-restarts", type=int, default=2,
                        help="Additional stable partial cold starts (1 or 2)")
    parser.add_argument("--timeout", type=float, default=120,
                        help="Bound for each checkpoint/instrumentation/server gate")
    parser.add_argument("--artifacts", type=Path,
                        help="New output directory (must not already exist)")
    args = parser.parse_args()
    if args.cold_restarts not in (1, 2):
        parser.error("--cold-restarts must be 1 or 2 so partial fixture bytes remain")
    if args.timeout < 10:
        parser.error("--timeout must be at least 10 seconds")
    if not args.serial.startswith("emulator-"):
        parser.error("--serial must select an emulator transport")
    return args


def main():
    args = arguments()
    directory = args.artifacts or Path("/tmp") / ("pvcpipe-cold-" + str(time.time_ns()))
    directory.mkdir(parents=True, exist_ok=False)
    evidence = Evidence(directory)
    adb = Adb(args.adb, args.serial, evidence, args.timeout)
    state = PeerState(evidence, args.timeout)
    server = None
    run = None
    port = None
    success = False
    try:
        adb.require_disposable(args.disposable_avd_name)
        # Refuse to kill an unrelated already-running app process on entry.
        if adb.pids():
            raise RuntimeError("Target app is already running; close it on the disposable AVD first")
        server = PeerServer(("127.0.0.1", 0), PeerHandler)
        server.state = state
        port = server.server_address[1]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        adb.run("reverse", "tcp:" + str(port), "tcp:" + str(port))
        run = Run(args, evidence, adb, state, port)
        for index, name in enumerate(args.scenario or SCENARIOS):
            # A completed runner may leave the real service process alive; only
            # this harness's previously reported PID may be killed between cases.
            if run.results and adb.pids():
                pid = run.results[-1]["pid"]
                adb.kill(pid)
                run.previous_pid = pid
            run.scenario(name, 41 + index * 13)
        if run.results and adb.pids():
            adb.kill(run.results[-1]["pid"])
        success = True
    except (AssertionError, RuntimeError, TimeoutError, OSError,
            subprocess.SubprocessError, ValueError, KeyError) as error:
        evidence.record("failure", error=repr(error))
        print("FAIL:", error, file=sys.stderr)
    finally:
        if run and run.active:
            # Failure cleanup is SIGKILL, never pause, force-stop or data-clear.
            with contextlib.suppress(Exception):
                pids = adb.pids()
                if len(pids) == 1:
                    adb.kill(next(iter(pids)))
            with contextlib.suppress(Exception):
                run.active.finish(killed=True)
        state.stop()
        if server:
            server.shutdown()
            server.server_close()
        if port is not None:
            with contextlib.suppress(Exception):
                adb.run("reverse", "--remove", "tcp:" + str(port))
        summary = dict(success=success, serial=args.serial,
                       avd=args.disposable_avd_name, scenarios=args.scenario or list(SCENARIOS),
                       phases=run.results if run else [])
        (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        evidence.record("finished", success=success)
        evidence.stream.close()
    print(("PASS" if success else "FAIL") + ": evidence in " + str(directory))
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
