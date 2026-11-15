package org.schabi.newpipe.extractor.linkhandler;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;

/**
 * A {@link ListLinkHandler} which can be used to be returned from {@link
 * org.schabi.newpipe.extractor.channel.ChannelInfo#getTabs() ChannelInfo#getTabs()} when a
 * specific tab's data has already been fetched.
 *
 * <p>
 * This class allows passing a builder for a {@link ChannelTabExtractor} that can hold references
 * to variables.
 * </p>
 *
 * <p>
 * Note: a service that wishes to use this class in one of its {@link
 * org.schabi.newpipe.extractor.channel.ChannelExtractor ChannelExtractor}s must also add the
 * following snippet of code in the service's
 * {@link StreamingService#getChannelTabExtractor(ListLinkHandler)}:
 * <pre>
 * if (linkHandler instanceof ReadyChannelTabListLinkHandler) {
 *     return ((ReadyChannelTabListLinkHandler) linkHandler).getChannelTabExtractor(this);
 * }
 * </pre>
 */
public class ReadyChannelTabListLinkHandler extends ListLinkHandler {
    // Preserve compatibility with handlers saved before restoration fallbacks were introduced.
    private static final long serialVersionUID = -4117307732798877956L;

    private final ListLinkHandler restorationHandler;

    public interface ChannelTabExtractorBuilder extends Serializable {
        @Nonnull
        ChannelTabExtractor build(@Nonnull StreamingService service,
                                  @Nonnull ListLinkHandler linkHandler);
    }

    private final ChannelTabExtractorBuilder extractorBuilder;

    public ReadyChannelTabListLinkHandler(
            final String url,
            final String channelId,
            @Nonnull final FilterItem channelTab,
            @Nonnull final ChannelTabExtractorBuilder extractorBuilder) {
        this(url, channelId, channelTab, extractorBuilder, null);
    }

    /**
     * Supplies a lightweight handler with equivalent extraction behavior for state restoration.
     * The original builder is still used while its prefetched data is available.
     */
    public ReadyChannelTabListLinkHandler(
            final String url,
            final String channelId,
            @Nonnull final FilterItem channelTab,
            @Nonnull final ChannelTabExtractorBuilder extractorBuilder,
            final ListLinkHandler restorationHandler) {
        super(url, url, channelId, List.of(channelTab), List.of());
        this.restorationHandler = restorationHandler;
        this.extractorBuilder = extractorBuilder;
    }

    /**
     * Unknown and legacy builders retain their strategy rather than being blindly downgraded.
     * Such handlers are not guaranteed to have a small serialized representation.
     */
    @Nonnull
    public ListLinkHandler getRestorationHandler() {
        return restorationHandler == null ? this : restorationHandler;
    }

    @Nonnull
    public ChannelTabExtractor getChannelTabExtractor(@Nonnull final StreamingService service) {
        return extractorBuilder.build(service, new ListLinkHandler(this));
    }
}
