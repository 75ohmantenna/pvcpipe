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
Local app/test APK hashes must match the installed base APKs. Working-tree HEAD,
dirty status and source hashes are recorded separately, not asserted to be the
source of either APK. Existing pending missions are refused, never removed.

Select --scenario accounting for the ordinary ranged counter replay: a 768KiB
body gate, SIGKILL, actual manager load, then all replay bytes except 4KiB.
--ui-hold-seconds 3 additionally opens the real Downloads screen at that final
pending gate and captures its screenshot and accessibility hierarchy. Counter
classification is reported separately; a full-looking bar is not UI text proof.

Select --scenario transition for one clean A-to-B pre-initialization checkpoint,
SIGKILL, real cold load before Start, and exact A+B completion with B-only resume
traffic. B's next-initializer query is held after its initial sizing query. This
is service/loader integration coverage, not a staged queued-checkpoint writer race.
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
import shlex
import subprocess
import sys
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET


PACKAGE = "org.seventyfiveohmantenna.pvcpipe.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = "us.shandian.giga.service.DownloadColdRestartIntegrationTest#runPhase"
SCENARIOS = ("stable", "replacement-bytes", "replacement-before-bytes",
             "unsolicited206", "earlier-resource", "accounting", "transition")
FIXTURE_SIZE = 8 * 1024 * 1024
PARTIAL_BUDGET = 2 * 1024 * 1024
ACCOUNTING_BUDGET = 768 * 1024
ACCOUNTING_HOLD = 4096
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
        self.per_request_cap = None
        self.phase = ""
        self.requests = []
        self.fixtures = {}
        self.closed = False

    def configure(self, scenario, phase, epoch, budget, policy="normal", hold_headers=False,
                  per_request_cap=None):
        with self.condition:
            self.generation += 1
            self.scenario, self.phase, self.epoch = scenario, phase, epoch
            self.budget, self.policy = budget, policy
            self.hold_headers = hold_headers
            self.per_request_cap = per_request_cap
            self.condition.notify_all()
        self.evidence.record("gate", scenario=scenario, phase=phase, epoch=epoch,
                             budget=budget, policy=policy, hold_headers=hold_headers,
                             per_request_cap=per_request_cap)

    def release(self, budget):
        with self.condition:
            self.budget = budget
            self.condition.notify_all()
        self.evidence.record("body-budget-release", budget=budget, phase=self.phase)

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
            generation, scenario, phase, epoch, policy, per_request_cap = \
                self.server.accepted_context[self.connection]
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
                    # Deliberately satisfy only the first byte of this open
                    # range. A 206 may be shorter than the requested interval;
                    # Content-Range still supplies the real total. This keeps
                    # the initializer's GET probe close/drain outside our gate.
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
            request.update(planned_status=status, start=start, end=end,
                           etag='"cold-' + scenario + '-' + str(epoch) + '"')
            # A cleared checkpoint precedes even representation binding. Holding
            # only the body is insufficient: response headers bind the identity.
            probe = ranged and end - start + 1 <= 10
            gated = not probe and not (
                scenario in ("earlier-resource", "transition") and resource == 0)
            if scenario == "transition" and resource == 1 and request["range"] == "bytes=0-":
                # Initializer A sizes both URLs. Let its first B query through,
                # then hold B's own initializer before it can bind any B state.
                with state.condition:
                    queries = sum(item["phase"] == phase and item.get("resource") == 1
                                  and item["method"] == "GET" and item["range"] == "bytes=0-"
                                  for item in state.requests)
                    gated |= state.hold_headers and queries == 2
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
            request["status"] = status
            state.evidence.record("http-response-headers", **request)
            if not body:
                return
            position = start
            while position <= end:
                with state.condition:
                    while gated and (state.budget == 0 or (per_request_cap is not None
                            and position - start >= per_request_cap)) and not state.closed \
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
                        if per_request_cap is not None:
                            count = min(count, per_request_cap - (position - start))
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
            with state.condition:
                request["ended"] = True
                state.condition.notify_all()
            state.evidence.record("http-request-end", **request)


class PeerServer(http.server.ThreadingHTTPServer):
    # Join accepted handlers at final shutdown before auditing the final request
    # set, including connections buffered around the last process death.
    daemon_threads = False
    block_on_close = True

    def __init__(self, *args):
        self.accepted_context = {}
        super().__init__(*args)

    def get_request(self):
        connection, address = super().get_request()
        with self.state.condition:
            self.accepted_context[connection] = (
                self.state.generation, self.state.scenario, self.state.phase,
                self.state.epoch, self.state.policy, self.state.per_request_cap)
        return connection, address

    def close_request(self, request):
        try:
            super().close_request(request)
        finally:
            with self.state.condition:
                self.accepted_context.pop(request, None)


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
        remote = ["am", "instrument", "-w", "-r", "-e", "class", TEST]
        for key, value in arguments.items():
            remote.extend(["-e", key, str(value)])
        remote.append(RUNNER)
        # adb shell joins arguments before the device shell parses them.
        command = adb.prefix + ["shell", shlex.join(remote)]
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

    def result(self, key="coldResult"):
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
            marker = key + "="
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
        self.active_pid = None
        self.rejected_digest = None
        self.run_id = str(time.time_ns())

    def phase(self, scenario, seed, phase, expectation, epoch, budget,
              policy="normal", complete=False, fresh_below_loaded=False):
        self.counter += 1
        label = "%02d-%s-%s-%s" % (self.counter, scenario, phase, expectation)
        if self.adb.pids():
            raise AssertionError("A prior target process is still running before " + label)
        accounting_resume = scenario == "accounting" and phase == "resume"
        self.peer.configure(scenario, label, epoch, 0 if accounting_resume else budget, policy,
                            hold_headers=expectation in ("cleared", "transition"),
                            per_request_cap=ACCOUNTING_BUDGET // 2
                            if scenario == "accounting" and phase == "start" else None)
        arguments = dict(coldPhase=phase, coldExpectation=expectation,
                         coldScenario=scenario,
                         coldUrl="http://127.0.0.1:%d/%s/media" % (self.port, scenario),
                         coldName="cold-" + self.run_id + "-" + scenario,
                         coldFixtureSize=FIXTURE_SIZE, coldFixtureSeed=seed,
                         coldThreads=2,
                         coldHoldSeconds=int(self.args.timeout + 30))
        if phase != "start":
            arguments["coldTimestamp"] = self.results[-1]["result"]["timestamp"]
        if fresh_below_loaded:
            arguments["coldFreshBelowLoaded"] = "true"
        if policy == "unsolicited206":
            arguments["coldErrorContains"] = "Clean restart requires a full response"
        if epoch > 0:
            arguments["coldReplacementSeed"] = seed + 97
        if scenario in ("earlier-resource", "transition"):
            arguments["coldUrl2"] = arguments["coldUrl"].replace("/media", "/media2")
            arguments["coldCurrent"] = 1
        if scenario == "accounting":
            arguments["coldByteBudget"] = budget if budget is not None else 0
            arguments["coldRemainingHold"] = ACCOUNTING_HOLD
            if accounting_resume and self.args.ui_hold_seconds:
                arguments["coldShowUi"] = "true"
        self.active = Instrumentation(self.adb, self.evidence, self.args.timeout,
                                      label, arguments)
        if scenario == "transition" and phase != "start":
            before = self.active.result("coldLoaded")
            self.active_pid = int(before["pid"])
            self.evidence.record("transition-loaded-before-start", label=label, **before)
        if accounting_resume:
            before = self.active.result("coldResumed")
            self.active_pid = int(before["pid"])
            budget = before["byteBudget"]
            loaded = before["loaded"]
            expected = FIXTURE_SIZE - loaded["resumableCurrentBytes"] - ACCOUNTING_HOLD
            if budget != expected or budget <= 0:
                raise AssertionError("Accounting replay budget differs from saved resume plan")
            self.evidence.record("accounting-before-bytes", label=label, **before)
            if self.args.ui_hold_seconds:
                ready = before["beforeBytes"]
                self.capture_ui(label + "-before-bytes", ready["done"] * 100 / ready["length"],
                                arguments["coldName"])
            self.peer.release(budget)
        result = self.active.result()
        pid = int(result["pid"])
        self.active_pid = pid
        if self.previous_pid is not None and pid == self.previous_pid:
            raise AssertionError("Cold phase reused the killed PID: " + str(pid))
        if not complete and pid not in self.adb.pids():
            raise AssertionError("Reported instrumentation PID is not alive")
        self.evidence.record("new-process", label=label, pid=pid,
                             previous_pid=self.previous_pid)
        # Driver assertions establish checkpoint semantics; the host independently
        # establishes wire semantics and actual cold process death.
        self.assert_wire(label, scenario, phase, expectation, epoch, budget, result)
        if scenario == "accounting":
            if not complete:
                self.output_digest(arguments["coldName"], label + "-before-death")
            if accounting_resume:
                loaded = result["loaded"]
                replay_done = loaded["resumableTotalBytes"] + budget
                accounting = dict(label=label, before=self.results[-1]["result"],
                                  loaded=loaded, persisted=result.get("checkpoint"),
                                  resumedBeforeBytes=before["beforeBytes"],
                                  loadedOvercount=loaded["done"]
                                  - loaded["resumableTotalBytes"],
                                  live=result, expectedLogicalDone=replay_done,
                                  observedDone=result["done"],
                                  overcount=result["done"] - replay_done,
                                  counterMatchesReplay=result["done"] == replay_done,
                                  pendingAbove100=result["done"] > result["length"],
                                  remainingWireBytes=ACCOUNTING_HOLD,
                                  uiEvidence="See screenshot/XML; counter is not UI proof")
                self.evidence.record("accounting-comparison", **accounting)
                (self.evidence.directory / "accounting.json").write_text(
                    json.dumps(accounting, indent=2) + "\n")
                if self.args.ui_hold_seconds:
                    self.capture_ui(label, result["done"] * 100 / result["length"],
                                    arguments["coldName"])
        if expectation == "transition":
            actual = self.output_digest(arguments["coldName"], label + "-before-death")
            expected = dict(size=FIXTURE_SIZE, sha256=hashlib.sha256(
                self.peer.fixtures[(scenario, 0)]).hexdigest())
            if actual != expected:
                raise AssertionError("Clean transition did not preserve exact A bytes")
        if complete:
            self.active.finish(killed=False)
        else:
            self.adb.kill(pid)
            self.active.finish(killed=True)
            self.previous_pid = pid
            if scenario in ("accounting", "transition"):
                self.capture_checkpoint(result["timestamp"], label)
        # Repeat every wire assertion after exit: late retries/workers must not
        # escape the pre-kill snapshot. Shutdown performs one final joined audit.
        self.assert_wire(label, scenario, phase, expectation, epoch, budget, result,
                         final=True)
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
        self.active_pid = None
        self.results.append(dict(label=label, scenario=scenario, phase=phase,
                                 expectation=expectation, epoch=epoch, budget=budget,
                                 pid=pid, result=result))
        return result

    def capture_checkpoint(self, timestamp, label):
        recoverable = False
        for suffix in ("", ".bak", ".new"):
            remote = "files/pending_downloads/" + str(timestamp) + suffix
            completed = subprocess.run(
                self.adb.prefix + ["exec-out", "run-as", PACKAGE, "cat", remote],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=self.args.timeout)
            if completed.returncode:
                self.evidence.record("checkpoint-file-absent", label=label, path=remote,
                                     error=completed.stderr.decode(errors="replace"))
                continue
            local = label + ".checkpoint" + suffix
            recoverable |= suffix in ("", ".bak")
            (self.evidence.directory / local).write_bytes(completed.stdout)
            self.evidence.record("checkpoint-file", label=label, remote=remote, local=local,
                                 size=len(completed.stdout),
                                 sha256=hashlib.sha256(completed.stdout).hexdigest())
        if not recoverable:
            raise RuntimeError("Killed process left no readable primary/backup checkpoint")

    def capture_ui(self, label, expected_percent, name):
        # Wait for observable row text, not an arbitrary crash timer. Every body
        # stays held at the same byte boundary throughout these UI-only polls.
        deadline = time.monotonic() + self.args.ui_hold_seconds
        texts = []
        while True:
            self.adb.run("shell", "uiautomator", "dump", "/sdcard/cold-accounting.xml")
            completed = subprocess.run(
                self.adb.prefix + ["exec-out", "cat", "/sdcard/cold-accounting.xml"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=self.args.timeout)
            if completed.returncode:
                raise RuntimeError("Cannot capture actual Downloads hierarchy: " +
                                   completed.stderr.decode(errors="replace"))
            xml = completed.stdout
            root = ET.fromstring(xml)
            texts = [node.get("text", "") for node in root.iter("node")]
            percentages = []
            for row in root.iter("node"):
                if row.get("resource-id") != PACKAGE + ":id/item_bkg":
                    continue
                if any(node.get("resource-id") == PACKAGE + ":id/item_name"
                       and node.get("text") == name + ".bin" for node in row.iter("node")):
                    percentages.extend(node.get("text", "") for node in row.iter("node")
                                       if node.get("resource-id") == PACKAGE + ":id/item_status")
            values = [float(value.replace(",", ".")) for text in percentages
                      for value in re.findall(r"(\d+(?:[.,]\d+)?)\s*%", text)]
            if any(abs(value - expected_percent) <= 0.0051 for value in values):
                break
            if time.monotonic() >= deadline:
                raise AssertionError("Actual Downloads row/current percentage not visible: " +
                                     repr(texts))
            threading.Event().wait(0.1)
        (self.evidence.directory / (label + ".ui.xml")).write_bytes(xml)
        screenshot = subprocess.run(self.adb.prefix + ["exec-out", "screencap", "-p"],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=self.args.timeout)
        if screenshot.returncode:
            raise RuntimeError("Cannot capture actual Downloads screenshot: " +
                               screenshot.stderr.decode(errors="replace"))
        (self.evidence.directory / (label + ".ui.png")).write_bytes(screenshot.stdout)
        self.evidence.record("ui-capture", label=label, visibleText=texts,
                             percentageText=percentages, screenshot=label + ".ui.png",
                             hierarchy=label + ".ui.xml",
                             attribution="Actual Downloads activity text, not inferred from counter")

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
        resources = 2 if scenario in ("earlier-resource", "transition") else 1
        for resource in range(resources):
            digest.update(self.peer.fixtures[(scenario, resource)])
        expected = dict(size=FIXTURE_SIZE * resources, sha256=digest.hexdigest())
        actual = self.output_digest(name, label)
        if actual != expected:
            raise AssertionError("Independent final output mismatch: %r != %r" % (actual, expected))

    def assert_wire(self, label, scenario, phase, expectation, epoch, budget, result,
                    final=False):
        deadline = time.monotonic() + self.args.timeout
        with self.peer.condition:
            while True:
                requests = [dict(item) for item in self.peer.requests
                            if item["phase"] == label]
                objects = [item for item in requests
                           if item["method"] == "GET"
                           and item["path"].rsplit("/", 1)[-1] in ("object", "object2")]
                probes = [item for item in objects if "start" in item
                          and item["end"] - item["start"] + 1 <= 10]
                all_downloads = [item for item in objects if "start" in item
                                 and item["end"] - item["start"] + 1 > 10]
                downloads = [item for item in all_downloads
                             if scenario not in ("earlier-resource", "transition")
                             or item.get("resource") == 1]
                ready = downloads and (expectation != "cleared" or
                                       any(item.get("header_gated") for item in downloads))
                if expectation == "transition":
                    ready = any(item.get("resource") == 1 and item.get("header_gated")
                                for item in probes)
                if expectation in ("partial", "clearedPartial"):
                    ready = ready and any(item["bytes_sent"] > 0 for item in downloads)
                if expectation == "accounting":
                    ready = ready and sum(item["bytes_sent"] for item in downloads) == budget
                if ready or final:
                    break
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AssertionError("No expected actual media GET/gate in phase " + label)
                self.peer.condition.wait(min(remaining, 0.05))
        if not ready:
            raise AssertionError("Missing expected media GET/byte/header gate in " + label)
        if any(item["scenario"] != scenario for item in requests):
            raise AssertionError("Phase mixed requests from another scenario")
        if any(item["accept_encoding"] != "identity" for item in requests):
            raise AssertionError("Request did not require identity encoding")
        for item in requests:
            suffix = item["path"].rsplit("/", 1)[-1]
            if item["path"] != "/" + scenario + "/" + suffix \
                    or suffix not in ("media", "media2", "object", "object2") \
                    or item["method"] not in ("GET", "HEAD"):
                raise AssertionError("Unexpected phase request: " + repr(item))
            if "status" not in item:
                if item.get("header_gated") and (
                        item.get("planned_status") == 200 or expectation == "transition"
                        and item.get("resource") == 1 and item.get("planned_status") == 206):
                    continue  # Intentionally held before sending response headers.
                if final:
                    raise AssertionError("Request ended without an observed response: " + repr(item))
                continue
            if suffix in ("media", "media2"):
                expected_status = 302
            elif item["method"] == "HEAD":
                expected_status = 405
            else:
                unsolicited = scenario == "unsolicited206" and expectation == "rejected" \
                    and (result.get("loaded") or {}).get("forceFullDownload")
                expected_status = 206 if item["range"] is not None or unsolicited else 200
            if item["status"] != expected_status:
                raise AssertionError("Unexpected phase HTTP response: " + repr(item))
        if scenario in ("earlier-resource", "transition") and phase != "start" and any(
                item["path"].rsplit("/", 1)[-1] in ("media", "object") for item in requests):
            raise AssertionError("Cold restore re-requested the completed earlier resource")

        # Initializer 0 queries every URL, then probes the last ten bytes of
        # resource 0. Initializer 1 queries resource 1 again and probes its tail.
        # Each initializer first attempts HEAD; the server's real 405 switches
        # that initializer to GET. Initialized cold restores run no initializer.
        expected_probes = {}
        if phase == "start":
            expected_probes[(0, "bytes=0-")] = 1
            expected_probes[(0, "bytes=%d-%d" % (FIXTURE_SIZE - 10, FIXTURE_SIZE))] = 1
            if scenario in ("earlier-resource", "transition"):
                expected_probes[(1, "bytes=0-")] = 2
                if scenario != "transition":
                    expected_probes[(1, "bytes=%d-%d" %
                                     (FIXTURE_SIZE - 10, FIXTURE_SIZE))] = 1
        elif scenario == "transition":
            expected_probes[(1, "bytes=0-")] = 1
            expected_probes[(1, "bytes=%d-%d" %
                             (FIXTURE_SIZE - 10, FIXTURE_SIZE))] = 1
        actual_probes = {}
        for item in probes:
            key = (item["resource"], item["range"])
            actual_probes[key] = actual_probes.get(key, 0) + 1
            held_transition = expectation == "transition" and item.get("resource") == 1 \
                and item.get("header_gated") and item.get("planned_status") == 206
            if item["if_range"] is not None or not (item.get("status") == 206 or held_transition):
                raise AssertionError("Initializer probe used a validator or lacked 206")
        if actual_probes != expected_probes:
            raise AssertionError("Unexpected initializer probes in %s: %r != %r" %
                                 (label, actual_probes, expected_probes))
        heads = [item for item in requests if item["method"] == "HEAD"
                 and item["path"].rsplit("/", 1)[-1] in ("object", "object2")]
        expected_heads = 2 if phase == "start" and scenario in ("earlier-resource", "transition") \
            else int(phase == "start")
        if scenario == "transition" and phase != "start":
            expected_heads = 1
        if len(heads) != expected_heads or any(item.get("status") != 405 for item in heads):
            raise AssertionError("Unexpected HEAD/405 initializer fallback in " + label)
        if any(item["range"] != "bytes=0-" or item["if_range"] is not None for item in heads):
            raise AssertionError("HEAD initializer query carried unexpected Range/If-Range")

        loaded = result.get("loaded") or {}
        full_only = phase == "startReplacement" or expectation in ("cleared", "clearedPartial") \
            or loaded.get("forceFullDownload")
        worker_cap = 1 if full_only else FIXTURE_SIZE // (512 * 1024)
        if not full_only and budget is not None:
            worker_cap = min(worker_cap, (budget + 512 * 1024 - 1) // (512 * 1024) + 2)
        if len(downloads) > worker_cap:
            raise AssertionError("Too many media requests/workers/retries: " + repr(downloads))
        earlier_cap = FIXTURE_SIZE // (512 * 1024) \
            if phase == "start" and scenario in ("earlier-resource", "transition") else 0
        if len(all_downloads) - len(downloads) > earlier_cap:
            raise AssertionError("Too many earlier-resource media requests")
        object_cap = worker_cap + earlier_cap + len(expected_probes) + expected_heads
        # Two identical resource-1 queries are expected in the multi-resource start.
        object_cap += sum(expected_probes.values()) - len(expected_probes)
        if len(requests) > 2 * object_cap:
            raise AssertionError("Phase exceeded its bounded request/redirect count")
        for resource in (0, 1):
            intervals = sorted((item["start"], item["end"]) for item in all_downloads
                               if item["resource"] == resource)
            if any(right[0] <= left[1] for left, right in zip(intervals, intervals[1:])):
                raise AssertionError("Duplicate or overlapping media workers: " + repr(all_downloads))
        if full_only:
            if len(downloads) != 1:
                raise AssertionError("Full-only recovery created duplicate workers or retries")
            if any(item["range"] is not None or item["if_range"] is not None
                   for item in downloads):
                raise AssertionError("Full-only replacement sent Range/If-Range: " + repr(downloads))
        if phase != "startReplacement" and loaded and not loaded.get("forceFullDownload") \
                and scenario != "transition":
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
            wanted = '"cold-' + scenario + '-' + str(epoch) + '"'
            if any(item["if_range"] != wanted for item in downloads):
                raise AssertionError("Stable resume lost the persisted strong validator")
        if expectation == "transition":
            if downloads:
                raise AssertionError("B workers started before the clean transition gate")
            earlier_bytes = sum(item["bytes_sent"] for item in all_downloads
                                if item.get("resource") == 0)
            if earlier_bytes != FIXTURE_SIZE:
                raise AssertionError("Transition did not completely transfer A exactly once")
        if scenario == "accounting" and expectation == "accounting":
            if sum(item["bytes_sent"] for item in downloads) != budget:
                raise AssertionError("Accounting gate did not consume exactly its body budget")
            if result["done"] != result["resumedDone"] + budget:
                raise AssertionError("Live counter delta differs from controlled body bytes")
            if len(downloads) < 2:
                raise AssertionError("Accounting case did not exercise two ranged workers")

    def scenario(self, name, seed):
        payload = fixture(FIXTURE_SIZE, seed)
        self.peer.fixtures[(name, 0)] = payload
        if name in ("earlier-resource", "transition"):
            second = fixture(FIXTURE_SIZE, seed + 1)
            self.peer.fixtures[(name, 1)] = second
            self.evidence.record("fixture", scenario=name, resource=1, seed=seed + 1,
                                 size=len(second), sha256=hashlib.sha256(second).hexdigest())
        self.evidence.record("fixture", scenario=name, seed=seed, size=len(payload),
                             sha256=hashlib.sha256(payload).hexdigest(),
                             formula="(i*31 + (i>>8)*17 + seed) & 255")
        if name == "transition":
            self.phase(name, seed, "start", "transition", 0, 0)
            self.phase(name, seed, "assertComplete", "complete", 0, None, complete=True)
            return
        if name == "accounting":
            (self.evidence.directory / "accounting.fixture.bin").write_bytes(payload)
            self.phase(name, seed, "start", "accounting", 0, ACCOUNTING_BUDGET)
            self.phase(name, seed, "resume", "accounting", 0, None)
            self.phase(name, seed, "assertComplete", "complete", 0, None, complete=True)
            return
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
        previous = self.phase(name, seed, "startReplacement", expectation, 1, budget)
        # The saved checkpoint may advance before SIGKILL, but cannot regress:
        # maxRetry=0. Spend less than its observed current-resource progress so
        # the driver's base < saved.done < loadedDone gate proves fresh bytes.
        saved = previous["checkpoint"]
        base = saved["offsets"][saved["current"]] - saved["offsets"][0]
        progress = saved["done"] - base
        fresh_below_loaded = progress > 0
        resume_budget = max(1, progress // 2) if fresh_below_loaded else PARTIAL_BUDGET
        if fresh_below_loaded and resume_budget >= progress:
            raise AssertionError("Saved full-only progress is too small for a fresh byte gate")
        self.phase(name, seed, "resume", "clearedPartial", 1, resume_budget,
                   fresh_below_loaded=fresh_below_loaded)
        self.phase(name, seed, "assertComplete", "complete", 1, None, complete=True)


def record_provenance(args, evidence, adb):
    """Record observed artifacts/source separately; hashes do not prove a build."""
    root = Path(__file__).resolve().parents[1]

    def git(*arguments):
        completed = subprocess.run(["git", "-C", str(root), *arguments],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   timeout=args.timeout, check=True)
        return completed.stdout.decode("utf-8", errors="surrogateescape")

    head = git("rev-parse", "HEAD").strip()
    status = git("status", "--porcelain=v1", "--untracked-files=all")
    changed = git("diff", "--name-only", "-z", "HEAD").split("\0")
    untracked = git("ls-files", "--others", "--exclude-standard", "-z").split("\0")
    sources = git("ls-files", "-z", "--", "app/src/main/java/us/shandian/giga",
                  "app/src/androidTest/java/us/shandian/giga/service",
                  "tools/pr151_cold_restart.py", "app/build.gradle.kts",
                  "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                  "gradle/libs.versions.toml", "gradle/wrapper").split("\0")
    hashes = {}
    for name in sorted(set(changed + untracked + sources) - {""}):
        path = root / name
        if path.is_file():
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            hashes[name] = digest.hexdigest()
        else:
            hashes[name] = None
    evidence.record("source-provenance", head=head, dirty=bool(status), status=status,
                    file_sha256=hashes,
                    attribution="Observed working tree; APK/source build relationship not inferred")
    packages = []
    for package, local in ((PACKAGE, args.app_apk), (PACKAGE + ".test", args.test_apk)):
        local = local.resolve(strict=True)
        digest = hashlib.sha256()
        with local.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        local_sha256 = digest.hexdigest()
        paths = adb.run("shell", "pm", "path", package).splitlines()
        bases = [line[len("package:"):] for line in paths
                 if line.startswith("package:") and line.endswith("/base.apk")]
        if len(bases) != 1:
            raise RuntimeError("Cannot identify installed base APK for " + package)
        installed = adb.run("shell", "sha256sum", bases[0]).split()
        if len(installed) != 2 or not re.fullmatch(r"[0-9a-f]{64}", installed[0]) \
                or installed[1] != bases[0]:
            raise RuntimeError("Cannot hash installed base APK for " + package)
        metadata = adb.run("shell", "dumpsys", "package", package)
        fields = {}
        for key in ("versionCode", "versionName", "firstInstallTime", "lastUpdateTime"):
            match = re.search(r"(?m)^\s*" + key + r"=(.+)$", metadata)
            if not match:
                raise RuntimeError("Missing installed package metadata " + key + ": " + package)
            fields[key] = match.group(1).strip()
        artifact = dict(package=package, local_apk=str(local), local_sha256=local_sha256,
                        installed_base=bases[0], installed_sha256=installed[0],
                        installed_paths=paths, metadata=fields)
        evidence.record("apk-provenance", **artifact)
        if local_sha256 != installed[0]:
            raise AssertionError("Installed base APK differs from local APK: " + package)
        packages.append(artifact)
    return dict(head=head, dirty=bool(status), status=status,
                file_sha256=hashes, packages=packages,
                attribution="Observed working tree; APK/source build relationship not inferred")


def arguments():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True, help="Explicit adb emulator serial")
    parser.add_argument("--disposable-avd-name", required=True,
                        help="Exact name returned by adb emu avd name")
    parser.add_argument("--allow-disposable", action="store_true", required=True,
                        help="Acknowledge that this named API26 AVD is disposable")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--app-apk", type=Path,
                        default=Path("app/build/outputs/apk/debug/app-debug.apk"),
                        help="Local app APK that must match the installed base APK")
    parser.add_argument("--test-apk", type=Path,
                        default=Path("app/build/outputs/apk/androidTest/debug/"
                                     "app-debug-androidTest.apk"),
                        help="Local test APK that must match the installed base APK")
    parser.add_argument("--scenario", choices=SCENARIOS, action="append",
                        help="Repeat to select scenarios (default: all)")
    parser.add_argument("--cold-restarts", type=int, default=2,
                        help="Additional stable partial cold starts (1 or 2)")
    parser.add_argument("--timeout", type=float, default=120,
                        help="Bound for each checkpoint/instrumentation/server gate")
    parser.add_argument("--ui-hold-seconds", type=float, default=0,
                        help="Wait up to this many seconds for accounting Downloads UI text/capture")
    parser.add_argument("--artifacts", type=Path,
                        help="New output directory (must not already exist)")
    args = parser.parse_args()
    if args.cold_restarts not in (1, 2):
        parser.error("--cold-restarts must be 1 or 2 so partial fixture bytes remain")
    if args.timeout < 10:
        parser.error("--timeout must be at least 10 seconds")
    if not 0 <= args.ui_hold_seconds <= args.timeout / 2:
        parser.error("--ui-hold-seconds must be between 0 and half --timeout")
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
    provenance = None
    failure = None
    try:
        adb.require_disposable(args.disposable_avd_name)
        # Refuse to kill an unrelated already-running app process on entry.
        if adb.pids():
            raise RuntimeError("Target app is already running; close it on the disposable AVD first")
        pending = adb.run("shell", "run-as " + PACKAGE + " sh -c "
                          "'if [ -e files/pending_downloads ]; then "
                          "ls -A files/pending_downloads; fi'")
        evidence.record("pending-preflight", entries=pending)
        if pending:
            raise RuntimeError("Refusing nonempty pending_downloads; existing missions are preserved")
        provenance = record_provenance(args, evidence, adb)
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
        state.stop()
        server.shutdown()
        server.server_close()
        server = None
        labels = {item["label"] for item in run.results}
        if any(not item.get("ended") or item["phase"] not in labels
               for item in state.requests):
            raise AssertionError("Final wire audit found an unfinished or unassigned request")
        for item in run.results:
            run.assert_wire(item["label"], item["scenario"], item["phase"],
                            item["expectation"], item["epoch"], item["budget"],
                            item["result"], final=True)
        evidence.record("final-wire-audit", phases=len(run.results),
                        requests=len(state.requests), handlers_joined=True)
        success = True
    except Exception as error:
        failure = repr(error)
        evidence.record("failure", error=repr(error))
        print("FAIL:", error, file=sys.stderr)
    finally:
        if run and run.active:
            # Kill only an explicitly reported owned PID, never an unknown app
            # process discovered during failure cleanup.
            with contextlib.suppress(Exception):
                pids = adb.pids()
                if run.active_pid is not None and pids == {run.active_pid}:
                    adb.kill(run.active_pid)
                elif pids:
                    evidence.record("cleanup-left-process", pids=sorted(pids),
                                    reported_pid=run.active_pid)
            with contextlib.suppress(Exception):
                run.active.finish(killed=True)
        state.stop()
        if server:
            try:
                server.shutdown()
                server.server_close()
            except Exception as error:
                success = False
                failure = repr(error)
                evidence.record("failure", error=repr(error), stage="server-cleanup")
                print("FAIL:", error, file=sys.stderr)
        if port is not None:
            with contextlib.suppress(Exception):
                adb.run("reverse", "--remove", "tcp:" + str(port))
        summary = dict(success=success, serial=args.serial,
                       avd=args.disposable_avd_name, scenarios=args.scenario or list(SCENARIOS),
                       phases=run.results if run else [],
                       provenance=provenance, failure=failure)
        (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        evidence.record("finished", success=success)
        evidence.stream.close()
    print(("PASS" if success else "FAIL") + ": evidence in " + str(directory))
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
