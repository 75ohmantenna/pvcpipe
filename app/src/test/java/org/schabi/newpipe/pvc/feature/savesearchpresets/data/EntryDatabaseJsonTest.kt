package org.schabi.newpipe.pvc.feature.savesearchpresets.data

import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.pvc.feature.savesearchpresets.domain.SortDirection
import org.schabi.newpipe.pvc.feature.savesearchpresets.domain.SortType

class EntryDatabaseJsonTest {
    private val storedJson = """
        {
          "version": 1,
          "sorting": "byLastUsed",
          "direction": "asc",
          "defaults": [{"service": "YouTube", "entry": 1774216721895}],
          "entries": [{
            "name": "News \"daily\" \\ 音楽",
            "service": "YouTube",
            "created_at": 1774216721895,
            "modified_at": 1774216753902,
            "last_used": 1774216823270,
            "sort_filter_data": [12, 17, 12],
            "content_filter_data": [3, 1]
          }]
        }
    """.trimIndent()

    private val storedState = DBState(
        version = 1,
        sorting = SortType.LAST_USED,
        direction = SortDirection.ASC,
        defaults = listOf(DefaultEntry("YouTube", 1774216721895)),
        entries = listOf(
            Entry(
                name = "News \"daily\" \\ 音楽",
                service = "YouTube",
                createdAt = 1774216721895,
                modifiedAt = 1774216753902,
                lastUsed = 1774216823270,
                contentFilterData = listOf(3, 1),
                sortFilterData = listOf(12, 17, 12)
            )
        )
    )

    @Test
    fun `decodes existing keys without losing timestamps or filter order`() {
        assertEquals(storedState, EntryDatabaseJson.decode(storedJson))
    }

    @Test
    fun `encodes the existing format including escaped names`() {
        assertEquals(
            JsonParser.`object`().from(storedJson),
            JsonParser.`object`().from(EntryDatabaseJson.encode(storedState))
        )
    }

    @Test
    fun `preserves every sort and direction mapping`() {
        val sortKeys = mapOf(
            SortType.NAME to "byName",
            SortType.LAST_USED to "byLastUsed",
            SortType.CREATED to "byCreated",
            SortType.MODIFIED to "byModified"
        )
        val directionKeys = mapOf(SortDirection.ASC to "asc", SortDirection.DESC to "desc")
        for ((sort, sortKey) in sortKeys) {
            for ((direction, directionKey) in directionKeys) {
                val state = storedState.copy(sorting = sort, direction = direction)
                val json = EntryDatabaseJson.encode(state)
                val root = JsonParser.`object`().from(json)
                assertEquals(sortKey, root.getString("sorting"))
                assertEquals(directionKey, root.getString("direction"))
                assertEquals(state, EntryDatabaseJson.decode(json))
            }
        }
    }

    @Test
    fun `missing collection keys retain empty collections`() {
        assertEquals(
            DBState(1, SortType.NAME, SortDirection.DESC, emptyList(), emptyList()),
            EntryDatabaseJson.decode("""{"version":1,"sorting":"byName","direction":"desc"}""")
        )
    }

    @Test
    fun `empty collections are written explicitly`() {
        val state = DBState(1, SortType.NAME, SortDirection.DESC, emptyList(), emptyList())
        assertEquals(
            JsonParser.`object`().from(
                """{"version":1,"sorting":"byName","direction":"desc","defaults":[],"entries":[]}"""
            ),
            JsonParser.`object`().from(EntryDatabaseJson.encode(state))
        )
    }

    @Test
    fun `malformed JSON and unknown sort keys still fail`() {
        assertThrows(JsonParserException::class.java) { EntryDatabaseJson.decode("invalid") }
        assertThrows(NullPointerException::class.java) {
            EntryDatabaseJson.decode("""{"version":1,"sorting":"unknown","direction":"desc"}""")
        }
        assertThrows(NullPointerException::class.java) {
            EntryDatabaseJson.decode("""{"version":1,"sorting":"byName","direction":"unknown"}""")
        }
    }
}
