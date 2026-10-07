/*
 * SPDX-FileCopyrightText: 2024-2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export

import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass

/**
 * Restricts class resolution for legacy serialized preferences to boxed primitives, HashMap,
 * and HashSet. Primitives and strings use dedicated serialization encodings; object arrays are
 * not on the class whitelist.
 */
class PreferencesObjectInputStream(stream: InputStream) : ObjectInputStream(stream) {
    @Throws(ClassNotFoundException::class, IOException::class)
    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
        if (desc.name in CLASS_WHITELIST) {
            return super.resolveClass(desc)
        } else {
            throw ClassNotFoundException("Class not allowed: $desc.name")
        }
    }

    companion object {
        /**
         * Primitive types, strings and other built-in types do not pass through resolveClass() but
         * instead have a custom encoding; see
         * [
         * official docs](https://docs.oracle.com/javase/6/docs/platform/serialization/spec/protocol.html#10152).
         */
        private val CLASS_WHITELIST = setOf<String>(
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Character",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Void",
            "java.util.HashMap",
            "java.util.HashSet"
        )
    }
}
