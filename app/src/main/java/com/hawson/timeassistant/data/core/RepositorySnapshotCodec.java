package com.hawson.timeassistant.data.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps the existing portable snapshot format to Room aggregate rows. Keeping this mapping at the
 * storage boundary lets import/export and LAN sync retain their established JSON contracts.
 */
public final class RepositorySnapshotCodec {
    public static final String TYPE_TASK = "task";
    public static final String TYPE_RECURRING_GROUP = "recurring_group";
    public static final String TYPE_RECURRING_OVERRIDE = "recurring_override";
    public static final String TYPE_REMINDER = "reminder";
    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_FOCUS_RECORD = "focus_record";
    public static final String TYPE_CURRENT_FOCUS = "current_focus";
    public static final String TYPE_SETTINGS = "settings";
    public static final String TYPE_TASK_TOMBSTONE = "task";
    public static final String TYPE_RECURRING_GROUP_TOMBSTONE = "recurring_group";
    public static final String TYPE_RECURRING_OVERRIDE_TOMBSTONE = "recurring_override";
    public static final String ID_CURRENT_FOCUS = "current";
    public static final String ID_SETTINGS = "settings";

    private RepositorySnapshotCodec() {
    }

    public static MaterializedSnapshot materialize(JSONObject snapshot) throws JSONException {
        if (snapshot == null) throw new JSONException("snapshot is missing");
        List<RepositoryDocumentEntity> documents = new ArrayList<>();
        List<RepositoryTombstoneEntity> tombstones = new ArrayList<>();
        addArrayDocuments(documents, snapshot.optJSONArray("tasks"), TYPE_TASK);
        addArrayDocuments(documents, snapshot.optJSONArray("recurringTaskGroups"),
                TYPE_RECURRING_GROUP);
        addArrayDocuments(documents, snapshot.optJSONArray("recurringOccurrenceOverrides"),
                TYPE_RECURRING_OVERRIDE);
        addArrayDocuments(documents, snapshot.optJSONArray("reminderEvents"), TYPE_REMINDER);
        JSONArray chats = snapshot.optJSONArray("chatMessages");
        addArrayDocuments(documents, chats != null ? chats : snapshot.optJSONArray("chats"),
                TYPE_CHAT);
        JSONArray records = snapshot.optJSONArray("focusRecords");
        addArrayDocuments(documents, records != null ? records : snapshot.optJSONArray("focusHistory"),
                TYPE_FOCUS_RECORD);
        addSingletonDocument(documents, snapshot.optJSONObject("currentFocus"), TYPE_CURRENT_FOCUS,
                ID_CURRENT_FOCUS);
        addSingletonDocument(documents, snapshot.optJSONObject("settings"), TYPE_SETTINGS,
                ID_SETTINGS);
        addTombstones(tombstones, snapshot.optJSONArray("taskTombstones"), TYPE_TASK_TOMBSTONE);
        addTombstones(tombstones, snapshot.optJSONArray("recurringTaskGroupTombstones"),
                TYPE_RECURRING_GROUP_TOMBSTONE);
        addTombstones(tombstones, snapshot.optJSONArray("recurringOccurrenceOverrideTombstones"),
                TYPE_RECURRING_OVERRIDE_TOMBSTONE);
        return new MaterializedSnapshot(documents, tombstones);
    }

    public static JSONObject rehydrate(List<RepositoryDocumentEntity> documents,
                                       List<RepositoryTombstoneEntity> tombstones,
                                       int schemaVersion, long exportedAt) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", schemaVersion);
        root.put("exportedAt", exportedAt);
        JSONArray tasks = new JSONArray();
        JSONArray groups = new JSONArray();
        JSONArray overrides = new JSONArray();
        JSONArray reminders = new JSONArray();
        JSONArray chats = new JSONArray();
        JSONArray focusRecords = new JSONArray();
        JSONObject currentFocus = null;
        JSONObject settings = null;
        if (documents != null) {
            for (RepositoryDocumentEntity document : documents) {
                JSONObject payload = new JSONObject(document.payloadJson);
                switch (document.type) {
                    case TYPE_TASK: tasks.put(payload); break;
                    case TYPE_RECURRING_GROUP: groups.put(payload); break;
                    case TYPE_RECURRING_OVERRIDE: overrides.put(payload); break;
                    case TYPE_REMINDER: reminders.put(payload); break;
                    case TYPE_CHAT: chats.put(payload); break;
                    case TYPE_FOCUS_RECORD: focusRecords.put(payload); break;
                    case TYPE_CURRENT_FOCUS: currentFocus = payload; break;
                    case TYPE_SETTINGS: settings = payload; break;
                    default: throw new JSONException("unknown repository document type: " + document.type);
                }
            }
        }
        root.put("tasks", tasks);
        root.put("recurringTaskGroups", groups);
        root.put("recurringOccurrenceOverrides", overrides);
        root.put("reminderEvents", reminders);
        root.put("chatMessages", chats);
        root.put("focusRecords", focusRecords);
        if (currentFocus != null) root.put("currentFocus", currentFocus);
        if (settings != null) root.put("settings", settings);
        JSONArray taskTombstones = new JSONArray();
        JSONArray groupTombstones = new JSONArray();
        JSONArray overrideTombstones = new JSONArray();
        if (tombstones != null) {
            for (RepositoryTombstoneEntity tombstone : tombstones) {
                JSONObject payload = new JSONObject();
                payload.put("id", tombstone.id);
                payload.put("deletedAt", tombstone.deletedAt);
                payload.put("updatedAt", tombstone.deletedAt);
                switch (tombstone.type) {
                    case TYPE_TASK_TOMBSTONE: taskTombstones.put(payload); break;
                    case TYPE_RECURRING_GROUP_TOMBSTONE: groupTombstones.put(payload); break;
                    case TYPE_RECURRING_OVERRIDE_TOMBSTONE: overrideTombstones.put(payload); break;
                    default: throw new JSONException("unknown repository tombstone type: " + tombstone.type);
                }
            }
        }
        root.put("taskTombstones", taskTombstones);
        root.put("recurringTaskGroupTombstones", groupTombstones);
        root.put("recurringOccurrenceOverrideTombstones", overrideTombstones);
        return root;
    }

    private static void addArrayDocuments(List<RepositoryDocumentEntity> output, JSONArray items,
                                          String type) throws JSONException {
        if (items == null) return;
        for (int index = 0; index < items.length(); index++) {
            JSONObject payload = items.optJSONObject(index);
            if (payload == null) throw new JSONException(type + "[" + index + "] is not an object");
            String id = payload.optString("id", "").trim();
            if (id.isEmpty()) throw new JSONException(type + "[" + index + "] is missing id");
            output.add(new RepositoryDocumentEntity(type, id,
                    Math.max(0L, payload.optLong("updatedAt", 0L)), payload.toString()));
        }
    }

    private static void addSingletonDocument(List<RepositoryDocumentEntity> output,
                                             JSONObject payload, String type, String id)
            throws JSONException {
        if (payload == null) return;
        output.add(new RepositoryDocumentEntity(type, id,
                Math.max(0L, payload.optLong("updatedAt", 0L)), payload.toString()));
    }

    private static void addTombstones(List<RepositoryTombstoneEntity> output, JSONArray items,
                                      String type) throws JSONException {
        if (items == null) return;
        for (int index = 0; index < items.length(); index++) {
            JSONObject payload = items.optJSONObject(index);
            if (payload == null) throw new JSONException(type + " tombstone[" + index
                    + "] is not an object");
            String id = payload.optString("id", "").trim();
            long deletedAt = Math.max(payload.optLong("deletedAt", 0L),
                    payload.optLong("updatedAt", 0L));
            if (id.isEmpty() || deletedAt <= 0L) {
                throw new JSONException(type + " tombstone[" + index + "] is invalid");
            }
            output.add(new RepositoryTombstoneEntity(type, id, deletedAt));
        }
    }

    public static final class MaterializedSnapshot {
        public final List<RepositoryDocumentEntity> documents;
        public final List<RepositoryTombstoneEntity> tombstones;

        private MaterializedSnapshot(List<RepositoryDocumentEntity> documents,
                                     List<RepositoryTombstoneEntity> tombstones) {
            this.documents = documents;
            this.tombstones = tombstones;
        }
    }
}
