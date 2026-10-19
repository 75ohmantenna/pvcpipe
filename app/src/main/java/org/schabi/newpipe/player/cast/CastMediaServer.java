package org.schabi.newpipe.player.cast;

import android.net.Uri;

import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.player.datasource.YoutubeHttpDataSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Per-load capability URLs, not a general-purpose LAN forward proxy. */
final class CastMediaServer implements Closeable, CastManifest.Urls {
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ROUTES = 8192;
    private static final int MAX_HEADER_BYTES = 32768;
    private static final long HEADER_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final Pattern TEMPLATE = Pattern.compile("\\$(Number|Time)(%0[1-9][0-9]?d)?\\$");
    private static final List<String> RESPONSE_HEADERS = List.of("Content-Type", "Content-Length",
            "Content-Range", "Accept-Ranges", "ETag", "Last-Modified", "Cache-Control");
    private final ServerSocket server;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                final Thread thread = new Thread(runnable, "cast-media-request");
                thread.setDaemon(true);
                return thread;
            });
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final Set<Closeable> sources = ConcurrentHashMap.newKeySet();
    private final Map<String, Resource> routes = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<String, String> keys = new HashMap<>();
    private final Set<String> retainedManifests = new HashSet<>();
    private final OkHttpClient client = DownloaderImpl.getInstance().getClient().newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();
    private final String prefix = "/" + UUID.randomUUID() + "/";
    private final String origin;
    private volatile boolean closed;
    private boolean preparing = true;

    CastMediaServer(final InetAddress address) throws IOException {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw new IOException("A reachable LAN address is required for casting");
        }
        server = new ServerSocket(0, 16, address);
        final String host = address.getHostAddress();
        origin = "http://" + (address instanceof Inet6Address
                ? "[" + host.replace("%", "%25") + "]" : host) + ":" + server.getLocalPort();
        final Thread acceptor = new Thread(this::accept, "cast-media-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @Override
    public synchronized String register(final String url, final String mimeType)
            throws IOException {
        final String checked = CastManifest.absolute(url, "");
        return add(new Resource(checked, mimeType, null, -1));
    }

    synchronized String register(final String url, final String mimeType, final long length)
            throws IOException {
        return add(new Resource(CastManifest.absolute(url, ""), mimeType, null, length));
    }

    synchronized String content(final String text, final String mimeType) throws IOException {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("Cast manifest is too large");
        }
        return add(new Resource("", mimeType, bytes, -1));
    }

    /** Initial manifests must retain every capability they publish, including their own routes. */
    synchronized void finishPreparation() {
        preparing = false;
    }

    private String add(final Resource resource) throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new IOException("Cast source was closed");
        }
        if (resource.url.length() > 8192) {
            throw new IOException("Media URL is too long");
        }
        final Matcher matcher = TEMPLATE.matcher(resource.url);
        final StringBuilder suffix = new StringBuilder();
        while (matcher.find()) {
            suffix.append('/').append(matcher.group());
        }
        // Validate before mutating the bounded route table, including its LRU ordering.
        if (TEMPLATE.matcher(resource.url).replaceAll("").contains("$")) {
            throw new IOException("Unsupported DASH URL template");
        }
        final String key = resource.url + "\n" + resource.mimeType;
        String path = resource.content == null ? keys.get(key) : null;
        if (path == null) {
            if (routes.size() >= MAX_ROUTES) {
                if (preparing) {
                    throw new IOException("Cast preparation references too many media resources");
                }
                final var candidates = routes.entrySet().iterator();
                boolean evicted = false;
                while (candidates.hasNext()) {
                    final var candidate = candidates.next();
                    if (!retainedManifests.contains(candidate.getKey())) {
                        final Resource removed = candidate.getValue();
                        candidates.remove();
                        keys.remove(removed.url + "\n" + removed.mimeType);
                        evicted = true;
                        break;
                    }
                }
                if (!evicted) {
                    throw new IOException("Cast source has too many retained manifests");
                }
            }
            path = prefix + UUID.randomUUID();
            routes.put(path, resource);
            if (resource.content == null) {
                keys.put(key, path);
            }
            // The receiver can revisit these original entry points after hours of live segments.
            if (preparing && (resource.content != null || CastManifest.HLS.equals(resource.mimeType)
                    || CastManifest.DASH.equals(resource.mimeType))) {
                retainedManifests.add(path);
            }
        } else {
            final Resource existing = routes.get(path);
            if (resource.length > 0) {
                existing.length = resource.length;
            }
        }
        return origin + path + suffix;
    }

    String manifest(final String url, final String mimeType) throws Exception {
        final Resource resource = new Resource(CastManifest.absolute(url, ""), mimeType, null, -1);
        try (Upstream upstream = open(resource, false, null, null)) {
            if (upstream.status < 200 || upstream.status >= 300) {
                throw new HttpFailure(upstream.status, upstream.headers);
            }
            final String text = new String(readBounded(upstream.stream), StandardCharsets.UTF_8);
            return rewrite(text, upstream.url, mimeType);
        }
    }

    private String rewrite(final String text, final String base, final String mimeType)
            throws Exception {
        final int[] count = {0};
        final CastManifest.Urls bounded = (url, mime) -> {
            if (++count[0] > MAX_ROUTES / 2) {
                throw new IOException("Cast manifest references too many resources");
            }
            return register(url, mime);
        };
        return CastManifest.DASH.equals(mimeType) ? CastManifest.dash(text, base, bounded)
                : CastManifest.hls(text, base, bounded);
    }

    private void accept() {
        while (!closed) {
            try {
                final Socket socket = server.accept();
                final long headerDeadline = System.nanoTime() + HEADER_TIMEOUT_NANOS;
                sockets.add(socket);
                if (closed) {
                    socket.close();
                    sockets.remove(socket);
                    return;
                }
                try {
                    workers.execute(() -> serve(socket, headerDeadline));
                } catch (final java.util.concurrent.RejectedExecutionException error) {
                    sockets.remove(socket);
                    socket.close();
                }
            } catch (final IOException error) {
                if (!closed) {
                    close();
                }
            }
        }
    }

    private void serve(final Socket socket, final long headerDeadline) {
        try (socket) {
            final InputStream input = new HeaderInputStream(socket, headerDeadline);
            final ResponseOutput output = new ResponseOutput(socket.getOutputStream());
            try {
                final int[] remaining = {MAX_HEADER_BYTES};
                final String[] request = line(input, remaining).split(" ", -1);
                if (request.length != 3 || !request[2].startsWith("HTTP/1.")) {
                    send(output, 400, Map.of(), null, false);
                    return;
                }
                final boolean head = "HEAD".equals(request[0]);
                final Map<String, String> headers = new HashMap<>();
                String header = line(input, remaining);
                while (!header.isEmpty()) {
                    final int colon = header.indexOf(':');
                    if (colon <= 0) {
                        throw new IOException("Malformed HTTP header");
                    }
                    final String key = header.substring(0, colon).toLowerCase(Locale.ROOT);
                    if (headers.put(key, header.substring(colon + 1).trim()) != null) {
                        throw new IOException("Duplicate HTTP header");
                    }
                    header = line(input, remaining);
                }
                if (!(head || "GET".equals(request[0]) || "OPTIONS".equals(request[0]))) {
                    send(output, 405, Map.of("Allow", "GET, HEAD, OPTIONS"), null, head);
                    return;
                }
                final Resource resource = lookup(request[1]);
                if (resource == null) {
                    send(output, 404, Map.of(), null, head);
                } else if ("OPTIONS".equals(request[0])) {
                    send(output, 204, Map.of(), null, false);
                } else if (resource.content != null) {
                    serveContent(output, resource.content, resource.mimeType,
                            headers.get("range"), head);
                } else if (CastManifest.HLS.equals(resource.mimeType)
                        || CastManifest.DASH.equals(resource.mimeType)) {
                    final String text = manifest(resource.url, resource.mimeType);
                    serveContent(output, text.getBytes(StandardCharsets.UTF_8), resource.mimeType,
                            headers.get("range"), head);
                } else {
                    try (Upstream upstream = open(resource, head, headers.get("range"),
                            headers.get("if-range"))) {
                        // Some services use extensionless HLS resources.
                        final String type = upstream.headers.getOrDefault("Content-Type", "");
                        if (upstream.status == 200 && (type.contains("mpegurl")
                                || type.contains("dash+xml"))) {
                            final String mime = type.contains("dash+xml")
                                    ? CastManifest.DASH : CastManifest.HLS;
                            final String text = head ? manifest(upstream.url, mime)
                                    : rewrite(new String(readBounded(upstream.stream),
                                            StandardCharsets.UTF_8), upstream.url, mime);
                            serveContent(output, text.getBytes(StandardCharsets.UTF_8), mime,
                                    headers.get("range"), head);
                        } else {
                            send(output, upstream.status, upstream.headers, upstream.stream, head);
                        }
                    }
                }
            } catch (final HttpFailure error) {
                if (!output.started) {
                    // The upstream error body is intentionally not relayed, so its byte length
                    // must not frame this empty response (including a manifest HEAD failure).
                    final Map<String, String> headers = new HashMap<>(error.headers);
                    headers.put("Content-Length", "0");
                    send(output, error.status, headers, null, false);
                }
            } catch (final Exception error) {
                // Do not leak signed upstream URLs or exception text to the LAN.
                if (!output.started) {
                    send(output, 502, Map.of(), null, false);
                }
            }
        } catch (final IOException ignored) {
            // Receiver stopped reading, or resolver.close() closed the socket.
        } finally {
            sockets.remove(socket);
        }
    }

    private synchronized Resource lookup(final String target) {
        if (closed || !target.startsWith(prefix) || target.contains("?") || target.contains("#")) {
            return null;
        }
        final int split = target.indexOf('/', prefix.length());
        final String path = split < 0 ? target : target.substring(0, split);
        final Resource resource = routes.get(path);
        if (resource == null) {
            return null;
        }
        final String[] values = split < 0 ? new String[0]
                : target.substring(split + 1).split("/", -1);
        final Matcher matcher = TEMPLATE.matcher(resource.url);
        final StringBuffer url = new StringBuffer();
        int i = 0;
        while (matcher.find()) {
            if (i >= values.length || !values[i].matches("[0-9]{1,20}")) {
                return null;
            }
            matcher.appendReplacement(url, Matcher.quoteReplacement(values[i++]));
        }
        matcher.appendTail(url);
        if (i != values.length) {
            return null;
        }
        return new Resource(url.toString(), resource.mimeType, resource.content, resource.length);
    }

    private Upstream open(final Resource resource, final boolean head,
                          final String range, final String ifRange) throws IOException {
        if (closed) {
            throw new IOException("Cast source was closed");
        }
        final URI uri = URI.create(resource.url);
        final String host = uri.getHost();
        if (host != null && (host.equals("googlevideo.com") || host.endsWith(".googlevideo.com"))
                && uri.getPath() != null && uri.getPath().startsWith("/videoplayback")) {
            // Ignoring Range is permitted when the validator cannot be evaluated locally.
            return youtube(resource, ifRange == null ? range : null);
        }
        final Request.Builder request = new Request.Builder().url(resource.url)
                .header("User-Agent", DownloaderImpl.getMediaUserAgent(resource.url))
                .header("Accept-Encoding", "identity");
        final String referer = DownloaderImpl.getMediaReferer(resource.url);
        if (referer != null) {
            request.header("Referer", referer);
        }
        if (range != null) {
            request.header("Range", range);
        }
        if (ifRange != null) {
            request.header("If-Range", ifRange);
        }
        // GET probes work for Odysee sources whose HEAD response switches to an HLS manifest.
        if (head && !DownloaderImpl.isHeadUnusable(resource.url)) {
            request.head();
        }
        final Call call = client.newCall(request.build());
        calls.add(call);
        if (closed) {
            call.cancel();
        }
        try {
            final Response response = call.execute();
            final Map<String, String> headers = new HashMap<>();
            for (final String key : RESPONSE_HEADERS) {
                final String value = response.header(key);
                if (value != null) {
                    headers.put(key, value);
                }
            }
            return new Upstream(response.code(), headers,
                    response.body() == null ? new ByteArrayInputStream(new byte[0])
                            : response.body().byteStream(), response.request().url().toString(),
                    () -> {
                        response.close();
                        calls.remove(call);
                    });
        } catch (final IOException error) {
            calls.remove(call);
            throw error;
        }
    }

    private Upstream youtube(final Resource resource, final String range)
            throws IOException {
        final YoutubeHttpDataSource source = new YoutubeHttpDataSource.Factory()
                .setRangeParameterEnabled(false).setRnParameterEnabled(true).createDataSource();
        final Closeable closer = source::close;
        sources.add(closer);
        if (closed) {
            sources.remove(closer);
            throw new IOException("Cast source was closed");
        }
        try {
            long total = resource.length;
            if (total <= 0) {
                final String length = Uri.parse(resource.url).getQueryParameter("clen");
                if (length != null) {
                    total = Long.parseLong(length);
                }
            }
            if (range != null && total <= 0) {
                source.open(new DataSpec.Builder().setUri(resource.url).setLength(1).build());
                total = documentLength(source.getResponseHeaders());
                source.close();
            }
            final ByteRange parsed = range == null ? null : ByteRange.parse(range, total);
            final DataSpec.Builder spec = new DataSpec.Builder().setUri(resource.url);
            if (parsed != null) {
                spec.setPosition(parsed.start).setLength(parsed.end - parsed.start + 1);
            }
            final long length = source.open(spec.build());
            if (closed) {
                throw new IOException("Cast source was closed during its request");
            }
            final Map<String, String> headers = new HashMap<>();
            final Map<String, List<String>> upstreamHeaders = source.getResponseHeaders();
            for (final String key : RESPONSE_HEADERS) {
                final String value = firstHeader(upstreamHeaders, key);
                if (value != null) {
                    headers.put(key, value);
                }
            }
            int status = source.getResponseCode();
            if (status >= 400) {
                throw new HttpFailure(status, headers);
            }
            if (parsed != null) {
                status = 206;
                headers.put("Content-Range", "bytes " + parsed.start + "-" + parsed.end + "/"
                        + (total > 0 ? total : "*"));
                headers.put("Content-Length", Long.toString(parsed.end - parsed.start + 1));
            } else if (length >= 0) {
                headers.put("Content-Length", Long.toString(length));
            }
            headers.put("Accept-Ranges", "bytes");
            final InputStream stream = new InputStream() {
                private final byte[] single = new byte[1];

                @Override
                public int read() throws IOException {
                    return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
                }

                @Override
                public int read(final byte[] buffer, final int offset, final int count)
                        throws IOException {
                    return count == 0 ? 0 : source.read(buffer, offset, count);
                }
            };
            return new Upstream(status, headers, stream, resource.url, () -> {
                sources.remove(closer);
                source.close();
            });
        } catch (final HttpDataSource.InvalidResponseCodeException error) {
            sources.remove(closer);
            source.close();
            final Map<String, String> headers = new HashMap<>();
            final String contentRange = firstHeader(error.headerFields, "Content-Range");
            if (contentRange != null) {
                headers.put("Content-Range", contentRange);
            }
            throw new HttpFailure(error.responseCode, headers);
        } catch (final IOException | RuntimeException error) {
            sources.remove(closer);
            source.close();
            throw error;
        }
    }

    private static long documentLength(final Map<String, List<String>> headers) throws IOException {
        final String range = firstHeader(headers, "Content-Range");
        final String length = range != null && range.contains("/")
                ? range.substring(range.lastIndexOf('/') + 1)
                : firstHeader(headers, "Content-Length");
        try {
            return Long.parseLong(length == null ? "-1" : length);
        } catch (final NumberFormatException error) {
            throw new IOException("Unknown media length", error);
        }
    }

    private static String firstHeader(final Map<String, List<String>> headers, final String key) {
        for (final var entry : headers.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static void serveContent(final OutputStream output, final byte[] content,
                                     final String mime, final String range, final boolean head)
            throws IOException {
        final Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", mime);
        headers.put("Accept-Ranges", "bytes");
        final ByteRange parsed = range == null ? new ByteRange(0, content.length - 1)
                : ByteRange.parse(range, content.length);
        final int length = (int) (parsed.end - parsed.start + 1);
        headers.put("Content-Length", Integer.toString(length));
        if (range != null) {
            headers.put("Content-Range", "bytes " + parsed.start + "-" + parsed.end + "/"
                    + content.length);
        }
        send(output, range == null ? 200 : 206, headers, null, true);
        if (!head) {
            output.write(content, (int) parsed.start, length);
        }
    }

    private static void send(final OutputStream output, final int status,
                             final Map<String, String> headers, final InputStream body,
                             final boolean head) throws IOException {
        final StringBuilder text = new StringBuilder("HTTP/1.1 ").append(status).append(" ")
                .append(status >= 400 ? "Error" : "OK").append("\r\nConnection: close\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                .append("Access-Control-Allow-Headers: Range, If-Range\r\n")
                .append("Access-Control-Expose-Headers: ")
                .append("Content-Length, Content-Range, Accept-Ranges\r\n");
        for (final var header : headers.entrySet()) {
            if (!header.getValue().contains("\r") && !header.getValue().contains("\n")) {
                text.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        if (!headers.containsKey("Content-Length") && body == null && !head) {
            text.append("Content-Length: 0\r\n");
        }
        output.write(text.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        if (!head && body != null) {
            final byte[] buffer = new byte[32768];
            int count;
            while ((count = body.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private static String line(final InputStream input, final int[] remaining) throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (--remaining[0] >= 0) {
            final int value = input.read();
            if (value < 0) {
                throw new IOException("Incomplete request");
            }
            if (value == '\n') {
                return line.toString(StandardCharsets.US_ASCII.name()).stripTrailing();
            }
            line.write(value);
        }
        throw new IOException("HTTP headers too large");
    }

    private static byte[] readBounded(final InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_MANIFEST_BYTES) {
                throw new IOException("Manifest too large");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            routes.clear();
            keys.clear();
            retainedManifests.clear();
        }
        closeQuietly(server);
        workers.shutdownNow();
        sockets.forEach(CastMediaServer::closeQuietly);
        calls.forEach(Call::cancel);
        // Closing network sources may briefly block: cancellation must never block Android's UI.
        final List<Closeable> pending = new ArrayList<>(sources);
        if (!pending.isEmpty()) {
            final Thread cleanup = new Thread(() -> pending.forEach(CastMediaServer::closeQuietly),
                    "cast-media-close");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    private static void closeQuietly(final Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException ignored) {
            // Already closed, or cancelled during a network operation.
        }
    }

    record ByteRange(long start, long end) {
        static ByteRange parse(final String header, final long total) throws HttpFailure {
            try {
                if (!header.matches("bytes=[0-9]*-[0-9]*") || total <= 0) {
                    throw new IllegalArgumentException();
                }
                final String[] values = header.substring(6).split("-", -1);
                final long start;
                final long end;
                if (values[0].isEmpty()) {
                    final long suffix = Long.parseLong(values[1]);
                    if (suffix <= 0) {
                        throw new IllegalArgumentException();
                    }
                    start = Math.max(0, total - suffix);
                    end = total - 1;
                } else {
                    start = Long.parseLong(values[0]);
                    end = values[1].isEmpty() ? total - 1
                            : Math.min(total - 1, Long.parseLong(values[1]));
                }
                if (start < 0 || start >= total || end < start) {
                    throw new IllegalArgumentException();
                }
                return new ByteRange(start, end);
            } catch (final IllegalArgumentException error) {
                throw new HttpFailure(416, total > 0
                        ? Map.of("Content-Range", "bytes */" + total) : Map.of());
            }
        }
    }

    static final class HttpFailure extends IOException {
        final int status;
        final Map<String, String> headers;

        HttpFailure(final int status, final Map<String, String> headers) {
            super("HTTP " + status);
            this.status = status;
            this.headers = headers;
        }
    }

    /** One worker reads each request; response-body streaming has independent upstream timeouts. */
    private static final class HeaderInputStream extends BufferedInputStream {
        private final Socket socket;
        private final long deadline;

        private HeaderInputStream(final Socket socket, final long deadline) throws IOException {
            super(socket.getInputStream());
            this.socket = socket;
            this.deadline = deadline;
        }

        private void beforeRead() throws IOException {
            // Buffered bytes cannot stall a worker; update the timeout only for a socket read.
            if (pos >= count) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new SocketTimeoutException("Cast request headers timed out");
                }
                socket.setSoTimeout((int) (TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
            }
        }

        @Override
        public int read() throws IOException {
            beforeRead();
            return super.read();
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length)
                throws IOException {
            beforeRead();
            return super.read(buffer, offset, length);
        }
    }

    private static final class ResponseOutput extends OutputStream {
        private final OutputStream delegate;
        private boolean started;

        private ResponseOutput(final OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(final int value) throws IOException {
            started = true;
            delegate.write(value);
        }

        @Override
        public void write(final byte[] buffer, final int offset, final int length)
                throws IOException {
            started = true;
            delegate.write(buffer, offset, length);
        }
    }

    private static final class Resource {
        private final String url;
        private final String mimeType;
        private final byte[] content;
        private long length;

        private Resource(final String url, final String mimeType, final byte[] content,
                         final long length) {
            this.url = url;
            this.mimeType = mimeType;
            this.content = content;
            this.length = length;
        }
    }

    private record Upstream(int status, Map<String, String> headers, InputStream stream,
                            String url, Closeable cleanup) implements Closeable {
        @Override
        public void close() throws IOException {
            cleanup.close();
        }
    }
}
