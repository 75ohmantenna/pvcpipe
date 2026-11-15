package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.downloader.DownloaderFactory;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.schabi.newpipe.extractor.ServiceList.YouTube;

@Tag("offline")
class YoutubeChannelDescriptionTest {
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;

    @BeforeEach
    void useRecordedChannel() {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        NewPipe.init(DownloaderFactory.getMockDownloader(DownloaderFactory.getMockPath(
                YoutubeChannelExtractorTest.Kurzgesagt.class, null)));
        YoutubeTestsUtils.ensureStateless();
    }

    @AfterEach
    void restoreExtractorConfiguration() {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @Test
    void returnsCompleteRecordedMetadataDescription() throws Exception {
        final ChannelExtractor extractor = YouTube.getChannelExtractor(
                "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q");
        extractor.fetchPage();

        // This recording contains channelMetadataRenderer.description, including its final
        // paragraph. The old monthly-upload sentence is absent from the source itself.
        assertEquals("Animation videos explaining things with optimistic nihilism since 12,013.\n\n"
                + "We’re a team of illustrators, animators, number crunchers and one dog who aim "
                + "to spark curiosity about science and the world we live in. To us nothing is "
                + "boring if you tell a good story.\n\n"
                + "For updates on our videos and other news from the kurzgesagt universe follow "
                + "us on Twitter, Facebook, Instagram and LinkedIn.\n", extractor.getDescription());
    }
}
