package org.schabi.newpipe.pvc.feature.challenge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.util.Log;

import com.github.evermindzz.challengefloatsaway.ChallengeResult;
import com.github.evermindzz.challengefloatsaway.manager.ChallengeManagerInterface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class PvcCfChallenge403InterceptorTest {
    private final ChallengeManagerInterface manager = mock(ChallengeManagerInterface.class);
    private final PvcCfChallenge403Interceptor interceptor =
            new PvcCfChallenge403Interceptor(manager);
    private final Interceptor.Chain chain = mock(Interceptor.Chain.class);
    private final ResponseBody body = mock(ResponseBody.class);
    private MockedStatic<Log> logs;

    @Before
    public void setUp() {
        logs = mockStatic(Log.class);
    }

    @After
    public void tearDown() {
        logs.close();
    }

    @Test
    public void unrelatedAndLookalikeHostsNeverOpenTheChallengeWebView() throws Exception {
        for (final String host : new String[]{"notrumble.com", "rumble.com.example.org"}) {
            final Response response = prepareResponse("https://" + host + "/", 403);
            assertSame(response, interceptor.intercept(chain));
        }
        verifyNoInteractions(manager);
        verify(body, never()).close();
    }

    @Test
    public void successfulChallengeClosesOriginalBodyAndReturnsReplacement() throws Exception {
        for (final String host : new String[]{"rumble.com", "www.rumble.com", "wn0.rumble.com"}) {
            final String url = "https://" + host + "/";
            prepareResponse(url, 403);
            when(manager.fetchContentViaWebView(url, 30000))
                    .thenReturn(new ChallengeResult(true, "<html>content</html>", ""));
            try (Response replacement = interceptor.intercept(chain)) {
                assertEquals(200, replacement.code());
                assertEquals("<html>content</html>", replacement.body().string());
            }
        }
        verify(body, times(3)).close();
    }

    @Test
    public void failedOrEmptyChallengeKeepsOriginalResponseReadable() throws Exception {
        final String url = "https://rumble.com/";
        final Response response = prepareResponse(url, 403);
        when(manager.fetchContentViaWebView(url, 30000))
                .thenReturn(new ChallengeResult(false, null, ""),
                        new ChallengeResult(true, null, ""));
        assertSame(response, interceptor.intercept(chain));
        assertSame(response, interceptor.intercept(chain));
        verify(body, never()).close();
    }

    @Test
    public void challengeExceptionClosesOriginalBody() throws Exception {
        final String url = "https://rumble.com/";
        prepareResponse(url, 403);
        final IllegalStateException failure = new IllegalStateException("challenge failed");
        when(manager.fetchContentViaWebView(url, 30000)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> interceptor.intercept(chain)));
        verify(body).close();
    }

    @Test
    public void successfulHttpResponseBypassesChallengeAndKeepsBodyOpen() throws Exception {
        final Response response = prepareResponse("https://rumble.com/", 200);
        assertSame(response, interceptor.intercept(chain));
        verifyNoInteractions(manager);
        verify(body, never()).close();
    }

    @Test
    public void redirectedForbiddenResponseOutsideRumbleBypassesChallenge() throws Exception {
        final Request request = new Request.Builder().url("https://rumble.com/").build();
        when(chain.request()).thenReturn(request);
        final Response response = responseFor("https://example.org/", 403);
        when(chain.proceed(request)).thenReturn(response);
        assertSame(response, interceptor.intercept(chain));
        verifyNoInteractions(manager);
    }

    private Response prepareResponse(final String url, final int code) throws Exception {
        final Response response = responseFor(url, code);
        when(chain.request()).thenReturn(response.request());
        when(chain.proceed(response.request())).thenReturn(response);
        return response;
    }

    private Response responseFor(final String url, final int code) {
        return new Response.Builder()
                .request(new Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).build();
    }
}
