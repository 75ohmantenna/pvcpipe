package org.schabi.newpipe.extractor.services.kick;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.LIVE;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickChannelExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickChannelTabExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickClipExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickKioskExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickSearchExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickStreamExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickSuggestionExtractor;
import org.schabi.newpipe.extractor.services.kick.settings.KickSettings;
import org.schabi.newpipe.extractor.settings.ServiceSettings;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.util.EnumSet;

public final class KickService extends StreamingService {
    public KickService(final int id) {
        super(id, "Kick", EnumSet.of(VIDEO, LIVE));
    }

    @Override
    public String getBaseUrl() {
        return KickUrl.BASE;
    }

    @Override
    public LinkHandlerFactory getStreamLHFactory() {
        return KickLinkHandlerFactory.STREAM;
    }

    @Override
    public ListLinkHandlerFactory getChannelLHFactory() {
        return KickLinkHandlerFactory.CHANNEL;
    }

    @Override
    public ListLinkHandlerFactory getChannelTabLHFactory() {
        return KickLinkHandlerFactory.TAB;
    }

    @Override
    public ListLinkHandlerFactory getPlaylistLHFactory() {
        return null;
    }

    @Override
    public SearchQueryHandlerFactory getSearchQHFactory() {
        return KickLinkHandlerFactory.SEARCH;
    }

    @Override
    public ListLinkHandlerFactory getCommentsLHFactory() {
        return null;
    }

    @Override
    public SearchExtractor getSearchExtractor(final SearchQueryHandler handler) {
        return new KickSearchExtractor(this, handler);
    }

    @Override
    public SuggestionExtractor getSuggestionExtractor() {
        return new KickSuggestionExtractor(this);
    }

    @Override
    public SubscriptionExtractor getSubscriptionExtractor() {
        // Remote following import is not implemented; local subscriptions use channel tabs.
        return null;
    }

    @Override
    public KioskList getKioskList() throws ExtractionException {
        final KioskList list = new KioskList(this);
        try {
            list.addKioskEntry((service, url, id) -> new KickKioskExtractor(this,
                    KickLinkHandlerFactory.KIOSK.fromId(id), id), KickLinkHandlerFactory.KIOSK,
                    KickLinkHandlerFactory.FEATURED);
            list.setDefaultKiosk(KickLinkHandlerFactory.FEATURED);
        } catch (final Exception e) {
            throw new ExtractionException(e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler handler) {
        return new KickChannelExtractor(this, handler);
    }

    @Override
    public ChannelTabExtractor getChannelTabExtractor(final ListLinkHandler handler) {
        return new KickChannelTabExtractor(this, handler);
    }

    @Override
    public PlaylistExtractor getPlaylistExtractor(final ListLinkHandler handler) {
        return null;
    }

    @Override
    public StreamExtractor getStreamExtractor(final LinkHandler handler) {
        if (KickUrl.isClip(handler.getId())) {
            return new KickClipExtractor(this, handler);
        }
        return new KickStreamExtractor(this, handler);
    }

    @Override
    public CommentsExtractor getCommentsExtractor(final ListLinkHandler handler) {
        return null;
    }

    @Override
    public ServiceSettings getServiceSettings() {
        return KickSettings.getInstance();
    }
}
