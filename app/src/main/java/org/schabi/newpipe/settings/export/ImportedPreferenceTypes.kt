package org.schabi.newpipe.settings.export

import java.math.BigDecimal

/**
 * Makes the values of an imported settings file fit the types the app reads them with.
 *
 * A settings file is not trusted, and its JSON form does not tell an Int from a Long or from a
 * Float with a whole value. A value of the wrong type survives the import and then throws
 * [ClassCastException] at the first typed read, which for some keys is the start of the app.
 */
object ImportedPreferenceTypes {
    /**
     * Checks a whole import.
     *
     * A key the app reads as a number is held to that type. Other keys with a live value
     * are held to its type. A mismatched value is converted if that does not change it,
     * and is otherwise replaced by the live value, or dropped so that the default applies.
     * Keys without a numeric type or live value are left unchanged; this does not validate
     * every possible imported preference.
     *
     * Keys awaiting a settings migration are also left alone. The migration runs at the
     * next start and needs the old value in its old type.
     *
     * @param imported the decoded settings, changed in place
     * @param numericTypes the type of every key the app reads as a number
     * @param versionKey the key of the settings version
     * @param migratedTypes for each key whose type a migration changed, the settings version
     * from which it has its present type
     * @param live the settings the app has now
     */
    @JvmStatic
    fun conformImport(
        imported: MutableMap<String, Any>,
        numericTypes: Map<String, Class<*>>,
        versionKey: String,
        migratedTypes: Map<String, Int>,
        live: Map<String, *>
    ) {
        // the version first: it decides which keys are still in an older type
        conform(imported, numericTypes, live, setOf(versionKey))
        // SettingMigrations reads a missing version as 0
        val version = imported[versionKey] as? Int ?: 0
        val awaitingMigration = migratedTypes.filterValues { version < it }.keys
        conform(imported, numericTypes, live, imported.keys - awaitingMigration)
    }

    private fun conform(
        imported: MutableMap<String, Any>,
        numericTypes: Map<String, Class<*>>,
        live: Map<String, *>,
        keys: Set<String>
    ) {
        for (key in keys.toList()) {
            val value = imported[key] ?: continue
            val liveValue = live[key]
            val type = numericTypes[key] ?: liveValue?.let(::typeOf) ?: continue
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
        val exact: BigDecimal = when (value) {
            is Int -> BigDecimal(value)

            is Long -> BigDecimal(value)

            // the conversion of a finite Float to Double and on to BigDecimal is exact
            is Float -> if (value.isFinite()) BigDecimal(value.toDouble()) else return null

            else -> return null
        }
        return when (type) {
            Long::class.javaObjectType -> exact.wholeOrNull()

            Int::class.javaObjectType ->
                exact.wholeOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

            Float::class.javaObjectType ->
                exact.toFloat().takeIf { it.isFinite() && BigDecimal(it.toDouble()).compareTo(exact) == 0 }

            else -> null
        }
    }

    private fun BigDecimal.wholeOrNull(): Long? = try {
        longValueExact()
    } catch (e: ArithmeticException) {
        null
    }
}
