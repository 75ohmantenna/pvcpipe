package org.schabi.newpipe.pvc.feature.savesearchpresets.data

import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import com.grack.nanojson.JsonWriter
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
    fun `malformed JSON still fails in the codec`() {
        assertThrows(JsonParserException::class.java) { EntryDatabaseJson.decode("invalid") }
    }

    @Test
    fun `unknown sort keys retain entries with default sorting`() {
        assertEquals(
            storedState.copy(sorting = SortType.NAME, direction = SortDirection.DESC),
            EntryDatabaseJson.decode(
                storedJson.replace("byLastUsed", "unknown").replace("asc", "unknown")
            )
        )
    }

    @Test
    fun `unsupported and nonintegral versions are rejected`() {
        for (version in listOf("2", "0", "1.5", "null", "\"1\"")) {
            assertThrows(IllegalArgumentException::class.java) {
                EntryDatabaseJson.decode(storedJson.replace("\"version\": 1", "\"version\": $version"))
            }
        }
    }

    @Test
    fun `invalid collection and record types are rejected`() {
        for (key in listOf("defaults", "entries")) {
            for (value in listOf(1, "bad", listOf(null), listOf("bad"))) {
                val root = JsonParser.`object`().from(storedJson)
                root[key] = value
                assertThrows(IllegalArgumentException::class.java) {
                    EntryDatabaseJson.decode(JsonWriter.string(root))
                }
            }
        }
    }

    @Test
    fun `missing and incorrectly typed entry fields are rejected`() {
        val keys = listOf(
            "name",
            "service",
            "created_at",
            "modified_at",
            "last_used",
            "sort_filter_data",
            "content_filter_data"
        )
        for (key in keys) {
            val root = JsonParser.`object`().from(storedJson)
            root.getArray("entries").getObject(0).remove(key)
            assertThrows(IllegalArgumentException::class.java) {
                EntryDatabaseJson.decode(JsonWriter.string(root))
            }
        }
        for (invalid in listOf("null", "1.5", "9223372036854775808", "\"123\"")) {
            assertThrows(IllegalArgumentException::class.java) {
                EntryDatabaseJson.decode(storedJson.replace("1774216721895", invalid))
            }
        }
    }

    @Test
    fun `filter identifiers must be exact signed integers`() {
        for (invalid in listOf("null", "1.5", "2147483648", "-2147483649", "\"12\"")) {
            assertThrows(IllegalArgumentException::class.java) {
                EntryDatabaseJson.decode(storedJson.replace("[12, 17, 12]", "[$invalid]"))
            }
        }
    }

    @Test
    fun `signed numeric limits round trip without precision loss`() {
        val entry = storedState.entries.single().copy(
            createdAt = Long.MIN_VALUE,
            modifiedAt = Long.MAX_VALUE,
            lastUsed = 0,
            sortFilterData = listOf(Int.MIN_VALUE, Int.MAX_VALUE, -1, 0)
        )
        val state = storedState.copy(entries = listOf(entry))
        assertEquals(state, EntryDatabaseJson.decode(EntryDatabaseJson.encode(state)))
    }
}
