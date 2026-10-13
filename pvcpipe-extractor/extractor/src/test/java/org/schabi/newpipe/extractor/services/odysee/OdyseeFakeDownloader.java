package org.schabi.newpipe.extractor.services.odysee;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

/**
 * Answers the requests of the Odysee extractors from canned responses and remembers what was
 * asked. A request that no route covers fails the test.
 */
final class OdyseeFakeDownloader extends Downloader {

    final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, Supplier<IOException>> failures = new LinkedHashMap<>();

    /**
     * @param urlPart a part of the address that selects the request
     * @param handler what to answer
     */
    OdyseeFakeDownloader on(final String urlPart, final Function<Request, Response> handler) {
        routes.add(new Route(urlPart, handler));
        return this;
    }

    /** Makes requests to the address fail the way a broken or abandoned connection does. */
    OdyseeFakeDownloader onFailure(final String urlPart, final Supplier<IOException> failure) {
        failures.put(urlPart, failure);
        return this;
    }

    OdyseeFakeDownloader on(final String urlPart, final String body) {
        return on(urlPart, request -> ok(request, body));
    }

    /** Answers a call of the claim API with a result. */
    OdyseeFakeDownloader onProxy(final String method, final Function<Request, String> result) {
        return on(OdyseeApi.PROXY_URL + "?m=" + method, request -> ok(request,
                "{\"jsonrpc\":\"2.0\",\"result\":" + result.apply(request) + ",\"id\":1}"));
    }

    OdyseeFakeDownloader onProxy(final String method, final String result) {
        return onProxy(method, request -> result);
    }

    /** Answers a call of the claim API with an error message. */
    OdyseeFakeDownloader onProxyError(final String method, final String message) {
        return on(OdyseeApi.PROXY_URL + "?m=" + method, request -> ok(request,
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"" + message
                        + "\"},\"id\":1}"));
    }

    /** Answers a call of the comment service with a result. */
    OdyseeFakeDownloader onComments(final String method, final Function<Request, String> result) {
        return on(OdyseeApi.COMMENTS_URL + "?m=" + method, request -> ok(request,
                "{\"jsonrpc\":\"2.0\",\"result\":" + result.apply(request) + ",\"id\":1}"));
    }

    static Response ok(final Request request, final String body) {
        return new Response(200, "OK", Map.of(), body, request.url());
    }

    static Response status(final Request request, final int code) {
        return new Response(code, "", Map.of(), "", request.url());
    }

    static Response redirectedTo(final String url) {
        return new Response(200, "OK", Map.of(), "", url);
    }

    /** @return the parameters of a JSON-RPC request */
    static JsonObject params(final Request request) {
        try {
            return JsonParser.object().from(
                    new String(request.dataToSend(), StandardCharsets.UTF_8)).getObject("params");
        } catch (final JsonParserException e) {
            throw new IllegalStateException("Request body is not JSON", e);
        }
    }

    /** @return the requests whose address contains the given part */
    List<Request> requestsTo(final String urlPart) {
        final List<Request> matching = new ArrayList<>();
        for (final Request request : new ArrayList<>(requests)) {
            if (request.url().contains(urlPart)) {
                matching.add(request);
            }
        }
        return matching;
    }

    @Override
    public Response execute(@Nonnull final Request request) throws IOException {
        requests.add(request);
        for (final Map.Entry<String, Supplier<IOException>> failure : failures.entrySet()) {
            if (request.url().contains(failure.getKey())) {
                throw failure.getValue().get();
            }
        }
        for (final Route route : routes) {
            if (request.url().contains(route.urlPart)) {
                return route.handler.apply(request);
            }
        }
        throw new IOException("Unexpected request: " + request.httpMethod() + " "
                + request.url());
    }

    private static final class Route {
        private final String urlPart;
        private final Function<Request, Response> handler;

        Route(final String urlPart, final Function<Request, Response> handler) {
            this.urlPart = urlPart;
            this.handler = handler;
        }
    }
}
