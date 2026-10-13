package org.schabi.newpipe.extractor.services.odysee;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import java.util.Arrays;
import java.util.List;

/**
 * Claims shaped like the ones the Odysee API returns, with only the fields the extractors read.
 */
final class OdyseeFixtures {

    static final String CHANNEL_ID = "80d2590ad04e36fb1d077a9b9e3a8bba76defdf8";
    static final String VIDEO_ID = "ee58d95e7e42ec9479e13b28d57556f9ffb373dc";
    static final String VIDEO_URL = "https://odysee.com/@Example:8/a-video:e";
    static final String VIDEO_URI = "lbry://@Example#8/a-video#e";
    static final String FILE_URL = "https://player.odycdn.com/v6/streams/" + VIDEO_ID
            + "/786fca.mp4";
    static final String PLAYLIST_URL = "https://player.odycdn.com/v6/streams/" + VIDEO_ID
            + "/786fca06af11/master.m3u8";
    static final String HLS_PLAYLIST = "#EXTM3U\n#EXT-X-VERSION:6\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=4026000,RESOLUTION=1920x1080,"
            + "CODECS=\"avc1.4d402a,mp4a.40.2\",CLOSED-CAPTIONS=NONE\nv0.m3u8\n\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=655600,RESOLUTION=640x360,"
            + "CODECS=\"avc1.4d401f,mp4a.40.2\",CLOSED-CAPTIONS=NONE\nv2.m3u8\n";

    private OdyseeFixtures() {
    }

    static JsonObject channel() {
        return channel("@Example", CHANNEL_ID, "Example Channel");
    }

    static JsonObject channel(final String name, final String claimId, final String title) {
        final JsonObject value = new JsonObject();
        value.put("title", title);
        value.put("description", "About " + title);
        value.put("thumbnail", url("https://thumbs.example/avatar.webp"));
        value.put("cover", url("https://thumbs.example/cover.webp"));
        value.put("tags", array("odysee", "c:disable-slimes-all"));
        final JsonObject meta = new JsonObject();
        meta.put("claims_in_channel", 137);
        final JsonObject claim = new JsonObject();
        claim.put("claim_id", claimId);
        claim.put("name", name);
        claim.put("value_type", "channel");
        claim.put("canonical_url", "lbry://" + name + "#" + claimId.charAt(0));
        claim.put("permanent_url", "lbry://" + name + "#" + claimId);
        claim.put("value", value);
        claim.put("meta", meta);
        return claim;
    }

    static JsonObject video() {
        return video("a-video", VIDEO_ID, "A video");
    }

    /** A video of 413 seconds whose uploaded file is 1080p and 361921226 bytes. */
    static JsonObject video(final String name, final String claimId, final String title) {
        final JsonObject video = new JsonObject();
        video.put("duration", 413);
        video.put("height", 1080);
        video.put("width", 1920);
        final JsonObject source = new JsonObject();
        source.put("media_type", "video/mp4");
        source.put("name", name + ".mp4");
        source.put("size", "361921226");
        final JsonObject value = new JsonObject();
        value.put("title", title);
        value.put("description", "First line\nSecond line");
        value.put("thumbnail", url("https://thumbs.example/" + name + ".webp"));
        value.put("release_time", "1780000000");
        value.put("stream_type", "video");
        value.put("license", "None");
        value.put("languages", array("en"));
        value.put("tags", array("news", "c:disable-download"));
        value.put("video", video);
        value.put("source", source);
        final JsonObject claim = new JsonObject();
        claim.put("claim_id", claimId);
        claim.put("name", name);
        claim.put("value_type", "stream");
        claim.put("canonical_url", "lbry://@Example#8/" + name + "#" + claimId.charAt(0));
        claim.put("permanent_url", "lbry://" + name + "#" + claimId);
        claim.put("timestamp", 1780000100);
        claim.put("value", value);
        claim.put("signing_channel", channel());
        return claim;
    }

    static JsonObject audio() {
        final JsonObject claim = video("a-podcast", "e7d93d772bd87e2b62d5ab993c1c3ced86ebb396",
                "A podcast");
        final JsonObject value = claim.getObject("value");
        value.put("stream_type", "audio");
        value.remove("video");
        final JsonObject audio = new JsonObject();
        audio.put("duration", 2570);
        value.put("audio", audio);
        value.getObject("source").put("media_type", "audio/mpeg");
        value.getObject("source").put("name", "a-podcast.mp3");
        value.getObject("source").put("size", "53970000");
        return claim;
    }

    /** A livestream page: a stream claim that has no file. */
    static JsonObject livestream() {
        final JsonObject claim = video("a-livestream", "d826937ad9bf3b7991eada5034c4612389583bc1",
                "A livestream");
        final JsonObject value = claim.getObject("value");
        value.remove("source");
        value.remove("video");
        value.remove("stream_type");
        return claim;
    }

    static JsonObject document() {
        final JsonObject claim = video("a-document", "0000000000000000000000000000000000000001",
                "A document");
        claim.getObject("value").put("stream_type", "document");
        claim.getObject("value").remove("video");
        return claim;
    }

    static JsonObject playlist(final String... claimIds) {
        final JsonObject value = new JsonObject();
        value.put("title", "A playlist");
        value.put("description", "Things to watch");
        value.put("claims", array(claimIds));
        final JsonObject claim = new JsonObject();
        claim.put("claim_id", "ffef782f27486f0ac138bde8777f72ebdd0548c2");
        claim.put("name", "a-playlist");
        claim.put("value_type", "collection");
        claim.put("canonical_url", "lbry://@Example#8/a-playlist#f");
        claim.put("value", value);
        claim.put("signing_channel", channel());
        return claim;
    }

    static JsonObject repostOf(final JsonObject reposted) {
        final JsonObject claim = new JsonObject();
        claim.put("claim_id", "1111111111111111111111111111111111111111");
        claim.put("name", "a-repost");
        claim.put("value_type", "repost");
        claim.put("canonical_url", "lbry://@Other#1/a-repost#1");
        claim.put("reposted_claim", reposted);
        return claim;
    }

    static JsonObject withTags(final JsonObject claim, final String... tags) {
        claim.getObject("value").put("tags", array(tags));
        return claim;
    }

    /** @return the answer to a resolve of one address */
    static String resolved(final String lbryUri, final JsonObject claim) {
        final JsonObject result = new JsonObject();
        result.put(lbryUri, claim);
        return JsonWriter.string(result);
    }

    /** @return the answer to a claim search */
    static String items(final List<JsonObject> claims) {
        final JsonObject result = new JsonObject();
        final JsonArray items = new JsonArray();
        items.addAll(claims);
        result.put("items", items);
        return JsonWriter.string(result);
    }

    static String items(final JsonObject... claims) {
        return items(Arrays.asList(claims));
    }

    static JsonArray array(final String... values) {
        final JsonArray array = new JsonArray();
        array.addAll(Arrays.asList(values));
        return array;
    }

    private static JsonObject url(final String url) {
        final JsonObject object = new JsonObject();
        object.put("url", url);
        return object;
    }
}
