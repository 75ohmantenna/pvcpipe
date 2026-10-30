package us.shandian.giga.preprocessing;

import androidx.annotation.Nullable;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Logical HLS segments over the whole physical resources downloaded by a mission. */
public final class HlsDownloadPlan implements Serializable {
    private static final long serialVersionUID = 1L;

    private final List<Segment> segments;

    public HlsDownloadPlan(final List<Segment> segments) {
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        for (final Segment segment : this.segments) {
            Objects.requireNonNull(segment);
        }
    }

    public List<Segment> getSegments() {
        return segments;
    }

    /**
     * Migrates old checkpoints without deduplicating their already downloaded resources.
     * Resource i must retain its original URL, offset and worker checkpoint identity.
     *
     * @param legacy persisted segments in physical-resource order
     * @return a plan retaining one whole-object window per old resource
     * @throws IOException if the checkpoint is missing or malformed
     */
    public static HlsDownloadPlan fromLegacy(final List<?> legacy) throws IOException {
        if (legacy == null || legacy.isEmpty()) {
            throw new IOException("Legacy HLS checkpoint has no segments");
        }
        final List<Segment> segments = new ArrayList<>(legacy.size());
        for (int i = 0; i < legacy.size(); i++) {
            final Object value = legacy.get(i);
            if (!(value instanceof HlsParser.Segment)) {
                throw new IOException("Invalid segment in legacy HLS checkpoint");
            }
            final HlsParser.Segment media = (HlsParser.Segment) value;
            if (media.getUri() == null || !media.getUri().isAbsolute()) {
                throw new IOException("Invalid resource in legacy HLS checkpoint");
            }
            segments.add(new Segment(media, new Window(i, 0, -1), null));
        }
        return new HlsDownloadPlan(segments);
    }

    public static final class Segment implements Serializable {
        private static final long serialVersionUID = 1L;

        private final HlsParser.Segment media;
        private final Window mediaWindow;
        @Nullable
        private final Initialization initialization;

        public Segment(final HlsParser.Segment media, final Window mediaWindow,
                       @Nullable final Initialization initialization) {
            this.media = Objects.requireNonNull(media);
            this.mediaWindow = Objects.requireNonNull(mediaWindow);
            this.initialization = initialization;
        }

        public HlsParser.Segment getMedia() {
            return media;
        }

        public Window getMediaWindow() {
            return mediaWindow;
        }

        @Nullable
        public Initialization getInitialization() {
            return initialization;
        }
    }

    public static final class Window implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int resourceIndex;
        private final long offset;
        private final long length;

        public Window(final int resourceIndex, final long offset, final long length) {
            if (resourceIndex < 0 || offset < 0 || length < -1
                    || (length == -1 && offset != 0)
                    || (length >= 0 && offset > Long.MAX_VALUE - length)) {
                throw new IllegalArgumentException("Invalid HLS resource window");
            }
            this.resourceIndex = resourceIndex;
            this.offset = offset;
            this.length = length;
        }

        public int getResourceIndex() {
            return resourceIndex;
        }

        public long getOffset() {
            return offset;
        }

        public long getLength() {
            return length;
        }
    }

    public static final class Initialization implements Serializable {
        private static final long serialVersionUID = 1L;

        private final HlsParser.Segment media;
        private final Window window;

        public Initialization(final HlsParser.Segment media, final Window window) {
            this.media = Objects.requireNonNull(media);
            this.window = Objects.requireNonNull(window);
        }

        public HlsParser.Segment getMedia() {
            return media;
        }

        public Window getWindow() {
            return window;
        }
    }
}
