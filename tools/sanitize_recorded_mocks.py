#!/usr/bin/env python3
"""Replace captured identifiers in recorded test fixtures without displaying them.

This is a narrow maintenance tool, not a general secret scanner. Review newly
recorded responses before publication, including binary request bodies and URLs.
"""

import argparse
import base64
import ipaddress
import json
from pathlib import Path
import re
from urllib.parse import quote, unquote


STRING = re.compile(r'"(?:[^"\\]|\\.)*"')
VISITOR = re.compile(r'("(?:visitorData|VISITOR_DATA)"\s*:\s*")([^"\\]+)(")')
IPV6 = re.compile(r'(?<![\w:.])[0-9a-f:.]*:[0-9a-f:.]+(?![\w:.])', re.I)
JWT = re.compile(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+')
JS_ESCAPE = re.compile(r'\\u([0-9a-fA-F]{4})|\\x([0-9a-fA-F]{2})')
SYNTHETIC_VALUES = {"SYNTHETIC_VISITOR_DATA", "SYNTHETIC_COOKIE", "SYNTHETIC_MEDIA_TOKEN"}


def scrub_address(value):
    if value.count(":") < 2 and "%3a" not in value.lower():
        return value
    try:
        address = ipaddress.ip_address(unquote(value))
    except ValueError:
        return value
    if address.version != 6:
        return value
    mapped = address.ipv4_mapped
    if not (mapped.is_global if mapped is not None else address.is_global):
        return value
    return "2001%3Adb8%3A%3A1" if "%" in value else "2001:db8::1"


def decoded_layers(value):
    """Inspect nested encoding without rewriting unrelated response content."""
    for _ in range(5):
        yield value
        decoded = JS_ESCAPE.sub(lambda m: chr(int(m[1] or m[2], 16)), unquote(value))
        decoded = decoded.replace(r'\"', '"')
        if decoded == value:
            break
        value = decoded


def sanitize(root, write=False, statistics=None):
    files = sorted(Path(root).rglob("generated_mock_*.json"))
    replacements = {}
    skipped_binary = 0
    unreplaced = 0

    def remember(old, new):
        if old == new or old in SYNTHETIC_VALUES:
            return
        # Gson/JavaScript may escape delimiters inside an embedded string.
        for chars in ("=<>&'", ":"):
            escaped = re.sub("[" + re.escape(chars) + "]",
                             lambda m: "\\u%04x" % ord(m[0]), old)
            if escaped != old:
                replacements[escaped] = new
        for _ in range(5):
            replacements[old] = new
            encoded_old, encoded_new = quote(old, safe=""), quote(new, safe="")
            if encoded_old == old:
                break
            old, new = encoded_old, encoded_new

    def collect(value):
        for layer in decoded_layers(value):
            for match in VISITOR.finditer(layer):
                remember(match[2], "SYNTHETIC_VISITOR_DATA")
            for match in IPV6.finditer(layer):
                candidate = match[0].rstrip(".")
                remember(candidate, scrub_address(candidate))
            for match in JWT.finditer(layer):
                token = match[0]
                try:
                    payload = json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "==="))
                except (ValueError, UnicodeError):
                    continue
                if isinstance(payload, dict) and {"geo", "iat", "rid", "sub"} <= payload.keys():
                    remember(token, "SYNTHETIC_MEDIA_TOKEN")

    def walk(value):
        if isinstance(value, str):
            collect(value)
        elif isinstance(value, dict):
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    def string_values(value):
        if isinstance(value, str):
            yield value
        elif isinstance(value, dict):
            for child in value.values():
                yield from string_values(child)
        elif isinstance(value, list):
            for child in value:
                yield from string_values(child)

    # Collect globally so a value repeated in another request/header/body stays consistent.
    for path in files:
        data = json.loads(path.read_text(encoding="utf-8"))
        walk(data)
        for name, values in data.get("response", {}).get("responseHeaders", {}).items():
            if name.lower() == "set-cookie":
                for value in values:
                    pair = value.partition(";")[0]
                    _, equals, cookie_value = pair.partition("=")
                    # Short/common cookie values cannot safely be replaced globally.
                    if equals and len(cookie_value) >= 8:
                        remember(cookie_value, "SYNTHETIC_COOKIE")
        payload = data.get("request", {}).get("dataToSend")
        if payload:
            try:
                collect(bytes(x % 256 for x in payload).decode("utf-8"))
            except UnicodeError:
                skipped_binary += 1

    def canonical_percent(value):
        return re.sub(r"%[0-9a-fA-F]{2}", lambda m: m[0].upper(), value)

    canonical = {canonical_percent(k): v for k, v in replacements.items()}
    patterns = {
        value: re.sub(r"%[0-9A-F]{2}", lambda m: "(?i:" + m[0] + ")", re.escape(value))
        for value in canonical
    }
    replacement_pattern = None

    def text(value):
        if replacement_pattern is None:
            return value
        return replacement_pattern.sub(lambda m: canonical[canonical_percent(m[0])], value)

    changed = 0
    for path in files:
        original = path.read_text(encoding="utf-8")
        data = json.loads(original)
        request = data.get("request", {})
        payload = request.get("dataToSend")
        decoded = None
        if payload:
            try:
                decoded = bytes(x % 256 for x in payload).decode("utf-8")
            except UnicodeError:
                pass
        # Limit the regex to values actually present in this fixture. A large
        # global alternation is needlessly expensive on minified player scripts.
        searchable = canonical_percent("\n".join(string_values(data)) + "\n" + (decoded or ""))
        active = [value for value in canonical if value in searchable]
        active.sort(key=len, reverse=True)
        replacement_pattern = re.compile("|".join(patterns[value] for value in active)) if active else None
        edits = {}
        response = data.get("response", {})
        for name, values in response.get("responseHeaders", {}).items():
            if name.lower() != "set-cookie":
                continue
            for value in values:
                pair, separator, attributes = value.partition(";")
                cookie_name, equals, _ = pair.partition("=")
                if equals:
                    edits[value] = cookie_name + "=SYNTHETIC_COOKIE" + separator + attributes

        def literal(match):
            value = json.loads(match[0])
            replacement = text(edits.get(value, value))
            return match[0] if replacement == value else json.dumps(replacement, ensure_ascii=False)

        updated = STRING.sub(literal, original)
        if payload:
            if decoded is not None and text(decoded) != decoded:
                new_payload = [x if x < 128 else x - 256 for x in text(decoded).encode()]
                updated = re.sub(
                    r'("dataToSend"\s*:\s*)\[[^\]]*\]',
                    lambda m: m[1] + json.dumps(new_payload), updated, count=1,
                )
        # Detection deliberately understands more escapes than replacement.
        # Fail the CLI gate if a detected value could not be replaced safely.
        original_replacements = replacements
        replacements = {}
        updated_data = json.loads(updated)
        walk(updated_data)
        updated_payload = updated_data.get("request", {}).get("dataToSend")
        if updated_payload:
            try:
                collect(bytes(x % 256 for x in updated_payload).decode("utf-8"))
            except UnicodeError:
                pass
        unreplaced += len(replacements)
        replacements = original_replacements
        if updated != original:
            json.loads(updated)
            changed += 1
            if write:
                path.write_text(updated, encoding="utf-8")
    if statistics is not None:
        statistics["skipped_binary_request_payloads"] = skipped_binary
        statistics["replacement_variants"] = len(replacements)
        statistics["unreplaced_detected_identifiers"] = unreplaced
    return len(files), changed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    statistics = {}
    total, changed = sanitize(args.root, args.write, statistics)
    print(f"Checked {total} recorded fixtures; {changed} require synthetic replacements.")
    if total == 0:
        print("No recorded fixtures found; check the input path.")
        return 2
    skipped = statistics["skipped_binary_request_payloads"]
    print(f"Skipped binary request payloads requiring separate review: {skipped}.")
    remaining = statistics["unreplaced_detected_identifiers"]
    print(f"Detected identifiers still requiring review: {remaining}.")
    if skipped or remaining:
        return 2
    return 0 if args.write or changed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
