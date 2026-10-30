package us.shandian.giga.get;

import androidx.annotation.NonNull;


import java.io.Serializable;
import java.util.Calendar;

import org.schabi.newpipe.streams.io.StoredFileHelper;

public abstract class Mission implements Serializable {
    private static final long serialVersionUID = 1L; // last bump: 27 march 2019

    /**
     * Source url of the resource.
     */
    public String source;

    /**
     * Length of the current resource.
     */
    public long length;

    /**
     * creation timestamp (and maybe unique identifier).
     */
    public long timestamp;

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * pre-defined content type.
     */
    public char kind;

    /**
     * The downloaded file.
     */
    public StoredFileHelper storage;

    public String segmentsJson;

    // A provider may revoke access as soon as deletion succeeds. Retry the remaining bookkeeping
    // without querying that URI again; pending checkpoints and finished receipts persist this fact.
    public volatile boolean mediaDeleted;

    /**
     * Delete the downloaded file.
     * The app defers grant release until commit; the provider can revoke earlier.
     *
     * @return whether deletion completed or no target is assigned
     */
    public boolean delete() {
        if (!mediaDeleted && storage != null && !storage.deleteRetainingPermission()) {
            return false;
        }
        mediaDeleted = true;
        return true;
    }

    @NonNull
    @Override
    public String toString() {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return "[" + calendar.getTime().toString() + "] "
                + (storage.isInvalid() ? storage.getName() : storage.getUri());
    }
}
