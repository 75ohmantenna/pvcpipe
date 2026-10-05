package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.Map;

import okhttp3.Interceptor;
import okhttp3.Request;

public class PvcHostInterceptorTest {
    @Test
    public void replacingHostPreservesTheRestOfTheRequest() throws IOException {
        final Request request = new Request.Builder()
                .url("https://original.example:8443/path%20name?q=a%2Bb#fragment")
                .header("X-Test", "value").head().build();
        final Interceptor.Chain chain = chain(request);
        final PvcHostInterceptor interceptor =
                new PvcHostInterceptor(Map.of("original.example", "replacement.example"));

        interceptor.intercept(chain);

        final ArgumentCaptor<Request> forwarded = ArgumentCaptor.forClass(Request.class);
        verify(chain).proceed(forwarded.capture());
        assertEquals("https://replacement.example:8443/path%20name?q=a%2Bb#fragment",
                forwarded.getValue().url().toString());
        assertEquals("HEAD", forwarded.getValue().method());
        assertEquals("value", forwarded.getValue().header("X-Test"));
    }

    @Test
    public void unconfiguredHostsKeepTheOriginalRequest() throws IOException {
        final Request request = new Request.Builder().url("https://other.example/").build();
        final Interceptor.Chain chain = chain(request);
        new PvcHostInterceptor(Map.of("original.example", "replacement.example")).intercept(chain);
        verify(chain).proceed(request);
    }

    @Test
    public void subsequentRequestsUseUpdatedHostSettings() throws IOException {
        final Request request = new Request.Builder().url("https://original.example/").build();
        final PvcHostInterceptor interceptor =
                new PvcHostInterceptor(Map.of("original.example", "old.example"));
        interceptor.setHosts(Map.of("original.example", "new.example"));
        final Interceptor.Chain updatedChain = chain(request);
        interceptor.intercept(updatedChain);
        final ArgumentCaptor<Request> forwarded = ArgumentCaptor.forClass(Request.class);
        verify(updatedChain).proceed(forwarded.capture());
        assertEquals("new.example", forwarded.getValue().url().host());

        interceptor.setHosts(Map.of());
        final Interceptor.Chain clearedChain = chain(request);
        interceptor.intercept(clearedChain);
        verify(clearedChain).proceed(request);
    }

    private static Interceptor.Chain chain(final Request request) {
        final Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        return chain;
    }
}
