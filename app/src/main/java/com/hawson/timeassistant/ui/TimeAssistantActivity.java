/**
 * 应用主界面
 *
 * 承载对话、日程、专注三大页面与设置，协调数据仓库、AI、同步与提醒等子系统。
 */
package com.hawson.timeassistant.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import androidx.appcompat.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.TransitionManager;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import android.provider.OpenableColumns;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.lifecycle.ViewModelProvider;

import com.hawson.timeassistant.R;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.AppGraph;
import com.hawson.timeassistant.settings.AiConnectionsView;
import com.hawson.timeassistant.settings.SettingsNavigationView;
import com.hawson.timeassistant.settings.PermissionGuideView;
import com.hawson.timeassistant.ui.settings.SystemPermissionGuideController;
import com.hawson.timeassistant.chat.ChatConnectionView;
import com.hawson.timeassistant.connections.AiProfile;
import com.hawson.timeassistant.design.AppearanceSettingsView;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.design.UiKit;
import com.hawson.timeassistant.design.FormDraftGuard;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.hawson.timeassistant.navigation.GuideRegistry;
import com.hawson.timeassistant.navigation.GuideTarget;
import com.hawson.timeassistant.navigation.TutorialFeature;
import com.hawson.timeassistant.ai.AiClient;
import com.hawson.timeassistant.ai.AiTransportPolicy;
import com.hawson.timeassistant.ai.AiPlan;
import com.hawson.timeassistant.ai.TaskActionExecutor;
import com.hawson.timeassistant.ai.learning.PreferenceLearningCoordinator;
import com.hawson.timeassistant.ai.runtime.AgentRuntime;
import com.hawson.timeassistant.ai.runtime.ContextBroker;
import com.hawson.timeassistant.ai.runtime.PolicyEngine;
import com.hawson.timeassistant.ai.runtime.PromptCompiler;
import com.hawson.timeassistant.data.AiErrorLogStore;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.data.AppState;
import com.hawson.timeassistant.data.SecureApiKeyStore;
import com.hawson.timeassistant.data.SyncSnapshotGateway;
import com.hawson.timeassistant.data.local.BehaviorEventEntity;
import com.hawson.timeassistant.data.local.BehaviorEventRecorder;
import com.hawson.timeassistant.data.local.LocalIntegrationSettingsStore;
import com.hawson.timeassistant.data.local.AiEndpointTrustStore;
import com.hawson.timeassistant.data.local.NotificationPrivacyStore;
import com.hawson.timeassistant.data.local.LocalIntelligenceStore;
import com.hawson.timeassistant.data.local.PreferenceRuleEntity;
import com.hawson.timeassistant.data.local.ToolExecutionEntity;
import com.hawson.timeassistant.databinding.ActivityTimeAssistantBinding;
import com.hawson.timeassistant.databinding.PageChatBinding;
import com.hawson.timeassistant.databinding.PageFocusBinding;
import com.hawson.timeassistant.databinding.PageScheduleBinding;
import com.hawson.timeassistant.databinding.PageSettingsBinding;
import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.ChatSessionPolicy;
import com.hawson.timeassistant.domain.FocusRecord;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.RecurringOccurrenceOverride;
import com.hawson.timeassistant.domain.RecurringTaskGroup;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.domain.TodoDatePolicy;
import com.hawson.timeassistant.focus.AmbientSoundPlayer;
import com.hawson.timeassistant.focus.FocusCompletionUseCase;
import com.hawson.timeassistant.focus.FocusStageSignal;
import com.hawson.timeassistant.focus.FocusTimerService;
import com.hawson.timeassistant.integration.calendar.CalendarAccessPolicy;
import com.hawson.timeassistant.integration.calendar.CalendarBusyReader;
import com.hawson.timeassistant.integration.calendar.CalendarEventComposer;
import com.hawson.timeassistant.reminder.ReminderScheduler;
import com.hawson.timeassistant.proactive.ProactiveMessageBus;
import com.hawson.timeassistant.proactive.ProactiveNotification;
import com.hawson.timeassistant.proactive.ProactiveRoutine;
import com.hawson.timeassistant.proactive.ProactiveScheduleCoordinator;
import com.hawson.timeassistant.proactive.ProactiveSettings;
import com.hawson.timeassistant.sync.DeviceSyncManager;
import com.hawson.timeassistant.ui.widget.TimerRingView;
import com.hawson.timeassistant.ui.schedule.TaskRowControls;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The functional application shell. Stable page structure lives in XML and is accessed through
 * ViewBinding; this activity coordinates repository state and renders only data-driven content.
 */
@SuppressLint("SetTextI18n")
public class TimeAssistantActivity extends AppCompatActivity {
    private static final String STATE_SETTINGS_VISIBLE = "settings_visible";
    private static final String STATE_SELECTED_CHAT_DATE = "selected_chat_date";
    private static final String PREF_ACTIVE_CHAT_DAY = "active_chat_day";
    private static final String PREF_LAST_APP_ACTIVITY_AT = "last_app_activity_at";
    private static final String PREF_FOCUS_LOCKSCREEN_SETTINGS_CONFIRMED =
            "focus_lockscreen_settings_confirmed_v2";
    private static final String PREF_FOCUS_LOCKSCREEN_SETTINGS_RETURN_PENDING =
            "focus_lockscreen_settings_return_pending";
    protected static final int PAGE_CHAT = 0;
    protected static final int PAGE_SCHEDULE = 1;
    protected static final int PAGE_FOCUS = 2;
    private static final int MAX_ATTACHMENTS = 4;
    private static final int MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOTAL_ATTACHMENT_BYTES = 16 * 1024 * 1024;
    private static final String[] TODAY_MOTIVATION_FALLBACKS = {
            "今天没有硬性安排，留白也是一种从容。",
            "先完成最重要的一件事，今天就有了支点。",
            "安排不多，给每件事留一点完整的注意力。",
            "节奏刚刚好，按顺序推进就可以。",
            "任务之间留一点空隙，转换会更轻松。",
            "上午适合启动，把最需要思考的事放在前面。",
            "午后先做一个小步骤，状态会慢慢回来。",
            "傍晚适合收尾，不必再给今天增加负担。",
            "安排较满，完成关键部分比全部做完更重要。",
            "时间块比较长，记得在中间站起来活动一下。",
            "每完成一项，就让今天变得更清晰一点。",
            "保持自己的节奏，稳定推进已经很好。"
    };

    protected int PRIMARY;
    protected int PRIMARY_SOFT;
    protected int ACCENT;
    protected int ACCENT_SOFT;
    protected int INSIGHT;
    protected int INSIGHT_SOFT;
    protected int SURFACE;
    protected int TEXT;
    protected int TEXT_MUTED;
    private int NEUTRAL;
    private int ERROR;
    private int ERROR_SOFT;
    private int WARNING;
    private int WARNING_SOFT;
    private int ON_BRAND;
    private static final int TASK_ACTION_GRAY = Color.rgb(116, 118, 132);
    private static final int TASK_ACTION_GREEN = Color.rgb(47, 158, 122);
    private static final int TASK_ACTION_YELLOW = Color.rgb(199, 139, 31);
    protected int BORDER;
    protected static final Locale LOCALE = Locale.CHINA;

    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    protected AppRepository repository;
    private AppGraph appGraph;
    private AiConnectionsView aiSettingsFeature;
    private SettingsNavigationView settingsNavigation;
    private TutorialFeature tutorials;
    private final GuideRegistry guideRegistry = new GuideRegistry();
    private FocusCompletionUseCase focusCompletionUseCase;
    protected SecureApiKeyStore secureApiKeyStore;
    protected AiClient aiClient;
    private String aiClientBoundary = "";
    private final Runnable aiConnectionChanged = this::onAiConnectionChanged;
    private final com.hawson.timeassistant.auth.OAuthBrowser oauthBrowser =
            new com.hawson.timeassistant.auth.OAuthBrowser(this);
    private void onAiConnectionChanged() {
        if (chatPageViewModel != null && chatPageViewModel.preparingSubmission != null
                && !chatPageViewModel.preparingSubmission.connection.equals(appGraph.ai.profiles.contextBoundary()))
            cancelPreparingSubmission();
        if (aiClient != null && !aiClientBoundary.equals(appGraph.ai.profiles.contextBoundary())) {
            if (activeAgentRequest != null) activeAgentRequest.cancel();
            activeAgentRequest = null; aiClient.close(); aiClient = null;
            chatBusy = false; ProactiveMessageBus.setUserAiBusy(false);
        }
        if (currentPage == PAGE_CHAT && !settingsVisible) refreshChatUi();
    }
    protected AiClient.Request activeAiRequest;
    private AgentRuntime.Request activeAgentRequest;
    private AgentRuntime agentRuntime;
    private ContextBroker contextBroker;
    private PolicyEngine policyEngine;
    private PromptCompiler promptCompiler;
    private LocalIntelligenceStore localIntelligenceStore;
    private LocalIntegrationSettingsStore localIntegrationSettings;
    private PreferenceLearningCoordinator preferenceLearningCoordinator;
    private BehaviorEventRecorder behaviorEventRecorder;
    private boolean focusCompletionDialogVisible;

    protected FrameLayout pageContainer;
    protected View bottomNavigation;
    private ActivityTimeAssistantBinding activityBinding;
    private BottomNavigationController navigationController;
    protected int currentPage = PAGE_CHAT;
    protected static final int SCHEDULE_DAY = 0;
    protected static final int SCHEDULE_TODO = 1;
    protected static final int SCHEDULE_REFLOW = 2;
    protected static final int SCHEDULE_CALENDAR = 3;
    private static final int SETTINGS_APPEARANCE = 0;
    private static final int SETTINGS_AI = 1;
    private static final int SETTINGS_PROACTIVE = 2;
    private static final int SETTINGS_PREFERENCES = 3;
    private static final int SETTINGS_CONNECTIONS = 4;
    private static final int SETTINGS_DATA = 5;
    private static final int SETTINGS_PERMISSIONS = 6;
    private SystemPermissionGuideController systemPermissionGuide;
    private PermissionGuideView systemPermissionView;
    private static final int FOCUS_SETTINGS_TIMER = 0;
    private static final int FOCUS_SETTINGS_AUTOMATION = 1;
    private static final int FOCUS_SETTINGS_SIGNALS = 2;
    private static final int FOCUS_SETTINGS_HISTORY = 3;
    protected int scheduleMode = SCHEDULE_DAY;
    private int scheduleModeBeforeCalendar = SCHEDULE_DAY;
    protected Calendar selectedDate = Calendar.getInstance();
    protected Calendar calendarMonth = Calendar.getInstance();
    private long activeChatDay;
    private Calendar selectedChatDate = Calendar.getInstance();
    private Calendar chatCalendarMonth = Calendar.getInstance();
    protected boolean settingsVisible;
    private boolean focusSettingsVisible;
    private boolean keyboardVisible;
    private boolean animateNextPageChange;
    private int schedulePageTransitionDirection;
    private long scheduleRenderedLocalDay;
    private boolean schedulePageAnimating;
    private int settingsCategory = -1;
    private int focusSettingsCategory = FOCUS_SETTINGS_TIMER;
    protected boolean rendering;
    private boolean renderPosted;
    private View scheduleScrollTarget;
    private View scheduleScrollFallback;
    private boolean scheduleAutoScrollEnabled;
    private boolean preserveScheduleScroll;
    private View scheduleSwipeArea;
    private final List<View> scheduleCardGestureRegions = new ArrayList<>();
    private float schedulePageSwipeDownX;
    private float schedulePageSwipeDownY;
    private boolean schedulePageSwipeCandidate;
    private final Set<String> promptedTaskStarts = new HashSet<>();
    private final Set<String> promptedTaskEnds = new HashSet<>();
    private boolean taskStartPromptVisible;
    private boolean taskEndPromptVisible;
    private boolean normalizingEndedTaskStates;
    private boolean activityStarted;
    private boolean focusLockscreenSettingsDialogVisible;
    private boolean countdownReceiverRegistered;
    private final List<CountdownCardBinding> countdownCardBindings = new ArrayList<>();
    private final BroadcastReceiver countdownUpdateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || repository == null) return;
            if (FocusTimerService.ACTION_FOCUS_UPDATED.equals(intent.getAction())) {
                syncFocusStateFromStorage();
                mainHandler.post(TimeAssistantActivity.this::resumePendingFocusCompletion);
            } else {
                syncCountdownTaskFromStorage(
                        intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID));
            }
        }
    };

    protected LinearLayout chatMessages;
    protected ScrollView chatScroll;
    protected EditText chatInput;
    protected boolean chatBusy;
    protected String chatError;
    private com.hawson.timeassistant.connections.AiConnectionException.Status chatErrorStatus;
    protected TextView chatStateLabel;
    private LinearLayout attachmentPreview;
    private View attachmentPreviewScroll;
    private PageChatBinding chatPageBinding;
    private final ChatPageController chatPageController = new ChatPageController();
    private final PagePresentationState presentation = new PagePresentationState();
    private final Map<String, Set<Integer>> proposalSelection = presentation.chat.selections;
    private final Set<String> executingProposals = new HashSet<>();
    private final Set<String> confirmingProposals = new HashSet<>();
    private FormDraftGuard.Session settingsDraft;
    private android.widget.ImageButton latestChatButton;
    private final SchedulePageController schedulePageController = new SchedulePageController();
    private final FocusPageController focusPageController = new FocusPageController();
    private final SettingsPageController settingsPageController = new SettingsPageController();
    private ChatPageViewModel chatPageViewModel;
    private SchedulePageViewModel schedulePageViewModel;
    private com.hawson.timeassistant.ui.schedule.AttentionListView attentionList;
    private final Runnable attentionExpiry = this::refreshAttentionList;
    private FocusPageViewModel focusPageViewModel;
    private SettingsPageViewModel settingsPageViewModel;
    private List<PendingAttachment> pendingAttachments = new ArrayList<>();
    private com.hawson.timeassistant.reminder.ReminderForegroundPresenter reminderPresenter;
    private LastAiRequest lastAiRequest;
    private AiErrorLogStore aiErrorLogStore;
    private String chatDraft = "";
    private TextView aiThinkingText;
    private int aiThinkingDotCount;
    private boolean keepKeyboardAfterSend;
    private final ExecutorService attachmentExecutor = Executors.newSingleThreadExecutor();

    protected TimerRingView timerRing;
    private PageFocusBinding focusPageBinding;
    private boolean focusRingAnimating;
    private boolean suppressFocusRepositoryRender;
    protected Button timerButton;
    protected TextView focusTaskLabel;
    protected CountDownTimer countDownTimer;
    private FocusState countDownState;
    private FocusState.Mode idleFocusMode = FocusState.Mode.FOCUS;
    private String lastFocusTaskId;
    private String focusCustomTaskLabel = "";
    private final AmbientSoundPlayer ambientSoundPlayer = new AmbientSoundPlayer();
    protected TaskActionExecutor.ExecutionResult lastExecutionResult;
    protected DeviceSyncManager deviceSyncManager;
    protected String syncStatus;
    protected volatile boolean applyingRemoteSync;
    private final Runnable autoSyncRunnable = () -> {
        if (deviceSyncManager != null && deviceSyncManager.getPeer() != null) deviceSyncManager.syncNow();
    };
    private final Runnable aiThinkingRunnable = new Runnable() {
        @Override public void run() {
            if (!chatBusy || !activityStarted || currentPage != PAGE_CHAT || settingsVisible
                    || aiThinkingText == null || !aiThinkingText.isAttachedToWindow()) return;
            if (!MotionSpec.full(TimeAssistantActivity.this)) { aiThinkingText.setText("正在处理…"); return; }
            aiThinkingDotCount = aiThinkingDotCount % 3 + 1;
            aiThinkingText.setText("正在处理" + new String(new char[aiThinkingDotCount]).replace('\0', '.'));
            mainHandler.postDelayed(this, 420L);
        }
    };
    private final Runnable taskStartPromptRunnable = new Runnable() {
        @Override public void run() {
            checkTaskEndPrompt();
            if (!taskEndPromptVisible) checkTaskStartPrompt();
            if (currentPage == PAGE_SCHEDULE && !settingsVisible && scheduleRenderedLocalDay != startOfDay(System.currentTimeMillis())) renderCurrentPage();
            if (activityStarted) mainHandler.postDelayed(this, 30_000L);
        }
    };
    private final Runnable countdownCardRunnable = new Runnable() {
        @Override public void run() {
            if (currentPage != PAGE_SCHEDULE || scheduleMode != SCHEDULE_TODO
                    || countdownCardBindings.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (CountdownCardBinding binding : new ArrayList<>(countdownCardBindings)) {
                TaskItem task = repository == null ? null : repository.getTask(binding.taskId);
                if (task == null || !task.hasCountdown() || task.getStatus().isTerminal()) {
                    binding.value.setText("已结束");
                    binding.value.setTextColor(TEXT_MUTED);
                    continue;
                }
                binding.value.setText(formatCountdownCompact(task.getCountdownTargetAt() - now));
                binding.value.setTextColor(task.getCountdownTargetAt() > now
                        ? ACCENT : ERROR);
            }
            mainHandler.postDelayed(this, 1_000L);
        }
    };

    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String[]> attachmentLauncher;
    private ActivityResultLauncher<Intent> voiceLauncher;
    private VoiceInputController voiceInput;
    private AiToolsController aiToolsController;
    private AiToolsController aiTools() {
        if (aiToolsController == null) aiToolsController = new AiToolsController(this, appGraph.ai, this::openAiSettings);
        return aiToolsController;
    }
    private ActivityResultLauncher<String> calendarPermissionLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<String> focusNotificationPermissionLauncher;
    private SharedPreferences uiPreferences;
    private final ExecutorService calendarExecutor = Executors.newSingleThreadExecutor();
    private final ProactiveMessageBus.Listener proactiveMessageListener = message -> {
        if (repository != null) {
            if (message.hasAction()) repository.cancelPendingChatActions(
                    message.getConversationDay(), ChatMessage.ProposalKind.PROACTIVE_PLAN);
            repository.addChatMessage(message);
        }
    };
    private final AppRepository.ChangeListener repositoryChangeListener =
            this::onRepositoryChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        uiPreferences = getSharedPreferences(AppTheme.PREFERENCES_NAME, MODE_PRIVATE);
        AppRepository existingRepository = ((TimeAssistantApplication) getApplication()).getRepository();
        AppTheme.initialize(uiPreferences, !uiPreferences.getAll().isEmpty()
                || getSharedPreferences("time_assistant_tutorial", MODE_PRIVATE).contains("seen_version")
                || !existingRepository.getAllTasks().isEmpty()
                || !existingRepository.getChatMessages().isEmpty()
                || !existingRepository.getFocusHistory().isEmpty());
        setTheme(AppTheme.styleFor(AppTheme.selected(uiPreferences)));
        super.onCreate(savedInstanceState);
        applyThemePalette();
        configureWindow();
        repository = ((TimeAssistantApplication) getApplication()).getRepository();
        reminderPresenter = new com.hawson.timeassistant.reminder.ReminderForegroundPresenter(this, repository,
                ((TimeAssistantApplication) getApplication()).getReminderCoordinator(), id -> {
            showPage(PAGE_SCHEDULE);
            mainHandler.postDelayed(() -> showTaskReadOnlyDetails(id), 250L);
        });
        appGraph = ((TimeAssistantApplication) getApplication()).getAppGraph();
        tutorials = appGraph.tutorial(this, guideRegistry, R.drawable.ic_launcher_foreground);
        tutorials.initialize(!repository.getAllTasks().isEmpty()
                || !repository.getChatMessages().isEmpty()
                || !repository.getFocusHistory().isEmpty()
                || !repository.getSettings().getModel().isEmpty()
                || uiPreferences.contains("last_page"));
        focusCompletionUseCase = new FocusCompletionUseCase(repository);
        localIntegrationSettings = new LocalIntegrationSettingsStore(this);
        localIntelligenceStore = new LocalIntelligenceStore(this);
        policyEngine = new PolicyEngine(this, localIntegrationSettings);
        contextBroker = new ContextBroker(this, repository, localIntelligenceStore, policyEngine);
        promptCompiler = new PromptCompiler(repository, localIntelligenceStore, policyEngine);
        preferenceLearningCoordinator = new PreferenceLearningCoordinator(
                repository, localIntelligenceStore, localIntegrationSettings);
        behaviorEventRecorder = new BehaviorEventRecorder(
                localIntelligenceStore, repository.getAllTasks());
        localIntelligenceStore.refreshPreferences(rules -> refreshSettingsIfVisible());
        initializeChatSessionState();
        if (savedInstanceState != null) {
            long restoredChatDate = savedInstanceState.getLong(
                    STATE_SELECTED_CHAT_DATE, activeChatDay);
            if (restoredChatDate > 0
                    && startOfDay(restoredChatDate) <= startOfDay(System.currentTimeMillis())) {
                selectedChatDate.setTimeInMillis(restoredChatDate);
                chatCalendarMonth.setTimeInMillis(restoredChatDate);
            }
        }
        aiErrorLogStore = new AiErrorLogStore(this);
        secureApiKeyStore = new SecureApiKeyStore(this);
        currentPage = uiPreferences.getInt("last_page", PAGE_CHAT);
        if (savedInstanceState != null) settingsCategory = savedInstanceState.getInt("settings_section", -1);
        scheduleMode = SCHEDULE_DAY;
        selectedDate.setTimeInMillis(System.currentTimeMillis());
        presentation.restore(savedInstanceState);
        if (savedInstanceState != null) {
            selectedDate.setTimeInMillis(savedInstanceState.getLong("ui.schedule.date", selectedDate.getTimeInMillis()));
            scheduleMode = savedInstanceState.getInt("ui.schedule.mode", SCHEDULE_DAY);
            scheduleModeBeforeCalendar = savedInstanceState.getInt(
                    "ui.schedule.mode.before_calendar", SCHEDULE_DAY);
            chatDraft = savedInstanceState.getString("ui.chat.draft", "");
        }
        calendarMonth.setTimeInMillis(selectedDate.getTimeInMillis());
        idleFocusMode = focusModeFromOrdinal(uiPreferences.getInt("focus_idle_mode", 0));
        lastFocusTaskId = uiPreferences.getString("last_focus_task_id", null);
        focusCustomTaskLabel = uiPreferences.getString("focus_custom_task_label", "");
        registerResultLaunchers();
        ProactiveMessageBus.register(proactiveMessageListener);
        initializeDeviceSync();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (settingsVisible) {
                    closeSettings();
                } else if (currentPage == PAGE_SCHEDULE && scheduleMode == SCHEDULE_CALENDAR) {
                    scheduleMode = scheduleModeBeforeCalendar;
                    renderCurrentPage();
                } else if (currentPage == PAGE_CHAT && isViewingHistoricalChat()) {
                    returnToActiveChat();
                } else if (currentPage != PAGE_CHAT) {
                    showPage(PAGE_CHAT);
                } else {
                    finish();
                }
            }
        });
        repository.addChangeListener(repositoryChangeListener);
        ((TimeAssistantApplication) getApplication()).getReminderCoordinator().requestRefresh();
        FocusTimerService.sync(this, repository.getCurrentFocus());
        buildShell();
        initializePageStateObservers();
        boolean restoreSettings = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_SETTINGS_VISIBLE, false);
        if (restoreSettings) {
            if (savedInstanceState.getBoolean("ui.settings.focus", false)) openFocusSettings();
            else openSettings();
            if (settingsDraft != null) settingsDraft.restore(savedInstanceState.getBundle("ui.settings.draft"));
        } else renderCurrentPage();
        handleIncomingIntent(getIntent());
    }

    private void onRepositoryChanged(AppRepository.Change change) {
        // The repository may notify from a sync worker. Capture this flag before posting to the
        // main thread; the try/finally around importJson can otherwise clear it first.
        final boolean originatedFromRemote = applyingRemoteSync;
        if (behaviorEventRecorder != null) {
            behaviorEventRecorder.onRepositoryChange(repository, change, originatedFromRemote);
        }
        if (!originatedFromRemote && change.getType() != AppRepository.ChangeType.SETTINGS
                && deviceSyncManager != null && deviceSyncManager.getPeer() != null) {
            mainHandler.removeCallbacks(autoSyncRunnable);
            mainHandler.postDelayed(autoSyncRunnable, 900);
        }
    }

    private void initializePageStateObservers() {
        ViewModelProvider provider = new ViewModelProvider(this, appGraph.pageViewModels());
        chatPageViewModel = provider.get(ChatPageViewModel.class);
        pendingAttachments = chatPageViewModel.attachments;
        schedulePageViewModel = provider.get(SchedulePageViewModel.class);
        focusPageViewModel = provider.get(FocusPageViewModel.class);
        settingsPageViewModel = provider.get(SettingsPageViewModel.class);
        chatPageViewModel.getUiState().observe(this, this::onChatPageState);
        chatPageViewModel.proposalChanges().observe(this, version -> {
            if (currentPage == PAGE_CHAT && !settingsVisible && chatPageBinding != null
                    && chatPageBinding.getRoot().isAttachedToWindow()) refreshChatUi();
        });
        schedulePageViewModel.getUiState().observe(this, this::onSchedulePageState);
        focusPageViewModel.getUiState().observe(this, this::onFocusPageState);
        settingsPageViewModel.getUiState().observe(this, this::onSettingsPageState);
        repository.observeState().observe(this, this::onRepositoryState);
    }

    private void onRepositoryState(AppState state) {
        if (state == null || isFinishing() || isDestroyed()) return;
        syncAmbientSound(state.getCurrentFocus());
        FocusTimerService.sync(this, state.getCurrentFocus());
    }

    private void onChatPageState(ChatPageUiState state) {
        chatPageController.render(state);
        if (!shouldRenderRepositoryPage(PAGE_CHAT)) return;
        if (chatPageBinding != null && chatPageBinding.getRoot().isAttachedToWindow()) {
            refreshChatUi();
            return;
        }
        renderCurrentPage();
    }

    private void onSchedulePageState(SchedulePageUiState state) {
        schedulePageController.render(state);
        if (!shouldRenderRepositoryPage(PAGE_SCHEDULE)) return;
        if (scheduleMode == SCHEDULE_REFLOW && attentionList != null && attentionList.isAttachedToWindow()) {
            refreshAttentionList();
            return;
        }
        preserveScheduleScroll = true;
        renderCurrentPage();
    }

    private void onFocusPageState(FocusPageUiState state) {
        focusPageController.render(state);
        if (!shouldRenderRepositoryPage(PAGE_FOCUS) || suppressFocusRepositoryRender) return;
        renderCurrentPage();
    }

    private void onSettingsPageState(SettingsPageUiState state) {
        settingsPageController.render(state);
        if (!activityStarted || !settingsVisible || isFinishing() || isDestroyed()) return;
        refreshSettingsIfVisible();
    }

    private boolean shouldRenderRepositoryPage(int page) {
        return activityStarted && !settingsVisible && currentPage == page
                && !normalizingEndedTaskStates && !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        cancelPreparingSubmission();
        capturePagePresentation();
        presentation.save(outState);
        outState.putLong("ui.schedule.date", selectedDate.getTimeInMillis());
        outState.putInt("ui.schedule.mode", scheduleMode);
        outState.putInt("ui.schedule.mode.before_calendar", scheduleModeBeforeCalendar);
        outState.putString("ui.chat.draft", chatDraft);
        outState.putInt("settings_section", settingsCategory);
        outState.putBoolean(STATE_SETTINGS_VISIBLE, settingsVisible);
        outState.putBoolean("ui.settings.focus", focusSettingsVisible);
        if (settingsVisible && settingsDraft != null) outState.putBundle("ui.settings.draft", settingsDraft.save());
        outState.putLong(STATE_SELECTED_CHAT_DATE, selectedChatDate.getTimeInMillis());
        super.onSaveInstanceState(outState);
    }

    private void applyThemePalette() {
        SURFACE = ContextCompat.getColor(this, R.color.surface);
        TEXT = ContextCompat.getColor(this, R.color.text_primary);
        TEXT_MUTED = ContextCompat.getColor(this, R.color.text_secondary);
        NEUTRAL = ContextCompat.getColor(this, R.color.button_neutral);
        ERROR = ContextCompat.getColor(this, R.color.danger);
        ERROR_SOFT = ContextCompat.getColor(this, R.color.danger_soft);
        WARNING = ContextCompat.getColor(this, R.color.warning);
        WARNING_SOFT = ContextCompat.getColor(this, R.color.warning_soft);
        ON_BRAND = ContextCompat.getColor(this, R.color.on_brand);
        PRIMARY = AppTheme.resolveColor(this,
                R.attr.colorBrandPrimary, R.color.brand_primary);
        PRIMARY_SOFT = AppTheme.resolveColor(this,
                R.attr.colorPrimarySoft, R.color.brand_primary_soft);
        ACCENT = AppTheme.resolveColor(this,
                R.attr.colorBrandSecondary, R.color.brand_secondary);
        ACCENT_SOFT = AppTheme.resolveColor(this,
                R.attr.colorSecondarySoft, R.color.brand_secondary_soft);
        INSIGHT = AppTheme.resolveColor(this,
                R.attr.colorInsight, R.color.stats_blue);
        INSIGHT_SOFT = AppTheme.resolveColor(this,
                R.attr.colorInsightSoft, R.color.stats_blue_soft);
        BORDER = AppTheme.resolveColor(this,
                R.attr.colorCardBorder, R.color.card_border);
    }

    private void configureWindow() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                window, window.getDecorView());
        boolean light = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
    }

    private void registerResultLaunchers() {
        systemPermissionGuide = new SystemPermissionGuideController(this);
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                this::writeExport
        );
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::readImport
        );
        attachmentLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                this::onAttachmentsSelected
        );
        voiceInput = new VoiceInputController(this, () -> chatInput, aiTools());
        voiceLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    ArrayList<String> values = result.getData().getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS);
                    if (values != null && !values.isEmpty() && chatInput != null) {
                        chatInput.setText(values.get(0));
                        chatInput.setSelection(chatInput.length());
                    }
                }
        );
        calendarPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (localIntegrationSettings == null) return;
                    localIntegrationSettings.setCalendarExperimentEnabled(Boolean.TRUE.equals(granted));
                    showMessage(Boolean.TRUE.equals(granted)
                            ? "系统日历实验已开启" : "未获得日历读取权限，实验功能保持关闭");
                    refreshSettingsIfVisible();
                });
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (localIntegrationSettings == null) return;
                    ProactiveSettings settings = localIntegrationSettings.getProactiveSettings();
                    settings.notificationEnabled = Boolean.TRUE.equals(granted);
                    localIntegrationSettings.saveProactiveSettings(settings);
                    ProactiveScheduleCoordinator.rescheduleAll(this);
                    showMessage(Boolean.TRUE.equals(granted)
                            ? "主动整理通知已开启" : "未获得通知权限；仍可在聊天页看到主动整理");
                    refreshSettingsIfVisible();
                });
        focusNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (repository != null) {
                        FocusTimerService.sync(this, repository.getCurrentFocus());
                        ReminderScheduler.refresh(this, repository.getAllTasks(),
                                repository.getSettings(), repository.getCurrentFocus());
                    }
                    if (Boolean.TRUE.equals(granted)) {
                        promptForFocusLockscreenNotificationIfNeeded();
                    } else {
                        showNotificationSettingsDialog();
                    }
                });
    }

    private void buildShell() {
        activityBinding = ActivityTimeAssistantBinding.inflate(getLayoutInflater());
        pageContainer = activityBinding.pageContainer;
        bottomNavigation = activityBinding.bottomNavigation;
        navigationController = new BottomNavigationController(activityBinding, this::showPage);
        ViewCompat.setOnApplyWindowInsetsListener(activityBinding.rootContainer, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            keyboardVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right,
                    keyboardVisible ? Math.max(bars.bottom, ime.bottom) : bars.bottom);
            updateBottomNavigationVisibility();
            if (keyboardVisible && settingsVisible) {
                androidx.core.view.OneShotPreDrawListener.add(view, () -> {
                    View focused = view.findFocus();
                    if (focused instanceof android.widget.EditText && focused.isAttachedToWindow())
                        focused.requestRectangleOnScreen(new android.graphics.Rect(
                                0, 0, focused.getWidth(), focused.getHeight()), true);
                });
            }
            return windowInsets;
        });
        setContentView(activityBinding.getRoot());
        activityBinding.getRoot().post(this::configureWindow);
    }

    protected void showPage(int page) {
        if (page == currentPage && !settingsVisible && pageContainer != null
                && pageContainer.getChildCount() > 0) return;
        capturePagePresentation();
        settingsVisible = false;
        focusSettingsVisible = false;
        int previousPage = currentPage;
        if (previousPage == PAGE_FOCUS && page != PAGE_FOCUS) focusPageController.stopMotion();
        currentPage = Math.max(PAGE_CHAT, Math.min(PAGE_FOCUS, page));
        animateNextPageChange = previousPage != currentPage;
        if (previousPage != currentPage) preserveScheduleScroll = currentPage == PAGE_SCHEDULE;
        if (uiPreferences != null) {
            uiPreferences.edit().putInt("last_page", currentPage).apply();
        }
        renderCurrentPage();
    }

    protected void renderCurrentPage() {
        if (pageContainer == null || repository == null || renderPosted) return;
        renderPosted = true;
        mainHandler.post(() -> {
            renderPosted = false;
            renderCurrentPageNow();
        });
    }

    private void renderCurrentPageNow() {
        if (pageContainer == null || repository == null || rendering) return;
        if (!settingsVisible && currentPage == PAGE_FOCUS && focusPageBinding != null
                && pageContainer.getChildCount() > 0
                && pageContainer.getChildAt(pageContainer.getChildCount() - 1) == focusPageBinding.getRoot()) {
            refreshFocusModeControls();
            syncFocusTimerUi();
            return;
        }
        if (!settingsVisible && currentPage == PAGE_CHAT && chatPageBinding != null
                && chatPageBinding.getRoot().isAttachedToWindow()) {
            refreshChatUi();
            return;
        }
        if (schedulePageAnimating) {
            mainHandler.postDelayed(this::renderCurrentPage, 280L);
            return;
        }
        capturePagePresentation();
        rendering = true;
        try {
            mainHandler.removeCallbacks(countdownCardRunnable);
            countdownCardBindings.clear();
            int previousScheduleScrollY = presentation.scroll[PAGE_SCHEDULE];
            if (preserveScheduleScroll && currentPage == PAGE_SCHEDULE) {
                ScrollView currentScroll = pageContainer.findViewById(R.id.scheduleScroll);
                if (currentScroll != null) previousScheduleScrollY = currentScroll.getScrollY();
            }
            View page = currentPage == PAGE_CHAT ? buildChatPage()
                    : currentPage == PAGE_SCHEDULE ? buildSchedulePage() : buildFocusPage();
            UiKit.polish(page);
            int scheduleDirection = schedulePageTransitionDirection;
            schedulePageTransitionDirection = 0;
            boolean retainedSchedule = currentPage == PAGE_SCHEDULE
                    && pageContainer.getChildCount() == 1 && pageContainer.getChildAt(0) == page;
            boolean animatedScheduleReplacement = !retainedSchedule && scheduleDirection != 0
                    && currentPage == PAGE_SCHEDULE
                    && pageContainer.getChildCount() == 1
                    && ViewCompat.isLaidOut(pageContainer);
            if (retainedSchedule) {
                animateNextPageChange = false;
                if (scheduleDirection != 0) schedulePageController.animateBody(scheduleDirection);
            } else if (animatedScheduleReplacement) {
                animateNextPageChange = false;
                animateSchedulePageReplacement(page, scheduleDirection);
            } else {
                com.hawson.timeassistant.design.ContentTransition.replace(pageContainer, page, 1, animateNextPageChange);
                animateNextPageChange = false;
            }
            if (preserveScheduleScroll && currentPage == PAGE_SCHEDULE) {
                ScrollView newScroll = page.findViewById(R.id.scheduleScroll);
                int targetScrollY = previousScheduleScrollY;
                if (newScroll != null) newScroll.post(() -> newScroll.scrollTo(0, targetScrollY));
                preserveScheduleScroll = false;
            }
            updateBottomNavigationVisibility();
            navigationController.select(currentPage);
            View restoredPage = page;
            if (currentPage == PAGE_FOCUS && page instanceof ScrollView) {
                int offset = presentation.scroll[PAGE_FOCUS];
                restoredPage.post(() -> ((ScrollView) restoredPage).scrollTo(0, offset));
            }
            if (tutorials != null) page.post(() -> {
                if (settingsVisible || !page.isAttachedToWindow()) return;
                tutorials.requestGuide(currentPage == PAGE_CHAT ? GuideTarget.CHAT_COMPOSER
                        : currentPage == PAGE_SCHEDULE ? GuideTarget.CREATE_TASK
                        : GuideTarget.FOCUS_MODES);
            });
            if (!countdownCardBindings.isEmpty()) mainHandler.post(countdownCardRunnable);
        } finally {
            rendering = false;
        }
    }

    private void animateSchedulePageReplacement(View incoming, int direction) {
        schedulePageAnimating = false;
        schedulePageController.replace(pageContainer, incoming, direction);
    }

    private void updateBottomNavigationVisibility() {
        if (bottomNavigation == null) return;
        bottomNavigation.setVisibility(keyboardVisible || settingsVisible ? View.GONE : View.VISIBLE);
    }

    private void capturePagePresentation() {
        if (pageContainer == null || settingsVisible || pageContainer.getChildCount() == 0) return;
        View page = pageContainer.getChildAt(0);
        ScrollView chat = page.findViewById(R.id.chatScroll);
        ScrollView schedule = page.findViewById(R.id.scheduleScroll);
        if (chat != null) presentation.scroll[PAGE_CHAT] = chat.getScrollY();
        else if (schedule != null) presentation.scroll[PAGE_SCHEDULE] = schedule.getScrollY();
        else if (page instanceof ScrollView) presentation.scroll[PAGE_FOCUS] = page.getScrollY();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int targetMode = scheduleSwipeTarget(event);
        if (targetMode >= 0) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            selectScheduleMode(targetMode);
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private int scheduleSwipeTarget(MotionEvent event) {
        if (currentPage != PAGE_SCHEDULE || settingsVisible || keyboardVisible
                || scheduleMode == SCHEDULE_CALENDAR || scheduleSwipeArea == null) {
            schedulePageSwipeCandidate = false;
            return -1;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                schedulePageSwipeDownX = event.getRawX();
                schedulePageSwipeDownY = event.getRawY();
                schedulePageSwipeCandidate = pointInsideView(
                        scheduleSwipeArea, schedulePageSwipeDownX, schedulePageSwipeDownY)
                        && !pointInsideAnyView(scheduleCardGestureRegions,
                        schedulePageSwipeDownX, schedulePageSwipeDownY);
                return -1;
            case MotionEvent.ACTION_MOVE:
                if (schedulePageSwipeCandidate) {
                    float moveX = event.getRawX() - schedulePageSwipeDownX;
                    float moveY = event.getRawY() - schedulePageSwipeDownY;
                    if (Math.abs(moveY) > dp(10) && Math.abs(moveY) > Math.abs(moveX)) {
                        schedulePageSwipeCandidate = false;
                    }
                }
                return -1;
            case MotionEvent.ACTION_UP:
                if (!schedulePageSwipeCandidate) return -1;
                schedulePageSwipeCandidate = false;
                float dx = event.getRawX() - schedulePageSwipeDownX;
                float dy = event.getRawY() - schedulePageSwipeDownY;
                if (Math.abs(dx) < dp(64) || Math.abs(dx) < Math.abs(dy) * 1.25f) return -1;
                int maxMode = sameDay(selectedDate.getTimeInMillis(),
                        System.currentTimeMillis()) ? SCHEDULE_REFLOW : SCHEDULE_TODO;
                int target = dx < 0 ? scheduleMode + 1 : scheduleMode - 1;
                return target >= SCHEDULE_DAY && target <= maxMode ? target : -1;
            case MotionEvent.ACTION_CANCEL:
                schedulePageSwipeCandidate = false;
                return -1;
            default:
                return -1;
        }
    }

    private boolean pointInsideAnyView(List<View> views, float rawX, float rawY) {
        for (View view : views) {
            if (pointInsideView(view, rawX, rawY)) return true;
        }
        return false;
    }

    private boolean pointInsideView(View view, float rawX, float rawY) {
        if (view == null || !view.isShown()) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0] && rawX <= location[0] + view.getWidth()
                && rawY >= location[1] && rawY <= location[1] + view.getHeight();
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(com.hawson.timeassistant.auth.OAuthSignInService.EXTRA_OPEN_SIGN_IN, false)) {
            intent.removeExtra(com.hawson.timeassistant.auth.OAuthSignInService.EXTRA_OPEN_SIGN_IN);
            openAiSettings();
            return;
        }
        String focusAction = intent.getStringExtra(
                FocusTimerService.EXTRA_NOTIFICATION_ACTION);
        String reminderKind = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_KIND);
        String taskId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID);
        // Consume the navigation instruction so an Activity recreation does not reopen the same
        // task/dialog a second time.
        intent.removeExtra(ReminderScheduler.EXTRA_REMINDER_KIND);
        intent.removeExtra(ReminderScheduler.EXTRA_TASK_ID);
        intent.removeExtra(FocusTimerService.EXTRA_NOTIFICATION_ACTION);
        if (focusAction != null) {
            showPage(PAGE_FOCUS);
            mainHandler.postDelayed(() -> {
                if (FocusTimerService.NOTIFICATION_ACTION_CANCEL.equals(focusAction)) {
                    cancelFocusTimer();
                } else if (FocusTimerService.NOTIFICATION_ACTION_END.equals(focusAction)) {
                    endFocusEarly();
                }
            }, 180L);
            return;
        }
        if (ReminderScheduler.KIND_DAILY_REVIEW.equals(reminderKind)) {
            showPage(PAGE_CHAT);
            return;
        }
        if (intent.getBooleanExtra(ProactiveNotification.EXTRA_OPEN_PROACTIVE_CHAT, false)) {
            intent.removeExtra(ProactiveNotification.EXTRA_OPEN_PROACTIVE_CHAT);
            showPage(PAGE_CHAT);
            return;
        }
        if (ReminderScheduler.KIND_SCHEDULE_END.equals(reminderKind)) {
            showPage(PAGE_SCHEDULE);
            mainHandler.postDelayed(() -> showTaskEndConfirmation(taskId, true), 180L);
            return;
        }
        if (ReminderScheduler.KIND_FOCUS.equals(reminderKind)) {
            showPage(PAGE_FOCUS);
            return;
        }
        if (taskId != null) {
            showPage(PAGE_SCHEDULE);
            mainHandler.postDelayed(() -> showTaskReadOnlyDetails(taskId), 160);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        appGraph.ai.accounts.resumeSignIn();
        if (appGraph.ai.accounts.signInStatus().active) com.hawson.timeassistant.auth.OAuthSignInService.start(this);
        appGraph.ai.addConnectionListener(aiConnectionChanged);
        aiConnectionChanged.run();
        if (tutorials != null) tutorials.onStart();
        boolean chatRolledOver = rollChatSessionIfNeeded(System.currentTimeMillis());
        uiPreferences.edit().putLong(
                PREF_LAST_APP_ACTIVITY_AT, System.currentTimeMillis()).apply();
        activityStarted = true;
        if (reminderPresenter != null) reminderPresenter.start();
        ((TimeAssistantApplication) getApplication()).getReminderCoordinator().requestRefresh();
        ProactiveScheduleCoordinator.ensureScheduled(this);
        registerCountdownUpdateReceiver();
        syncCountdownTasksFromStorage();
        syncFocusStateFromStorage();
        focusPageController.setStarted(hasFocusStarted(repository.getCurrentFocus()), false);
        if (currentPage == PAGE_FOCUS && !settingsVisible) renderCurrentPage();
        mainHandler.removeCallbacks(taskStartPromptRunnable);
        mainHandler.post(taskStartPromptRunnable);
        if (chatBusy && aiThinkingText != null) {
            mainHandler.removeCallbacks(aiThinkingRunnable);
            mainHandler.post(aiThinkingRunnable);
        }
        if (currentPage == PAGE_SCHEDULE && !settingsVisible) renderCurrentPage();
        if (repository != null && repository.getCurrentFocus().getState() == FocusState.State.RUNNING) {
            // Re-post after returning from vendor notification settings so ColorOS re-evaluates
            // the lock-screen visibility rule for the active foreground notification.
            FocusTimerService.sync(this, repository.getCurrentFocus());
            mainHandler.post(() -> startCountDown(repository.getCurrentFocus()));
            mainHandler.postDelayed(this::requestNotificationPermissionIfNeeded, 550L);
        } else if (repository != null
                && repository.getCurrentFocus().getState() == FocusState.State.FINISHED) {
            mainHandler.post(this::resumePendingFocusCompletion);
        }
        if (chatRolledOver && currentPage == PAGE_CHAT) renderCurrentPage();
        confirmFocusLockscreenSettingsAfterReturn();
        mainHandler.postDelayed(this::runPreferenceAnalysisIfDue, 700L);
    }

    private void runPreferenceAnalysisIfDue() {
        if (!activityStarted || chatBusy || !isAiConfigured()
                || preferenceLearningCoordinator == null) return;
        try {
            ensureAiClient();
            preferenceLearningCoordinator.runIfDue(aiClient);
        } catch (RuntimeException ignored) { }
    }

    @Override
    protected void onStop() {
        if (reminderPresenter != null) reminderPresenter.stop();
        mainHandler.removeCallbacks(attentionExpiry);
        focusPageController.stopMotion();
        scheduleSwipeGroup.closeOpenRow();
        if (voiceInput != null) voiceInput.background();
        appGraph.ai.removeConnectionListener(aiConnectionChanged);
        activityStarted = false;
        if (tutorials != null) tutorials.onStop();
        if (pageContainer != null) {
            TransitionManager.endTransitions(pageContainer);
            MotionSpec.cancelTree(pageContainer);
            if (bottomNavigation != null) MotionSpec.cancelTree(bottomNavigation);
        }
        if (timerRing != null) timerRing.stopMotion();
        unregisterCountdownUpdateReceiver();
        mainHandler.removeCallbacks(countdownCardRunnable);
        mainHandler.removeCallbacks(taskStartPromptRunnable);
        mainHandler.removeCallbacks(aiThinkingRunnable);
        persistRunningFocusSnapshot();
        stopCountDown();
        uiPreferences.edit().putLong(
                PREF_LAST_APP_ACTIVITY_AT, System.currentTimeMillis()).apply();
        super.onStop();
    }

    private void registerCountdownUpdateReceiver() {
        if (countdownReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(ReminderScheduler.ACTION_COUNTDOWN_UPDATED);
        filter.addAction(FocusTimerService.ACTION_FOCUS_UPDATED);
        ContextCompat.registerReceiver(this, countdownUpdateReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        countdownReceiverRegistered = true;
    }

    private void unregisterCountdownUpdateReceiver() {
        if (!countdownReceiverRegistered) return;
        try { unregisterReceiver(countdownUpdateReceiver); }
        catch (IllegalArgumentException ignored) { }
        countdownReceiverRegistered = false;
    }

    private void syncCountdownTasksFromStorage() {
        if (repository == null) return;
        AppRepository persisted = AppRepository.get(this);
        for (TaskItem task : persisted.getAllTasks()) {
            TaskItem current = repository.getTask(task.getId());
            if (current != null && task.getUpdatedAt() > current.getUpdatedAt()
                    && (current.hasCountdown() || task.hasCountdown())) {
                repository.upsertTask(task);
            }
        }
    }

    private void syncCountdownTaskFromStorage(String taskId) {
        if (repository == null || taskId == null || taskId.trim().isEmpty()) return;
        TaskItem task = AppRepository.get(this).getTask(taskId);
        TaskItem current = repository.getTask(taskId);
        if (task != null && current != null && task.getUpdatedAt() > current.getUpdatedAt()) {
            repository.upsertTask(task);
        }
    }

    private void persistRunningFocusSnapshot() {
        if (repository == null) return;
        FocusState focus = repository.getCurrentFocus();
        if (focus.getState() == FocusState.State.RUNNING) {
            try {
                FocusState persisted = AppRepository.get(this).getCurrentFocus();
                if (persisted.getUpdatedAt() > focus.getUpdatedAt()) {
                    repository.setCurrentFocus(persisted);
                    return;
                }
                repository.setCurrentFocus(focus.snapshotAt(System.currentTimeMillis()));
            }
            catch (RuntimeException ignored) { }
        }
    }

    private void syncFocusStateFromStorage() {
        if (repository == null) return;
        FocusState persisted = AppRepository.get(this).getCurrentFocus();
        FocusState current = repository.getCurrentFocus();
        if (persisted.getUpdatedAt() > current.getUpdatedAt()) {
            repository.setCurrentFocus(persisted);
        }
    }

    private void initializeChatSessionState() {
        long now = System.currentTimeMillis();
        long today = startOfDay(now);
        boolean hadStoredActiveDay = uiPreferences.contains(PREF_ACTIVE_CHAT_DAY);
        long fallbackDay = latestStoredChatDay(today);
        activeChatDay = uiPreferences.getLong(PREF_ACTIVE_CHAT_DAY, fallbackDay);
        if (activeChatDay <= 0) activeChatDay = fallbackDay;
        activeChatDay = startOfDay(activeChatDay);
        if (hadStoredActiveDay) rollChatSessionIfNeeded(now);
        selectedChatDate.setTimeInMillis(activeChatDay);
        chatCalendarMonth.setTimeInMillis(activeChatDay);
        uiPreferences.edit().putLong(PREF_ACTIVE_CHAT_DAY, activeChatDay).apply();
    }

    private long latestStoredChatDay(long fallback) {
        long latestMessageAt = 0L;
        for (ChatMessage message : repository.getChatMessages()) {
            latestMessageAt = Math.max(latestMessageAt, message.getCreatedAt());
        }
        return latestMessageAt > 0 ? startOfDay(latestMessageAt) : fallback;
    }

    /** Starts a fresh conversation only after both a date boundary and two idle hours. */
    private boolean rollChatSessionIfNeeded(long now) {
        long today = startOfDay(now);
        long lastActivityAt = uiPreferences.getLong(PREF_LAST_APP_ACTIVITY_AT, 0L);
        if (!ChatSessionPolicy.shouldStartNewSession(
                activeChatDay, lastActivityAt, now, TimeZone.getDefault())) return false;
        cancelPreparingSubmission();

        activeChatDay = today;
        selectedChatDate.setTimeInMillis(today);
        chatCalendarMonth.setTimeInMillis(today);
        chatDraft = "";
        keepKeyboardAfterSend = false;
        pendingAttachments.clear();
        chatError = null;
        lastAiRequest = null;
        if (activeAiRequest != null) {
            activeAiRequest.cancel();
            activeAiRequest = null;
        }
        if (activeAgentRequest != null) {
            activeAgentRequest.cancel();
            activeAgentRequest = null;
        }
        chatBusy = false;
        repository.cancelPendingChatActions();
        uiPreferences.edit().putLong(PREF_ACTIVE_CHAT_DAY, activeChatDay).apply();
        return true;
    }

    private boolean isViewingHistoricalChat() {
        return !sameDay(selectedChatDate.getTimeInMillis(), activeChatDay);
    }

    private void returnToActiveChat() {
        selectedChatDate.setTimeInMillis(activeChatDay);
        chatCalendarMonth.setTimeInMillis(activeChatDay);
        hideChatKeyboard();
        if (currentPage == PAGE_CHAT) renderCurrentPage();
    }

    private void hideChatKeyboard() {
        keepKeyboardAfterSend = false;
        if (chatInput == null) return;
        chatInput.clearFocus();
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(chatInput.getWindowToken(), 0);
    }

    @Override
    protected void onDestroy() {
        cancelPreparingSubmission();
        if (voiceInput != null) voiceInput.close();
        if (aiToolsController != null) aiToolsController.close();
        if (tutorials != null) tutorials.dispose();
        guideRegistry.clear();
        if (repository != null) repository.removeChangeListener(repositoryChangeListener);
        ProactiveMessageBus.unregister(proactiveMessageListener);
        ProactiveMessageBus.setUserAiBusy(false);
        if (activeAiRequest != null) activeAiRequest.cancel();
        if (activeAgentRequest != null) activeAgentRequest.cancel();
        if (preferenceLearningCoordinator != null) {
            preferenceLearningCoordinator.cancelForUserRequest();
        }
        if (aiClient != null) aiClient.close();
        if (contextBroker != null) contextBroker.close();
        if (localIntelligenceStore != null) localIntelligenceStore.close();
        calendarExecutor.shutdownNow();
        attachmentExecutor.shutdownNow();
        if (countDownTimer != null) countDownTimer.cancel();
        ambientSoundPlayer.stop();
        mainHandler.removeCallbacks(autoSyncRunnable);
        mainHandler.removeCallbacks(taskStartPromptRunnable);
        mainHandler.removeCallbacks(aiThinkingRunnable);
        mainHandler.removeCallbacks(countdownCardRunnable);
        unregisterCountdownUpdateReceiver();
        if (deviceSyncManager != null) deviceSyncManager.stop();
        chatPageController.clearBinding();
        schedulePageController.clearBinding();
        focusPageController.clearBinding();
        settingsPageController.clearBinding();
        super.onDestroy();
    }

    protected View buildChatPage() {
        PageChatBinding binding = chatPageController.inflate(getLayoutInflater());
        chatPageBinding = binding;
        boolean configured = isAiConfigured();
        boolean historical = isViewingHistoricalChat();
        binding.settingsButton.setOnClickListener(this::showChatOverflowMenu);
        applyPressFeedback(binding.settingsButton);
        binding.connectionPicker.addView(new ChatConnectionView(this, appGraph.ai.profiles, appGraph.ai,
                profile -> beforeAiConnectionChange(() -> {
                    try { appGraph.ai.profiles.activate(profile.id); renderCurrentPage(); }
                    catch (Exception failed) { showMessage("切换失败，请检查连接配置"); }
                }), option -> beforeAiConnectionChange(() -> {
                    try {
                        AiProfile active = appGraph.ai.profiles.active();
                        if (active == null) return;
                        getSharedPreferences("ai_model_names", 0).edit().putString(active.id + ":" + option.id, option.name).apply();
                        if (active.model.equals(option.id)) { refreshChatUi(); return; }
                        appGraph.ai.profiles.save(new AiProfile(active.id, active.name, active.kind,
                                active.protocol, active.baseUrl, option.id, active.credentialRef), null);
                        getSharedPreferences("ai_model_names", 0).edit().putString(active.id + ":" + option.id, option.name).apply();
                        refreshChatUi();
                    } catch (Exception failed) { showMessage("模型未能保存，已保留原选择"); }
                }), this::openAiSettings, () -> aiTools().showAccountsAndUsage()), 0);

        chatScroll = binding.chatScroll;
        latestChatButton = binding.latestMessageButton;
        latestChatButton.setOnClickListener(view -> { presentation.followChat = true; scrollChatToBottom(); });
        chatScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (rendering || !view.isAttachedToWindow()) return;
            presentation.followChat = chatMessages.getHeight() - chatScroll.getHeight() - y < dp(64);
            latestChatButton.setVisibility(presentation.followChat ? View.GONE : View.VISIBLE);
        });
        chatMessages = binding.chatMessages;
        chatInput = binding.chatInput;
        chatInput.setBackgroundColor(Color.TRANSPARENT);
        chatInput.setTextSize(14);
        chatInput.setPadding(dp(8), dp(12), dp(8), dp(12));
        chatInput.setOnFocusChangeListener((view, focused) -> binding.composerSurface.setBackground(
                UiKit.background(this, SURFACE, focused ? PRIMARY : BORDER)));
        guideRegistry.register(GuideTarget.CHAT_COMPOSER, binding.chatInput);
        attachmentPreview = binding.attachmentPreview;
        attachmentPreviewScroll = binding.attachmentPreviewScroll;
        chatInput.setHint(historical ? "历史聊天记录"
                : configured ? "告诉我你想怎么安排…" : "连接后开始对话");
        chatInput.setEnabled(!historical);
        chatInput.setText(historical ? "" : chatDraft);
        if (!historical && !chatDraft.isEmpty()) chatInput.setSelection(chatInput.length());
        chatInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                chatDraft = value == null ? "" : value.toString();
                if (chatPageViewModel != null) chatPageViewModel.composerRevision++;
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        chatInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendCurrentInput();
                return true;
            }
            return false;
        });
        binding.fileButton.setEnabled(configured && !chatBusy && !historical);
        binding.fileButton.setOnClickListener(view -> chooseAttachments());
        binding.voiceButton.setEnabled(!historical && !chatBusy);
        binding.voiceButton.setOnClickListener(view -> requestVoiceInput());
        applyPressFeedback(binding.fileButton);
        configureChatActionButton(binding, configured, historical);
        applyPressFeedback(binding.sendButton);

        renderChatMessages();
        renderPendingAttachments();
        if (!historical) restoreComposerFocusIfNeeded(chatInput);
        return binding.getRoot();
    }

    private void chooseChatModel() {
        com.hawson.timeassistant.connections.AiProfile profile = appGraph.ai.profiles.active();
        if (profile == null) { openAiSettings(); return; }
        com.hawson.timeassistant.connections.AiCancellation cancellation =
                new com.hawson.timeassistant.connections.AiCancellation();
        AlertDialog loading = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setMessage("正在获取模型")
                .setNegativeButton("取消", (d, w) -> cancellation.cancel()).create();
        loading.setOnCancelListener(d -> cancellation.cancel()); loading.show();
        attachmentExecutor.execute(() -> {
            try {
                List<String> models = appGraph.ai.models(profile, cancellation);
                mainHandler.post(() -> {
                    if (!loading.isShowing() || isFinishing() || isDestroyed()) return;
                    loading.dismiss();
                    if (models.isEmpty()) { showMessage("没有可选模型，已保留当前选择"); return; }
                    new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("选择模型")
                            .setSingleChoiceItems(models.toArray(new String[0]), models.indexOf(profile.model), (dialog, index) -> {
                                dialog.dismiss();
                                beforeAiConnectionChange(() -> {
                                    try {
                                        com.hawson.timeassistant.connections.AiProfile current = appGraph.ai.profiles.active();
                                        if (current == null || !current.id.equals(profile.id)) return;
                                        appGraph.ai.profiles.save(new com.hawson.timeassistant.connections.AiProfile(
                                                profile.id, profile.name, profile.kind, profile.protocol, profile.baseUrl,
                                                models.get(index), profile.credentialRef), null);
                                        renderCurrentPage();
                                    } catch (Exception failed) { showMessage("模型未能保存，已保留原选择"); }
                                });
                            }).setNegativeButton("取消", null).show();
                });
            } catch (Exception failed) {
                mainHandler.post(() -> {
                    if (!loading.isShowing() || isFinishing() || isDestroyed()) return;
                    loading.dismiss();
                    new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setMessage("模型获取失败，已保留原选择")
                            .setPositiveButton("重试", (d, w) -> chooseChatModel()).setNegativeButton("关闭", null).show();
                });
            }
        });
    }

    private void refreshChatUi() {
        PageChatBinding binding = chatPageBinding;
        if (binding == null || !binding.getRoot().isAttachedToWindow()) {
            renderCurrentPage();
            return;
        }
        boolean configured = isAiConfigured();
        boolean historical = isViewingHistoricalChat();
        binding.chatInput.setEnabled(!historical);
        if (binding.connectionPicker.getChildCount() > 0)
            ((ChatConnectionView) binding.connectionPicker.getChildAt(0)).refresh();
        binding.chatInput.setHint(historical ? "历史聊天记录"
                : configured ? "告诉我你想怎么安排…" : "连接后开始对话");
        binding.fileButton.setEnabled(configured && !chatBusy && !historical);
        configureChatActionButton(binding, configured, historical);
        binding.voiceButton.setEnabled(!historical && !chatBusy);
        renderChatMessages();
        renderPendingAttachments();
        if (!historical) restoreComposerFocusIfNeeded(binding.chatInput);
    }

    private void renderChatMessages() {
        if (chatMessages == null) return;
        LinearLayout target = chatMessages;
        int previousScroll = chatScroll == null ? presentation.scroll[PAGE_CHAT] : chatScroll.getScrollY();
        boolean historical = isViewingHistoricalChat();
        List<ChatMessage> messages = chatMessagesForDate(selectedChatDate.getTimeInMillis());
        List<ChatMessage> suggestions = new ArrayList<>();
        List<ChatPageController.Row> rows = new ArrayList<>();
        StringBuilder foreground = new StringBuilder().append(selectedChatDate.getTimeInMillis());
        if (!isAiConfigured() && !historical) {
            rows.add(new ChatPageController.Row("configuration", "unconfigured",
                    () -> collectChatRow(this::addConfigurationGuide)));
        }
        for (ChatMessage message : messages) {
            if (isBackgroundSuggestion(message)) { suggestions.add(message); continue; }
            if (message.getRole() != ChatMessage.Role.USER && message.getRole() != ChatMessage.Role.ASSISTANT) continue;
            foreground.append(message.getId()).append(chatMessageSignature(message));
            rows.add(new ChatPageController.Row(message.getId(), chatMessageSignature(message),
                    () -> collectChatRow(() -> addConversationMessage(message))));
        }
        if (!suggestions.isEmpty()) {
            String key = "suggestions:" + startOfDay(selectedChatDate.getTimeInMillis());
            String signature = suggestions.stream().map(this::chatMessageSignature)
                    .collect(java.util.stream.Collectors.joining("|"));
            rows.add(new ChatPageController.Row(key, signature, () -> collectChatRow(() -> {
                TextView summary = label((historical ? "当日建议" : "今日建议") + " · " + suggestions.size(), 14,
                        TEXT_MUTED, Typeface.NORMAL);
                summary.setMinHeight(dp(48)); summary.setGravity(Gravity.CENTER_VERTICAL);
                boolean expanded = presentation.chat.expandedDays.contains(key);
                ViewCompat.setStateDescription(summary, expanded ? "已展开" : "已收起");
                summary.setOnClickListener(view -> {
                    if (expanded) presentation.chat.expandedDays.remove(key);
                    else presentation.chat.expandedDays.add(key);
                    chatPageController.invalidate(key); refreshChatUi();
                });
                chatMessages.addView(summary);
                if (expanded) for (ChatMessage message : suggestions) addConversationMessage(message);
            })));
        }
        if (messages.isEmpty() && historical) {
            rows.add(new ChatPageController.Row("empty", "history", () -> collectChatRow(this::addChatHistoryEmptyState)));
        } else if (messages.isEmpty()) {
            rows.add(new ChatPageController.Row("empty", "current", () -> collectChatRow(() ->
                    chatMessages.addView(UiKit.emptyState(this, R.drawable.ic_sparkles,
                            "暂无对话", null, null), lp(-1, -2)))));
        }
        if (!historical && chatBusy) rows.add(new ChatPageController.Row("request", "busy",
                () -> collectChatRow(this::addAiThinkingBubble)));
        if (!historical && chatError != null && !chatError.trim().isEmpty()) {
            rows.add(new ChatPageController.Row("error", chatError, () -> collectChatRow(() -> addErrorCard(chatError))));
        }
        chatPageController.reconcile(target, rows);
        boolean foregroundChanged = chatPageController.foregroundChanged(foreground.append(chatBusy).toString());
        if (chatScroll != null) {
            if (foregroundChanged && presentation.followChat && !historical) scrollChatToBottom();
            else chatScroll.post(() -> chatScroll.scrollTo(0, previousScroll));
        }
        if (latestChatButton != null) latestChatButton.setVisibility(
                presentation.followChat || messages.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View collectChatRow(Runnable render) {
        LinearLayout target = chatMessages;
        LinearLayout group = column();
        group.setTag("conversation-row");
        group.setLayoutParams(lp(-1, -2));
        chatMessages = group;
        try { render.run(); } finally { chatMessages = target; }
        UiKit.polish(group);
        return group;
    }

    private String chatMessageSignature(ChatMessage message) {
        StringBuilder key = new StringBuilder().append(message.getUpdatedAt()).append(':')
                .append(message.getContent()).append(message.getActionState()).append(message.getActionItemStates());
        if (message.hasAction()) {
            try {
                AiPlan parsed = AiPlan.parse(message.getActionPayload());
                for (AiPlan.Action action : parsed.actions) key.append(actionPreview(action));
                ProposalPresentation bubble = proposalPresentation(message, parsed.actions.size());
                key.append(bubble.outcomeText).append(bubble.status).append(bubble.undoAvailable)
                        .append(bubble.undoUnavailableReason).append(bubble.focusPrepared);
            } catch (Exception ignored) { key.append("invalid"); }
        }
        return key.toString();
    }

    private boolean isBackgroundSuggestion(ChatMessage message) {
        if (message.getProposalKind() == ChatMessage.ProposalKind.PROACTIVE_PLAN) return true;
        if (message.getProposalKind() != ChatMessage.ProposalKind.PREFERENCE_CANDIDATE || localIntelligenceStore == null) return false;
        try {
            AiPlan plan = AiPlan.parse(message.getActionPayload());
            if (plan.actions.isEmpty()) return false;
            for (AiPlan.Action action : plan.actions) {
                if (action.getType() != AiPlan.Action.Type.ACTIVATE_PREFERENCE) return false;
                String id = String.valueOf(actionValue(action, "preferenceId"));
                boolean inferred = false;
                for (PreferenceRuleEntity rule : localIntelligenceStore.preferences()) {
                    if (id.equals(rule.id) && PreferenceRuleEntity.ORIGIN_INFERRED.equals(rule.origin)) inferred = true;
                }
                if (!inferred) return false;
            }
            return true;
        } catch (Exception unknownOrigin) { return false; }
    }

    private void addConversationMessage(ChatMessage message) {
        if (message.getRole() == ChatMessage.Role.USER) { addUserBubble(message); return; }
        if (message.hasAction()) { addActionCard(message); return; }
        if (!message.getContent().trim().isEmpty()) addAssistantBubble(message.getContent(), false);
        if (!message.getContextSources().isEmpty()) {
            ImageButton more = new ImageButton(this);
            more.setImageResource(R.drawable.ic_more);
            more.setImageTintList(android.content.res.ColorStateList.valueOf(TEXT_MUTED));
            more.setBackgroundColor(Color.TRANSPARENT); UiKit.tooltip(more, "消息更多操作");
            chatMessages.addView(more, lp(dp(48), dp(48)));
            more.setOnClickListener(view -> {
                androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, more);
                menu.getMenu().add(0, 1, 0, "复制");
                if (!message.getContextSources().isEmpty()) menu.getMenu().add(0, 2, 1, "读取来源");
                menu.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("时屿", message.getContent()));
                    } else new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("读取来源")
                            .setItems(message.getContextSources().toArray(new String[0]), null)
                            .setPositiveButton("关闭", null).show();
                    return true;
                }); menu.show();
            });
        }
    }

    private List<ChatMessage> chatMessagesForDate(long dateAt) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage message : repository.getChatMessages()) {
            if (sameDay(message.getConversationDay(), dateAt)) result.add(message);
        }
        return result;
    }

    private void addContextSourcesCard(List<String> sources) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(rounded(NEUTRAL, UiKit.RADIUS_CONTAINER));
        TextView summary = label("已读取 " + sources.size() + " 类应用数据", 12,
                TEXT_MUTED, Typeface.BOLD);
        summary.setMinHeight(dp(44));
        summary.setGravity(Gravity.CENTER_VERTICAL);
        TextView detail = label(android.text.TextUtils.join(" · ", sources), 12,
                TEXT_MUTED, Typeface.NORMAL);
        detail.setVisibility(View.GONE);
        card.addView(summary, lp(-1, dp(44)));
        card.addView(detail, marginTop(lp(-1, -2), dp(4)));
        summary.setOnClickListener(view -> {
            boolean show = detail.getVisibility() != View.VISIBLE;
            detail.setVisibility(show ? View.VISIBLE : View.GONE);
            summary.setText(show ? "收起读取来源" : "已读取 " + sources.size() + " 类应用数据");
        });
        applyPressFeedback(summary);
        LinearLayout.LayoutParams params = marginBottom(lp(-1, -2), dp(12));
        params.setMargins(dp(53), 0, dp(26), dp(12));
        chatMessages.addView(card, params);
    }

    private void addChatHistoryEmptyState() {
        addAssistantBubble("这一天没有聊天记录。点击我的头像可以重新选择日期，"
                + "也可以点击右下角返回今日聊天。", false);
    }

    private void configureChatActionButton(PageChatBinding binding, boolean configured,
                                           boolean historical) {
        if (historical) {
            binding.sendButton.setEnabled(true);
            binding.sendButton.setImageResource(R.drawable.ic_back);
            binding.sendButton.setContentDescription("返回今日聊天记录");
            binding.sendButton.setBackground(rounded(PRIMARY, UiKit.RADIUS_CONTAINER));
            binding.sendButton.setAlpha(1f);
            binding.sendButton.setOnClickListener(view -> returnToActiveChat());
            binding.sendButton.setOnLongClickListener(null);
            return;
        }
        boolean enabled = configured;
        binding.sendButton.setEnabled(enabled);
        binding.sendButton.setImageResource(chatBusy ? R.drawable.ic_stop : R.drawable.ic_send);
        binding.sendButton.setImageTintList(android.content.res.ColorStateList.valueOf(ON_BRAND));
        binding.sendButton.setContentDescription(chatBusy ? "停止请求" : "发送，长按语音输入");
        binding.sendButton.setBackground(UiKit.background(this, PRIMARY, 0));
        binding.sendButton.setAlpha(enabled ? 1f : .4f);
        binding.sendButton.setOnClickListener(view -> {
            if (!chatBusy) { presentation.followChat = true; sendCurrentInput(); return; }
            cancelPreparingSubmission();
            if (activeAgentRequest != null) activeAgentRequest.cancel();
            activeAgentRequest = null;
            if (aiClient != null) { aiClient.close(); aiClient = null; }
            chatBusy = false;
            ProactiveMessageBus.setUserAiBusy(false);
            chatError = chatPageViewModel.recoverableSubmission == null ? null : "附件尚未发送，可恢复未发送内容。";
            refreshChatUi();
        });
        binding.sendButton.setOnLongClickListener(view -> {
            if (chatBusy) return true;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            requestVoiceInput();
            return true;
        });
    }

    private boolean isAiConfigured() {
        return appGraph != null && appGraph.ai.configured();
    }

    private void addConfigurationGuide() {
        chatMessages.addView(chatPageController.disconnected(this, this::openAiSettings),
                verticalMargins(lp(-1, -2), dp(8), dp(16)));
    }

    private void showChatOverflowMenu(View anchor) {
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "聊天记录");
        menu.getMenu().add(0, 2, 1, "设置");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showChatHistoryCalendar();
            else openSettings();
            return true;
        });
        menu.show();
    }

    private ImageView chatAiAvatar() {
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.ic_time_isle_mark);
        avatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        avatar.setPadding(dp(2), dp(2), dp(2), dp(2));
        avatar.setContentDescription("AI 头像，点击查看聊天记录日历");
        avatar.setFocusable(true);
        avatar.setClickable(true);
        avatar.setOnClickListener(view -> showChatHistoryCalendar());
        applyPressFeedback(avatar);
        return avatar;
    }


    private void chooseAttachments() {
        if (!isAiConfigured()) {
            openAiSettings();
            return;
        }
        if (pendingAttachments.size() >= MAX_ATTACHMENTS) {
            showMessage("一次最多发送 " + MAX_ATTACHMENTS + " 个附件");
            return;
        }
        attachmentLauncher.launch(new String[]{"*/*"});
    }

    private void onAttachmentsSelected(List<Uri> values) {
        if (values == null || values.isEmpty()) return;
        for (Uri uri : values) {
            if (uri == null || pendingAttachments.size() >= MAX_ATTACHMENTS) break;
            boolean duplicate = false;
            for (PendingAttachment existing : pendingAttachments) {
                if (existing.uri.equals(uri)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) { }
            PendingAttachment attachment = describeAttachment(uri);
            if (attachment.size > MAX_ATTACHMENT_BYTES) {
                showMessage(attachment.name + " 超过 8 MB，暂时无法添加");
                continue;
            }
            pendingAttachments.add(attachment);
        }
        if (values.size() + pendingAttachments.size() > MAX_ATTACHMENTS) {
            showMessage("一次最多发送 " + MAX_ATTACHMENTS + " 个附件");
        }
        renderPendingAttachments();
    }

    private PendingAttachment describeAttachment(Uri uri) {
        String name = "附件";
        long size = -1;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (RuntimeException ignored) { }
        String mimeType = getContentResolver().getType(uri);
        if (mimeType == null || mimeType.trim().isEmpty()) mimeType = "application/octet-stream";
        return new PendingAttachment(uri, name, mimeType, size);
    }

    private void renderPendingAttachments() {
        if (attachmentPreview == null || attachmentPreviewScroll == null) return;
        if (isViewingHistoricalChat()) {
            attachmentPreviewScroll.setVisibility(View.GONE);
            return;
        }
        attachmentPreviewScroll.setVisibility(
                pendingAttachments.isEmpty() ? View.GONE : View.VISIBLE);
        String signature = pendingAttachments.stream().map(item -> item.uri.toString())
                .collect(java.util.stream.Collectors.joining("|"));
        if (signature.equals(attachmentPreview.getTag())) return;
        attachmentPreview.setTag(signature);
        attachmentPreview.removeAllViews();
        for (int index = 0; index < pendingAttachments.size(); index++) {
            PendingAttachment attachment = pendingAttachments.get(index);
            final int target = index;
            if (attachment.isImage()) {
                LinearLayout preview = row();
                preview.setGravity(Gravity.CENTER_VERTICAL);
                preview.setBackground(rounded(PRIMARY_SOFT, UiKit.RADIUS_CONTAINER));
                preview.setClipToOutline(true);
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setContentDescription("预览图片：" + attachment.name);
                image.setOnClickListener(view -> previewAttachment(attachment));
                try { image.setImageURI(attachment.uri); }
                catch (RuntimeException ignored) { image.setImageDrawable(null); }
                preview.addView(image, lp(dp(64), dp(64)));
                ImageButton remove = attachmentRemoveButton(attachment.name);
                preview.addView(remove, lp(dp(48), dp(48)));
                remove.setOnClickListener(view -> {
                    if (target < pendingAttachments.size()) pendingAttachments.remove(target);
                    renderPendingAttachments();
                });
                LinearLayout.LayoutParams params = lp(dp(112), dp(64));
                if (index > 0) params.setMarginStart(dp(8));
                attachmentPreview.addView(preview, params);
                continue;
            }
            LinearLayout file = row(); file.setGravity(Gravity.CENTER_VERTICAL);
            TextView chip = label(attachment.name, 14, TEXT, Typeface.NORMAL);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setBackground(rounded(PRIMARY_SOFT, UiKit.RADIUS_PILL));
            chip.setMaxWidth(dp(192)); chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setMinHeight(dp(48));
            chip.setOnClickListener(view -> previewAttachment(attachment));
            file.addView(chip, lp(-2, -2));
            ImageButton remove = attachmentRemoveButton(attachment.name);
            file.addView(remove, lp(dp(48), dp(48)));
            remove.setOnClickListener(view -> {
                if (target < pendingAttachments.size()) pendingAttachments.remove(target);
                renderPendingAttachments();
            });
            chip.setContentDescription("查看附件：" + attachment.name);
            LinearLayout.LayoutParams params = lp(-2, dp(64));
            if (index > 0) params.setMarginStart(dp(8));
            attachmentPreview.addView(file, params);
        }
    }

    private ImageButton attachmentRemoveButton(String name) {
        ImageButton remove = new ImageButton(this);
        remove.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        remove.setImageTintList(android.content.res.ColorStateList.valueOf(TEXT_MUTED));
        remove.setBackgroundColor(Color.TRANSPARENT);
        UiKit.tooltip(remove, "移除附件：" + name);
        return remove;
    }

    private void previewAttachment(PendingAttachment attachment) {
        AlertDialog.Builder dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle(attachment.name)
                .setNegativeButton("关闭", null);
        if (attachment.isImage()) {
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageURI(attachment.uri); dialog.setView(image);
        } else {
            dialog.setMessage(attachment.mimeType + " · " + formatAttachmentSize(attachment.size));
            dialog.setPositiveButton("打开", (d, w) -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(attachment.uri, attachment.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
                catch (RuntimeException failed) { showMessage("没有可用的附件查看程序"); }
            });
        }
        dialog.show();
    }

    private void restoreComposerFocusIfNeeded(EditText input) {
        if (!keepKeyboardAfterSend || input == null || isViewingHistoricalChat()) return;
        input.post(() -> {
            if (!keepKeyboardAfterSend || !input.isAttachedToWindow()
                    || isViewingHistoricalChat()) return;
            input.requestFocus();
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            keepKeyboardAfterSend = false;
        });
    }

    private void requestVoiceInput() {
        if (voiceInput != null) { voiceInput.start(); return; }
        if (isViewingHistoricalChat()) {
            showMessage("历史聊天记录仅供查看");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 701);
            showMessage("请允许麦克风权限后再次长按发送键");
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "说说你想完成什么");
        try { voiceLauncher.launch(intent); }
        catch (RuntimeException exception) { showMessage("当前设备不支持语音输入"); }
    }

    private void sendCurrentInput() {
        if (isViewingHistoricalChat()) {
            showMessage("历史聊天记录仅供查看");
            return;
        }
        if (chatInput == null || chatBusy) return;
        String message = chatInput.getText().toString().trim();
        if (message.isEmpty() && pendingAttachments.isEmpty()) return;
        List<PendingAttachment> selected = new ArrayList<>(pendingAttachments);
        ComposerSubmission<PendingAttachment> submission = new ComposerSubmission<>(message, selected,
                appGraph.ai.profiles.contextBoundary(), activeChatDay);
        chatPageViewModel.preparingSubmission = submission;
        chatInput.setText("");
        pendingAttachments.removeAll(selected);
        submission.clearedRevision = chatPageViewModel.composerRevision;
        renderPendingAttachments();
        keepKeyboardAfterSend = true;
        if (selected.isEmpty()) {
            if (!submission.accept(appGraph.ai.profiles.contextBoundary(), activeChatDay)) return;
            chatPageViewModel.preparingSubmission = null;
            submitMessage(message);
            return;
        }
        chatBusy = true;
        refreshChatUi();
        attachmentExecutor.execute(() -> {
            try {
                List<AiClient.Attachment> payloads = readAttachments(selected);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (chatPageViewModel.preparingSubmission != submission) return;
                    if (isViewingHistoricalChat()
                            || !submission.accept(appGraph.ai.profiles.contextBoundary(), activeChatDay)) {
                        cancelPreparingSubmission(); refreshChatUi(); return;
                    }
                    chatPageViewModel.preparingSubmission = null;
                    chatBusy = false;
                    submitMessage(message, selected, payloads);
                });
            } catch (IOException | IllegalArgumentException | SecurityException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (chatPageViewModel.preparingSubmission != submission) return;
                    cancelPreparingSubmission();
                    chatBusy = false;
                    recordAiError(error, true);
                    chatError = "这个附件暂时无法上传，请换一个较小或常见格式的文件后重试。";
                    refreshChatUi();
                });
            }
        });
    }

    private void cancelPreparingSubmission() {
        if (chatPageViewModel == null) return;
        ComposerSubmission<PendingAttachment> submission = chatPageViewModel.preparingSubmission;
        if (submission == null) return;
        chatPageViewModel.preparingSubmission = null;
        if (!submission.cancel()) return;
        chatBusy = false;
        if (submission.clearedRevision == chatPageViewModel.composerRevision && chatDraft.isEmpty()
                && pendingAttachments.isEmpty()) restoreComposerSubmission(submission);
        else chatPageViewModel.recoverableSubmission = submission;
        if (chatPageViewModel.recoverableSubmission != null && chatError == null)
            chatError = "附件尚未发送，可恢复未发送内容；当前输入已保留。";
    }

    private void restoreComposerSubmission(ComposerSubmission<PendingAttachment> submission) {
        chatDraft = submission.text;
        if (chatInput != null) { chatInput.setText(chatDraft); chatInput.setSelection(chatInput.length()); }
        pendingAttachments.clear(); pendingAttachments.addAll(submission.attachments);
        chatPageViewModel.recoverableSubmission = null;
        renderPendingAttachments();
    }

    private void submitMessage(String message) {
        submitMessage(message, Collections.emptyList(), Collections.emptyList());
    }

    private ChatMessage addChatMessageToActiveSession(ChatMessage message) {
        return repository.addChatMessage(message.withConversationDay(activeChatDay));
    }

    private void submitMessage(String message, List<PendingAttachment> selected,
                               List<AiClient.Attachment> payloads) {
        if (isViewingHistoricalChat()) {
            showMessage("请先回到当前聊天");
            return;
        }
        String persistedMessage = message == null ? "" : message.trim();
        if (persistedMessage.isEmpty() && !selected.isEmpty()) persistedMessage = "请查看我发送的附件";
        List<ChatMessage.Attachment> attachmentMetadata = new ArrayList<>();
        for (PendingAttachment attachment : selected) {
            attachmentMetadata.add(new ChatMessage.Attachment(
                    attachment.name, attachment.mimeType, attachment.size));
        }
        ChatMessage userMessage = addChatMessageToActiveSession(
                new ChatMessage(ChatMessage.Role.USER, persistedMessage, null,
                        ChatMessage.ActionState.NONE, attachmentMetadata));
        chatError = null;
        String normalized = message.replaceAll("[\\s，。！？!?]", "");
        if (payloads.isEmpty() && isDirectScheduleNavigation(normalized)) {
            addChatMessageToActiveSession(new ChatMessage(
                    ChatMessage.Role.ASSISTANT, "我打开今天的时间线，你可以直接调整任务。"));
            showPage(PAGE_SCHEDULE);
            return;
        }
        if (payloads.isEmpty() && isDirectFocusNavigation(normalized)) {
            addChatMessageToActiveSession(new ChatMessage(ChatMessage.Role.ASSISTANT,
                    "好的，专注空间已经准备好；开始前可以选择要执行的任务。"));
            showPage(PAGE_FOCUS);
            return;
        }
        if (!isAiConfigured()) {
            addChatMessageToActiveSession(new ChatMessage(ChatMessage.Role.ASSISTANT,
                    "我已经记下你的想法，但还没有执行任何修改。请先在设置中配置 AI 服务。"));
            openAiSettings();
            return;
        }
        requestAiResponse(userMessage.getId(), message, payloads);
    }

    private void requestAiResponse(String userMessageId, String message,
                                   List<AiClient.Attachment> payloads) {
        List<AiClient.Message> history = new ArrayList<>();
        for (ChatMessage item : chatMessagesForDate(activeChatDay)) {
            if (item.getCreatedAt() < appGraph.ai.profiles.contextStartedAt()) continue;
            if (item.getRole() == ChatMessage.Role.USER) {
                if (item.getId().equals(userMessageId) && !payloads.isEmpty()) {
                    history.add(AiClient.Message.userWithAttachments(message, payloads));
                } else {
                    history.add(AiClient.Message.user(userMessageForAiHistory(item)));
                }
            }
            else if (item.getRole() == ChatMessage.Role.ASSISTANT && !item.getContent().trim().isEmpty()) {
                String assistantContext = item.getContent();
                if (item.hasAction()) {
                    String proposal = valueOrEmpty(item.getActionPayload());
                    String itemHistory = describeActionItemHistory(item);
                    if (proposal.length() <= 12_000) {
                        assistantContext += "\n\n[此前操作提案，状态="
                                + item.getActionState().storageValue() + itemHistory + "]\n" + proposal;
                    } else {
                        assistantContext += "\n\n[此前操作提案过长，已省略；状态="
                                + item.getActionState().storageValue() + itemHistory + "]";
                    }
                }
                history.add(AiClient.Message.assistant(assistantContext));
            }
        }
        if (history.size() > 24) history = new ArrayList<>(history.subList(history.size() - 24, history.size()));
        lastAiRequest = new LastAiRequest(userMessageId, message, payloads);
        chatBusy = true;
        ProactiveMessageBus.setUserAiBusy(true);
        refreshChatUi();
        try {
            if (preferenceLearningCoordinator != null) {
                preferenceLearningCoordinator.cancelForUserRequest();
            }
            ensureAiClient();
            agentRuntime = new AgentRuntime(aiClient, contextBroker);
            activeAgentRequest = agentRuntime.run(promptCompiler.compile(), history,
                    new AgentRuntime.Callback() {
                @Override public void onSuccess(AgentRuntime.Result runtimeResult) {
                    activeAgentRequest = null;
                    chatBusy = false;
                    ProactiveMessageBus.setUserAiBusy(false);
                    chatError = null;
                    lastAiRequest = null;
                    AiPlan plan = runtimeResult.plan;
                    String payload = plan.actions.isEmpty() ? null : plan.rawJson;
                    ChatMessage.ActionState state = payload == null
                            ? ChatMessage.ActionState.NONE : ChatMessage.ActionState.PENDING_CONFIRMATION;
                    ChatMessage.ProposalKind kind = proposalKindFor(plan);
                    if (payload != null) {
                        cancelSupersededProposals(activeChatDay, kind);
                        if (kind == ChatMessage.ProposalKind.TASK_PLAN) {
                            repository.cancelPendingChatActions(
                                    activeChatDay, ChatMessage.ProposalKind.PROACTIVE_PLAN);
                        }
                    }
                    ChatMessage assistant = new ChatMessage(ChatMessage.Role.ASSISTANT,
                            plan.message, payload, state, kind, Collections.emptyList())
                            .withContextSources(runtimeResult.sources);
                    addChatMessageToActiveSession(assistant);
                }

                @Override public void onError(AiClient.AiError error) {
                    activeAgentRequest = null;
                    chatBusy = false;
                    ProactiveMessageBus.setUserAiBusy(false);
                    recordAiError(error, !payloads.isEmpty());
                    chatError = friendlyAiError(error, !payloads.isEmpty());
                    refreshChatUi();
                }
            });
        } catch (RuntimeException exception) {
            activeAgentRequest = null;
            chatBusy = false;
            ProactiveMessageBus.setUserAiBusy(false);
            recordAiError(exception, !payloads.isEmpty());
            chatError = friendlyAiError(exception, !payloads.isEmpty());
            refreshChatUi();
        }
    }

    private ChatMessage.ProposalKind proposalKindFor(AiPlan plan) {
        boolean preference = false;
        boolean external = false;
        for (AiPlan.Action action : plan.actions) {
            switch (action.getType()) {
                case CREATE_PREFERENCE:
                case UPDATE_PREFERENCE:
                case DELETE_PREFERENCE:
                case ACTIVATE_PREFERENCE:
                    preference = true;
                    break;
                case COMPOSE_CALENDAR_EVENT:
                    external = true;
                    break;
                default:
                    break;
            }
        }
        if (external) return ChatMessage.ProposalKind.EXTERNAL_HANDOFF;
        if (preference) return ChatMessage.ProposalKind.PREFERENCE_CANDIDATE;
        return ChatMessage.ProposalKind.TASK_PLAN;
    }

    private void cancelSupersededProposals(long conversationDay,
                                           ChatMessage.ProposalKind kind) {
        if (kind == ChatMessage.ProposalKind.PREFERENCE_CANDIDATE) {
            for (ChatMessage message : repository.getChatMessages()) {
                if (message.getConversationDay() != conversationDay
                        || message.getProposalKind() != kind
                        || message.getActionState() != ChatMessage.ActionState.PENDING_CONFIRMATION
                        || !message.hasAction()) continue;
                try {
                    AiPlan previous = AiPlan.parse(message.getActionPayload());
                    List<Integer> indexes = new ArrayList<>();
                    for (int index = 0; index < previous.actions.size(); index++) {
                        if (message.getActionItemState(index) == ChatMessage.ActionItemState.PENDING) {
                            indexes.add(index);
                        }
                    }
                    rejectCancelledPreferenceCandidates(message.getId(), indexes);
                } catch (Exception ignored) { }
            }
        }
        repository.cancelPendingChatActions(conversationDay, kind);
    }

    private List<AiClient.Attachment> readAttachments(List<PendingAttachment> selected)
            throws IOException {
        List<AiClient.Attachment> result = new ArrayList<>();
        int totalBytes = 0;
        byte[] buffer = new byte[16 * 1024];
        for (PendingAttachment attachment : selected) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = getContentResolver().openInputStream(attachment.uri)) {
                if (input == null) throw new IOException("无法读取附件：" + attachment.name);
                int count;
                while ((count = input.read(buffer)) != -1) {
                    totalBytes += count;
                    if (output.size() + count > MAX_ATTACHMENT_BYTES) {
                        throw new IOException("附件超过 8 MB：" + attachment.name);
                    }
                    if (totalBytes > MAX_TOTAL_ATTACHMENT_BYTES) {
                        throw new IOException("附件总大小超过 16 MB");
                    }
                    output.write(buffer, 0, count);
                }
            }
            if (output.size() == 0) throw new IOException("附件内容为空：" + attachment.name);
            result.add(new AiClient.Attachment(attachment.name, attachment.mimeType,
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)));
        }
        return Collections.unmodifiableList(result);
    }

    private String userMessageForAiHistory(ChatMessage message) {
        StringBuilder value = new StringBuilder(valueOrEmpty(message.getContent()).trim());
        if (!message.getAttachments().isEmpty()) {
            if (value.length() == 0) value.append("请查看我发送的附件");
            value.append("\n\n此前附件：");
            for (int index = 0; index < message.getAttachments().size(); index++) {
                if (index > 0) value.append("、");
                value.append(message.getAttachments().get(index).getName());
            }
        }
        return value.toString();
    }

    private void retryLastAiRequest() {
        if (chatBusy) return;
        chatError = null;
        if (lastAiRequest != null) {
            requestAiResponse(lastAiRequest.userMessageId, lastAiRequest.message,
                    lastAiRequest.attachments);
            return;
        }
        List<ChatMessage> messages = chatMessagesForDate(activeChatDay);
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage previous = messages.get(index);
            if (previous.getRole() == ChatMessage.Role.USER) {
                requestAiResponse(previous.getId(), previous.getContent(), Collections.emptyList());
                return;
            }
        }
    }

    private void recordAiError(Throwable error, boolean hadAttachments) {
        chatErrorStatus = error instanceof AiClient.AiError
                ? ((AiClient.AiError) error).connectionStatus : null;
        if (aiErrorLogStore == null || repository == null) return;
        AppSettings settings = repository.getSettings();
        aiErrorLogStore.append(error, settings.getBaseUrl(), settings.getModel(), hadAttachments);
    }

    private String friendlyAiError(Throwable error, boolean hadAttachments) {
        if (!(error instanceof AiClient.AiError)) {
            return hadAttachments
                    ? "附件这次没有发送成功，请稍后重试或换一个文件。"
                    : "这次消息没有发送成功，请稍后再试。";
        }
        AiClient.AiError aiError = (AiClient.AiError) error;
        if (aiError.connectionStatus != null) return aiError.getMessage();
        int status = aiError.getHttpStatus();
        if (hadAttachments && (status == 400 || status == 413
                || status == 415 || status == 422)) {
            return "这个模型可能不支持上传附件。请移除附件后重试，或在设置中更换支持附件的模型。";
        }
        switch (aiError.getKind()) {
            case NETWORK:
                return "暂时没有连上 AI 服务，请检查网络后再试。";
            case PARSE:
                return "AI 已经回复，但结果暂时无法正确读取，请重试一次。";
            case CONFIGURATION:
                return "AI 服务还没有准备好，请到设置中检查配置。";
            case HTTP:
                if (status == 401 || status == 403) {
                    return "AI 服务暂时无法验证配置，请到设置中检查连接。";
                }
                if (status == 404) return "暂时找不到可用的 AI 服务，请检查设置后再试。";
                if (status == 408) return "AI 服务等待太久了，请稍后重试。";
                if (status == 429) return "AI 服务现在有些忙，请稍后再试。";
                if (status >= 500) return "AI 服务暂时不可用，请过一会儿再试。";
                return "这次请求暂时无法处理，请调整内容后重试。";
            case CANCELLED:
                return "这次请求已取消。";
            default:
                return "这次请求没有完成，请稍后再试。";
        }
    }

    private boolean isDirectScheduleNavigation(String normalized) {
        return "今天安排".equals(normalized)
                || "今天的安排".equals(normalized)
                || "今日安排".equals(normalized)
                || "今日的安排".equals(normalized)
                || "查看今天安排".equals(normalized)
                || "查看今天的安排".equals(normalized)
                || "查看今日安排".equals(normalized)
                || "看看今天安排".equals(normalized)
                || "看看今日安排".equals(normalized)
                || "打开今天日程".equals(normalized)
                || "打开今日日程".equals(normalized);
    }

    private boolean isDirectFocusNavigation(String normalized) {
        return "开始专注".equals(normalized)
                || "打开专注".equals(normalized)
                || "进入专注".equals(normalized)
                || "开始番茄钟".equals(normalized)
                || "打开番茄钟".equals(normalized);
    }

    private String buildSystemPrompt() {
        return promptCompiler.compile();
    }

    private void ensureAiClient() {
        String boundary = appGraph.ai.profiles.contextBoundary();
        if (aiClient != null && !aiClientBoundary.equals(boundary)) { aiClient.close(); aiClient = null; }
        if (aiClient == null) {
            aiClient = appGraph.ai.client();
            aiClientBoundary = boundary;
        }
    }

    private void openAiBrowser(String url) {
        try {
            Uri uri = Uri.parse(url);
            com.hawson.timeassistant.auth.OAuthSignInService.start(this);
            if (!oauthBrowser.launch(uri)) showMessage("授权地址无效，请重新开始官方登录");
        }
        catch (android.content.ActivityNotFoundException missing) { showMessage("未找到可用浏览器"); }
    }

    private void beforeAiConnectionChange(Runnable change) {
        Runnable apply = () -> {
            cancelPreparingSubmission();
            appGraph.ai.cancelAll();
            if (activeAiRequest != null) activeAiRequest.cancel();
            if (activeAgentRequest != null) activeAgentRequest.cancel();
            if (preferenceLearningCoordinator != null) preferenceLearningCoordinator.cancelForUserRequest();
            if (aiClient != null) { aiClient.close(); aiClient = null; }
            chatBusy = false; ProactiveMessageBus.setUserAiBusy(false);
            change.run();
        };
        if (!chatBusy) { apply.run(); return; }
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("停止当前 AI 请求？")
                .setMessage("未完成的请求将取消。新连接不会自动接收之前的聊天上下文。")
                .setNegativeButton("继续当前请求", null)
                .setPositiveButton("停止并更改", (d, w) -> apply.run()).show();
    }

    private TextView addAssistantBubble(String message, boolean scroll) {
        ChatBubbleLayout bubble = ChatBubbleFactory.assistant(this, message, NEUTRAL, TEXT);
        chatMessages.addView(bubble, marginBottom(lp(-2, -2), dp(10)));
        if (scroll) scrollChatToBottom();
        return (TextView) bubble.getChildAt(0);
    }

    private void addAiThinkingBubble() {
        aiThinkingText = label("正在处理", 14, TEXT_MUTED, Typeface.NORMAL);
        aiThinkingText.setMinHeight(dp(48)); aiThinkingText.setGravity(Gravity.CENTER_VERTICAL);
        aiThinkingText.setWidth((int) Math.ceil(aiThinkingText.getPaint().measureText("正在处理...")) + dp(32)); aiThinkingText.setPadding(dp(16), 0, dp(16), 0);
        aiThinkingText.setBackground(rounded(NEUTRAL, UiKit.RADIUS_CONTAINER));
        aiThinkingText.setContentDescription("正在处理");
        aiThinkingText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        chatMessages.addView(aiThinkingText);
        mainHandler.removeCallbacks(aiThinkingRunnable); mainHandler.post(aiThinkingRunnable);
    }

    private void stopAiThinkingAnimation() {
        mainHandler.removeCallbacks(aiThinkingRunnable);
        aiThinkingText = null;
    }

    private void addUserBubble(ChatMessage message) {
        LinearLayout line = ChatBubbleFactory.user(this, message, PRIMARY_SOFT, TEXT, TEXT_MUTED);
        chatMessages.addView(line, marginBottom(lp(-1, -2), dp(10)));
    }

    private String formatAttachmentSize(long bytes) {
        if (bytes <= 0) return "附件";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return Math.max(1, bytes / 1024L) + " KB";
        return String.format(LOCALE, "%.1f MB", bytes / (1024d * 1024d));
    }

    private void addErrorCard(String error) {
        LinearLayout card = column();
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.setBackground(rounded(ERROR_SOFT, UiKit.RADIUS_CONTAINER));
        card.addView(label(error, 14, TEXT, Typeface.NORMAL),
                marginTop(lp(-1, -2), dp(6)));
        LinearLayout actions = row();
        boolean typed = error.equals(chatError) && chatErrorStatus != null;
        boolean authorization = typed
                ? chatErrorStatus == com.hawson.timeassistant.connections.AiConnectionException.Status.LOGIN_REQUIRED
                    || chatErrorStatus == com.hawson.timeassistant.connections.AiConnectionException.Status.PERMISSION_DENIED
                : error.contains("授权") || error.contains("登录") || error.contains("权限") || error.contains("配置");
        boolean usage = typed
                ? chatErrorStatus == com.hawson.timeassistant.connections.AiConnectionException.Status.USAGE_LIMITED
                : error.contains("额度") || error.contains("用量");
        Button action = button(authorization ? "检查连接" : usage ? "查看用量" : "重试", PRIMARY, Color.TRANSPARENT);
        action.setOnClickListener(v -> {
            if (authorization) openAiSettings();
            else if (usage) aiTools().showAccountsAndUsage();
            else retryLastAiRequest();
        });
        actions.addView(action, lp(-2, -2));
        card.addView(actions, marginTop(lp(-1, -2), dp(4)));
        if (chatPageViewModel != null && chatPageViewModel.recoverableSubmission != null) {
            Button restore = button("恢复未发送内容", TEXT_MUTED, Color.TRANSPARENT);
            restore.setOnClickListener(v -> new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                    .setTitle("恢复未发送内容？").setMessage("将替换当前输入框中的文字和附件，聊天记录不会改变。")
                    .setNegativeButton("继续编辑", null).setPositiveButton("恢复", (d, w) -> {
                        ComposerSubmission<PendingAttachment> saved = chatPageViewModel.recoverableSubmission;
                        if (saved != null) restoreComposerSubmission(saved);
                        refreshChatUi();
                    }).show());
            card.addView(restore, lp(-1, -2));
        }
        chatMessages.addView(card, verticalMargins(lp(-1, -2), dp(8), dp(12)));
    }

    private void addActionCard(ChatMessage message) {
        final AiPlan plan;
        try { plan = AiPlan.parse(message.getActionPayload()); }
        catch (Exception invalid) { addErrorCard("这份安排暂时无法读取"); return; }
        List<Integer> pending = pendingActionIndexes(message, plan.actions.size());
        ProposalView proposal = new ProposalView(this, message, message.getContent(),
                proposalPresentation(message, plan.actions.size()), presentation.chat, new ProposalView.Host() {
            public String description(int index) { return actionPreview(plan.actions.get(index)); }
            public String status(int index) { return actionItemStateLabel(message.getActionItemState(index)); }
            public void confirm(List<Integer> indexes) { confirmPlan(message, plan, indexes); refreshChatUi(); }
            public void cancel(List<Integer> indexes) {
                cancelPlanActions(message.getId(), indexes, plan.actions.size()); refreshChatUi();
            }
            public void changed() {
                chatPageController.invalidate(message.getId());
                chatPageController.invalidate("suggestions:" + startOfDay(message.getConversationDay()));
                refreshChatUi();
            }
            public void undo() { undoProposal(message.getId()); }
            public void view(boolean focusPrepared) { showPage(focusPrepared ? PAGE_FOCUS : PAGE_SCHEDULE); }
            public void sources(List<String> sources) {
                new com.hawson.timeassistant.design.SoftAlertDialogBuilder(TimeAssistantActivity.this)
                        .setTitle("读取来源").setItems(sources.toArray(new String[0]), null)
                        .setPositiveButton("关闭", null).show();
            }
        });
        chatMessages.addView(proposal, marginBottom(lp(-1, -2), dp(16)));
    }

    private ProposalPresentation proposalPresentation(ChatMessage message, int count) {
        TaskActionExecutor.ExecutionResult result = chatPageViewModel.proposalUndoResult(message.getId());
        boolean available = result != null && result.canUndo(repository);
        String reason = result == null ? "当前会话没有可用的撤销记录"
                : available ? "" : "数据已变化或此操作已撤销，无法安全撤销";
        return chatPageViewModel.proposalPresentation(message, count,
                pendingActionIndexes(message, count), isViewingHistoricalChat(), available, reason);
    }

    private void undoProposal(String messageId) {
        TaskActionExecutor.ExecutionResult result = chatPageViewModel.proposalUndoResult(messageId);
        if (result == null) return;
        try {
            result.undo(repository);
            chatPageViewModel.recordProposalResult(messageId, "已撤销", null, false);
            if (behaviorEventRecorder != null) behaviorEventRecorder.recordAction(
                    BehaviorEventEntity.AI_ACTION_REVERTED, "proposal_reverted");
        } catch (TaskActionExecutor.ExecutionException | RuntimeException error) {
            showMessage(safeError(error));
        }
        chatPageController.invalidate(messageId);
        chatPageController.invalidate("suggestions:" + startOfDay(selectedChatDate.getTimeInMillis()));
        refreshChatUi();
    }

    private String actionPreview(AiPlan.Action action) {
        String description = actionLabel(action);
        Object taskId = actionValue(action, "taskId", "task_id", "id");
        TaskItem task = taskId == null ? null : repository.getTask(String.valueOf(taskId));
        if (task != null && (action.getType() == AiPlan.Action.Type.UPDATE_TASK
                || action.getType() == AiPlan.Action.Type.SCHEDULE_TASK)) {
            String before = task.isScheduled() ? formatDateTime(task.getScheduledStartAt()) : "未安排";
            Object next = actionValue(action, "startAt", "start_at", "scheduledStartAt");
            Object changes = actionValue(action, "changes");
            if (next == null && changes instanceof Map) next = mapValue((Map<?, ?>) changes,
                    "startAt", "start_at", "scheduledStartAt");
            if (next != null) {
                return task.getTitle() + "\n" + before + " → " + displayActionTime(next);
            }
        }
        return description;
    }

    private List<Integer> selectedProposalIndexes(String messageId, List<Integer> pending) {
        Set<Integer> selection = proposalSelection.get(messageId);
        List<Integer> result = new ArrayList<>();
        for (Integer index : pending) if (selection != null && selection.contains(index)) result.add(index);
        return result;
    }

    private String actionLabel(AiPlan.Action action) {
        Object title = actionValue(action, "title");
        Object taskIdValue = actionValue(action, "taskId", "task_id", "id");
        if (title == null && taskIdValue != null) {
            TaskItem task = repository.getTask(String.valueOf(taskIdValue));
            title = task == null ? taskIdValue : task.getTitle();
        }
        String name = title == null ? "任务" : String.valueOf(title);
        switch (action.getType()) {
            case CREATE_TASK:
                return "创建任务：" + name
                        + actionDurationSuffix(actionValue(action, "estimatedMinutes",
                        "estimated_minutes", "estimatedDurationMinutes", "durationMinutes"))
                        + actionTimeSuffix(actionValue(action, "scheduledStartAt", "startAt", "start_at"))
                        + actionDeadlineSuffix(actionValue(action, "dueAt", "due_at", "deadline"))
                        + actionReminderSuffix(actionValue(action, "reminderAt", "reminder_at"))
                        + actionCountdownSuffix(
                        actionValue(action, "countdownMinutes", "countdown_minutes"),
                        actionValue(action, "repeatCountdown", "repeat_countdown"))
                        + describeSubtasks(actionValue(action, "subtasks"));
            case UPDATE_TASK:
                return "修改任务：" + name + describeActionChanges(action.get("changes"));
            case DELETE_TASK: return "删除任务：" + name;
            case CANCEL_TASK: return "取消任务：" + name;
            case COMPLETE_TASK: return "标记完成：" + name;
            case SCHEDULE_TASK:
                return "安排任务：" + name
                        + actionTimeSuffix(actionValue(action, "startAt", "start_at", "scheduledStartAt"))
                        + actionDurationSuffix(actionValue(action, "durationMinutes",
                        "duration_minutes", "estimatedDurationMinutes"));
            case SPLIT_TASK: return "拆分任务：" + name + describeSubtasks(action.get("subtasks"));
            case START_FOCUS:
                return "开始专注：" + name
                        + actionDurationSuffix(actionValue(action, "durationMinutes", "duration_minutes"));
            case SET_GREETING:
                return "更新问候语：" + String.valueOf(actionValue(action, "text"));
            case CREATE_RECURRING_GROUP:
                return "创建周期日程：" + name
                        + actionTimeSuffix(actionValue(action, "startAt", "start_at"))
                        + actionDurationSuffix(actionValue(action,
                        "durationMinutes", "duration_minutes"));
            case UPDATE_RECURRING_GROUP:
                return "修改周期日程：" + String.valueOf(actionValue(action, "groupId"));
            case STOP_RECURRING_GROUP:
                return "结束周期日程：" + String.valueOf(actionValue(action, "groupId"));
            case SET_RECURRING_OVERRIDE:
                return "设置单次周期例外：" + String.valueOf(
                        actionValue(action, "operation"));
            case CREATE_PREFERENCE:
                return "新增安排偏好：" + preferenceActionSummary(action);
            case UPDATE_PREFERENCE:
                return "修改安排偏好：" + String.valueOf(actionValue(action, "preferenceId"));
            case DELETE_PREFERENCE:
                return "删除安排偏好：" + String.valueOf(actionValue(action, "preferenceId"));
            case ACTIVATE_PREFERENCE:
                Object summary = actionValue(action, "summary");
                return summary == null ? "启用建议偏好" : "启用建议偏好：" + summary;
            case COMPOSE_CALENDAR_EVENT:
                return "在系统日历中填写：" + name
                        + actionTimeSuffix(actionValue(action, "startAt", "start_at"));
            default: return action.getWireType();
        }
    }

    private String preferenceActionSummary(AiPlan.Action action) {
        int start = integerActionValue(actionValue(action, "startMinute"), -1);
        int end = integerActionValue(actionValue(action, "endMinute"), -1);
        String type = String.valueOf(actionValue(action, "preferenceType"));
        String window = start >= 0 && end >= 0
                ? formatMinute(start) + "–" + formatMinute(end) : "时间窗口";
        return (PreferenceRuleEntity.TYPE_AVOID_WINDOW.equals(type) ? "避开 " : "偏好 ") + window;
    }

    private int integerActionValue(Object value, int fallback) {
        if (value instanceof java.math.BigDecimal) return ((java.math.BigDecimal) value).intValue();
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private Object actionValue(AiPlan.Action action, String... names) {
        for (String name : names) {
            Object value = action.get(name);
            if (value != null) return value;
        }
        return null;
    }

    private Object mapValue(Map<?, ?> values, String... names) {
        for (String name : names) {
            if (values.containsKey(name)) return values.get(name);
        }
        return null;
    }

    private String actionDurationSuffix(Object value) {
        if (value == null) return "";
        try {
            return " · " + TaskActionExecutor.positiveInt(value, "durationMinutes") + " 分钟";
        } catch (TaskActionExecutor.ExecutionException ignored) {
            return " · 时长 " + value;
        }
    }

    private String actionTimeSuffix(Object value) {
        return value == null ? "" : " · " + displayActionTime(value);
    }

    private String actionDeadlineSuffix(Object value) {
        return value == null ? "" : " · 截止 " + displayActionTime(value);
    }

    private String actionCountdownSuffix(Object minutesValue, Object repeatValue) {
        if (minutesValue == null) return "";
        try {
            int minutes = Integer.parseInt(String.valueOf(minutesValue));
            if (minutes <= 0) return "，清除倒计时";
            boolean repeat = Boolean.parseBoolean(String.valueOf(repeatValue));
            return repeat ? "，每 " + formatDuration(minutes) + " 循环提醒"
                    : "，确认执行后 " + formatDuration(minutes) + " 提醒";
        } catch (NumberFormatException ignored) {
            return "，设置倒计时";
        }
    }

    private String actionReminderSuffix(Object value) {
        return value == null ? "" : " · 提醒 " + displayActionTime(value);
    }

    private String describeActionChanges(Object rawChanges) {
        if (!(rawChanges instanceof Map)) return "";
        Map<?, ?> changes = (Map<?, ?>) rawChanges;
        List<String> details = new ArrayList<>();
        Object changedTitle = mapValue(changes, "title");
        if (changedTitle != null) details.add("标题→" + changedTitle);
        Object priority = mapValue(changes, "priority");
        if (priority != null) details.add("优先级→" + priority);
        Object duration = mapValue(changes, "estimatedMinutes", "estimated_minutes",
                "estimatedDurationMinutes", "durationMinutes", "duration_minutes");
        if (duration != null) details.add(actionDurationSuffix(duration).replace(" · ", ""));
        Object start = mapValue(changes, "scheduledStartAt", "startAt", "start_at");
        if (start != null) details.add("时间→" + displayActionTime(start));
        Object due = mapValue(changes, "dueAt", "due_at", "deadline");
        if (due != null) details.add("截止→" + displayActionTime(due));
        Object reminder = mapValue(changes, "reminderAt", "reminder_at");
        if (reminder != null) details.add("提醒→" + displayActionTime(reminder));
        Object countdown = mapValue(changes, "countdownMinutes", "countdown_minutes");
        if (countdown != null) details.add(actionCountdownSuffix(countdown, mapValue(changes, "repeatCountdown", "repeat_countdown")));
        Object status = mapValue(changes, "status");
        if (status != null) details.add("状态→" + status);
        if (changes.containsKey("description")) details.add("更新备注");
        if (changes.containsKey("project")) details.add("更新项目");
        if (changes.containsKey("tags")) details.add("更新标签");
        if (details.isEmpty()) details.addAll(stringKeys(changes));
        if (details.size() > 5) details = new ArrayList<>(details.subList(0, 5));
        return details.isEmpty() ? "" : " · " + android.text.TextUtils.join("；", details);
    }

    private List<String> stringKeys(Map<?, ?> values) {
        List<String> keys = new ArrayList<>();
        for (Object key : values.keySet()) keys.add(String.valueOf(key));
        return keys;
    }

    private String describeSubtasks(Object rawSubtasks) {
        if (!(rawSubtasks instanceof List)) return "";
        List<?> subtasks = (List<?>) rawSubtasks;
        List<String> names = new ArrayList<>();
        for (Object subtask : subtasks) {
            if (subtask instanceof String) names.add((String) subtask);
            else if (subtask instanceof Map) {
                Object value = ((Map<?, ?>) subtask).get("title");
                if (value != null) names.add(String.valueOf(value));
            }
            if (names.size() == 4) break;
        }
        if (names.isEmpty()) return " · " + subtasks.size() + " 个子任务";
        String suffix = subtasks.size() > names.size() ? " 等" : "";
        return " · " + android.text.TextUtils.join("、", names) + suffix;
    }

    private long numberTime(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0;
        try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException ignored) { return 0; }
    }

    private String displayActionTime(Object value) {
        if (value == null) return "待定";
        try {
            long timestamp = TaskActionExecutor.parseTimestamp(value);
            return timestamp > 0 ? formatDateTime(timestamp) : "待定";
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String actionCardStateLabel(ChatMessage message, int actionCount) {
        int pending = countActionItems(message, actionCount, ChatMessage.ActionItemState.PENDING);
        int executed = countActionItems(message, actionCount, ChatMessage.ActionItemState.EXECUTED);
        int cancelled = countActionItems(message, actionCount, ChatMessage.ActionItemState.CANCELLED);
        int reverted = countActionItems(message, actionCount, ChatMessage.ActionItemState.REVERTED);
        int failed = countActionItems(message, actionCount, ChatMessage.ActionItemState.FAILED);
        int handedOff = countActionItems(message, actionCount, ChatMessage.ActionItemState.HANDED_OFF);
        if (pending == actionCount) return "等待确认";
        if (pending > 0) return "部分已处理";
        if (executed == actionCount) return "已执行";
        if (handedOff == actionCount) return "已打开系统日历";
        if (cancelled == actionCount) return "已取消";
        if (reverted == actionCount) return "已撤销";
        if (failed == actionCount) return "执行失败";
        if (failed > 0) return "部分失败";
        return executed > 0 || handedOff > 0 ? "部分已执行" : "已处理";
    }

    private int statusColorForAction(ChatMessage message, int actionCount) {
        if (countActionItems(message, actionCount, ChatMessage.ActionItemState.PENDING) > 0) {
            return PRIMARY;
        }
        if (countActionItems(message, actionCount, ChatMessage.ActionItemState.FAILED) > 0) {
            return ERROR;
        }
        if (countActionItems(message, actionCount, ChatMessage.ActionItemState.EXECUTED) > 0
                || countActionItems(message, actionCount, ChatMessage.ActionItemState.HANDED_OFF) > 0) {
            return ACCENT;
        }
        if (countActionItems(message, actionCount, ChatMessage.ActionItemState.REVERTED) > 0) {
            return WARNING;
        }
        return TEXT_MUTED;
    }

    private int statusBackgroundForAction(ChatMessage message, int actionCount) {
        if (countActionItems(message, actionCount, ChatMessage.ActionItemState.PENDING) > 0) {
            return PRIMARY_SOFT;
        }
        if (countActionItems(message, actionCount, ChatMessage.ActionItemState.FAILED) > 0) {
            return ERROR_SOFT;
        }
        if (countActionItems(message, actionCount, ChatMessage.ActionItemState.EXECUTED) > 0
                || countActionItems(message, actionCount, ChatMessage.ActionItemState.HANDED_OFF) > 0) {
            return ACCENT_SOFT;
        }
        if (countActionItems(message, actionCount, ChatMessage.ActionItemState.REVERTED) > 0) {
            return WARNING_SOFT;
        }
        return NEUTRAL;
    }

    private int countActionItems(ChatMessage message, int actionCount,
                                 ChatMessage.ActionItemState expected) {
        int count = 0;
        for (int index = 0; index < actionCount; index++) {
            if (message.getActionItemState(index) == expected) count++;
        }
        return count;
    }

    private String actionItemStateLabel(ChatMessage.ActionItemState state) {
        if (state == ChatMessage.ActionItemState.EXECUTED) return "已执行";
        if (state == ChatMessage.ActionItemState.HANDED_OFF) return "已打开系统日历";
        if (state == ChatMessage.ActionItemState.CANCELLED) return "已取消";
        if (state == ChatMessage.ActionItemState.REVERTED) return "已撤销";
        if (state == ChatMessage.ActionItemState.FAILED) return "执行失败";
        return "待确认";
    }

    private int statusColorForActionItem(ChatMessage.ActionItemState state) {
        if (state == ChatMessage.ActionItemState.EXECUTED
                || state == ChatMessage.ActionItemState.HANDED_OFF) return ACCENT;
        if (state == ChatMessage.ActionItemState.FAILED) return ERROR;
        if (state == ChatMessage.ActionItemState.REVERTED) return WARNING;
        if (state == ChatMessage.ActionItemState.CANCELLED) return TEXT_MUTED;
        return PRIMARY;
    }

    private int statusBackgroundForActionItem(ChatMessage.ActionItemState state) {
        if (state == ChatMessage.ActionItemState.EXECUTED
                || state == ChatMessage.ActionItemState.HANDED_OFF) return ACCENT_SOFT;
        if (state == ChatMessage.ActionItemState.FAILED) return ERROR_SOFT;
        if (state == ChatMessage.ActionItemState.REVERTED) return WARNING_SOFT;
        if (state == ChatMessage.ActionItemState.CANCELLED) return NEUTRAL;
        return PRIMARY_SOFT;
    }

    private List<Integer> pendingActionIndexes(ChatMessage message, int actionCount) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < actionCount; index++) {
            if (message.getActionItemState(index) == ChatMessage.ActionItemState.PENDING
                    || message.getActionItemState(index) == ChatMessage.ActionItemState.FAILED) {
                result.add(index);
            }
        }
        return result;
    }

    private String describeActionItemHistory(ChatMessage message) {
        if (message.getActionItemStates().isEmpty()) return "";
        StringBuilder result = new StringBuilder("；步骤记录=");
        for (int index = 0; index < message.getActionItemStates().size(); index++) {
            if (index > 0) result.append(',');
            result.append(index + 1).append(':')
                    .append(message.getActionItemStates().get(index).storageValue());
        }
        return result.toString();
    }

    private List<Integer> currentPendingActionIndexes(String messageId,
                                                      List<Integer> requestedIndexes,
                                                      int actionCount) {
        ChatMessage latest = repository.getChatMessage(messageId);
        if (latest == null || requestedIndexes == null) return Collections.emptyList();
        List<Integer> result = new ArrayList<>();
        for (Integer value : requestedIndexes) {
            int index = value == null ? -1 : value;
            if (index >= 0 && index < actionCount
                    && (latest.getActionItemState(index) == ChatMessage.ActionItemState.PENDING
                    || latest.getActionItemState(index) == ChatMessage.ActionItemState.FAILED)) {
                result.add(index);
            }
        }
        return result;
    }

    private void cancelPlanActions(String messageId, List<Integer> requestedIndexes,
                                   int actionCount) {
        List<Integer> pending = currentPendingActionIndexes(
                messageId, requestedIndexes, actionCount);
        if (pending.isEmpty()) {
            showMessage("这些步骤已经处理");
            return;
        }
        repository.updateChatActionItemStates(messageId, pending,
                ChatMessage.ActionItemState.CANCELLED, actionCount);
        rejectCancelledPreferenceCandidates(messageId, pending);
        if (behaviorEventRecorder != null) {
            behaviorEventRecorder.recordAction(BehaviorEventEntity.AI_ACTION_CANCELLED,
                    "proposal_cancelled");
        }
    }

    private void rejectCancelledPreferenceCandidates(String messageId, List<Integer> indexes) {
        ChatMessage message = repository.getChatMessage(messageId);
        if (message == null || !message.hasAction()) return;
        try {
            AiPlan plan = AiPlan.parse(message.getActionPayload());
            for (Integer value : indexes) {
                int index = value == null ? -1 : value;
                if (index < 0 || index >= plan.actions.size()) continue;
                AiPlan.Action action = plan.actions.get(index);
                if (action.type != AiPlan.Action.Type.ACTIVATE_PREFERENCE) continue;
                PreferenceRuleEntity rule = findPreference(String.valueOf(action.get("preferenceId")));
                if (rule == null) continue;
                rule.status = PreferenceRuleEntity.STATUS_REJECTED;
                rule.rejectedUntil = System.currentTimeMillis() + 90L * 86_400_000L;
                localIntelligenceStore.putPreference(rule, null);
            }
        } catch (Exception ignored) { }
    }

    private AiPlan selectPlanActions(AiPlan plan, List<Integer> actionIndexes) {
        List<AiPlan.Action> selected = new ArrayList<>();
        for (Integer actionIndex : actionIndexes) {
            selected.add(plan.actions.get(actionIndex));
        }
        return plan.withActions(selected);
    }

    private void confirmPlan(ChatMessage message, AiPlan plan,
                             List<Integer> requestedIndexes) {
        if (confirmingProposals.contains(message.getId()) || executingProposals.contains(message.getId())
                || chatPageViewModel.isProposalBusy(message.getId())) return;
        List<Integer> pending = currentPendingActionIndexes(
                message.getId(), requestedIndexes, plan.actions.size());
        if (pending.isEmpty()) {
            showMessage("这些步骤已经处理");
            return;
        }
        AiPlan selectedPlan = selectPlanActions(plan, pending);
        if (hasDestructiveAction(selectedPlan)) {
            confirmingProposals.add(message.getId());
            AlertDialog confirmation = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                    .setTitle("确认高影响操作")
                    .setMessage("所选步骤包含删除、取消或批量调整。确认后会立即修改任务，并提供撤销机会。")
                    .setNegativeButton("暂不执行", null)
                    .setPositiveButton("继续执行", (dialog, which) -> executePlan(
                            message.getId(), selectedPlan, pending, plan.actions.size()))
                    .create();
            confirmation.setOnDismissListener(dialog -> {
                confirmingProposals.remove(message.getId());
                chatPageController.invalidate(message.getId());
                chatPageController.invalidate("suggestions:" + startOfDay(message.getConversationDay()));
                refreshChatUi();
            });
            confirmation.show();
        } else {
            executePlan(message.getId(), selectedPlan, pending, plan.actions.size());
        }
    }

    private boolean hasDestructiveAction(AiPlan plan) {
        for (AiPlan.Action action : plan.actions) {
            if (action.getType() == AiPlan.Action.Type.DELETE_TASK
                    || action.getType() == AiPlan.Action.Type.CANCEL_TASK
                    || action.getType() == AiPlan.Action.Type.STOP_RECURRING_GROUP
                    || action.getType() == AiPlan.Action.Type.DELETE_PREFERENCE) return true;
        }
        return plan.actions.size() > 3;
    }

    private void executePlan(String messageId, AiPlan plan, List<Integer> actionIndexes,
                             int actionCount) {
        if (executingProposals.contains(messageId) || chatPageViewModel.isProposalBusy(messageId)) return;
        List<Integer> pending = currentPendingActionIndexes(messageId, actionIndexes, actionCount);
        if (!pending.equals(actionIndexes)) { refreshChatUi(); return; }
        executingProposals.add(messageId);
        chatPageViewModel.setProposalBusy(messageId, true);
        try {
            List<AiPlan.Action> taskActions = new ArrayList<>();
            List<AiPlan.Action> hostActions = new ArrayList<>();
            List<Integer> taskIndexes = new ArrayList<>();
            List<Integer> hostIndexes = new ArrayList<>();
            for (int index = 0; index < plan.actions.size(); index++) {
                AiPlan.Action action = plan.actions.get(index);
                if (isHostAction(action.getType())) {
                    hostActions.add(action); hostIndexes.add(actionIndexes.get(index));
                } else {
                    taskActions.add(action); taskIndexes.add(actionIndexes.get(index));
                }
            }
            TaskActionExecutor.ExecutionResult taskResult = null;
            if (!taskActions.isEmpty()) {
                taskResult = TaskActionExecutor.executeConfirmed(repository,
                        plan.withActions(taskActions), messageId, taskIndexes, actionCount);
                lastExecutionResult = taskResult;
            }
            TaskActionExecutor.ExecutionResult finalTaskResult = taskResult;
            executeHostActions(hostActions, 0, messageId, hostIndexes, actionCount, () -> {
                executingProposals.remove(messageId);
                chatPageViewModel.setProposalBusy(messageId, false);
                if (behaviorEventRecorder != null) {
                    behaviorEventRecorder.recordAction(BehaviorEventEntity.AI_ACTION_CONFIRMED,
                            "proposal_executed");
                }
                boolean onlyTaskActions = hostActions.isEmpty();
                if (onlyTaskActions && finalTaskResult != null) {
                    boolean focusPrepared = containsAction(plan, AiPlan.Action.Type.START_FOCUS);
                    boolean greetingOnly = plan.actions.size() == 1
                            && plan.actions.get(0).getType() == AiPlan.Action.Type.SET_GREETING;
                    showExecutionResult(finalTaskResult, focusPrepared, greetingOnly,
                            messageId, actionIndexes, actionCount);
                } else {
                    chatPageViewModel.recordProposalResult(messageId,
                            containsAction(plan, AiPlan.Action.Type.COMPOSE_CALENDAR_EVENT)
                                    ? "已打开系统日历，请在系统页面确认保存"
                                    : finalTaskResult == null ? "偏好已更新" : "操作已完成", finalTaskResult, false);
                    chatPageController.invalidate(messageId);
                    chatPageController.invalidate("suggestions:" + startOfDay(selectedChatDate.getTimeInMillis()));
                    refreshChatUi();
                }
            }, error -> {
                executingProposals.remove(messageId);
                chatPageViewModel.setProposalBusy(messageId, false);
                try {
                    repository.updateChatActionItemStates(messageId,
                            currentPendingActionIndexes(messageId, hostIndexes, actionCount),
                            ChatMessage.ActionItemState.FAILED, actionCount);
                } catch (RuntimeException ignored) { }
                if (finalTaskResult != null) {
                    showPartialExternalFailure(finalTaskResult, messageId, taskIndexes,
                            actionCount, error);
                } else {
                    chatPageViewModel.recordProposalResult(messageId,
                            "部分操作未完成：" + safeError(error)
                                    + "。已提交的步骤请先核对，未自动重复执行。", null, false);
                    chatPageController.invalidate(messageId);
                    refreshChatUi();
                }
            });
        } catch (TaskActionExecutor.ExecutionException | RuntimeException exception) {
            executingProposals.remove(messageId);
            chatPageViewModel.setProposalBusy(messageId, false);
            try {
                repository.updateChatActionItemStates(messageId,
                        currentPendingActionIndexes(messageId, actionIndexes, actionCount),
                        ChatMessage.ActionItemState.FAILED, actionCount);
            } catch (RuntimeException ignored) { }
            chatPageViewModel.recordProposalResult(messageId,
                    "操作执行失败：" + safeError(exception), null, false);
            chatPageController.invalidate(messageId);
            refreshChatUi();
        }
    }

    private void showPartialExternalFailure(TaskActionExecutor.ExecutionResult localResult,
                                            String messageId, List<Integer> taskIndexes,
                                            int actionCount, Throwable error) {
        showExecutionResult(localResult, false, false, messageId, taskIndexes, actionCount);
        chatPageViewModel.recordProposalResult(messageId,
                "部分完成。本地修改已保存，外部操作未完成：" + safeError(error), localResult, false);
        chatPageController.invalidate(messageId);
        chatPageController.invalidate("suggestions:" + startOfDay(selectedChatDate.getTimeInMillis()));
        refreshChatUi();
    }

    private boolean isHostAction(AiPlan.Action.Type type) {
        return type == AiPlan.Action.Type.CREATE_PREFERENCE
                || type == AiPlan.Action.Type.UPDATE_PREFERENCE
                || type == AiPlan.Action.Type.DELETE_PREFERENCE
                || type == AiPlan.Action.Type.ACTIVATE_PREFERENCE
                || type == AiPlan.Action.Type.COMPOSE_CALENDAR_EVENT;
    }

    private interface HostActionFailure { void onError(Throwable error); }

    private void executeHostActions(List<AiPlan.Action> actions, int index,
                                    String messageId, List<Integer> originalIndexes, int actionCount,
                                    Runnable success, HostActionFailure failure) {
        if (index >= actions.size()) { success.run(); return; }
        AiPlan.Action action = actions.get(index);
        try {
            // Claim before crossing stores/apps so an uncertain side effect is never replayed.
            repository.updateChatActionItemStates(messageId,
                    Collections.singletonList(originalIndexes.get(index)),
                    ChatMessage.ActionItemState.HANDED_OFF, actionCount);
            if (action.type == AiPlan.Action.Type.COMPOSE_CALENDAR_EVENT) {
                handoffCalendarEvent(action);
                repository.updateChatActionItemStates(messageId,
                        Collections.singletonList(originalIndexes.get(index)),
                        ChatMessage.ActionItemState.HANDED_OFF, actionCount);
                executeHostActions(actions, index + 1, messageId, originalIndexes, actionCount, success, failure);
                return;
            }
            executePreferenceAction(action, () -> {
                try {
                    repository.updateChatActionItemStates(messageId,
                            Collections.singletonList(originalIndexes.get(index)),
                            ChatMessage.ActionItemState.EXECUTED, actionCount);
                    executeHostActions(actions, index + 1, messageId, originalIndexes, actionCount, success, failure);
                } catch (RuntimeException error) { failure.onError(error); }
            });
        } catch (Throwable error) { failure.onError(error); }
    }

    private void executePreferenceAction(AiPlan.Action action, Runnable completion) {
        long now = System.currentTimeMillis();
        switch (action.type) {
            case CREATE_PREFERENCE: {
                PreferenceRuleEntity rule = new PreferenceRuleEntity();
                rule.type = String.valueOf(actionValue(action, "preferenceType"));
                rule.scopeType = String.valueOf(actionValue(action, "scopeType"));
                Object scope = actionValue(action, "scopeValue");
                rule.scopeValue = scope == null ? "" : String.valueOf(scope).trim();
                rule.startMinute = integerActionValue(actionValue(action, "startMinute"), -1);
                rule.endMinute = integerActionValue(actionValue(action, "endMinute"), -1);
                rule.daysOfWeek = csvActionValue(actionValue(action, "daysOfWeek"));
                Object reason = actionValue(action, "reason");
                rule.reason = reason == null ? "用户通过聊天创建" : String.valueOf(reason);
                rule.origin = PreferenceRuleEntity.ORIGIN_EXPLICIT;
                rule.status = PreferenceRuleEntity.STATUS_ACTIVE;
                rule.createdAt = now; rule.updatedAt = now; rule.confidence = 1d;
                localIntelligenceStore.putPreference(rule, completion);
                break;
            }
            case UPDATE_PREFERENCE: {
                PreferenceRuleEntity rule = requirePreference(String.valueOf(action.get("preferenceId")));
                Object raw = action.get("changes");
                if (!(raw instanceof Map)) throw new IllegalArgumentException("偏好 changes 无效");
                applyPreferenceChanges(rule, (Map<?, ?>) raw);
                localIntelligenceStore.putPreference(rule, completion);
                break;
            }
            case DELETE_PREFERENCE:
                localIntelligenceStore.deletePreference(
                        String.valueOf(action.get("preferenceId")), completion);
                break;
            case ACTIVATE_PREFERENCE: {
                PreferenceRuleEntity rule = requirePreference(String.valueOf(action.get("preferenceId")));
                if (!PreferenceRuleEntity.STATUS_CANDIDATE.equals(rule.status)) {
                    throw new IllegalStateException("这条偏好建议已经处理");
                }
                rule.status = PreferenceRuleEntity.STATUS_ACTIVE;
                rule.expiresAt = 0; rule.rejectedUntil = 0;
                localIntelligenceStore.putPreference(rule, completion);
                break;
            }
            default: throw new IllegalArgumentException("不支持的偏好操作");
        }
    }

    private void applyPreferenceChanges(PreferenceRuleEntity rule, Map<?, ?> changes) {
        if (changes.containsKey("preferenceType")) rule.type = String.valueOf(changes.get("preferenceType"));
        if (changes.containsKey("scopeType")) rule.scopeType = String.valueOf(changes.get("scopeType"));
        if (changes.containsKey("scopeValue")) rule.scopeValue = String.valueOf(changes.get("scopeValue"));
        if (changes.containsKey("startMinute")) rule.startMinute = integerActionValue(changes.get("startMinute"), -1);
        if (changes.containsKey("endMinute")) rule.endMinute = integerActionValue(changes.get("endMinute"), -1);
        if (changes.containsKey("daysOfWeek")) rule.daysOfWeek = csvActionValue(changes.get("daysOfWeek"));
        if (changes.containsKey("status")) rule.status = String.valueOf(changes.get("status"));
    }

    private String csvActionValue(Object value) {
        if (!(value instanceof List)) return "";
        List<String> pieces = new ArrayList<>();
        for (Object item : (List<?>) value) pieces.add(String.valueOf(integerActionValue(item, -1)));
        return TextUtils.join(",", pieces);
    }

    private PreferenceRuleEntity findPreference(String id) {
        for (PreferenceRuleEntity rule : localIntelligenceStore.preferences()) {
            if (rule.id.equals(id)) return rule;
        }
        return null;
    }

    private PreferenceRuleEntity requirePreference(String id) {
        PreferenceRuleEntity rule = findPreference(id);
        if (rule == null) throw new IllegalArgumentException("偏好不存在或已删除");
        return rule;
    }

    private void handoffCalendarEvent(AiPlan.Action action) throws Exception {
        if (!policyEngine.mayComposeCalendarEvent()) {
            throw new SecurityException("系统日历实验未开启");
        }
        String title = String.valueOf(actionValue(action, "title"));
        long startAt = TaskActionExecutor.parseTimestamp(actionValue(action, "startAt", "start_at"));
        long endAt = TaskActionExecutor.parseTimestamp(actionValue(action, "endAt", "end_at"));
        Intent intent = CalendarEventComposer.build(title, startAt, endAt,
                actionValue(action, "description") == null ? "" : String.valueOf(actionValue(action, "description")),
                actionValue(action, "recurrenceRule") == null ? "" : String.valueOf(actionValue(action, "recurrenceRule")));
        ToolExecutionEntity audit = new ToolExecutionEntity();
        audit.toolId = "calendar.compose_event";
        audit.summary = "打开系统日历填写事件";
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            audit.status = ToolExecutionEntity.FAILED;
            audit.error = "没有可处理日历事件的应用";
            audit.finishedAt = System.currentTimeMillis();
            localIntelligenceStore.putToolExecution(audit);
            throw new IllegalStateException("设备上没有可打开的日历应用", error);
        }
        audit.status = ToolExecutionEntity.HANDED_OFF;
        audit.finishedAt = System.currentTimeMillis();
        localIntelligenceStore.putToolExecution(audit);
        showMessage("已打开系统日历，请在系统页面确认保存");
    }

    private boolean containsAction(AiPlan plan, AiPlan.Action.Type type) {
        for (AiPlan.Action action : plan.actions) if (action.getType() == type) return true;
        return false;
    }

    private void showExecutionResult(TaskActionExecutor.ExecutionResult result,
                                     boolean focusPrepared, boolean greetingOnly,
                                     String messageId, List<Integer> actionIndexes,
                                     int actionCount) {
        chatPageViewModel.recordProposalResult(messageId, result.summary, result, focusPrepared);
        chatPageController.invalidate("suggestions:" + startOfDay(selectedChatDate.getTimeInMillis()));
        chatPageController.invalidate(messageId); refreshChatUi();
    }

    private void scrollChatToBottom() {
        if (chatScroll != null && chatMessages != null) {
            chatScroll.post(() -> {
                if (MotionSpec.duration(this, 180) == 0) chatScroll.scrollTo(0, chatMessages.getHeight());
                else chatScroll.smoothScrollTo(0, chatMessages.getHeight());
                if (latestChatButton != null) latestChatButton.setVisibility(View.GONE);
            });
        }
    }

    private String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 11) return "早上好";
        if (hour < 14) return "中午好";
        if (hour < 19) return "下午好";
        return "晚上好";
    }

    protected void showManualTaskDialog() {
        LinearLayout form = column();
        form.setPadding(dp(22), dp(8), dp(22), dp(10));
        EditText title = edit("任务标题", "", false);
        EditText description = edit("备注", "", false);
        description.setSingleLine(false);
        description.setMinLines(2);
        SwitchCompat requiresTime = new SwitchCompat(this);
        requiresTime.setText("需要安排");
        requiresTime.setTextColor(TEXT);
        requiresTime.setTextSize(13);
        requiresTime.setChecked(true);
        requiresTime.setGravity(Gravity.CENTER_VERTICAL);
        final long[] plannedDateAt = {currentPage == PAGE_SCHEDULE
                ? startOfDay(selectedDate.getTimeInMillis()) : 0};
        final long[] startAt = {0};
        final long[] endAt = {0};
        com.hawson.timeassistant.ui.task.TaskDateTimeFields dateTimes =
                new com.hawson.timeassistant.ui.task.TaskDateTimeFields(
                        this, plannedDateAt, startAt, endAt, true);
        title.setMinimumHeight(dp(48));
        requiresTime.setMinimumHeight(dp(48));
        form.addView(title, lp(-1, -2));
        form.addView(requiresTime, marginTop(lp(-1, -2), dp(8)));
        form.addView(dateTimes, lp(-1, -2));
        final RecurrenceRuleDraft recurrenceDraft = new RecurrenceRuleDraft();
        SwitchCompat recurring = new SwitchCompat(this);
        recurring.setText("周期日程");
        recurring.setTextColor(TEXT);
        recurring.setTextSize(13);
        recurring.setGravity(Gravity.CENTER_VERTICAL);
        TextView recurrenceRule = filterChip("每周", false, null);
        recurring.setMinimumHeight(dp(48));
        recurrenceRule.setMinHeight(dp(48));
        Runnable refreshRecurrence = () -> {
            recurrenceRule.setText(formatRecurrenceRule(recurrenceDraft));
            recurrenceRule.setVisibility(recurring.isChecked() ? View.VISIBLE : View.GONE);
        };
        recurring.setOnCheckedChangeListener((button, checked) -> refreshRecurrence.run());
        recurrenceRule.setOnClickListener(v -> showRecurrenceRulePicker(
                recurrenceDraft, selected -> {
                    recurrenceDraft.copyFrom(selected);
                    refreshRecurrence.run();
                }));
        com.hawson.timeassistant.design.DisclosureSection advanced =
                new com.hawson.timeassistant.design.DisclosureSection(this, "重复与备注", false);
        advanced.content.addView(description, marginTop(lp(-1, -2), dp(8)));
        advanced.content.addView(recurring, marginTop(lp(-1, -2), dp(8)));
        advanced.content.addView(recurrenceRule, marginTop(lp(-1, -2), dp(2)));
        form.addView(advanced, lp(-1, -2));
        refreshRecurrence.run();
        ScrollView scheduleFormScroll = new ScrollView(this);
        scheduleFormScroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("添加任务")
                .setView(scheduleFormScroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = title.getText().toString().trim();
            if (value.isEmpty()) {
                title.setError("请输入任务标题");
                title.requestFocus();
                return;
            }
            if (!dateTimes.validate(recurring.isChecked())) return;
            if (recurring.isChecked()) {
                RecurringTaskGroup group = new RecurringTaskGroup(
                        value, startAt[0], endAt[0] - startAt[0],
                        recurrenceDraft.unit, recurrenceDraft.interval)
                        .setAdvancedRule(recurrenceDraft.workdaysOnly,
                                recurrenceDraft.weekdays,
                                recurrenceDraft.monthlyOrdinal,
                                recurrenceDraft.monthlyWeekday,
                                recurrenceDraft.endsAt,
                                recurrenceDraft.maxOccurrences)
                        .setDescription(description.getText().toString().trim());
                repository.upsertRecurringGroup(group);
                dialog.dismiss();
                showMessage("周期日程已创建");
                return;
            }
            TaskItem task = new TaskItem(value)
                    .setDescription(description.getText().toString().trim())
                    .setSource(TaskItem.SOURCE_MANUAL)
                    .setRequiresTimeArrangement(requiresTime.isChecked())
                    .setScheduleFields(plannedDateAt[0], startAt[0], endAt[0], false);
            if (plannedDateAt[0] > 0) task.setDueAt(endOfDay(plannedDateAt[0]));
            repository.upsertTask(task);
            dialog.dismiss();
            showMessage(task.isScheduled() ? "任务已安排" : "任务已保存");
            if (currentPage == PAGE_SCHEDULE) renderCurrentPage();
        }));
        UiKit.polish(form);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        com.hawson.timeassistant.design.FormDraftGuard.attach(dialog, form);
    }

    private interface RecurrenceSelectionCallback {
        void onSelected(RecurrenceRuleDraft selection);
    }

    private void showRecurrenceRulePicker(RecurrenceRuleDraft initial,
                                          RecurrenceSelectionCallback callback) {
        RecurrenceRuleDraft draft = initial == null
                ? new RecurrenceRuleDraft() : initial.copy();
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), dp(16));
        form.addView(label("重复方式", 12, TEXT_MUTED, Typeface.BOLD));
        NumberPicker pattern = new NumberPicker(this);
        pattern.setMinValue(0); pattern.setMaxValue(5);
        pattern.setDisplayedValues(new String[]{"每 N 天", "每 N 周", "每 N 月",
                "每个工作日", "每周多选", "每月第 N 个星期几"});
        pattern.setWrapSelectorWheel(false);
        pattern.setValue(draft.patternIndex());
        form.addView(pattern, marginTop(lp(-1, dp(150)), dp(4)));

        TextView intervalLabel = label("间隔", 12, TEXT_MUTED, Typeface.BOLD);
        NumberPicker amount = new NumberPicker(this);
        amount.setMinValue(1); amount.setMaxValue(365);
        amount.setValue(Math.max(1, Math.min(365, draft.interval)));
        amount.setWrapSelectorWheel(false);
        form.addView(intervalLabel, marginTop(lp(-2, -2), dp(12)));
        form.addView(amount, lp(-1, dp(110)));

        TextView weekdayLabel = label("每周执行日", 12, TEXT_MUTED, Typeface.BOLD);
        LinearLayout weekdayRow = row();
        int[] calendarDays = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        String[] weekdayNames = {"一", "二", "三", "四", "五", "六", "日"};
        List<TextView> weekdayButtons = new ArrayList<>();
        for (int index = 0; index < calendarDays.length; index++) {
            int day = calendarDays[index];
            TextView chip = filterChip(weekdayNames[index], draft.weekdays.contains(day), null);
            chip.setOnClickListener(v -> {
                if (draft.weekdays.contains(day)) draft.weekdays.remove(day);
                else draft.weekdays.add(day);
                styleOptionalTimeButton(chip, draft.weekdays.contains(day));
            });
            weekdayButtons.add(chip);
            weekdayRow.addView(chip, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        form.addView(weekdayLabel, marginTop(lp(-2, -2), dp(12)));
        form.addView(weekdayRow, marginTop(lp(-1, dp(44)), dp(6)));

        LinearLayout monthlyRow = row();
        NumberPicker ordinal = new NumberPicker(this);
        ordinal.setMinValue(1); ordinal.setMaxValue(5);
        ordinal.setDisplayedValues(new String[]{"第 1 个", "第 2 个", "第 3 个", "第 4 个", "第 5 个"});
        ordinal.setValue(draft.monthlyOrdinal > 0 ? draft.monthlyOrdinal : 1);
        NumberPicker monthlyWeekday = new NumberPicker(this);
        monthlyWeekday.setMinValue(1); monthlyWeekday.setMaxValue(7);
        monthlyWeekday.setDisplayedValues(new String[]{"星期日", "星期一", "星期二", "星期三",
                "星期四", "星期五", "星期六"});
        monthlyWeekday.setValue(draft.monthlyWeekday > 0 ? draft.monthlyWeekday : Calendar.MONDAY);
        monthlyRow.addView(ordinal, new LinearLayout.LayoutParams(0, dp(120), 1));
        monthlyRow.addView(monthlyWeekday, new LinearLayout.LayoutParams(0, dp(120), 1));
        form.addView(monthlyRow, marginTop(lp(-1, dp(120)), dp(12)));

        form.addView(label("结束条件", 12, TEXT_MUTED, Typeface.BOLD),
                marginTop(lp(-2, -2), dp(16)));
        NumberPicker endType = new NumberPicker(this);
        endType.setMinValue(0); endType.setMaxValue(2);
        endType.setDisplayedValues(new String[]{"永不结束", "结束日期", "重复次数"});
        endType.setValue(draft.endsAt > 0 ? 1 : draft.maxOccurrences > 0 ? 2 : 0);
        form.addView(endType, lp(-1, dp(110)));
        final long[] endDate = {draft.endsAt};
        TextView endDateButton = filterChip(endDate[0] > 0
                ? "结束于 " + formatDateOnly(endDate[0]) : "选择结束日期", endDate[0] > 0, null);
        endDateButton.setOnClickListener(v -> pickDateOnly(endDate[0], value -> {
            endDate[0] = endOfDay(value);
            endDateButton.setText("结束于 " + formatDateOnly(value));
            styleOptionalTimeButton(endDateButton, true);
        }));
        NumberPicker repeatCount = new NumberPicker(this);
        repeatCount.setMinValue(1); repeatCount.setMaxValue(999);
        repeatCount.setValue(draft.maxOccurrences > 0 ? draft.maxOccurrences : 10);
        form.addView(endDateButton, marginTop(lp(-1, dp(44)), dp(8)));
        form.addView(repeatCount, marginTop(lp(-1, dp(110)), dp(8)));
        Runnable refresh = () -> {
            int value = pattern.getValue();
            intervalLabel.setVisibility(value <= 2 || value == 4 || value == 5
                    ? View.VISIBLE : View.GONE);
            amount.setVisibility(intervalLabel.getVisibility());
            weekdayLabel.setVisibility(value == 4 ? View.VISIBLE : View.GONE);
            weekdayRow.setVisibility(value == 4 ? View.VISIBLE : View.GONE);
            monthlyRow.setVisibility(value == 5 ? View.VISIBLE : View.GONE);
            endDateButton.setVisibility(endType.getValue() == 1 ? View.VISIBLE : View.GONE);
            repeatCount.setVisibility(endType.getValue() == 2 ? View.VISIBLE : View.GONE);
        };
        pattern.setOnValueChangedListener((picker, oldValue, newValue) -> refresh.run());
        endType.setOnValueChangedListener((picker, oldValue, newValue) -> refresh.run());
        refresh.run();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("重复规律")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int value = pattern.getValue();
                    draft.unit = value == 0 ? RecurringTaskGroup.Unit.DAYS
                            : value == 2 || value == 5 ? RecurringTaskGroup.Unit.MONTHS
                            : RecurringTaskGroup.Unit.WEEKS;
                    draft.interval = value == 3 ? 1 : amount.getValue();
                    draft.workdaysOnly = value == 3;
                    if (value != 4) draft.weekdays.clear();
                    if (value == 4 && draft.weekdays.isEmpty()) {
                        showMessage("请至少选择一个星期");
                        return;
                    }
                    draft.monthlyOrdinal = value == 5 ? ordinal.getValue() : 0;
                    draft.monthlyWeekday = value == 5 ? monthlyWeekday.getValue() : 0;
                    draft.endsAt = endType.getValue() == 1 ? endDate[0] : 0;
                    if (endType.getValue() == 1 && draft.endsAt <= 0) {
                        showMessage("请选择结束日期");
                        return;
                    }
                    draft.maxOccurrences = endType.getValue() == 2 ? repeatCount.getValue() : 0;
                    callback.onSelected(draft.copy());
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private String formatRecurrenceRule(RecurringTaskGroup.Unit unit, int interval) {
        String value = unit == RecurringTaskGroup.Unit.DAYS ? "天"
                : unit == RecurringTaskGroup.Unit.MONTHS ? "月" : "周";
        return interval == 1 ? "每" + value : "每 " + interval + " " + value;
    }

    private String formatRecurrenceRule(RecurrenceRuleDraft draft) {
        String base;
        if (draft.workdaysOnly) base = "每个工作日";
        else if (draft.monthlyOrdinal > 0) base = "每月第 " + draft.monthlyOrdinal + " 个"
                + weekdayName(draft.monthlyWeekday);
        else if (!draft.weekdays.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Integer day : draft.weekdays) names.add(weekdayName(day));
            base = (draft.interval == 1 ? "每周 " : "每 " + draft.interval + " 周 ")
                    + TextUtils.join("、", names);
        } else base = formatRecurrenceRule(draft.unit, draft.interval);
        if (draft.endsAt > 0) return base + " · 至 " + formatDateOnly(draft.endsAt);
        if (draft.maxOccurrences > 0) return base + " · 共 " + draft.maxOccurrences + " 次";
        return base + " · 永不结束";
    }

    private String formatRecurrenceRule(RecurringTaskGroup group) {
        return formatRecurrenceRule(RecurrenceRuleDraft.from(group));
    }

    private String weekdayName(int day) {
        switch (day) {
            case Calendar.MONDAY: return "周一";
            case Calendar.TUESDAY: return "周二";
            case Calendar.WEDNESDAY: return "周三";
            case Calendar.THURSDAY: return "周四";
            case Calendar.FRIDAY: return "周五";
            case Calendar.SATURDAY: return "周六";
            default: return "周日";
        }
    }

    protected void showTodoTaskDialog() {
        showTodoTaskDialog(null);
    }

    private void showTodoTaskDialog(TaskItem existing) {
        LinearLayout form = column();
        form.setPadding(dp(22), dp(8), dp(22), dp(14));
        LinearLayout basicSection = column();
        LinearLayout reminderSection = column();
        LinearLayout actionSection = column();
        form.addView(basicSection, lp(-1, -2));
        com.hawson.timeassistant.design.DisclosureSection reminder =
                new com.hawson.timeassistant.design.DisclosureSection(this, "提醒与备注",
                        existing != null && (existing.hasCountdown() || !valueOrEmpty(existing.getDescription()).isEmpty()));
        reminder.content.addView(reminderSection, lp(-1, -2));
        form.addView(reminder, lp(-1, -2));
        com.hawson.timeassistant.design.DisclosureSection taskActions =
                new com.hawson.timeassistant.design.DisclosureSection(this, "更多操作", false);
        taskActions.content.addView(actionSection, lp(-1, -2));
        if (existing != null) form.addView(taskActions, lp(-1, -2));
        EditText title = edit("待办标题", existing == null ? "" : existing.getTitle(), false);
        EditText description = edit("备注",
                existing == null ? "" : existing.getDescription(), false);
        description.setSingleLine(false);
        description.setMinLines(2);

        long now = System.currentTimeMillis();
        final long[] todoDateAt = {existing != null ? existing.getPlannedDateAt()
                : currentPage == PAGE_SCHEDULE ? startOfDay(selectedDate.getTimeInMillis()) : 0};
        com.hawson.timeassistant.ui.task.TaskDateTimeFields dateFields =
                new com.hawson.timeassistant.ui.task.TaskDateTimeFields(
                        this, todoDateAt, new long[]{0}, new long[]{0}, false);
        final long[] targetAt = {existing == null ? 0 : existing.getCountdownTargetAt()};
        final long[] durationMillis = {0};
        if (existing != null && existing.getCountdownIntervalMillis() > 0) {
            durationMillis[0] = existing.getCountdownIntervalMillis();
        } else if (targetAt[0] > now) {
            durationMillis[0] = Math.max(60_000L,
                    ((targetAt[0] - now + 59_999L) / 60_000L) * 60_000L);
        }
        final boolean[] countdownChanged = {false};
        final boolean initiallyRepeating = existing != null && existing.isRepeatingCountdown();

        TextView timerButton = filterChip("倒计时", false, null);
        android.widget.ImageButton clearTimer = new android.widget.ImageButton(this);
        clearTimer.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        clearTimer.setColorFilter(TEXT_MUTED);
        clearTimer.setBackgroundColor(Color.TRANSPARENT);
        clearTimer.setPadding(dp(12), dp(12), dp(12), dp(12));
        UiKit.tooltip(clearTimer, "清除倒计时");
        SwitchCompat repeat = new SwitchCompat(this);
        repeat.setText("循环提醒");
        repeat.setTextColor(TEXT);
        repeat.setTextSize(13);
        repeat.setGravity(Gravity.CENTER_VERTICAL);
        repeat.setChecked(initiallyRepeating);

        Runnable refreshTimer = () -> {
            boolean hasTimer = countdownChanged[0]
                    ? durationMillis[0] > 0 : targetAt[0] > 0;
            if (!hasTimer) {
                timerButton.setText("倒计时");
            } else if (countdownChanged[0]) {
                timerButton.setText("倒计时 · " + formatCountdownDuration(durationMillis[0]));
            } else if (targetAt[0] <= System.currentTimeMillis()) {
                timerButton.setText("提醒已到时");
            } else {
                timerButton.setText("定时 · " + formatDateTime(targetAt[0]));
            }
            styleOptionalTimeButton(timerButton, hasTimer);
            timerButton.setError(null);
            clearTimer.setVisibility(hasTimer ? View.VISIBLE : View.INVISIBLE);
            repeat.setEnabled(durationMillis[0] > 0);
            repeat.setAlpha(repeat.isEnabled() ? 1f : 0.48f);
            if (!repeat.isEnabled()) repeat.setChecked(false);
        };
        timerButton.setOnClickListener(v -> showCountdownDurationPicker(durationMillis[0], value -> {
            durationMillis[0] = value;
            countdownChanged[0] = true;
            refreshTimer.run();
        }));
        clearTimer.setOnClickListener(v -> {
            targetAt[0] = 0;
            durationMillis[0] = 0;
            countdownChanged[0] = true;
            repeat.setChecked(false);
            refreshTimer.run();
        });
        applyPressFeedback(timerButton);
        applyPressFeedback(clearTimer);

        title.setMinimumHeight(dp(48));
        timerButton.setMinHeight(dp(48));
        repeat.setMinimumHeight(dp(48));
        basicSection.addView(title, marginTop(lp(-1, -2), existing == null ? 0 : dp(12)));
        reminderSection.addView(description, marginTop(lp(-1, -2), dp(10)));
        basicSection.addView(dateFields, marginTop(lp(-1, -2), dp(8)));
        reminderSection.addView(label("定时提醒", 12, TEXT_MUTED, Typeface.BOLD),
                verticalMargins(lp(-2, -2), dp(16), dp(8)));
        LinearLayout timerRow = row();
        timerRow.setGravity(Gravity.CENTER_VERTICAL);
        timerRow.addView(timerButton, new LinearLayout.LayoutParams(0, -2, 1));
        timerRow.addView(clearTimer, lp(dp(48), dp(48)));
        reminderSection.addView(timerRow, lp(-1, -2));
        reminderSection.addView(repeat, marginTop(lp(-1, -2), dp(6)));
        TextView timerError = label("", 12, ERROR, Typeface.NORMAL);
        timerError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        timerError.setVisibility(View.GONE);
        reminderSection.addView(timerError, lp(-1, -2));
        timerButton.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                timerError.setText(""); timerError.setVisibility(View.GONE);
            }
            public void afterTextChanged(android.text.Editable text) { }
        });
        refreshTimer.run();

        if (existing != null) {
            LinearLayout actions = row();
            Button primary = button(taskPrimaryActionLabel(existing), Color.WHITE, ACCENT);
            Button delete = button("删除", ERROR, ERROR_SOFT);
            primary.setMinimumHeight(dp(48));
            delete.setMinimumHeight(dp(48));
            actions.addView(primary, new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(delete,
                    marginLeft(new LinearLayout.LayoutParams(0, -2, 1), dp(8)));
            actionSection.addView(label("任务操作", 13, TEXT, Typeface.BOLD),
                    marginTop(lp(-2, -2), dp(16)));
            actionSection.addView(actions, marginTop(lp(-1, -2), dp(10)));
            primary.setTag("todo_primary");
            delete.setTag("todo_delete");
        }


        ScrollView scroll = new ScrollView(this);
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle(existing == null ? "新建待办" : "待办详情")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton(existing == null ? "创建" : "保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = title.getText().toString().trim();
                if (name.isEmpty()) {
                    title.setError("请输入待办标题");
                    title.requestFocus();
                    return;
                }
                if (repeat.isChecked() && durationMillis[0] <= 0) {
                    reminder.setExpanded(true);
                    timerError.setVisibility(View.VISIBLE);
                    timerError.setText("请先设置倒计时");
                    return;
                }
                try {
                    TaskItem task = existing == null
                            ? new TaskItem(name).setSource(TaskItem.SOURCE_MANUAL)
                            : repository.getTask(existing.getId());
                    if (task == null) throw new IllegalStateException("待办已不存在");
                    task.setTitle(name)
                            .setDescription(description.getText().toString().trim())
                            .setRequiresTimeArrangement(false)
                            .setDueAt(0)
                            .clearSchedule();
                    task.setPlannedDateAt(todoDateAt[0]);
                    if (countdownChanged[0]) {
                        if (durationMillis[0] <= 0) {
                            task.clearCountdown();
                        } else {
                            long next = Math.addExact(System.currentTimeMillis(), durationMillis[0]);
                            task.setCountdown(next,
                                    repeat.isChecked() ? durationMillis[0] : 0);
                        }
                    } else if (existing != null && repeat.isChecked() != initiallyRepeating) {
                        long interval = repeat.isChecked() ? durationMillis[0] : 0;
                        task.setCountdown(task.getCountdownTargetAt(), interval);
                    }
                    repository.upsertTask(task);
                    if (task.hasCountdown()) requestNotificationPermissionIfNeeded();
                    dialog.dismiss();
                    showMessage(existing == null ? "待办已创建" : "待办已更新");
                } catch (ArithmeticException exception) {
                    showMessage("倒计时时间超出范围");
                } catch (RuntimeException exception) {
                    showMessage("保存失败：" + safeError(exception));
                }
            });
            if (existing != null) {
                View primary = form.findViewWithTag("todo_primary");
                View delete = form.findViewWithTag("todo_delete");
                if (primary != null) primary.setOnClickListener(v -> {
                    dialog.dismiss();
                    performTaskPrimaryAction(existing.getId());
                });
                if (delete != null) delete.setOnClickListener(v -> {
                    dialog.dismiss();
                    confirmDeleteTask(existing.getId());
                });
            }
        });
        UiKit.polish(form);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        com.hawson.timeassistant.design.FormDraftGuard.attach(dialog, form);
    }

    private void showCountdownDurationPicker(long initialMillis, TimeSelectionCallback callback) {
        long minute = 60_000L;
        long hour = 60L * minute;
        long day = 24L * hour;
        int unitIndex;
        int initialValue;
        if (initialMillis > 0 && initialMillis % day == 0) {
            unitIndex = 2;
            initialValue = (int) Math.min(999L, initialMillis / day);
        } else if (initialMillis > 0 && initialMillis % hour == 0) {
            unitIndex = 1;
            initialValue = (int) Math.min(999L, initialMillis / hour);
        } else {
            unitIndex = 0;
            initialValue = initialMillis <= 0 ? 30
                    : (int) Math.min(999L, Math.max(1L, (initialMillis + minute - 1L) / minute));
        }
        NumberPicker amount = new NumberPicker(this);
        amount.setMinValue(1);
        amount.setMaxValue(999);
        amount.setValue(initialValue);
        amount.setWrapSelectorWheel(false);
        NumberPicker unit = new NumberPicker(this);
        unit.setMinValue(0);
        unit.setMaxValue(2);
        unit.setDisplayedValues(new String[]{"分钟", "小时", "天"});
        unit.setValue(unitIndex);
        unit.setWrapSelectorWheel(false);
        LinearLayout picker = row();
        picker.setPadding(dp(22), dp(6), dp(22), dp(6));
        picker.setGravity(Gravity.CENTER);
        picker.addView(amount, new LinearLayout.LayoutParams(0, dp(150), 1));
        picker.addView(unit, new LinearLayout.LayoutParams(0, dp(150), 1));
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("设置倒计时")
                .setView(picker)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    long multiplier = unit.getValue() == 2 ? day
                            : unit.getValue() == 1 ? hour : minute;
                    callback.onSelected(amount.getValue() * multiplier);
                })
                .show();
    }

    private String formatCountdownDuration(long millis) {
        long minutes = Math.max(1L, (millis + 59_999L) / 60_000L);
        if (minutes < 60L) return minutes + " 分钟";
        if (minutes % (24L * 60L) == 0) return minutes / (24L * 60L) + " 天";
        if (minutes % 60L == 0) return minutes / 60L + " 小时";
        return minutes / 60L + " 小时 " + minutes % 60L + " 分钟";
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            focusNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            showNotificationSettingsDialog();
            return;
        }
        promptForFocusLockscreenNotificationIfNeeded();
    }

    private void showNotificationSettingsDialog() {
        if (isFinishing() || isDestroyed()) return;
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("需要开启通知")
                .setMessage("专注计时需要系统通知权限，才能在通知栏和锁屏显示进度与结束提醒。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("打开系统设置", (dialog, which) -> {
                    Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(settings);
                })
                .show();
    }

    /** Android and vendor ROMs keep lock-screen presentation under a user-controlled setting. */
    private void promptForFocusLockscreenNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isFinishing() || isDestroyed()
                || focusLockscreenSettingsDialogVisible) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mainHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()
                    || focusLockscreenSettingsDialogVisible
                    || !NotificationManagerCompat.from(this).areNotificationsEnabled()) return;
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null
                    : manager.getNotificationChannel(
                    FocusTimerService.ACTIVE_NOTIFICATION_CHANNEL_ID);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                showFocusLockscreenSettingsDialog(
                        "“正在进行的专注”通知已被关闭。请先重新开启此通知，再确认锁屏通知显示内容。",
                        true);
                return;
            }
            if (!uiPreferences.getBoolean(PREF_FOCUS_LOCKSCREEN_SETTINGS_CONFIRMED, false)) {
                showFocusLockscreenSettingsDialog(
                        "请在“锁屏通知”中选择“显示通知及内容”。这样锁屏时会持续显示番茄钟倒计时。",
                        false);
            }
        }, 500L);
    }

    private void showFocusLockscreenSettingsDialog(String message, boolean channelOnly) {
        if (isFinishing() || isDestroyed() || focusLockscreenSettingsDialogVisible) return;
        focusLockscreenSettingsDialogVisible = true;
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("锁屏显示番茄钟")
                .setMessage(message)
                .setNegativeButton("稍后", null)
                .setPositiveButton(channelOnly ? "打开专注通知设置" : "打开应用通知设置",
                        (ignored, which) -> openFocusLockscreenSettings(channelOnly))
                .create();
        dialog.setOnDismissListener(ignored -> focusLockscreenSettingsDialogVisible = false);
        dialog.show();
    }

    private void openFocusLockscreenSettings(boolean channelOnly) {
        uiPreferences.edit()
                .putBoolean(PREF_FOCUS_LOCKSCREEN_SETTINGS_RETURN_PENDING, true)
                .apply();
        Intent settings = new Intent(channelOnly
                ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        if (channelOnly) {
            settings.putExtra(Settings.EXTRA_CHANNEL_ID,
                    FocusTimerService.ACTIVE_NOTIFICATION_CHANNEL_ID);
        }
        try {
            startActivity(settings);
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", getPackageName(), null)));
        }
    }

    private void confirmFocusLockscreenSettingsAfterReturn() {
        if (uiPreferences == null || !uiPreferences.getBoolean(
                PREF_FOCUS_LOCKSCREEN_SETTINGS_RETURN_PENDING, false)) return;
        uiPreferences.edit().remove(PREF_FOCUS_LOCKSCREEN_SETTINGS_RETURN_PENDING).apply();
        mainHandler.postDelayed(() -> {
            if (!activityStarted || isFinishing() || isDestroyed()
                    || focusLockscreenSettingsDialogVisible) return;
            focusLockscreenSettingsDialogVisible = true;
            AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                    .setTitle("确认锁屏显示")
                    .setMessage("请确认已在系统设置中将“锁屏通知”设为“显示通知及内容”。"
                            + "开启后，番茄钟会与音乐卡片同时出现在锁屏上。")
                    .setNegativeButton("继续设置", (ignored, which) ->
                            openFocusLockscreenSettings(false))
                    .setPositiveButton("已开启", (ignored, which) -> {
                        uiPreferences.edit()
                                .putBoolean(PREF_FOCUS_LOCKSCREEN_SETTINGS_CONFIRMED, true)
                                .apply();
                        showMessage("锁屏番茄钟显示已确认");
                    })
                    .create();
            dialog.setOnDismissListener(ignored -> focusLockscreenSettingsDialogVisible = false);
            dialog.show();
        }, 350L);
    }

    protected int parsePositiveInt(String raw, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(raw.trim()))); }
        catch (Exception ignored) { return fallback; }
    }

    protected View buildSchedulePage() {
        scheduleRenderedLocalDay = startOfDay(System.currentTimeMillis());
        transitionEndedTasksToAwaitingConfirmation(System.currentTimeMillis());
        PageScheduleBinding binding = schedulePageController.inflate(getLayoutInflater());
        scheduleSwipeGroup.closeOpenRow();
        scheduleSwipeArea = binding.scheduleScroll;
        scheduleCardGestureRegions.clear();
        scheduleScrollTarget = null;
        scheduleScrollFallback = null;
        scheduleAutoScrollEnabled = false;
        boolean viewingToday = sameDay(selectedDate.getTimeInMillis(),
                System.currentTimeMillis());
        if (!viewingToday && scheduleMode == SCHEDULE_REFLOW) scheduleMode = SCHEDULE_DAY;
        String selectedDateLabel = formatDateOnly(selectedDate.getTimeInMillis());
        String visibleDateLabel = getResources().getConfiguration().fontScale > 1.3f
                ? new java.text.SimpleDateFormat("M月d日", Locale.getDefault()).format(selectedDate.getTimeInMillis())
                : selectedDateLabel;
        binding.selectedDateButton.setText((viewingToday ? "今天 · " : "") + visibleDateLabel);
        binding.selectedDateButton.setContentDescription("日期与月历：" + selectedDateLabel);
        binding.selectedDateButton.setOnClickListener(view -> toggleScheduleCalendar());
        binding.previousDateButton.setOnClickListener(view -> moveScheduleDate(-1));
        binding.nextDateButton.setOnClickListener(view -> moveScheduleDate(1));
        binding.todayButton.setVisibility(viewingToday ? View.INVISIBLE : View.VISIBLE);
        binding.todayButton.setEnabled(!viewingToday);
        binding.todayButton.setContentDescription("回到今天的安排");
        binding.todayButton.setOnClickListener(view ->
                selectScheduleDate(System.currentTimeMillis()));
        if (scheduleMode == SCHEDULE_REFLOW) binding.scheduleEyebrow.setText("注意事项");
        else if (scheduleMode == SCHEDULE_CALENDAR) binding.scheduleEyebrow.setText("选择日期");
        else binding.scheduleEyebrow.setText(viewingToday ? "今天" : selectedDateLabel);
        binding.scheduleTitle.setText("安排");
        binding.addTaskButton.setOnClickListener(view -> {
            if (scheduleMode == SCHEDULE_TODO) showTodoTaskDialog();
            else showManualTaskDialog();
        });
        guideRegistry.register(GuideTarget.CREATE_TASK, binding.addTaskButton);
        applyPressFeedback(binding.addTaskButton);
        bindSchedulePageButton(binding.scheduleTabButton, SCHEDULE_DAY);
        bindSchedulePageButton(binding.todoTabButton, SCHEDULE_TODO);
        bindSchedulePageButton(binding.reflowTabButton, SCHEDULE_REFLOW);
        binding.scheduleSegments.setSelectedIndex(scheduleMode == SCHEDULE_CALENDAR
                ? scheduleModeBeforeCalendar : scheduleMode, false);
        if (!viewingToday) {
            binding.reflowTabButton.setText("注意");
            binding.reflowTabButton.setTextColor(Color.rgb(170, 170, 182));
            binding.reflowTabButton.setEnabled(false);
            binding.reflowTabButton.setOnClickListener(null);
            binding.reflowTabButton.setContentDescription("注意页仅在今天可用");
        } else {
            binding.reflowTabButton.setEnabled(true);
            binding.reflowTabButton.setContentDescription(getString(R.string.schedule_attention_content_description));
        }
        if (scheduleMode == SCHEDULE_DAY) buildDayScheduleContent(binding.scheduleBody);
        else if (scheduleMode == SCHEDULE_TODO) buildTodoContent(binding.scheduleBody);
        else if (scheduleMode == SCHEDULE_REFLOW) buildTaskListContent(binding.scheduleBody);
        else buildCalendarContent(binding.scheduleBody);
        if (!preserveScheduleScroll) schedulePageController.restoreScroll(scheduleScrollKey());
        if (scheduleMode == SCHEDULE_DAY && scheduleAutoScrollEnabled
                && !preserveScheduleScroll && !schedulePageController.hasSavedScroll(scheduleScrollKey())) {
            View target = scheduleScrollTarget != null ? scheduleScrollTarget : scheduleScrollFallback;
            if (target != null) binding.scheduleScroll.post(() -> binding.scheduleScroll.scrollTo(
                    0, Math.max(0, target.getTop() - dp(24))));
        }
        return binding.getRoot();
    }

    private void moveScheduleDate(int days) {
        Calendar next = (Calendar) selectedDate.clone();
        next.add(Calendar.DAY_OF_YEAR, days);
        selectScheduleDate(next.getTimeInMillis());
    }

    private void toggleScheduleCalendar() {
        schedulePageController.saveScroll(scheduleScrollKey());
        schedulePageTransitionDirection = 1;
        if (scheduleMode == SCHEDULE_CALENDAR) {
            scheduleMode = scheduleModeBeforeCalendar == SCHEDULE_TODO
                    ? SCHEDULE_TODO : SCHEDULE_DAY;
        } else {
            scheduleModeBeforeCalendar = scheduleMode == SCHEDULE_TODO
                    ? SCHEDULE_TODO : SCHEDULE_DAY;
            calendarMonth.setTimeInMillis(selectedDate.getTimeInMillis());
            scheduleMode = SCHEDULE_CALENDAR;
        }
        preserveScheduleScroll = false;
        renderCurrentPage();
    }

    private void selectScheduleDate(long dateAt) {
        schedulePageController.saveScroll(scheduleScrollKey());
        schedulePageTransitionDirection = dateAt >= selectedDate.getTimeInMillis() ? 1 : -1;
        selectedDate.setTimeInMillis(startOfDay(dateAt));
        calendarMonth.setTimeInMillis(selectedDate.getTimeInMillis());
        if (scheduleMode == SCHEDULE_CALENDAR) {
            scheduleMode = scheduleModeBeforeCalendar == SCHEDULE_TODO
                    ? SCHEDULE_TODO : SCHEDULE_DAY;
        } else if (scheduleMode == SCHEDULE_REFLOW) {
            scheduleMode = SCHEDULE_DAY;
        }
        preserveScheduleScroll = false;
        renderCurrentPage();
    }

    private String scheduleScrollKey() { return scheduleMode + ":" + startOfDay(selectedDate.getTimeInMillis()); }

    private int scheduledCountForDay(long dateAt) {
        int count = 0;
        List<TaskItem> tasks = repository.getAllTasks();
        for (TaskItem task : tasks) {
            if (task.getStatus() != TaskItem.Status.CANCELLED && task.isScheduled()
                    && sameDay(task.getScheduledStartAt(), dateAt)) count++;
        }
        count += projectedRecurringEntriesForDay(dateAt, tasks).size();
        return count;
    }

    private void bindSchedulePageButton(TextView button, int mode) {
        boolean selected = scheduleMode == mode;
        button.setTextColor(selected ? PRIMARY : TEXT_MUTED);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setSelected(selected);
        ViewCompat.setStateDescription(button, selected ? "已选择" : null);
        button.setOnClickListener(view -> {
            selectScheduleMode(mode);
        });
        applyPressFeedback(button);
    }

    private void selectScheduleMode(int mode) {
        if (mode < SCHEDULE_DAY || mode > SCHEDULE_REFLOW) return;
        boolean viewingToday = sameDay(selectedDate.getTimeInMillis(),
                System.currentTimeMillis());
        if (mode == SCHEDULE_REFLOW && !viewingToday) return;
        int previousMode = scheduleMode;
        schedulePageController.saveScroll(scheduleScrollKey());
        if (mode == previousMode) {
            return;
        } else if (previousMode <= SCHEDULE_REFLOW) {
            schedulePageTransitionDirection = mode > previousMode ? 1 : -1;
        }
        preserveScheduleScroll = false;
        scheduleMode = mode;
        PageScheduleBinding binding = schedulePageController.getBinding();
        if (binding != null && binding.getRoot().isAttachedToWindow()) {
            binding.scheduleSegments.setSelectedIndex(mode, false);
            bindSchedulePageButton(binding.scheduleTabButton, SCHEDULE_DAY);
            bindSchedulePageButton(binding.todoTabButton, SCHEDULE_TODO);
            bindSchedulePageButton(binding.reflowTabButton, SCHEDULE_REFLOW);
        }
        renderCurrentPage();
    }

    private int todoTaskCount(long dateAt) {
        int count = 0;
        long now = System.currentTimeMillis();
        for (TaskItem task : repository.getAllTasks()) {
            if (isTodoVisibleOnDate(task, dateAt, now)) count++;
        }
        return count;
    }

    private int todoTaskCount() {
        return todoTaskCount(System.currentTimeMillis());
    }

    private boolean isTodoVisibleOnDate(TaskItem task, long dateAt, long now) {
        if (task == null || task.requiresTimeArrangement()
                || task.hasCompleteTimeArrangement()
                || task.getStatus() == TaskItem.Status.CANCELLED) return false;
        return !requiresReflow(task, now) && TodoDatePolicy.isVisible(task.getPlannedDateAt(),
                task.getStatus() == TaskItem.Status.COMPLETED, task.getCompletedAt(),
                dateAt, now, TimeZone.getDefault());
    }

    private int reflowTaskCount() {
        return new com.hawson.timeassistant.proactive.AttentionIssueProvider(repository)
                .snapshot(System.currentTimeMillis()).count();
    }

    private boolean requiresReflow(TaskItem task, long now) {
        return com.hawson.timeassistant.proactive.AttentionIssueProvider
                .requiresReflow(task, now);
    }

    private boolean requiresCompletionConfirmation(TaskItem task, long now) {
        return com.hawson.timeassistant.proactive.AttentionIssueProvider
                .requiresCompletionConfirmation(task, now);
    }

    private void transitionEndedTasksToAwaitingConfirmation(long now) {
        if (repository == null) return;
        normalizingEndedTaskStates = true;
        try {
            for (TaskItem task : repository.getAllTasks()) {
                if (!task.isScheduled() || task.getStatus().isTerminal()
                        || task.getStatus() == TaskItem.Status.NEEDS_RESCHEDULING
                        || task.getStatus() == TaskItem.Status.AWAITING_CONFIRMATION
                        || task.getScheduledEndAt() > now) continue;
                task.setStatus(TaskItem.Status.AWAITING_CONFIRMATION);
                repository.upsertTask(task);
            }
        } finally {
            normalizingEndedTaskStates = false;
        }
    }

    private String dayViewTitle(long value) {
        if (sameDay(value, System.currentTimeMillis())) return "今天";
        return new SimpleDateFormat("M月d日 EEEE", LOCALE).format(new Date(value));
    }

    private void buildDayScheduleContent(LinearLayout body) {
        List<TaskItem> scheduled = new ArrayList<>();
        List<DayScheduleEntry> entries = new ArrayList<>();
        List<TaskItem> dueWithoutStart = new ArrayList<>();
        long selected = selectedDate.getTimeInMillis();
        List<TaskItem> allTasks = repository.getAllTasks();
        for (TaskItem task : allTasks) {
            if (task.getStatus() == TaskItem.Status.CANCELLED) continue;
            if (task.isScheduled() && sameDay(task.getScheduledStartAt(), selected)) {
                scheduled.add(task);
                entries.add(DayScheduleEntry.forTask(task));
            }
            if (task.getStatus() != TaskItem.Status.COMPLETED && task.requiresTimeArrangement()
                    && task.getScheduledStartAt() == 0 && task.getDueAt() > 0
                    && sameDay(task.getDueAt(), selected)) dueWithoutStart.add(task);
        }
        entries.addAll(projectedRecurringEntriesForDay(selected, allTasks));
        entries.sort(Comparator.comparingLong(entry -> entry.startAt));
        dueWithoutStart.sort(Comparator.comparingInt(TaskItem::getPriority).reversed()
                .thenComparing(TaskItem::getCreatedAt));
        boolean allCompleted = !entries.isEmpty();
        for (DayScheduleEntry entry : entries) {
            if (entry.task == null || entry.task.getStatus() != TaskItem.Status.COMPLETED) {
                allCompleted = false;
                break;
            }
        }
        scheduleAutoScrollEnabled = sameDay(selected, System.currentTimeMillis()) && !allCompleted;

        if (entries.isEmpty()) {
            addScheduleEmpty(body, "暂无日程", "点击右上角 + 创建，日期和时间可以稍后补充");
        } else {
            for (DayScheduleEntry entry : entries) {
                if (entry.task != null) addTimelineTask(body, entry.task);
                else addTimelineRecurring(body, entry);
            }
        }
        if (!dueWithoutStart.isEmpty()) {
            String section = sameDay(selected, System.currentTimeMillis())
                    ? "今天截止 · 未定时间" : formatDateOnly(selected) + "截止 · 未定时间";
            body.addView(label(section, 14, WARNING, Typeface.BOLD),
                    verticalMargins(lp(-2, -2), dp(18), dp(10)));
            for (TaskItem task : dueWithoutStart) {
                addTaskCard(body, task, false);
                body.addView(new Space(this), lp(1, dp(9)));
            }
        }
        addSkippedRecurringOccurrences(body, selected);
    }

    private void addSkippedRecurringOccurrences(LinearLayout body, long dayAt) {
        for (RecurringOccurrenceOverride override : repository.getAllRecurringOverrides()) {
            if (!override.isSkipped() || !sameDay(override.getOriginalOccurrenceAt(), dayAt)) continue;
            RecurringTaskGroup group = repository.getRecurringGroup(override.getRecurringGroupId());
            if (group == null || !group.isActive()) continue;
            LinearLayout line = row(); line.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = label(group.getTitle() + " · 已跳过", 14, TEXT_MUTED, Typeface.NORMAL);
            line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            TextView restore = filterChip("恢复本次", false, v ->
                    repository.deleteRecurringOverride(override.getId()));
            restore.setMinHeight(dp(48));
            restore.setContentDescription("恢复本次：" + group.getTitle());
            line.addView(restore, marginLeft(lp(-2, -2), dp(8)));
            body.addView(line, marginTop(lp(-1, -2), dp(8)));
        }
    }

    private boolean hasRecurringInstance(List<TaskItem> tasks, String groupId, long occurrenceAt) {
        for (TaskItem task : tasks) {
            if (groupId.equals(task.getRecurringGroupId())
                    && occurrenceAt == task.getRecurringOccurrenceAt()) return true;
        }
        return false;
    }

    private List<DayScheduleEntry> projectedRecurringEntriesForDay(
            long dayAt, List<TaskItem> tasks) {
        List<DayScheduleEntry> result = new ArrayList<>();
        Set<String> added = new HashSet<>();
        Map<String, RecurringTaskGroup> groups = new java.util.LinkedHashMap<>();
        for (RecurringTaskGroup group : repository.getAllRecurringGroups()) {
            groups.put(group.getId(), group);
            long original = group.occurrenceStartOnDay(dayAt);
            if (original <= 0 || hasRecurringInstance(tasks, group.getId(), original)) continue;
            RecurringOccurrenceOverride override =
                    repository.getRecurringOverride(group.getId(), original);
            if (override == null) {
                result.add(DayScheduleEntry.forRecurring(group, original, null));
                added.add(RecurringOccurrenceOverride.stableId(group.getId(), original));
            } else if (!override.isSkipped()) {
                DayScheduleEntry entry = DayScheduleEntry.forRecurring(group, original, override);
                if (sameDay(entry.startAt, dayAt)) {
                    result.add(entry);
                    added.add(override.getId());
                }
            }
        }
        for (RecurringOccurrenceOverride override : repository.getAllRecurringOverrides()) {
            if (override.isSkipped() || added.contains(override.getId())) continue;
            RecurringTaskGroup group = groups.get(override.getRecurringGroupId());
            if (group == null || !group.isActive()
                    || hasRecurringInstance(tasks, group.getId(),
                    override.getOriginalOccurrenceAt())) continue;
            DayScheduleEntry entry = DayScheduleEntry.forRecurring(group,
                    override.getOriginalOccurrenceAt(), override);
            if (sameDay(entry.startAt, dayAt)) result.add(entry);
        }
        return result;
    }

    private TaskItem materializeRecurringOccurrence(RecurringTaskGroup group, long occurrenceAt) {
        if (group == null || occurrenceAt <= 0) return null;
        for (TaskItem task : repository.getAllTasks()) {
            if (group.getId().equals(task.getRecurringGroupId())
                    && occurrenceAt == task.getRecurringOccurrenceAt()) return task;
        }
        RecurringOccurrenceOverride override =
                repository.getRecurringOverride(group.getId(), occurrenceAt);
        if (override != null && override.isSkipped()) return null;
        long effectiveStart = override == null ? occurrenceAt
                : override.effectiveStartAt(occurrenceAt);
        long endAt;
        try { endAt = override == null
                ? Math.addExact(effectiveStart, group.getDurationMillis())
                : override.effectiveEndAt(effectiveStart, group.getDurationMillis()); }
        catch (ArithmeticException overflow) { return null; }
        TaskItem instance = new TaskItem(override == null ? group.getTitle()
                : override.effectiveTitle(group))
                .setDescription(override == null ? group.getDescription()
                        : override.effectiveDescription(group))
                .setSource(TaskItem.SOURCE_RECURRING)
                .setRequiresTimeArrangement(true)
                .setRecurringOccurrence(group.getId(), occurrenceAt)
                .schedule(effectiveStart, endAt, false);
        return repository.upsertTask(instance);
    }

    private void addTimelineRecurring(LinearLayout body, DayScheduleEntry entry) {
        RecurringTaskGroup group = entry.group;
        long occurrenceAt = entry.originalOccurrenceAt;
        long endAt = entry.endAt;
        LinearLayout line = com.hawson.timeassistant.ui.schedule.ScheduleTaskLayout.timeline(
                this, formatTime(entry.startAt), formatTime(endAt));
        LinearLayout card = com.hawson.timeassistant.ui.schedule.ScheduleTaskLayout.card(this);
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(label(entry.title, 16, TEXT, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = UiKit.tag(this, entry.override == null ? "周期" : "周期例外", PRIMARY);
        top.addView(state, marginLeft(lp(-2, -2), dp(8)));
        card.addView(top);
        if (!entry.description.trim().isEmpty()) {
            TextView description = label(entry.description, 12, TEXT_MUTED, Typeface.NORMAL);
            description.setMaxLines(3);
            description.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(description, marginTop(lp(-1, -2), dp(6)));
        }
        card.addView(label(formatRecurrenceRule(group),
                11, PRIMARY, Typeface.BOLD), marginTop(lp(-2, -2), dp(7)));
        LinearLayout quick = row();
        TextView startAction = label("开始", 12, ACCENT, Typeface.BOLD);
        TextView manageAction = label("管理周期 ›", 12, PRIMARY, Typeface.BOLD);
        startAction.setGravity(Gravity.CENTER);
        manageAction.setGravity(Gravity.CENTER);
        startAction.setOnClickListener(v -> {
            TaskItem instance = materializeRecurringOccurrence(group, occurrenceAt);
            if (instance == null) showMessage("无法创建本次周期任务");
            else performTaskPrimaryAction(instance.getId());
        });
        manageAction.setOnClickListener(v -> showRecurringOccurrenceActions(
                group.getId(), occurrenceAt));
        applyPressFeedback(startAction);
        applyPressFeedback(manageAction);
        startAction.setMinHeight(dp(48)); manageAction.setMinHeight(dp(48));
        quick.addView(startAction, new LinearLayout.LayoutParams(0, -2, 1));
        quick.addView(manageAction, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(quick, marginTop(lp(-1, -2), dp(8)));
        final long[] lastTapAt = {0};
        card.setOnClickListener(v -> {
            long tappedAt = System.currentTimeMillis();
            if (tappedAt - lastTapAt[0] <= 300L) {
                lastTapAt[0] = 0;
                TaskItem instance = materializeRecurringOccurrence(group, occurrenceAt);
                if (instance != null) startFocusForTask(instance.getId(), 0);
            } else {
                lastTapAt[0] = tappedAt;
                mainHandler.postDelayed(() -> {
                    if (lastTapAt[0] == tappedAt) {
                        lastTapAt[0] = 0;
                        showRecurringOccurrenceActions(group.getId(), occurrenceAt);
                    }
                }, 310L);
            }
        });
        applyPressFeedback(card);
        scheduleCardGestureRegions.add(card);
        com.hawson.timeassistant.ui.schedule.ScheduleTaskLayout.content(line, card);
        body.addView(line, marginBottom(lp(-1, -2), dp(12)));
        if (scheduleAutoScrollEnabled && scheduleScrollTarget == null
                && endAt >= System.currentTimeMillis()) scheduleScrollTarget = line;
    }

    private void showRecurringGroupDialog(String groupId) {
        RecurringTaskGroup group = repository.getRecurringGroup(groupId);
        if (group == null) {
            showMessage("周期日程不存在或已删除");
            return;
        }
        String message = group.getDescription().trim().isEmpty() ? ""
                : group.getDescription().trim() + "\n\n";
        message += "规律：" + formatRecurrenceRule(group)
                + "\n首次：" + formatDateTime(group.getAnchorStartAt())
                + "\n时段：" + formatTime(group.getAnchorStartAt()) + "–"
                + formatTime(group.getAnchorStartAt() + group.getDurationMillis())
                + "\n\n停止周期不会删除已经开始或完成的历史任务。";
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle(group.getTitle())
                .setMessage(message)
                .setNegativeButton("关闭", null)
                .setNeutralButton("编辑系列", (dialog, which) ->
                        showRecurringEditScopeDialog(groupId, group.getAnchorStartAt()))
                .setPositiveButton("结束系列", (dialog, which) -> {
                    RecurringTaskGroup latest = repository.getRecurringGroup(groupId);
                    if (latest != null) repository.upsertRecurringGroup(latest.setActive(false));
                    showMessage("已结束后续周期，历史任务仍保留");
                })
                .show();
    }

    private void showRecurringOccurrenceActions(String groupId, long originalOccurrenceAt) {
        RecurringTaskGroup group = repository.getRecurringGroup(groupId);
        if (group == null) {
            showMessage("周期日程不存在或已结束");
            return;
        }
        RecurringOccurrenceOverride override =
                repository.getRecurringOverride(groupId, originalOccurrenceAt);
        List<String> actions = new ArrayList<>();
        actions.add("修改…");
        actions.add("跳过本次");
        if (override != null) actions.add("恢复本次为系列规则");
        actions.add("本次标记为已完成");
        actions.add("本次标记为未完成");
        actions.add("本次标记为取消");
        actions.add("查看与结束系列");
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle(group.getTitle())
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    String selected = actions.get(which);
                    if (selected.startsWith("修改")) {
                        showRecurringEditScopeDialog(groupId, originalOccurrenceAt);
                    } else if ("跳过本次".equals(selected)) {
                        repository.upsertRecurringOverride(
                                RecurringOccurrenceOverride.skipped(groupId, originalOccurrenceAt));
                        showMessage("已跳过本次，不会生成任务");
                    } else if (selected.startsWith("恢复本次")) {
                        repository.deleteRecurringOverride(
                                RecurringOccurrenceOverride.stableId(groupId, originalOccurrenceAt));
                        showMessage("本次已恢复为系列规则");
                    } else if (selected.contains("已完成")) {
                        setRecurringOccurrenceStatus(group, originalOccurrenceAt,
                                TaskItem.Status.COMPLETED);
                    } else if (selected.contains("未完成")) {
                        setRecurringOccurrenceStatus(group, originalOccurrenceAt,
                                TaskItem.Status.SCHEDULED);
                    } else if (selected.contains("取消")) {
                        setRecurringOccurrenceStatus(group, originalOccurrenceAt,
                                TaskItem.Status.CANCELLED);
                    } else {
                        showRecurringGroupDialog(groupId);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void setRecurringOccurrenceStatus(RecurringTaskGroup group, long occurrenceAt,
                                              TaskItem.Status status) {
        TaskItem instance = materializeRecurringOccurrence(group, occurrenceAt);
        if (instance == null) {
            showMessage("本次已跳过，恢复后才能标记状态");
            return;
        }
        instance.setStatus(status);
        repository.upsertTask(instance);
        showMessage(status == TaskItem.Status.COMPLETED ? "本次已完成"
                : status == TaskItem.Status.CANCELLED ? "本次已取消" : "本次已恢复未完成");
    }

    private void showRecurringEditScopeDialog(String groupId, long occurrenceAt) {
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("修改作用范围")
                .setItems(new String[]{"仅本次", "本次及以后", "整个系列"},
                        (dialog, which) -> showRecurringOccurrenceEditor(groupId, occurrenceAt,
                                which == 0 ? RecurrenceEditScope.ONLY_THIS
                                        : which == 1 ? RecurrenceEditScope.THIS_AND_FOLLOWING
                                        : RecurrenceEditScope.ENTIRE_SERIES))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRecurringOccurrenceEditor(String groupId, long originalOccurrenceAt,
                                               RecurrenceEditScope scope) {
        RecurringTaskGroup group = repository.getRecurringGroup(groupId);
        if (group == null) return;
        RecurringOccurrenceOverride existing =
                repository.getRecurringOverride(groupId, originalOccurrenceAt);
        long initialStart = existing == null ? originalOccurrenceAt
                : existing.effectiveStartAt(originalOccurrenceAt);
        long initialEnd = existing == null ? initialStart + group.getDurationMillis()
                : existing.effectiveEndAt(initialStart, group.getDurationMillis());
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), dp(16));
        EditText title = edit("标题", existing == null ? group.getTitle()
                : existing.effectiveTitle(group), false);
        EditText description = edit("描述或备注", existing == null ? group.getDescription()
                : existing.effectiveDescription(group), false);
        description.setSingleLine(false); description.setMinLines(2);
        form.addView(title, lp(-1, dp(50)));
        form.addView(description, marginTop(lp(-1, dp(72)), dp(8)));
        final long[] dateAt = {startOfDay(initialStart)};
        final long[] startAt = {initialStart};
        final long[] endAt = {initialEnd};
        TextView date = filterChip("日期 · " + formatDateOnly(initialStart), true, null);
        TextView start = filterChip("开始 · " + formatTime(initialStart), true, null);
        TextView end = filterChip("结束 · " + formatTime(initialEnd), true, null);
        date.setOnClickListener(v -> pickDateOnly(dateAt[0], selected -> {
            dateAt[0] = selected;
            startAt[0] = moveTimeToDate(startAt[0], selected);
            endAt[0] = moveTimeToDate(endAt[0], selected);
            date.setText("日期 · " + formatDateOnly(selected));
        }));
        start.setOnClickListener(v -> pickOptionalTaskTime(dateAt[0], startAt[0], selected -> {
            startAt[0] = selected; start.setText("开始 · " + formatTime(selected));
        }));
        end.setOnClickListener(v -> pickOptionalTaskTime(dateAt[0], endAt[0], selected -> {
            endAt[0] = selected; end.setText("结束 · " + formatTime(selected));
        }));
        form.addView(label("本次时段", 12, TEXT_MUTED, Typeface.BOLD),
                verticalMargins(lp(-2, -2), dp(14), dp(8)));
        form.addView(date, lp(-1, dp(44)));
        LinearLayout times = row();
        times.addView(start, new LinearLayout.LayoutParams(0, dp(44), 1));
        times.addView(end, marginLeft(new LinearLayout.LayoutParams(0, dp(44), 1), dp(8)));
        form.addView(times, marginTop(lp(-1, dp(44)), dp(8)));
        RecurrenceRuleDraft rule = RecurrenceRuleDraft.from(group);
        TextView ruleButton = filterChip(formatRecurrenceRule(rule), true, null);
        if (scope != RecurrenceEditScope.ONLY_THIS) {
            form.addView(label("后续重复规则", 12, TEXT_MUTED, Typeface.BOLD),
                    verticalMargins(lp(-2, -2), dp(14), dp(8)));
            form.addView(ruleButton, lp(-1, dp(44)));
            ruleButton.setOnClickListener(v -> showRecurrenceRulePicker(rule, selected -> {
                rule.copyFrom(selected);
                ruleButton.setText(formatRecurrenceRule(rule));
            }));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        String dialogTitle = scope == RecurrenceEditScope.ONLY_THIS ? "仅修改本次"
                : scope == RecurrenceEditScope.THIS_AND_FOLLOWING ? "修改本次及以后"
                : "修改整个系列";
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle(dialogTitle).setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = title.getText().toString().trim();
                    if (name.isEmpty()) { title.setError("请输入标题"); return; }
                    if (endAt[0] <= startAt[0]) {
                        showMessage("结束时间需要晚于开始时间"); return;
                    }
                    String note = description.getText().toString().trim();
                    if (scope == RecurrenceEditScope.ONLY_THIS) {
                        repository.upsertRecurringOverride(RecurringOccurrenceOverride.modified(
                                groupId, originalOccurrenceAt, name, note,
                                startAt[0], endAt[0]));
                    } else if (scope == RecurrenceEditScope.THIS_AND_FOLLOWING) {
                        RecurringTaskGroup old = repository.getRecurringGroup(groupId);
                        old.setEndCondition(originalOccurrenceAt - 1L, 0);
                        repository.upsertRecurringGroup(old);
                        RecurringTaskGroup fork = group.forkFrom(startAt[0])
                                .setTitle(name).setDescription(note)
                                .setRule(startAt[0], endAt[0] - startAt[0],
                                        rule.unit, rule.interval)
                                .setAdvancedRule(rule.workdaysOnly, rule.weekdays,
                                        rule.monthlyOrdinal, rule.monthlyWeekday,
                                        rule.endsAt, rule.maxOccurrences);
                        repository.upsertRecurringGroup(fork);
                    } else {
                        RecurringTaskGroup latest = repository.getRecurringGroup(groupId);
                        latest.setTitle(name).setDescription(note)
                                .setRule(startAt[0], endAt[0] - startAt[0],
                                        rule.unit, rule.interval)
                                .setAdvancedRule(rule.workdaysOnly, rule.weekdays,
                                        rule.monthlyOrdinal, rule.monthlyWeekday,
                                        rule.endsAt, rule.maxOccurrences);
                        repository.upsertRecurringGroup(latest);
                    }
                    dialog.dismiss();
                    showMessage(scope == RecurrenceEditScope.ONLY_THIS
                            ? "已仅修改本次" : scope == RecurrenceEditScope.THIS_AND_FOLLOWING
                            ? "已从本次拆分为新系列" : "整个系列已更新");
                }));
        dialog.show();
    }

    private interface CalendarDateSelection {
        void onSelected(Calendar date);
    }

    private void showChatHistoryCalendar() {
        hideChatKeyboard();
        chatCalendarMonth.setTimeInMillis(selectedChatDate.getTimeInMillis());
        LinearLayout body = column();
        body.setPadding(dp(8), 0, dp(8), dp(4));
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("聊天记录")
                .setView(body)
                .setNegativeButton("关闭", null)
                .create();
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            body.removeAllViews();
            buildMonthCalendarContent(
                    body,
                    chatCalendarMonth,
                    selectedChatDate,
                    System.currentTimeMillis(),
                    () -> {
                        chatCalendarMonth.add(Calendar.MONTH, -1);
                        render[0].run();
                    },
                    () -> {
                        chatCalendarMonth.add(Calendar.MONTH, 1);
                        render[0].run();
                    },
                    value -> {
                        selectedChatDate = (Calendar) value.clone();
                        hideChatKeyboard();
                        dialog.dismiss();
                        renderCurrentPage();
                    },
                    "回到当前聊天",
                    () -> {
                        selectedChatDate.setTimeInMillis(activeChatDay);
                        chatCalendarMonth.setTimeInMillis(activeChatDay);
                        hideChatKeyboard();
                        dialog.dismiss();
                        renderCurrentPage();
                    });
        };
        render[0].run();
        dialog.show();
    }

    private void buildCalendarContent(LinearLayout body) {
        buildMonthCalendarContent(
                body,
                calendarMonth,
                selectedDate,
                0L,
                () -> {
                    calendarMonth.add(Calendar.MONTH, -1);
                    renderCurrentPage();
                },
                () -> {
                    calendarMonth.add(Calendar.MONTH, 1);
                    renderCurrentPage();
                },
                value -> {
                    selectedDate = (Calendar) value.clone();
                    selectScheduleDate(value.getTimeInMillis());
                },
                "回到今天",
                () -> {
                    selectedDate.setTimeInMillis(System.currentTimeMillis());
                    calendarMonth.setTimeInMillis(selectedDate.getTimeInMillis());
                    selectScheduleDate(System.currentTimeMillis());
                });
    }

    private void buildMonthCalendarContent(LinearLayout body, Calendar displayedMonth,
                                           Calendar selectedDay, long latestSelectableAt,
                                           Runnable previousMonth, Runnable nextMonth,
                                           CalendarDateSelection dateSelection,
                                           String returnLabel, Runnable returnAction) {
        LinearLayout monthHeader = row();
        monthHeader.setGravity(Gravity.CENTER_VERTICAL);
        ImageView previous = iconButton(R.drawable.ic_chevron_left, "上个月");
        TextView monthTitle = label(new SimpleDateFormat("yyyy年 M月", LOCALE)
                .format(displayedMonth.getTime()), 18, TEXT, Typeface.BOLD);
        monthTitle.setGravity(Gravity.CENTER);
        ImageView next = iconButton(R.drawable.ic_chevron_right, "下个月");
        previous.setOnClickListener(v -> previousMonth.run());
        Calendar nextCandidate = (Calendar) displayedMonth.clone();
        nextCandidate.set(Calendar.DAY_OF_MONTH, 1);
        nextCandidate.add(Calendar.MONTH, 1);
        boolean canOpenNextMonth = latestSelectableAt <= 0L
                || startOfDay(nextCandidate.getTimeInMillis())
                <= startOfDay(latestSelectableAt);
        next.setEnabled(canOpenNextMonth);
        next.setAlpha(canOpenNextMonth ? 1f : 0.34f);
        if (canOpenNextMonth) next.setOnClickListener(v -> nextMonth.run());
        monthHeader.addView(previous, lp(dp(44), dp(44)));
        monthHeader.addView(monthTitle, new LinearLayout.LayoutParams(0, dp(44), 1));
        monthHeader.addView(next, lp(dp(44), dp(44)));
        body.addView(monthHeader, marginBottom(lp(-1, dp(44)), dp(10)));

        LinearLayout weekdayRow = row();
        for (String weekday : new String[]{"一", "二", "三", "四", "五", "六", "日"}) {
            TextView value = label(weekday, 12, TEXT_MUTED, Typeface.BOLD);
            value.setGravity(Gravity.CENTER);
            weekdayRow.addView(value, new LinearLayout.LayoutParams(0, dp(34), 1));
        }
        body.addView(weekdayRow);

        Calendar cursor = (Calendar) displayedMonth.clone();
        cursor.set(Calendar.DAY_OF_MONTH, 1);
        int offset = cursor.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                ? 6 : cursor.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        cursor.add(Calendar.DAY_OF_MONTH, -offset);
        for (int week = 0; week < 6; week++) {
            LinearLayout weekRow = row();
            for (int day = 0; day < 7; day++) {
                Calendar value = (Calendar) cursor.clone();
                boolean currentMonth = value.get(Calendar.MONTH) == displayedMonth.get(Calendar.MONTH)
                        && value.get(Calendar.YEAR) == displayedMonth.get(Calendar.YEAR);
                boolean selectable = latestSelectableAt <= 0L
                        || startOfDay(value.getTimeInMillis())
                        <= startOfDay(latestSelectableAt);
                boolean selected = selectable
                        && sameDay(value.getTimeInMillis(), selectedDay.getTimeInMillis());
                boolean today = sameDay(value.getTimeInMillis(), System.currentTimeMillis());
                TextView date = label(String.valueOf(value.get(Calendar.DAY_OF_MONTH)), 14,
                        !selectable ? Color.rgb(205, 205, 214)
                                : selected ? ON_BRAND
                                : currentMonth ? TEXT : Color.rgb(184, 184, 196),
                        today || selected ? Typeface.BOLD : Typeface.NORMAL);
                date.setGravity(Gravity.CENTER);
                date.setBackground(selected ? rounded(PRIMARY, UiKit.RADIUS_CONTAINER)
                        : today ? outlined(PRIMARY_SOFT, PRIMARY, UiKit.RADIUS_CONTAINER, 2) : null);
                date.setEnabled(selectable);
                date.setContentDescription(new SimpleDateFormat("yyyy年M月d日 EEEE", LOCALE)
                        .format(value.getTime()) + (selectable ? "" : "，未来日期不可查看"));
                if (selectable) {
                    date.setOnClickListener(v -> dateSelection.onSelected(
                            (Calendar) value.clone()));
                    applyPressFeedback(date);
                }
                weekRow.addView(date, new LinearLayout.LayoutParams(0, dp(48), 1));
                cursor.add(Calendar.DAY_OF_MONTH, 1);
            }
            body.addView(weekRow);
        }
        if (returnLabel != null && returnAction != null) {
            TextView returnButton = filterChip(returnLabel, true, v -> returnAction.run());
            body.addView(returnButton, marginTop(lp(-1, dp(44)), dp(12)));
        }
    }

    private void addTimelineTask(LinearLayout body, TaskItem task) {
        LinearLayout line = com.hawson.timeassistant.ui.schedule.ScheduleTaskLayout.timeline(this,
                formatTime(task.getScheduledStartAt()), formatTime(task.getScheduledEndAt()));
        addTaskCard(line, task, true);
        body.addView(line, marginBottom(lp(-1, -2), dp(12)));
        if (scheduleAutoScrollEnabled) {
            if (task.getStatus() != TaskItem.Status.COMPLETED) {
                scheduleScrollFallback = line;
            }
            long now = System.currentTimeMillis();
            if (scheduleScrollTarget == null && task.getScheduledEndAt() >= now) {
                scheduleScrollTarget = line;
            }
        }
    }

    private void addTaskCard(LinearLayout parent, TaskItem task, boolean timeline) {
        LinearLayout card = com.hawson.timeassistant.ui.schedule.ScheduleTaskLayout.card(this);
        LinearLayout details = column();
        TextView title = label(task.getTitle(), 16, TEXT, Typeface.BOLD);
        if (task.getStatus() == TaskItem.Status.COMPLETED) {
            title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            title.setTextColor(TEXT_MUTED);
        }
        LinearLayout heading = row(); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (TaskItem.SOURCE_RECURRING.equals(task.getSource()))
            heading.addView(UiKit.tag(this, "周期", PRIMARY), marginLeft(lp(-2, -2), dp(8)));
        details.addView(heading, lp(-1, -2));
        if (task.getStatus() == TaskItem.Status.AWAITING_CONFIRMATION
                || task.getStatus() == TaskItem.Status.NEEDS_RESCHEDULING) {
            details.addView(label(statusLabel(task.getStatus()), 12, statusColor(task.getStatus()), Typeface.NORMAL),
                    marginTop(lp(-1, -2), dp(4)));
        }
        TaskRowControls controls = (TaskRowControls) taskRowControls(task);
        controls.content(details);
        card.addView(controls, lp(-1, -2));
        bindTaskCardTap(card, task);
        View wrapped = wrapTaskCardActions(card, task, true);
        if (timeline) com.hawson.timeassistant.ui.schedule.ScheduleTaskLayout.content(parent, wrapped);
        else parent.addView(wrapped, lp(-1, -2));
    }

    private String taskPrimaryActionLabel(TaskItem task) {
        if (task.getStatus() == TaskItem.Status.COMPLETED) return "撤销";
        if (isStandaloneTodo(task)) return "完成";
        if (task.getStatus() == TaskItem.Status.IN_PROGRESS
                || task.getStatus() == TaskItem.Status.AWAITING_CONFIRMATION) return "完成";
        return "开始";
    }

    private View taskRowControls(TaskItem task) {
        return new TaskRowControls(this, task.getTitle(),
                task.getStatus() == TaskItem.Status.COMPLETED,
                task.getStatus() == TaskItem.Status.CANCELLED,
                R.drawable.ic_more, TEXT_MUTED, new TaskRowControls.Actions() {
                    @Override public void completeOrUndo() {
                        TaskItem latest = repository.getTask(task.getId());
                        if (latest == null || latest.getStatus() == TaskItem.Status.CANCELLED) return;
                        if (currentPage == PAGE_SCHEDULE && scheduleMode == SCHEDULE_REFLOW
                                && schedulePageViewModel != null && attentionList != null) {
                            completeAttentionTask(latest);
                            return;
                        }
                        if (latest.getStatus() == TaskItem.Status.COMPLETED) {
                            confirmUndoTaskCompletion(latest);
                            return;
                        }
                        latest.setStatus(TaskItem.Status.COMPLETED);
                        repository.upsertTask(latest);
                        showMessage("任务已完成");
                        renderCurrentPage();
                    }
                    @Override public void edit() { openTaskEditor(task.getId()); }
                    @Override public void focus() { startFocusForTask(task.getId(), 0); }
                    @Override public void delete() { confirmDeleteTask(task.getId()); }
                });
    }

    private boolean isStandaloneTodo(TaskItem task) {
        return task != null && !task.requiresTimeArrangement()
                && !task.hasCompleteTimeArrangement();
    }

    private void performTaskPrimaryAction(String taskId) {
        TaskItem latest = repository.getTask(taskId);
        if (latest == null) return;
        if (latest.isScheduled() && !latest.getStatus().isTerminal()
                && latest.getStatus() != TaskItem.Status.NEEDS_RESCHEDULING
                && latest.getScheduledEndAt() <= System.currentTimeMillis()) {
            latest.setStatus(TaskItem.Status.AWAITING_CONFIRMATION);
        }
        String message;
        if (latest.getStatus() == TaskItem.Status.COMPLETED) {
            confirmUndoTaskCompletion(latest);
            return;
        } else if (isStandaloneTodo(latest)) {
            latest.setStatus(TaskItem.Status.COMPLETED);
            message = "待办已完成";
        } else if (latest.getStatus() == TaskItem.Status.IN_PROGRESS
                || latest.getStatus() == TaskItem.Status.AWAITING_CONFIRMATION) {
            latest.setStatus(TaskItem.Status.COMPLETED);
            message = "任务已完成";
        } else {
            latest.setStatus(TaskItem.Status.IN_PROGRESS);
            message = "任务已开始";
        }
        repository.upsertTask(latest);
        showMessage(message);
        renderCurrentPage();
    }

    private void confirmUndoTaskCompletion(TaskItem task) {
        long now = System.currentTimeMillis();
        boolean becomesOverdue = task.isScheduled() && task.getScheduledEndAt() <= now;
        String destination = becomesOverdue
                ? "该日程已经超过结束时间，撤销后会转为逾期，并进入“注意”页。"
                : "撤销后会恢复为待办，原有内容和时间安排会保留。";
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("撤销完成“" + task.getTitle() + "”？")
                .setMessage(destination)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认撤销", (dialog, which) ->
                        undoTaskCompletion(task.getId()))
                .show();
    }

    private void undoTaskCompletion(String taskId) {
        TaskItem latest = repository.getTask(taskId);
        if (latest == null) {
            showMessage("任务不存在或已删除");
            return;
        }
        if (latest.getStatus() != TaskItem.Status.COMPLETED) {
            showMessage("任务状态已经变化");
            renderCurrentPage();
            return;
        }
        long now = System.currentTimeMillis();
        boolean overdue = latest.isScheduled() && latest.getScheduledEndAt() <= now;
        latest.undoCompletion(now);
        repository.upsertTask(latest);
        showMessage(overdue ? "已撤销完成，任务已转为逾期" : "已撤销完成，任务已恢复为待办");
        renderCurrentPage();
    }

    private void bindTaskCardTap(View card, TaskItem task) {
        final long[] lastTapAt = {0};
        final Runnable[] pendingDetails = {null};
        card.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastTapAt[0] <= 300L) {
                if (pendingDetails[0] != null) mainHandler.removeCallbacks(pendingDetails[0]);
                lastTapAt[0] = 0;
                pendingDetails[0] = null;
                startFocusForTask(task.getId(), 0);
                return;
            }
            lastTapAt[0] = now;
            Runnable details = () -> {
                if (lastTapAt[0] == now) {
                    lastTapAt[0] = 0;
                    showTaskReadOnlyDetails(task.getId());
                }
            };
            pendingDetails[0] = details;
            mainHandler.postDelayed(details, 310L);
        });
    }

    private void buildTodoContent(LinearLayout body) {
        List<TaskItem> tasks = new ArrayList<>();
        long now = System.currentTimeMillis();
        long selected = selectedDate.getTimeInMillis();
        for (TaskItem task : repository.getAllTasks()) {
            if (isTodoVisibleOnDate(task, selected, now)) tasks.add(task);
        }
        tasks.sort((first, second) -> {
            int completed = Boolean.compare(first.getStatus() == TaskItem.Status.COMPLETED,
                    second.getStatus() == TaskItem.Status.COMPLETED);
            if (completed != 0) return completed;
            int progress = Boolean.compare(second.getStatus() == TaskItem.Status.IN_PROGRESS,
                    first.getStatus() == TaskItem.Status.IN_PROGRESS);
            if (progress != 0) return progress;
            if (first.hasCountdown() != second.hasCountdown()) {
                return first.hasCountdown() ? -1 : 1;
            }
            if (first.hasCountdown()) {
                int countdown = Long.compare(first.getCountdownTargetAt(),
                        second.getCountdownTargetAt());
                if (countdown != 0) return countdown;
            }
            int priority = Integer.compare(second.getPriority(), first.getPriority());
            return priority != 0 ? priority : Long.compare(first.getCreatedAt(), second.getCreatedAt());
        });
        if (tasks.isEmpty()) {
            body.addView(UiKit.emptyState(this, R.drawable.ic_task_check,
                    "暂无待办", "新建任务", this::showTodoTaskDialog), lp(-1, -2));
            return;
        }
        for (TaskItem task : tasks) {
            addScheduleListCard(body, task, true, null, true);
            body.addView(new Space(this), lp(1, dp(9)));
        }
    }

    private void buildTaskListContent(LinearLayout body) {
        attentionList = new com.hawson.timeassistant.ui.schedule.AttentionListView(this, (task, group) -> {
            LinearLayout holder = column();
            addScheduleListCard(holder, task, true, AttentionCardAction.valueOf(group), false);
            View card = holder.getChildAt(0); holder.removeView(card);
            return card;
        });
        body.addView(attentionList, lp(-1, -2));
        refreshAttentionList();
    }

    private void refreshAttentionList() {
        mainHandler.removeCallbacks(attentionExpiry);
        if (repository == null || schedulePageViewModel == null || attentionList == null
                || currentPage != PAGE_SCHEDULE || scheduleMode != SCHEDULE_REFLOW || settingsVisible) return;
        com.hawson.timeassistant.proactive.AttentionIssueProvider.Snapshot snapshot =
                new com.hawson.timeassistant.proactive.AttentionIssueProvider(repository)
                        .snapshot(System.currentTimeMillis());
        com.hawson.timeassistant.ui.schedule.AttentionCompletionBuffer buffer = schedulePageViewModel.completions;
        long now = android.os.SystemClock.elapsedRealtime();
        List<TaskItem> all = repository.getAllTasks();
        attentionList.bind("RESCHEDULE", "逾期", WARNING, buffer.merge("RESCHEDULE", snapshot.overdue, all, now));
        attentionList.bind("CONFIRM", "待确认", PRIMARY, buffer.merge("CONFIRM", snapshot.awaitingConfirmation, all, now));
        attentionList.bind("SET_TIME", "未确定时间", TEXT_MUTED, buffer.merge("SET_TIME", snapshot.incompleteTime, all, now));
        long deadline = buffer.nextDeadline(now);
        if (activityStarted && deadline != Long.MAX_VALUE)
            mainHandler.postDelayed(attentionExpiry, Math.max(1, deadline - now));
    }

    private void completeAttentionTask(TaskItem latest) {
        com.hawson.timeassistant.ui.schedule.AttentionCompletionBuffer buffer = schedulePageViewModel.completions;
        long now = android.os.SystemClock.elapsedRealtime();
        com.hawson.timeassistant.ui.schedule.AttentionCompletionBuffer.Entry pending = buffer.get(latest.getId(), now);
        if (pending != null) {
            boolean undone = repository.undoTaskCompletion(pending.ticket);
            buffer.remove(latest.getId());
            showMessage(undone ? "已取消完成" : "任务已变化，无法取消本次完成");
        } else if (!latest.getStatus().isTerminal()) {
            String group = attentionList.groupOf(latest.getId());
            int position = attentionList.positionOf(latest.getId());
            com.hawson.timeassistant.data.CompletionUndoTicket ticket =
                    repository.completeTaskForUndo(latest.getId(), latest.getUpdatedAt());
            if (ticket == null || group == null) { showMessage("任务已变化，请重新操作"); return; }
            long timeout = 5_000L;
            if (Build.VERSION.SDK_INT >= 29) {
                android.view.accessibility.AccessibilityManager accessibility =
                        (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
                if (accessibility != null) timeout = accessibility.getRecommendedTimeoutMillis(5_000,
                        android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS);
            }
            buffer.retain(ticket, group, position, now, timeout);
        } else { confirmUndoTaskCompletion(latest); return; }
        refreshAttentionList();
    }

    private void addTaskGroup(LinearLayout body, String title, List<TaskItem> tasks, int color,
                              AttentionCardAction action) {
        body.addView(label(title + "  " + tasks.size(), 14, color, Typeface.BOLD),
                verticalMargins(lp(-2, -2), body.getChildCount() == 0 ? 0 : dp(18), dp(10)));
        if (tasks.isEmpty()) {
            TextView empty = label("暂无", 12, TEXT_MUTED, Typeface.NORMAL);
            empty.setPadding(dp(14), dp(12), dp(14), dp(12));
            empty.setBackground(rounded(NEUTRAL, UiKit.RADIUS_CONTAINER));
            body.addView(empty, lp(-1, -2));
            return;
        }
        for (TaskItem task : tasks) {
            addScheduleListCard(body, task, true, action, false);
            body.addView(new Space(this), lp(1, dp(9)));
        }
    }

    private void addScheduleListCard(LinearLayout parent, TaskItem task,
                                     boolean showTimeSummary,
                                     AttentionCardAction attentionAction,
                                     boolean allowPrimaryAction) {
        LinearLayout card = com.hawson.timeassistant.ui.schedule.ScheduleTaskLayout.card(this);
        LinearLayout contentRow = row();
        contentRow.setGravity(Gravity.TOP);
        LinearLayout main = column();
        TextView title = label(task.getTitle(), 16, TEXT, Typeface.BOLD);
        if (task.getStatus() == TaskItem.Status.COMPLETED) {
            title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            title.setTextColor(TEXT_MUTED);
        }
        main.addView(title, lp(-1, -2));
        if (showTimeSummary) {
            TextView time = label(taskTimeSummary(task), 12, PRIMARY, Typeface.BOLD);
            time.setSingleLine(false);
            main.addView(time, marginTop(lp(-1, -2), dp(7)));
        }
        contentRow.addView(main, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout side = column();
        side.setGravity(Gravity.END);
        TaskItem.Status visualStatus = isStandaloneTodo(task)
                && task.getStatus() == TaskItem.Status.IN_PROGRESS
                ? TaskItem.Status.PENDING : task.getStatus();
        TextView state = label(statusLabel(visualStatus), 10,
                statusColor(visualStatus), Typeface.BOLD);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(8), 0, dp(8), 0);
        state.setBackground(rounded(statusBackground(visualStatus), UiKit.RADIUS_TAG));
        if (visualStatus == TaskItem.Status.AWAITING_CONFIRMATION
                || visualStatus == TaskItem.Status.NEEDS_RESCHEDULING) side.addView(state, lp(-2, dp(24)));
        if (task.hasCountdown() && !task.getStatus().isTerminal()) {
            TextView countdown = label(formatCountdownCompact(
                    task.getCountdownTargetAt() - System.currentTimeMillis()),
                    12, ACCENT, Typeface.BOLD);
            countdown.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            countdown.setGravity(Gravity.CENTER);
            countdown.setContentDescription("查看“" + task.getTitle() + "”的定时");
            countdown.setPadding(dp(8), 0, dp(8), 0);
            countdown.setBackground(rounded(ACCENT_SOFT, UiKit.RADIUS_TAG));
            countdown.setOnClickListener(v -> showCountdownDetails(task.getId()));
            applyPressFeedback(countdown);
            side.addView(countdown, marginTop(lp(dp(92), dp(44)), dp(8)));
            countdownCardBindings.add(new CountdownCardBinding(task.getId(), countdown));
        }
        if (side.getChildCount() > 0) main.addView(side, marginTop(lp(-1, -2), dp(8)));
        TaskRowControls controls = (TaskRowControls) taskRowControls(task);
        controls.content(contentRow);
        card.addView(controls, lp(-1, -2));
        card.setOnClickListener(view -> {
            if (attentionAction == AttentionCardAction.CONFIRM) {
                showTaskEndConfirmation(task.getId(), true);
            } else if (attentionAction == AttentionCardAction.RESCHEDULE
                    || attentionAction == AttentionCardAction.SET_TIME) {
                openTaskEditor(task.getId());
            } else {
                showTaskReadOnlyDetails(task.getId());
            }
        });
        parent.addView(wrapTaskCardActions(card, task, allowPrimaryAction), lp(-1, -2));
    }

    private enum AttentionCardAction { RESCHEDULE, CONFIRM, SET_TIME }

    private final com.hawson.timeassistant.ui.schedule.ScheduleSwipeGroup scheduleSwipeGroup =
            new com.hawson.timeassistant.ui.schedule.ScheduleSwipeGroup();

    private View wrapTaskCardActions(View foreground, TaskItem task, boolean allowPrimaryAction) {
        boolean showPrimary = allowPrimaryAction && task.getStatus() != TaskItem.Status.CANCELLED;
        String action = taskPrimaryActionLabel(task);
        int icon = !showPrimary ? R.drawable.ic_task_settings
                : task.getStatus() == TaskItem.Status.COMPLETED ? R.drawable.ic_task_undo
                : "开始".equals(action) ? android.R.drawable.ic_media_play : R.drawable.ic_task_check;
        android.graphics.drawable.Drawable mainIcon = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(this, icon).mutate();
        mainIcon.setTint(Color.WHITE);
        android.graphics.drawable.Drawable editIcon = showPrimary
                ? androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_task_settings).mutate()
                : null;
        if (editIcon != null) editIcon.setTint(Color.WHITE);
        com.hawson.timeassistant.ui.schedule.ScheduleSwipeLayout root =
                new com.hawson.timeassistant.ui.schedule.ScheduleSwipeLayout(this);
        root.setSwipeGroup(scheduleSwipeGroup);
        root.setActionColors(!showPrimary ? TASK_ACTION_GRAY
                : task.getStatus() == TaskItem.Status.COMPLETED ? TASK_ACTION_YELLOW : TASK_ACTION_GREEN,
                TASK_ACTION_GRAY);
        root.setContent(foreground);
        root.bind(task.copy(), mainIcon, (showPrimary ? action : "编辑") + "“" + task.getTitle() + "”",
                editIcon, "编辑“" + task.getTitle() + "”", (command, captured) -> {
            TaskItem expected = (TaskItem) captured;
            TaskItem latest = repository.getTask(expected.getId());
            if (latest == null || latest.getUpdatedAt() != expected.getUpdatedAt()
                    || latest.getStatus() != expected.getStatus()
                    || !taskPrimaryActionLabel(latest).equals(taskPrimaryActionLabel(expected))) {
                showMessage("任务已变化，请重新操作");
                return;
            }
            if (!showPrimary || command == com.hawson.timeassistant.ui.schedule.ScheduleSwipeLayout.Action.EDIT)
                openTaskEditor(latest.getId());
            else performTaskPrimaryAction(latest.getId());
        });
        if (showPrimary && !task.getStatus().isTerminal()) {
            ViewCompat.addAccessibilityAction(root, "进入专注", (view, arguments) -> {
                TaskItem latest = repository.getTask(task.getId());
                if (latest != null && !latest.getStatus().isTerminal()) startFocusForTask(latest.getId(), 0);
                return true;
            });
        }
        if (currentPage == PAGE_SCHEDULE && !settingsVisible) scheduleCardGestureRegions.add(root);
        return root;
    }

    private void openTaskEditor(String taskId) {
        showTaskDetails(taskId);
    }

    private void showCountdownDetails(String taskId) {
        TaskItem task = repository == null ? null : repository.getTask(taskId);
        if (task == null || !task.hasCountdown()) {
            showMessage("该待办没有定时");
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("提醒时间：").append(formatDateTime(task.getCountdownTargetAt()))
                .append("\n当前：")
                .append(formatCountdown(task.getCountdownTargetAt()
                        - System.currentTimeMillis()));
        if (task.isRepeatingCountdown()) {
            message.append("\n循环：每 ")
                    .append(formatCountdownDuration(task.getCountdownIntervalMillis()));
        } else {
            message.append("\n循环：不重复");
        }
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle(task.getTitle() + " · 定时")
                .setMessage(message.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showTaskReadOnlyDetails(String taskId) {
        TaskItem task = repository == null ? null : repository.getTask(taskId);
        if (task == null) {
            showMessage("任务不存在或已删除");
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("类型：").append(task.isScheduled() ? "日程" : "待办")
                .append("\n状态：").append(statusLabel(task.getStatus()));
        if (!task.getDescription().trim().isEmpty()) {
            message.append("\n\n").append(task.getDescription().trim());
        }
        if (task.isScheduled() || task.hasCountdown() || task.getDueAt() > 0
                || !com.hawson.timeassistant.ui.schedule.TaskReminderSummary.format(task, repository.getAllReminderEvents()).isEmpty()) {
            message.append("\n\n时间：").append(taskTimeSummary(task));
        }
        String statusTime = taskStatusTimeSummary(task);
        if (!statusTime.isEmpty()) message.append("\n").append(statusTime);
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle(task.getTitle())
                .setMessage(message.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    private String formatCountdownCompact(long remainingMillis) {
        if (remainingMillis <= 0) return "已到时";
        long totalSeconds = (remainingMillis + 999L) / 1_000L;
        long days = totalSeconds / 86_400L;
        if (days > 0) return days + "天";
        long hours = totalSeconds / 3_600L;
        long minutes = totalSeconds % 3_600L / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format(LOCALE, "%02d:%02d:%02d", hours, minutes, seconds)
                : String.format(LOCALE, "%02d:%02d", minutes, seconds);
    }

    private String taskTimeSummary(TaskItem task) {
        String reminders = com.hawson.timeassistant.ui.schedule.TaskReminderSummary.format(task, repository.getAllReminderEvents());
        if (task.hasCompleteTimeArrangement()) {
            return formatDateOnly(task.getScheduledStartAt()) + " · "
                    + formatTime(task.getScheduledStartAt()) + "–" + formatTime(task.getScheduledEndAt())
                    + (reminders.isEmpty() ? "" : "\n" + reminders);
        }
        if (!reminders.isEmpty()) return reminders;
        if (!task.requiresTimeArrangement() && task.getStatus() == TaskItem.Status.COMPLETED
                && task.getCompletedAt() > 0) {
            return "完成于 " + formatDateTime(task.getCompletedAt());
        }
        if (!task.requiresTimeArrangement() && task.hasCountdown()) {
            if (task.isRepeatingCountdown()) {
                return "循环提醒 · 每 "
                        + formatCountdownDuration(task.getCountdownIntervalMillis());
            }
            return task.getCountdownTargetAt() > System.currentTimeMillis()
                    ? "定时提醒 · " + formatDateTime(task.getCountdownTargetAt())
                    : "定时提醒 · 已到时";
        }
        long dateAt = taskTargetDate(task);
        StringBuilder summary = new StringBuilder();
        if (dateAt > 0) summary.append(formatDateOnly(dateAt));
        if (task.getScheduledStartAt() > 0) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append("开始 ").append(formatTime(task.getScheduledStartAt()));
        }
        if (task.getScheduledEndAt() > 0) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append("结束 ").append(formatTime(task.getScheduledEndAt()));
        }
        if (task.getDueAt() > 0 && task.getPlannedDateAt() == 0
                && task.getScheduledStartAt() == 0 && task.getScheduledEndAt() == 0) {
            summary.setLength(0);
            summary.append("截止 ").append(formatDateTime(task.getDueAt()));
        }
        if (!task.requiresTimeArrangement()) return summary.length() == 0
                ? "无需安排时间" : summary + " · 无需排期";
        return summary.length() == 0 ? "尚未确认时间" : summary + " · 待补全";
    }

    private String taskStatusTimeSummary(TaskItem task) {
        if (task == null) return "";
        if (task.getStatus() == TaskItem.Status.COMPLETED && task.getCompletedAt() > 0) {
            return "完成于 " + formatDateTime(task.getCompletedAt());
        }
        if (task.getStatus() == TaskItem.Status.IN_PROGRESS && task.getStartedAt() > 0) {
            return "开始于 " + formatDateTime(task.getStartedAt());
        }
        if (task.getStatus() == TaskItem.Status.AWAITING_CONFIRMATION
                && task.getScheduledEndAt() > 0) {
            return "结束于 " + formatDateTime(task.getScheduledEndAt()) + " · 等待确认";
        }
        return "";
    }

    private long taskTargetDate(TaskItem task) {
        if (task.getScheduledStartAt() > 0) return task.getScheduledStartAt();
        if (task.getPlannedDateAt() > 0) return task.getPlannedDateAt();
        if (task.getScheduledEndAt() > 0) return task.getScheduledEndAt();
        if (task.getDueAt() > 0) return task.getDueAt();
        return 0;
    }

    private long effectiveTaskDate(TaskItem task) {
        if (task.isScheduled()) return task.getScheduledStartAt();
        if (task.getPlannedDateAt() > 0) return task.getPlannedDateAt();
        if (task.getScheduledStartAt() > 0) return task.getScheduledStartAt();
        if (task.getScheduledEndAt() > 0) return task.getScheduledEndAt();
        if (task.getDueAt() > 0) return task.getDueAt();
        return Long.MAX_VALUE;
    }

    private TextView filterChip(String text, boolean active, View.OnClickListener listener) {
        TextView chip = label(text, 12, active ? PRIMARY : TEXT_MUTED, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER); chip.setPadding(dp(9), 0, dp(9), 0);
        chip.setBackground(active ? rounded(PRIMARY_SOFT, UiKit.RADIUS_PILL)
                : outlined(SURFACE, BORDER, UiKit.RADIUS_PILL, 1));
        chip.setOnClickListener(listener); applyPressFeedback(chip); return chip;
    }

    private ImageView iconButton(int icon, String description) {
        ImageView button = new ImageView(this);
        button.setImageResource(icon);
        button.setColorFilter(TEXT_MUTED);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(outlined(SURFACE, BORDER, UiKit.RADIUS_CONTAINER, 1));
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        applyPressFeedback(button);
        return button;
    }

    private void addScheduleEmpty(LinearLayout body, String title, String description) {
        body.addView(UiKit.emptyState(this, R.drawable.ic_task_check, title,
                "新建任务", this::showManualTaskDialog), lp(-1, -2));
    }

    private void checkTaskEndPrompt() {
        if (repository == null || taskEndPromptVisible || taskStartPromptVisible
                || !activityStarted) return;
        long now = System.currentTimeMillis();
        materializeRecentlyEndedRecurringOccurrences(now);
        transitionEndedTasksToAwaitingConfirmation(now);
        TaskItem due = null;
        for (TaskItem task : repository.getAllTasks()) {
            if (!requiresCompletionConfirmation(task, now)) continue;
            String promptKey = task.getId() + ":" + task.getScheduledEndAt();
            if (!promptedTaskEnds.contains(promptKey)
                    && (due == null || task.getScheduledEndAt() < due.getScheduledEndAt())) {
                due = task;
            }
        }
        if (due != null) showTaskEndConfirmation(due.getId(), false);
    }

    private void materializeRecentlyEndedRecurringOccurrences(long now) {
        List<TaskItem> tasks = new ArrayList<>(repository.getAllTasks());
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(now);
        for (int offset = 0; offset >= -1; offset--) {
            if (offset < 0) day.add(Calendar.DAY_OF_YEAR, -1);
            long dayAt = day.getTimeInMillis();
            for (RecurringTaskGroup group : repository.getAllRecurringGroups()) {
                long occurrenceAt = group.occurrenceStartOnDay(dayAt);
                if (occurrenceAt <= 0 || hasRecurringInstance(
                        tasks, group.getId(), occurrenceAt)) continue;
                RecurringOccurrenceOverride override =
                        repository.getRecurringOverride(group.getId(), occurrenceAt);
                if (override != null && override.isSkipped()) continue;
                long effectiveStart = override == null ? occurrenceAt
                        : override.effectiveStartAt(occurrenceAt);
                long endAt;
                try { endAt = override == null
                        ? Math.addExact(effectiveStart, group.getDurationMillis())
                        : override.effectiveEndAt(effectiveStart, group.getDurationMillis()); }
                catch (ArithmeticException overflow) { continue; }
                if (endAt > now) continue;
                TaskItem instance = materializeRecurringOccurrence(group, occurrenceAt);
                if (instance != null) tasks.add(instance);
            }
        }
        long recentBoundary = now - 2L * 86_400_000L;
        for (RecurringOccurrenceOverride override : repository.getAllRecurringOverrides()) {
            if (override.isSkipped() || hasRecurringInstance(tasks,
                    override.getRecurringGroupId(), override.getOriginalOccurrenceAt())) continue;
            RecurringTaskGroup group = repository.getRecurringGroup(
                    override.getRecurringGroupId());
            if (group == null || !group.isActive()) continue;
            DayScheduleEntry entry = DayScheduleEntry.forRecurring(group,
                    override.getOriginalOccurrenceAt(), override);
            if (entry.endAt > now || entry.endAt < recentBoundary) continue;
            TaskItem instance = materializeRecurringOccurrence(group,
                    override.getOriginalOccurrenceAt());
            if (instance != null) tasks.add(instance);
        }
    }

    private void showTaskEndConfirmation(String taskId, boolean userInitiated) {
        if (repository == null || taskId == null || taskEndPromptVisible) return;
        TaskItem task = repository.getTask(taskId);
        long now = System.currentTimeMillis();
        if (!requiresCompletionConfirmation(task, now)) return;
        String promptKey = task.getId() + ":" + task.getScheduledEndAt();
        if (!userInitiated && promptedTaskEnds.contains(promptKey)) return;
        promptedTaskEnds.add(promptKey);
        taskEndPromptVisible = true;
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("日程已结束")
                .setMessage("“" + task.getTitle() + "”是否已经完成？")
                .setNeutralButton("稍后确认", (ignored, which) -> {
                    TaskItem latest = repository.getTask(task.getId());
                    if (latest != null && requiresCompletionConfirmation(
                            latest, System.currentTimeMillis())) {
                        latest.setStatus(TaskItem.Status.AWAITING_CONFIRMATION);
                        repository.upsertTask(latest);
                        showMessage("已放入注意页的待确认事项");
                        if (currentPage == PAGE_SCHEDULE) renderCurrentPage();
                    }
                })
                .setNegativeButton("未完成", (ignored, which) -> {
                    TaskItem latest = repository.getTask(task.getId());
                    if (latest != null && requiresCompletionConfirmation(
                            latest, System.currentTimeMillis())) {
                        latest.setStatus(TaskItem.Status.NEEDS_RESCHEDULING);
                        repository.upsertTask(latest);
                        showMessage("已放入注意页的逾期事项");
                        if (currentPage == PAGE_SCHEDULE) renderCurrentPage();
                    }
                })
                .setPositiveButton("已完成", (ignored, which) -> {
                    TaskItem latest = repository.getTask(task.getId());
                    if (latest != null && requiresCompletionConfirmation(
                            latest, System.currentTimeMillis())) {
                        latest.setStatus(TaskItem.Status.COMPLETED);
                        repository.upsertTask(latest);
                        showMessage("日程已完成");
                        if (currentPage == PAGE_SCHEDULE) renderCurrentPage();
                    }
                })
                .create();
        dialog.setOnDismissListener(ignored -> taskEndPromptVisible = false);
        dialog.show();
    }

    private void checkTaskStartPrompt() {
        if (repository == null || taskStartPromptVisible || taskEndPromptVisible
                || !activityStarted) return;
        long now = System.currentTimeMillis();
        TaskItem due = null;
        for (TaskItem task : repository.getAllTasks()) {
            if (task.getStatus() != TaskItem.Status.SCHEDULED || !task.isScheduled()) continue;
            long delay = now - task.getScheduledStartAt();
            String promptKey = task.getId() + ":" + task.getScheduledStartAt();
            if (task.getScheduledEndAt() > now
                    && delay >= 0 && delay <= 5 * 60_000L
                    && !promptedTaskStarts.contains(promptKey)
                    && (due == null || task.getScheduledStartAt() < due.getScheduledStartAt())) {
                due = task;
            }
        }
        if (due == null) return;
        TaskItem prompted = due;
        promptedTaskStarts.add(prompted.getId() + ":" + prompted.getScheduledStartAt());
        taskStartPromptVisible = true;
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("任务时间到了")
                .setMessage("“" + prompted.getTitle() + "”现在开始，是否确认开始？")
                .setNegativeButton("暂不开始", null)
                .setPositiveButton("开始", (ignored, which) -> {
                    TaskItem latest = repository.getTask(prompted.getId());
                    if (latest != null && latest.getStatus() == TaskItem.Status.SCHEDULED) {
                        latest.setStatus(TaskItem.Status.IN_PROGRESS);
                        repository.upsertTask(latest);
                        showMessage("任务已开始");
                        if (currentPage == PAGE_SCHEDULE) renderCurrentPage();
                    }
                })
                .create();
        dialog.setOnDismissListener(ignored -> taskStartPromptVisible = false);
        dialog.show();
    }

    protected View buildFocusPage() {
        PageFocusBinding binding = focusPageController.inflate(getLayoutInflater());
        focusPageBinding = binding;
        focusRingAnimating = false;
        FocusState focus = repository.getCurrentFocus();
        AppSettings settings = repository.getSettings();
        FocusState.Mode displayMode = displayFocusMode(focus);
        String stats = todayFocusStats();
        binding.focusEyebrow.setText(stats);
        binding.focusEyebrow.setVisibility(View.GONE);
        binding.focusModeLabel.setText("专注");
        boolean started = hasFocusStarted(focus);
        binding.focusSettingsButton.setOnClickListener(view -> showFocusOverflowMenu(
                view, hasFocusStarted(repository.getCurrentFocus())));
        guideRegistry.register(GuideTarget.FOCUS_MODES, binding.focusModes);
        bindFocusModes(binding, displayMode);
        applyPressFeedback(binding.focusSettingsButton);

        timerRing = binding.timerRing;
        timerRing.setPalette(PRIMARY, PRIMARY_SOFT, TEXT, TEXT_MUTED);
        long remaining = focus.getState() == FocusState.State.IDLE
                ? focusMinutes(settings, displayMode) * 60_000L : focus.remainingAt(System.currentTimeMillis());
        long total = focus.getState() == FocusState.State.IDLE
                ? focusMinutes(settings, displayMode) * 60_000L : focus.getTotalMillis();
        timerRing.setTimer(focusTimerDisplayMillis(settings, focus, remaining, total),
                total, focusStateLabel(focus, displayMode));
        attachTimerRingGestures(timerRing);

        String selectedTaskId = focus.getTaskId() != null ? focus.getTaskId() : validFocusTaskId(lastFocusTaskId);
        TaskItem focusedTask = selectedTaskId == null ? null : repository.getTask(selectedTaskId);
        focusTaskLabel = binding.focusTaskTitle;
        String selectedLabel = focusedTask != null ? focusedTask.getTitle()
                : focusCustomTaskLabel.isEmpty() ? (started ? "自由专注" : "不关联任务 · 可选择 ›")
                : focusCustomTaskLabel + (started ? "" : "  ·  临时任务 ›");
        focusTaskLabel.setText(displayMode == FocusState.Mode.FOCUS
                ? selectedLabel : "休息一下，让注意力恢复");
        focusTaskLabel.setTextColor(displayMode == FocusState.Mode.FOCUS ? TEXT : TEXT_MUTED);
        focusTaskLabel.setOnClickListener(view -> {
            if (displayFocusMode(repository.getCurrentFocus()) != FocusState.Mode.FOCUS) return;
            if (hasFocusStarted(repository.getCurrentFocus())) {
                showMessage("请先取消或结束本轮，再更换任务");
            } else showFocusTaskPicker();
        });
        applyPressFeedback(focusTaskLabel);

        timerButton = binding.timerButton;
        timerButton.setText(focusButtonLabel(focus, displayMode));
        timerButton.setEnabled(true);
        timerButton.setOnClickListener(view -> toggleFocusTimer());
        binding.endFocusButton.setOnClickListener(view -> endFocusEarly());
        focusPageController.setStarted(started, false);
        focusPageController.updatePresentation(focus, activityStarted);
        timerButton.setVisibility(View.VISIBLE);
        applyPressFeedback(timerButton);
        applyPressFeedback(binding.endFocusButton);
        mainHandler.post(this::syncFocusTimerUi);
        return binding.getRoot();
    }

    private void showFocusOverflowMenu(View anchor, boolean started) {
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "专注设置");
        menu.getMenu().add(0, 3, 2, "专注统计与记录");
        if (started) menu.getMenu().add(0, 2, 1, "取消本轮");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) openFocusSettings();
            else if (item.getItemId() == 3) {
                focusSettingsCategory = FOCUS_SETTINGS_HISTORY;
                openFocusSettings();
            }
            else cancelFocusTimer();
            return true;
        });
        menu.show();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachTimerRingGestures(TimerRingView ring) {
        ViewCompat.setStateDescription(ring,
                "当前模式：" + focusModeLabel(displayFocusMode(repository.getCurrentFocus())));
        focusPageController.attachDial(new FocusDialController.Host() {
            public boolean editable() { return !hasFocusStarted(repository.getCurrentFocus()); }
            public FocusState.Mode mode() { return displayFocusMode(repository.getCurrentFocus()); }
            public int minutes() { return focusMinutes(repository.getSettings(), mode()); }
            public void preview(int minutes) {
                ring.transitionTimerTo(minutes * 60_000L, minutes * 60_000L,
                        focusStateLabel(repository.getCurrentFocus(), mode()), false);
            }
            public void restore() { syncFocusTimerUi(); }
            public void save(FocusState.Mode mode, int minutes) {
                if (editable() && mode() == mode) updateFocusDuration(mode, minutes);
            }
            public void switchMode(int direction) { switchFocusMode(direction); }
            public void details() { animateFocusDurationPicker(mode()); }
        });
    }

    private void switchFocusMode(int direction) {
        if (focusRingAnimating) return;
        if (repository.getSettings().isBreaksDisabled()) {
            selectFocusMode(FocusState.Mode.FOCUS, 0);
            return;
        }
        FocusState.Mode[] modes = {FocusState.Mode.FOCUS, FocusState.Mode.SHORT_BREAK,
                FocusState.Mode.LONG_BREAK};
        FocusState.Mode current = displayFocusMode(repository.getCurrentFocus());
        int index = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i] == current) index = i;
        selectFocusMode(modes[(index + direction + modes.length) % modes.length], direction);
    }

    private void selectFocusMode(FocusState.Mode mode, int direction) {
        focusPageController.cancelDialGesture();
        if (mode != FocusState.Mode.FOCUS && repository.getSettings().isBreaksDisabled()) {
            showMessage("休息已在专注设置中禁用");
            return;
        }
        FocusState current = repository.getCurrentFocus();
        if (hasFocusStarted(current)) {
            showMessage("请先取消或结束当前计时");
            if (focusPageBinding != null) {
                bindFocusModes(focusPageBinding, displayFocusMode(current));
            }
            return;
        }
        if (displayFocusMode(current) == mode) return;
        if (direction != 0 && timerRing != null && timerRing.isAttachedToWindow()) {
            animateFocusModeChange(mode, direction, current);
        } else {
            commitFocusMode(mode, current);
            refreshFocusModeControls(true);
        }
    }

    private void animateFocusModeChange(FocusState.Mode mode, int direction,
                                        FocusState previous) {
        // Commit independently of decorative motion, including when animations are disabled.
        commitFocusMode(mode, previous);
        refreshFocusModeControls(true);
    }

    private void bindFocusModes(PageFocusBinding binding, FocusState.Mode mode) {
        binding.focusModes.setOnCheckedChangeListener(null);
        binding.focusModes.check(mode == FocusState.Mode.SHORT_BREAK
                ? R.id.focusModeShort : mode == FocusState.Mode.LONG_BREAK
                ? R.id.focusModeLong : R.id.focusModeFocus);
        for (int index = 0; index < binding.focusModes.getChildCount(); index++) {
            android.widget.RadioButton option = (android.widget.RadioButton) binding.focusModes.getChildAt(index);
            option.setTextColor(option.isChecked() ? PRIMARY : TEXT_MUTED);
            option.setBackground(option.isChecked() ? rounded(SURFACE, UiKit.RADIUS_PILL) : null);
            option.setSelected(option.isChecked());
            applyPressFeedback(option);
        }
        boolean breaksEnabled = !repository.getSettings().isBreaksDisabled();
        binding.focusModeShort.setEnabled(breaksEnabled);
        binding.focusModeLong.setEnabled(breaksEnabled);
        binding.focusModes.setOnCheckedChangeListener((group, checkedId) ->
                selectFocusMode(checkedId == R.id.focusModeShort ? FocusState.Mode.SHORT_BREAK
                        : checkedId == R.id.focusModeLong ? FocusState.Mode.LONG_BREAK
                        : FocusState.Mode.FOCUS, 1));
    }

    private void commitFocusMode(FocusState.Mode mode, FocusState current) {
        if (current.getMode() == FocusState.Mode.FOCUS && current.getTaskId() != null) {
            rememberFocusTask(current.getTaskId());
        }
        stopCountDown();
        idleFocusMode = mode;
        uiPreferences.edit().putInt("focus_idle_mode", mode.ordinal()).apply();
        suppressFocusRepositoryRender = true;
        try {
            repository.clearCurrentFocus();
        } finally {
            suppressFocusRepositoryRender = false;
        }
    }

    private void refreshFocusModeControls() {
        refreshFocusModeControls(false);
    }

    private void refreshFocusModeControls(boolean animateTime) {
        PageFocusBinding binding = focusPageBinding;
        if (binding == null || !binding.getRoot().isAttachedToWindow()) {
            renderCurrentPage();
            return;
        }
        FocusState focus = repository.getCurrentFocus();
        AppSettings settings = repository.getSettings();
        FocusState.Mode displayMode = displayFocusMode(focus);
        bindFocusModes(binding, displayMode);
        long remaining = focus.getState() == FocusState.State.IDLE
                ? focusMinutes(settings, displayMode) * 60_000L
                : focus.remainingAt(System.currentTimeMillis());
        long total = focus.getState() == FocusState.State.IDLE
                ? focusMinutes(settings, displayMode) * 60_000L : focus.getTotalMillis();
        timerRing = binding.timerRing;
        timerRing.setPalette(PRIMARY, PRIMARY_SOFT, TEXT, TEXT_MUTED);
        if (!focusPageController.isDurationPreviewing()) {
            long display = focusTimerDisplayMillis(settings, focus, remaining, total);
            if (animateTime) timerRing.transitionTimerTo(display, total, focusStateLabel(focus, displayMode), true);
            else timerRing.setTimer(display, total, focusStateLabel(focus, displayMode));
        }
        ViewCompat.setStateDescription(timerRing,
                "当前模式：" + focusModeLabel(displayMode));

        String selectedTaskId = focus.getTaskId() != null
                ? focus.getTaskId() : validFocusTaskId(lastFocusTaskId);
        TaskItem focusedTask = selectedTaskId == null ? null : repository.getTask(selectedTaskId);
        boolean started = hasFocusStarted(focus);
        String selectedLabel = focusedTask != null ? focusedTask.getTitle()
                : focusCustomTaskLabel.isEmpty() ? (started ? "自由专注" : "不关联任务 · 可选择 ›")
                : focusCustomTaskLabel + (started ? "" : "  ·  临时任务 ›");
        focusTaskLabel = binding.focusTaskTitle;
        focusTaskLabel.setText(displayMode == FocusState.Mode.FOCUS
                ? selectedLabel : "休息一下，让注意力恢复");
        focusTaskLabel.setTextColor(displayMode == FocusState.Mode.FOCUS ? TEXT : TEXT_MUTED);
        timerButton = binding.timerButton;
        timerButton.setText(focusButtonLabel(focus, displayMode));
        focusPageController.setStarted(started, activityStarted);
        focusPageController.updatePresentation(focus, activityStarted);
        timerButton.setVisibility(View.VISIBLE);
    }

    private boolean hasFocusStarted(FocusState state) {
        if (state == null || state.getState() == FocusState.State.IDLE
                || state.getState() == FocusState.State.READY) return false;
        return state.getState() == FocusState.State.RUNNING
                || state.getState() == FocusState.State.FINISHED
                || state.getRemainingMillis() < state.getTotalMillis()
                || state.getInterruptionCount() > 0;
    }

    private void animateFocusDurationPicker(FocusState.Mode mode) {
        FocusState current = repository.getCurrentFocus();
        if (hasFocusStarted(current)) {
            showMessage("请先取消或结束当前计时，再调整时间");
            return;
        }
        TimerRingView ring = timerRing;
        if (ring == null || !ring.isAttachedToWindow()) {
            showFocusDurationPicker(mode);
            return;
        }
        showFocusDurationPicker(mode);
    }

    private void showFocusDurationPicker(FocusState.Mode mode) {
        FocusState current = repository.getCurrentFocus();
        if (hasFocusStarted(current)) {
            showMessage("请先取消或结束当前计时，再调整时间");
            return;
        }
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(24));
        content.addView(UiKit.title(this, focusModeLabel(mode) + "时长"));
        EditText minutes = edit("分钟", String.valueOf(focusMinutes(repository.getSettings(), mode)), false);
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutes.setSelectAllOnFocus(true);
        minutes.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (minutes.getError() != null) minutes.setError(null);
            }
            public void afterTextChanged(Editable value) { }
        });
        LinearLayout presets = row();
        int[] values = mode == FocusState.Mode.FOCUS ? new int[]{15, 25, 45, 60} : new int[]{5, 10, 15, 20};
        for (int value : values) {
            Button preset = button(String.valueOf(value), PRIMARY, PRIMARY_SOFT);
            preset.setContentDescription(value + "分钟");
            preset.setOnClickListener(view -> minutes.setText(String.valueOf(value)));
            presets.addView(preset, new LinearLayout.LayoutParams(0, -2, 1));
        }
        content.addView(presets);
        content.addView(minutes, verticalMargins(lp(-1, -2), dp(16), dp(16)));
        LinearLayout actions = row();
        Button cancel = button("取消", TEXT_MUTED, NEUTRAL);
        Button save = button("保存", ON_BRAND, PRIMARY);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(save, marginLeft(new LinearLayout.LayoutParams(0, -2, 1), dp(8)));
        content.addView(actions);
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            int value;
            try { value = Integer.parseInt(minutes.getText().toString().trim()); }
            catch (NumberFormatException exception) { value = 0; }
            int maximum = mode == FocusState.Mode.FOCUS ? 180 : 60;
            if (value < 1 || value > maximum) {
                minutes.setError("请输入1–" + maximum + "分钟");
                minutes.requestFocus();
                return;
            }
            if (hasFocusStarted(repository.getCurrentFocus())) {
                dialog.dismiss();
                return;
            }
            if (displayFocusMode(repository.getCurrentFocus()) != mode) {
                dialog.dismiss();
                showMessage("模式已变化，请重新设置时长");
                return;
            }
            save.setEnabled(false);
            updateFocusDuration(mode, value);
            MotionSpec.feedback(save);
            dialog.dismiss();
        });
        UiKit.polish(content);
        ScrollView sheetScroll = new ScrollView(this);
        sheetScroll.setFillViewport(true);
        sheetScroll.addView(content);
        dialog.setContentView(sheetScroll);
        dialog.show();
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setShouldRemoveExpandedCorners(false);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    private void updateFocusDuration(FocusState.Mode mode, int minutes) {
        AppSettings settings = repository.getSettings();
        int focusMinutes = mode == FocusState.Mode.FOCUS ? minutes : settings.getFocusDurationMinutes();
        int shortMinutes = mode == FocusState.Mode.SHORT_BREAK ? minutes : settings.getShortBreakDurationMinutes();
        int longMinutes = mode == FocusState.Mode.LONG_BREAK ? minutes : settings.getLongBreakDurationMinutes();
        settings.setFocusDurations(focusMinutes, shortMinutes, longMinutes);
        suppressFocusRepositoryRender = true;
        try {
            repository.saveSettings(settings);
            FocusState current = repository.getCurrentFocus();
            if (current.getState() != FocusState.State.IDLE && current.getMode() == mode) {
                repository.setCurrentFocus(FocusState.ready(current.getTaskId(), mode,
                        minutes * 60_000L, current.getPomodoroIndex(),
                        current.getSessionLabel()));
            }
        } finally {
            suppressFocusRepositoryRender = false;
        }
        if (currentPage == PAGE_FOCUS && timerRing != null
                && timerRing.isAttachedToWindow()) {
            FocusState updated = repository.getCurrentFocus();
            long total = minutes * 60_000L;
            long remaining = updated.getState() == FocusState.State.IDLE
                    ? total : updated.remainingAt(System.currentTimeMillis());
            timerRing.transitionTimerTo(focusTimerDisplayMillis(settings, updated,
                            remaining, total), total, focusStateLabel(updated, mode), false);
        } else {
            renderCurrentPage();
        }
    }

    private FocusState.Mode displayFocusMode(FocusState state) {
        if (state == null || state.getState() == FocusState.State.IDLE) {
            return repository != null && repository.getSettings().isBreaksDisabled()
                    ? FocusState.Mode.FOCUS : idleFocusMode;
        }
        return state.getMode();
    }

    private FocusState.Mode focusModeFromOrdinal(int ordinal) {
        FocusState.Mode[] values = FocusState.Mode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : FocusState.Mode.FOCUS;
    }

    private int focusMinutes(AppSettings settings, FocusState.Mode mode) {
        if (mode == FocusState.Mode.SHORT_BREAK) return settings.getShortBreakDurationMinutes();
        if (mode == FocusState.Mode.LONG_BREAK) return settings.getLongBreakDurationMinutes();
        return settings.getFocusDurationMinutes();
    }

    private String focusModeLabel(FocusState.Mode mode) {
        if (mode == FocusState.Mode.SHORT_BREAK) return "短休";
        if (mode == FocusState.Mode.LONG_BREAK) return "长休";
        return "专注";
    }

    private void rememberFocusTask(String taskId) {
        lastFocusTaskId = taskId;
        uiPreferences.edit().putString("last_focus_task_id", taskId).apply();
    }

    private String validFocusTaskId(String taskId) {
        TaskItem task = taskId == null ? null : repository.getTask(taskId);
        return task == null || task.getStatus().isTerminal() ? null : task.getId();
    }

    protected void startFocusForTask(String taskId, int requestedMinutes) {
        TaskItem task = repository.getTask(taskId);
        if (task == null || task.getStatus().isTerminal()) {
            showMessage("该任务当前不能开始专注"); return;
        }
        int minutes = requestedMinutes > 0 ? requestedMinutes : repository.getSettings().getFocusDurationMinutes();
        stopCountDown();
        idleFocusMode = FocusState.Mode.FOCUS;
        rememberFocusTask(taskId);
        focusCustomTaskLabel = "";
        uiPreferences.edit().putString("focus_custom_task_label", "").apply();
        uiPreferences.edit().putInt("focus_idle_mode", FocusState.Mode.FOCUS.ordinal()).apply();
        repository.setCurrentFocus(FocusState.ready(taskId, FocusState.Mode.FOCUS,
                minutes * 60_000L, nextPomodoroIndex()));
        showPage(PAGE_FOCUS);
    }

    private int nextPomodoroIndex() {
        int count = 0;
        long now = System.currentTimeMillis();
        for (FocusRecord record : repository.getFocusHistory()) {
            if (sameDay(record.getCompletedAt(), now) && "completed".equals(record.getOutcome())) count++;
        }
        return count + 1;
    }

    private void showFocusTaskPicker() {
        List<TaskItem> candidates = new ArrayList<>();
        for (TaskItem task : repository.getTodayTasks(System.currentTimeMillis(), TimeZone.getDefault())) {
            if (!task.getStatus().isTerminal()) candidates.add(task);
        }
        candidates.sort((first, second) -> {
            int firstRank = first.getStatus() == TaskItem.Status.IN_PROGRESS ? 0 : 1;
            int secondRank = second.getStatus() == TaskItem.Status.IN_PROGRESS ? 0 : 1;
            if (firstRank != secondRank) return Integer.compare(firstRank, secondRank);
            int time = Long.compare(effectiveTaskDate(first), effectiveTaskDate(second));
            return time != 0 ? time : first.getTitle().compareToIgnoreCase(second.getTitle());
        });

        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(12));
        final int SELECT_NONE = 0;
        final int SELECT_TASK = 1;
        final int SELECT_CUSTOM = 2;
        final int[] selectedKind = {SELECT_NONE};
        final String[] selectedTaskId = {null};
        TextView noTask = focusSelectionRow("不关联任务", "直接开始专注", true);
        content.addView(noTask, lp(-1, -2));
        content.addView(focusPickerDivider("今日任务"), verticalMargins(lp(-1, dp(28)), dp(16), dp(10)));

        List<TextView> taskRows = new ArrayList<>();
        List<TaskItem> rowTasks = new ArrayList<>();
        EditText search = edit("搜索今日任务", "", false);
        search.setSingleLine(true);
        content.addView(search, marginBottom(lp(-1, -2), dp(12)));
        for (TaskItem task : candidates) {
            String meta = focusTaskMeta(task);
            if (task.getStatus() == TaskItem.Status.IN_PROGRESS) meta += "（当前）";
            TextView item = focusSelectionRow(task.getTitle(), meta, false);
            taskRows.add(item);
            rowTasks.add(task);
            content.addView(item, marginBottom(lp(-1, -2), dp(8)));
        }
        if (candidates.isEmpty()) {
            TextView empty = label("今天暂无可选任务", 12, TEXT_MUTED, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            content.addView(empty, lp(-1, dp(42)));
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                String query = value.toString().trim().toLowerCase(LOCALE);
                for (int index = 0; index < taskRows.size(); index++) {
                    taskRows.get(index).setVisibility(rowTasks.get(index).getTitle().toLowerCase(LOCALE)
                            .contains(query) ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable value) { }
        });

        content.addView(focusPickerDivider("自定义任务"), verticalMargins(lp(-1, dp(28)), dp(8), dp(10)));
        EditText custom = edit("输入自定义任务名称", "", false);
        custom.setSingleLine(true);
        custom.setPadding(dp(16), 0, dp(16), 0);
        content.addView(custom, lp(-1, dp(52)));
        // Keep the requested default on "no task" until the user explicitly taps the input.
        content.setFocusableInTouchMode(true);
        content.requestFocus();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("选择专注任务")
                .setView(scroll)
                .setPositiveButton("确认", null)
                .create();
        Runnable[] refreshSelection = {null};
        refreshSelection[0] = () -> {
            styleFocusSelectionRow(noTask, selectedKind[0] == SELECT_NONE);
            for (int index = 0; index < taskRows.size(); index++) {
                styleFocusSelectionRow(taskRows.get(index), selectedKind[0] == SELECT_TASK
                        && rowTasks.get(index).getId().equals(selectedTaskId[0]));
            }
            boolean customSelected = selectedKind[0] == SELECT_CUSTOM;
            custom.setTextColor(customSelected ? PRIMARY : TEXT);
            custom.setBackground(customSelected ? outlined(PRIMARY_SOFT, PRIMARY, UiKit.RADIUS_CONTAINER, 1)
                    : outlined(SURFACE, BORDER, UiKit.RADIUS_CONTAINER, 1));
        };
        Runnable confirmSelection = () -> {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (confirm != null) confirm.performClick();
        };
        bindFocusPickerOption(noTask, () -> {
            selectedKind[0] = SELECT_NONE;
            selectedTaskId[0] = null;
            refreshSelection[0].run();
        }, confirmSelection);
        for (int index = 0; index < taskRows.size(); index++) {
            TaskItem option = rowTasks.get(index);
            bindFocusPickerOption(taskRows.get(index), () -> {
                selectedKind[0] = SELECT_TASK;
                selectedTaskId[0] = option.getId();
                refreshSelection[0].run();
            }, confirmSelection);
        }
        custom.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                selectedKind[0] = SELECT_CUSTOM;
                selectedTaskId[0] = null;
                refreshSelection[0].run();
            }
        });
        custom.setOnClickListener(view -> {
            selectedKind[0] = SELECT_CUSTOM;
            selectedTaskId[0] = null;
            refreshSelection[0].run();
        });
        custom.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (custom.hasFocus() && selectedKind[0] != SELECT_CUSTOM) {
                    selectedKind[0] = SELECT_CUSTOM;
                    selectedTaskId[0] = null;
                    refreshSelection[0].run();
                }
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String customValue = custom.getText().toString().trim();
                    if (selectedKind[0] == SELECT_CUSTOM && customValue.isEmpty()) {
                        custom.setError("请输入自定义任务名称");
                        custom.requestFocus();
                        return;
                    }
                    String taskId = selectedKind[0] == SELECT_TASK ? selectedTaskId[0] : null;
                    String label = selectedKind[0] == SELECT_CUSTOM ? customValue : "";
                    dialog.dismiss();
                    selectFocusTask(taskId, label);
                }));
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        refreshSelection[0].run();
    }

    private TextView focusSelectionRow(String title, String subtitle, boolean selected) {
        String value = subtitle == null || subtitle.isEmpty() ? title : title + "\n" + subtitle;
        TextView row = label(value, 14, selected ? PRIMARY : TEXT, Typeface.BOLD);
        row.setLineSpacing(dp(4), 1f);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(selected ? outlined(PRIMARY_SOFT, PRIMARY, UiKit.RADIUS_CONTAINER, 1)
                : outlined(SURFACE, BORDER, UiKit.RADIUS_CONTAINER, 1));
        applyPressFeedback(row);
        return row;
    }

    private void styleFocusSelectionRow(TextView row, boolean selected) {
        row.setTextColor(selected ? PRIMARY : TEXT);
        row.setBackground(selected ? outlined(PRIMARY_SOFT, PRIMARY, UiKit.RADIUS_CONTAINER, 1)
                : outlined(SURFACE, BORDER, UiKit.RADIUS_CONTAINER, 1));
    }

    private View focusPickerDivider(String text) {
        LinearLayout divider = row();
        divider.setGravity(Gravity.CENTER_VERTICAL);
        View before = new View(this);
        before.setBackgroundColor(BORDER);
        View after = new View(this);
        after.setBackgroundColor(BORDER);
        TextView title = label(text, 12, TEXT_MUTED, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        divider.addView(before, new LinearLayout.LayoutParams(0, dp(1), 1));
        divider.addView(title, lp(dp(92), -2));
        divider.addView(after, new LinearLayout.LayoutParams(0, dp(1), 1));
        return divider;
    }

    private void bindFocusPickerOption(View option, Runnable select, Runnable confirm) {
        final long[] lastTapAt = {0};
        option.setOnClickListener(view -> {
            long now = System.currentTimeMillis();
            boolean doubleTap = now - lastTapAt[0] <= 300L;
            lastTapAt[0] = doubleTap ? 0 : now;
            select.run();
            if (doubleTap) confirm.run();
        });
    }

    private String focusTaskMeta(TaskItem task) {
        if (task.isScheduled()) {
            return formatTime(task.getScheduledStartAt()) + "–" + formatTime(task.getScheduledEndAt());
        }
        return task.getDueAt() > 0 ? "今天截止 · 时间未定" : "时间未定";
    }

    private void selectFocusTask(String taskId, String customLabel) {
        FocusState current = repository.getCurrentFocus();
        if (hasFocusStarted(current)) return;
        String validTaskId = validFocusTaskId(taskId);
        lastFocusTaskId = validTaskId;
        focusCustomTaskLabel = validTaskId == null ? valueOrEmpty(customLabel).trim() : "";
        SharedPreferences.Editor editor = uiPreferences.edit()
                .putString("focus_custom_task_label", focusCustomTaskLabel);
        if (validTaskId == null) editor.remove("last_focus_task_id");
        else editor.putString("last_focus_task_id", validTaskId);
        editor.apply();
        if (current.getState() != FocusState.State.IDLE) {
            stopCountDown();
            repository.setCurrentFocus(FocusState.ready(validTaskId, current.getMode(),
                    current.getTotalMillis(), current.getPomodoroIndex(),
                    focusCustomTaskLabel));
        }
        renderCurrentPage();
    }

    private void toggleFocusTimer() {
        FocusState current = repository.getCurrentFocus();
        long now = System.currentTimeMillis();
        if (current.getState() == FocusState.State.IDLE) {
            int minutes = focusMinutes(repository.getSettings(), idleFocusMode);
            String taskId = idleFocusMode == FocusState.Mode.FOCUS
                    ? validFocusTaskId(lastFocusTaskId) : null;
            repository.setCurrentFocus(FocusState.ready(taskId, idleFocusMode,
                    minutes * 60_000L,
                    idleFocusMode == FocusState.Mode.FOCUS ? nextPomodoroIndex() : 0,
                    idleFocusMode == FocusState.Mode.FOCUS ? focusCustomTaskLabel : ""));
            toggleFocusTimer();
            return;
        }
        if (current.getState() == FocusState.State.RUNNING) {
            stopCountDown();
            FocusState paused = current.pause(now);
            repository.setCurrentFocus(paused);
            if (paused.getState() == FocusState.State.FINISHED) {
                finishFocusSession(paused);
            } else {
                renderCurrentPage();
            }
            return;
        }
        if (current.getState() == FocusState.State.FINISHED || current.remainingAt(now) == 0) {
            finishFocusSession(current.finish(now)); return;
        }
        FocusState running = current.startOrResume(now);
        requestNotificationPermissionIfNeeded();
        repository.setCurrentFocus(running);
        if (running.getMode() == FocusState.Mode.FOCUS && running.getTaskId() != null) {
            TaskItem task = repository.getTask(running.getTaskId());
            if (task != null && !task.getStatus().isTerminal()) {
                task.setStatus(TaskItem.Status.IN_PROGRESS); repository.upsertTask(task);
            }
        }
        renderCurrentPage();
    }

    private void startCountDown(FocusState state) {
        if (state.getState() != FocusState.State.RUNNING) { stopCountDown(); return; }
        syncAmbientSound(state);
        if (countDownTimer != null && countDownState != null
                && countDownState.getSessionId().equals(state.getSessionId())
                && countDownState.getMode() == state.getMode()
                && countDownState.getTotalMillis() == state.getTotalMillis()
                && countDownState.getLastStartedAt() + countDownState.getRemainingMillis()
                == state.getLastStartedAt() + state.getRemainingMillis()) return;
        stopCountDown();
        long remaining = state.remainingAt(System.currentTimeMillis());
        if (remaining <= 0) { finishFocusSession(state.finish(System.currentTimeMillis())); return; }
        syncAmbientSound(state);
        countDownState = state;
        countDownTimer = new CountDownTimer(remaining, 250) {
            @Override public void onTick(long millisUntilFinished) {
                if (timerRing != null) {
                    timerRing.animateTimerTo(focusTimerDisplayMillis(repository.getSettings(), state,
                                    millisUntilFinished, state.getTotalMillis()),
                            state.getTotalMillis(),
                            state.getMode() == FocusState.Mode.FOCUS ? "正在专注" : "正在休息", MotionSpec.SETTLE);
                }
            }
            @Override public void onFinish() {
                countDownTimer = null;
                finishFocusSession(repository.getCurrentFocus());
            }
        }.start();
    }

    private void stopCountDown() {
        if (countDownTimer != null) countDownTimer.cancel();
        countDownTimer = null;
        countDownState = null;
        ambientSoundPlayer.stop();
    }

    private void syncAmbientSound(FocusState state) {
        if (state != null
                && state.getState() == FocusState.State.RUNNING
                && state.getMode() == FocusState.Mode.FOCUS
                && repository != null
                && repository.getSettings().isAmbientSoundEnabled()) {
            ambientSoundPlayer.start();
        } else {
            ambientSoundPlayer.stop();
        }
    }

    private void syncFocusTimerUi() {
        if (repository == null || timerRing == null) return;
        FocusState state = repository.getCurrentFocus();
        focusPageController.updatePresentation(state, activityStarted && currentPage == PAGE_FOCUS && !settingsVisible);
        if (focusPageController.isDurationPreviewing()) return;
        FocusState.Mode displayMode = displayFocusMode(state);
        long remaining = state.remainingAt(System.currentTimeMillis());
        long total = state.getState() != FocusState.State.IDLE && state.getTotalMillis() > 0 ? state.getTotalMillis()
                : focusMinutes(repository.getSettings(), displayMode) * 60_000L;
        long safeRemaining = state.getState() == FocusState.State.FINISHED
                ? 0 : remaining > 0 ? remaining : total;
        timerRing.setTimer(focusTimerDisplayMillis(repository.getSettings(), state,
                safeRemaining, total), total, focusStateLabel(state, displayMode));
        if (timerButton != null) timerButton.setText(focusButtonLabel(state, displayMode));
        if (state.getState() == FocusState.State.RUNNING) startCountDown(state);
        else stopCountDown();
    }

    private String focusStateLabel(FocusState state, FocusState.Mode displayMode) {
        if (state.getState() == FocusState.State.RUNNING) return state.getMode() == FocusState.Mode.FOCUS ? "正在专注" : "正在休息";
        if (state.getState() == FocusState.State.READY) return "准备开始";
        if (state.getState() == FocusState.State.PAUSED) return "计时已暂停";
        if (state.getState() == FocusState.State.FINISHED) return "本轮完成";
        if (displayMode == FocusState.Mode.SHORT_BREAK) return "准备短休";
        if (displayMode == FocusState.Mode.LONG_BREAK) return "准备长休";
        return "准备专注";
    }

    private long focusTimerDisplayMillis(AppSettings settings, FocusState state,
                                         long remaining, long total) {
        if (state == null || state.getState() == FocusState.State.IDLE
                || state.getState() == FocusState.State.READY) return Math.max(0, remaining);
        if (settings == null || !settings.isFocusCountUpEnabled()) {
            return Math.max(0, remaining);
        }
        if (state == null || state.getState() == FocusState.State.IDLE) return 0;
        return Math.max(0, Math.min(total, total - Math.max(0, remaining)));
    }

    private String focusButtonLabel(FocusState state, FocusState.Mode displayMode) {
        if (state.getState() == FocusState.State.RUNNING) return "暂停";
        if (state.getState() == FocusState.State.PAUSED) return "继续";
        if (state.getState() == FocusState.State.FINISHED) return "完成反馈";
        return "开始";
    }

    private void cancelFocusTimer() {
        FocusState current = repository.getCurrentFocus();
        if (!hasFocusStarted(current)) return;
        if (current.getState() == FocusState.State.FINISHED) {
            resumePendingFocusCompletion();
            return;
        }
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("取消本轮并重新计时？")
                .setMessage("本轮不会计入番茄钟记录，时间会恢复到开始前。")
                .setNegativeButton("继续专注", null)
                .setPositiveButton("确认取消", (dialog, which) -> {
                    if (repository.cancelCurrentFocus(current.getSessionId(),
                            System.currentTimeMillis())) {
                        stopCountDown();
                        renderCurrentPage();
                        return;
                    }
                    FocusState latest = repository.getCurrentFocus();
                    if (!latest.getSessionId().equals(current.getSessionId())) {
                        showMessage("当前会话已改变，未取消新会话");
                        renderCurrentPage();
                        return;
                    }
                    if (latest.getState() == FocusState.State.FINISHED
                            || latest.remainingAt(System.currentTimeMillis()) == 0) {
                        finishFocusSession(latest);
                        return;
                    }
                    renderCurrentPage();
                })
                .show();
    }

    private void endFocusEarly() {
        FocusState current = repository.getCurrentFocus();
        if (!hasFocusStarted(current)) return;
        if (current.getState() == FocusState.State.FINISHED) {
            resumePendingFocusCompletion();
            return;
        }
        if (current.getMode() != FocusState.Mode.FOCUS) {
            new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("结束休息？")
                    .setMessage("休息记录不会保存。")
                    .setNegativeButton("继续休息", null)
                    .setPositiveButton("确认结束", (dialog, which) -> {
                        stopCountDown();
                        handleNaturalFocusCompletion(repository.endCurrentFocus(
                                current.getSessionId(), System.currentTimeMillis(), false, ""));
                        renderCurrentPage();
                    }).show();
            return;
        }
        LinearLayout content = column(); content.setPadding(dp(20), dp(6), dp(20), dp(8));
        content.addView(label("请选择结束类型", 13, TEXT_MUTED, Typeface.NORMAL));
        TextView early = focusSelectionRow("提前结束", "保存已经专注的时间", true);
        TextView interrupted = focusSelectionRow("任务中断", "可补充本次中断原因", false);
        EditText reason = edit("中断原因（可选）", "", false);
        reason.setVisibility(View.GONE);
        content.addView(early, marginTop(lp(-1, -2), dp(12)));
        content.addView(interrupted, marginTop(lp(-1, -2), dp(8)));
        content.addView(reason, marginTop(lp(-1, dp(50)), dp(8)));
        boolean[] isInterrupted = {false};
        Runnable refresh = () -> {
            early.setBackground(isInterrupted[0] ? outlined(SURFACE, BORDER, UiKit.RADIUS_CONTAINER, 1)
                    : outlined(PRIMARY_SOFT, PRIMARY, UiKit.RADIUS_CONTAINER, 1));
            interrupted.setBackground(isInterrupted[0] ? outlined(PRIMARY_SOFT, PRIMARY, UiKit.RADIUS_CONTAINER, 1)
                    : outlined(SURFACE, BORDER, UiKit.RADIUS_CONTAINER, 1));
            reason.setVisibility(isInterrupted[0] ? View.VISIBLE : View.GONE);
        };
        early.setOnClickListener(v -> { isInterrupted[0] = false; refresh.run(); });
        interrupted.setOnClickListener(v -> { isInterrupted[0] = true; refresh.run(); });
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("结束本轮专注？")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认结束", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    long now = System.currentTimeMillis();
                    stopCountDown();
                    AppRepository.FocusCompletion completion = repository.endCurrentFocus(
                            current.getSessionId(), now, isInterrupted[0],
                            isInterrupted[0] ? reason.getText().toString().trim() : "");
                    if (completion.getKind() == AppRepository.FocusCompletionKind.FOCUS_ENDED) {
                        if (isInterrupted[0] && completion.wasSettledNow()
                                && behaviorEventRecorder != null) {
                            behaviorEventRecorder.recordAction(BehaviorEventEntity.FOCUS_INTERRUPTED,
                                    "focus_interrupted");
                        }
                    } else {
                        handleNaturalFocusCompletion(completion);
                    }
                    dialog.dismiss();
                    renderCurrentPage();
                }));
        dialog.show();
    }

    private void finishFocusSession(FocusState state) {
        stopCountDown();
        if (focusCompletionUseCase == null) return;
        AppRepository.FocusCompletion completion =
                focusCompletionUseCase.completeIfDue(System.currentTimeMillis());
        if (completion.wasSettledNow() && completion.getCompletedState() != null) {
            FocusStageSignal.play(this, repository.getSettings(), completion.getCompletedState());
        }
        handleNaturalFocusCompletion(completion);
    }

    private void resumePendingFocusCompletion() {
        if (!activityStarted || repository == null || focusCompletionUseCase == null
                || focusCompletionDialogVisible) return;
        FocusState state = repository.getCurrentFocus();
        if (state.getState() != FocusState.State.FINISHED) return;
        AppRepository.FocusCompletion completion =
                focusCompletionUseCase.completeIfDue(System.currentTimeMillis());
        handleNaturalFocusCompletion(completion);
    }

    private void handleNaturalFocusCompletion(AppRepository.FocusCompletion completion) {
        if (completion == null) return;
        FocusState completed = completion.getCompletedState();
        if (completion.isAwaitingFeedback()
                || completion.getKind() == AppRepository.FocusCompletionKind.ALREADY_SETTLED) {
            if (completed == null) completed = repository.getCurrentFocus();
            if (completed.getMode() != FocusState.Mode.FOCUS || focusCompletionDialogVisible) return;
            TaskItem task = completed.getTaskId() == null
                    ? null : repository.getTask(completed.getTaskId());
            showFocusCompletionDialog(task, completed, completed.getTotalMillis(),
                    completed.getUpdatedAt());
            return;
        }
        if (completion.getKind() == AppRepository.FocusCompletionKind.BREAK_IDLE) {
            idleFocusMode = FocusState.Mode.FOCUS;
            uiPreferences.edit().putInt("focus_idle_mode", FocusState.Mode.FOCUS.ordinal()).apply();
            showMessage("休息结束，准备好后开始下一轮专注");
            renderCurrentPage();
        } else if (completion.getKind() == AppRepository.FocusCompletionKind.BREAK_READY
                || completion.getKind() == AppRepository.FocusCompletionKind.BREAK_RUNNING) {
            idleFocusMode = FocusState.Mode.FOCUS;
            uiPreferences.edit().putInt("focus_idle_mode", FocusState.Mode.FOCUS.ordinal()).apply();
            showMessage(completion.getKind() == AppRepository.FocusCompletionKind.BREAK_RUNNING
                    ? "休息结束，下一轮番茄已开始" : "休息结束，已进入下一轮番茄");
            if (completion.getKind() == AppRepository.FocusCompletionKind.BREAK_RUNNING) {
                startCountDown(repository.getCurrentFocus());
            }
            renderCurrentPage();
        }
    }

    private void showFocusCompletionDialog(TaskItem task, FocusState state, long elapsed, long completedAt) {
        if (focusCompletionDialogVisible) return;
        focusCompletionDialogVisible = true;
        LinearLayout content = column(); content.setPadding(dp(20), dp(8), dp(20), dp(8));
        content.addView(label("本次专注已完成", 17, TEXT, Typeface.BOLD));
        content.addView(label("专注时长：" + formatDuration(FocusRecord.durationMinutesRounded(elapsed))
                        + "\n中断次数：" + state.getInterruptionCount() + " 次", 14, TEXT_MUTED, Typeface.NORMAL),
                marginTop(lp(-1, -2), dp(9)));
        Button completed = button("任务已完成", TEXT, Color.TRANSPARENT);
        boolean breaksDisabled = repository.getSettings().isBreaksDisabled();
        Button another = button(breaksDisabled ? "开始下一轮番茄" : "开始休息",
                Color.WHITE, PRIMARY);
        Button later = button("稍后休息", TEXT, Color.TRANSPARENT);
        Button postpone = button("标记为延期", WARNING, WARNING_SOFT);
        content.addView(another, marginTop(lp(-1, -2), dp(16)));
        content.addView(later, marginTop(lp(-1, -2), dp(8)));
        if (task != null) {
            Button more = button("任务操作", TEXT_MUTED, Color.TRANSPARENT);
            content.addView(more, lp(-1, -2));
            more.setOnClickListener(view -> new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle(task.getTitle())
                    .setItems(new String[]{"任务已完成", "标记为延期"}, (d, w) -> {
                        if (w == 0) completed.performClick(); else postpone.performClick();
                    }).show());
        }
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setView(content).create();
        completed.setOnClickListener(v -> {
            TaskItem latest = task == null ? null : repository.getTask(task.getId());
            if (latest != null) {
                latest.setStatus(TaskItem.Status.COMPLETED);
                repository.upsertTask(latest);
            }
            repository.clearCurrentFocus();
            dialog.dismiss();
            advanceAfterFocusUsingSettings(state);
        });
        another.setOnClickListener(v -> {
            if (breaksDisabled) prepareNextPomodoro(state.getPomodoroIndex(), true);
            else prepareBreakAfterFocus(state.getPomodoroIndex(), true);
            dialog.dismiss();
        });
        later.setOnClickListener(v -> {
            repository.clearCurrentFocus();
            setIdleModeAfterFocus(state.getPomodoroIndex());
            dialog.dismiss();
            renderCurrentPage();
        });
        postpone.setOnClickListener(v -> {
            if (task == null) return;
            repository.clearCurrentFocus();
            TaskItem changed = repository.getTask(task.getId());
            if (changed != null) {
                changed.setRequiresTimeArrangement(true).clearSchedule()
                        .setStatus(TaskItem.Status.NEEDS_RESCHEDULING);
                repository.upsertTask(changed);
            }
            dialog.dismiss(); showPage(PAGE_SCHEDULE);
        });
        dialog.setOnDismissListener(ignored -> focusCompletionDialogVisible = false);
        dialog.setCancelable(false);
        dialog.show();
    }

    private String focusRecordTitle(TaskItem task) {
        if (task != null) return task.getTitle();
        return focusCustomTaskLabel.isEmpty() ? "无任务专注" : focusCustomTaskLabel;
    }

    private FocusState.Mode breakModeAfterFocus(int pomodoroIndex) {
        if (repository.getSettings().isBreaksDisabled()) return FocusState.Mode.FOCUS;
        return repository.getSettings().isLastFocusInCycle(pomodoroIndex)
                ? FocusState.Mode.LONG_BREAK : FocusState.Mode.SHORT_BREAK;
    }

    private void prepareBreakAfterFocus(int pomodoroIndex, boolean startImmediately) {
        if (repository.getSettings().isBreaksDisabled()) {
            prepareNextPomodoro(pomodoroIndex, startImmediately);
            return;
        }
        FocusState.Mode breakMode = breakModeAfterFocus(pomodoroIndex);
        idleFocusMode = breakMode;
        uiPreferences.edit().putInt("focus_idle_mode", breakMode.ordinal()).apply();
        int minutes = focusMinutes(repository.getSettings(), breakMode);
        String carriedTaskId = validFocusTaskId(lastFocusTaskId);
        repository.setCurrentFocus(FocusState.ready(carriedTaskId, breakMode,
                minutes * 60_000L, Math.max(1, pomodoroIndex),
                carriedTaskId == null ? focusCustomTaskLabel : ""));
        if (startImmediately) mainHandler.post(this::toggleFocusTimer);
    }

    private void prepareNextPomodoro(int previousPomodoroIndex, boolean startImmediately) {
        idleFocusMode = FocusState.Mode.FOCUS;
        uiPreferences.edit().putInt("focus_idle_mode", FocusState.Mode.FOCUS.ordinal()).apply();
        int minutes = repository.getSettings().getFocusDurationMinutes();
        String taskId = validFocusTaskId(lastFocusTaskId);
        repository.setCurrentFocus(FocusState.ready(taskId, FocusState.Mode.FOCUS,
                minutes * 60_000L, Math.max(1, previousPomodoroIndex + 1),
                taskId == null ? focusCustomTaskLabel : ""));
        if (startImmediately) mainHandler.post(this::toggleFocusTimer);
    }

    private void setIdleModeAfterFocus(int pomodoroIndex) {
        idleFocusMode = breakModeAfterFocus(pomodoroIndex);
        uiPreferences.edit().putInt("focus_idle_mode", idleFocusMode.ordinal()).apply();
    }

    private void advanceAfterFocusUsingSettings(FocusState state) {
        AppSettings settings = repository.getSettings();
        if (!settings.isAutoAdvanceFocusSegment()) {
            setIdleModeAfterFocus(state.getPomodoroIndex());
            renderCurrentPage();
            return;
        }
        if (settings.isBreaksDisabled()) {
            prepareNextPomodoro(state.getPomodoroIndex(), settings.isAutoStartNextPomodoro());
        } else {
            prepareBreakAfterFocus(state.getPomodoroIndex(), settings.isAutoStartBreak());
        }
        renderCurrentPage();
    }

    private String todayFocusStats() {
        long now = System.currentTimeMillis(); int count = 0; long totalMinutes = 0;
        for (FocusRecord record : repository.getFocusHistory()) {
            if (sameDay(record.getCompletedAt(), now)) {
                if ("completed".equals(record.getOutcome())) count++;
                totalMinutes += record.getDurationMinutesRounded();
            }
        }
        if (count == 0 && totalMinutes == 0) return "";
        return count + " 个番茄 · "
                + formatDuration((int) Math.min(Integer.MAX_VALUE, totalMinutes));
    }

    private void addFocusHistory(LinearLayout content, TextView sectionTitle) {
        List<FocusRecord> history = new ArrayList<>(repository.getFocusHistory());
        history.sort(Comparator.comparingLong(FocusRecord::getCompletedAt).reversed());
        if (history.isEmpty()) return;
        sectionTitle.setVisibility(View.VISIBLE);
        for (int i = 0; i < Math.min(3, history.size()); i++) {
            FocusRecord record = history.get(i);
            LinearLayout row = row(); row.setPadding(dp(14), dp(11), dp(14), dp(11)); row.setBackground(rounded(SURFACE, UiKit.RADIUS_CONTAINER));
            LinearLayout text = column(); text.addView(label(record.getTaskTitleSnapshot(), 14, TEXT, Typeface.BOLD));
            text.addView(label(formatDateTime(record.getCompletedAt()), 11, TEXT_MUTED, Typeface.NORMAL), marginTop(lp(-2, -2), dp(4)));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(label(formatDuration(record.getDurationMinutesRounded()), 12, PRIMARY, Typeface.BOLD));
            content.addView(row, marginBottom(lp(-1, -2), dp(7)));
        }
    }

    private void initializeDeviceSync() {
        AppSettings current = repository.getSettings();
        SyncSnapshotGateway syncGateway =
                ((TimeAssistantApplication) getApplication()).getSyncSnapshotGateway();
        deviceSyncManager = new DeviceSyncManager(current.getDeviceId(), new DeviceSyncManager.SnapshotProvider() {
            @Override public String getSnapshot() { return syncGateway.exportSnapshot(); }

            @Override public void applyMergedSnapshot(String mergedSnapshot) {
                applyingRemoteSync = true;
                try { syncGateway.mergeSnapshot(mergedSnapshot); }
                finally { applyingRemoteSync = false; }
            }
        }, new DeviceSyncManager.Callback() {
            @Override public void onHostStarted(DeviceSyncManager.HostInfo info) {
                syncStatus = "配对服务已开启：" + localIpv4Address() + ":" + info.port
                        + "  配对码 " + info.pairingCode;
                AppSettings settings = repository.getSettings();
                String existingEndpoint = settings.getSyncEndpoint();
                String existingPeerName = settings.getPairedDeviceName();
                settings.setPairingInfo(info.pairingCode,
                                existingEndpoint.isEmpty() ? "等待设备加入" : existingPeerName)
                        .setSyncConfiguration(existingEndpoint, true);
                repository.saveSettings(settings);
                refreshSettingsIfVisible();
            }

            @Override public void onPeerAvailable(DeviceSyncManager.Peer peer) {
                syncStatus = "已配对 " + peer.host + ":" + peer.port;
                AppSettings settings = repository.getSettings();
                settings.setPairingInfo(peer.pairingCode,
                                peer.deviceId == null ? "已配对设备" : peer.deviceId)
                        .setSyncConfiguration(peer.host + ":" + peer.port, true)
                        .setLastSyncAt(System.currentTimeMillis());
                repository.saveSettings(settings);
                refreshSettingsIfVisible();
            }

            @Override public void onSyncComplete(String mergedSnapshot) {
                syncStatus = "同步完成 · " + new SimpleDateFormat("HH:mm:ss", LOCALE).format(new Date());
                AppSettings settings = repository.getSettings();
                settings.setLastSyncAt(System.currentTimeMillis()); repository.saveSettings(settings);
                showMessage("设备数据已同步"); refreshSettingsIfVisible();
            }

            @Override public void onError(DeviceSyncManager.SyncError error) {
                syncStatus = "同步失败：" + error.message;
                showMessage(syncStatus); refreshSettingsIfVisible();
            }
        });
        restoreSyncPeer(current);
    }

    private void restoreSyncPeer(AppSettings settings) {
        if (!settings.isSyncEnabled() || settings.getSyncEndpoint().isEmpty()
                || settings.getPairingCode().isEmpty()) return;
        String endpoint = settings.getSyncEndpoint();
        int separator = endpoint.lastIndexOf(':');
        if (separator <= 0) return;
        try {
            String host = endpoint.substring(0, separator);
            int port = Integer.parseInt(endpoint.substring(separator + 1));
            String pairedDeviceId = settings.getPairedDeviceName();
            // Older builds stored a localized display label here.  The sync protocol accepts
            // only the peer's safe ASCII device id, so discard any legacy label on restore.
            if (pairedDeviceId == null
                    || !pairedDeviceId.matches("[A-Za-z0-9._:-]{1,128}")) {
                pairedDeviceId = null;
            }
            DeviceSyncManager.Peer restoredPeer = new DeviceSyncManager.Peer(
                    host, port, settings.getPairingCode(), pairedDeviceId
            );
            deviceSyncManager.setPeer(restoredPeer);
            // Rebind the listener after process/activity recreation before attempting the first
            // exchange. Either peer can then initiate a later sync even if the other was offline
            // while this activity started.
            deviceSyncManager.joinBidirectionally(host, port, settings.getPairingCode());
            syncStatus = "正在恢复双向配对：" + endpoint;
        } catch (RuntimeException ignored) { syncStatus = "保存的配对信息无效，请重新配对"; }
    }

    private void refreshSettingsIfVisible() {
        if (settingsVisible) mainHandler.post(() -> {
            if (!settingsVisible) return;
            if (settingsDraft != null && settingsDraft.dirty()) return;
            if (aiSettingsFeature != null && aiSettingsFeature.isAttachedToWindow()
                    && aiSettingsFeature.hasPendingWork()) return;
            ScrollView oldScroll = pageContainer.findViewById(R.id.settingsScroll);
            int scrollY = oldScroll == null ? 0 : oldScroll.getScrollY();
            View page = focusSettingsVisible ? buildFocusSettingsPage() : buildSettingsPage();
            UiKit.polish(page);
            pageContainer.removeAllViews();
            pageContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));
            ScrollView newScroll = page.findViewById(R.id.settingsScroll);
            if (newScroll != null) newScroll.post(() -> newScroll.scrollTo(0, scrollY));
            updateBottomNavigationVisibility();
        });
    }

    private void openFocusSettings() {
        focusPageController.stopMotion();
        settingsVisible = true;
        focusSettingsVisible = true;
        View page = buildFocusSettingsPage();
        UiKit.polish(page);
        com.hawson.timeassistant.design.ContentTransition.replace(pageContainer, page, 1, true);
        updateBottomNavigationVisibility();
    }

    private View buildFocusSettingsPage() {
        LinearLayout root = column();
        root.setBackgroundColor(AppTheme.resolveColor(
                this, R.attr.colorAppBackground, R.color.app_background));
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(4), dp(16), dp(4));
        ImageView back = iconButton(R.drawable.ic_back, "返回专注页");
        header.addView(back, lp(dp(48), dp(48)));
        LinearLayout titles = column();
        TextView focusSettingsTitle = label("专注设置", 20, TEXT, Typeface.BOLD);
        ViewCompat.setAccessibilityHeading(focusSettingsTitle, true);
        titles.addView(focusSettingsTitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        back.setOnClickListener(v -> closeSettings());
        applyPressFeedback(back);

        FocusSettingsLayout layout = new FocusSettingsLayout(this);
        AppSettings settings = repository.getSettings();
        LinearLayout config = layout.section("计时");
        SwitchCompat countUp = focusSettingsSwitch("正计时（关闭为倒计时）",
                settings.isFocusCountUpEnabled());
        config.addView(countUp, marginTop(lp(-1, -2), dp(12)));
        EditText focusMinutes = edit("分钟",
                String.valueOf(settings.getFocusDurationMinutes()), false);
        EditText shortBreakMinutes = edit("分钟",
                String.valueOf(settings.getShortBreakDurationMinutes()), false);
        EditText longBreakMinutes = edit("分钟",
                String.valueOf(settings.getLongBreakDurationMinutes()), false);
        EditText focusRounds = edit("番茄数",
                String.valueOf(settings.getFocusRoundsPerCycle()), false);
        for (EditText input : new EditText[]{focusMinutes, shortBreakMinutes,
                longBreakMinutes, focusRounds}) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        config.addView(UiKit.numericRow(this, "番茄时长", focusMinutes, "分钟"),
                marginTop(lp(-1, -2), dp(12)));
        config.addView(UiKit.numericRow(this, "短休时长", shortBreakMinutes, "分钟"),
                marginTop(lp(-1, -2), dp(10)));
        config.addView(UiKit.numericRow(this, "长休时长", longBreakMinutes, "分钟"),
                marginTop(lp(-1, -2), dp(10)));
        focusRounds.setInputType(InputType.TYPE_CLASS_NUMBER);
        config.addView(UiKit.numericRow(this, "长休间隔", focusRounds, "番茄"),
                marginTop(lp(-1, -2), dp(10)));
        TextView cyclePreview = label(focusCyclePreview(settings.getFocusRoundsPerCycle()),
                12, TEXT_MUTED, Typeface.NORMAL);
        config.addView(cyclePreview, marginTop(lp(-1, -2), dp(10)));

        LinearLayout automation = layout.section("衔接");
        SwitchCompat autoAdvance = focusSettingsSwitch("结束后自动进入下一段",
                settings.isAutoAdvanceFocusSegment());
        SwitchCompat disableBreaks = focusSettingsSwitch("禁用休息",
                settings.isBreaksDisabled());
        SwitchCompat autoBreak = focusSettingsSwitch("自动开始休息",
                settings.isAutoStartBreak());
        SwitchCompat autoPomodoro = focusSettingsSwitch("自动开始下个番茄",
                settings.isAutoStartNextPomodoro());
        SwitchCompat focusReminder = focusSettingsSwitch("专注结束提醒",
                settings.isFocusReminderEnabled());
        autoBreak.setPadding(dp(16), dp(8), 0, dp(8));
        autoPomodoro.setPadding(dp(16), dp(8), 0, dp(8));
        automation.addView(autoAdvance, marginTop(lp(-1, -2), dp(12)));
        automation.addView(disableBreaks, lp(-1, -2));
        automation.addView(autoBreak, lp(-1, -2));
        automation.addView(autoPomodoro, lp(-1, -2));
        automation.addView(focusReminder, lp(-1, -2));
        LinearLayout stageSignals = layout.section("声振");
        SwitchCompat endSound = focusSettingsSwitch("结束铃声",
                settings.isFocusEndSoundEnabled());
        SwitchCompat endVibration = focusSettingsSwitch("结束振动",
                settings.isFocusEndVibrationEnabled());
        SwitchCompat ambient = focusSettingsSwitch("专注时播放轻柔环境音",
                settings.isAmbientSoundEnabled());
        View lockscreenSettings = UiKit.row(this, "配置锁屏番茄钟显示", null,
                R.drawable.ic_more, () -> openFocusLockscreenSettings(false));
        stageSignals.addView(endSound, marginTop(lp(-1, -2), dp(12)));
        stageSignals.addView(endVibration, lp(-1, -2));
        stageSignals.addView(ambient, lp(-1, -2));
        stageSignals.addView(lockscreenSettings, marginTop(lp(-1, -2), dp(12)));
        Runnable refreshDependencies = () -> {
            boolean breaksEnabled = !disableBreaks.isChecked();
            setFocusSettingEnabled(shortBreakMinutes, breaksEnabled);
            setFocusSettingEnabled(longBreakMinutes, breaksEnabled);
            setFocusSettingEnabled(focusRounds, breaksEnabled);
            cyclePreview.setText(breaksEnabled
                    ? focusCyclePreview(parsePositiveInt(focusRounds.getText().toString(),
                    settings.getFocusRoundsPerCycle(), 1, 20))
                    : "休息已禁用：番茄结束后直接回到下一轮番茄");
            boolean canAutoBreak = autoAdvance.isChecked() && breaksEnabled;
            boolean canAutoPomodoro = autoAdvance.isChecked();
            if (!canAutoBreak) autoBreak.setChecked(false);
            if (!canAutoPomodoro) autoPomodoro.setChecked(false);
            setFocusSettingEnabled(autoBreak, canAutoBreak);
            setFocusSettingEnabled(autoPomodoro, canAutoPomodoro);
        };
        autoAdvance.setOnCheckedChangeListener((button, checked) -> refreshDependencies.run());
        disableBreaks.setOnCheckedChangeListener((button, checked) -> refreshDependencies.run());
        refreshDependencies.run();

        Button save = UiKit.primaryButton(this, "保存专注设置");
        save.setOnClickListener(v -> {
            try {
                AppSettings changed = repository.getSettings();
                changed.setFocusDurations(
                                parsePositiveInt(focusMinutes.getText().toString(),
                                        changed.getFocusDurationMinutes(), 1, 24 * 60),
                                parsePositiveInt(shortBreakMinutes.getText().toString(),
                                        changed.getShortBreakDurationMinutes(), 1, 24 * 60),
                                parsePositiveInt(longBreakMinutes.getText().toString(),
                                        changed.getLongBreakDurationMinutes(), 1, 24 * 60))
                        .setFocusRoundsPerCycle(parsePositiveInt(
                                focusRounds.getText().toString(),
                                changed.getFocusRoundsPerCycle(), 1, 20))
                        .setFocusCountUpEnabled(countUp.isChecked())
                        .setAmbientSoundEnabled(ambient.isChecked())
                        .setFocusEndSignals(endSound.isChecked(), endVibration.isChecked())
                        .setFocusAutomation(autoAdvance.isChecked(), autoBreak.isChecked(),
                                autoPomodoro.isChecked(), disableBreaks.isChecked())
                        .setNotificationSettings(changed.isScheduleReminderEnabled(),
                                changed.isTaskDeadlineReminderEnabled(), focusReminder.isChecked(),
                                changed.isOverdueTaskReminderEnabled(),
                                changed.isDailyReviewReminderEnabled());
                repository.saveSettings(changed);
                if (settingsDraft != null) settingsDraft.accept();
                FocusState currentFocus = repository.getCurrentFocus();
                if (changed.isBreaksDisabled()
                        && currentFocus.getState() != FocusState.State.RUNNING
                        && displayFocusMode(currentFocus) != FocusState.Mode.FOCUS) {
                    idleFocusMode = FocusState.Mode.FOCUS;
                    uiPreferences.edit().putInt("focus_idle_mode",
                            FocusState.Mode.FOCUS.ordinal()).apply();
                    repository.clearCurrentFocus();
                }
                if (focusReminder.isChecked()) requestNotificationPermissionIfNeeded();
                showMessage("专注设置已保存");
            } catch (RuntimeException error) {
                showMessage("保存失败：" + safeError(error));
            }
        });
        LinearLayout historyCard = layout.section("记录");
        List<FocusRecord> history = new ArrayList<>(repository.getFocusHistory());
        history.sort(Comparator.comparingLong(FocusRecord::getCompletedAt).reversed());
        if (history.isEmpty()) {
            historyCard.addView(label("还没有专注记录", 13, TEXT_MUTED, Typeface.NORMAL),
                    marginTop(lp(-1, -2), dp(16)));
        } else {
            for (FocusRecord record : history) addFocusSettingsRecord(historyCard, record);
        }
        layout.attach(root, save);
        if (focusSettingsCategory == FOCUS_SETTINGS_HISTORY) layout.reveal(historyCard);
        settingsDraft = new FormDraftGuard.Session(this, config, automation, stageSignals);
        UiKit.polish(root);
        return root;
    }

    private String focusCyclePreview(int rounds) {
        int safeRounds = Math.max(1, rounds);
        StringBuilder preview = new StringBuilder("循环：");
        for (int index = 1; index <= safeRounds; index++) {
            if (index > 1) preview.append(" → ");
            preview.append("专注 → ").append(index == safeRounds ? "长休" : "短休");
        }
        return preview.toString();
    }

    private SwitchCompat focusSettingsSwitch(String text, boolean checked) {
        SwitchCompat value = new SwitchCompat(this);
        value.setText(text);
        value.setTextColor(TEXT);
        value.setTextSize(13);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setMinHeight(dp(48));
        value.setPadding(0, dp(8), 0, dp(8));
        value.setChecked(checked);
        return value;
    }

    private void setFocusSettingEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.42f);
    }

    private void addFocusSettingsRecord(LinearLayout parent, FocusRecord record) {
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), dp(12), dp(8), dp(12));
        item.setBackground(rounded(NEUTRAL, UiKit.RADIUS_CONTAINER));
        LinearLayout text = column();
        String title = valueOrEmpty(record.getTaskTitleSnapshot()).isEmpty()
                ? "无任务专注" : record.getTaskTitleSnapshot();
        text.addView(label(title, 14, TEXT, Typeface.BOLD));
        String detail = formatDateTime(record.getCompletedAt()) + " · "
                + formatDuration(record.getDurationMinutesRounded()) + " · "
                + focusOutcomeLabel(record.getOutcome());
        if (!record.getNote().isEmpty()) detail += "\n原因：" + record.getNote();
        text.addView(label(detail, 11, TEXT_MUTED, Typeface.NORMAL),
                marginTop(lp(-1, -2), dp(4)));
        item.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView delete = label("删除", 12, ERROR, Typeface.BOLD);
        delete.setGravity(Gravity.CENTER);
        item.addView(delete, lp(dp(52), dp(44)));
        delete.setOnClickListener(v -> new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("删除这条专注记录？")
                .setMessage("该操作不会删除任务。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    repository.deleteFocusRecord(record.getId());
                    openFocusSettings();
                }).show());
        applyPressFeedback(delete);
        parent.addView(item, marginTop(lp(-1, -2), dp(10)));
    }

    private String focusOutcomeLabel(String outcome) {
        if ("interrupted".equals(outcome)) return "任务中断";
        if ("ended_early".equals(outcome)) return "提前结束";
        return "已完成";
    }

    private String localIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "本机局域网地址";
    }

    protected View buildSettingsPage() {
        PageSettingsBinding binding = settingsPageController.inflate(getLayoutInflater());
        AppSettings settings = repository.getSettings();
        binding.backButton.setOnClickListener(view -> closeSettings());
        applyPressFeedback(binding.backButton);
        bindThemeOptions(binding);
        binding.themeSettingsCard.addView(new AppearanceSettingsView(this,
                () -> tutorials.replay()), lp(-1, -2));

        binding.aiSettingsCard.removeAllViews();
        binding.aiSettingsCard.setBackgroundColor(Color.TRANSPARENT);
        binding.aiSettingsCard.setPadding(0, 0, 0, 0);
        aiSettingsFeature = appGraph.aiSettings(this, new AiConnectionsView.Host() {
            public void diagnostics() { showAiErrorLog(); }
            public void usage() { aiTools().showAccountsAndUsage(); }
            public void speech() { aiTools().showSpeechSettings(); }
            public void keepSignInAlive() { com.hawson.timeassistant.auth.OAuthSignInService.start(TimeAssistantActivity.this); }
            public void signInBackgroundSettings() {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
            public void beforeChange(Runnable change) { beforeAiConnectionChange(change); }
            public void openBrowser(String url) { openAiBrowser(url); }
        });
        binding.aiSettingsCard.addView(aiSettingsFeature, lp(-1, -2));
        if (appGraph.ai.migrationError != null) {
            binding.aiSettingsCard.addView(label(appGraph.ai.migrationError, 14, ERROR, Typeface.NORMAL));
        }

        binding.availableStartInput.setText(formatMinute(settings.getAvailableStartMinute()));
        binding.availableEndInput.setText(formatMinute(settings.getAvailableEndMinute()));
        binding.workStartInput.setText(formatMinute(settings.getWorkStartMinute()));
        binding.workEndInput.setText(formatMinute(settings.getWorkEndMinute()));
        binding.blockedRangesInput.setText(formatBlockedRanges(settings));
        binding.allowMoveSwitch.setChecked(settings.isAllowAiRescheduleUnlockedTasks());
        binding.scheduleReminderSwitch.setChecked(settings.isScheduleReminderEnabled());
        binding.deadlineReminderSwitch.setChecked(settings.isTaskDeadlineReminderEnabled());
        binding.overdueReminderSwitch.setChecked(settings.isOverdueTaskReminderEnabled());
        binding.reviewReminderSwitch.setChecked(settings.isDailyReviewReminderEnabled());
        for (EditText clock : new EditText[]{binding.availableStartInput, binding.availableEndInput,
                binding.workStartInput, binding.workEndInput}) {
            clock.setOnClickListener(view -> showClockPicker(clock));
        }
        View diagnostics = UiKit.row(this, "任务提醒诊断", "通知、声音、震动与准时性",
                R.drawable.ic_notification_time_isle, () -> com.hawson.timeassistant.reminder.ReminderDiagnostics.show(this, () -> {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) focusNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    else com.hawson.timeassistant.reminder.ReminderDiagnostics.openNotificationSettings(this, false);
                }));
        // The group already has 16dp padding; let the row span its full width.
        LinearLayout.LayoutParams diagnosticsParams = marginTop(lp(-1, -2), dp(8));
        diagnosticsParams.leftMargin = -dp(16); diagnosticsParams.rightMargin = -dp(16);
        binding.notificationGroup.addView(diagnostics, diagnosticsParams);
        NotificationPrivacyStore notificationPrivacy = new NotificationPrivacyStore(this);
        SwitchCompat hideContent = focusSettingsSwitch("通知中隐藏任务内容（含锁屏）",
                notificationPrivacy.hideTaskContent());
        binding.notificationGroup.addView(hideContent, marginTop(lp(-1, -2), dp(4)));
        hideContent.setOnCheckedChangeListener((button, checked) -> {
            notificationPrivacy.setHideTaskContent(checked);
            if (checked) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    for (android.service.notification.StatusBarNotification active :
                            manager.getActiveNotifications()) {
                        if ((active.getNotification().flags
                                & android.app.Notification.FLAG_FOREGROUND_SERVICE) == 0) {
                            manager.cancel(active.getTag(), active.getId());
                        }
                    }
                }
            }
            FocusTimerService.sync(this, repository.getCurrentFocus());
        });
        binding.savePreferencesButton.setOnClickListener(view -> savePreferenceSettings(
                binding.availableStartInput, binding.availableEndInput,
                binding.workStartInput, binding.workEndInput, binding.blockedRangesInput,
                binding.allowMoveSwitch, binding.scheduleReminderSwitch,
                binding.deadlineReminderSwitch,
                binding.overdueReminderSwitch, binding.reviewReminderSwitch));

        binding.dynamicSettingsContainer.addView(
                buildSyncSettingsCard(), lp(-1, -2));
        binding.dynamicSettingsContainer.addView(
                buildDataSettingsCard(), lp(-1, -2));
        binding.preferenceIntelligenceContainer.addView(
                buildLearningSettingsCard(), lp(-1, -2));
        binding.preferenceIntelligenceContainer.addView(
                buildPreferenceRulesCard(), lp(-1, -2));
        binding.connectionSettingsContainer.addView(
                buildCalendarConnectionCard(), lp(-1, -2));
        systemPermissionView = systemPermissionGuide.createView();
        binding.settingsBody.addView(systemPermissionView, lp(-1, -2));
        buildProactiveSettings(binding.proactiveSettingsContainer);
        settingsDraft = new FormDraftGuard.Session(this,
                binding.availableStartInput, binding.availableEndInput, binding.workStartInput,
                binding.workEndInput, binding.blockedRangesInput, binding.allowMoveSwitch,
                binding.scheduleReminderSwitch, binding.deadlineReminderSwitch,
                binding.overdueReminderSwitch, binding.reviewReminderSwitch);
        bindSettingsCategories(binding);
        return binding.getRoot();
    }

    private void bindSettingsCategories(PageSettingsBinding binding) {
        settingsNavigation = new SettingsNavigationView(this, category ->
                settingsDraft.leave(() -> aiSettingsFeature.leave(() -> {
                    settingsCategory = category;
                    renderSettingsSection(binding);
                    binding.settingsScroll.smoothScrollTo(0, 0);
                })));
        boolean aiConfigured = isAiConfigured();
        settingsNavigation.configure(new int[]{R.drawable.ic_settings, R.drawable.ic_nav_chat,
                R.drawable.ic_time_isle_mark, R.drawable.ic_nav_schedule, R.drawable.ic_calendar,
                R.drawable.ic_more, R.drawable.ic_notification_time_isle},
                new String[]{"主题、动效与教程", aiConfigured ? "已连接" : "尚未连接",
                        "整理建议与提醒", "时间、通知与个人偏好", "系统日历忙碌时间",
                        "设备配对与数据管理", "通知、后台与按需权限"},
                new int[]{0, aiConfigured ? ACCENT : 0, 0, 0, 0, 0, 0},
                new SettingsNavigationView.Group[]{
                        new SettingsNavigationView.Group("个性化", SETTINGS_APPEARANCE),
                        new SettingsNavigationView.Group("智能助手", SETTINGS_AI, SETTINGS_PROACTIVE,
                                SETTINGS_PREFERENCES),
                        new SettingsNavigationView.Group("数据与系统", SETTINGS_CONNECTIONS, SETTINGS_DATA,
                                SETTINGS_PERMISSIONS)});
        binding.settingsBody.addView(settingsNavigation, 0, lp(-1, -2));
        binding.backButton.setOnClickListener(view -> {
            if (settingsCategory < 0) closeSettings();
            else settingsDraft.leave(() -> aiSettingsFeature.leave(() -> { settingsCategory = -1; renderSettingsSection(binding); }));
        });
        renderSettingsSection(binding);
    }

    private void renderSettingsSection(PageSettingsBinding binding) {
        settingsNavigation.showSection(settingsCategory);
        binding.settingsTitle.setText(SettingsNavigationView.sectionTitle(settingsCategory));
        ViewCompat.setAccessibilityHeading(binding.settingsTitle, true);
        binding.backButton.setContentDescription(settingsCategory < 0 ? "返回" : "返回设置");
        binding.themeSettingsCard.setVisibility(settingsCategory == SETTINGS_APPEARANCE ? View.VISIBLE : View.GONE);
        binding.aiSettingsCard.setVisibility(settingsCategory == SETTINGS_AI ? View.VISIBLE : View.GONE);
        binding.proactiveSettingsContainer.setVisibility(settingsCategory == SETTINGS_PROACTIVE ? View.VISIBLE : View.GONE);
        boolean preferences = settingsCategory == SETTINGS_PREFERENCES;
        binding.timeSettingsCard.setVisibility(preferences ? View.VISIBLE : View.GONE);
        binding.notificationSettingsCard.setVisibility(preferences ? View.VISIBLE : View.GONE);
        binding.preferenceSaveBar.setVisibility(preferences ? View.VISIBLE : View.GONE);
        binding.preferenceIntelligenceContainer.setVisibility(preferences ? View.VISIBLE : View.GONE);
        binding.connectionSettingsContainer.setVisibility(settingsCategory == SETTINGS_CONNECTIONS ? View.VISIBLE : View.GONE);
        binding.dynamicSettingsContainer.setVisibility(settingsCategory == SETTINGS_DATA ? View.VISIBLE : View.GONE);
        systemPermissionView.setVisibility(settingsCategory == SETTINGS_PERMISSIONS ? View.VISIBLE : View.GONE);
        if (settingsCategory == SETTINGS_PERMISSIONS) systemPermissionGuide.refresh();
        UiKit.polish(binding.getRoot());
    }

    private void bindThemeOptions(PageSettingsBinding binding) {
        String selectedTheme = AppTheme.selected(uiPreferences);
        bindThemeOption(binding.themeNeutralOption, AppTheme.NEUTRAL, "中性色", selectedTheme);
        bindThemeOption(binding.themeVioletOption, AppTheme.VIOLET,
                getString(R.string.theme_violet), selectedTheme);
        bindThemeOption(binding.themeOceanOption, AppTheme.OCEAN,
                getString(R.string.theme_ocean), selectedTheme);
        bindThemeOption(binding.themeForestOption, AppTheme.FOREST,
                getString(R.string.theme_forest), selectedTheme);
        bindThemeOption(binding.themeClayOption, AppTheme.CLAY,
                getString(R.string.theme_clay), selectedTheme);
    }

    private void bindThemeOption(View option, String themeId, String label,
                                 String selectedTheme) {
        boolean selected = themeId.equals(selectedTheme);
        option.setBackground(outlined(
                selected ? PRIMARY_SOFT : SURFACE,
                selected ? PRIMARY : BORDER,
                UiKit.RADIUS_CONTAINER,
                selected ? 2 : 1
        ));
        View check = option.findViewById(R.id.themeSwatchCheck);
        if (check != null) check.setVisibility(selected ? View.VISIBLE : View.GONE);
        option.setContentDescription(selected
                ? label + "，" + getString(R.string.theme_selected)
                : label);
        option.setSelected(selected);
        option.setOnClickListener(view -> selectTheme(themeId));
        applyPressFeedback(option);
    }

    /** Opens a 24-hour picker and writes HH:mm back so the existing save and draft logic still apply. */
    private void showClockPicker(EditText field) {
        int minute;
        try { minute = parseClockMinute(field.getText().toString()); }
        catch (RuntimeException invalid) { minute = 9 * 60; }
        new TimePickerDialog(this, (picker, hour, minuteOfHour) -> {
            field.setText(String.format(Locale.ROOT, "%02d:%02d", hour, minuteOfHour));
            field.setError(null);
        }, (minute / 60) % 24, minute % 60, true).show();
    }

    /** Section header plus a framed group, used by dynamically built settings sections. */
    private LinearLayout settingsGroup(LinearLayout parent, String title, String subtitle) {
        parent.addView(UiKit.sectionHeader(this, title));
        LinearLayout group = UiKit.group(this);
        if (subtitle != null && !subtitle.isEmpty()) {
            group.addView(label(subtitle, 12, TEXT_MUTED, Typeface.NORMAL), lp(-1, -2));
        }
        parent.addView(group, lp(-1, -2));
        return group;
    }

    private LinearLayout settingsListGroup(LinearLayout parent, String title) {
        parent.addView(UiKit.sectionHeader(this, title));
        LinearLayout group = UiKit.groupList(this);
        parent.addView(group, lp(-1, -2));
        return group;
    }

    /** Two equal-width buttons side by side. */
    private LinearLayout buttonPair(Button first, Button second) {
        LinearLayout pair = row();
        pair.addView(first, new LinearLayout.LayoutParams(0, dp(48), 1));
        pair.addView(second, marginLeft(new LinearLayout.LayoutParams(0, dp(48), 1), dp(8)));
        return pair;
    }

    private void selectTheme(String themeId) {
        String normalized = AppTheme.normalize(themeId);
        if (normalized.equals(AppTheme.selected(uiPreferences))) return;
        uiPreferences.edit().putString(AppTheme.PREFERENCE_KEY, normalized).apply();
        recreate();
    }

    private LinearLayout settingsCard(String title, String subtitle) {
        LinearLayout card = column(); card.setPadding(0, dp(16), 0, dp(16));
        card.addView(label(title, 17, TEXT, Typeface.BOLD));
        card.addView(label(subtitle, 12, TEXT_MUTED, Typeface.NORMAL), marginTop(lp(-1, -2), dp(4)));
        return card;
    }

    private void showAiErrorLog() {
        if (aiErrorLogStore == null) return;
        List<AiErrorLogStore.Entry> entries = aiErrorLogStore.getEntries();
        if (entries.isEmpty()) {
            new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                    .setTitle("AI 错误日志")
                    .setMessage("还没有错误记录。只有 AI 请求失败时才会在这里留下详细信息。")
                    .setPositiveButton("好", null)
                    .show();
            return;
        }
        String[] labels = new String[entries.size()];
        SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm:ss", LOCALE);
        for (int index = 0; index < entries.size(); index++) {
            AiErrorLogStore.Entry entry = entries.get(index);
            labels[index] = dateFormat.format(new Date(entry.createdAt))
                    + (entry.hadAttachments ? " · 含附件" : " · 普通请求");
        }
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("AI 错误日志")
                .setItems(labels, (dialog, which) -> showAiErrorLogEntry(entries.get(which)))
                .setNegativeButton("关闭", null)
                .setNeutralButton("清空记录", (dialog, which) -> {
                    aiErrorLogStore.clear();
                    refreshSettingsIfVisible();
                })
                .show();
    }

    private void showAiErrorLogEntry(AiErrorLogStore.Entry entry) {
        StringBuilder detail = new StringBuilder();
        detail.append("时间：")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", LOCALE)
                        .format(new Date(entry.createdAt)))
                .append("\n类型：").append(entry.kind);
        if (entry.httpStatus > 0) detail.append("\nHTTP：").append(entry.httpStatus);
        if (!entry.model.isEmpty()) detail.append("\n模型：").append(entry.model);
        if (!entry.baseUrl.isEmpty()) detail.append("\n服务地址：").append(entry.baseUrl);
        detail.append("\n附件：").append(entry.hadAttachments ? "是" : "否")
                .append("\n\n详细错误\n").append(entry.detail);
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("错误详情")
                .setMessage(detail.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void savePreferenceSettings(EditText availableStart, EditText availableEnd,
                                        EditText workStart, EditText workEnd, EditText blocked,
                                        SwitchCompat allowMove, SwitchCompat scheduleReminder,
                                        SwitchCompat deadlineReminder,
                                        SwitchCompat overdueReminder, SwitchCompat reviewReminder) {
        try {
            AppSettings settings = repository.getSettings();
            settings.setAvailableWindow(parseClockMinute(availableStart.getText().toString()), parseClockMinute(availableEnd.getText().toString()))
                    .setWorkWindow(parseClockMinute(workStart.getText().toString()), parseClockMinute(workEnd.getText().toString()))
                    .setBlockedTimeRanges(parseBlockedRanges(blocked.getText().toString()))
                    .setAllowAiRescheduleUnlockedTasks(allowMove.isChecked())
                    .setNotificationSettings(scheduleReminder.isChecked(), deadlineReminder.isChecked(),
                            settings.isFocusReminderEnabled(), overdueReminder.isChecked(), reviewReminder.isChecked());
            repository.saveSettings(settings);
            if (Build.VERSION.SDK_INT >= 33 && anyReminderEnabled(settings)
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 702);
            }
            ReminderScheduler.refresh(
                    this,
                    repository.getAllTasks(),
                    settings,
                    repository.getCurrentFocus()
            );
            showMessage("偏好已保存");
            if (settingsDraft != null) settingsDraft.accept();
        } catch (RuntimeException exception) { showMessage("无法保存偏好：" + safeError(exception)); }
    }

    private boolean anyReminderEnabled(AppSettings value) {
        return value.isScheduleReminderEnabled() || value.isTaskDeadlineReminderEnabled()
                || value.isFocusReminderEnabled() || value.isOverdueTaskReminderEnabled()
                || value.isDailyReviewReminderEnabled();
    }

    private String formatMinute(int minute) {
        return String.format(LOCALE, "%02d:%02d", minute / 60, minute % 60);
    }

    private int parseClockMinute(String value) {
        String[] pieces = value.trim().split(":");
        if (pieces.length != 2) throw new IllegalArgumentException("时间须使用 HH:mm 格式");
        int hour = Integer.parseInt(pieces[0]); int minute = Integer.parseInt(pieces[1]);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new IllegalArgumentException("时间超出有效范围");
        return hour * 60 + minute;
    }

    private String formatBlockedRanges(AppSettings settings) {
        StringBuilder value = new StringBuilder();
        for (AppSettings.TimeRange range : settings.getBlockedTimeRanges()) {
            if (value.length() > 0) value.append(',');
            value.append(formatMinute(range.getStartMinute())).append('-').append(formatMinute(range.getEndMinute()));
        }
        return value.toString();
    }

    private List<AppSettings.TimeRange> parseBlockedRanges(String raw) {
        List<AppSettings.TimeRange> ranges = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return ranges;
        for (String part : raw.split("[,，]")) {
            String[] bounds = part.trim().split("-");
            if (bounds.length != 2) throw new IllegalArgumentException("不安排时间须形如 12:00-13:00");
            ranges.add(new AppSettings.TimeRange(parseClockMinute(bounds[0]), parseClockMinute(bounds[1]), "不安排"));
        }
        return ranges;
    }

    private void buildProactiveSettings(LinearLayout container) {
        ProactiveSettings settings = localIntegrationSettings.getProactiveSettings();
        boolean notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        container.addView(new com.hawson.timeassistant.ui.settings.ProactiveSettingsView(
                this, settings, isAiConfigured(), notificationGranted,
                new com.hawson.timeassistant.ui.settings.ProactiveSettingsView.Callbacks() {
                    @Override public void save(ProactiveSettings updated) {
                        localIntegrationSettings.saveProactiveSettings(updated);
                        ProactiveScheduleCoordinator.rescheduleAll(TimeAssistantActivity.this);
                    }

                    @Override public void requestMasterEnabled(boolean enabled,
                            java.util.function.Consumer<Boolean> result) {
                        if (!enabled) {
                            result.accept(false);
                            return;
                        }
                        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(TimeAssistantActivity.this)
                                .setTitle("允许主动 AI 请求？")
                                .setMessage("启用后，应用可在设定时段向你配置的 AI 服务发送所需任务、"
                                        + "排期及偏好上下文，以生成建议。它不会自动修改任务。"
                                        + "\n此授权与 AI 学习分别保存，可随时关闭。")
                                .setNegativeButton("取消", (dialog, which) -> result.accept(false))
                                .setPositiveButton("同意并启用", (dialog, which) -> {
                                    localIntegrationSettings.grantProactiveConsent();
                                    result.accept(true);
                                })
                                .setOnCancelListener(dialog -> result.accept(false)).show();
                    }

                    @Override public void requestNotifications(boolean enabled,
                            java.util.function.Consumer<Boolean> result) {
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                && ContextCompat.checkSelfPermission(TimeAssistantActivity.this,
                                        Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED) {
                            result.accept(false);
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                            return;
                        }
                        result.accept(enabled);
                    }

                    @Override public void runNow() {
                        ProactiveScheduleCoordinator.runCheckNow(TimeAssistantActivity.this);
                        showMessage("已开始检查已启用内容；没有符合条件的事项时不会发消息");
                    }

                    @Override public void showMessage(String message) {
                        TimeAssistantActivity.this.showMessage(message);
                    }
                }), lp(-1, -2));
    }

    private void setProactiveChildEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                setProactiveChildEnabled(group.getChildAt(index), enabled);
            }
        }
    }

    private View buildLearningSettingsCard() {
        LinearLayout section = column();
        LinearLayout card = settingsGroup(section, "AI 学习", "只分析脱敏的时间变化、项目、标签和操作结果");
        SwitchCompat enabled = focusSettingsSwitch("允许每日进入应用时分析一次", 
                localIntegrationSettings.isLearningEnabled());
        card.addView(enabled, marginTop(lp(-1, dp(52)), dp(8)));
        long last = localIntegrationSettings.getLastAnalysisSuccessAt();
        String state = last > 0 ? "上次分析：" + formatDateTime(last)
                : "尚未分析；AI 未配置或证据不足时不会发起请求";
        card.addView(label(state, 12, TEXT_MUTED, Typeface.NORMAL),
                marginTop(lp(-1, -2), dp(4)));
        LinearLayout danger = UiKit.groupList(this);
        section.addView(danger, marginTop(lp(-1, -2), dp(8)));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("允许 AI 分析学习证据？")
                        .setMessage("应用可每天将脱敏的时间变化、项目、标签和操作结果发送给你配置的"
                                + " AI 服务，推断偏好建议。建议不会自动生效。"
                                + "\n此授权与主动整理分别保存，可随时关闭或清除学习数据。")
                        .setNegativeButton("取消", (dialog, which) -> enabled.setChecked(false))
                        .setPositiveButton("同意并启用", (dialog, which) -> {
                            localIntegrationSettings.grantLearningConsent();
                            localIntegrationSettings.clearAnalysisCursor();
                            mainHandler.post(this::runPreferenceAnalysisIfDue);
                        })
                        .setOnCancelListener(dialog -> enabled.setChecked(false)).show();
                return;
            }
            localIntegrationSettings.setLearningEnabled(checked);
            if (!checked && preferenceLearningCoordinator != null) {
                preferenceLearningCoordinator.cancelForUserRequest();
            }
        });
        UiKit.addRows(danger, 52, UiKit.dangerRow(this, "清除学习数据", 0, () -> new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle("清除 AI 学习数据？")
                .setMessage("将删除行为证据和 AI 推断偏好；你手动创建的偏好会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) ->
                        localIntelligenceStore.clearLearningData(() -> {
                            localIntegrationSettings.clearAnalysisCursor();
                            showMessage("学习数据已清除");
                            refreshSettingsIfVisible();
                        })).show()));
        return section;
    }

    private View buildPreferenceRulesCard() {
        LinearLayout section = column();
        LinearLayout card = settingsGroup(section, "安排偏好", "明确偏好优先于已确认的 AI 推断；硬性工作时段始终优先");
        List<PreferenceRuleEntity> rules = localIntelligenceStore.preferences();
        if (rules.isEmpty()) {
            card.addView(label("暂无偏好", 13, TEXT_MUTED, Typeface.NORMAL),
                    marginTop(lp(-1, -2), dp(12)));
        } else {
            for (PreferenceRuleEntity rule : rules) addPreferenceRuleRow(card, rule);
        }
        Button add = UiKit.secondaryButton(this, "新增偏好");
        add.setOnClickListener(view -> showPreferenceRuleDialog(null));
        card.addView(add, marginTop(lp(-1, dp(48)), dp(12)));
        return section;
    }

    private void addPreferenceRuleRow(LinearLayout card, PreferenceRuleEntity rule) {
        LinearLayout row = column();
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        row.setBackground(rounded(NEUTRAL, UiKit.RADIUS_CONTAINER));
        LinearLayout top = row(); top.setGravity(Gravity.CENTER_VERTICAL);
        String title = (PreferenceRuleEntity.TYPE_AVOID_WINDOW.equals(rule.type) ? "避开 " : "偏好 ")
                + formatMinute(rule.startMinute) + "–" + formatMinute(rule.endMinute);
        top.addView(label(title, 14, TEXT, Typeface.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = label(preferenceStatusLabel(rule), 11,
                PreferenceRuleEntity.STATUS_ACTIVE.equals(rule.status) ? ACCENT : TEXT_MUTED,
                Typeface.BOLD);
        badge.setGravity(Gravity.CENTER); badge.setPadding(dp(8), 0, dp(8), 0);
        badge.setBackground(rounded(PreferenceRuleEntity.STATUS_ACTIVE.equals(rule.status)
                ? ACCENT_SOFT : NEUTRAL, UiKit.RADIUS_TAG));
        top.addView(badge, lp(-2, dp(24))); row.addView(top);
        String scope = PreferenceRuleEntity.SCOPE_GLOBAL.equals(rule.scopeType)
                ? "全部安排" : (PreferenceRuleEntity.SCOPE_TAG.equals(rule.scopeType) ? "标签 · " : "项目 · ") + rule.scopeValue;
        row.addView(label(scope + " · " + (PreferenceRuleEntity.ORIGIN_INFERRED.equals(rule.origin)
                        ? "AI 建议" : "手动设置"), 12, TEXT_MUTED, Typeface.NORMAL),
                marginTop(lp(-1, -2), dp(4)));
        LinearLayout actions = row();
        if (PreferenceRuleEntity.STATUS_CANDIDATE.equals(rule.status)) {
            Button reject = button("忽略", TEXT_MUTED, NEUTRAL);
            Button accept = button("确认", Color.WHITE, PRIMARY);
            actions.addView(reject, new LinearLayout.LayoutParams(0, dp(44), 1));
            actions.addView(accept, marginLeft(new LinearLayout.LayoutParams(0, dp(44), 1), dp(8)));
            reject.setOnClickListener(view -> {
                rule.status = PreferenceRuleEntity.STATUS_REJECTED;
                rule.rejectedUntil = System.currentTimeMillis() + 90L * 86_400_000L;
                localIntelligenceStore.putPreference(rule, this::refreshSettingsIfVisible);
            });
            accept.setOnClickListener(view -> {
                rule.status = PreferenceRuleEntity.STATUS_ACTIVE;
                rule.expiresAt = 0; rule.rejectedUntil = 0;
                localIntelligenceStore.putPreference(rule, this::refreshSettingsIfVisible);
            });
        } else {
            Button edit = button("编辑", TEXT_MUTED, NEUTRAL);
            Button toggle = button(PreferenceRuleEntity.STATUS_ACTIVE.equals(rule.status)
                    ? "停用" : "启用", PRIMARY, PRIMARY_SOFT);
            actions.addView(edit, new LinearLayout.LayoutParams(0, dp(44), 1));
            actions.addView(toggle, marginLeft(new LinearLayout.LayoutParams(0, dp(44), 1), dp(8)));
            edit.setOnClickListener(view -> showPreferenceRuleDialog(rule));
            toggle.setOnClickListener(view -> {
                rule.status = PreferenceRuleEntity.STATUS_ACTIVE.equals(rule.status)
                        ? PreferenceRuleEntity.STATUS_DISABLED : PreferenceRuleEntity.STATUS_ACTIVE;
                localIntelligenceStore.putPreference(rule, this::refreshSettingsIfVisible);
            });
        }
        row.addView(actions, marginTop(lp(-1, dp(44)), dp(10)));
        card.addView(row, marginTop(lp(-1, -2), dp(10)));
    }

    private String preferenceStatusLabel(PreferenceRuleEntity rule) {
        if (PreferenceRuleEntity.STATUS_CANDIDATE.equals(rule.status)) return "待确认";
        if (PreferenceRuleEntity.STATUS_ACTIVE.equals(rule.status)) return "生效中";
        if (PreferenceRuleEntity.STATUS_REJECTED.equals(rule.status)) return "已忽略";
        if (PreferenceRuleEntity.STATUS_EXPIRED.equals(rule.status)) return "已过期";
        return "已停用";
    }

    private void showPreferenceRuleDialog(PreferenceRuleEntity existing) {
        PreferenceRuleEntity draft = existing == null ? new PreferenceRuleEntity() : existing.copy();
        LinearLayout form = column(); form.setPadding(dp(20), dp(4), dp(20), dp(8));
        SwitchCompat avoid = focusSettingsSwitch("这是需要避开的时间", 
                PreferenceRuleEntity.TYPE_AVOID_WINDOW.equals(draft.type));
        EditText start = edit("开始时间 HH:mm", formatMinute(draft.startMinute), false);
        EditText end = edit("结束时间 HH:mm", formatMinute(draft.endMinute == 0 ? 60 : draft.endMinute), false);
        EditText scope = edit("项目或标签名称（留空表示全部）", draft.scopeValue, false);
        SwitchCompat tagScope = focusSettingsSwitch("将上方名称作为标签", 
                PreferenceRuleEntity.SCOPE_TAG.equals(draft.scopeType));
        form.addView(avoid, lp(-1, dp(52)));
        form.addView(start, marginTop(lp(-1, dp(50)), dp(8)));
        form.addView(end, marginTop(lp(-1, dp(50)), dp(8)));
        form.addView(scope, marginTop(lp(-1, dp(50)), dp(8)));
        form.addView(tagScope, marginTop(lp(-1, dp(52)), dp(4)));
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                .setTitle(existing == null ? "新增安排偏好" : "编辑安排偏好")
                .setView(form).setNegativeButton("取消", null)
                .setNeutralButton(existing == null ? null : "删除", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    draft.type = avoid.isChecked() ? PreferenceRuleEntity.TYPE_AVOID_WINDOW
                            : PreferenceRuleEntity.TYPE_PREFERRED_WINDOW;
                    draft.startMinute = parseClockMinute(start.getText().toString());
                    draft.endMinute = parseClockMinute(end.getText().toString());
                    draft.scopeValue = scope.getText().toString().trim();
                    draft.scopeType = draft.scopeValue.isEmpty() ? PreferenceRuleEntity.SCOPE_GLOBAL
                            : tagScope.isChecked() ? PreferenceRuleEntity.SCOPE_TAG : PreferenceRuleEntity.SCOPE_PROJECT;
                    draft.origin = PreferenceRuleEntity.ORIGIN_EXPLICIT;
                    draft.status = PreferenceRuleEntity.STATUS_ACTIVE;
                    draft.confidence = 1d; draft.expiresAt = 0; draft.rejectedUntil = 0;
                    localIntelligenceStore.putPreference(draft, this::refreshSettingsIfVisible);
                    dialog.dismiss();
                } catch (RuntimeException error) { showMessage("无法保存偏好：" + safeError(error)); }
            });
            if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                    new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("删除这条偏好？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (confirm, which) -> {
                                localIntelligenceStore.deletePreference(existing.id, this::refreshSettingsIfVisible);
                                dialog.dismiss();
                            }).show());
        });
        dialog.show();
    }

    private View buildCalendarConnectionCard() {
        boolean enabled = localIntegrationSettings.isCalendarExperimentEnabled();
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
        if (enabled && !granted) {
            localIntegrationSettings.setCalendarExperimentEnabled(false);
            enabled = false;
        }
        LinearLayout section = column();
        section.addView(UiKit.sectionHeader(this, "系统日历"));
        LinearLayout card = UiKit.group(this);
        section.addView(card, lp(-1, -2));
        LinearLayout titleRow = row(); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(label("只读取忙碌区间，不读取标题、描述、参与人或账户", 12, TEXT_MUTED,
                Typeface.NORMAL), new LinearLayout.LayoutParams(0, -2, 1));
        titleRow.addView(UiKit.tag(this, "实验功能", UiKit.warning(this)),
                marginLeft(lp(-2, -2), dp(8)));
        card.addView(titleRow, lp(-1, -2));
        String state = enabled && granted ? "已开启 · 可按需读取忙碌时间"
                : granted ? "已授权但实验功能关闭" : "默认关闭 · 开启时会请求系统权限";
        card.addView(UiKit.statusLine(this, state, enabled && granted ? ACCENT : TEXT_MUTED),
                marginTop(lp(-1, -2), dp(12)));
        SwitchCompat toggle = focusSettingsSwitch("允许 AI 查询系统日历忙碌时间", enabled && granted);
        card.addView(toggle, marginTop(lp(-1, dp(52)), dp(4)));
        Button test = UiKit.secondaryButton(this, "测试读取");
        test.setEnabled(enabled && granted);
        card.addView(test, marginTop(lp(-1, dp(48)), dp(8)));
        LinearLayout links = UiKit.groupList(this);
        section.addView(links, marginTop(lp(-1, -2), dp(8)));
        UiKit.addRows(links, 52, UiKit.row(this, "系统权限", "在系统设置中管理日历授权",
                R.drawable.ic_calendar, () -> startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())))));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                localIntegrationSettings.setCalendarExperimentEnabled(false);
                refreshSettingsIfVisible();
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
                    == PackageManager.PERMISSION_GRANTED) {
                localIntegrationSettings.setCalendarExperimentEnabled(true);
                refreshSettingsIfVisible();
            } else {
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR);
            }
        });
        test.setOnClickListener(view -> testCalendarRead(test));
        return section;
    }

    private void testCalendarRead(Button button) {
        if (!CalendarAccessPolicy.canRead(this, localIntegrationSettings.isCalendarExperimentEnabled())) {
            showMessage("请先开启系统日历实验并授权"); return;
        }
        button.setEnabled(false); button.setText("读取中…");
        long startAt = startOfDay(System.currentTimeMillis());
        long endAt = startAt + 7L * 86_400_000L;
        calendarExecutor.execute(() -> {
            ToolExecutionEntity audit = new ToolExecutionEntity();
            audit.toolId = "calendar.busy.test";
            try {
                List<CalendarBusyReader.BusyBlock> blocks = new CalendarBusyReader()
                        .query(getContentResolver(), startAt, endAt);
                audit.status = ToolExecutionEntity.SUCCEEDED;
                audit.summary = "未来 7 天共 " + blocks.size() + " 个忙碌区间";
                runOnUiThread(() -> new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                        .setTitle("日历读取成功")
                        .setMessage("未来 7 天共读取到 " + blocks.size()
                                + " 个忙碌区间。没有读取事件标题或其他内容。")
                        .setPositiveButton("好", null).show());
            } catch (Exception error) {
                audit.status = ToolExecutionEntity.FAILED; audit.error = safeError(error);
                runOnUiThread(() -> showMessage("暂时无法读取系统日历，请检查权限"));
            } finally {
                audit.finishedAt = System.currentTimeMillis();
                localIntelligenceStore.putToolExecution(audit);
                runOnUiThread(() -> { button.setEnabled(true); button.setText("测试读取"); });
            }
        });
    }

    private View buildSyncSettingsCard() {
        AppSettings settings = repository.getSettings();
        LinearLayout section = column();
        LinearLayout card = settingsGroup(section, "设备配对与同步", "同一局域网内即可配对，无需账号");
        String detail = syncStatus;
        if (detail == null) {
            detail = settings.isSyncEnabled()
                    ? "已配对 " + valueOrEmpty(settings.getSyncEndpoint())
                    : "尚未配对设备";
        }
        if (settings.getLastSyncAt() > 0) detail += "\n上次同步：" + formatDateTime(settings.getLastSyncAt());
        card.addView(UiKit.statusLine(this, detail, settings.isSyncEnabled() ? ACCENT : TEXT_MUTED),
                marginTop(lp(-1, -2), dp(12)));
        Button join = UiKit.primaryButton(this, "加入设备");
        card.addView(join, marginTop(lp(-1, dp(48)), dp(12)));
        Button host = UiKit.secondaryButton(this, "生成配对码");
        Button sync = UiKit.secondaryButton(this, "立即同步");
        sync.setEnabled(deviceSyncManager != null && deviceSyncManager.getPeer() != null);
        card.addView(buttonPair(host, sync), marginTop(lp(-1, dp(48)), dp(8)));
        host.setOnClickListener(v -> startPairingHost()); join.setOnClickListener(v -> showJoinDeviceDialog());
        sync.setOnClickListener(v -> deviceSyncManager.syncNow());
        LinearLayout danger = UiKit.groupList(this);
        section.addView(danger, marginTop(lp(-1, -2), dp(8)));
        UiKit.addRows(danger, 52, UiKit.dangerRow(this, "断开配对",
                com.hawson.timeassistant.design.R.drawable.ui_ic_unlink, this::disconnectDeviceSync));
        return section;
    }

    private void startPairingHost() {
        if (deviceSyncManager == null) initializeDeviceSync();
        String code = deviceSyncManager.host();
        if (code != null) {
            syncStatus = "正在开启配对服务… 配对码 " + code;
            refreshSettingsIfVisible();
        }
    }

    private void showJoinDeviceDialog() {
        LinearLayout form = column(); form.setPadding(dp(22), dp(5), dp(22), 0);
        EditText host = edit("主机地址，例如 192.168.1.20", "", false);
        EditText port = edit("端口", String.valueOf(DeviceSyncManager.DEFAULT_PORT), false); port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText code = edit("6 位配对码", "", false); code.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(host, lp(-1, dp(50))); form.addView(port, marginTop(lp(-1, dp(50)), dp(8)));
        form.addView(code, marginTop(lp(-1, dp(50)), dp(8)));
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("加入已开启的设备").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("配对并同步", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = host.getText().toString().trim();
            String pairing = code.getText().toString().trim();
            int targetPort = parsePositiveInt(port.getText().toString(), DeviceSyncManager.DEFAULT_PORT, 1, 65535);
            if (address.isEmpty()) { host.setError("请输入主机地址"); return; }
            if (!pairing.matches("\\d{6}")) { code.setError("请输入 6 位配对码"); return; }
            deviceSyncManager.joinBidirectionally(address, targetPort, pairing);
            syncStatus = "正在配对并交换数据…"; dialog.dismiss(); refreshSettingsIfVisible();
        })); dialog.show();
    }

    private void disconnectDeviceSync() {
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("断开设备配对？")
                .setMessage("本机数据不会删除，之后可重新配对。")
                .setNegativeButton("取消", null).setPositiveButton("断开", (dialog, which) -> {
                    if (deviceSyncManager != null) deviceSyncManager.stop();
                    AppSettings settings = repository.getSettings();
                    settings.setPairingInfo("", "").setSyncConfiguration("", false).setLastSyncAt(0);
                    repository.saveSettings(settings); syncStatus = "已断开配对";
                    initializeDeviceSync(); refreshSettingsIfVisible();
                }).show();
    }

    private View buildDataSettingsCard() {
        LinearLayout section = column();
        LinearLayout card = settingsGroup(section, "数据管理", "本地数据会在应用关闭后保留");
        String status = repository.getAllTasks().size() + " 个任务 · "
                + repository.getChatMessages().size() + " 条聊天 · "
                + repository.getFocusHistory().size() + " 条专注记录";
        if (repository.getLastLoadError() != null) status += "\n数据恢复提示：" + repository.getLastLoadError();
        card.addView(UiKit.statusLine(this, status, repository.getLastLoadError() != null
                ? UiKit.warning(this) : ACCENT), marginTop(lp(-1, -2), dp(12)));
        Button export = UiKit.primaryButton(this, "导出数据");
        Button importData = UiKit.secondaryButton(this, "导入数据");
        card.addView(buttonPair(export, importData), marginTop(lp(-1, dp(48)), dp(12)));
        export.setOnClickListener(v -> exportLauncher.launch("time-assistant-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", LOCALE).format(new Date()) + ".json"));
        importData.setOnClickListener(v -> importLauncher.launch(new String[]{"application/json", "text/plain"}));
        LinearLayout danger = settingsListGroup(section, "危险操作");
        UiKit.addRows(danger, 52,
                UiKit.dangerRow(this, "清除聊天记录", 0, () -> new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("清除聊天记录？")
                        .setMessage("任务、日程和专注记录不会删除。")
                        .setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> repository.clearChatMessages()).show()),
                UiKit.dangerRow(this, "清除全部本地数据", 0, this::confirmClearAllData));
        return section;
    }

    private void confirmClearAllData() {
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("清除全部本地数据？")
                .setMessage("将删除所有任务、日程、聊天、专注历史、偏好、配对信息和 API 配置。")
                .setNegativeButton("取消", null).setPositiveButton("继续", (first, one) ->
                        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("再次确认")
                                .setMessage("此操作不可自动恢复。建议先导出数据。确定继续吗？")
                                .setNegativeButton("返回", null).setPositiveButton("永久清除", (second, two) -> {
                                    beforeAiConnectionChange(() -> {
                                        if (deviceSyncManager != null) deviceSyncManager.stop();
                                        showMessage("正在清除本地数据…");
                                        attachmentExecutor.execute(() -> {
                                            try {
                                                appGraph.ai.clearAllLocal();
                                                secureApiKeyStore.clear();
                                                mainHandler.post(() -> {
                                                    repository.clearAll();
                                                    localIntegrationSettings.reset();
                                                    ProactiveScheduleCoordinator.rescheduleAll(this);
                                                    localIntelligenceStore.clearAll(null);
                                                    initializeDeviceSync();
                                                    showMessage("全部本地数据已清除");
                                                    refreshSettingsIfVisible();
                                                });
                                            } catch (Exception exception) {
                                                mainHandler.post(() -> showMessage("清除失败，请重试"));
                                            }
                                        });
                                    });
                                }).show()).show();
    }












    protected void openSettings() {
        settingsVisible = true;
        focusSettingsVisible = false;
        View page = buildSettingsPage();
        UiKit.polish(page);
        com.hawson.timeassistant.design.ContentTransition.replace(pageContainer, page, 1, true);
        updateBottomNavigationVisibility();
    }

    private void openAiSettings() {
        settingsCategory = SETTINGS_AI;
        openSettings();
    }

    protected void showTaskDetails(String taskId) {
        TaskItem task = repository == null ? null : repository.getTask(taskId);
        if (task == null) { showMessage("任务不存在或已删除"); return; }
        if (!task.requiresTimeArrangement() && !task.hasCompleteTimeArrangement()) {
            showTodoTaskDialog(task);
            return;
        }
        final long[] plannedDateAt = {task.getPlannedDateAt() > 0 ? task.getPlannedDateAt()
                : task.getScheduledStartAt() > 0 ? task.getScheduledStartAt()
                : task.getScheduledEndAt() > 0 ? task.getScheduledEndAt() : task.getDueAt()};
        if (plannedDateAt[0] > 0) plannedDateAt[0] = startOfDay(plannedDateAt[0]);
        final long[] startAt = {task.getScheduledStartAt()};
        final long[] endAt = {task.getScheduledEndAt()};

        LinearLayout form = column(); form.setPadding(dp(20), dp(4), dp(20), dp(16));
        TextView status = label(statusLabel(task.getStatus()), 12, statusColor(task.getStatus()), Typeface.BOLD);
        status.setGravity(Gravity.CENTER); status.setBackground(rounded(statusBackground(task.getStatus()), UiKit.RADIUS_TAG));
        form.addView(status, lp(-2, -2));
        String statusTime = taskStatusTimeSummary(task);
        if (!statusTime.isEmpty()) {
            form.addView(label(statusTime, 12, TEXT_MUTED, Typeface.NORMAL),
                    marginTop(lp(-1, -2), dp(6)));
        }
        LinearLayout editorCategoryBar = row();
        editorCategoryBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        editorCategoryBar.setBackground(rounded(NEUTRAL, UiKit.RADIUS_PILL));
        String[] editorCategoryLabels = {"基本", "时间", "操作"};
        TextView[] editorCategoryButtons = new TextView[editorCategoryLabels.length];
        for (int index = 0; index < editorCategoryLabels.length; index++) {
            TextView category = label(editorCategoryLabels[index], 13, TEXT_MUTED, Typeface.BOLD);
            category.setGravity(Gravity.CENTER);
            category.setClickable(true);
            category.setFocusable(true);
            editorCategoryButtons[index] = category;
            category.setMinHeight(dp(48));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (index > 0) params.setMarginStart(dp(4));
            editorCategoryBar.addView(category, params);
        }
        form.addView(editorCategoryBar, marginTop(lp(-1, -2), dp(14)));
        LinearLayout basicSection = column();
        LinearLayout timeSection = column();
        LinearLayout actionSection = column();
        form.addView(basicSection, lp(-1, -2));
        form.addView(timeSection, lp(-1, -2));
        form.addView(actionSection, lp(-1, -2));
        EditText title = edit("任务标题", task.getTitle(), false);
        EditText description = edit("备注", task.getDescription(), false);
        description.setSingleLine(false); description.setMinLines(2);
        SwitchCompat requiresTime = new SwitchCompat(this);
        requiresTime.setText("需要安排");
        requiresTime.setTextColor(TEXT);
        requiresTime.setTextSize(13);
        requiresTime.setChecked(task.requiresTimeArrangement());
        requiresTime.setGravity(Gravity.CENTER_VERTICAL);
        title.setMinimumHeight(dp(48));
        requiresTime.setMinimumHeight(dp(48));
        basicSection.addView(title, marginTop(lp(-1, -2), dp(12)));
        basicSection.addView(requiresTime, marginTop(lp(-1, -2), dp(8)));
        com.hawson.timeassistant.ui.task.TaskDateTimeFields dateTimes =
                new com.hawson.timeassistant.ui.task.TaskDateTimeFields(
                        this, plannedDateAt, startAt, endAt, true);
        timeSection.addView(dateTimes, lp(-1, -2));
        com.hawson.timeassistant.design.DisclosureSection notes =
                new com.hawson.timeassistant.design.DisclosureSection(this, "备注",
                        !valueOrEmpty(task.getDescription()).isEmpty());
        notes.content.addView(description, marginTop(lp(-1, -2), dp(8)));
        timeSection.addView(notes, lp(-1, -2));

        actionSection.addView(label("任务操作", 13, TEXT, Typeface.BOLD),
                verticalMargins(lp(-2, -2), dp(16), dp(8)));
        LinearLayout quickOne = row();
        Button complete = button(taskPrimaryActionLabel(task), Color.WHITE, ACCENT);
        Button postpone = button("延期处理", WARNING, WARNING_SOFT);
        complete.setMinimumHeight(dp(48));
        postpone.setMinimumHeight(dp(48));
        quickOne.addView(complete, new LinearLayout.LayoutParams(0, -2, 1));
        quickOne.addView(postpone, marginLeft(new LinearLayout.LayoutParams(0, -2, 1), dp(6)));
        actionSection.addView(quickOne);
        LinearLayout quickTwo = row();
        Button removeSchedule = button("仅移除安排", TEXT_MUTED, NEUTRAL);
        Button delete = button("删除任务", ERROR, ERROR_SOFT);
        removeSchedule.setMinimumHeight(dp(48));
        delete.setMinimumHeight(dp(48));
        quickTwo.addView(removeSchedule, new LinearLayout.LayoutParams(0, -2, 1));
        quickTwo.addView(delete, marginLeft(new LinearLayout.LayoutParams(0, -2, 1), dp(6)));
        actionSection.addView(quickTwo, marginTop(lp(-1, -2), dp(8)));

        final int[] editorCategory = {0};
        editorCategoryButtons[0].setText("任务信息");
        editorCategoryButtons[1].setVisibility(View.GONE);
        editorCategoryButtons[2].setText("更多操作");
        Runnable refreshEditorCategory = () -> {
            basicSection.setVisibility(editorCategory[0] != 2 ? View.VISIBLE : View.GONE);
            timeSection.setVisibility(editorCategory[0] != 2 ? View.VISIBLE : View.GONE);
            actionSection.setVisibility(editorCategory[0] == 2 ? View.VISIBLE : View.GONE);
            for (int index = 0; index < editorCategoryButtons.length; index++) {
                boolean selected = editorCategory[0] == index;
                editorCategoryButtons[index].setTextColor(selected ? PRIMARY : TEXT_MUTED);
                editorCategoryButtons[index].setBackground(selected
                        ? rounded(SURFACE, UiKit.RADIUS_PILL) : null);
                ViewCompat.setStateDescription(editorCategoryButtons[index],
                        selected ? "已选择" : null);
            }
        };
        for (int index = 0; index < editorCategoryButtons.length; index++) {
            final int category = index;
            editorCategoryButtons[index].setOnClickListener(view -> {
                editorCategory[0] = category;
                refreshEditorCategory.run();
            });
            applyPressFeedback(editorCategoryButtons[index]);
        }
        refreshEditorCategory.run();

        ScrollView scroll = new ScrollView(this); scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("任务详情").setView(scroll)
                .setNegativeButton("关闭", null).setPositiveButton("保存修改", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = title.getText().toString().trim();
                if (name.isEmpty()) {
                    editorCategory[0] = 0;
                    refreshEditorCategory.run();
                    title.setError("标题不能为空");
                    title.requestFocus();
                    return;
                }
                if (!dateTimes.validate(false)) {
                    editorCategory[0] = 0;
                    refreshEditorCategory.run();
                    return;
                }
                try {
                    TaskItem changed = repository.getTask(taskId);
                    if (changed == null) throw new IllegalStateException("任务已不存在");
                    changed.setTitle(name)
                            .setDescription(description.getText().toString().trim())
                            .setRequiresTimeArrangement(requiresTime.isChecked())
                            .setDueAt(plannedDateAt[0] > 0 ? endOfDay(plannedDateAt[0]) : 0)
                            .setScheduleFields(plannedDateAt[0], startAt[0], endAt[0],
                                    task.isLocked() && startAt[0] > 0 && endAt[0] > 0);
                    repository.upsertTask(changed); dialog.dismiss(); showMessage("任务已更新");
                } catch (RuntimeException exception) { showMessage("保存失败：" + safeError(exception)); }
            });
            complete.setOnClickListener(v -> { dialog.dismiss(); performTaskPrimaryAction(taskId); });
            postpone.setOnClickListener(v -> { dialog.dismiss(); showPostponeOptions(taskId); });
            removeSchedule.setOnClickListener(v -> {
                new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                        .setTitle("仅移除时间安排？")
                        .setMessage("任务会保留并回到待办，标题、备注和截止日期不会删除。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确认移除", (confirmDialog, which) -> {
                            TaskItem changed = repository.getTask(taskId);
                            if (changed != null) {
                                changed.clearSchedule().setRequiresTimeArrangement(false);
                                repository.upsertTask(changed);
                            }
                            dialog.dismiss();
                            showMessage("已移除时间安排，任务仍保留");
                        })
                        .show();
            });
            delete.setOnClickListener(v -> { dialog.dismiss(); confirmDeleteTask(taskId); });
        });
        UiKit.polish(form);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        com.hawson.timeassistant.design.FormDraftGuard.attach(dialog, form);
    }

    private interface TimeSelectionCallback { void onSelected(long value); }

    private void pickDateOnly(long initial, TimeSelectionCallback callback) {
        Calendar value = Calendar.getInstance();
        if (initial > 0) value.setTimeInMillis(initial);
        new DatePickerDialog(this, (view, year, month, day) -> {
            value.set(Calendar.YEAR, year);
            value.set(Calendar.MONTH, month);
            value.set(Calendar.DAY_OF_MONTH, day);
            callback.onSelected(startOfDay(value.getTimeInMillis()));
        }, value.get(Calendar.YEAR), value.get(Calendar.MONTH), value.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickOptionalTaskTime(long plannedDateAt, long initial,
                                      TimeSelectionCallback callback) {
        if (plannedDateAt <= 0) {
            showMessage("请先选择日期");
            return;
        }
        Calendar clock = Calendar.getInstance();
        if (initial > 0) clock.setTimeInMillis(initial);
        new TimePickerDialog(this, (view, hour, minute) -> {
            Calendar result = Calendar.getInstance();
            result.setTimeInMillis(plannedDateAt);
            result.set(Calendar.HOUR_OF_DAY, hour);
            result.set(Calendar.MINUTE, minute);
            result.set(Calendar.SECOND, 0);
            result.set(Calendar.MILLISECOND, 0);
            callback.onSelected(result.getTimeInMillis());
        }, clock.get(Calendar.HOUR_OF_DAY), clock.get(Calendar.MINUTE), true).show();
    }

    private void styleOptionalTimeButton(TextView button, boolean selected) {
        button.setTextColor(selected ? PRIMARY : TEXT_MUTED);
        button.setBackground(selected ? rounded(PRIMARY_SOFT, UiKit.RADIUS_CONTAINER)
                : outlined(SURFACE, BORDER, UiKit.RADIUS_CONTAINER, 1));
    }

    private long moveTimeToDate(long timeAt, long dateAt) {
        Calendar source = Calendar.getInstance();
        source.setTimeInMillis(timeAt);
        Calendar result = Calendar.getInstance();
        result.setTimeInMillis(dateAt);
        result.set(Calendar.HOUR_OF_DAY, source.get(Calendar.HOUR_OF_DAY));
        result.set(Calendar.MINUTE, source.get(Calendar.MINUTE));
        result.set(Calendar.SECOND, 0);
        result.set(Calendar.MILLISECOND, 0);
        return result.getTimeInMillis();
    }

    private long startOfDay(long value) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(value);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private long endOfDay(long value) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(startOfDay(value));
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        return calendar.getTimeInMillis() - 1;
    }

    private String formatDateOnly(long value) {
        return new SimpleDateFormat("M月d日 EEEE", LOCALE).format(new Date(value));
    }

    private void showPostponeOptions(String taskId) {
        String[] options = {"请 AI 拆分任务", "移至待安排", "保留原时间并稍后处理",
                "降低优先级并待安排", "取消任务"};
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("如何处理延期任务？")
                .setItems(options, (dialog, which) -> {
                    TaskItem task = repository.getTask(taskId); if (task == null) return;
                    if (which == 0) {
                        if (!isAiConfigured()) { openAiSettings(); return; }
                        showPage(PAGE_CHAT);
                        mainHandler.postDelayed(() -> submitMessage(
                                "请把任务“" + task.getTitle() + "”（taskId=" + task.getId()
                                        + "）拆成可执行子任务，先给出确认提案。"), 100);
                        return;
                    }
                    String undoSnapshot = which == 4 ? repository.exportJson(false) : null;
                    if (which == 1) task.clearSchedule().setStatus(TaskItem.Status.NEEDS_RESCHEDULING);
                    else if (which == 2) task.setStatus(TaskItem.Status.NEEDS_RESCHEDULING);
                    else if (which == 3) task.clearSchedule().setPriority(Math.max(0, task.getPriority() - 1)).setStatus(TaskItem.Status.NEEDS_RESCHEDULING);
                    else task.setStatus(TaskItem.Status.CANCELLED);
                    repository.upsertTask(task);
                    if (which == 4) {
                        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                                .setTitle("任务已取消")
                                .setMessage("如有误操作，可以立即撤销。")
                                .setNegativeButton("关闭", null)
                                .setPositiveButton("撤销取消", (undoDialog, undoWhich) -> {
                                    try {
                                        repository.importJson(undoSnapshot, AppRepository.ImportMode.REPLACE);
                                        showMessage("已恢复任务");
                                    } catch (RuntimeException error) {
                                        showMessage("撤销失败：" + safeError(error));
                                    }
                                })
                                .show();
                    } else {
                        showMessage("延期任务已处理");
                    }
                }).show();
    }

    private void confirmDeleteTask(String taskId) {
        TaskItem task = repository.getTask(taskId); if (task == null) return;
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("删除“" + task.getTitle() + "”？")
                .setMessage("任务、父子关系与当前专注关联会被删除。此操作执行后可立即撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", (dialog, which) -> {
                    String snapshot = repository.exportJson(false);
                    repository.deleteTask(taskId);
                    AlertDialog undo = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this).setTitle("任务已删除")
                            .setMessage("需要恢复吗？")
                            .setNegativeButton("关闭", null)
                            .setPositiveButton("撤销删除", (d, w) -> {
                                try { repository.importJson(snapshot, AppRepository.ImportMode.REPLACE); showMessage("已恢复任务"); }
                                catch (RuntimeException error) { showMessage("撤销失败：" + safeError(error)); }
                            }).create();
                    undo.show();
                }).show();
    }

    protected void closeSettings() {
        if (settingsDraft != null && settingsDraft.dirty()) {
            settingsDraft.leave(this::closeSettings);
            return;
        }
        if (aiSettingsFeature != null && settingsVisible && !focusSettingsVisible) {
            aiSettingsFeature.leave(this::finishClosingSettings);
            return;
        }
        finishClosingSettings();
    }

    private void finishClosingSettings() {
        settingsVisible = false;
        focusSettingsVisible = false;
        settingsCategory = -1;
        aiSettingsFeature = null;
        settingsDraft = null;
        animateNextPageChange = true;
        renderCurrentPage();
    }

    protected LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    protected LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    protected TextView label(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    protected EditText edit(String hint, String value, boolean secret) {
        EditText field = new EditText(this);
        field.setText(value == null ? "" : value);
        field.setHint(hint);
        field.setTextSize(15);
        field.setTextColor(TEXT);
        field.setHintTextColor(TEXT_MUTED);
        field.setSingleLine(true);
        field.setPadding(dp(15), 0, dp(15), 0);
        UiKit.input(field);
        if (secret) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return field;
    }

    protected Button button(String value, int foreground, int background) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setTextColor(foreground == Color.WHITE && (background == PRIMARY || background == ACCENT)
                ? ContextCompat.getColor(this, R.color.on_brand) : foreground);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        button.setBackground(rounded(background, UiKit.RADIUS_CONTAINER));
        button.setStateListAnimator(null);
        button.setLetterSpacing(0);
        applyPressFeedback(button);
        return button;
    }

    protected LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    protected <T extends ViewGroup.MarginLayoutParams> T marginTop(T params, int value) {
        params.topMargin = value; return params;
    }

    protected <T extends ViewGroup.MarginLayoutParams> T marginBottom(T params, int value) {
        params.bottomMargin = value; return params;
    }

    protected <T extends ViewGroup.MarginLayoutParams> T marginLeft(T params, int value) {
        params.leftMargin = value; return params;
    }

    protected <T extends ViewGroup.MarginLayoutParams> T marginRight(T params, int value) {
        params.rightMargin = value; return params;
    }

    protected <T extends ViewGroup.MarginLayoutParams> T marginAll(T params, int value) {
        params.setMargins(value, value, value, value); return params;
    }

    protected <T extends ViewGroup.MarginLayoutParams> T verticalMargins(T params, int top, int bottom) {
        params.topMargin = top; params.bottomMargin = bottom; return params;
    }

    protected GradientDrawable rounded(int color, float radiusDp) {
        return UiKit.background(this, color, 0, radiusDp);
    }

    protected GradientDrawable outlined(int color, int strokeColor, float radiusDp, int strokeWidthDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(strokeWidthDp), strokeColor);
        return drawable;
    }

    protected int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    protected String valueOrEmpty(String value) { return value == null ? "" : value; }

    protected String formatDuration(int minutes) {
        if (minutes <= 0) return "0 分钟";
        if (minutes < 60) return minutes + " 分钟";
        int hours = minutes / 60;
        int rest = minutes % 60;
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分钟";
    }

    private String formatCountdown(long remainingMillis) {
        if (remainingMillis <= 0) return "提醒时间已到";
        long totalSeconds = (remainingMillis + 999L) / 1_000L;
        long days = totalSeconds / 86_400L;
        long hours = totalSeconds % 86_400L / 3_600L;
        long minutes = totalSeconds % 3_600L / 60L;
        long seconds = totalSeconds % 60L;
        if (days > 0) {
            return String.format(LOCALE, "倒计时 · %d天 %02d:%02d:%02d",
                    days, hours, minutes, seconds);
        }
        return String.format(LOCALE, "倒计时 · %02d:%02d:%02d", hours, minutes, seconds);
    }

    protected String formatDateTime(long millis) {
        if (millis <= 0) return "未安排";
        return new SimpleDateFormat("M月d日 EEE HH:mm", LOCALE).format(new Date(millis));
    }

    protected String formatTime(long millis) {
        if (millis <= 0) return "未安排";
        return new SimpleDateFormat("HH:mm", LOCALE).format(new Date(millis));
    }

    protected boolean sameDay(long first, long second) {
        Calendar a = Calendar.getInstance(); a.setTimeInMillis(first);
        Calendar b = Calendar.getInstance(); b.setTimeInMillis(second);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    protected String statusLabel(TaskItem.Status status) {
        if (status == null) return "待处理";
        switch (status) {
            case SCHEDULED: return "待办";
            case IN_PROGRESS: return "进行中";
            case AWAITING_CONFIRMATION: return "待确认";
            case COMPLETED: return "已完成";
            case NEEDS_RESCHEDULING: return "需要重新安排";
            case CANCELLED: return "已取消";
            default: return "待处理";
        }
    }

    protected int statusColor(TaskItem.Status status) {
        if (status == TaskItem.Status.COMPLETED || status == TaskItem.Status.CANCELLED) return TEXT_MUTED;
        if (status == TaskItem.Status.IN_PROGRESS) return ACCENT;
        if (status == TaskItem.Status.AWAITING_CONFIRMATION) return PRIMARY;
        if (status == TaskItem.Status.NEEDS_RESCHEDULING) return WARNING;
        return PRIMARY;
    }

    protected int statusBackground(TaskItem.Status status) {
        if (status == TaskItem.Status.COMPLETED || status == TaskItem.Status.CANCELLED) {
            return NEUTRAL;
        }
        if (status == TaskItem.Status.IN_PROGRESS) return ACCENT_SOFT;
        if (status == TaskItem.Status.AWAITING_CONFIRMATION) return PRIMARY_SOFT;
        if (status == TaskItem.Status.NEEDS_RESCHEDULING) return WARNING_SOFT;
        return PRIMARY_SOFT;
    }

    @SuppressLint("ClickableViewAccessibility")
    protected void applyPressFeedback(View view) {
        MotionSpec.press(view);
    }

    protected void showMessage(String message) {
        if (message == null || message.trim().isEmpty()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void writeExport(Uri uri) {
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("无法打开导出文件");
            output.write(repository.exportJson(false).getBytes(StandardCharsets.UTF_8));
            showMessage("数据已导出（API Key 未包含在文件中）");
        } catch (Exception exception) {
            showMessage("导出失败：" + safeError(exception));
        }
    }

    private void readImport(Uri uri) {
        if (uri == null) return;
        try (InputStream input = getContentResolver().openInputStream(uri);
             BufferedReader reader = input == null ? null
                     : new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            if (reader == null) throw new IllegalStateException("无法打开导入文件");
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) content.append(line);
            new com.hawson.timeassistant.design.SoftAlertDialogBuilder(this)
                    .setTitle("导入数据")
                    .setMessage("选择替换本机数据，或合并到现有数据。API Key 不会被导入。")
                    .setNegativeButton("取消", null)
                    .setNeutralButton("合并", (dialog, which) -> importContent(content.toString(), AppRepository.ImportMode.MERGE))
                    .setPositiveButton("替换", (dialog, which) -> importContent(content.toString(), AppRepository.ImportMode.REPLACE))
                    .show();
        } catch (Exception exception) {
            showMessage("导入失败：" + safeError(exception));
        }
    }

    private void importContent(String content, AppRepository.ImportMode mode) {
        try {
            repository.importJson(content, mode);
            showMessage("数据导入成功");
        } catch (RuntimeException exception) {
            showMessage("导入失败：" + safeError(exception));
        }
    }

    private static final class CountdownCardBinding {
        final String taskId;
        final TextView value;

        CountdownCardBinding(String taskId, TextView value) {
            this.taskId = taskId;
            this.value = value;
        }
    }

    private static final class RecurrenceRuleDraft {
        RecurringTaskGroup.Unit unit = RecurringTaskGroup.Unit.WEEKS;
        int interval = 1;
        boolean workdaysOnly;
        final LinkedHashSet<Integer> weekdays = new LinkedHashSet<>();
        int monthlyOrdinal;
        int monthlyWeekday;
        long endsAt;
        int maxOccurrences;

        static RecurrenceRuleDraft from(RecurringTaskGroup group) {
            RecurrenceRuleDraft draft = new RecurrenceRuleDraft();
            if (group == null) return draft;
            draft.unit = group.getUnit();
            draft.interval = group.getInterval();
            draft.workdaysOnly = group.isWorkdaysOnly();
            draft.weekdays.addAll(group.getWeekdays());
            draft.monthlyOrdinal = group.getMonthlyOrdinal();
            draft.monthlyWeekday = group.getMonthlyWeekday();
            draft.endsAt = group.getEndsAt();
            draft.maxOccurrences = group.getMaxOccurrences();
            return draft;
        }

        int patternIndex() {
            if (workdaysOnly) return 3;
            if (monthlyOrdinal > 0) return 5;
            if (!weekdays.isEmpty()) return 4;
            if (unit == RecurringTaskGroup.Unit.DAYS) return 0;
            if (unit == RecurringTaskGroup.Unit.MONTHS) return 2;
            return 1;
        }

        RecurrenceRuleDraft copy() {
            RecurrenceRuleDraft result = new RecurrenceRuleDraft();
            result.copyFrom(this);
            return result;
        }

        void copyFrom(RecurrenceRuleDraft source) {
            unit = source.unit;
            interval = source.interval;
            workdaysOnly = source.workdaysOnly;
            weekdays.clear();
            weekdays.addAll(source.weekdays);
            monthlyOrdinal = source.monthlyOrdinal;
            monthlyWeekday = source.monthlyWeekday;
            endsAt = source.endsAt;
            maxOccurrences = source.maxOccurrences;
        }
    }

    private enum RecurrenceEditScope {
        ONLY_THIS, THIS_AND_FOLLOWING, ENTIRE_SERIES
    }

    private static final class DayScheduleEntry {
        final TaskItem task;
        final RecurringTaskGroup group;
        final RecurringOccurrenceOverride override;
        final long originalOccurrenceAt;
        final long startAt;
        final long endAt;
        final String title;
        final String description;

        private DayScheduleEntry(TaskItem task, RecurringTaskGroup group,
                                 RecurringOccurrenceOverride override,
                                 long originalOccurrenceAt, long startAt, long endAt,
                                 String title, String description) {
            this.task = task;
            this.group = group;
            this.override = override;
            this.originalOccurrenceAt = originalOccurrenceAt;
            this.startAt = startAt;
            this.endAt = endAt;
            this.title = title;
            this.description = description;
        }

        static DayScheduleEntry forTask(TaskItem task) {
            return new DayScheduleEntry(task, null, null, 0,
                    task.getScheduledStartAt(), task.getScheduledEndAt(),
                    task.getTitle(), task.getDescription());
        }

        static DayScheduleEntry forRecurring(RecurringTaskGroup group, long originalOccurrenceAt,
                                             RecurringOccurrenceOverride override) {
            long startAt = override == null ? originalOccurrenceAt
                    : override.effectiveStartAt(originalOccurrenceAt);
            long endAt = override == null ? Math.addExact(startAt, group.getDurationMillis())
                    : override.effectiveEndAt(startAt, group.getDurationMillis());
            return new DayScheduleEntry(null, group, override, originalOccurrenceAt,
                    startAt, endAt, override == null ? group.getTitle()
                    : override.effectiveTitle(group), override == null ? group.getDescription()
                    : override.effectiveDescription(group));
        }
    }

    private static final class WelcomeShortcut {
        final String label;
        final Runnable action;

        WelcomeShortcut(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private static final class LastAiRequest {
        final String userMessageId;
        final String message;
        final List<AiClient.Attachment> attachments;

        LastAiRequest(String userMessageId, String message,
                      List<AiClient.Attachment> attachments) {
            this.userMessageId = userMessageId;
            this.message = message == null ? "" : message;
            this.attachments = Collections.unmodifiableList(new ArrayList<>(attachments));
        }
    }

    protected String safeError(Throwable throwable) {
        if (throwable == null) return "未知错误";
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }
}
