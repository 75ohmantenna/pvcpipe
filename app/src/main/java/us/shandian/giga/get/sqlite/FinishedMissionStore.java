package us.shandian.giga.get.sqlite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Objects;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.Mission;
import org.schabi.newpipe.streams.io.StoredFileHelper;

/**
 * SQLite helper to store finished {@link us.shandian.giga.get.FinishedMission}'s.
 */
public class FinishedMissionStore extends SQLiteOpenHelper {

    // Download history remains in its own downloads.db, separate from Room's newpipe.db.
    private static final String DATABASE_NAME = "downloads.db";

    private static final int DATABASE_VERSION = 5;

    /**
     * The table name of download missions (old).
     */
    @SuppressWarnings("checkstyle:ConstantName")
    private static final String MISSIONS_TABLE_NAME_v2 = "download_missions";

    /**
     * The table name of download missions.
     */
    private static final String FINISHED_TABLE_NAME = "finished_missions";

    /**
     * The key to the urls of a mission.
     */
    private static final String KEY_SOURCE = "url";


    /**
     * The key to the done.
     */
    private static final String KEY_DONE = "bytes_downloaded";

    private static final String KEY_TIMESTAMP = "timestamp";

    private static final String KEY_KIND = "kind";

    private static final String KEY_PATH = "path";

    private static final String KEY_SEGMENTS = "segments";

    /**
     * The statement to create the table.
     */
    private static final String MISSIONS_CREATE_TABLE =
            "CREATE TABLE " + FINISHED_TABLE_NAME + " ("
                    + KEY_PATH + " TEXT NOT NULL, "
                    + KEY_SOURCE + " TEXT NOT NULL, "
                    + KEY_DONE + " INTEGER NOT NULL, "
                    + KEY_TIMESTAMP + " INTEGER NOT NULL, "
                    + KEY_KIND + " TEXT NOT NULL, "
                    + KEY_SEGMENTS + " TEXT, "
                    + " UNIQUE(" + KEY_TIMESTAMP + ", " + KEY_PATH + "));";


    private final Context context;

    public FinishedMissionStore(final Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context;
    }

    @Override
    public void onCreate(final SQLiteDatabase db) {
        db.execSQL(MISSIONS_CREATE_TABLE);
    }

    @Override
    @SuppressWarnings("checkstyle:FinalParameters")
    public void onUpgrade(final SQLiteDatabase db, int oldVersion, final int newVersion) {
        if (oldVersion == 2) {
            db.execSQL("ALTER TABLE " + MISSIONS_TABLE_NAME_v2 + " ADD COLUMN " + KEY_KIND
                    + " TEXT;");
            oldVersion++;
        }

        if (oldVersion == 3) {
            final String keyLocation = "location";
            final String keyName = "name";

            db.execSQL(MISSIONS_CREATE_TABLE);

            try (Cursor cursor = db.query(MISSIONS_TABLE_NAME_v2, null, null,
                    null, null, null, KEY_TIMESTAMP)) {
                if (cursor.getCount() > 0) {
                    db.beginTransaction();
                    try {
                        while (cursor.moveToNext()) {
                            final ContentValues values = new ContentValues();
                            values.put(
                                    KEY_SOURCE,
                                    cursor.getString(cursor.getColumnIndexOrThrow(KEY_SOURCE))
                            );
                            values.put(
                                    KEY_DONE,
                                    cursor.getString(cursor.getColumnIndexOrThrow(KEY_DONE))
                            );
                            values.put(
                                    KEY_TIMESTAMP,
                                    cursor.getLong(cursor.getColumnIndexOrThrow(KEY_TIMESTAMP))
                            );
                            values.put(KEY_KIND,
                                    cursor.getString(cursor.getColumnIndexOrThrow(KEY_KIND)));
                            values.put(KEY_PATH, Uri.fromFile(
                                    new File(
                                            cursor.getString(cursor.getColumnIndexOrThrow(
                                                    keyLocation)),
                                            cursor.getString(cursor.getColumnIndexOrThrow(keyName))
                                    )
                            ).toString());

                            db.insertOrThrow(FINISHED_TABLE_NAME, null, values);
                        }
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                }
            }

            db.execSQL("DROP TABLE " + MISSIONS_TABLE_NAME_v2);
            oldVersion++;
        }

        if (oldVersion == 4) {
            db.execSQL("ALTER TABLE " + FINISHED_TABLE_NAME + " ADD COLUMN " + KEY_SEGMENTS
                    + " TEXT;");
        }
    }

    /**
     * Returns all values of the download mission as ContentValues.
     *
     * @param downloadMission the download mission
     * @return the content values
     */
    private ContentValues getValuesOfMission(@NonNull final Mission downloadMission) {
        final ContentValues values = new ContentValues();
        values.put(KEY_SOURCE, downloadMission.source);
        values.put(KEY_PATH, downloadMission.storage.getUri().toString());
        values.put(KEY_DONE, downloadMission.length);
        values.put(KEY_TIMESTAMP, downloadMission.timestamp);
        values.put(KEY_KIND, String.valueOf(downloadMission.kind));
        values.put(KEY_SEGMENTS, downloadMission.segmentsJson);
        return values;
    }

    private FinishedMission getMissionFromCursor(final Cursor cursor) {
        String kind = Objects.requireNonNull(cursor)
                .getString(cursor.getColumnIndexOrThrow(KEY_KIND));
        if (kind == null || kind.isEmpty()) {
            kind = "?";
        }

        final String path = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PATH));

        final FinishedMission mission = new FinishedMission();

        mission.source = cursor.getString(cursor.getColumnIndexOrThrow(KEY_SOURCE));
        mission.length = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DONE));
        mission.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_TIMESTAMP));
        mission.kind = kind.charAt(0);
        mission.segmentsJson = cursor.getString(cursor.getColumnIndexOrThrow(KEY_SEGMENTS));

        try {
            // Keep the URI even when the provider cannot currently supply its display name.
            mission.storage = new StoredFileHelper(context, Uri.parse(path),
                    StoredFileHelper.DEFAULT_MIME);
        } catch (Exception e) {
            Log.e("FinishedMissionStore", "failed to load the storage path of: " + path, e);
            mission.storage = new StoredFileHelper(null, path, "", "");
        }

        return mission;
    }


    //////////////////////////////////
    // Data source methods
    ///////////////////////////////////

    public ArrayList<FinishedMission> loadFinishedMissions() {
        final SQLiteDatabase database = getReadableDatabase();
        try (Cursor cursor = database.query(FINISHED_TABLE_NAME, null, null,
                null, null, null, KEY_TIMESTAMP + " DESC")) {
            final int count = cursor.getCount();
            if (count == 0) {
                return new ArrayList<>(1);
            }

            final ArrayList<FinishedMission> result = new ArrayList<>(count);
            while (cursor.moveToNext()) {
                result.add(getMissionFromCursor(cursor));
            }

            return result;
        }
    }

    public boolean addFinishedMission(final DownloadMission downloadMission) {
        final ContentValues values = getValuesOfMission(Objects.requireNonNull(downloadMission));
        final SQLiteDatabase database = getWritableDatabase();
        // Replaying a completed checkpoint after a crash must be safe.
        return database.insertWithOnConflict(FINISHED_TABLE_NAME, null, values,
                SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

    public void deleteMission(final Mission mission) {
        final String ts = String.valueOf(Objects.requireNonNull(mission).timestamp);

        final SQLiteDatabase database = getWritableDatabase();

        if (mission instanceof FinishedMission) {
            if (mission.storage.isInvalid()) {
                database.delete(FINISHED_TABLE_NAME, KEY_TIMESTAMP + " = ?", new String[]{ts});
            } else {
                database.delete(FINISHED_TABLE_NAME,
                        KEY_TIMESTAMP + " = ? AND " + KEY_PATH + " = ?",
                        new String[]{ts, mission.storage.getUri().toString()});
            }
        } else {
            throw new UnsupportedOperationException("DownloadMission");
        }
    }
}
