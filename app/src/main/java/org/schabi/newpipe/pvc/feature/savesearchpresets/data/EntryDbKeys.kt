package org.schabi.newpipe.pvc.feature.savesearchpresets.data

object EntryDbKeys {

    const val PREF_KEY_JSON_DB = "pvc_search_presets"
    internal const val PREF_KEY_JSON_DB_RECOVERY = "pvc_search_presets_recovery"

    const val VERSION = "version"
    const val SORTING = "sorting"
    const val DIRECTION = "direction"

    const val DEFAULTS = "defaults"
    const val ENTRIES = "entries"

    const val SERVICE = "service"
    const val ENTRY = "entry"

    const val NAME = "name"
    const val CREATED_AT = "created_at"
    const val MODIFIED_AT = "modified_at"
    const val LAST_USED = "last_used"
    const val CONTENT_FILTER_DATA = "content_filter_data"
    const val SORT_FILTER_DATA = "sort_filter_data"

    const val SORT_BY_NAME = "byName"
    const val SORT_BY_LAST_USED = "byLastUsed"
    const val SORT_BY_CREATED = "byCreated"
    const val SORT_BY_MODIFIED = "byModified"

    const val DIR_ASC = "asc"
    const val DIR_DESC = "desc"
}
