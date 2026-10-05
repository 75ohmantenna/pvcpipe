package org.schabi.newpipe.pvc.feature.savesearchpresets.data

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter

/** Converts preset states to and from the existing SharedPreferences JSON format. */
internal object EntryDatabaseJson {
    fun decode(jsonString: String): DBState {
        val root = JsonParser.`object`().from(jsonString)

        val version = root.getInt(EntryDbKeys.VERSION)
        val sorting = PresetSortMappings.dbKeyToSort[root.getString(EntryDbKeys.SORTING)]!!
        val direction =
            PresetSortMappings.dbKeyToSortDirection[root.getString(EntryDbKeys.DIRECTION)]!!

        val defaults = mutableListOf<DefaultEntry>()
        val defaultsArray = root.getArray(EntryDbKeys.DEFAULTS)

        if (defaultsArray != null) {
            for (i in 0 until defaultsArray.size) {
                val obj = defaultsArray.getObject(i)

                defaults.add(
                    DefaultEntry(
                        obj.getString(EntryDbKeys.SERVICE),
                        obj.getLong(EntryDbKeys.ENTRY)
                    )
                )
            }
        }

        val entries = mutableListOf<Entry>()
        val entriesArray = root.getArray(EntryDbKeys.ENTRIES)

        if (entriesArray != null) {
            for (i in 0 until entriesArray.size) {
                val obj = entriesArray.getObject(i)

                val contentFilterArray = obj.getArray(EntryDbKeys.CONTENT_FILTER_DATA)
                val sortFilterArray = obj.getArray(EntryDbKeys.SORT_FILTER_DATA)
                val contentFilterData = mutableListOf<Int>()
                val sortFilterData = mutableListOf<Int>()

                for (j in 0 until contentFilterArray.size) {
                    contentFilterData.add(contentFilterArray.getInt(j))
                }

                for (j in 0 until sortFilterArray.size) {
                    sortFilterData.add(sortFilterArray.getInt(j))
                }

                entries.add(
                    Entry(
                        obj.getString(EntryDbKeys.NAME),
                        obj.getString(EntryDbKeys.SERVICE),
                        obj.getLong(EntryDbKeys.CREATED_AT),
                        obj.getLong(EntryDbKeys.MODIFIED_AT),
                        obj.getLong(EntryDbKeys.LAST_USED),
                        contentFilterData,
                        sortFilterData
                    )
                )
            }
        }

        return DBState(version, sorting, direction, defaults, entries)
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
