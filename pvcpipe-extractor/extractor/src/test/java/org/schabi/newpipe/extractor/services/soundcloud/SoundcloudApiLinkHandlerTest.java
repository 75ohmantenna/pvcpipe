/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.extractor.services.soundcloud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudCommentsLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudStreamLinkHandlerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.SoundCloud;

/** Real factory/extractor paths with synthetic responses and no network transport. */
@Tag("offline")
class SoundcloudApiLinkHandlerTest {
    private static final String CLIENT_ID = "0123456789abcdef0123456789abcdef";
    private static final String PUBLIC = "https://soundcloud.com/fixture-artist/fixture-track";
    private static final String API = "https://api-v2.soundcloud.com/tracks/123/";
    private static final String SHORT = "https://on.soundcloud.com/Test123";
    private static final String MOBILE = "https://m.soundcloud.com/discover";
    private static final String WIDGET = "https://api-widget.soundcloud.com/resolve?url="
            + "https%3A%2F%2Fsoundcloud.com%2Ffixture-artist%2Ffixture-track"
            + "&format=json&client_id=" + CLIENT_ID;
    private static final String EMBED = "https://w.soundcloud.com/player/?url="
            + "https%3A%2F%2Fapi.soundcloud.com%2Ftracks%2F123";
    private static final String COMMENTS = "https://api-v2.soundcloud.com/tracks/123/comments"
            + "?client_id=" + CLIENT_ID + "&threaded=0&filter_replies=1";

    private final SoundcloudStreamLinkHandlerFactory streams =
            SoundcloudStreamLinkHandlerFactory.getInstance();
    private final SoundcloudCommentsLinkHandlerFactory comments =
            SoundcloudCommentsLinkHandlerFactory.getInstance();
    private final Map<Field, Object> previousState = new LinkedHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private IOException widgetFailure;

    @BeforeEach
    void setup() throws Exception {
        save(NewPipe.class, "downloader");
        save(NewPipe.class, "preferredLocalization");
        save(NewPipe.class, "preferredContentCountry");
        save(SoundcloudParsingHelper.class, "clientId").set(null, null);
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) throws IOException {
                requests.add(request.url());
                final String body;
                if (SHORT.equals(request.url())) {
                    assertEquals("HEAD", request.httpMethod());
                    return new Response(200, "", Map.of(), "", PUBLIC + "?si=fixture");
                }
                assertEquals("GET", request.httpMethod());
                switch (request.url()) {
                    case MOBILE:
                        body = "<script id='__NEXT_DATA__' type='application/json'>"
                                + "{\"runtimeConfig\":{\"clientId\":\"" + CLIENT_ID + "\"}}"
                                + "</script>";
                        break;
                    case WIDGET:
                        if (widgetFailure != null) {
                            throw widgetFailure;
                        }
                        body = "{\"id\":123}";
                        break;
                    case EMBED:
                        body = "<link rel='canonical' href='" + PUBLIC + "'>";
                        break;
                    case COMMENTS:
                        body = "{\"collection\":[],\"next_href\":null}";
                        break;
                    default:
                        throw new AssertionError("Unexpected request: " + request.url());
                }
                return new Response(200, "", Map.of(), body, request.url());
            }
        });
    }

    @AfterEach
    void restoreState() throws Exception {
        for (final Map.Entry<Field, Object> entry : previousState.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
    }

    @ParameterizedTest
    @CsvSource({"tracks,123", "albums,456", "sets,abc_123-z", "reposts,789",
            "followers,42", "following,99"})
    void existingApiKindsReturnTheirIdentifierThroughBothFactories(final String kind,
                                                                  final String id)
            throws Exception {
        final String url = "https://api-v2.soundcloud.com/" + kind + "/" + id + "/";
        assertEquals(id, streams.getId(url));
        assertEquals(id, comments.getId(url));
        assertTrue(requests.isEmpty());
    }

    @Test
    void httpAndExistingPathSuffixKeepTheSameIdentifier() throws Exception {
        final String url = "http://api-v2.soundcloud.com/tracks/123/comments?limit=5";
        assertEquals("123", streams.getId(url));
        assertEquals("123", comments.getId(url));
        assertTrue(requests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tracks", "albums", "sets", "reposts", "followers", "following"})
    void existingApiAcceptanceDiffersBetweenStreamAndCommentsFactories(final String kind)
            throws Exception {
        final String url = "https://api-v2.soundcloud.com/" + kind + "/123/";
        assertFalse(streams.acceptUrl(url));
        assertThrows(ParsingException.class, () -> streams.fromUrl(url));
        assertTrue(comments.acceptUrl(url));
        assertTrue(requests.isEmpty());
    }

    @Test
    void commentsHandlerUsesTheTrackIdentifierInItsCanonicalPath() throws Exception {
        final var handler = comments.fromUrl(API);
        assertEquals("123", handler.getId());
        assertEquals(API, handler.getOriginalUrl());
        assertEquals(COMMENTS, handler.getUrl());
        assertEquals(List.of(MOBILE), requests);
    }

    @Test
    void serviceCommentsExtractorFetchesTheCorrectTrackPath() throws Exception {
        final var extractor = SoundCloud.getCommentsExtractor(API);
        assertEquals("123", extractor.getId());
        assertEquals(COMMENTS, extractor.getUrl());
        extractor.fetchPage();
        final var page = extractor.getInitialPage();
        assertTrue(page.getItems().isEmpty());
        assertTrue(page.getErrors().isEmpty());
        assertFalse(page.hasNextPage());
        assertEquals(List.of(MOBILE, COMMENTS), requests);
    }

    @Test
    void ordinaryStreamLinksKeepWidgetAndEmbedResolution() throws Exception {
        assertTrue(streams.acceptUrl(PUBLIC));
        assertTrue(requests.isEmpty());
        final var handler = streams.fromUrl(PUBLIC);
        assertEquals("123", handler.getId());
        assertEquals(PUBLIC, handler.getOriginalUrl());
        assertEquals(PUBLIC, handler.getUrl());
        assertEquals(List.of(MOBILE, WIDGET, EMBED), requests);
    }

    @Test
    void ordinaryCommentsLinksKeepWidgetResolution() throws Exception {
        final var handler = comments.fromUrl(PUBLIC);
        assertEquals("123", handler.getId());
        assertEquals(COMMENTS, handler.getUrl());
        assertEquals(List.of(MOBILE, WIDGET, WIDGET), requests);
    }

    @Test
    void shortLinksKeepRedirectWidgetAndEmbedResolution() throws Exception {
        final var handler = streams.fromUrl(SHORT);
        assertEquals("123", handler.getId());
        assertEquals(SHORT, handler.getOriginalUrl());
        assertEquals(PUBLIC, handler.getUrl());
        assertEquals(List.of(SHORT, MOBILE, WIDGET, EMBED), requests);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api-v2.soundcloud.com/users/123/",
            "https://api-v2.soundcloud.com/tracks//",
            "https://api-v2.soundcloud.com/tracks/123",
            "ftp://api-v2.soundcloud.com/tracks/123/",
            "https://api-v2.soundcloud.com.evil.invalid/tracks/123/",
            "https://soundcloud.com/fixture-artist/tracks"
    })
    void rejectedUrlsStillFailWithoutRequests(final String url) throws Exception {
        assertThrows(ParsingException.class, () -> streams.getId(url));
        assertFalse(streams.acceptUrl(url));
        assertFalse(comments.acceptUrl(url));
        assertThrows(ParsingException.class, () -> streams.fromUrl(url));
        assertThrows(ParsingException.class, () -> comments.fromUrl(url));
        assertTrue(requests.isEmpty());
    }

    @Test
    void widgetFailureKeepsTheExistingExceptionAndCommentsAcceptanceBehavior() throws Exception {
        widgetFailure = new IOException("controlled widget failure");
        assertTrue(streams.acceptUrl(PUBLIC));
        final ParsingException error = assertThrows(ParsingException.class,
                () -> streams.getId(PUBLIC));
        assertSame(widgetFailure, error.getCause());
        assertFalse(comments.acceptUrl(PUBLIC));
        assertEquals(List.of(MOBILE, WIDGET, WIDGET), requests);
    }

    private Field save(final Class<?> owner, final String name) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        previousState.put(field, field.get(null));
        return field;
    }
}
