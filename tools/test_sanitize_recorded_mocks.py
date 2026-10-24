"""Offline regressions for identifier removal and replay request consistency."""

import base64
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import quote

from sanitize_recorded_mocks import main, sanitize


class SanitizeRecordedMocksTest(unittest.TestCase):
    def test_cli_rejects_detected_but_unsupported_escape_replacements(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            path = Path(directory) / "generated_mock_0.json"
            for body in [r'2001\x3a4860\x3a\x3a1', r'2001\u003A4860\u003A\u003A1']:
                path.write_text(json.dumps({"response": {"responseBody": body}}))
                statistics = {}
                sanitize(directory, write=True, statistics=statistics)
                self.assertGreater(statistics["unreplaced_detected_identifiers"], 0)
                with patch("sys.argv", ["sanitizer", directory]):
                    self.assertEqual(2, main())

    def test_shared_cookie_visitor_marker_remains_synthetic_and_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "generated_mock_0.json"
            path.write_text(json.dumps({"response": {
                "responseHeaders": {"Set-Cookie": ["visitor=synthetic-shared-input"]},
                "responseBody": '{"visitorData":"synthetic-shared-input"}'}}))
            self.assertEqual((1, 1), sanitize(directory, write=True))
            statistics = {}
            self.assertEqual((1, 0), sanitize(directory, statistics=statistics))
            self.assertEqual(0, statistics["unreplaced_detected_identifiers"])

    def test_detect_js_escaped_parameter_delimiters(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "generated_mock_0.json"
            body = r'\u0026ip\u003d2001:4860::1 \x26ip\x3d2001:4860::2 2001\u003a4860\u003a\u003a3'
            path.write_text(json.dumps({"response": {"responseBody": body}}))
            self.assertEqual((1, 1), sanitize(directory, write=True))
            result = json.loads(path.read_text())["response"]["responseBody"]
            self.assertEqual(r'\u0026ip\u003d2001:db8::1 \x26ip\x3d2001:db8::1 2001:db8::1', result)

    def test_cli_rejects_empty_and_binary_inputs_and_reports_dry_run(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            path = Path(directory) / "generated_mock_0.json"
            with patch("sys.argv", ["sanitizer", directory]):
                self.assertEqual(2, main())
            path.write_text(json.dumps({"request": {"dataToSend": [-1]}}))
            with patch("sys.argv", ["sanitizer", directory]):
                self.assertEqual(2, main())
            path.write_text(json.dumps({"response": {"responseBody":
                '{"visitorData":"synthetic-input-visitor"}'}}))
            with patch("sys.argv", ["sanitizer", directory]):
                self.assertEqual(1, main())
            with patch("sys.argv", ["sanitizer", directory, "--write"]):
                self.assertEqual(0, main())
            with patch("sys.argv", ["sanitizer", directory]):
                self.assertEqual(0, main())

    def test_replace_longer_identifiers_before_their_prefix_and_encoded_variants(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "generated_mock_0.json"
            response = '{"visitorData":"synthetic-input==","nested":{"visitorData":"synthetic-input==suffix"}}'
            payload = b'{"visitorData":"synthetic-input%3D%3Dsuffix"}'
            path.write_text(json.dumps({"response": {"responseBody": response},
                                       "request": {"dataToSend": list(payload)}}))
            self.assertEqual((1, 1), sanitize(directory, write=True))
            result = json.loads(path.read_text())
            body = json.loads(result["response"]["responseBody"])
            self.assertEqual("SYNTHETIC_VISITOR_DATA", body["visitorData"])
            self.assertEqual(body["visitorData"], body["nested"]["visitorData"])
            self.assertEqual(b'{"visitorData":"SYNTHETIC_VISITOR_DATA"}',
                             bytes(result["request"]["dataToSend"]))

    def test_replace_nested_encoded_ip_and_escaped_visitor_field(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "generated_mock_0.json"
            url = "https://media.example/ip/2001:4860::1?ip=2001:4860::1"
            encoded = quote(quote(url, safe=""), safe="")
            body = r'\"visitorData\":\"synthetic-input-visitor\" ' + encoded
            path.write_text(json.dumps({"response": {"responseBody": body}}))
            self.assertEqual((1, 1), sanitize(directory, write=True))
            result = json.loads(path.read_text())["response"]["responseBody"]
            self.assertIn("SYNTHETIC_VISITOR_DATA", result)
            self.assertIn(quote(quote(url.replace("2001:4860::1", "2001:db8::1"), safe=""), safe=""), result)

    def test_replace_cookie_repeated_across_files_and_request_only_visitor(self):
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "generated_mock_0.json"
            second = Path(directory) / "generated_mock_1.json"
            cookie = "synthetic-input-cookie"
            first.write_text(json.dumps({"response": {"responseHeaders": {
                "Set-Cookie": ["session=" + cookie + "; Secure"]}}}))
            payload = b'{"visitorData":"synthetic-request-only-visitor"}'
            second.write_text(json.dumps({"request": {"headers": {"Cookie": ["session=" + cookie]},
                                                     "dataToSend": list(payload)},
                                          "response": {"responseBody": cookie}}))
            self.assertEqual((2, 2), sanitize(directory, write=True))
            result = json.loads(second.read_text())
            self.assertEqual(["session=SYNTHETIC_COOKIE"], result["request"]["headers"]["Cookie"])
            self.assertEqual("SYNTHETIC_COOKIE", result["response"]["responseBody"])
            self.assertIn(b"SYNTHETIC_VISITOR_DATA", bytes(result["request"]["dataToSend"]))

    def test_preserve_private_example_address_and_replace_complete_mapped_ip(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "generated_mock_0.json"
            path.write_text(json.dumps({"response": {"responseBody":
                "fe80::1 2001:db8::1 ::ffff:8.8.8.8"}}))
            self.assertEqual((1, 1), sanitize(directory, write=True))
            self.assertEqual("fe80::1 2001:db8::1 2001:db8::1",
                             json.loads(path.read_text())["response"]["responseBody"])

    def test_update_identifier_repeated_in_a_different_request_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "generated_mock_0.json"
            second = Path(directory) / "generated_mock_1.json"
            first.write_text(json.dumps({"response": {"responseBody":
                '{"visitorData":"synthetic-input-visitor"}'}}))
            payload = b'{"visitorData":"synthetic-input-visitor"}'
            second.write_text(json.dumps({"request": {"dataToSend": list(payload)}}))
            self.assertEqual((2, 2), sanitize(directory, write=True))
            request = json.loads(second.read_text())["request"]["dataToSend"]
            self.assertIn(b"SYNTHETIC_VISITOR_DATA", bytes(request))

    def test_preserve_public_protocol_data_and_binary_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "generated_mock_0.json"
            original = json.dumps({"request": {"dataToSend": [-1, -2]}, "response": {
                "responseBody": "https://media.example/video?ip=127.0.0.1&sig=fixture "
                                "https://media.example/video?ip=2001%3Adb8%3A%3A1",
                "responseHeaders": {"Origin-Trial": ["public-protocol-marker"]}}})
            path.write_text(original)
            statistics = {}
            self.assertEqual((1, 0), sanitize(directory, write=True, statistics=statistics))
            self.assertEqual(1, statistics["skipped_binary_request_payloads"])
            self.assertEqual(original, path.read_text())

    def test_replace_captured_media_jwt_without_replacing_other_jwts(self):
        def token(payload):
            encode = lambda value: base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip("=")
            return encode({"alg": "synthetic"}) + "." + encode(payload) + ".synthetic"

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "generated_mock_0.json"
            captured = token({"geo": "synthetic", "iat": 1, "rid": "synthetic", "sub": "synthetic"})
            public = token({"public": "protocol-example"})
            path.write_text(json.dumps({"response": {"responseBody": captured + " " + public}}))
            self.assertEqual((1, 1), sanitize(directory, write=True))
            result = json.loads(path.read_text())["response"]["responseBody"]
            self.assertEqual("SYNTHETIC_MEDIA_TOKEN " + public, result)

    def test_preserve_replay_structure_and_replace_captured_identifiers(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "generated_mock_0.json"
            # An intentionally non-documentation address exercises replacement.
            body = json.dumps({"visitorData": "synthetic-input-visitor", "url":
                               "https://media.example/video?ip=2001%3A4860%3A%3A1&sig=fixture"})
            payload = json.dumps({"visitorData": "synthetic-input-visitor"}).encode()
            recorded = {
                "request": {"url": "https://api.example/next", "dataToSend": list(payload),
                            "headers": {"Cookie": ["SOCS=public-consent-marker"]}},
                "response": {"responseBody": body, "responseHeaders": {
                    "Set-Cookie": ["YSC=synthetic-input-cookie; Secure; HttpOnly"],
                    "Content-Type": ["application/json"]}},
            }
            path.write_text(json.dumps(recorded, indent=2))
            self.assertEqual((1, 1), sanitize(directory))
            self.assertEqual(recorded, json.loads(path.read_text()))
            self.assertEqual((1, 1), sanitize(directory, write=True))
            result = json.loads(path.read_text())
            response = json.loads(result["response"]["responseBody"])
            request = json.loads(bytes(x % 256 for x in result["request"]["dataToSend"]))
            self.assertEqual("SYNTHETIC_VISITOR_DATA", response["visitorData"])
            self.assertEqual(response["visitorData"], request["visitorData"])
            self.assertIn("ip=2001%3Adb8%3A%3A1&sig=fixture", response["url"])
            self.assertEqual(["YSC=SYNTHETIC_COOKIE; Secure; HttpOnly"],
                             result["response"]["responseHeaders"]["Set-Cookie"])
            self.assertEqual(recorded["request"]["headers"], result["request"]["headers"])
            self.assertEqual((1, 0), sanitize(directory))


if __name__ == "__main__":
    unittest.main()
