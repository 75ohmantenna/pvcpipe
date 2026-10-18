package org.schabi.newpipe.player.cast;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Manifest-only transformations; no receiver-supplied URL is ever resolved here. */
final class CastManifest {
    static final String DASH = "application/dash+xml";
    static final String HLS = "application/vnd.apple.mpegurl";
    private static final Pattern HLS_URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final String[] URL_ATTRIBUTES =
            {"media", "initialization", "sourceURL", "index"};

    interface Urls {
        String register(String url, String mimeType) throws IOException;
    }

    private CastManifest() {
    }

    static String hls(final String text, final String base, final Urls urls) throws IOException {
        if (!text.stripLeading().startsWith("#EXTM3U")) {
            throw new IOException("Not an HLS playlist");
        }
        final StringBuilder result = new StringBuilder(text.length());
        boolean nextIsPlaylist = false;
        for (final String line : text.split("\\r?\\n")) {
            if (line.startsWith("#")) {
                final Matcher matcher = HLS_URI.matcher(line);
                final StringBuffer rewritten = new StringBuffer();
                final boolean playlist = line.startsWith("#EXT-X-MEDIA:")
                        || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                        || line.startsWith("#EXT-X-RENDITION-REPORT:");
                while (matcher.find()) {
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement("URI=\""
                            + urls.register(absolute(base, matcher.group(1)),
                            playlist ? HLS : "") + "\""));
                }
                matcher.appendTail(rewritten);
                result.append(rewritten);
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    nextIsPlaylist = true;
                }
            } else if (!line.isBlank()) {
                result.append(urls.register(absolute(base, line.trim()),
                        nextIsPlaylist ? HLS : ""));
                nextIsPlaylist = false;
            }
            result.append('\n');
        }
        return result.toString();
    }

    static String dash(final String text, final String base, final Urls urls) throws Exception {
        final Document document = parse(text);
        rewriteDash(document.getDocumentElement(), base, null, urls);
        return serialize(document);
    }

    // Merge extractor-generated single-period tracks, retaining timing/index metadata verbatim.
    static String mergeDash(final String video, final String audio) throws Exception {
        final Document document = parse(video);
        final Document audioDocument = parse(audio);
        final List<Element> periods = children(document.getDocumentElement(), "Period");
        final List<Element> audioPeriods = children(audioDocument.getDocumentElement(), "Period");
        if (periods.size() != 1 || audioPeriods.size() != 1) {
            throw new IOException("Separate DASH tracks require one matching period");
        }
        final Element period = periods.get(0);
        final Element audioPeriod = audioPeriods.get(0);
        if (!period.getAttribute("start").equals(audioPeriod.getAttribute("start"))) {
            throw new IOException("Separate DASH tracks have different period starts");
        }
        // DASH AdaptationSet IDs are integers; Android receivers parse them as such.
        int id = 0;
        for (final Element adaptation : children(period, "AdaptationSet")) {
            adaptation.setAttribute("id", Integer.toString(id++));
        }
        for (final Element adaptation : children(audioPeriod, "AdaptationSet")) {
            final Element imported = (Element) document.importNode(adaptation, true);
            imported.setAttribute("id", Integer.toString(id++));
            period.appendChild(imported);
        }
        return serialize(document);
    }

    private static void rewriteDash(final Element element, final String inheritedBase,
                                    final Element inheritedSegment, final Urls urls)
            throws Exception {
        String base = inheritedBase;
        final List<Element> bases = children(element, "BaseURL");
        if (bases.size() > 1) {
            throw new IOException("Multiple DASH BaseURL alternatives are unsupported");
        }
        if (!bases.isEmpty()) {
            base = absolute(base, bases.get(0).getTextContent().trim());
            element.removeChild(bases.get(0));
        }
        Element segment = inheritedSegment;
        for (final Element child : children(element, null)) {
            if (isSegment(child)) {
                if (segment != null && name(segment).equals(name(child))) {
                    final Element combined = (Element) segment.cloneNode(true);
                    for (int i = 0; i < child.getAttributes().getLength(); i++) {
                        final Node attribute = child.getAttributes().item(i);
                        combined.setAttribute(attribute.getNodeName(), attribute.getNodeValue());
                    }
                    final Set<String> replaced = new HashSet<>();
                    for (final Element own : children(child, null)) {
                        // A SegmentList may contain many SegmentURL children. Remove inherited
                        // children once per name, not the overrides appended earlier in this loop.
                        if (replaced.add(name(own))) {
                            for (final Element old : children(combined, name(own))) {
                                combined.removeChild(old);
                            }
                        }
                        combined.appendChild(own.cloneNode(true));
                    }
                    segment = combined;
                } else {
                    segment = child;
                }
            }
        }
        if ("Representation".equals(name(element))) {
            for (final Element child : children(element, null)) {
                if (isSegment(child)) {
                    element.removeChild(child);
                }
            }
            if (segment != null) {
                final Element copy = (Element) segment.cloneNode(true);
                rewriteSegment(copy, base, element, urls);
                element.appendChild(copy);
            }
            // SegmentBase and unsegmented representations fetch this exact resource, never a
            // client-selected path under a directory proxy.
            if (segment == null || "SegmentBase".equals(name(segment))) {
                final Element localBase = element.getOwnerDocument().createElementNS(
                        element.getNamespaceURI(), "BaseURL");
                localBase.setTextContent(urls.register(base, ""));
                element.insertBefore(localBase, element.getFirstChild());
            }
        } else {
            for (final Element child : children(element, null)) {
                if (!isSegment(child)) {
                    if ("Location".equals(name(child))) {
                        child.setTextContent(urls.register(
                                absolute(base, child.getTextContent().trim()), DASH));
                    } else if ("UTCTiming".equals(name(child))) {
                        if (child.getAttribute("schemeIdUri").contains("http")) {
                            child.setAttribute("value", urls.register(
                                    absolute(base, child.getAttribute("value")), ""));
                        }
                    } else {
                        rewriteDash(child, base, segment, urls);
                    }
                }
            }
            for (final Element child : children(element, null)) {
                if (isSegment(child)) {
                    element.removeChild(child);
                }
            }
        }
    }

    private static void rewriteSegment(final Element element, final String base,
                                       final Element representation, final Urls urls)
            throws IOException {
        for (final String attribute : URL_ATTRIBUTES) {
            if (element.hasAttribute(attribute)) {
                String value = element.getAttribute(attribute)
                        .replace("$RepresentationID$", representation.getAttribute("id"))
                        .replace("$Bandwidth$", representation.getAttribute("bandwidth"));
                value = absolute(base, value);
                element.setAttribute(attribute, urls.register(value, ""));
            }
        }
        for (final Element child : children(element, null)) {
            rewriteSegment(child, base, representation, urls);
        }
    }

    private static boolean isSegment(final Element element) {
        return switch (name(element)) {
            case "SegmentTemplate", "SegmentList", "SegmentBase" -> true;
            default -> false;
        };
    }

    static String absolute(final String base, final String value) throws IOException {
        try {
            final URI reference = URI.create(value);
            final URI uri;
            if (reference.isAbsolute()) {
                uri = reference;
            } else if (base != null && !base.isBlank()) {
                final URI parent = URI.create(base);
                if (reference.getRawAuthority() == null && reference.getRawPath().isEmpty()
                        && reference.getRawQuery() != null) {
                    // URI.resolve drops the base filename for query-only references, unlike
                    // RFC 3986 section 5.2.2. Keep the exact signed path and replace its query.
                    final String raw = parent.toString();
                    final int query = raw.indexOf('?');
                    final int fragment = raw.indexOf('#');
                    final int end = Math.min(query < 0 ? raw.length() : query,
                            fragment < 0 ? raw.length() : fragment);
                    uri = URI.create(raw.substring(0, end) + "?" + reference.getRawQuery());
                } else {
                    uri = value.isEmpty() ? parent : parent.resolve(reference);
                }
            } else {
                throw new IOException("Relative media resource has no manifest base URL");
            }
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getFragment() != null) {
                throw new IOException("Expected an HTTP(S) media URL");
            }
            return uri.toString();
        } catch (final IllegalArgumentException error) {
            throw new IOException("Invalid media URL", error);
        }
    }

    private static Document parse(final String xml) throws Exception {
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) {
            throw new IOException("External XML declarations are unsupported");
        }
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        final var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new IOException("External XML entities are unsupported");
        });
        final Document document = builder.parse(new InputSource(new StringReader(xml)));
        if (!"MPD".equals(name(document.getDocumentElement()))) {
            throw new IOException("Not a DASH manifest");
        }
        return document;
    }

    private static String serialize(final Document document) throws Exception {
        final var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        final StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private static String name(final Element element) {
        return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
    }

    private static List<Element> children(final Element parent, final String tag) {
        final List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && (tag == null || tag.equals(name(element)))) {
                result.add(element);
            }
        }
        return result;
    }
}
