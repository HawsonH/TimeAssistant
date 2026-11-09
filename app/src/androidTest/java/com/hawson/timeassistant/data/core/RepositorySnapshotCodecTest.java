package com.hawson.timeassistant.data.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Covers the mapping used for old-snapshot migration and Room read-back verification. */
@RunWith(AndroidJUnit4.class)
public final class RepositorySnapshotCodecTest {
    @Test
    public void materializeAndRehydrateKeepsPortableCollections() throws Exception {
        JSONObject snapshot = new JSONObject();
        snapshot.put("schemaVersion", 4);
        snapshot.put("exportedAt", 123L);
        snapshot.put("tasks", new JSONArray().put(item("task-1", 11L)));
        snapshot.put("recurringTaskGroups", new JSONArray().put(item("group-1", 12L)));
        snapshot.put("recurringOccurrenceOverrides", new JSONArray().put(item("override-1", 13L)));
        snapshot.put("reminderEvents", new JSONArray().put(item("reminder-1", 14L)));
        snapshot.put("chatMessages", new JSONArray().put(item("chat-1", 15L)));
        snapshot.put("focusRecords", new JSONArray().put(item("focus-1", 16L)));
        snapshot.put("currentFocus", item("ignored", 17L));
        snapshot.put("settings", item("ignored", 18L));
        snapshot.put("taskTombstones", new JSONArray().put(tombstone("task-removed", 19L)));
        snapshot.put("recurringTaskGroupTombstones",
                new JSONArray().put(tombstone("group-removed", 20L)));
        snapshot.put("recurringOccurrenceOverrideTombstones",
                new JSONArray().put(tombstone("override-removed", 21L)));

        RepositorySnapshotCodec.MaterializedSnapshot rows =
                RepositorySnapshotCodec.materialize(snapshot);
        JSONObject rehydrated = RepositorySnapshotCodec.rehydrate(rows.documents, rows.tombstones,
                4, 123L);

        assertEquals("task-1", rehydrated.getJSONArray("tasks").getJSONObject(0).getString("id"));
        assertEquals("chat-1", rehydrated.getJSONArray("chatMessages").getJSONObject(0)
                .getString("id"));
        assertEquals("task-removed", rehydrated.getJSONArray("taskTombstones").getJSONObject(0)
                .getString("id"));
        assertEquals(18L, rehydrated.getJSONObject("settings").getLong("updatedAt"));
    }

    @Test
    public void materializeRejectsTombstoneWithoutDeletionTime() throws Exception {
        JSONObject snapshot = new JSONObject();
        snapshot.put("taskTombstones", new JSONArray().put(new JSONObject().put("id", "gone")));

        assertThrows(JSONException.class, () -> RepositorySnapshotCodec.materialize(snapshot));
    }

    private static JSONObject item(String id, long updatedAt) throws JSONException {
        return new JSONObject().put("id", id).put("updatedAt", updatedAt);
    }

    private static JSONObject tombstone(String id, long deletedAt) throws JSONException {
        return new JSONObject().put("id", id).put("deletedAt", deletedAt);
    }
}
