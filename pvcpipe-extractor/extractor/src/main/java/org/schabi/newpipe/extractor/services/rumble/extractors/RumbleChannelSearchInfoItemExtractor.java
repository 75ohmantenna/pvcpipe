package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.rumble.RumbleChannelParsingHelper;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.utils.Utils;

import java.util.List;

import javax.annotation.Nonnull;

import static org.schabi.newpipe.extractor.ListExtractor.ITEM_COUNT_UNKNOWN;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

class RumbleChannelSearchInfoItemExtractor implements ChannelInfoItemExtractor {

    private long subscriberCount;
    private String description = "";
    private String name;
    private String url;
    private String thumbUrl;
    private boolean verified;

    RumbleChannelSearchInfoItemExtractor(final Element element, final Document doc)
            throws ParsingException {
        extractData(element, doc);
    }

    private void extractData(final Element element, final Document doc) throws ParsingException {
        final Element data = element.selectFirst("div[class*=media-subscribe-and-notify]");
        if (data == null) {
            final Element link = element.selectFirst(
                    "a[href*='/c/']:has(h3), a[href*='/user/']:has(h3)");
            if (link == null) {
                throw new ParsingException("Channel link not found");
            }
            this.url = RumbleChannelLinkHandlerFactory.getInstance()
                    .fromUrl(link.absUrl("href")).getUrl();
            final Element title = link.selectFirst("h3").clone();
            title.select("svg").remove();
            this.name = title.text();
        } else {
            this.name = data.attr("data-title");
            this.url = Rumble.getBaseUrl() + "/"
                    + RumbleChannelParsingHelper.getChannelIdAlreadySelected(data);
        }
        this.description = RumbleParsingHelper.extractSafely(false, "",
                () -> element.selectFirst("p[class*='text-sm text-fjord']").text());
        if (this.description == null) {
            this.description = "";
        }
        this.subscriberCount = extractSubscriberCount(element, doc);

        this.thumbUrl = extractTheThumbnailOfAChannelInASearchForChannels(element, doc);

        this.verified = !element.select("svg[class*=\"verification-badge-icon\"]").isEmpty();
    }

    private long extractSubscriberCount(final Element element, final Document document)
            throws ParsingException {

        final String errorMsg = "Could not get subscriber count";
        final String amountOfSubscribers = RumbleParsingHelper.extractSafely(false,
                errorMsg,
                () -> element.selectFirst(
                        ".channel-item--subscribers, span[class*='text-sm text-fjord']")
                        .text());

        if (null != amountOfSubscribers) {
            try {
                return Utils.mixedNumberWordToLong(amountOfSubscribers.replace(",", ""));
            } catch (final NumberFormatException e) {
                throw new ParsingException(errorMsg, e);
            }
        } else {
            return ITEM_COUNT_UNKNOWN;
        }
    }

    // extract the thumbnail of a channel in a channel search
    private String extractTheThumbnailOfAChannelInASearchForChannels(final Element element,
                                                                     final Document document)
            throws ParsingException {
        if (element.selectFirst("i.user-image") == null) {
            return null;
        }
        return RumbleParsingHelper.extractThumbnail(document, element.toString(),
                () -> {
                    final String thumbUrlIdentifier = "i." + element
                            .select("i.user-image")
                            .attr("class")
                            .split(" ")[2];
                    return thumbUrlIdentifier;
                });
    }

    @Override
    public String getDescription() throws ParsingException {
        return description;
    }

    @Override
    public long getSubscriberCount() throws ParsingException {
        return subscriberCount;
    }

    @Override
    public long getStreamCount() throws ParsingException {
        return -1;
    }

    @Override
    public boolean isVerified() throws ParsingException {
        return verified;
    }

    @Override
    public String getName() throws ParsingException {
        return name;
    }

    @Override
    public String getUrl() throws ParsingException {
        return url;
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() throws ParsingException {
        return thumbUrl == null || thumbUrl.isEmpty() ? List.of() : List.of(new Image(thumbUrl,
                Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN));
    }
}
