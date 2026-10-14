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

    /**
     * Delete the downloaded file.
     *
     * @return {@code true} when no storage is assigned, or the storage deletion result
     */
    public boolean delete() {
        if (storage != null) {
            return storage.delete();
        }
        return true;
    }

    /**
     * Marks the mission as deleted, independently of whether its output file was removed.
     */
    public transient boolean deleted = false;

    @NonNull
    @Override
    public String toString() {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return "[" + calendar.getTime().toString() + "] "
                + (storage.isInvalid() ? storage.getName() : storage.getUri());
    }
}
