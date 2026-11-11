package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class ItagItemCopyTest {
    @Test
    void copyPreservesStreamMetadataAndIsIndependent() throws Exception {
        final ItagItem original = ItagItem.getItag(140);
        original.setIsDrc(true);
        original.setLastModified(1730000000000000L);
        original.setXtags("drc=1:lang=en");

        final ItagItem copy = new ItagItem(original);
        assertTrue(copy.isDrc());
        assertEquals(original.getLastModified(), copy.getLastModified());
        assertEquals(original.getXtags(), copy.getXtags());
        assertEquals(original.id, copy.id);
        assertEquals(original.getMediaFormat(), copy.getMediaFormat());

        copy.setIsDrc(false);
        copy.setLastModified(1L);
        copy.setXtags("changed");
        assertTrue(original.isDrc());
        assertEquals(1730000000000000L, original.getLastModified());
        assertEquals("drc=1:lang=en", original.getXtags());
    }

    @Test
    void copyPreservesUnsetStreamMetadata() throws Exception {
        final ItagItem copy = new ItagItem(ItagItem.getItag(140));
        assertFalse(copy.isDrc());
        assertEquals(0L, copy.getLastModified());
        assertNull(copy.getXtags());
    }
}
