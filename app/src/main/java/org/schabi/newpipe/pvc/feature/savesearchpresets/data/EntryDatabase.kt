package org.schabi.newpipe.pvc.feature.savesearchpresets.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.grack.nanojson.JsonParserException
import org.schabi.newpipe.pvc.feature.savesearchpresets.domain.SortDirection
import org.schabi.newpipe.pvc.feature.savesearchpresets.domain.SortType

/**
 * this is Preset database based just on a JSON String that is stored into SharedPreferences.
 *
 * Here we have the JSON example database structure:
 * {
 *  "version" : 1,
 *  "sorting" : "byName",
 *  "direction" : "desc",
 *  "defaults" : [ {
 *    "service" : "YouTube",
 *    "entry" : 1774216721895
 *  } ],
 *  "entries" : [ {
 *    "name" : "My Preset",
 *    "service" : "YouTube",
 *    "created_at" : 1774216721895, // this timestamp is used as unique id for each entry
 *    "modified_at" : 1774216753902,
 *    "last_used" : 1774216823270,
 *    "sort_filter_data" : [ 12, 17, 24 ],
 *    "content_filter_data" : [ 3 ]
 *  } ]
 *}
 */
class EntryDatabase internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(PreferenceManager.getDefaultSharedPreferences(context))

    private var unreadableJson: String? = null

    fun load(): DBState {
        unreadableJson = null
        val jsonString = prefs.getString(EntryDbKeys.PREF_KEY_JSON_DB, null)
            ?: return emptyState()

        return try {
            EntryDatabaseJson.decode(jsonString)
        } catch (exception: JsonParserException) {
            recover(jsonString, exception)
        } catch (exception: IllegalArgumentException) {
            recover(jsonString, exception)
        }
    }

    fun save(state: DBState) {
        val json = EntryDatabaseJson.encode(state)
        val editor = prefs.edit()
        // Keep unreadable data before replacing it, including earlier recovery snapshots.
        unreadableJson?.let { original ->
            val recovered = prefs.getStringSet(EntryDbKeys.PREF_KEY_JSON_DB_RECOVERY, emptySet())
                .orEmpty() + original
            editor.putStringSet(EntryDbKeys.PREF_KEY_JSON_DB_RECOVERY, recovered)
        }
        editor.putString(EntryDbKeys.PREF_KEY_JSON_DB, json).apply()
        unreadableJson = null
    }

    private fun recover(jsonString: String, exception: Exception): DBState {
        unreadableJson = jsonString
        Log.w("EntryDatabase", "Unable to load search presets", exception)
        return emptyState()
    }

    private fun emptyState(): DBState {
        return DBState(
            version = 1,
            sorting = PresetSortMappings.dbKeyToSort[EntryDbKeys.SORT_BY_NAME]!!,
            direction = PresetSortMappings.dbKeyToSortDirection[EntryDbKeys.DIR_DESC]!!,
            defaults = emptyList(),
            entries = emptyList()
        )
    }
}

data class Entry(
    val name: String,
    val service: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val lastUsed: Long,
    val contentFilterData: List<Int>,
    val sortFilterData: List<Int>
)

data class DefaultEntry(
    val service: String,
    val entry: Long
)

data class DBState(
    val version: Int,
    val sorting: SortType,
    val direction: SortDirection,
    val defaults: List<DefaultEntry>,
    val entries: List<Entry>
)
