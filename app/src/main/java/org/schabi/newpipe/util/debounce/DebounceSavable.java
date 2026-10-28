package org.schabi.newpipe.util.debounce;

import org.schabi.newpipe.error.ErrorInfo;

public interface DebounceSavable {

    /**
     * Starts saving the current draft.
     * Mark a successful save with {@link DebounceSaver#setNoChangesToSave(long)} using the
     * revision captured for that save, so later edits remain dirty. The no-argument overload
     * clears the flag unconditionally and is appropriate only when newer edits cannot intervene.
     */
    void saveImmediate();

    void showError(ErrorInfo errorInfo);
}
