package org.schabi.newpipe.extractor.services.odysee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Odysee;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFakeDownloader.params;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFakeDownloader.status;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.CHANNEL_ID;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.VIDEO_ID;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.VIDEO_URI;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.VIDEO_URL;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.items;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.resolved;

import com.grack.nanojson.JsonObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.stream.Description;

import java.time.Instant;
import java.util.List;

@Tag("offline")
class OdyseeCommentsExtractorTest {

    private static final String PINNED = "{\"comment\":\"Welcome :star:\",\"comment_id\":\"c1\","
            + "\"claim_id\":\"" + VIDEO_ID + "\",\"timestamp\":1780063253,"
            + "\"channel_id\":\"" + CHANNEL_ID + "\",\"channel_name\":\"@Example\","
            + "\"channel_url\":\"lbry://@Example#" + CHANNEL_ID + "\",\"replies\":5,"
            + "\"is_creator\":true,\"is_pinned\":true}";
    private static final String PLAIN = "{\"comment\":\"Nice\",\"comment_id\":\"c2\","
            + "\"claim_id\":\"" + VIDEO_ID + "\",\"timestamp\":1780104489,"
            + "\"channel_id\":\"viewer\",\"channel_name\":\"@Viewer\","
            + "\"channel_url\":\"lbry://@Viewer#viewer\",\"is_pinned\":false}";
    private static final String REACTIONS = "{\"others_reactions\":{"
            + "\"c1\":{\"like\":53,\"dislike\":28,\"creator_like\":0},"
            + "\"c2\":{\"like\":25,\"dislike\":9,\"creator_like\":1}}}";

    private OdyseeFakeDownloader downloader;

    @BeforeEach
    void setUp() {
        downloader = new OdyseeFakeDownloader();
        NewPipe.init(downloader);
        OdyseeApi.clearCaches();
        downloader.onProxy("resolve", resolved(VIDEO_URI, OdyseeFixtures.video()));
    }

    private static String page(final int totalPages, final String... comments) {
        return "{\"page\":1,\"page_size\":20,\"total_pages\":" + totalPages
                + ",\"total_items\":186,\"total_filtered_items\":66,\"items\":["
                + String.join(",", comments) + "]}";
    }

    @Test
    void commentsCarryLikesAuthorsAndReplyThreads() throws Exception {
        downloader.onComments("comment.List", request -> page(4, PINNED, PLAIN))
                .onComments("reaction.List", request -> REACTIONS)
                .onProxy("claim_search", items(OdyseeFixtures.channel()));

        final CommentsInfo comments = CommentsInfo.getInfo(Odysee, VIDEO_URL);

        assertTrue(comments.getErrors().isEmpty(), comments.getErrors().toString());
        assertEquals(186, comments.getCommentsCount());
        assertEquals(2, comments.getRelatedItems().size());

        final CommentsInfoItem pinned = comments.getRelatedItems().get(0);
        assertEquals("c1", pinned.getCommentId());
        assertEquals(new Description("Welcome :star:", Description.PLAIN_TEXT),
                pinned.getCommentText());
        assertEquals("Example Channel", pinned.getUploaderName());
        assertEquals("https://odysee.com/@Example:8", pinned.getUploaderUrl());
        assertEquals(2, pinned.getUploaderAvatars().size());
        assertEquals(Instant.ofEpochSecond(1780063253), pinned.getUploadDate().getInstant());
        assertEquals(53, pinned.getLikeCount());
        assertEquals("53", pinned.getTextualLikeCount());
        assertTrue(pinned.isPinned());
        assertTrue(pinned.isChannelOwner());
        assertFalse(pinned.isHeartedByUploader());
        assertEquals(5, pinned.getReplyCount());
        assertEquals("replies:c1:1", pinned.getReplies().getId());
        assertEquals(VIDEO_URL, pinned.getUrl());

        // the author of this one could not be looked up: its name and address come from
        // the comment itself
        final CommentsInfoItem plain = comments.getRelatedItems().get(1);
        assertEquals("@Viewer", plain.getUploaderName());
        assertEquals("https://odysee.com/@Viewer:viewer", plain.getUploaderUrl());
        assertTrue(plain.getUploaderAvatars().isEmpty());
        assertTrue(plain.isHeartedByUploader());
        assertFalse(plain.isChannelOwner());
        assertEquals(0, plain.getReplyCount());
        assertNull(plain.getReplies());

        final Request list = downloader.requestsTo("?m=comment.List").get(0);
        assertEquals(List.of("application/json"), list.headers().get("Content-Type"));
        final JsonObject listParams = params(list);
        assertEquals(VIDEO_ID, listParams.getString("claim_id"));
        assertTrue(listParams.getBoolean("top_level"));
        assertEquals(3, listParams.getInt("sort_by"));
        assertEquals(1, listParams.getInt("page"));
        assertEquals("c1,c2", params(downloader.requestsTo("?m=reaction.List").get(0))
                .getString("comment_ids"));
        assertEquals(List.of(CHANNEL_ID, "viewer"),
                params(downloader.requestsTo("?m=claim_search").get(0)).getArray("claim_ids"));
    }

    @Test
    void furtherPagesAndReplyThreadsAreAskedForByNumber() throws Exception {
        downloader.onComments("comment.List", request -> page(4, PLAIN))
                .onComments("reaction.List", request -> REACTIONS)
                .onProxy("claim_search", items());
        final CommentsInfo comments = CommentsInfo.getInfo(Odysee, VIDEO_URL);
        assertEquals("2", comments.getNextPage().getId());

        final InfoItemsPage<CommentsInfoItem> second =
                CommentsInfo.getMoreItems(Odysee, comments, comments.getNextPage());
        assertEquals("3", second.getNextPage().getId());
        final JsonObject secondParams = params(downloader.requestsTo("?m=comment.List").get(1));
        assertEquals(2, secondParams.getInt("page"));
        assertTrue(secondParams.getBoolean("top_level"));

        final InfoItemsPage<CommentsInfoItem> replies = CommentsInfo.getMoreItems(Odysee,
                comments, new org.schabi.newpipe.extractor.Page(VIDEO_URL, "replies:c1:4"));
        // page 4 of 4 is the last one
        assertFalse(replies.hasNextPage());
        final JsonObject replyParams = params(downloader.requestsTo("?m=comment.List").get(2));
        assertEquals("c1", replyParams.getString("parent_id"));
        assertEquals(4, replyParams.getInt("page"));
        assertEquals(1, replyParams.getInt("sort_by"));
        assertFalse(replyParams.has("top_level"));
        // the video was looked up once for all three requests
        assertEquals(1, downloader.requestsTo("?m=resolve").size());
    }

    @Test
    void commentsAreShownWithoutLikesAndAvatarsWhenThoseCannotBeLoaded() throws Exception {
        downloader.onComments("comment.List", request -> page(1, PINNED))
                .on(OdyseeApi.COMMENTS_URL + "?m=reaction.List", request -> status(request, 500))
                .on(OdyseeApi.PROXY_URL + "?m=claim_search", request -> status(request, 500));

        final CommentsInfo comments = CommentsInfo.getInfo(Odysee, VIDEO_URL);

        assertTrue(comments.getErrors().isEmpty(), comments.getErrors().toString());
        final CommentsInfoItem comment = comments.getRelatedItems().get(0);
        assertEquals("Welcome :star:", comment.getCommentText().getContent());
        assertEquals(CommentsInfoItem.NO_LIKE_COUNT, comment.getLikeCount());
        assertEquals("", comment.getTextualLikeCount());
        assertEquals("@Example", comment.getUploaderName());
        assertTrue(comment.getUploaderAvatars().isEmpty());
        assertFalse(comments.hasNextPage());
    }

    @Test
    void videoWithoutCommentsHasAnEmptyList() throws Exception {
        downloader.onComments("comment.List", request ->
                "{\"page\":1,\"page_size\":20,\"total_pages\":0,\"total_items\":0,\"items\":[]}");

        final CommentsInfo comments = CommentsInfo.getInfo(Odysee, VIDEO_URL);

        assertTrue(comments.getRelatedItems().isEmpty());
        assertEquals(0, comments.getCommentsCount());
        assertFalse(comments.hasNextPage());
        // nothing to ask likes or authors for
        assertEquals(2, downloader.requests.size());
    }
}
