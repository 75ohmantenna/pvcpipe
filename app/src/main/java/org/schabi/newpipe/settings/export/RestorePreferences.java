/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Typed journal snapshots preserve SharedPreferences numbers and sets across process death. */
final class RestorePreferences {
    private static final int MAGIC = 0x50564331;

    private RestorePreferences() { }

    static Map<String, Object> copy(final Map<String, ?> source) throws IOException {
        return decode(encode(source));
    }

    static byte[] encode(final Map<String, ?> values) throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(values.size());
            for (final var entry : values.entrySet()) {
                string(out, entry.getKey());
                final Object value = entry.getValue();
                if (value instanceof Boolean) {
                    out.writeByte(1);
                    out.writeBoolean((Boolean) value);
                } else if (value instanceof Integer) {
                    out.writeByte(2);
                    out.writeInt((Integer) value);
                } else if (value instanceof Long) {
                    out.writeByte(3);
                    out.writeLong((Long) value);
                } else if (value instanceof Float) {
                    out.writeByte(4);
                    out.writeFloat((Float) value);
                } else if (value instanceof String) {
                    out.writeByte(5);
                    string(out, (String) value);
                } else if (value instanceof Set<?>) {
                    out.writeByte(6);
                    out.writeInt(((Set<?>) value).size());
                    for (final Object item : (Set<?>) value) {
                        if (!(item instanceof String)) {
                            throw new IOException("Invalid settings set");
                        }
                        string(out, (String) item);
                    }
                } else {
                    throw new IOException("Invalid settings value");
                }
            }
        }
        return bytes.toByteArray();
    }

    static Map<String, Object> decode(final byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Unknown restore settings format");
            }
            final int size = count(in, 6);
            final Map<String, Object> values = new HashMap<>();
            for (int i = 0; i < size; i++) {
                final String key = string(in);
                final Object value;
                switch (in.readUnsignedByte()) {
                    case 1: value = in.readBoolean(); break;
                    case 2: value = in.readInt(); break;
                    case 3: value = in.readLong(); break;
                    case 4: value = in.readFloat(); break;
                    case 5: value = string(in); break;
                    case 6:
                        final int members = count(in, 4);
                        final Set<String> strings = new HashSet<>();
                        for (int j = 0; j < members; j++) {
                            if (!strings.add(string(in))) {
                                throw new IOException("Duplicate restore settings set member");
                            }
                        }
                        value = strings;
                        break;
                    default: throw new IOException("Unknown restore settings type");
                }
                if (values.put(key, value) != null) {
                    throw new IOException("Duplicate restore settings key");
                }
            }
            if (in.available() != 0) {
                throw new IOException("Trailing restore settings data");
            }
            return values;
        }
    }

    private static int count(final DataInputStream in, final int minimumBytes) throws IOException {
        final int count = in.readInt();
        if (count < 0 || count > in.available() / minimumBytes) {
            throw new IOException("Invalid restore settings length");
        }
        return count;
    }

    private static void string(final DataOutputStream out, final String text) throws IOException {
        if (text == null) {
            throw new IOException("Null restore settings key");
        }
        // UTF-16 code units avoid writeUTF's 64 KiB limit and preserve Java strings exactly.
        out.writeInt(text.length());
        for (int i = 0; i < text.length(); i++) {
            out.writeChar(text.charAt(i));
        }
    }

    private static String string(final DataInputStream in) throws IOException {
        final int length = count(in, 2);
        final char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = in.readChar();
        }
        return new String(chars);
    }
}
