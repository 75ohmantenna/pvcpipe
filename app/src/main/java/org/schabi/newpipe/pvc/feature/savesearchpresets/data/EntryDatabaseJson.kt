package org.schabi.newpipe.pvc.feature.savesearchpresets.data

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.math.BigInteger
import org.schabi.newpipe.pvc.feature.savesearchpresets.domain.SortDirection
import org.schabi.newpipe.pvc.feature.savesearchpresets.domain.SortType

/** Converts preset states to and from the existing SharedPreferences JSON format. */
internal object EntryDatabaseJson {
    fun decode(jsonString: String): DBState {
        val root = JsonParser.`object`().from(jsonString)

        val version = root.requiredLong(EntryDbKeys.VERSION)
        require(version == 1L) { "Unsupported preset database version" }
        val sorting = PresetSortMappings.dbKeyToSort[root.getString(EntryDbKeys.SORTING)]
            ?: SortType.NAME
        val direction =
            PresetSortMappings.dbKeyToSortDirection[root.getString(EntryDbKeys.DIRECTION)]
                ?: SortDirection.DESC

        val defaults = root.optionalArray(EntryDbKeys.DEFAULTS).orEmpty().map { value ->
            val obj = requiredObject(value)
            DefaultEntry(obj.requiredString(EntryDbKeys.SERVICE), obj.requiredLong(EntryDbKeys.ENTRY))
        }
        val entries = root.optionalArray(EntryDbKeys.ENTRIES).orEmpty().map { value ->
            val obj = requiredObject(value)
            Entry(
                obj.requiredString(EntryDbKeys.NAME),
                obj.requiredString(EntryDbKeys.SERVICE),
                obj.requiredLong(EntryDbKeys.CREATED_AT),
                obj.requiredLong(EntryDbKeys.MODIFIED_AT),
                obj.requiredLong(EntryDbKeys.LAST_USED),
                obj.requiredIntArray(EntryDbKeys.CONTENT_FILTER_DATA),
                obj.requiredIntArray(EntryDbKeys.SORT_FILTER_DATA)
            )
        }

        return DBState(version.toInt(), sorting, direction, defaults, entries)
    }

    private fun requiredObject(value: Any?): JsonObject {
        require(value is JsonObject) { "Invalid preset record" }
        return value
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = this[key]
        require(value is String) { "Invalid preset field: $key" }
        return value
    }

    private fun JsonObject.requiredLong(key: String): Long = integralNumber(this[key], key)

    private fun integralNumber(value: Any?, key: String): Long {
        return when (value) {
            is Int -> value.toLong()

            is Long -> value

            is BigInteger -> {
                require(value.bitLength() <= 63) { "Invalid preset field: $key" }
                value.toLong()
            }

            else -> throw IllegalArgumentException("Invalid preset field: $key")
        }
    }

    private fun JsonObject.optionalArray(key: String): JsonArray? {
        val value = this[key]
        require(value == null || value is JsonArray) { "Invalid preset field: $key" }
        return value
    }

    private fun JsonObject.requiredIntArray(key: String): List<Int> {
        val array = optionalArray(key)
        require(array != null) { "Missing preset field: $key" }
        return array.map { value ->
            val number = integralNumber(value, key)
            require(number in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid preset filter: $key" }
            number.toInt()
        }
    }

    fun encode(state: DBState): String {
        val root = JsonObject()

        root[EntryDbKeys.VERSION] = state.version
        root[EntryDbKeys.SORTING] = PresetSortMappings.sortToDbKey[state.sorting]!!
        root[EntryDbKeys.DIRECTION] = PresetSortMappings.sortDirectionToDbKey[state.direction]!!

        val defaultsArray = JsonArray()

        for (defaultEntry in state.defaults) {
            val obj = JsonObject()
            obj[EntryDbKeys.SERVICE] = defaultEntry.service
            obj[EntryDbKeys.ENTRY] = defaultEntry.entry
            defaultsArray.add(obj)
        }

        root[EntryDbKeys.DEFAULTS] = defaultsArray

        val entriesArray = JsonArray()

        for (entry in state.entries) {
            val obj = JsonObject()

            obj[EntryDbKeys.NAME] = entry.name
            obj[EntryDbKeys.SERVICE] = entry.service
            obj[EntryDbKeys.CREATED_AT] = entry.createdAt
            obj[EntryDbKeys.MODIFIED_AT] = entry.modifiedAt
            obj[EntryDbKeys.LAST_USED] = entry.lastUsed

            val sortFilterArray = JsonArray()
            val contentFilterArray = JsonArray()

            for (filterId in entry.sortFilterData) {
                sortFilterArray.add(filterId)
            }
            obj[EntryDbKeys.SORT_FILTER_DATA] = sortFilterArray

            for (filterId in entry.contentFilterData) {
                contentFilterArray.add(filterId)
            }
            obj[EntryDbKeys.CONTENT_FILTER_DATA] = contentFilterArray

            entriesArray.add(obj)
        }

        root[EntryDbKeys.ENTRIES] = entriesArray

        return JsonWriter.string(root)
    }
}
