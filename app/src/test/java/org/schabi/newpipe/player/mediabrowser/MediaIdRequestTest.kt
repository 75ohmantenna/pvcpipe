package org.schabi.newpipe.player.mediabrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.ServiceList

class MediaIdRequestTest {
    private val youtube = ServiceList.YouTube.serviceId
    private val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test
    fun idsThatTheMediaBrowserCreatesAreDecoded() {
        assertEquals(
            MediaIdRequest.LocalPlaylist(12, 3),
            MediaIdRequest.parse(listOf(ID_BOOKMARKS, ID_LOCAL, "12", "3"), null)
        )
        assertEquals(
            MediaIdRequest.RemotePlaylist(4, 0),
            MediaIdRequest.parse(listOf(ID_BOOKMARKS, ID_REMOTE, "4", "0"), null)
        )
        assertEquals(
            MediaIdRequest.PlaylistUrl(youtube, url),
            MediaIdRequest.parse(listOf(ID_BOOKMARKS, ID_URL, "$youtube"), url)
        )
        assertEquals(
            MediaIdRequest.History(7),
            MediaIdRequest.parse(listOf(ID_HISTORY, "7"), null)
        )
        assertEquals(
            MediaIdRequest.Item(InfoType.CHANNEL, youtube, url),
            MediaIdRequest.parse(listOf(ID_INFO_ITEM, ID_CHANNEL, "$youtube"), url)
        )
    }

    @Test
    fun unknownTypesAreRejected() {
        assertNull(MediaIdRequest.parse(listOf(ID_INFO_ITEM, "comment", "$youtube"), url))
        assertNull(MediaIdRequest.parse(listOf(ID_BOOKMARKS, "shared", "1", "0"), null))
        assertNull(MediaIdRequest.parse(listOf("search", "1"), null))
        assertNull(MediaIdRequest.parse(emptyList(), null))
    }

    @Test
    fun numbersThatOverflowOrAreNotNumbersAreRejected() {
        val tooLong = "9223372036854775808" // Long.MAX_VALUE + 1
        val tooBigInt = "2147483648" // Int.MAX_VALUE + 1
        assertNull(MediaIdRequest.parse(listOf(ID_BOOKMARKS, ID_LOCAL, tooLong, "0"), null))
        assertNull(MediaIdRequest.parse(listOf(ID_BOOKMARKS, ID_LOCAL, "1", tooBigInt), null))
        assertNull(MediaIdRequest.parse(listOf(ID_HISTORY, tooLong), null))
        assertNull(MediaIdRequest.parse(listOf(ID_HISTORY, "1e3"), null))
        assertNull(MediaIdRequest.parse(listOf(ID_INFO_ITEM, ID_STREAM, tooBigInt), url))
        assertNull(MediaIdRequest.parse(listOf(ID_BOOKMARKS, ID_URL, "youtube"), url))
    }

    @Test
    fun negativeIndicesUnknownServicesAndMissingUrlsAreRejected() {
        assertNull(MediaIdRequest.parse(listOf(ID_BOOKMARKS, ID_LOCAL, "1", "-1"), null))
        assertNull(MediaIdRequest.parse(listOf(ID_BOOKMARKS, ID_REMOTE, "-1", "0"), null))
        // -1 is NO_SERVICE_ID; 2 to 4 belonged to services that were removed
        assertNull(MediaIdRequest.parse(listOf(ID_INFO_ITEM, ID_STREAM, "-1"), url))
        assertNull(MediaIdRequest.parse(listOf(ID_INFO_ITEM, ID_STREAM, "3"), url))
        assertNull(MediaIdRequest.parse(listOf(ID_INFO_ITEM, ID_STREAM, "$youtube"), null))
        assertNull(MediaIdRequest.parse(listOf(ID_INFO_ITEM, ID_STREAM, "$youtube"), " "))
    }

    @Test
    fun wrongSegmentCountsAreRejected() {
        assertNull(MediaIdRequest.parse(listOf(ID_BOOKMARKS, ID_LOCAL, "1"), null))
        assertNull(MediaIdRequest.parse(listOf(ID_BOOKMARKS, ID_LOCAL, "1", "0", "0"), null))
        assertNull(MediaIdRequest.parse(listOf(ID_HISTORY), null))
        assertNull(MediaIdRequest.parse(listOf(ID_HISTORY, "1", "2"), null))
        assertNull(MediaIdRequest.parse(listOf(ID_INFO_ITEM, ID_STREAM), url))
    }
}
