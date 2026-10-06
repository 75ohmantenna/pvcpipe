package org.schabi.newpipe.util.debounce;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;

public class DebounceSaverTest {
    @Test
    public void olderAcknowledgmentCannotClearANewerEdit() {
        final DebounceSaver saver = new DebounceSaver(mock(DebounceSavable.class));
        saver.setHasChangesToSave();
        final long saved = saver.getRevision();
        saver.setHasChangesToSave();
        saver.setNoChangesToSave(saved);
        assertTrue(saver.getIsModified());
        saver.setNoChangesToSave(saver.getRevision());
        assertFalse(saver.getIsModified());
    }
}
