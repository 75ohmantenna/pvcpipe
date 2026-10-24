#!/usr/bin/env python3
"""One real-manager warm retry/Start on an explicitly disposable API26 emulator.

Build the debug app and androidTest APK separately. This launcher never builds,
uninstalls, clears data, kills/force-stops an Android process, edits a checkpoint,
or runs connectedDebugAndroidTest. It refuses existing pending missions and an
already-running target process. Only the HTTP peer is controlled: instrumentation
starts the real service, obtains its real binder, and invokes manager.resumeMission.

Example (after building; start the named disposable AVD separately if necessary):
  python3 tools/download_warm_retry.py --serial emulator-5554 \
      --disposable-avd-name pvcpipe_cold26_20261024 --allow-disposable \
      --install --artifacts /tmp/pvcpipe-warm-fix/api26

--install is optional and only performs install -r after inspecting both installed
packages, verifying their signing certificates against the candidate APKs, and
checking candidate package IDs. Without it, installed APK hashes must already
match. Both debug packages must already exist; an absent/incompatible installation
is refused, never uninstalled. Failed runs preserve their mission/output for
inspection; there is deliberately no automated destructive recovery or rerun.

The initial block0 response advertises its full range but closes after 187 bytes.
Its reconnect is held until instrumentation observes the other original worker
has exited, then receives HTTP503 with maxRetry=1. Before releasing that 503,
instrumentation uses the normal UI queue API, setEnqueued(false), to prevent the
service's automatic HTTP5xx retry; it does not pause or change worker progress or
ownership. This queue toggle itself schedules a normal checkpoint. Only the later
explicit manager.resumeMission triggers Start. Before it, the launcher checks the
reported live PID. Resume response bytes remain gated until
it independently checks that same PID after Start. Final byte/hash and logical
progress checks occur while instrumentation still holds that same process alive.
Checkpoint snapshots are observations before Start/cleanup, not proof that the
terminal worker's newly published in-memory prefix was durably checkpointed.
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
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse


PACKAGE = "org.seventyfiveohmantenna.pvcpipe.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = "us.shandian.giga.service.DownloadWarmRetryIntegrationTest#retryThenWarmStart"
BLOCK = 512 * 1024
PREFIX = 187
SIZE = 2 * BLOCK + 137
SEED = 73
ETAG = '"warm-retry-stable"'
PAYLOAD = bytes((i * 31 + (i >> 8) * 17 + SEED) & 255 for i in range(SIZE))


class Evidence:
    def __init__(self, directory):
        self.directory = directory
        self.lock = threading.Lock()
        self.stream = (directory / "events.jsonl").open("x", encoding="utf-8")

    def record(self, kind, **fields):
        with self.lock:
            self.stream.write(json.dumps(dict(kind=kind, monotonic=time.monotonic(),
                                              **fields), sort_keys=True) + "\n")
            self.stream.flush()


class Adb:
    def __init__(self, args, evidence):
        self.args, self.evidence = args, evidence
        self.prefix = [args.adb, "-s", args.serial]

    def command(self, command, binary=False, check=True):
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   timeout=self.args.timeout)
        self.evidence.record("command", command=command, returncode=completed.returncode,
                             stdout=("<%d binary bytes>" % len(completed.stdout)) if binary else
                             completed.stdout.decode("utf-8", errors="replace"),
                             stderr=completed.stderr.decode("utf-8", errors="replace"))
        if check and completed.returncode:
            raise RuntimeError("Command failed: " + shlex.join(command) + "\n"
                               + completed.stderr.decode("utf-8", errors="replace"))
        return completed.stdout if binary else completed.stdout.decode("utf-8", errors="replace").strip()

    def run(self, *arguments, check=True, binary=False):
        return self.command(self.prefix + list(arguments), check=check, binary=binary)

    def pids(self):
        value = self.run("shell", "pidof", PACKAGE, check=False)
        if not value:
            return set()
        if not re.fullmatch(r"\d+(?:\s+\d+)*", value):
            raise RuntimeError("Cannot establish target PID: " + value)
        return {int(pid) for pid in value.split()}

    def assert_pid(self, pid, boundary):
        actual = self.pids()
        self.evidence.record("warm-live-pid", boundary=boundary, expected=pid, actual=sorted(actual))
        if actual != {pid}:
            raise AssertionError("Live PID changed at %s: expected %d, actual %r" %
                                 (boundary, pid, actual))

    def require_disposable(self):
        if self.run("get-state") != "device":
            raise RuntimeError("Explicit emulator transport is not ready")
        if self.run("shell", "getprop", "ro.kernel.qemu") != "1":
            raise RuntimeError("Refusing a non-emulator target")
        if self.run("shell", "getprop", "ro.build.version.sdk") != "26":
            raise RuntimeError("Refusing an API other than 26")
        names = [line.strip() for line in self.run("emu", "avd", "name").splitlines()
                 if line.strip() and line.strip() != "OK"]
        if names != [self.args.disposable_avd_name]:
            raise RuntimeError("Disposable AVD identity mismatch: " + repr(names))
        self.run("shell", "run-as", PACKAGE, "id")
        if self.pids():
            raise RuntimeError("Target process already running; leave it untouched")
        pending = self.run("shell", "run-as " + PACKAGE + " sh -c " + shlex.quote(
            "if [ -e files/pending_downloads ]; then ls -A files/pending_downloads; fi"))
        self.evidence.record("pending-preflight", entries=pending)
        if pending:
            raise RuntimeError("Existing pending missions are preserved; refusing this run")


class Peer:
    def __init__(self, evidence, timeout, token):
        self.evidence, self.timeout, self.token = evidence, timeout, token
        self.condition = threading.Condition()
        self.fail_retry = False
        self.resume = False
        self.bytes_ready = False
        self.finish = False
        self.closed = False
        self.requests = []
        self.prefix_sent = False

    def release(self, gate):
        with self.condition:
            setattr(self, gate, True)
            self.condition.notify_all()
        self.evidence.record("host-gate-release", gate=gate)

    def wait(self, gate):
        deadline = time.monotonic() + self.timeout
        with self.condition:
            while not getattr(self, gate) and not self.closed:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("HTTP peer gate timed out: " + gate)
                self.condition.wait(remaining)
            if self.closed:
                raise RuntimeError("HTTP peer closed at " + gate)

    def stop(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_arguments):
        pass

    def do_HEAD(self):
        self.respond(False)

    def do_GET(self):
        self.respond(True)

    def headers_only(self, status, length=0, start=None, end=None):
        self.send_response(status)
        self.send_header("Content-Length", str(length))
        self.send_header("Connection", "close")
        self.send_header("ETag", ETAG)
        self.send_header("Accept-Ranges", "bytes")
        if start is not None:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, SIZE))
        self.end_headers()
        self.close_connection = True

    def respond(self, body):
        peer = self.server.peer
        self.connection.settimeout(peer.timeout)
        path = urllib.parse.urlsplit(self.path).path
        if path.startswith("/" + peer.token + "/control/"):
            gate = path.rsplit("/", 1)[1]
            with peer.condition:
                if gate == "fail-retry":
                    peer.fail_retry = True
                    peer.condition.notify_all()
                    ready = True
                    peer.evidence.record("instrumentation-other-worker-exited")
                else:
                    ready = {"resume": peer.resume, "finish": peer.finish}.get(gate, False)
            self.headers_only(200 if ready and not peer.closed else 425)
            return
        with peer.condition:
            request = dict(id=len(peer.requests) + 1, method=self.command,
                           path=path, range=self.headers.get("Range"),
                           if_range=self.headers.get("If-Range"), bytes_sent=0,
                           phase="warm" if peer.resume else "initial")
            peer.requests.append(request)
        peer.evidence.record("http-request", **request)
        try:
            if path != "/" + peer.token + "/object":
                request["status"] = 404
                self.headers_only(404)
                return
            if not body:
                # Exercise the initializer's real HEAD405 -> ranged GET fallback.
                request["status"] = 405
                self.headers_only(405)
                return
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", request["range"] or "")
            if not match:
                raise AssertionError("Expected one ranged GET: " + repr(request))
            start = int(match.group(1))
            end = min(int(match.group(2)), SIZE - 1) if match.group(2) else SIZE - 1
            if request["range"] == "bytes=0-" and request["if_range"] is None:
                end = 0
            if start > end:
                raise AssertionError("Unexpected invalid range: " + repr(request))
            request.update(start=start, end=end, probe=end - start + 1 <= 10)
            if not request["probe"]:
                if request["if_range"] != ETAG:
                    raise AssertionError("Worker did not conditionally resume stable identity")
                if request["phase"] == "warm":
                    if start != PREFIX or end != BLOCK - 1:
                        raise AssertionError("Warm Start replayed or fetched a completed block")
                    peer.wait("bytes_ready")
                elif start == PREFIX:
                    peer.wait("fail_retry")
                    request["status"] = 503
                    self.headers_only(503)
                    return
                elif start == 0:
                    with peer.condition:
                        if peer.prefix_sent:
                            raise AssertionError("Unexpected repeated initial block0")
                        peer.prefix_sent = True
                    request["status"] = 206
                    self.headers_only(206, end - start + 1, start, end)
                    self.wfile.write(PAYLOAD[:PREFIX])
                    self.wfile.flush()
                    request.update(bytes_sent=PREFIX, truncated=True)
                    return
                elif start not in (BLOCK, 2 * BLOCK):
                    raise AssertionError("Unexpected initial worker range")
            request["status"] = 206
            self.headers_only(206, end - start + 1, start, end)
            self.wfile.write(PAYLOAD[start:end + 1])
            self.wfile.flush()
            request["bytes_sent"] = end - start + 1
        except Exception as error:
            request["error"] = repr(error)
        finally:
            request["ended"] = True
            peer.evidence.record("http-request-end", **request)
            with peer.condition:
                peer.condition.notify_all()


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False


class Instrumentation:
    def __init__(self, adb, evidence, arguments):
        self.adb, self.evidence = adb, evidence
        self.messages = queue.Queue()
        self.text = []
        remote = ["am", "instrument", "-w", "-r", "-e", "class", TEST]
        for key, value in arguments.items():
            remote.extend(["-e", key, str(value)])
        remote.append(RUNNER)
        self.command = adb.prefix + ["shell", shlex.join(remote)]
        evidence.record("instrumentation-command", command=self.command)
        self.process = subprocess.Popen(self.command, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.reader = threading.Thread(target=self.consume, daemon=True)
        self.reader.start()

    def consume(self):
        with (self.evidence.directory / "instrumentation.txt").open("x", encoding="utf-8") as log:
            for line in self.process.stdout:
                log.write(line)
                log.flush()
                self.text.append(line)
                match = re.match(r"INSTRUMENTATION_STATUS: (warm\w+)=(.*)", line)
                if match:
                    self.messages.put((match.group(1), json.loads(match.group(2))))
        self.messages.put(("eof", None))

    def result(self, key):
        name, result = self.messages.get(timeout=self.adb.args.timeout)
        if name != key:
            raise AssertionError("Expected %s, received %s; see instrumentation.txt" % (key, name))
        self.evidence.record("instrumentation-result", key=key, result=result)
        return result

    def finish(self):
        returncode = self.process.wait(timeout=self.adb.args.timeout)
        self.reader.join(timeout=5)
        text = "".join(self.text)
        if returncode or "OK (1 test)" not in text or "FAILURES!!!" in text \
                or "INSTRUMENTATION_FAILED" in text:
            raise AssertionError("Targeted instrumentation failed; see instrumentation.txt")


def sdk_tool(name):
    found = shutil.which(name)
    if found:
        return found
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
             str(Path.home() / "Android/Sdk")]
    for root in roots:
        if root:
            paths = list((Path(root) / "build-tools").glob("*/" + name))
            if paths:
                return str(sorted(paths, key=lambda path:
                           tuple(int(part) for part in re.findall(r"\d+", path.parent.name)))[-1])
    return name


def prepare_packages(args, adb, evidence):
    packages = []
    candidates = []
    for package, candidate, label in ((PACKAGE, args.app_apk, "app"),
                                      (PACKAGE + ".test", args.test_apk, "test")):
        candidate = candidate.resolve(strict=True)
        badging = adb.command([args.aapt, "dump", "badging", str(candidate)])
        if not re.search(r"(?m)^package: name='" + re.escape(package) + "'", badging):
            raise RuntimeError("Candidate APK has the wrong package ID: " + str(candidate))
        paths = adb.run("shell", "pm", "path", package).splitlines()
        bases = [line[len("package:"):] for line in paths
                 if line.startswith("package:") and line.endswith("/base.apk")]
        if len(bases) != 1:
            raise RuntimeError("Exactly one existing installed base APK is required: " + package)
        installed = evidence.directory / ("installed-before-" + label + ".apk")
        adb.run("pull", bases[0], str(installed))

        def certificate(path):
            output = adb.command([args.apksigner, "verify", "--print-certs", str(path)])
            # SDK apksigner and apksig's compatible CLI label verified signers differently.
            digests = set(re.findall(
                r"(?m)^(?:Signer #\d+ |V[123] Signer: )"
                r"certificate SHA-256 digest: ([0-9a-fA-F]{64})$", output))
            if len(digests) != 1:
                raise RuntimeError("Expected exactly one verified APK signer: " + str(path))
            return next(iter(digests)).lower()

        installed_certificate = certificate(installed)
        candidate_certificate = certificate(candidate)
        if installed_certificate != candidate_certificate:
            raise RuntimeError("Refusing incompatible install -r signing identity: " + package)
        item = dict(package=package, candidate=str(candidate),
                    candidate_sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(),
                    installed_before_sha256=hashlib.sha256(installed.read_bytes()).hexdigest(),
                    certificate_sha256=candidate_certificate)
        evidence.record("package-preflight", **item)
        packages.append(item)
        candidates.append(candidate)
    if packages[0]["certificate_sha256"] != packages[1]["certificate_sha256"]:
        raise RuntimeError("App and instrumentation APK signing certificates differ")
    # No installation occurs until BOTH candidates and BOTH existing signatures passed.
    if args.install:
        for candidate in candidates:
            result = adb.run("install", "-r", str(candidate))
            if "Success" not in result.splitlines():
                raise RuntimeError("install -r did not report success")
    for item in packages:
        paths = adb.run("shell", "pm", "path", item["package"]).splitlines()
        bases = [line[len("package:"):] for line in paths
                 if line.startswith("package:") and line.endswith("/base.apk")]
        if len(bases) != 1:
            raise RuntimeError("Cannot identify installed base APK after preflight")
        actual = adb.run("shell", "sha256sum", bases[0]).split()
        if len(actual) != 2 or actual[0] != item["candidate_sha256"] or actual[1] != bases[0]:
            raise RuntimeError("Installed APK does not match candidate: " + item["package"])
        item["installed_sha256"] = actual[0]
        evidence.record("apk-provenance", **item)
    root = Path(__file__).resolve().parents[1]
    names = ("app/src/main/java/us/shandian/giga/get/DownloadRunnable.java",
             "app/src/main/java/us/shandian/giga/get/DownloadMission.java",
             "app/src/androidTest/java/us/shandian/giga/service/DownloadWarmRetryIntegrationTest.java",
             "tools/download_warm_retry.py", "app/build.gradle.kts")
    sources = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in names}
    evidence.record("source-provenance", file_sha256=sources,
                    attribution="Observed source hashes; no inferred APK/source build relationship")
    return dict(packages=packages, source_sha256=sources)


def assert_wire(peer):
    deadline = time.monotonic() + peer.timeout
    with peer.condition:
        while any(not request.get("ended") for request in peer.requests):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError("HTTP requests did not settle")
            peer.condition.wait(remaining)
        requests = list(peer.requests)
    errors = [request for request in requests if "error" in request]
    initial = [request for request in requests if request["phase"] == "initial"
               and request["method"] == "GET" and not request.get("probe")]
    warm = [request for request in requests if request["phase"] == "warm"]
    if errors or len(initial) != 4 or len(warm) != 1:
        raise AssertionError("Unexpected HTTP sequence: " + repr(requests))
    expected = {(0, BLOCK - 1, 206, PREFIX), (PREFIX, BLOCK - 1, 503, 0),
                (BLOCK, 2 * BLOCK - 1, 206, BLOCK), (2 * BLOCK, SIZE - 1, 206, 137)}
    observed = {(request["start"], request["end"], request["status"], request["bytes_sent"])
                for request in initial}
    if observed != expected:
        raise AssertionError("Initial prefix/retry503 sequence did not match: " + repr(observed))
    request = warm[0]
    if (request["start"], request["end"], request["status"], request["bytes_sent"],
            request["if_range"]) != (PREFIX, BLOCK - 1, 206, BLOCK - PREFIX, ETAG):
        raise AssertionError("Warm Start did not conditionally resume exactly 187: " + repr(request))
    return dict(initial_body_bytes=sum(request["bytes_sent"] for request in initial),
                warm_body_bytes=request["bytes_sent"], warm_range=request["range"],
                warm_if_range=request["if_range"], request_count=len(requests))


def arguments():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--disposable-avd-name", required=True)
    parser.add_argument("--allow-disposable", action="store_true", required=True)
    parser.add_argument("--install", action="store_true",
                        help="Signing/package checked install -r; never uninstall or clear")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--apksigner", default=sdk_tool("apksigner"))
    parser.add_argument("--aapt", default=sdk_tool("aapt"))
    parser.add_argument("--app-apk", type=Path,
                        default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--test-apk", type=Path,
                        default=Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--artifacts", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only an explicit emulator transport is permitted")
    if not 30 <= args.timeout <= 300:
        parser.error("--timeout must be between 30 and 300 seconds")
    return args


def main():
    args = arguments()
    directory = args.artifacts or Path("/tmp") / ("pvcpipe-warm-" + str(time.time_ns()))
    directory.mkdir(parents=True, exist_ok=False)
    evidence = Evidence(directory)
    evidence.record("launcher-command", command=[sys.executable, *sys.argv])
    adb = Adb(args, evidence)
    token = "warm_" + str(time.time_ns())
    peer = Peer(evidence, args.timeout, token)
    server = None
    instrumentation = None
    provenance = None
    result = None
    wire = None
    failure = None
    success = False
    port = None
    try:
        adb.require_disposable()
        provenance = prepare_packages(args, adb, evidence)
        # Install -r must not have unexpectedly started an app process or introduced pending data.
        adb.require_disposable()
        server = Server(("127.0.0.1", 0), Handler)
        server.peer = peer
        port = server.server_address[1]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        adb.run("reverse", "tcp:" + str(port), "tcp:" + str(port))
        base = "http://127.0.0.1:%d/%s" % (port, token)
        instrumentation = Instrumentation(adb, evidence,
                                          dict(warmDisposable="true", warmName=token,
                                               warmUrl=base + "/object",
                                               warmControl=base + "/control"))
        before503 = instrumentation.result("warmBefore503")
        pid = before503["pid"]
        adb.assert_pid(pid, "first host PID observation after other original worker exit")
        stopped = instrumentation.result("warmStopped")
        if stopped["pid"] != pid:
            raise AssertionError("PID changed before actual manager Start")
        adb.assert_pid(pid, "both original workers exited, before actual manager Start")
        peer.release("resume")
        started = instrumentation.result("warmStarted")
        if started["pid"] != pid:
            raise AssertionError("Actual Start changed PID")
        adb.assert_pid(pid, "actual manager Start returned, before resumed response bytes")
        peer.release("bytes_ready")
        result = instrumentation.result("warmResult")
        if result["pid"] != pid or result["done"] != SIZE or result["length"] != SIZE \
                or result["outputLength"] != SIZE:
            raise AssertionError("Final logical progress/length/PID mismatch")
        adb.assert_pid(pid, "exact final output verification, before instrumentation cleanup")
        output = adb.run("exec-out", "run-as", PACKAGE, "cat",
                         "files/warm_retry/" + token + ".bin", binary=True)
        (directory / "output.bin").write_bytes(output)
        actual = hashlib.sha256(output).hexdigest()
        expected = hashlib.sha256(PAYLOAD).hexdigest()
        evidence.record("output-verification", length=len(output), expected_length=SIZE,
                        sha256=actual, expected_sha256=expected)
        if len(output) != SIZE or actual != expected:
            raise AssertionError("Independent host output verification failed")
        wire = assert_wire(peer)
        evidence.record("wire-verification", **wire)
        peer.release("finish")
        instrumentation.finish()
        success = True
    except Exception as error:
        failure = repr(error)
        evidence.record("failure", error=failure)
    finally:
        peer.stop()
        if instrumentation is not None and instrumentation.process.poll() is None:
            # Let bounded Android gates fail naturally. Never terminate the Android process.
            try:
                instrumentation.process.wait(timeout=65)
                instrumentation.reader.join(timeout=5)
            except subprocess.TimeoutExpired:
                evidence.record("left-running", note="No automatic process kill or force-stop")
        if server is not None:
            server.shutdown()
            server.server_close()
        if port is not None:
            with contextlib.suppress(Exception):
                adb.run("reverse", "--remove", "tcp:" + str(port))
        summary = dict(success=success, failure=failure, serial=args.serial,
                       avd=args.disposable_avd_name, provenance=provenance, result=result, wire=wire,
                       limitations=["Controlled HTTP peer, not a public-origin/network campaign",
                                    "Single API26 emulator/process, no physical device or API37",
                                    "Warm Start only; no kill/cold-loader coverage",
                                    "Checkpoint snapshots do not prove terminal prefix durability",
                                    "No UI assertion; logical progress and exact bytes are checked",
                                    "Fixture preferences maxRetry=1/cross_network=false are retained",
                                    "Existing user data/missions are never removed; fixture/history retained"])
        (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(json.dumps(dict(success=success, artifacts=str(directory), failure=failure)))
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
