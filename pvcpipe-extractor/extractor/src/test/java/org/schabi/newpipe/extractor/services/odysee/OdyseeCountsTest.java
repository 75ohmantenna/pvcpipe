package org.schabi.newpipe.extractor.services.odysee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Odysee;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFakeDownloader.ok;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFakeDownloader.status;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.CHANNEL_ID;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.FILE_URL;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.VIDEO_ID;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.VIDEO_URI;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.VIDEO_URL;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.items;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.resolved;

import com.grack.nanojson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.odysee.settings.OdyseeSettings;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import javax.annotation.Nullable;

@Tag("offline")
class OdyseeCountsTest {

    private static final String REGISTER_URL = OdyseeApi.REGISTER_URL;
    private static final String OTHER_ID = "e51671357333fe22ae88aad320bde2f6f96b1410";

    private OdyseeFakeDownloader downloader;
    private final List<String> savedTokens = new ArrayList<>();
    private int registrations;

    @BeforeEach
    void setUp() {
        downloader = new OdyseeFakeDownloader();
        NewPipe.init(downloader);
        OdyseeApi.clearCaches();
        OdyseeAuth.setTokenStore(null);
        OdyseeSettings.getInstance().setting(true, OdyseeSettings.SHOW_COUNTS);
        // each registration hands out the next token: token-1, token-2, ...
        downloader.on(REGISTER_URL, request -> ok(request, "{\"success\":true,\"error\":null,"
                + "\"data\":{\"id\":1,\"auth_token\":\"token-" + ++registrations + "\"}}"));
    }

    @AfterEach
    void tearDown() {
        OdyseeSettings.getInstance().setting(false, OdyseeSettings.SHOW_COUNTS);
        OdyseeAuth.setTokenStore(null);
    }

    private static String body(final Request request) {
        return new String(request.dataToSend(), StandardCharsets.UTF_8);
    }

    private static String success(final String data) {
        return "{\"success\":true,\"error\":null,\"data\":" + data + "}";
    }

    private void storeTokensIn(@Nullable final String initial) {
        savedTokens.clear();
        OdyseeAuth.setTokenStore(new OdyseeAuth.TokenStore() {
            private String token = initial;

            @Override
            public String load() {
                return token;
            }

            @Override
            public void save(final String newToken) {
                token = newToken;
                savedTokens.add(String.valueOf(newToken));
            }
        });
    }

    @Test
    void registersOneVisitorAndUsesItsTokenForEveryCount() {
        storeTokensIn(null);
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[8281,356]"))
                .on(OdyseeApi.FOLLOWER_COUNT_URL, success("[2234605]"));

        assertEquals(Map.of(VIDEO_ID, 8281L, OTHER_ID, 356L),
                OdyseeApi.viewCounts(List.of(VIDEO_ID, OTHER_ID)));
        assertEquals(Map.of(CHANNEL_ID, 2234605L), OdyseeApi.followerCounts(List.of(CHANNEL_ID)));

        assertEquals(1, downloader.requestsTo(REGISTER_URL).size());
        final Request registration = downloader.requestsTo(REGISTER_URL).get(0);
        assertEquals("POST", registration.httpMethod());
        assertTrue(body(registration).matches("auth_token=&app_id=[A-Za-z0-9]{66}&language=en"),
                body(registration));
        assertEquals(List.of("token-1"), savedTokens);

        final Request views = downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).get(0);
        assertEquals("POST", views.httpMethod());
        // the token travels in the body, where addresses that get logged do not show it
        assertEquals(OdyseeApi.VIEW_COUNT_URL, views.url());
        assertEquals("auth_token=token-1&claim_id=" + VIDEO_ID + "," + OTHER_ID, body(views));
        assertEquals("auth_token=token-1&claim_id=" + CHANNEL_ID,
                body(downloader.requestsTo(OdyseeApi.FOLLOWER_COUNT_URL).get(0)));
    }

    @Test
    void everyRegistrationUsesAnApplicationIdOfItsOwn() {
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[1]"));
        OdyseeApi.viewCounts(List.of(VIDEO_ID));
        OdyseeAuth.setTokenStore(null);
        OdyseeApi.viewCounts(List.of(VIDEO_ID));

        final List<Request> registered = downloader.requestsTo(REGISTER_URL);
        assertEquals(2, registered.size());
        assertFalse(body(registered.get(0)).equals(body(registered.get(1))));
    }

    @Test
    void tokenKeptFromAnEarlierRunIsUsedWithoutRegistering() {
        storeTokensIn("kept");
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[5]"));

        assertEquals(Map.of(VIDEO_ID, 5L), OdyseeApi.viewCounts(List.of(VIDEO_ID)));

        assertTrue(downloader.requestsTo(REGISTER_URL).isEmpty());
        assertTrue(savedTokens.isEmpty());
        assertEquals("auth_token=kept&claim_id=" + VIDEO_ID,
                body(downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).get(0)));
    }

    @Test
    void tokenThatOdyseeStoppedAcceptingIsReplacedOnce() {
        storeTokensIn("expired");
        downloader.on(OdyseeApi.VIEW_COUNT_URL, request -> body(request).contains("expired")
                ? new org.schabi.newpipe.extractor.downloader.Response(403, "", Map.of(),
                "{\"success\":false,\"error\":\"could not authenticate user\",\"data\":null}",
                request.url())
                : ok(request, success("[7]")));

        assertEquals(Map.of(VIDEO_ID, 7L), OdyseeApi.viewCounts(List.of(VIDEO_ID)));

        assertEquals(1, downloader.requestsTo(REGISTER_URL).size());
        assertEquals(List.of("null", "token-1"), savedTokens);
        assertEquals(2, downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).size());
    }

    @Test
    void refusalOfAFreshTokenDoesNotRegisterVisitorAfterVisitor() {
        storeTokensIn(null);
        downloader.on(OdyseeApi.VIEW_COUNT_URL, request ->
                new org.schabi.newpipe.extractor.downloader.Response(401, "", Map.of(),
                        "{\"success\":false,\"error\":\"authentication required\",\"data\":null}",
                        request.url()));

        for (int i = 0; i < 5; i++) {
            assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
        }

        assertEquals(1, downloader.requestsTo(REGISTER_URL).size());
        assertEquals(1, downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).size());
        // the refused token is not kept
        assertEquals(List.of("token-1", "null"), savedTokens);
    }

    @Test
    void errorMessageAboutAuthenticationCountsAsARefusalWhateverTheStatus() {
        storeTokensIn("expired");
        downloader.on(OdyseeApi.VIEW_COUNT_URL, request -> body(request).contains("expired")
                ? ok(request, "{\"success\":false,\"error\":\"authentication required\","
                + "\"data\":null}") : ok(request, success("[7]")));

        assertEquals(Map.of(VIDEO_ID, 7L), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
    }

    @Test
    void failedRegistrationIsNotTriedAgainRightAway() {
        final OdyseeFakeDownloader failing = new OdyseeFakeDownloader()
                .on(REGISTER_URL, request -> status(request, 500));
        NewPipe.init(failing);

        for (int i = 0; i < 5; i++) {
            assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
        }

        assertEquals(1, failing.requests.size());
    }

    @Test
    void registrationWithoutATokenInItsAnswerLeavesTheCountsOut() {
        final OdyseeFakeDownloader odd = new OdyseeFakeDownloader()
                .on(REGISTER_URL, success("{\"id\":1,\"auth_token\":\"\"}"));
        NewPipe.init(odd);

        assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
        assertEquals(1, odd.requests.size());
    }

    @Test
    void tokenStoreThatFailsDoesNotBreakTheCounts() {
        OdyseeAuth.setTokenStore(new OdyseeAuth.TokenStore() {
            @Override
            public String load() {
                throw new IllegalStateException("storage is gone");
            }

            @Override
            public void save(final String token) {
                throw new IllegalStateException("storage is gone");
            }
        });
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[5]"));

        assertEquals(Map.of(VIDEO_ID, 5L), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
        assertEquals(Map.of(VIDEO_ID, 5L), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
        // the token is still shared for as long as the program runs
        assertEquals(1, downloader.requestsTo(REGISTER_URL).size());
    }

    @Test
    void refusalThatDoesNotComeFromOdyseeLeavesTheTokenAlone() {
        storeTokensIn("kept");
        // a firewall in front of Odysee answers 403 with a page of its own
        downloader.on(OdyseeApi.VIEW_COUNT_URL, request ->
                new org.schabi.newpipe.extractor.downloader.Response(403, "", Map.of(),
                        "<html><title>Forbidden</title></html>", request.url()));

        for (int i = 0; i < 3; i++) {
            assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
        }

        assertTrue(downloader.requestsTo(REGISTER_URL).isEmpty());
        assertTrue(savedTokens.isEmpty());
        // and after one request that got no answer the next pages do not each try again
        assertEquals(1, downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).size());
        assertEquals(Map.of(), OdyseeApi.followerCounts(List.of(CHANNEL_ID)));
        assertTrue(downloader.requestsTo(OdyseeApi.FOLLOWER_COUNT_URL).isEmpty());
    }

    @Test
    void countRequestThatTheCallerAbandonedDoesNotStopTheNextOne() {
        storeTokensIn("kept");
        final OdyseeFakeDownloader abandoned = new OdyseeFakeDownloader().onFailure(
                OdyseeApi.VIEW_COUNT_URL, () -> new java.io.InterruptedIOException("interrupted"));
        NewPipe.init(abandoned);
        assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));

        NewPipe.init(downloader);
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[5]"));
        assertEquals(Map.of(VIDEO_ID, 5L), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
    }

    @Test
    void registrationThatTheCallerAbandonedIsTriedAgainByTheNextOne() {
        final OdyseeFakeDownloader abandoned = new OdyseeFakeDownloader()
                .onFailure(REGISTER_URL, () -> new java.io.InterruptedIOException("interrupted"));
        NewPipe.init(abandoned);
        assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));

        NewPipe.init(downloader);
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[5]"));
        assertEquals(Map.of(VIDEO_ID, 5L), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
    }

    @Test
    void registrationThatTimedOutIsNotTriedAgainRightAway() {
        final OdyseeFakeDownloader slow = new OdyseeFakeDownloader()
                .onFailure(REGISTER_URL, () -> new java.net.SocketTimeoutException("timeout"));
        NewPipe.init(slow);
        assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));

        NewPipe.init(downloader);
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[5]"));
        assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
        assertTrue(downloader.requests.isEmpty());
    }

    @Test
    void tokenThatArrivesAfterTheCountsWereSwitchedOffIsNotKept() {
        storeTokensIn(null);
        final OdyseeFakeDownloader late = new OdyseeFakeDownloader()
                .on(REGISTER_URL, request -> {
                    // the user switches the counts off while Odysee is answering
                    OdyseeSettings.getInstance().setting(false, OdyseeSettings.SHOW_COUNTS);
                    OdyseeAuth.forget();
                    return ok(request, success("{\"auth_token\":\"late\"}"));
                });
        NewPipe.init(late);

        assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));

        assertFalse(savedTokens.contains("late"), savedTokens.toString());
        assertEquals(1, late.requests.size());
        // and none is used when the counts are switched on again: a new one is registered
        OdyseeSettings.getInstance().setting(true, OdyseeSettings.SHOW_COUNTS);
        NewPipe.init(downloader);
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[5]"));
        OdyseeApi.viewCounts(List.of(VIDEO_ID));
        assertEquals("auth_token=token-1&claim_id=" + VIDEO_ID,
                body(downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).get(0)));
    }

    @Test
    void switchingOffForgetsTheTokenThatWasKept() {
        storeTokensIn("kept");
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[5]"));
        OdyseeApi.viewCounts(List.of(VIDEO_ID));

        OdyseeSettings.getInstance().setting(false, OdyseeSettings.SHOW_COUNTS);
        OdyseeAuth.forget();

        assertEquals(List.of("null"), savedTokens);
        assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
        assertEquals(1, downloader.requests.size());
    }

    @Test
    void settingsAreNotHeldUpByARegistrationThatHangs() throws Exception {
        final CountDownLatch registering = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final OdyseeFakeDownloader hanging = new OdyseeFakeDownloader()
                .on(REGISTER_URL, request -> {
                    registering.countDown();
                    try {
                        release.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ok(request, success("{\"auth_token\":\"slow\"}"));
                });
        NewPipe.init(hanging);
        final Thread loader = new Thread(() -> OdyseeApi.viewCounts(List.of(VIDEO_ID)));
        loader.start();
        assertTrue(registering.await(5, java.util.concurrent.TimeUnit.SECONDS));

        // what the settings screen does on the main thread
        final Thread settings = new Thread(() -> {
            OdyseeAuth.forget();
            OdyseeAuth.setTokenStore(null);
        });
        settings.start();
        settings.join(5000);
        final boolean heldUp = settings.isAlive();
        release.countDown();
        loader.join(5000);
        settings.join(5000);

        assertFalse(heldUp);
    }

    @Test
    void nothingIsAskedOrRegisteredWhileTheCountsAreSwitchedOff() {
        OdyseeSettings.getInstance().setting(false, OdyseeSettings.SHOW_COUNTS);

        assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID)));
        assertEquals(Map.of(), OdyseeApi.followerCounts(List.of(CHANNEL_ID)));

        assertTrue(downloader.requests.isEmpty());
    }

    @Test
    void answersThatDoNotMatchTheQuestionAreIgnored() {
        for (final String data : new String[]{"[1]", "[1,2,3]", "{\"a\":1}", "null", "\"x\""}) {
            downloader = new OdyseeFakeDownloader()
                    .on(REGISTER_URL, success("{\"auth_token\":\"t\"}"))
                    .on(OdyseeApi.VIEW_COUNT_URL, success(data));
            NewPipe.init(downloader);
            assertEquals(Map.of(), OdyseeApi.viewCounts(List.of(VIDEO_ID, OTHER_ID)), data);
        }
        // a count that is not a number, or is below zero, is left out on its own
        downloader = new OdyseeFakeDownloader()
                .on(OdyseeApi.VIEW_COUNT_URL, success("[\"many\",-4,9]"));
        NewPipe.init(downloader);
        assertEquals(Map.of(CHANNEL_ID, 9L),
                OdyseeApi.viewCounts(List.of(VIDEO_ID, OTHER_ID, CHANNEL_ID)));
    }

    @Test
    void onlyClaimIdsAreSentAndEachOnce() {
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[3,4]"));

        final List<String> asked = new ArrayList<>(List.of(VIDEO_ID, "", "not-an-id",
                VIDEO_ID, "abc&auth_token=x", OTHER_ID));
        asked.add(null);
        assertEquals(Map.of(VIDEO_ID, 3L, OTHER_ID, 4L), OdyseeApi.viewCounts(asked));
        assertEquals("auth_token=token-1&claim_id=" + VIDEO_ID + "," + OTHER_ID,
                body(downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).get(0)));

        // nothing to ask about: no request and no registration
        final int before = downloader.requests.size();
        assertEquals(Map.of(), OdyseeApi.viewCounts(List.of("nope")));
        assertEquals(before, downloader.requests.size());
    }

    @Test
    void callersAtTheSameTimeShareOneRegistration() throws Exception {
        downloader.on(OdyseeApi.VIEW_COUNT_URL, success("[1]"));
        final int threads = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    OdyseeApi.viewCounts(List.of(VIDEO_ID));
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        assertEquals(1, registrations);
    }

    @Test
    void videoPageShowsViewsAndTheFollowersOfItsChannel() throws Exception {
        downloader.onProxy("resolve", resolved(VIDEO_URI, OdyseeFixtures.video()))
                .onProxy("get", "{\"streaming_url\":\"" + FILE_URL + "\"}")
                .on(FILE_URL, request -> ok(request, ""))
                .on(OdyseeApi.REACTIONS_URL, request -> status(request, 500))
                .on(OdyseeApi.SEARCH_URL, "[{\"claimId\":\"" + OTHER_ID + "\",\"name\":\"r\"}]")
                .onProxy("claim_search", items(OdyseeFixtures.video("related", OTHER_ID, "R")))
                .on(OdyseeApi.VIEW_COUNT_URL, request -> ok(request, success(
                        body(request).endsWith(VIDEO_ID) ? "[8281]" : "[356]")))
                .on(OdyseeApi.FOLLOWER_COUNT_URL, success("[2234605]"));

        final StreamInfo info = StreamInfo.getInfo(Odysee, VIDEO_URL);

        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
        assertEquals(8281, info.getViewCount());
        assertEquals(2234605, info.getUploaderSubscriberCount());
        assertEquals(356, ((StreamInfoItem) info.getRelatedItems().get(0)).getViewCount());
        assertEquals(1, downloader.requestsTo(REGISTER_URL).size());
    }

    @Test
    void likesAndCountsOfAVideoPageAreAskedForAtTheSameTime() throws Exception {
        // each of the three answers only once all three have been asked
        final java.util.concurrent.CyclicBarrier together =
                new java.util.concurrent.CyclicBarrier(3);
        final java.util.function.Function<Request, Boolean> met = request -> {
            try {
                together.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return true;
            } catch (final Exception e) {
                return false;
            }
        };
        storeTokensIn("kept");
        downloader.onProxy("resolve", resolved(VIDEO_URI, OdyseeFixtures.video()))
                .onProxy("get", "{\"streaming_url\":\"" + FILE_URL + "\"}")
                .on(FILE_URL, request -> ok(request, ""))
                .on(OdyseeApi.SEARCH_URL, "[]")
                .on(OdyseeApi.REACTIONS_URL, request -> met.apply(request)
                        ? ok(request, success("{\"others_reactions\":{\"" + VIDEO_ID
                        + "\":{\"like\":213,\"dislike\":36}}}")) : status(request, 500))
                .on(OdyseeApi.VIEW_COUNT_URL, request -> met.apply(request)
                        ? ok(request, success("[8281]")) : status(request, 500))
                .on(OdyseeApi.FOLLOWER_COUNT_URL, request -> met.apply(request)
                        ? ok(request, success("[2234605]")) : status(request, 500));

        final StreamInfo info = StreamInfo.getInfo(Odysee, VIDEO_URL);

        assertEquals(213, info.getLikeCount());
        assertEquals(8281, info.getViewCount());
        assertEquals(2234605, info.getUploaderSubscriberCount());
    }

    @Test
    void videoPageOpensWithoutCountsWhenTheyCannotBeHad() throws Exception {
        final OdyseeFakeDownloader noCounts = new OdyseeFakeDownloader()
                .onProxy("resolve", resolved(VIDEO_URI, OdyseeFixtures.video()))
                .onProxy("get", "{\"streaming_url\":\"" + FILE_URL + "\"}")
                .on(FILE_URL, request -> ok(request, ""))
                .on(OdyseeApi.REACTIONS_URL, request -> status(request, 500))
                .on(OdyseeApi.SEARCH_URL, "[]")
                .on(REGISTER_URL, request -> status(request, 503));
        NewPipe.init(noCounts);

        final StreamInfo info = StreamInfo.getInfo(Odysee, VIDEO_URL);

        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
        assertEquals("A video", info.getName());
        assertEquals(-1, info.getViewCount());
        assertEquals(-1, info.getUploaderSubscriberCount());
        assertEquals(1, noCounts.requestsTo(REGISTER_URL).size());
    }

    @Test
    void livestreamShowsItsViewersWithoutAToken() throws Exception {
        OdyseeSettings.getInstance().setting(false, OdyseeSettings.SHOW_COUNTS);
        final JsonObject live = OdyseeFixtures.livestream();
        downloader.onProxy("resolve", resolved("lbry://@Example#8/a-livestream#d", live))
                .on(OdyseeApi.LIVESTREAM_URL, "{\"success\":true,\"error\":null,\"data\":"
                        + "{\"Live\":true,\"ViewerCount\":58,"
                        + "\"VideoURL\":\"https://cloud.odysee.live/content/x/master.m3u8\","
                        + "\"ActiveClaim\":{\"ClaimID\":\"" + live.getString("claim_id") + "\"}}}")
                .on(OdyseeApi.REACTIONS_URL, request -> status(request, 500))
                .on(OdyseeApi.SEARCH_URL, "[]");

        final StreamInfo info = StreamInfo.getInfo(Odysee,
                "https://odysee.com/@Example:8/a-livestream:d");

        assertEquals(58, info.getViewCount());
        assertTrue(downloader.requestsTo(OdyseeApi.ACCOUNT_API_URL + "user").isEmpty());
        assertTrue(downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).isEmpty());
    }

    @Test
    void channelPageAndItsVideosShowTheirCountsWithOneRequestEach() throws Exception {
        final List<JsonObject> videos = List.of(
                OdyseeFixtures.video("one", VIDEO_ID, "One"),
                OdyseeFixtures.video("two", OTHER_ID, "Two"),
                OdyseeFixtures.document());
        downloader.onProxy("resolve", resolved("lbry://@Example#8", OdyseeFixtures.channel()))
                .onProxy("claim_search", items(videos))
                .on(OdyseeApi.FOLLOWER_COUNT_URL, success("[2234605]"))
                .on(OdyseeApi.VIEW_COUNT_URL, success("[8281,356]"));

        final ChannelInfo channel = ChannelInfo.getInfo(Odysee, "https://odysee.com/@Example:8");
        final ChannelTabInfo tab = ChannelTabInfo.getInfo(Odysee, channel.getTabs().get(0));

        assertEquals(2234605, channel.getSubscriberCount());
        assertEquals(2, tab.getRelatedItems().size());
        assertEquals(8281, ((StreamInfoItem) tab.getRelatedItems().get(0)).getViewCount());
        assertEquals(356, ((StreamInfoItem) tab.getRelatedItems().get(1)).getViewCount());
        // the document is not listed, so nobody asks how often it was viewed
        assertEquals("auth_token=token-1&claim_id=" + VIDEO_ID + "," + OTHER_ID,
                body(downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).get(0)));
        assertEquals(1, downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).size());
        assertEquals(1, downloader.requestsTo(OdyseeApi.FOLLOWER_COUNT_URL).size());
    }

    @Test
    void videoInSearchResultsShowsItsViews() throws Exception {
        downloader.on(OdyseeApi.SEARCH_URL, "[{\"claimId\":\"" + VIDEO_ID + "\",\"name\":\"v\"}]")
                .onProxy("claim_search", items(OdyseeFixtures.video()))
                .on(OdyseeApi.VIEW_COUNT_URL, success("[8281]"));

        final SearchInfo search = SearchInfo.getInfo(Odysee,
                Odysee.getSearchQHFactory().fromQuery("x"));

        assertEquals(8281, ((StreamInfoItem) search.getRelatedItems().get(0)).getViewCount());
        // no channels among the results, so no follower counts are asked for
        assertTrue(downloader.requestsTo(OdyseeApi.FOLLOWER_COUNT_URL).isEmpty());
    }

    @Test
    void channelInSearchResultsShowsItsFollowers() throws Exception {
        downloader.on(OdyseeApi.SEARCH_URL, "[{\"claimId\":\"" + CHANNEL_ID + "\",\"name\":\"c\"}]")
                .onProxy("claim_search", items(OdyseeFixtures.channel()))
                .on(OdyseeApi.FOLLOWER_COUNT_URL, success("[2234605]"));

        final SearchInfo search = SearchInfo.getInfo(Odysee,
                Odysee.getSearchQHFactory().fromQuery("x"));

        final ChannelInfoItem channel = (ChannelInfoItem) search.getRelatedItems().get(0);
        assertEquals(2234605, channel.getSubscriberCount());
        // no videos among the results, so no view counts are asked for
        assertTrue(downloader.requestsTo(OdyseeApi.VIEW_COUNT_URL).isEmpty());
    }

    @Test
    void liveKioskShowsViewersAndAsksOdyseeForNoCounts() throws Exception {
        final JsonObject live = OdyseeFixtures.livestream();
        live.put("claim_id", VIDEO_ID);
        downloader.on(OdyseeApi.LIVESTREAM_URL + "/all", "{\"success\":true,\"error\":null,"
                        + "\"data\":[{\"Live\":true,\"ViewerCount\":58,"
                        + "\"ActiveClaim\":{\"ClaimID\":\"" + VIDEO_ID + "\"}}]}")
                .onProxy("claim_search", items(live));

        final KioskInfo kiosk = KioskInfo.getInfo(Odysee, "https://odysee.com/$/livenow");

        assertEquals(58, kiosk.getRelatedItems().get(0).getViewCount());
        assertTrue(downloader.requestsTo(OdyseeApi.ACCOUNT_API_URL).isEmpty());
    }
}
