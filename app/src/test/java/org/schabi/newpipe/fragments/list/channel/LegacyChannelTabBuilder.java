package org.schabi.newpipe.fragments.list.channel;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelTabPlaylistExtractor;

/** Stable synthetic builder used by the pre-change serialization fixture. */
public final class LegacyChannelTabBuilder
        implements ReadyChannelTabListLinkHandler.ChannelTabExtractorBuilder {
    private static final long serialVersionUID = 1L;
    private final byte[] payload;

    public LegacyChannelTabBuilder(final int size) {
        payload = new byte[size];
    }

    public int payloadSize() {
        return payload.length;
    }

    @Override
    public ChannelTabExtractor build(final StreamingService service,
                                     final ListLinkHandler handler) {
        return new YoutubeChannelTabPlaylistExtractor(service, handler);
    }
}
