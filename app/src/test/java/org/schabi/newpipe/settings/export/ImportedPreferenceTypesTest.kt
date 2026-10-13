package org.schabi.newpipe.settings.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedPreferenceTypesTest {
    private val int = Int::class.javaObjectType
    private val long = Long::class.javaObjectType
    private val float = Float::class.javaObjectType
    private val numeric = mapOf(
        "version" to int,
        "threads" to int,
        "timestamp" to long,
        "speed" to float
    )

    // version 3 kept this key as a boolean; migration 3 to 4 turns it into a set
    private val migrated = mapOf("suggestions" to 4)

    private fun import(
        imported: Map<String, Any>,
        live: Map<String, Any> = emptyMap()
    ): Map<String, Any> = imported.toMutableMap().also {
        ImportedPreferenceTypes.conformImport(it, numeric, "version", migrated, live)
    }

    @Test
    fun `a wrong-typed settings version does not survive the import`() {
        // App.onCreate reads this key with getInt before anything else
        assertFalse("version" in import(mapOf("version" to "bad")))
        assertEquals(9, import(mapOf("version" to "bad"), live = mapOf("version" to 9))["version"])
        assertFalse("version" in import(mapOf("version" to true)))
        assertFalse("version" in import(mapOf("version" to setOf("9"))))
    }

    @Test
    fun `a numeric key is checked even when the app has no value for it`() {
        // playback speed is only written once the user changes it
        assertFalse("speed" in import(mapOf("speed" to "fast")))
        assertEquals(1.5f, import(mapOf("speed" to 1.5f))["speed"])
        assertFalse("timestamp" in import(mapOf("timestamp" to 1.5f)))
    }

    @Test
    fun `a whole JSON number for a float preference becomes that float`() {
        // JSON does not tell 2 from 2.0
        assertEquals(2.0f, import(mapOf("speed" to 2))["speed"])
        assertEquals(2.0f, import(mapOf("speed" to 2L))["speed"])
        assertEquals(-1.0f, import(mapOf("speed" to -1))["speed"])
    }

    @Test
    fun `an int and a long are exchanged when the number fits`() {
        assertEquals(1700000000000L, import(mapOf("timestamp" to 1700000000000L))["timestamp"])
        assertEquals(5L, import(mapOf("timestamp" to 5))["timestamp"])
        assertEquals(3, import(mapOf("threads" to 3L))["threads"])
        assertEquals(3, import(mapOf("threads" to 3.0f))["threads"])
    }

    @Test
    fun `a number is converted exactly or not at all`() {
        // one past what an Int holds, on both sides
        assertEquals(Int.MAX_VALUE, import(mapOf("threads" to Int.MAX_VALUE.toLong()))["threads"])
        assertEquals(Int.MIN_VALUE, import(mapOf("threads" to Int.MIN_VALUE.toLong()))["threads"])
        assertFalse("threads" in import(mapOf("threads" to Int.MAX_VALUE + 1L)))
        assertFalse("threads" in import(mapOf("threads" to Int.MIN_VALUE - 1L)))
        // a fraction is not a whole number
        assertFalse("threads" in import(mapOf("threads" to 2.5f)))
        assertFalse("threads" in import(mapOf("threads" to Float.NaN)))
        assertFalse("threads" in import(mapOf("threads" to Float.POSITIVE_INFINITY)))
        // 2^31 as a Float is one past the largest Int
        assertFalse("threads" in import(mapOf("threads" to 2147483648f)))

        // up to 2^24 every whole number is a Float; above it only some are
        assertEquals(16777216f, import(mapOf("speed" to 16777216))["speed"])
        assertFalse("speed" in import(mapOf("speed" to 16777217)))
        assertEquals(16777218f, import(mapOf("speed" to 16777218))["speed"])
        assertEquals(33554432f, import(mapOf("speed" to 33554432L))["speed"])
        assertFalse("speed" in import(mapOf("speed" to Long.MAX_VALUE)))
        // and such a Float goes back to exactly that whole number
        assertEquals(16777218, import(mapOf("threads" to 16777218f))["threads"])
        assertEquals(1L shl 40, import(mapOf("timestamp" to (1L shl 40).toFloat()))["timestamp"])
    }

    @Test
    fun `a value that cannot be used gives way to the live value`() {
        assertEquals(4, import(mapOf("threads" to "many"), live = mapOf("threads" to 4))["threads"])
        // a live value of the wrong type is no help
        assertFalse("threads" in import(mapOf("threads" to "many"), live = mapOf("threads" to "4")))
    }

    @Test
    fun `every other key is held to the type of the live value`() {
        val live = mapOf("autoplay" to true, "theme" to "dark", "hosts" to setOf("a:b"))
        val imported = import(
            mapOf(
                "version" to 9,
                "autoplay" to "yes",
                "theme" to 3,
                "hosts" to "a:b",
                "unknown" to 1
            ),
            live
        )
        assertEquals(true, imported["autoplay"])
        assertEquals("dark", imported["theme"])
        assertEquals(setOf("a:b"), imported["hosts"])
        // nothing is known about this key, so it is imported as it is
        assertEquals(1, imported["unknown"])
    }

    @Test
    fun `a file from an older version is checked as well`() {
        // the app reads this switch with getBoolean while it starts, before any migration
        val live = mapOf("hide_paid" to false, "suggestions" to setOf("local"))
        for (version in listOf(0, 3, 8)) {
            val imported = import(mapOf("version" to version, "hide_paid" to "yes"), live)
            assertEquals(false, imported["hide_paid"])
            assertEquals(version, imported["version"])
        }
        // a file without a version is read as version 0
        assertEquals(false, import(mapOf("hide_paid" to "yes"), live)["hide_paid"])
    }

    @Test
    fun `a key keeps its old type until its migration has run`() {
        val live = mapOf("suggestions" to setOf("local", "remote"))
        // before version 4 the key is a boolean, and migration 3 to 4 has to read it
        assertEquals(false, import(mapOf("version" to 3, "suggestions" to false), live)["suggestions"])
        assertEquals(false, import(mapOf("suggestions" to false), live)["suggestions"])
        // from version 4 on it is a set, and a boolean is a broken value
        assertEquals(
            setOf("local", "remote"),
            import(mapOf("version" to 4, "suggestions" to false), live)["suggestions"]
        )
        assertEquals(
            setOf("local", "remote"),
            import(mapOf("version" to 9, "suggestions" to false), live)["suggestions"]
        )
        // an unreadable version is replaced by the live one, which is the current one
        assertEquals(
            setOf("local", "remote"),
            import(mapOf("version" to "bad", "suggestions" to false), live + ("version" to 9))["suggestions"]
        )
    }

    @Test
    fun `values of the right type are left alone`() {
        val settings = mapOf<String, Any>(
            "version" to 9,
            "threads" to 3,
            "timestamp" to 5L,
            "speed" to 1.25f,
            "theme" to "dark"
        )
        assertEquals(settings, import(settings, live = mapOf("theme" to "light")))
        assertTrue(import(emptyMap()).isEmpty())
    }
}
