package org.schabi.newpipe.extractor.services.odysee;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.AUDIO;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.COMMENTS;
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
import org.schabi.newpipe.extractor.services.odysee.extractors.OdyseeChannelExtractor;
import org.schabi.newpipe.extractor.services.odysee.extractors.OdyseeChannelTabExtractor;
import org.schabi.newpipe.extractor.services.odysee.extractors.OdyseeCommentsExtractor;
import org.schabi.newpipe.extractor.services.odysee.extractors.OdyseeKioskExtractor;
import org.schabi.newpipe.extractor.services.odysee.extractors.OdyseePlaylistExtractor;
import org.schabi.newpipe.extractor.services.odysee.extractors.OdyseeSearchExtractor;
import org.schabi.newpipe.extractor.services.odysee.extractors.OdyseeStreamExtractor;
import org.schabi.newpipe.extractor.services.odysee.extractors.OdyseeSuggestionExtractor;
import org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseeChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseeChannelTabLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseeCommentsLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseeKioskLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseePlaylistLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseeStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.odysee.settings.OdyseeSettings;
import org.schabi.newpipe.extractor.settings.ServiceSettings;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.util.EnumSet;

public class OdyseeService extends StreamingService {

    public OdyseeService(final int id) {
        super(id, "Odysee", EnumSet.of(VIDEO, AUDIO, LIVE, COMMENTS));
    }

    @Override
    public String getBaseUrl() {
        return OdyseeUrl.BASE_URL;
    }

    @Override
    public LinkHandlerFactory getStreamLHFactory() {
        return OdyseeStreamLinkHandlerFactory.getInstance();
    }

    @Override
    public ListLinkHandlerFactory getChannelLHFactory() {
        return OdyseeChannelLinkHandlerFactory.getInstance();
    }

    @Override
    public ListLinkHandlerFactory getChannelTabLHFactory() {
        return OdyseeChannelTabLinkHandlerFactory.getInstance();
    }

    @Override
    public ListLinkHandlerFactory getPlaylistLHFactory() {
        return OdyseePlaylistLinkHandlerFactory.getInstance();
    }

    @Override
    public SearchQueryHandlerFactory getSearchQHFactory() {
        return OdyseeSearchQueryHandlerFactory.getInstance();
    }

    @Override
    public ListLinkHandlerFactory getCommentsLHFactory() {
        return OdyseeCommentsLinkHandlerFactory.getInstance();
    }

    @Override
    public SearchExtractor getSearchExtractor(final SearchQueryHandler queryHandler) {
        return new OdyseeSearchExtractor(this, queryHandler);
    }

    @Override
    public SuggestionExtractor getSuggestionExtractor() {
        return new OdyseeSuggestionExtractor(this);
    }

    @Override
    public SubscriptionExtractor getSubscriptionExtractor() {
        return null;
    }

    @Override
    public KioskList getKioskList() throws ExtractionException {
        final KioskList.KioskExtractorFactory factory = (streamingService, url, kioskId) ->
                new OdyseeKioskExtractor(OdyseeService.this,
                        OdyseeKioskLinkHandlerFactory.getInstance().fromId(kioskId), kioskId);
        final KioskList list = new KioskList(this);
        try {
            for (final String kioskId : new String[]{OdyseeKioskLinkHandlerFactory.FEATURED,
                    OdyseeKioskLinkHandlerFactory.TRENDING, OdyseeKioskLinkHandlerFactory.LIVE}) {
                list.addKioskEntry(factory, OdyseeKioskLinkHandlerFactory.getInstance(), kioskId);
            }
            list.setDefaultKiosk(OdyseeKioskLinkHandlerFactory.FEATURED);
        } catch (final Exception e) {
            throw new ExtractionException(e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler linkHandler) {
        return new OdyseeChannelExtractor(this, linkHandler);
    }

    @Override
    public ChannelTabExtractor getChannelTabExtractor(final ListLinkHandler linkHandler) {
        return new OdyseeChannelTabExtractor(this, linkHandler);
    }

    @Override
    public PlaylistExtractor getPlaylistExtractor(final ListLinkHandler linkHandler) {
        return new OdyseePlaylistExtractor(this, linkHandler);
    }

    @Override
    public StreamExtractor getStreamExtractor(final LinkHandler linkHandler) {
        return new OdyseeStreamExtractor(this, linkHandler);
    }

    @Override
    public CommentsExtractor getCommentsExtractor(final ListLinkHandler linkHandler) {
        return new OdyseeCommentsExtractor(this, linkHandler);
    }

    @Override
    public ServiceSettings getServiceSettings() {
        return OdyseeSettings.getInstance();
    }
}
