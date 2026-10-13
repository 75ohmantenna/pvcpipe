package org.schabi.newpipe.settings.export

import kotlin.math.abs

/**
 * Makes the values of an imported settings file fit the types the app reads them with.
 *
 * A settings file is not trusted, and its JSON form does not tell an Int from a Long or from a
 * Float with a whole value. A value of the wrong type survives the import and then throws
 * [ClassCastException] at the first typed read, which for some keys is the start of the app.
 */
object ImportedPreferenceTypes {
    // Every whole number up to here is exactly a Float as well.
    private const val MAX_EXACT_FLOAT = 1L shl 24

    /**
     * Checks a whole import. The keys the app reads as numbers are always held to their type.
     * The other keys are held to the type of the app's live value only when the file was
     * written by the current settings version: an older file may hold a key in the type it had
     * then, and the settings migrations convert it at the next start.
     *
     * @param imported the decoded settings, changed in place
     * @param numericTypes the type of every key the app reads as a number
     * @param versionKey the key of the settings version
     * @param currentVersion the settings version of this build
     * @param live the settings the app has now
     */
    @JvmStatic
    fun conformImport(
        imported: MutableMap<String, Any>,
        numericTypes: Map<String, Class<*>>,
        versionKey: String,
        currentVersion: Int,
        live: Map<String, *>
    ) {
        conform(imported, numericTypes, live, liveTypes = false)
        if (imported[versionKey] == currentVersion) {
            conform(imported, numericTypes, live, liveTypes = true)
        }
    }

    /**
     * @param imported the decoded settings, changed in place
     * @param expected the type the app reads a key with, for the keys it reads as a number
     * @param live the settings the app has now. A live value of the right type replaces an
     * imported value that cannot be used.
     * @param liveTypes whether a key that [expected] does not list is held to the type of its
     * live value, which is the type of its default for the keys the settings screens define
     */
    private fun conform(
        imported: MutableMap<String, Any>,
        expected: Map<String, Class<*>>,
        live: Map<String, *>,
        liveTypes: Boolean
    ) {
        val keys = imported.keys.toList()
        for (key in keys) {
            val value = imported.getValue(key)
            val liveValue = live[key]
            val type = expected[key]
                ?: liveValue?.takeIf { liveTypes }?.let(::typeOf)
                ?: continue
            if (typeOf(value) == type) {
                continue
            }
            val converted = convert(value, type)
            when {
                converted != null -> imported[key] = converted

                // the app keeps the value it has
                liveValue != null && typeOf(liveValue) == type -> imported[key] = liveValue

                // the default applies
                else -> imported.remove(key)
            }
        }
    }

    private fun typeOf(value: Any): Class<*> = when (value) {
        is Boolean -> Boolean::class.javaObjectType
        is Int -> Int::class.javaObjectType
        is Long -> Long::class.javaObjectType
        is Float -> Float::class.javaObjectType
        is String -> String::class.java
        is Set<*> -> Set::class.java
        else -> value.javaClass
    }

    /** @return the same number as [type], or `null` if [type] cannot hold exactly that number */
    private fun convert(value: Any, type: Class<*>): Any? {
        val whole: Long = when (value) {
            is Int -> value.toLong()

            is Long -> value

            is Float -> if (value.isFinite() && value == Math.rint(value.toDouble()).toFloat() &&
                abs(value) <= MAX_EXACT_FLOAT.toFloat()
            ) {
                value.toLong()
            } else {
                return null
            }

            else -> return null
        }
        return when (type) {
            Long::class.javaObjectType -> whole

            Int::class.javaObjectType -> whole.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

            Float::class.javaObjectType ->
                whole.takeIf { it in -MAX_EXACT_FLOAT..MAX_EXACT_FLOAT }?.toFloat()

            else -> null
        }
    }
}
