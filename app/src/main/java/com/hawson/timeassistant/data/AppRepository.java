/**
 * 应用数据仓库
 *
 * 任务、聊天、专注与设置的单一数据源，基于 SharedPreferences 持久化 JSON 快照。
 */
package com.hawson.timeassistant.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.hawson.timeassistant.data.core.TimeAssistantCoreStore;
import com.hawson.timeassistant.data.core.TimeAssistantCoreDatabase;
import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.FocusRecord;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.RecurringOccurrenceOverride;
import com.hawson.timeassistant.domain.RecurringTaskGroup;
import com.hawson.timeassistant.domain.ReminderEvent;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.util.JsonInterop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Calendar;

/**
 * In-memory domain state backed by the core Room database. Public reads return defensive copies;
 * compatibility reads are retained while the page controllers finish moving to {@link #observeState()}.
 */
public final class AppRepository {
    private static final int SCHEMA_VERSION = 4;
    private static final String PREFS_NAME = "time_assistant_repository";
    private static final String KEY_SNAPSHOT = "snapshot_v2";
    private static final String KEY_BACKUP = "snapshot_v2_backup";
    private static final String LEGACY_PREFS_NAME = "time_assistant_schedule";
    private static final String LEGACY_EVENTS_KEY = "events";
    private static volatile AppRepository instance;

    public enum ImportMode { REPLACE, MERGE }

    public enum ChangeType {
        TASKS, TASK_SERIES, REMINDERS, CHAT, FOCUS_HISTORY, CURRENT_FOCUS, SETTINGS,
        IMPORT, CLEAR, MIGRATION
    }

    public static final class Change {
        private final ChangeType type;
        private final String itemId;

        private Change(ChangeType type, String itemId) {
            this.type = type;
            this.itemId = itemId;
        }

        public ChangeType getType() { return type; }
        public String getItemId() { return itemId; }
    }

    public enum FocusCompletionKind {
        NOT_DUE,
        ALREADY_SETTLED,
        FOCUS_ENDED,
        FOCUS_AWAITING_FEEDBACK,
        BREAK_READY,
        BREAK_RUNNING,
        BREAK_IDLE
    }

    /** Immutable result of the atomic natural-completion transition. */
    public static final class FocusCompletion {
        private final FocusCompletionKind kind;
        private final FocusState completedState;
        private final FocusState currentState;
        private final FocusRecord record;
        private final boolean settledNow;

        private FocusCompletion(FocusCompletionKind kind, FocusState completedState,
                                FocusState currentState, FocusRecord record,
                                boolean settledNow) {
            this.kind = kind;
            this.completedState = completedState;
            this.currentState = currentState;
            this.record = record;
            this.settledNow = settledNow;
        }

        public FocusCompletionKind getKind() { return kind; }
        public FocusState getCompletedState() { return completedState; }
        public FocusState getCurrentState() { return currentState; }
        public FocusRecord getRecord() { return record; }
        public boolean wasSettledNow() { return settledNow; }
        public boolean isAwaitingFeedback() {
            return kind == FocusCompletionKind.FOCUS_AWAITING_FEEDBACK;
        }
    }

    public interface ChangeListener {
        void onRepositoryChanged(Change change);
    }

    public static final class RepositoryException extends RuntimeException {
        public RepositoryException(String message) { super(message); }
        public RepositoryException(String message, Throwable cause) { super(message, cause); }
    }

    private interface Mutation { void run(); }

    private final Object lock = new Object();
    private final SharedPreferences preferences;
    private final SharedPreferences legacyPreferences;
    private final TimeAssistantCoreStore coreStore;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TimeAssistantRepositoryIO");
        thread.setDaemon(true);
        return thread;
    });
    // Commands may wait for lock; persistence must never queue behind such a command.
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TimeAssistantRepositoryCommands");
        thread.setDaemon(true);
        return thread;
    });
    private final MutableLiveData<AppState> observableState = new MutableLiveData<>();
    private final CopyOnWriteArrayList<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedHashMap<String, TaskItem> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> taskTombstones = new LinkedHashMap<>();
    private final LinkedHashMap<String, RecurringTaskGroup> recurringGroups =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> recurringGroupTombstones = new LinkedHashMap<>();
    private final LinkedHashMap<String, RecurringOccurrenceOverride> recurringOverrides =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> recurringOverrideTombstones = new LinkedHashMap<>();
    private final LinkedHashMap<String, ReminderEvent> reminderEvents = new LinkedHashMap<>();
    private final List<ChatMessage> chatMessages = new ArrayList<>();
    private final List<FocusRecord> focusRecords = new ArrayList<>();
    private AppSettings settings = new AppSettings();
    private FocusState currentFocus = FocusState.idle();
    private volatile String lastLoadError;
    private boolean writesBlockedByCorruption;
    private long revision;
    private List<Change> proposalChanges;
    private final Runnable testCommit;

    /** Synchronous local writes only. Do not start/wait for external host operations here. */
    public interface ProposalMutation<T, E extends Exception> {
        T run() throws E;
    }

    /** In-memory capability; retain in a ViewModel, never serialize or recreate after process death. */
    public static final class UndoTicket {
        private final AppRepository owner;
        public final String sessionId;
        private final long revision;
        private final Snapshot before;
        private final String messageId;
        private final List<Integer> actionIndexes;
        private final int actionCount;
        private boolean consumed;

        private UndoTicket(AppRepository owner, String sessionId, long revision, Snapshot before,
                           String messageId, List<Integer> actionIndexes, int actionCount) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.revision = revision;
            this.before = before;
            this.messageId = messageId;
            this.actionIndexes = Collections.unmodifiableList(new ArrayList<>(actionIndexes));
            this.actionCount = actionCount;
        }
    }

    public static final class ProposalSessionResult<T> {
        public final T value;
        public final UndoTicket undoTicket;

        private ProposalSessionResult(T value, UndoTicket undoTicket) {
            this.value = value;
            this.undoTicket = undoTicket;
        }
    }

    /** Snapshot, local actions, own chat states and ticket revision share the actual write lock. */
    public <T, E extends Exception> ProposalSessionResult<T> runProposalSession(
            String sessionId, ProposalMutation<T, E> operation) throws E {
        return runProposalSession(sessionId, null, Collections.emptyList(), 0, operation);
    }

    /** Own proposal indexes become REVERTED in the same commit as undo's data restore. */
    public <T, E extends Exception> ProposalSessionResult<T> runProposalSession(
            String sessionId, String messageId, List<Integer> actionIndexes, int actionCount,
            ProposalMutation<T, E> operation) throws E {
        if (sessionId == null || sessionId.trim().isEmpty() || operation == null) {
            throw new IllegalArgumentException("sessionId and operation are required");
        }
        List<Change> changes;
        List<ChangeListener> listenerSnapshot;
        ProposalSessionResult<T> result;
        List<Integer> indexes = actionIndexes == null ? Collections.emptyList()
                : new ArrayList<>(actionIndexes);
        synchronized (lock) {
            if (proposalChanges != null) throw new IllegalStateException("nested proposal session");
            if (writesBlockedByCorruption) throw new RepositoryException("repository is write blocked");
            Snapshot before = snapshotCopyLocked();
            String previousError = lastLoadError;
            proposalChanges = new ArrayList<>();
            try {
                T value = operation.run();
                enforceInvariantsLocked();
                changes = new ArrayList<>(proposalChanges);
                Snapshot undo = before.copy();
                undo.settings = undo.settings.copyWithoutApiKey();
                undo.settingsContainsApiKey = false;
                if (!changes.isEmpty()) persistLocked();
                result = new ProposalSessionResult<>(value, changes.isEmpty() ? null
                        : new UndoTicket(this, sessionId, revision, undo, messageId, indexes, actionCount));
            } catch (Exception | Error failure) {
                restoreSnapshotLocked(before);
                writesBlockedByCorruption = false;
                lastLoadError = previousError;
                throw failure;
            } finally {
                proposalChanges = null;
            }
            listenerSnapshot = new ArrayList<>(listeners);
            if (!changes.isEmpty()) publishStateLocked();
        }
        notifyChanges(changes, listenerSnapshot);
        return result;
    }

    public boolean canUndo(UndoTicket ticket, String sessionId) {
        synchronized (lock) { return canUndoLocked(ticket, sessionId); }
    }

    private boolean canUndoLocked(UndoTicket ticket, String sessionId) {
        return ticket != null && ticket.owner == this && !ticket.consumed
                && ticket.sessionId.equals(sessionId) && ticket.revision == revision
                && !writesBlockedByCorruption && proposalChanges == null;
    }

    /** Atomic compare-and-restore. A stale/wrong-session ticket never writes anything. */
    public boolean undoProposalSession(UndoTicket ticket, String sessionId) {
        List<ChangeListener> listenerSnapshot;
        synchronized (lock) {
            if (!canUndoLocked(ticket, sessionId)) return false;
            Snapshot current = snapshotCopyLocked();
            try {
                restoreSnapshotLocked(ticket.before);
                settings.preserveLocalApiKey(current.settings.getApiKey());
                // Deletions are sync history, including items created then deleted in this session.
                mergeTombstones(taskTombstones, current.taskTombstones);
                mergeTombstones(recurringGroupTombstones, current.recurringGroupTombstones);
                mergeTombstones(recurringOverrideTombstones, current.recurringOverrideTombstones);
                reconcileExplicitReplacementLocked(current);
                // Restoring an edit must also win over the executed version on sync peers.
                for (TaskItem restored : tasks.values()) {
                    TaskItem executed = current.tasks.get(restored.getId());
                    if (executed != null) restored.markUpdatedAfter(executed.getUpdatedAt());
                }
                for (RecurringTaskGroup restored : recurringGroups.values()) {
                    RecurringTaskGroup executed = current.recurringGroups.get(restored.getId());
                    if (executed != null) restored.markUpdatedAfter(executed.getUpdatedAt());
                }
                for (RecurringOccurrenceOverride restored : recurringOverrides.values()) {
                    RecurringOccurrenceOverride executed = current.recurringOverrides.get(restored.getId());
                    if (executed != null) restored.markUpdatedAfter(executed.getUpdatedAt());
                }
                if (ticket.messageId != null) {
                    ChatMessage executed = null;
                    for (ChatMessage message : current.chatMessages) {
                        if (ticket.messageId.equals(message.getId())) executed = message;
                    }
                    if (executed == null) throw new IllegalStateException("proposal not found");
                    ChatMessage reverted = executed.withActionItemStates(ticket.actionIndexes,
                            ChatMessage.ActionItemState.REVERTED, ticket.actionCount);
                    removeChatMessageLocked(ticket.messageId);
                    chatMessages.add(reverted);
                    chatMessages.sort(Comparator.comparingLong(ChatMessage::getCreatedAt));
                }
                enforceInvariantsLocked();
                advanceRestoredStateVersionsLocked(current);
                persistLocked();
            } catch (RuntimeException | Error failure) {
                restoreSnapshotLocked(current);
                throw failure;
            }
            ticket.consumed = true;
            listenerSnapshot = new ArrayList<>(listeners);
            publishStateLocked();
        }
        notifyChanges(Collections.singletonList(new Change(ChangeType.IMPORT, null)), listenerSnapshot);
        return true;
    }

    private static void mergeTombstones(Map<String, Long> restored, Map<String, Long> executed) {
        for (Map.Entry<String, Long> entry : executed.entrySet()) {
            restored.merge(entry.getKey(), entry.getValue(), Math::max);
        }
    }

    private void advanceRestoredStateVersionsLocked(Snapshot executed) {
        try {
            if (settings.getUpdatedAt() != executed.settings.getUpdatedAt()) {
                JSONObject restored = settings.toJson(false);
                restored.put("updatedAt", Math.max(System.currentTimeMillis(),
                        nextVersion(Math.max(settings.getUpdatedAt(), executed.settings.getUpdatedAt()))));
                settings = AppSettings.fromJson(restored)
                        .preserveLocalApiKey(executed.settings.getApiKey());
            }
            if (currentFocus != executed.currentFocus) {
                JSONObject restored = currentFocus.toJson();
                restored.put("updatedAt", Math.max(System.currentTimeMillis(),
                        nextVersion(Math.max(currentFocus.getUpdatedAt(), executed.currentFocus.getUpdatedAt()))));
                currentFocus = FocusState.fromJson(restored);
            }
        } catch (JSONException failure) {
            throw new RepositoryException("could not version restored proposal state", failure);
        }
    }

    /** Returns the only writable repository instance in this application process. */
    public static AppRepository get(Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        AppRepository current = instance;
        if (current == null) {
            synchronized (AppRepository.class) {
                current = instance;
                if (current == null) {
                    current = new AppRepository(context);
                    instance = current;
                }
            }
        }
        return current;
    }

    private AppRepository(Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        testCommit = null;
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        legacyPreferences = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
        coreStore = new TimeAssistantCoreStore(appContext);
        executeOnIoBlocking(() -> {
            loadOrMigrate();
            return null;
        });
    }

    /** Hermetic JVM seam for exercising the real write boundary, including commit failures. */
    AppRepository(Runnable commit) {
        preferences = null;
        legacyPreferences = null;
        coreStore = null;
        testCommit = java.util.Objects.requireNonNull(commit);
    }

    public void addChangeListener(ChangeListener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeChangeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    /** Reactive state stream for page ViewModels. Values are published only after a commit. */
    public LiveData<AppState> observeState() {
        return observableState;
    }

    /** Database access is reserved for background gateways; UI code should observe state instead. */
    public TimeAssistantCoreDatabase getCoreDatabase() {
        return coreStore.getDatabase();
    }

    /** Runs a repository command on its serial I/O queue and returns completion on the caller's executor. */
    public void executeAsync(RepositoryCommand command, CommandCallback callback) {
        if (command == null) throw new IllegalArgumentException("command must not be null");
        commandExecutor.execute(() -> {
            try {
                command.run(this);
                if (callback != null) callback.onComplete(null);
            } catch (RuntimeException exception) {
                if (callback != null) callback.onComplete(exception);
            }
        });
    }

    public interface RepositoryCommand {
        void run(AppRepository repository);
    }

    public interface CommandCallback {
        void onComplete(RuntimeException error);
    }

    /** Non-null only when loading required recovery or encountered corrupt persisted data. */
    public String getLastLoadError() { return lastLoadError; }

    public boolean isWriteBlockedByCorruption() {
        synchronized (lock) { return writesBlockedByCorruption; }
    }

    public TaskItem upsertTask(TaskItem task) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        TaskItem stored = task.copy();
        mutate(new Change(ChangeType.TASKS, stored.getId()), false, () -> {
            Long deletedAt = taskTombstones.remove(stored.getId());
            if (deletedAt != null && stored.getUpdatedAt() <= deletedAt) {
                stored.markUpdatedAfter(deletedAt);
            }
            tasks.put(stored.getId(), stored);
        });
        return stored.copy();
    }

    public TaskItem createTask(String title, String source) {
        TaskItem task = new TaskItem(title).setSource(source);
        return upsertTask(task);
    }

    public CompletionUndoTicket completeTaskForUndo(String id, long expectedVersion) {
        CompletionUndoTicket[] result = new CompletionUndoTicket[1];
        try {
            mutate(new Change(ChangeType.TASKS, id), false, () -> {
                TaskItem current = tasks.get(id);
                if (current == null || current.getStatus().isTerminal()
                        || current.getUpdatedAt() != expectedVersion) throw new CompletionChanged();
                TaskItem completed = current.copy().setStatus(TaskItem.Status.COMPLETED);
                result[0] = new CompletionUndoTicket(this, current, completed);
                tasks.put(id, completed);
            });
            return result[0];
        } catch (CompletionChanged stale) { return null; }
    }

    public boolean undoTaskCompletion(CompletionUndoTicket ticket) {
        if (ticket == null || ticket.owner != this) return false;
        try {
            mutate(new Change(ChangeType.TASKS, ticket.taskId()), false, () -> {
                TaskItem current = tasks.get(ticket.taskId());
                if (!ticket.matches(current)) throw new CompletionChanged();
                tasks.put(ticket.taskId(), ticket.before.copy().markUpdatedAfter(current.getUpdatedAt()));
            });
            // Consumption is checked inside the same write boundary as the restored task version.
            ticket.consumed = true;
            return true;
        } catch (CompletionChanged stale) { return false; }
    }

    private static final class CompletionChanged extends RuntimeException { }

    public RecurringTaskGroup upsertRecurringGroup(RecurringTaskGroup group) {
        if (group == null) throw new IllegalArgumentException("recurring group must not be null");
        RecurringTaskGroup stored = group.copy();
        mutate(new Change(ChangeType.TASK_SERIES, stored.getId()), false, () -> {
            Long deletedAt = recurringGroupTombstones.remove(stored.getId());
            if (deletedAt != null && stored.getUpdatedAt() <= deletedAt) {
                stored.markUpdatedAfter(deletedAt);
            }
            recurringGroups.put(stored.getId(), stored);
        });
        return stored.copy();
    }

    public RecurringTaskGroup getRecurringGroup(String id) {
        synchronized (lock) {
            RecurringTaskGroup group = recurringGroups.get(id);
            return group == null ? null : group.copy();
        }
    }

    public List<RecurringTaskGroup> getAllRecurringGroups() {
        synchronized (lock) {
            List<RecurringTaskGroup> result = new ArrayList<>();
            for (RecurringTaskGroup group : recurringGroups.values()) result.add(group.copy());
            return Collections.unmodifiableList(result);
        }
    }

    public boolean deleteRecurringGroup(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        synchronized (lock) {
            if (!recurringGroups.containsKey(id)) return false;
        }
        mutate(new Change(ChangeType.TASK_SERIES, id), false, () -> {
            RecurringTaskGroup removed = recurringGroups.remove(id);
            if (removed == null) return;
            long previous = recurringGroupTombstones.containsKey(id)
                    ? recurringGroupTombstones.get(id) : 0L;
            long deletedAt = Math.max(System.currentTimeMillis(),
                    Math.max(nextVersion(previous), nextVersion(removed.getUpdatedAt())));
            recurringGroupTombstones.put(id, deletedAt);
            List<String> overrideIds = new ArrayList<>();
            for (RecurringOccurrenceOverride override : recurringOverrides.values()) {
                if (id.equals(override.getRecurringGroupId())) overrideIds.add(override.getId());
            }
            for (String overrideId : overrideIds) deleteRecurringOverrideLocked(overrideId);
        });
        return true;
    }

    public RecurringOccurrenceOverride upsertRecurringOverride(
            RecurringOccurrenceOverride override) {
        if (override == null) throw new IllegalArgumentException("override must not be null");
        RecurringOccurrenceOverride stored = override.copy();
        mutate(new Change(ChangeType.TASK_SERIES, stored.getId()), false, () -> {
            if (!recurringGroups.containsKey(stored.getRecurringGroupId())) {
                throw new IllegalArgumentException("recurring group does not exist");
            }
            Long deletedAt = recurringOverrideTombstones.remove(stored.getId());
            if (deletedAt != null && stored.getUpdatedAt() <= deletedAt) {
                stored.markUpdatedAfter(deletedAt);
            }
            recurringOverrides.put(stored.getId(), stored);
        });
        return stored.copy();
    }

    public RecurringOccurrenceOverride getRecurringOverride(String groupId, long occurrenceAt) {
        return getRecurringOverride(RecurringOccurrenceOverride.stableId(groupId, occurrenceAt));
    }

    public RecurringOccurrenceOverride getRecurringOverride(String id) {
        synchronized (lock) {
            RecurringOccurrenceOverride override = recurringOverrides.get(id);
            return override == null ? null : override.copy();
        }
    }

    public List<RecurringOccurrenceOverride> getAllRecurringOverrides() {
        synchronized (lock) {
            List<RecurringOccurrenceOverride> result = new ArrayList<>();
            for (RecurringOccurrenceOverride override : recurringOverrides.values()) {
                result.add(override.copy());
            }
            return Collections.unmodifiableList(result);
        }
    }

    public boolean deleteRecurringOverride(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        synchronized (lock) {
            if (!recurringOverrides.containsKey(id)) return false;
        }
        mutate(new Change(ChangeType.TASK_SERIES, id), false,
                () -> deleteRecurringOverrideLocked(id));
        return true;
    }

    private void deleteRecurringOverrideLocked(String id) {
        RecurringOccurrenceOverride removed = recurringOverrides.remove(id);
        if (removed == null) return;
        long previous = recurringOverrideTombstones.containsKey(id)
                ? recurringOverrideTombstones.get(id) : 0L;
        long deletedAt = Math.max(System.currentTimeMillis(),
                Math.max(nextVersion(previous), nextVersion(removed.getUpdatedAt())));
        recurringOverrideTombstones.put(id, deletedAt);
    }

    public ReminderEvent upsertReminderEvent(ReminderEvent event) {
        if (event == null) throw new IllegalArgumentException("event must not be null");
        ReminderEvent stored = event.copy();
        mutate(new Change(ChangeType.REMINDERS, stored.getId()), false,
                () -> reminderEvents.put(stored.getId(), stored));
        return stored.copy();
    }

    public ReminderEvent claimReminder(String taskId, String kind, long targetAt, long now) {
        ReminderEvent[] claimed = new ReminderEvent[1];
        try {
            mutate(new Change(ChangeType.REMINDERS, taskId), false, () -> {
                TaskItem task = tasks.get(taskId);
                boolean review = "daily_review".equals(kind);
                if (review ? !settings.isDailyReviewReminderEnabled() || targetAt <= 0 || targetAt > now
                        : !com.hawson.timeassistant.reminder.ReminderPolicy.isDue(task, settings, kind, targetAt, now))
                    throw new ReminderRejected();
                ReminderEvent existing = null;
                for (ReminderEvent event : reminderEvents.values()) {
                    if (java.util.Objects.equals(taskId, event.getTargetId()) && event.getTriggerAt() == targetAt
                            && event.getTargetType() == (review ? ReminderEvent.TargetType.REVIEW : ReminderEvent.TargetType.TASK)) {
                        existing = event; break;
                    }
                }
                boolean needsEndTransition = !review && "schedule_end".equals(kind)
                        && task.getStatus() != TaskItem.Status.AWAITING_CONFIRMATION;
                if (existing != null && !"countdown".equals(kind) && !needsEndTransition)
                    throw new ReminderRejected();
                if (!review && "countdown".equals(kind)) {
                    TaskItem changed = task.copy();
                    if (changed.isRepeatingCountdown()) changed.advanceCountdownAfter(now);
                    else changed.clearCountdown();
                    tasks.put(taskId, changed);
                }
                if (needsEndTransition)
                    tasks.put(taskId, task.copy().setStatus(TaskItem.Status.AWAITING_CONFIRMATION));
                if (existing != null) return;
                ReminderEvent event = ReminderEvent.triggered(review ? ReminderEvent.TargetType.REVIEW
                        : ReminderEvent.TargetType.TASK, taskId, kind, review ? "今日复盘" : task.getTitle(), targetAt);
                reminderEvents.put(event.getId(), event);
                claimed[0] = event.copy();
            });
        } catch (ReminderRejected ignored) { }
        return claimed[0];
    }

    public void updateReminderDelivery(String id, String deliveryState, boolean presented) {
        mutate(new Change(ChangeType.REMINDERS, id), false, () -> {
            ReminderEvent current = reminderEvents.get(id);
            if (current == null) return;
            ReminderEvent updated = current.copy();
            if (deliveryState != null) updated.deliveryState(deliveryState);
            if (presented) updated.markPresented(System.currentTimeMillis());
            reminderEvents.put(id, updated);
        });
    }

    private static final class ReminderRejected extends RuntimeException { }

    public ReminderEvent getReminderEvent(String id) {
        synchronized (lock) {
            ReminderEvent event = reminderEvents.get(id);
            return event == null ? null : event.copy();
        }
    }

    public List<ReminderEvent> getAllReminderEvents() {
        synchronized (lock) {
            List<ReminderEvent> result = new ArrayList<>();
            for (ReminderEvent event : reminderEvents.values()) result.add(event.copy());
            result.sort(Comparator.comparingLong(ReminderEvent::getTriggerAt).reversed());
            return Collections.unmodifiableList(result);
        }
    }

    /** Links an existing child task to an existing parent task and persists both sides. */
    public void linkChildTask(String parentTaskId, String childTaskId) {
        mutate(new Change(ChangeType.TASKS, parentTaskId), false, () -> {
            TaskItem parent = requireTaskLocked(parentTaskId);
            TaskItem child = requireTaskLocked(childTaskId);
            if (parentTaskId.equals(childTaskId)) {
                throw new IllegalArgumentException("a task cannot be its own parent");
            }
            String previousParent = child.getParentTaskId();
            if (previousParent != null && !previousParent.equals(parentTaskId)) {
                TaskItem previous = tasks.get(previousParent);
                if (previous != null) previous.removeChildTaskId(childTaskId);
            }
            parent.addChildTaskId(childTaskId);
            child.setParentTaskId(parentTaskId);
        });
    }

    public void unlinkChildTask(String parentTaskId, String childTaskId) {
        mutate(new Change(ChangeType.TASKS, parentTaskId), false, () -> {
            TaskItem parent = requireTaskLocked(parentTaskId);
            TaskItem child = requireTaskLocked(childTaskId);
            parent.removeChildTaskId(childTaskId);
            if (parentTaskId.equals(child.getParentTaskId())) child.setParentTaskId(null);
        });
    }

    public TaskItem getTask(String id) {
        synchronized (lock) {
            TaskItem task = tasks.get(id);
            return task == null ? null : task.copy();
        }
    }

    public List<TaskItem> getAllTasks() {
        synchronized (lock) { return copyTasks(tasks.values()); }
    }

    public boolean deleteTask(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        synchronized (lock) {
            if (!tasks.containsKey(id)) return false;
        }
        mutate(new Change(ChangeType.TASKS, id), false, () -> {
            TaskItem removed = tasks.remove(id);
            if (removed == null) return;
            long previousDeletion = taskTombstones.containsKey(id)
                    ? taskTombstones.get(id) : 0L;
            long deletedAt = Math.max(System.currentTimeMillis(),
                    Math.max(nextVersion(previousDeletion), nextVersion(removed.getUpdatedAt())));
            taskTombstones.put(id, deletedAt);
            for (TaskItem task : tasks.values()) {
                task.removeChildTaskId(id);
                if (id.equals(task.getParentTaskId())) task.setParentTaskId(null);
            }
            if (id.equals(currentFocus.getTaskId())) currentFocus = FocusState.idle();
        });
        return true;
    }

    public TaskItem updateTaskStatus(String id, TaskItem.Status status) {
        final TaskItem[] result = new TaskItem[1];
        mutate(new Change(ChangeType.TASKS, id), false, () -> {
            TaskItem task = requireTaskLocked(id);
            task.setStatus(status);
            result[0] = task.copy();
        });
        return result[0];
    }

    public List<TaskItem> getTodayTasks(long now, TimeZone timeZone) {
        return queryToday(getAllTasks(), now, timeZone);
    }

    public List<TaskItem> getUpcomingTasks(long now, int days, TimeZone timeZone) {
        return queryUpcoming(getAllTasks(), now, days, timeZone);
    }

    public List<TaskItem> getUnscheduledTasks() {
        return queryUnscheduled(getAllTasks());
    }

    public List<TaskItem> getOverdueTasks(long now) {
        return queryOverdue(getAllTasks(), now);
    }

    public List<TaskItem> getUnscheduledTasks(String project, Collection<String> requiredTags) {
        return filterByProjectAndTags(getUnscheduledTasks(), project, requiredTags);
    }

    public ChatMessage addChatMessage(ChatMessage message) {
        if (message == null) throw new IllegalArgumentException("message must not be null");
        mutate(new Change(ChangeType.CHAT, message.getId()), false, () -> {
            removeChatMessageLocked(message.getId());
            chatMessages.add(message);
            chatMessages.sort(Comparator.comparingLong(ChatMessage::getCreatedAt));
        });
        return message;
    }

    public ChatMessage updateChatActionState(String messageId, ChatMessage.ActionState state) {
        final ChatMessage[] result = new ChatMessage[1];
        mutate(new Change(ChangeType.CHAT, messageId), false, () -> {
            for (int i = 0; i < chatMessages.size(); i++) {
                ChatMessage message = chatMessages.get(i);
                if (message.getId().equals(messageId)) {
                    ChatMessage updated = message.withActionState(state);
                    chatMessages.set(i, updated);
                    result[0] = updated;
                    return;
                }
            }
            throw new IllegalArgumentException("chat message not found: " + messageId);
        });
        return result[0];
    }

    /** Updates one or more actions inside a proposal in a single persisted mutation. */
    public ChatMessage updateChatActionItemStates(String messageId, List<Integer> actionIndexes,
                                                  ChatMessage.ActionItemState state,
                                                  int actionCount) {
        final ChatMessage[] result = new ChatMessage[1];
        mutate(new Change(ChangeType.CHAT, messageId), false, () -> {
            for (int index = 0; index < chatMessages.size(); index++) {
                ChatMessage message = chatMessages.get(index);
                if (message.getId().equals(messageId)) {
                    ChatMessage updated = message.withActionItemStates(
                            actionIndexes, state, actionCount);
                    chatMessages.set(index, updated);
                    result[0] = updated;
                    return;
                }
            }
            throw new IllegalArgumentException("chat message not found: " + messageId);
        });
        return result[0];
    }

    /** Cancels superseded proposals when a newer conversational revision is produced. */
    public int cancelPendingChatActions() {
        return cancelPendingChatActions(0, null);
    }

    /** Cancels only proposals from the selected conversation and proposal channel. */
    public int cancelPendingChatActions(long conversationDay, ChatMessage.ProposalKind proposalKind) {
        final int[] changed = {0};
        mutate(new Change(ChangeType.CHAT, null), false, () -> {
            for (int index = 0; index < chatMessages.size(); index++) {
                ChatMessage message = chatMessages.get(index);
                if (message.getActionState() == ChatMessage.ActionState.PENDING_CONFIRMATION
                        && (conversationDay <= 0 || message.getConversationDay() == conversationDay)
                        && (proposalKind == null || message.getProposalKind() == proposalKind)) {
                    chatMessages.set(index, message.cancelPendingActionItems());
                    changed[0]++;
                }
            }
        });
        return changed[0];
    }

    public List<ChatMessage> getChatMessages() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(chatMessages));
        }
    }

    public ChatMessage getChatMessage(String id) {
        if (id == null) return null;
        synchronized (lock) {
            for (ChatMessage message : chatMessages) {
                if (message.getId().equals(id)) return message;
            }
            return null;
        }
    }

    public boolean deleteChatMessage(String id) {
        synchronized (lock) {
            boolean exists = false;
            for (ChatMessage message : chatMessages) if (message.getId().equals(id)) exists = true;
            if (!exists) return false;
        }
        mutate(new Change(ChangeType.CHAT, id), false, () -> removeChatMessageLocked(id));
        return true;
    }

    public void clearChatMessages() {
        mutate(new Change(ChangeType.CHAT, null), false, chatMessages::clear);
    }

    public FocusRecord addFocusRecord(FocusRecord record) {
        return addFocusRecord(record, false);
    }

    /** Adds a history record and optionally applies its duration/completion result to the task. */
    public FocusRecord addFocusRecord(FocusRecord record, boolean applyToTask) {
        if (record == null) throw new IllegalArgumentException("record must not be null");
        mutate(new Change(ChangeType.FOCUS_HISTORY, record.getId()), false, () -> {
            removeFocusRecordLocked(record.getId());
            focusRecords.add(record);
            focusRecords.sort(Comparator.comparingLong(FocusRecord::getCompletedAt));
            if (applyToTask && record.getTaskId() != null) {
                TaskItem task = requireTaskLocked(record.getTaskId());
                task.addActualDurationMinutes(record.getDurationMinutesRounded());
                if (record.isTaskCompleted()) task.setStatus(TaskItem.Status.COMPLETED);
            }
        });
        return record;
    }

    public List<FocusRecord> getFocusHistory() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(focusRecords));
        }
    }

    public List<FocusRecord> getFocusHistoryForTask(String taskId) {
        List<FocusRecord> result = new ArrayList<>();
        synchronized (lock) {
            for (FocusRecord record : focusRecords) {
                if (taskId != null && taskId.equals(record.getTaskId())) result.add(record);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public boolean deleteFocusRecord(String recordId) {
        if (recordId == null || recordId.trim().isEmpty()) return false;
        final boolean[] removed = {false};
        mutate(new Change(ChangeType.FOCUS_HISTORY, recordId), false,
                () -> removed[0] = removeFocusRecordLocked(recordId));
        return removed[0];
    }

    public FocusState getCurrentFocus() {
        synchronized (lock) { return currentFocus; }
    }

    public void setCurrentFocus(FocusState focusState) {
        if (focusState == null) throw new IllegalArgumentException("focusState must not be null");
        if (focusState.getMode() == FocusState.Mode.FOCUS
                && focusState.getState() != FocusState.State.IDLE
                && focusState.getTaskId() != null
                && getTask(focusState.getTaskId()) == null) {
            throw new IllegalArgumentException("focus task not found: " + focusState.getTaskId());
        }
        mutate(new Change(ChangeType.CURRENT_FOCUS, focusState.getTaskId()), false,
                () -> currentFocus = focusState);
    }

    public void clearCurrentFocus() {
        mutate(new Change(ChangeType.CURRENT_FOCUS, null), false,
                () -> currentFocus = FocusState.idle());
    }

    public boolean cancelCurrentFocus(String expectedSessionId, long now) {
        if (now <= 0 || expectedSessionId == null || expectedSessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("expected session and current time are required");
        }
        final boolean[] cancelled = {false};
        mutate(new Change(ChangeType.CURRENT_FOCUS, null), false, () -> {
            FocusState state = currentFocus;
            if (!expectedSessionId.equals(state.getSessionId())
                    || state.getState() == FocusState.State.IDLE
                    || state.getState() == FocusState.State.READY
                    || state.getState() == FocusState.State.FINISHED
                    || state.remainingAt(now) == 0) return;
            currentFocus = FocusState.ready(state.getTaskId(), state.getMode(),
                    state.getTotalMillis(), state.getPomodoroIndex(), state.getSessionLabel());
            cancelled[0] = true;
        });
        return cancelled[0];
    }

    /**
     * Atomically settles a naturally elapsed focus/rest timer. A focus history row has a
     * deterministic id derived from the persisted session id, so duplicate callbacks cannot add
     * duration twice. Rest completion advances to the next pomodoro using the persisted settings.
     */
    public FocusCompletion completeCurrentFocusNaturally(long now) {
        return settleCurrentFocus(null, now, false, false, "");
    }

    public FocusCompletion endCurrentFocus(String expectedSessionId, long now,
                                           boolean interrupted, String note) {
        if (expectedSessionId == null || expectedSessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("expectedSessionId must not be empty");
        }
        return settleCurrentFocus(expectedSessionId, now, true, interrupted, note);
    }

    private FocusCompletion settleCurrentFocus(String expectedSessionId, long now,
                                               boolean requestedEnd, boolean interrupted,
                                               String note) {
        if (now <= 0) throw new IllegalArgumentException("now must be greater than zero");
        synchronized (lock) {
            FocusCompletion immediate = completionWithoutMutationLocked(
                    now, expectedSessionId, requestedEnd);
            if (immediate != null) return immediate;
        }

        final FocusCompletion[] result = {null};
        mutate(new Change(ChangeType.CURRENT_FOCUS, null), false, () -> {
            FocusCompletion immediate = completionWithoutMutationLocked(
                    now, expectedSessionId, requestedEnd);
            if (immediate != null) {
                result[0] = immediate;
                return;
            }

            boolean endingEarly = requestedEnd
                    && currentFocus.getState() != FocusState.State.FINISHED
                    && currentFocus.remainingAt(now) > 0;
            long activeMillis = currentFocus.getTotalMillis() - currentFocus.remainingAt(now);
            FocusState elapsed = currentFocus.getState() == FocusState.State.FINISHED
                    ? currentFocus : currentFocus.finish(now);
            if (elapsed.getMode() == FocusState.Mode.FOCUS) {
                long completedAt = elapsed.getUpdatedAt() > 0 ? elapsed.getUpdatedAt() : now;
                long duration = endingEarly ? activeMillis : elapsed.getTotalMillis();
                long startedAt = Math.max(1L, completedAt - duration);
                TaskItem task = elapsed.getTaskId() == null
                        ? null : tasks.get(elapsed.getTaskId());
                String title = task == null
                        ? (elapsed.getSessionLabel().isEmpty()
                        ? "无任务专注" : elapsed.getSessionLabel())
                        : task.getTitle();
                FocusRecord candidate = FocusRecord.sessionCompletion(
                        elapsed.getSessionId(), elapsed.getTaskId(), title, startedAt, duration,
                        elapsed.getInterruptionCount() + (endingEarly && interrupted ? 1 : 0),
                        completedAt, endingEarly ? (interrupted ? "interrupted" : "ended_early")
                                : "completed", endingEarly ? note : "");
                FocusRecord existing = focusRecordLocked(candidate.getId());
                boolean created = existing == null;
                if (created) {
                    focusRecords.add(candidate);
                    focusRecords.sort(Comparator.comparingLong(FocusRecord::getCompletedAt));
                    if (task != null) {
                        task.addActualDurationMinutes(candidate.getDurationMinutesRounded());
                    }
                    existing = candidate;
                }
                boolean transitioned = currentFocus.getState() != FocusState.State.FINISHED;
                if (endingEarly) {
                    currentFocus = FocusState.idle();
                    result[0] = new FocusCompletion(FocusCompletionKind.FOCUS_ENDED,
                            elapsed, currentFocus, existing, created);
                    return;
                }
                FocusState settled = elapsed.markNaturalCompletionRecorded(completedAt);
                currentFocus = settled;
                result[0] = new FocusCompletion(
                        FocusCompletionKind.FOCUS_AWAITING_FEEDBACK, settled, currentFocus,
                        existing, transitioned);
                return;
            }

            AppSettings focusSettings = settings;
            if (endingEarly) {
                currentFocus = FocusState.idle();
                result[0] = new FocusCompletion(FocusCompletionKind.BREAK_IDLE,
                        elapsed, currentFocus, null, true);
                return;
            }
            FocusState next;
            FocusCompletionKind kind;
            if (!focusSettings.isAutoAdvanceFocusSegment()) {
                next = FocusState.idle();
                kind = FocusCompletionKind.BREAK_IDLE;
            } else {
                int minutes = focusSettings.getFocusDurationMinutes();
                TaskItem carriedTask = elapsed.getTaskId() == null
                        ? null : tasks.get(elapsed.getTaskId());
                String carriedTaskId = carriedTask == null || carriedTask.getStatus().isTerminal()
                        ? null : carriedTask.getId();
                next = FocusState.ready(carriedTaskId, FocusState.Mode.FOCUS,
                        minutes * 60_000L, Math.max(1, elapsed.getPomodoroIndex() + 1),
                        carriedTaskId == null ? elapsed.getSessionLabel() : "");
                if (focusSettings.isAutoStartNextPomodoro()) {
                    next = next.startOrResume(now);
                    kind = FocusCompletionKind.BREAK_RUNNING;
                } else {
                    kind = FocusCompletionKind.BREAK_READY;
                }
            }
            currentFocus = next;
            result[0] = new FocusCompletion(kind, elapsed, next, null, true);
        });
        return result[0] == null
                ? new FocusCompletion(FocusCompletionKind.NOT_DUE, null,
                getCurrentFocus(), null, false)
                : result[0];
    }

    /** Returns a terminal result when no mutation is required; null means settlement is needed. */
    private FocusCompletion completionWithoutMutationLocked(long now, String expectedSessionId,
                                                             boolean requestedEnd) {
        FocusState state = currentFocus;
        if (state == null || state.getState() == FocusState.State.IDLE
                || state.getState() == FocusState.State.READY
                || (expectedSessionId != null && !expectedSessionId.equals(state.getSessionId()))
                || (!requestedEnd && state.getState() == FocusState.State.PAUSED)) {
            return new FocusCompletion(FocusCompletionKind.NOT_DUE, null, state, null, false);
        }
        if (!requestedEnd && state.getState() == FocusState.State.RUNNING && state.remainingAt(now) > 0) {
            return new FocusCompletion(FocusCompletionKind.NOT_DUE, null, state, null, false);
        }
        if (state.getState() == FocusState.State.FINISHED
                && state.getMode() == FocusState.Mode.FOCUS) {
            FocusRecord existing = focusRecordLocked("natural-focus-" + state.getSessionId());
            if (state.isNaturalCompletionRecorded()) {
                return new FocusCompletion(FocusCompletionKind.ALREADY_SETTLED, state, state,
                        existing, false);
            }
        }
        return null;
    }

    private FocusRecord focusRecordLocked(String id) {
        for (FocusRecord record : focusRecords) {
            if (record.getId().equals(id)) return record;
        }
        return null;
    }

    public AppSettings getSettings() {
        synchronized (lock) { return settings.copy(); }
    }

    public void saveSettings(AppSettings newSettings) {
        if (newSettings == null) throw new IllegalArgumentException("settings must not be null");
        AppSettings stored = newSettings.copy();
        mutate(new Change(ChangeType.SETTINGS, null), false, () -> settings = stored);
    }

    /** Full repository export. Secrets are excluded by default. */
    public String exportJson() {
        return exportJson(false);
    }

    public String exportJson(boolean includeApiKey) {
        synchronized (lock) {
            try {
                return buildSnapshotJsonLocked(includeApiKey, System.currentTimeMillis()).toString();
            } catch (JSONException exception) {
                throw new RepositoryException("could not export repository", exception);
            }
        }
    }

    /**
     * Sync snapshots never contain the API key and use the canonical LAN protocol names
     * ({@code chats} and {@code focusHistory}) rather than the local persistence names.
     */
    public String createSyncSnapshot() {
        synchronized (lock) {
            try {
                JSONObject snapshot = buildSnapshotJsonLocked(false, System.currentTimeMillis());
                JSONArray chats = snapshot.optJSONArray("chatMessages");
                JSONArray focusHistory = snapshot.optJSONArray("focusRecords");
                snapshot.remove("chatMessages");
                snapshot.remove("focusRecords");
                snapshot.put("chats", chats == null ? new JSONArray() : chats);
                snapshot.put("focusHistory", focusHistory == null ? new JSONArray() : focusHistory);
                snapshot.put("settings", JsonInterop.toJsonObject(settings.toSyncJsonValue()));
                return snapshot.toString();
            } catch (JSONException exception) {
                throw new RepositoryException("could not export sync snapshot", exception);
            }
        }
    }

    /**
     * Parses the complete input before mutating storage. Imported files never replace the local
     * API key; credentials are owned exclusively by {@link SecureApiKeyStore}.
     */
    public void importJson(String rawJson, ImportMode mode) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            throw new IllegalArgumentException("import JSON must not be empty");
        }
        final Snapshot incoming;
        try {
            incoming = parseSnapshot(new JSONObject(rawJson));
        } catch (JSONException | IllegalArgumentException exception) {
            throw new RepositoryException("import validation failed: " + exception.getMessage(), exception);
        }
        ImportMode effectiveMode = mode == null ? ImportMode.REPLACE : mode;
        mutate(new Change(ChangeType.IMPORT, null), true, () -> {
            String existingApiKey = settings.getApiKey();
            AppSettings localSettings = settings.copy();
            Snapshot localBeforeImport = snapshotCopyLocked();
            if (effectiveMode == ImportMode.REPLACE) {
                restoreSnapshotLocked(incoming.copy());
                settings.preserveLocalApiKey(existingApiKey);
                reconcileExplicitReplacementLocked(localBeforeImport);
            } else {
                mergeSnapshotLocked(incoming, existingApiKey);
            }
            settings.preserveLocalSyncStateFrom(localSettings);
            writesBlockedByCorruption = false;
            lastLoadError = null;
        });
    }

    /** Intended to be called only after a user-confirmed destructive clear. */
    public void clearAll() {
        mutate(new Change(ChangeType.CLEAR, null), true, () -> {
            for (TaskItem task : tasks.values()) {
                long previousDeletion = taskTombstones.containsKey(task.getId())
                        ? taskTombstones.get(task.getId()) : 0L;
                long deletedAt = Math.max(System.currentTimeMillis(),
                        Math.max(nextVersion(previousDeletion), nextVersion(task.getUpdatedAt())));
                taskTombstones.put(task.getId(), deletedAt);
            }
            for (RecurringTaskGroup group : recurringGroups.values()) {
                long previousDeletion = recurringGroupTombstones.containsKey(group.getId())
                        ? recurringGroupTombstones.get(group.getId()) : 0L;
                long deletedAt = Math.max(System.currentTimeMillis(),
                        Math.max(nextVersion(previousDeletion), nextVersion(group.getUpdatedAt())));
                recurringGroupTombstones.put(group.getId(), deletedAt);
            }
            for (RecurringOccurrenceOverride override : recurringOverrides.values()) {
                long previousDeletion = recurringOverrideTombstones.containsKey(override.getId())
                        ? recurringOverrideTombstones.get(override.getId()) : 0L;
                long deletedAt = Math.max(System.currentTimeMillis(),
                        Math.max(nextVersion(previousDeletion),
                                nextVersion(override.getUpdatedAt())));
                recurringOverrideTombstones.put(override.getId(), deletedAt);
            }
            tasks.clear();
            recurringGroups.clear();
            recurringOverrides.clear();
            reminderEvents.clear();
            chatMessages.clear();
            focusRecords.clear();
            settings = new AppSettings();
            currentFocus = FocusState.idle();
            writesBlockedByCorruption = false;
            lastLoadError = null;
        });
    }

    public static List<TaskItem> queryToday(
            Collection<TaskItem> source,
            long now,
            TimeZone timeZone
    ) {
        TimeZone zone = timeZone == null ? TimeZone.getDefault() : timeZone;
        long start = startOfDay(now, zone);
        long end = addDays(start, 1, zone);
        List<TaskItem> result = new ArrayList<>();
        if (source != null) {
            for (TaskItem task : source) {
                if (task == null || task.getStatus() == TaskItem.Status.CANCELLED) continue;
                boolean scheduledToday = task.isScheduled()
                        && inRange(task.getScheduledStartAt(), start, end);
                boolean dueToday = task.getScheduledStartAt() == 0
                        && inRange(task.getDueAt(), start, end);
                if (scheduledToday || dueToday) result.add(task.copy());
            }
        }
        result.sort(TASK_TIME_COMPARATOR);
        return Collections.unmodifiableList(result);
    }

    public static List<TaskItem> queryUpcoming(
            Collection<TaskItem> source,
            long now,
            int days,
            TimeZone timeZone
    ) {
        if (days <= 0) throw new IllegalArgumentException("days must be greater than zero");
        TimeZone zone = timeZone == null ? TimeZone.getDefault() : timeZone;
        long start = addDays(startOfDay(now, zone), 1, zone);
        long end = addDays(start, days, zone);
        List<TaskItem> result = new ArrayList<>();
        if (source != null) {
            for (TaskItem task : source) {
                if (task == null || task.getStatus().isTerminal()) continue;
                long effectiveAt = effectiveTaskTime(task);
                if (inRange(effectiveAt, start, end)) result.add(task.copy());
            }
        }
        result.sort(TASK_TIME_COMPARATOR);
        return Collections.unmodifiableList(result);
    }

    public static List<TaskItem> queryUnscheduled(Collection<TaskItem> source) {
        List<TaskItem> result = new ArrayList<>();
        if (source != null) {
            for (TaskItem task : source) {
                if (task == null || task.isScheduled() || task.getStatus().isTerminal()
                        || !task.requiresTimeArrangement()) continue;
                result.add(task.copy());
            }
        }
        result.sort((first, second) -> {
            boolean firstNeedsReschedule = first.getStatus() == TaskItem.Status.NEEDS_RESCHEDULING;
            boolean secondNeedsReschedule = second.getStatus() == TaskItem.Status.NEEDS_RESCHEDULING;
            if (firstNeedsReschedule != secondNeedsReschedule) return firstNeedsReschedule ? -1 : 1;
            int priority = Integer.compare(second.getPriority(), first.getPriority());
            if (priority != 0) return priority;
            return Long.compare(zeroLast(first.getDueAt()), zeroLast(second.getDueAt()));
        });
        return Collections.unmodifiableList(result);
    }

    public static List<TaskItem> queryOverdue(Collection<TaskItem> source, long now) {
        List<TaskItem> result = new ArrayList<>();
        if (source != null) {
            for (TaskItem task : source) {
                if (task == null || task.getStatus().isTerminal()) continue;
                long scheduledBoundary = task.getScheduledEndAt() > 0
                        ? task.getScheduledEndAt() : task.getScheduledStartAt();
                boolean pastDue = task.getDueAt() > 0 && task.getDueAt() < now;
                boolean missedSchedule = scheduledBoundary > 0 && scheduledBoundary < now
                        && task.getStatus() != TaskItem.Status.IN_PROGRESS
                        && task.getStatus() != TaskItem.Status.AWAITING_CONFIRMATION;
                if (pastDue || missedSchedule
                        || task.getStatus() == TaskItem.Status.NEEDS_RESCHEDULING) {
                    result.add(task.copy());
                }
            }
        }
        result.sort(Comparator.comparingLong(AppRepository::overdueSortTime));
        return Collections.unmodifiableList(result);
    }

    public static List<TaskItem> filterByProjectAndTags(
            Collection<TaskItem> source,
            String project,
            Collection<String> requiredTags
    ) {
        String normalizedProject = normalizeOptional(project);
        Set<String> tags = new HashSet<>();
        if (requiredTags != null) {
            for (String tag : requiredTags) {
                String normalized = normalizeOptional(tag);
                if (normalized != null) tags.add(normalized);
            }
        }
        List<TaskItem> result = new ArrayList<>();
        if (source != null) {
            for (TaskItem task : source) {
                if (task == null) continue;
                if (normalizedProject != null && !normalizedProject.equals(task.getProject())) continue;
                if (!task.getTags().containsAll(tags)) continue;
                result.add(task.copy());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private void loadOrMigrate() {
        synchronized (lock) {
            if (TimeAssistantCoreStore.MIGRATION_RECOVERY_REQUIRED.equals(
                    coreStore.getMigrationState())) {
                lastLoadError = "Core database requires recovery. Import a valid backup or clear data.";
                writesBlockedByCorruption = true;
                publishStateLocked();
                return;
            }
            try {
                JSONObject coreSnapshot = coreStore.loadSnapshot();
                if (coreSnapshot != null) {
                    restoreSnapshotLocked(parseSnapshot(coreSnapshot));
                    revision = coreStore.getRevision();
                    publishStateLocked();
                    return;
                }
            } catch (JSONException | IllegalArgumentException coreError) {
                try {
                    JSONObject backup = coreStore.loadBackupSnapshot();
                    if (backup != null) {
                        restoreSnapshotLocked(parseSnapshot(backup));
                        lastLoadError = "Core database data was invalid; recovered from backup: "
                                + coreError.getMessage();
                        revision = coreStore.replaceSnapshot(backup);
                        publishStateLocked();
                        return;
                    }
                } catch (JSONException | IllegalArgumentException backupError) {
                    lastLoadError = "Core database and backup are invalid. Primary: "
                            + coreError.getMessage() + "; backup: " + backupError.getMessage();
                }
                coreStore.markRecoveryRequired();
                writesBlockedByCorruption = true;
                publishStateLocked();
                return;
            }
            migrateSharedPreferencesSnapshotLocked();
            publishStateLocked();
        }
    }

    /** Imports the old JSON once. It is intentionally never used after a core state row exists. */
    private void migrateSharedPreferencesSnapshotLocked() {
        String primary = preferences.getString(KEY_SNAPSHOT, null);
        if (primary == null) {
            migrateLegacyLocked();
            return;
        }
        try {
            JSONObject primarySnapshot = new JSONObject(primary);
            restoreSnapshotLocked(parseSnapshot(primarySnapshot));
            revision = coreStore.replaceSnapshot(primarySnapshot);
            clearLegacySnapshotKeys();
            return;
        } catch (JSONException | IllegalArgumentException primaryError) {
            String backup = preferences.getString(KEY_BACKUP, null);
            if (backup != null) {
                try {
                    JSONObject backupSnapshot = new JSONObject(backup);
                    restoreSnapshotLocked(parseSnapshot(backupSnapshot));
                    revision = coreStore.replaceSnapshot(backupSnapshot);
                    clearLegacySnapshotKeys();
                    lastLoadError = "Primary repository data was invalid; recovered from backup: "
                            + primaryError.getMessage();
                    return;
                } catch (JSONException | IllegalArgumentException backupError) {
                    lastLoadError = "Repository and backup are invalid. Primary: "
                            + primaryError.getMessage() + "; backup: " + backupError.getMessage();
                }
            } else {
                lastLoadError = "Repository data is invalid and no backup exists: "
                        + primaryError.getMessage();
            }
        }
        coreStore.markRecoveryRequired();
        writesBlockedByCorruption = true;
    }

    private void clearLegacySnapshotKeys() {
        // The Room commit is already verified. Leaving the legacy copy behind after a failed
        // cleanup is safe because every later launch prefers the core state row.
        preferences.edit().remove(KEY_SNAPSHOT).remove(KEY_BACKUP).commit();
    }

    private void migrateLegacyLocked() {
        String raw = legacyPreferences.getString(LEGACY_EVENTS_KEY, null);
        if (raw == null || raw.trim().isEmpty()) {
            persistLocked();
            return;
        }
        int invalidCount = 0;
        try {
            JSONArray events = new JSONArray(raw);
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) {
                    invalidCount++;
                    continue;
                }
                try {
                    JSONObject taskJson = new JSONObject();
                    long startAt = event.getLong("startAt");
                    int duration = Math.max(0, event.optInt("durationMinutes", 0));
                    taskJson.put("id", event.optString("id", "legacy-" + i + "-" + startAt));
                    taskJson.put("title", event.getString("title"));
                    taskJson.put("description", event.optString("category", ""));
                    taskJson.put("status", TaskItem.Status.SCHEDULED.storageValue());
                    taskJson.put("priority", TaskItem.PRIORITY_NORMAL);
                    taskJson.put("estimatedDurationMinutes", duration);
                    taskJson.put("scheduledStartAt", startAt);
                    taskJson.put("scheduledEndAt", startAt + duration * 60_000L);
                    taskJson.put("source", TaskItem.SOURCE_LEGACY_SCHEDULE);
                    taskJson.put("createdAt", startAt);
                    taskJson.put("updatedAt", startAt);
                    TaskItem migrated = TaskItem.fromJson(taskJson);
                    tasks.put(migrated.getId(), migrated);
                } catch (JSONException | IllegalArgumentException itemError) {
                    invalidCount++;
                }
            }
            persistLocked();
            if (invalidCount > 0) {
                lastLoadError = "Migrated legacy schedule with " + invalidCount + " invalid item(s) skipped";
            }
        } catch (JSONException exception) {
            lastLoadError = "Legacy schedule could not be parsed: " + exception.getMessage();
            coreStore.markRecoveryRequired();
            writesBlockedByCorruption = true;
        }
    }

    private void mutate(Change change, boolean allowRecovery, Mutation mutation) {
        List<ChangeListener> listenerSnapshot;
        synchronized (lock) {
            if (writesBlockedByCorruption && !allowRecovery) {
                throw new RepositoryException("writes are blocked because persisted data is corrupt; import or clear explicitly");
            }
            if (proposalChanges != null) {
                Snapshot before = snapshotCopyLocked();
                try {
                    mutation.run();
                    enforceInvariantsLocked();
                } catch (RuntimeException | Error failure) {
                    restoreSnapshotLocked(before);
                    throw failure;
                }
                proposalChanges.add(change);
                return;
            }
            Snapshot before = snapshotCopyLocked();
            boolean wasBlocked = writesBlockedByCorruption;
            String previousError = lastLoadError;
            try {
                mutation.run();
                enforceInvariantsLocked();
                persistLocked();
                publishStateLocked();
            } catch (RuntimeException exception) {
                restoreSnapshotLocked(before);
                writesBlockedByCorruption = wasBlocked;
                lastLoadError = previousError;
                throw exception;
            }
            listenerSnapshot = new ArrayList<>(listeners);
        }
        notifyChanges(Collections.singletonList(change), listenerSnapshot);
    }

    private void notifyChanges(List<Change> changes, List<ChangeListener> listenerSnapshot) {
        for (Change change : changes) for (ChangeListener listener : listenerSnapshot) {
            try {
                listener.onRepositoryChanged(change);
            } catch (RuntimeException ignored) {
                // A UI listener must not roll back already committed repository data.
            }
        }
    }

    /**
     * Keeps the task/focus relationship valid regardless of which UI, AI action, import, or sync
     * path performed a mutation. A focus timer must never remain attached to a deleted or terminal
     * task.
     */
    private void enforceInvariantsLocked() {
        if (currentFocus == null) {
            currentFocus = FocusState.idle();
            return;
        }
        if (currentFocus.getMode() != FocusState.Mode.FOCUS
                || currentFocus.getState() == FocusState.State.IDLE) {
            return;
        }
        TaskItem focusedTask = currentFocus.getTaskId() == null
                ? null : tasks.get(currentFocus.getTaskId());
        if (hasInvalidFocusBinding(currentFocus, focusedTask)) {
            currentFocus = FocusState.idle();
        }
    }

    static boolean hasInvalidFocusBinding(FocusState focus, TaskItem focusedTask) {
        if (focus == null || focus.getMode() != FocusState.Mode.FOCUS
                || focus.getState() == FocusState.State.IDLE) return false;
        // Null is a valid, deliberate no-task focus session—not a missing task relationship.
        if (focus.getTaskId() == null) return false;
        return focusedTask == null || focusedTask.getStatus().isTerminal();
    }

    private void persistLocked() {
        if (testCommit != null) {
            testCommit.run();
            revision++;
            return;
        }
        final String raw;
        try {
            // API keys are owned by SecureApiKeyStore; never write them to the ordinary
            // SharedPreferences snapshot, including after an import or a legacy migration.
            raw = buildSnapshotJsonLocked(false, System.currentTimeMillis()).toString();
        } catch (JSONException exception) {
            throw new RepositoryException("could not serialize repository", exception);
        }
        revision = executeOnIoBlocking(() -> {
            try {
                return coreStore.replaceSnapshot(new JSONObject(raw));
            } catch (JSONException exception) {
                throw new RepositoryException("could not persist core repository", exception);
            }
        });
    }

    private void publishStateLocked() {
        if (testCommit != null) return;
        observableState.postValue(new AppState(revision, new ArrayList<>(tasks.values()),
                new ArrayList<>(recurringGroups.values()), new ArrayList<>(recurringOverrides.values()),
                new ArrayList<>(reminderEvents.values()), new ArrayList<>(chatMessages),
                new ArrayList<>(focusRecords), settings, currentFocus, lastLoadError,
                writesBlockedByCorruption));
    }

    private <T> T executeOnIoBlocking(Callable<T> operation) {
        if (Thread.currentThread().getName().startsWith("TimeAssistantRepositoryIO")) {
            try {
                return operation.call();
            } catch (RepositoryException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new RepositoryException("repository I/O failed", exception);
            }
        }
        Future<T> future = ioExecutor.submit(operation);
        boolean interrupted = false;
        try {
            // A submitted commit cannot be cancelled safely: observe its outcome before rollback.
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RepositoryException) {
                throw (RepositoryException) cause;
            }
            throw new RepositoryException("repository I/O failed", cause);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private JSONObject buildSnapshotJsonLocked(boolean includeApiKey, long snapshotAt) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("exportedAt", snapshotAt);
        JSONArray taskArray = new JSONArray();
        for (TaskItem task : tasks.values()) taskArray.put(task.toJson());
        root.put("tasks", taskArray);
        JSONArray recurringArray = new JSONArray();
        for (RecurringTaskGroup group : recurringGroups.values()) {
            recurringArray.put(group.toJson());
        }
        root.put("recurringTaskGroups", recurringArray);
        JSONArray overrideArray = new JSONArray();
        for (RecurringOccurrenceOverride override : recurringOverrides.values()) {
            overrideArray.put(override.toJson());
        }
        root.put("recurringOccurrenceOverrides", overrideArray);
        JSONArray reminderArray = new JSONArray();
        for (ReminderEvent event : reminderEvents.values()) reminderArray.put(event.toJson());
        root.put("reminderEvents", reminderArray);
        JSONArray tombstones = new JSONArray();
        List<String> deletedTaskIds = new ArrayList<>(taskTombstones.keySet());
        Collections.sort(deletedTaskIds);
        for (String id : deletedTaskIds) {
            long deletedAt = taskTombstones.get(id);
            JSONObject tombstone = new JSONObject();
            tombstone.put("id", id);
            tombstone.put("deletedAt", deletedAt);
            tombstone.put("updatedAt", deletedAt);
            tombstones.put(tombstone);
        }
        root.put("taskTombstones", tombstones);
        JSONArray recurringTombstones = new JSONArray();
        List<String> deletedGroupIds = new ArrayList<>(recurringGroupTombstones.keySet());
        Collections.sort(deletedGroupIds);
        for (String id : deletedGroupIds) {
            long deletedAt = recurringGroupTombstones.get(id);
            JSONObject tombstone = new JSONObject();
            tombstone.put("id", id);
            tombstone.put("deletedAt", deletedAt);
            tombstone.put("updatedAt", deletedAt);
            recurringTombstones.put(tombstone);
        }
        root.put("recurringTaskGroupTombstones", recurringTombstones);
        JSONArray overrideTombstones = new JSONArray();
        List<String> deletedOverrideIds = new ArrayList<>(recurringOverrideTombstones.keySet());
        Collections.sort(deletedOverrideIds);
        for (String id : deletedOverrideIds) {
            long deletedAt = recurringOverrideTombstones.get(id);
            JSONObject tombstone = new JSONObject();
            tombstone.put("id", id);
            tombstone.put("deletedAt", deletedAt);
            tombstone.put("updatedAt", deletedAt);
            overrideTombstones.put(tombstone);
        }
        root.put("recurringOccurrenceOverrideTombstones", overrideTombstones);
        JSONArray messages = new JSONArray();
        for (ChatMessage message : chatMessages) messages.put(message.toJson());
        root.put("chatMessages", messages);
        JSONArray history = new JSONArray();
        for (FocusRecord record : focusRecords) history.put(record.toJson());
        root.put("focusRecords", history);
        root.put("currentFocus", currentFocus.snapshotAt(snapshotAt).toJson());
        root.put("settings", settings.toJson(includeApiKey));
        return root;
    }

    private static Snapshot parseSnapshot(JSONObject root) throws JSONException {
        int version = root.optInt("schemaVersion", 1);
        if (version < 1 || version > SCHEMA_VERSION) {
            throw new JSONException("unsupported schemaVersion: " + version);
        }
        Snapshot snapshot = new Snapshot();
        JSONArray taskArray = root.optJSONArray("tasks");
        if (taskArray != null) {
            for (int i = 0; i < taskArray.length(); i++) {
                JSONObject item = taskArray.optJSONObject(i);
                if (item == null) throw new JSONException("tasks[" + i + "] is not an object");
                try {
                    TaskItem task = TaskItem.fromJson(item);
                    TaskItem existing = snapshot.tasks.get(task.getId());
                    if (existing == null || task.getUpdatedAt() >= existing.getUpdatedAt()) {
                        snapshot.tasks.put(task.getId(), task);
                    }
                } catch (JSONException exception) {
                    throw new JSONException("tasks[" + i + "]: " + exception.getMessage());
                }
            }
        }
        JSONArray recurringArray = root.optJSONArray("recurringTaskGroups");
        if (recurringArray != null) {
            for (int i = 0; i < recurringArray.length(); i++) {
                JSONObject item = recurringArray.optJSONObject(i);
                if (item == null) {
                    throw new JSONException("recurringTaskGroups[" + i + "] is not an object");
                }
                try {
                    RecurringTaskGroup group = RecurringTaskGroup.fromJson(item);
                    RecurringTaskGroup existing = snapshot.recurringGroups.get(group.getId());
                    if (existing == null || group.getUpdatedAt() >= existing.getUpdatedAt()) {
                        snapshot.recurringGroups.put(group.getId(), group);
                    }
                } catch (JSONException exception) {
                    throw new JSONException("recurringTaskGroups[" + i + "]: "
                            + exception.getMessage());
                }
            }
        }
        JSONArray overrideArray = root.optJSONArray("recurringOccurrenceOverrides");
        if (overrideArray != null) {
            for (int i = 0; i < overrideArray.length(); i++) {
                JSONObject item = overrideArray.optJSONObject(i);
                if (item == null) {
                    throw new JSONException("recurringOccurrenceOverrides[" + i
                            + "] is not an object");
                }
                try {
                    RecurringOccurrenceOverride override =
                            RecurringOccurrenceOverride.fromJson(item);
                    RecurringOccurrenceOverride existing =
                            snapshot.recurringOverrides.get(override.getId());
                    if (existing == null || override.getUpdatedAt() >= existing.getUpdatedAt()) {
                        snapshot.recurringOverrides.put(override.getId(), override);
                    }
                } catch (JSONException exception) {
                    throw new JSONException("recurringOccurrenceOverrides[" + i + "]: "
                            + exception.getMessage());
                }
            }
        }
        JSONArray reminderArray = root.optJSONArray("reminderEvents");
        if (reminderArray != null) {
            for (int i = 0; i < reminderArray.length(); i++) {
                JSONObject item = reminderArray.optJSONObject(i);
                if (item == null) {
                    throw new JSONException("reminderEvents[" + i + "] is not an object");
                }
                try {
                    ReminderEvent event = ReminderEvent.fromJson(item);
                    ReminderEvent existing = snapshot.reminderEvents.get(event.getId());
                    if (existing == null || event.getUpdatedAt() >= existing.getUpdatedAt()) {
                        snapshot.reminderEvents.put(event.getId(), event);
                    }
                } catch (JSONException exception) {
                    throw new JSONException("reminderEvents[" + i + "]: "
                            + exception.getMessage());
                }
            }
        }
        JSONArray tombstones = root.optJSONArray("taskTombstones");
        if (tombstones != null) {
            for (int i = 0; i < tombstones.length(); i++) {
                JSONObject item = tombstones.optJSONObject(i);
                if (item == null) {
                    throw new JSONException("taskTombstones[" + i + "] is not an object");
                }
                String id = item.optString("id", "").trim();
                long deletedAt = Math.max(
                        item.optLong("deletedAt", 0),
                        item.optLong("updatedAt", 0)
                );
                if (id.isEmpty() || deletedAt <= 0) {
                    throw new JSONException("taskTombstones[" + i + "] requires id and deletedAt");
                }
                Long existing = snapshot.taskTombstones.get(id);
                if (existing == null || deletedAt > existing) {
                    snapshot.taskTombstones.put(id, deletedAt);
                }
            }
        }
        JSONArray recurringTombstones = root.optJSONArray("recurringTaskGroupTombstones");
        if (recurringTombstones != null) {
            for (int i = 0; i < recurringTombstones.length(); i++) {
                JSONObject item = recurringTombstones.optJSONObject(i);
                if (item == null) {
                    throw new JSONException("recurringTaskGroupTombstones[" + i
                            + "] is not an object");
                }
                String id = item.optString("id", "").trim();
                long deletedAt = Math.max(item.optLong("deletedAt", 0),
                        item.optLong("updatedAt", 0));
                if (id.isEmpty() || deletedAt <= 0) {
                    throw new JSONException("recurringTaskGroupTombstones[" + i
                            + "] requires id and deletedAt");
                }
                Long existing = snapshot.recurringGroupTombstones.get(id);
                if (existing == null || deletedAt > existing) {
                    snapshot.recurringGroupTombstones.put(id, deletedAt);
                }
            }
        }
        JSONArray overrideTombstones =
                root.optJSONArray("recurringOccurrenceOverrideTombstones");
        if (overrideTombstones != null) {
            for (int i = 0; i < overrideTombstones.length(); i++) {
                JSONObject item = overrideTombstones.optJSONObject(i);
                if (item == null) {
                    throw new JSONException("recurringOccurrenceOverrideTombstones[" + i
                            + "] is not an object");
                }
                String id = item.optString("id", "").trim();
                long deletedAt = Math.max(item.optLong("deletedAt", 0),
                        item.optLong("updatedAt", 0));
                if (id.isEmpty() || deletedAt <= 0) {
                    throw new JSONException("recurringOccurrenceOverrideTombstones[" + i
                            + "] requires id and deletedAt");
                }
                Long existing = snapshot.recurringOverrideTombstones.get(id);
                if (existing == null || deletedAt > existing) {
                    snapshot.recurringOverrideTombstones.put(id, deletedAt);
                }
            }
        }
        reconcileSnapshotTasks(snapshot);
        reconcileSnapshotRecurringGroups(snapshot);
        reconcileSnapshotRecurringOverrides(snapshot);
        // Accept both the local export names and the canonical LAN sync names.  This keeps
        // imported desktop snapshots interoperable without changing the on-device schema.
        JSONArray messages = root.optJSONArray("chatMessages");
        if (messages == null) messages = root.optJSONArray("chats");
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject item = messages.optJSONObject(i);
                if (item == null) throw new JSONException("chatMessages[" + i + "] is not an object");
                try {
                    snapshot.chatMessages.add(ChatMessage.fromJson(item));
                } catch (JSONException exception) {
                    throw new JSONException("chatMessages[" + i + "]: " + exception.getMessage());
                }
            }
        }
        snapshot.chatMessages.sort(Comparator.comparingLong(ChatMessage::getCreatedAt));
        JSONArray history = root.optJSONArray("focusRecords");
        if (history == null) history = root.optJSONArray("focusHistory");
        if (history != null) {
            for (int i = 0; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item == null) throw new JSONException("focusRecords[" + i + "] is not an object");
                try {
                    snapshot.focusRecords.add(FocusRecord.fromJson(item));
                } catch (JSONException exception) {
                    throw new JSONException("focusRecords[" + i + "]: " + exception.getMessage());
                }
            }
        }
        snapshot.focusRecords.sort(Comparator.comparingLong(FocusRecord::getCompletedAt));
        JSONObject currentFocusJson = root.optJSONObject("currentFocus");
        snapshot.currentFocusPresent = currentFocusJson != null;
        snapshot.currentFocus = currentFocusJson == null
                ? FocusState.idle() : FocusState.fromJson(currentFocusJson);
        JSONObject settingsJson = root.optJSONObject("settings");
        snapshot.settingsPresent = settingsJson != null;
        if (settingsJson == null) {
            snapshot.settings = new AppSettings();
            snapshot.settingsContainsApiKey = false;
        } else {
            snapshot.settings = AppSettings.fromJson(settingsJson);
            snapshot.settingsContainsApiKey = AppSettings.containsApiKey(settingsJson);
        }
        return snapshot;
    }

    private Snapshot snapshotCopyLocked() {
        Snapshot snapshot = new Snapshot();
        for (TaskItem task : tasks.values()) snapshot.tasks.put(task.getId(), task.copy());
        snapshot.taskTombstones.putAll(taskTombstones);
        for (RecurringTaskGroup group : recurringGroups.values()) {
            snapshot.recurringGroups.put(group.getId(), group.copy());
        }
        snapshot.recurringGroupTombstones.putAll(recurringGroupTombstones);
        for (RecurringOccurrenceOverride override : recurringOverrides.values()) {
            snapshot.recurringOverrides.put(override.getId(), override.copy());
        }
        snapshot.recurringOverrideTombstones.putAll(recurringOverrideTombstones);
        for (ReminderEvent event : reminderEvents.values()) {
            snapshot.reminderEvents.put(event.getId(), event.copy());
        }
        snapshot.chatMessages.addAll(chatMessages);
        snapshot.focusRecords.addAll(focusRecords);
        snapshot.settings = settings.copy();
        snapshot.settingsContainsApiKey = true;
        snapshot.settingsPresent = true;
        snapshot.currentFocus = currentFocus;
        snapshot.currentFocusPresent = true;
        return snapshot;
    }

    private void restoreSnapshotLocked(Snapshot snapshot) {
        tasks.clear();
        for (TaskItem task : snapshot.tasks.values()) tasks.put(task.getId(), task.copy());
        taskTombstones.clear();
        taskTombstones.putAll(snapshot.taskTombstones);
        recurringGroups.clear();
        for (RecurringTaskGroup group : snapshot.recurringGroups.values()) {
            recurringGroups.put(group.getId(), group.copy());
        }
        recurringGroupTombstones.clear();
        recurringGroupTombstones.putAll(snapshot.recurringGroupTombstones);
        recurringOverrides.clear();
        for (RecurringOccurrenceOverride override : snapshot.recurringOverrides.values()) {
            recurringOverrides.put(override.getId(), override.copy());
        }
        recurringOverrideTombstones.clear();
        recurringOverrideTombstones.putAll(snapshot.recurringOverrideTombstones);
        reminderEvents.clear();
        for (ReminderEvent event : snapshot.reminderEvents.values()) {
            reminderEvents.put(event.getId(), event.copy());
        }
        chatMessages.clear();
        chatMessages.addAll(snapshot.chatMessages);
        focusRecords.clear();
        focusRecords.addAll(snapshot.focusRecords);
        settings = snapshot.settings.copy();
        currentFocus = snapshot.currentFocus == null ? FocusState.idle() : snapshot.currentFocus;
    }

    private void mergeSnapshotLocked(Snapshot incoming, String existingApiKey) {
        for (Map.Entry<String, Long> tombstone : incoming.taskTombstones.entrySet()) {
            Long current = taskTombstones.get(tombstone.getKey());
            if (current == null || tombstone.getValue() > current) {
                taskTombstones.put(tombstone.getKey(), tombstone.getValue());
            }
        }
        for (TaskItem task : incoming.tasks.values()) {
            TaskItem current = tasks.get(task.getId());
            if (current == null || task.getUpdatedAt() >= current.getUpdatedAt()) {
                tasks.put(task.getId(), task.copy());
            }
        }
        reconcileTaskTombstonesLocked();
        for (Map.Entry<String, Long> tombstone
                : incoming.recurringGroupTombstones.entrySet()) {
            Long current = recurringGroupTombstones.get(tombstone.getKey());
            if (current == null || tombstone.getValue() > current) {
                recurringGroupTombstones.put(tombstone.getKey(), tombstone.getValue());
            }
        }
        for (RecurringTaskGroup group : incoming.recurringGroups.values()) {
            RecurringTaskGroup current = recurringGroups.get(group.getId());
            if (current == null || group.getUpdatedAt() >= current.getUpdatedAt()) {
                recurringGroups.put(group.getId(), group.copy());
            }
        }
        reconcileRecurringGroupTombstonesLocked();
        for (Map.Entry<String, Long> tombstone
                : incoming.recurringOverrideTombstones.entrySet()) {
            Long current = recurringOverrideTombstones.get(tombstone.getKey());
            if (current == null || tombstone.getValue() > current) {
                recurringOverrideTombstones.put(tombstone.getKey(), tombstone.getValue());
            }
        }
        for (RecurringOccurrenceOverride override : incoming.recurringOverrides.values()) {
            RecurringOccurrenceOverride current = recurringOverrides.get(override.getId());
            if (current == null || override.getUpdatedAt() >= current.getUpdatedAt()) {
                recurringOverrides.put(override.getId(), override.copy());
            }
        }
        reconcileRecurringOverrideTombstonesLocked();
        for (ReminderEvent event : incoming.reminderEvents.values()) {
            ReminderEvent current = reminderEvents.get(event.getId());
            if (current == null || event.getUpdatedAt() >= current.getUpdatedAt()) {
                reminderEvents.put(event.getId(), event.copy());
            }
        }
        Map<String, ChatMessage> mergedMessages = new LinkedHashMap<>();
        for (ChatMessage message : chatMessages) mergedMessages.put(message.getId(), message);
        for (ChatMessage message : incoming.chatMessages) {
            ChatMessage current = mergedMessages.get(message.getId());
            if (current == null || message.getUpdatedAt() >= current.getUpdatedAt()) {
                mergedMessages.put(message.getId(), message);
            }
        }
        chatMessages.clear();
        chatMessages.addAll(mergedMessages.values());
        chatMessages.sort(Comparator.comparingLong(ChatMessage::getCreatedAt));

        Map<String, FocusRecord> mergedRecords = new LinkedHashMap<>();
        for (FocusRecord record : focusRecords) mergedRecords.put(record.getId(), record);
        for (FocusRecord record : incoming.focusRecords) mergedRecords.put(record.getId(), record);
        focusRecords.clear();
        focusRecords.addAll(mergedRecords.values());
        focusRecords.sort(Comparator.comparingLong(FocusRecord::getCompletedAt));

        if (incoming.settingsPresent && incoming.settings.getUpdatedAt() >= settings.getUpdatedAt()) {
            settings = incoming.settings.copy();
            settings.preserveLocalApiKey(existingApiKey);
        }
        if (incoming.currentFocusPresent && incoming.currentFocus != null
                && incoming.currentFocus.getUpdatedAt() >= currentFocus.getUpdatedAt()) {
            currentFocus = incoming.currentFocus;
        }
    }

    /**
     * REPLACE is an explicit local restore/import, unlike an automatic sync merge. Tasks restored
     * over a newer local tombstone are marked newer so undo remains effective on every peer;
     * tasks intentionally omitted by the replacement receive tombstones of their own.
     */
    private void reconcileExplicitReplacementLocked(Snapshot localBeforeImport) {
        long now = System.currentTimeMillis();
        for (TaskItem previous : localBeforeImport.tasks.values()) {
            if (tasks.containsKey(previous.getId())) continue;
            long previousDeletion = taskTombstones.containsKey(previous.getId())
                    ? taskTombstones.get(previous.getId()) : 0L;
            long deletedAt = Math.max(now,
                    Math.max(nextVersion(previousDeletion), nextVersion(previous.getUpdatedAt())));
            taskTombstones.put(previous.getId(), deletedAt);
        }
        for (TaskItem restored : tasks.values()) {
            Long previousDeletion = localBeforeImport.taskTombstones.get(restored.getId());
            if (previousDeletion != null && restored.getUpdatedAt() <= previousDeletion) {
                restored.markUpdatedAfter(previousDeletion);
                taskTombstones.remove(restored.getId());
            }
        }
        reconcileTaskTombstonesLocked();
        for (RecurringTaskGroup previous : localBeforeImport.recurringGroups.values()) {
            if (recurringGroups.containsKey(previous.getId())) continue;
            long previousDeletion = recurringGroupTombstones.containsKey(previous.getId())
                    ? recurringGroupTombstones.get(previous.getId()) : 0L;
            long deletedAt = Math.max(now,
                    Math.max(nextVersion(previousDeletion),
                            nextVersion(previous.getUpdatedAt())));
            recurringGroupTombstones.put(previous.getId(), deletedAt);
        }
        for (RecurringTaskGroup restored : recurringGroups.values()) {
            Long previousDeletion = localBeforeImport.recurringGroupTombstones
                    .get(restored.getId());
            if (previousDeletion != null && restored.getUpdatedAt() <= previousDeletion) {
                restored.markUpdatedAfter(previousDeletion);
                recurringGroupTombstones.remove(restored.getId());
            }
        }
        reconcileRecurringGroupTombstonesLocked();
        for (RecurringOccurrenceOverride previous
                : localBeforeImport.recurringOverrides.values()) {
            if (recurringOverrides.containsKey(previous.getId())) continue;
            long previousDeletion = recurringOverrideTombstones.containsKey(previous.getId())
                    ? recurringOverrideTombstones.get(previous.getId()) : 0L;
            long deletedAt = Math.max(now,
                    Math.max(nextVersion(previousDeletion),
                            nextVersion(previous.getUpdatedAt())));
            recurringOverrideTombstones.put(previous.getId(), deletedAt);
        }
        for (RecurringOccurrenceOverride restored : recurringOverrides.values()) {
            Long previousDeletion = localBeforeImport.recurringOverrideTombstones
                    .get(restored.getId());
            if (previousDeletion != null && restored.getUpdatedAt() <= previousDeletion) {
                restored.markUpdatedAfter(previousDeletion);
                recurringOverrideTombstones.remove(restored.getId());
            }
        }
        reconcileRecurringOverrideTombstonesLocked();
    }

    private void reconcileTaskTombstonesLocked() {
        List<String> ids = new ArrayList<>(taskTombstones.keySet());
        for (String id : ids) {
            TaskItem task = tasks.get(id);
            if (task == null) continue;
            long deletedAt = taskTombstones.get(id);
            if (deletedAt >= task.getUpdatedAt()) {
                tasks.remove(id);
            } else {
                taskTombstones.remove(id);
            }
        }
        repairTaskRelationships(tasks);
    }

    private void reconcileRecurringGroupTombstonesLocked() {
        List<String> ids = new ArrayList<>(recurringGroupTombstones.keySet());
        for (String id : ids) {
            RecurringTaskGroup group = recurringGroups.get(id);
            if (group == null) continue;
            long deletedAt = recurringGroupTombstones.get(id);
            if (deletedAt >= group.getUpdatedAt()) recurringGroups.remove(id);
            else recurringGroupTombstones.remove(id);
        }
    }

    private void reconcileRecurringOverrideTombstonesLocked() {
        List<String> ids = new ArrayList<>(recurringOverrideTombstones.keySet());
        for (String id : ids) {
            RecurringOccurrenceOverride override = recurringOverrides.get(id);
            if (override == null) continue;
            long deletedAt = recurringOverrideTombstones.get(id);
            if (deletedAt >= override.getUpdatedAt()) recurringOverrides.remove(id);
            else recurringOverrideTombstones.remove(id);
        }
        List<String> orphanIds = new ArrayList<>();
        for (RecurringOccurrenceOverride override : recurringOverrides.values()) {
            if (!recurringGroups.containsKey(override.getRecurringGroupId())) {
                orphanIds.add(override.getId());
            }
        }
        for (String id : orphanIds) recurringOverrides.remove(id);
    }

    private static void reconcileSnapshotTasks(Snapshot snapshot) {
        List<String> ids = new ArrayList<>(snapshot.taskTombstones.keySet());
        for (String id : ids) {
            TaskItem task = snapshot.tasks.get(id);
            if (task == null) continue;
            long deletedAt = snapshot.taskTombstones.get(id);
            if (deletedAt >= task.getUpdatedAt()) {
                snapshot.tasks.remove(id);
            } else {
                snapshot.taskTombstones.remove(id);
            }
        }
        repairTaskRelationships(snapshot.tasks);
    }

    private static void reconcileSnapshotRecurringGroups(Snapshot snapshot) {
        List<String> ids = new ArrayList<>(snapshot.recurringGroupTombstones.keySet());
        for (String id : ids) {
            RecurringTaskGroup group = snapshot.recurringGroups.get(id);
            if (group == null) continue;
            long deletedAt = snapshot.recurringGroupTombstones.get(id);
            if (deletedAt >= group.getUpdatedAt()) snapshot.recurringGroups.remove(id);
            else snapshot.recurringGroupTombstones.remove(id);
        }
    }

    private static void reconcileSnapshotRecurringOverrides(Snapshot snapshot) {
        List<String> ids = new ArrayList<>(snapshot.recurringOverrideTombstones.keySet());
        for (String id : ids) {
            RecurringOccurrenceOverride override = snapshot.recurringOverrides.get(id);
            if (override == null) continue;
            long deletedAt = snapshot.recurringOverrideTombstones.get(id);
            if (deletedAt >= override.getUpdatedAt()) snapshot.recurringOverrides.remove(id);
            else snapshot.recurringOverrideTombstones.remove(id);
        }
        List<String> orphanIds = new ArrayList<>();
        for (RecurringOccurrenceOverride override : snapshot.recurringOverrides.values()) {
            if (!snapshot.recurringGroups.containsKey(override.getRecurringGroupId())) {
                orphanIds.add(override.getId());
            }
        }
        for (String id : orphanIds) snapshot.recurringOverrides.remove(id);
    }

    private static void repairTaskRelationships(Map<String, TaskItem> taskMap) {
        for (TaskItem task : taskMap.values()) {
            String parentId = task.getParentTaskId();
            if (parentId != null && !taskMap.containsKey(parentId)) {
                task.setParentTaskId(null);
            }
            for (String childId : new ArrayList<>(task.getChildTaskIds())) {
                if (!taskMap.containsKey(childId)) task.removeChildTaskId(childId);
            }
        }
    }

    private static long nextVersion(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
    }

    private TaskItem requireTaskLocked(String id) {
        TaskItem task = tasks.get(id);
        if (task == null) throw new IllegalArgumentException("task not found: " + id);
        return task;
    }

    private void removeChatMessageLocked(String id) {
        chatMessages.removeIf(message -> message.getId().equals(id));
    }

    private boolean removeFocusRecordLocked(String id) {
        return focusRecords.removeIf(record -> record.getId().equals(id));
    }

    private static List<TaskItem> copyTasks(Collection<TaskItem> source) {
        List<TaskItem> result = new ArrayList<>();
        for (TaskItem task : source) result.add(task.copy());
        return Collections.unmodifiableList(result);
    }

    private static long startOfDay(long timestamp, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(timestamp);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static long addDays(long timestamp, int days, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(timestamp);
        calendar.add(Calendar.DAY_OF_YEAR, days);
        return calendar.getTimeInMillis();
    }

    private static boolean inRange(long value, long start, long end) {
        return value > 0 && value >= start && value < end;
    }

    private static long effectiveTaskTime(TaskItem task) {
        if (task.getScheduledStartAt() > 0) return task.getScheduledStartAt();
        if (task.getPlannedDateAt() > 0) return task.getPlannedDateAt();
        if (task.getScheduledEndAt() > 0) return task.getScheduledEndAt();
        return task.getDueAt();
    }

    private static long overdueSortTime(TaskItem task) {
        long result = Long.MAX_VALUE;
        if (task.getDueAt() > 0) result = Math.min(result, task.getDueAt());
        long schedule = task.getScheduledEndAt() > 0
                ? task.getScheduledEndAt() : task.getScheduledStartAt();
        if (schedule > 0) result = Math.min(result, schedule);
        return result;
    }

    private static long zeroLast(long value) {
        return value == 0 ? Long.MAX_VALUE : value;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static final Comparator<TaskItem> TASK_TIME_COMPARATOR = (first, second) -> {
        int time = Long.compare(zeroLast(effectiveTaskTime(first)), zeroLast(effectiveTaskTime(second)));
        if (time != 0) return time;
        int priority = Integer.compare(second.getPriority(), first.getPriority());
        if (priority != 0) return priority;
        return Long.compare(first.getCreatedAt(), second.getCreatedAt());
    };

    private static final class Snapshot {
        private final LinkedHashMap<String, TaskItem> tasks = new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> taskTombstones = new LinkedHashMap<>();
        private final LinkedHashMap<String, RecurringTaskGroup> recurringGroups =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> recurringGroupTombstones =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, RecurringOccurrenceOverride> recurringOverrides =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> recurringOverrideTombstones =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, ReminderEvent> reminderEvents =
                new LinkedHashMap<>();
        private final List<ChatMessage> chatMessages = new ArrayList<>();
        private final List<FocusRecord> focusRecords = new ArrayList<>();
        private AppSettings settings = new AppSettings();
        private boolean settingsContainsApiKey;
        private boolean settingsPresent;
        private FocusState currentFocus = FocusState.idle();
        private boolean currentFocusPresent;

        private Snapshot copy() {
            Snapshot copy = new Snapshot();
            for (TaskItem task : tasks.values()) copy.tasks.put(task.getId(), task.copy());
            copy.taskTombstones.putAll(taskTombstones);
            for (RecurringTaskGroup group : recurringGroups.values()) {
                copy.recurringGroups.put(group.getId(), group.copy());
            }
            copy.recurringGroupTombstones.putAll(recurringGroupTombstones);
            for (RecurringOccurrenceOverride override : recurringOverrides.values()) {
                copy.recurringOverrides.put(override.getId(), override.copy());
            }
            copy.recurringOverrideTombstones.putAll(recurringOverrideTombstones);
            for (ReminderEvent event : reminderEvents.values()) {
                copy.reminderEvents.put(event.getId(), event.copy());
            }
            copy.chatMessages.addAll(chatMessages);
            copy.focusRecords.addAll(focusRecords);
            copy.settings = settings.copy();
            copy.settingsContainsApiKey = settingsContainsApiKey;
            copy.settingsPresent = settingsPresent;
            copy.currentFocus = currentFocus;
            copy.currentFocusPresent = currentFocusPresent;
            return copy;
        }
    }
}
