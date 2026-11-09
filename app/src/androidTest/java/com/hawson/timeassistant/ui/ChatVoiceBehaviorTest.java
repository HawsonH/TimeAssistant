package com.hawson.timeassistant.ui;

import android.content.Context;
import android.os.Build;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.*;
import com.hawson.timeassistant.ai.AiAuxiliaryServices;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.ChatMessage;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ChatVoiceBehaviorTest {
    private Context context;
    private AppRepository repository;
    private AiServices ai;
    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext(); assertTrue(context.getPackageName().endsWith(".qa"));
        com.hawson.timeassistant.DeviceFixtureSafety.requireIsolatedTarget(context);
        repository = AppRepository.get(context); repository.clearAll();
        ai = ((TimeAssistantApplication) context).getAiServices();
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1).putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putInt("last_page", 0).putBoolean("focus_lockscreen_settings_confirmed_v2", true).commit();
    }
    @After public void clean() {
        if (ai != null) new AiAuxiliaryServices(context, ai).removeQuota("fixture-quota");
        if (repository != null) repository.clearAll();
    }
    private void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(240); }
    @Test public void todayActionDisappearsAfterDateReturn() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> a.showPage(1)); idle();
            scenario.onActivity(a -> a.findViewById(R.id.nextDateButton).performClick()); idle();
            scenario.onActivity(a -> {
                assertEquals(View.VISIBLE, a.findViewById(R.id.todayButton).getVisibility());
                assertEquals("回到今天", ((TextView) a.findViewById(R.id.todayButton)).getText().toString());
                a.findViewById(R.id.todayButton).performClick();
            }); idle();
            scenario.onActivity(a -> {
                assertEquals(View.GONE, a.findViewById(R.id.todayButton).getVisibility());
                assertTrue(((TextView) a.findViewById(R.id.selectedDateButton)).getText().toString().startsWith("今天"));
            });
        }
    }
    @Test public void repeatedScheduleTabDoesNotChangeDateAndRapidSwitchSettles() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> a.showPage(1)); idle();
            scenario.onActivity(a -> a.findViewById(R.id.nextDateButton).performClick()); idle();
            long[] date = {0}; scenario.onActivity(a -> { date[0] = a.selectedDate.getTimeInMillis(); a.findViewById(R.id.scheduleTabButton).performClick(); }); idle();
            scenario.onActivity(a -> {
                assertEquals(date[0], a.selectedDate.getTimeInMillis());
                a.findViewById(R.id.todoTabButton).performClick();
            }); android.os.SystemClock.sleep(45);
            scenario.onActivity(a -> a.findViewById(R.id.scheduleTabButton).performClick()); idle();
            scenario.onActivity(a -> { assertEquals(0, a.scheduleMode); assertEquals(1, a.pageContainer.getChildCount()); assertEquals(1f, a.findViewById(R.id.scheduleScroll).getAlpha(), 0.01f); });
        }
    }
    @Test public void bothRolesHaveBubblesAndVoiceEntry() {
        repository.addChatMessage(new ChatMessage(ChatMessage.Role.USER, "明天下午安排一小时复习"));
        repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, "请告诉我你希望开始的时间。"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle(); scenario.onActivity(a -> {
                View user = a.pageContainer.findViewWithTag("user-bubble"); View assistant = a.pageContainer.findViewWithTag("assistant-bubble");
                assertNotNull(user); assertNotNull(assistant); assertNotNull(user.getBackground());
                assertTrue(assistant.getParent() instanceof ChatBubbleLayout);
                assertNotNull(((View) assistant.getParent()).getBackground());
                assertEquals("语音输入", a.findViewById(R.id.voiceButton).getContentDescription());
            });
        }
    }
    @Test public void voiceDraftInsertionPreservesSurroundingText() {
        VoiceDraft draft = new VoiceDraft("周三复习", 2, 2); assertEquals("周三下午复习", draft.insert("下午"));
        assertFalse(draft.unchanged("周四复习")); assertTrue(draft.unchanged("周三复习"));
        assertEquals("新内容", new VoiceDraft("旧内容", 0, 3).insert("新内容"));
    }
    @Test public void usageOnlyFinishesOnceAndKeepsUnknownTokens() {
        String ref = "fixture:" + UUID.randomUUID();
        AiProfile p = new AiProfile("fixture", "fixture", AiProfile.Kind.RELAY, AiProfile.Protocol.RESPONSES, "https://example.com/v1", "fixture", ref);
        AiCallObserver.Call call = ai.usage.begin(p, "chat"); call.usage(new AiCallObserver.Usage(7L, 2L, null)); call.finish("success"); call.finish("failed");
        AiCallObserver.Call missing = ai.usage.begin(p, "chat"); missing.finish("cancelled");
        String summary = ai.usage.summary(0, ref); assertTrue(summary.contains("2 次")); assertTrue(summary.contains("失败 0")); assertTrue(summary.contains("输入 Token：7")); assertTrue(summary.contains("1 次未返回"));
    }
    @Test public void quotaMissingWindowsDoNotBecomeZero() throws Exception {
        AiQuotaSnapshot value = com.hawson.timeassistant.quota.sub2api.Sub2ApiQuotaCodec.decode(new JSONObject("{\"plan_type\":\"plus\",\"rate_limit\":{\"primary_window\":null}}"), 0);
        assertTrue(value.limits.get(0).windows.isEmpty());
        AiQuotaSnapshot full = com.hawson.timeassistant.quota.sub2api.Sub2ApiQuotaCodec.decode(new JSONObject("{\"rate_limit\":{\"primary_window\":{\"used_percent\":32,\"limit_window_seconds\":18000,\"reset_at\":1800000000}}}"), 0);
        assertEquals(32, full.limits.get(0).windows.get(0).usedPercent, 0);
        assertEquals(Long.valueOf(1800000000000L), full.limits.get(0).windows.get(0).resetAt);
    }
    @Test public void quotaTransportUsesIndependentKeyAndRetainsCacheOnFailure() throws Exception {
        AiAuxiliaryServices services = new AiAuxiliaryServices(context, ai); String reference = "fixture-quota";
        try (Fixture fixture = new Fixture("{\"data\":{\"account_id\":\"demo\",\"plan_type\":\"plus\",\"rate_limit\":{\"primary_window\":{\"used_percent\":23}}}}", 200)) {
            String root = fixture.url(); services.approve(root); services.put("quota." + reference + ".url", root); services.put("quota." + reference + ".id", "42");
            services.secret("aux:quota:" + reference, "fixture-quota-key");
            JSONObject result = services.quota(reference, new AiCancellation()); services.cacheQuota(reference, result);
            fixture.await(); assertTrue(fixture.request.contains("/api/v1/admin/openai/accounts/42/quota")); assertTrue(fixture.request.toLowerCase(Locale.ROOT).contains("x-api-key: fixture-quota-key"));
            assertEquals("demo", services.cachedQuota(reference).getString("account_id"));
        }
        try (Fixture fixture = new Fixture("{}", 503)) {
            services.approve(fixture.url()); services.put("quota." + reference + ".url", fixture.url());
            try { services.quota(reference, new AiCancellation()); fail(); } catch (IOException expected) { }
            assertEquals("demo", services.cachedQuota(reference).getString("account_id"));
        }
    }
    @Test public void speechMultipartReturnsTextAndRecordsSeparateUsage() throws Exception {
        AiAuxiliaryServices services = new AiAuxiliaryServices(context, ai);
        try (Fixture fixture = new Fixture("{\"text\":\"明天下午复习数学\",\"usage\":{\"input_tokens\":8,\"output_tokens\":4}}", 200)) {
            services.approve(fixture.url()); services.put("speech.url", fixture.url()); services.put("speech.model", "fixture-transcribe"); services.secret("aux:speech", "fixture-speech-key");
            File file = File.createTempFile("fixture-voice", ".m4a", context.getCacheDir());
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[1024]); }
            try { assertEquals("明天下午复习数学", services.transcribe(file, new AiCancellation())); }
            finally { file.delete(); }
            fixture.await(); assertTrue(fixture.request.contains("/audio/transcriptions")); assertTrue(fixture.body.contains("fixture-transcribe")); assertTrue(fixture.body.contains("filename=\"speech.m4a\""));
        } finally { services.put("speech.engine", "system"); services.put("speech.url", "https://api.openai.com/v1"); services.put("speech.model", "gpt-transcribe"); services.clearSecret("aux:speech"); }
    }
    @Test public void processingAnimationStopsInBackgroundAndRespectsReducedMode() throws Exception {
        context.getSharedPreferences("time_assistant_appearance", 0).edit().putString("motion", "FULL").commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle(); scenario.onActivity(a -> { a.chatBusy = true; a.renderCurrentPage(); }); idle();
            String[] first = {""};
            scenario.onActivity(a -> first[0] = ((TextView) reflect(a, "aiThinkingText")).getText().toString());
            android.os.SystemClock.sleep(440);
            scenario.onActivity(a -> assertNotEquals(first[0], ((TextView) reflect(a, "aiThinkingText")).getText().toString()));
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            String[] stopped = {""}; scenario.onActivity(a -> stopped[0] = ((TextView) reflect(a, "aiThinkingText")).getText().toString());
            android.os.SystemClock.sleep(500);
            scenario.onActivity(a -> assertEquals(stopped[0], ((TextView) reflect(a, "aiThinkingText")).getText().toString()));
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); idle();
            scenario.onActivity(a -> { a.chatBusy = false; a.renderCurrentPage(); }); idle();
        } finally { context.getSharedPreferences("time_assistant_appearance", 0).edit().putString("motion", "REDUCED").commit(); }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle(); scenario.onActivity(a -> { a.chatBusy = true; a.renderCurrentPage(); }); idle();
            scenario.onActivity(a -> assertEquals("正在处理…", ((TextView) reflect(a, "aiThinkingText")).getText().toString()));
            scenario.onActivity(a -> { a.chatBusy = false; a.renderCurrentPage(); }); idle();
        } finally { context.getSharedPreferences("time_assistant_appearance", 0).edit().putString("motion", "FULL").commit(); }
    }
    @Test public void captureNewChatAndInputWalkthrough() throws Exception {
        repository.addChatMessage(new ChatMessage(ChatMessage.Role.USER, "明天下午帮我安排数学复习，保留晚上的阅读时间。"));
        repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, "可以先安排一小时数学复习，再检查晚间阅读是否有冲突。你希望下午几点开始？"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle(); capture("chat-bubbles");
            scenario.onActivity(a -> { a.chatBusy = true; a.renderCurrentPage(); }); idle(); capture("chat-processing");
            scenario.onActivity(a -> { a.chatBusy = false; a.renderCurrentPage(); }); idle();
            AiToolsController[] tool = {null};
            scenario.onActivity(a -> {
                tool[0] = new AiToolsController(a, ai, () -> { });
                assertFalse("Only capture an empty credential form", tool[0].services.hasSecret("aux:speech"));
                tool[0].showSpeechSettings();
                @SuppressWarnings("unchecked") Set<com.google.android.material.bottomsheet.BottomSheetDialog> sheets = (Set<com.google.android.material.bottomsheet.BottomSheetDialog>) reflect(tool[0], "sheets");
                for (com.google.android.material.bottomsheet.BottomSheetDialog sheet : sheets) sheet.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }); idle(); capture("speech-settings");
            scenario.onActivity(a -> {
                @SuppressWarnings("unchecked") Set<com.google.android.material.bottomsheet.BottomSheetDialog> sheets = (Set<com.google.android.material.bottomsheet.BottomSheetDialog>) reflect(tool[0], "sheets");
                for (com.google.android.material.bottomsheet.BottomSheetDialog sheet : sheets) {
                    sheet.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    selectSpinner(sheet.findViewById(android.R.id.content), 1);
                }
            }); idle(); capture("speech-gpt-settings");
            scenario.onActivity(a -> {
                @SuppressWarnings("unchecked") Set<com.google.android.material.bottomsheet.BottomSheetDialog> sheets = (Set<com.google.android.material.bottomsheet.BottomSheetDialog>) reflect(tool[0], "sheets");
                for (com.google.android.material.bottomsheet.BottomSheetDialog sheet : sheets) selectSpinner(sheet.findViewById(android.R.id.content), 2);
            }); idle(); capture("speech-custom-settings");
            scenario.onActivity(a -> tool[0].close()); idle();
            scenario.onActivity(a -> { tool[0] = new AiToolsController(a, ai, () -> { }); tool[0].showAccountsAndUsage(); }); idle(); capture("accounts-usage");
            scenario.onActivity(a -> tool[0].close()); idle();
            scenario.onActivity(a -> { tool[0] = new AiToolsController(a, ai, () -> { }); tool[0].showQuotaSettings(new AiAccountPort.Account("fixture-guide", "演示账号", true)); }); idle();
            scenario.onActivity(a -> {
                @SuppressWarnings("unchecked") Set<com.google.android.material.bottomsheet.BottomSheetDialog> sheets = (Set<com.google.android.material.bottomsheet.BottomSheetDialog>) reflect(tool[0], "sheets");
                for (com.google.android.material.bottomsheet.BottomSheetDialog sheet : sheets) sheet.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }); capture("quota-source");
            scenario.onActivity(a -> tool[0].close()); idle();
            scenario.onActivity(a -> a.showPage(1)); idle();
            scenario.onActivity(a -> a.findViewById(R.id.nextDateButton).performClick()); idle(); capture("schedule-other-date");
            scenario.onActivity(a -> a.findViewById(R.id.todayButton).performClick()); idle(); capture("schedule-today");
            scenario.onActivity(a -> a.findViewById(R.id.todoTabButton).performClick()); idle(); capture("schedule-todos");
        }
    }
    private static boolean selectSpinner(View view, int position) {
        if (view instanceof Spinner) { ((Spinner) view).setSelection(position); return true; }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (selectSpinner(((ViewGroup) view).getChildAt(i), position)) return true;
        return false;
    }
    @Test public void recordingCancelKeepsDraftAndRemovesTemporaryAudio() throws Exception {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(), android.Manifest.permission.RECORD_AUDIO);
        AiAuxiliaryServices services = new AiAuxiliaryServices(context, ai);
        String previous = services.get("speech.engine", "system");
        services.put("speech.engine", "custom"); services.secret("aux:speech", "fixture-not-a-real-key");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle(); scenario.onActivity(a -> { a.chatInput.setText("保留这段草稿"); a.findViewById(R.id.voiceButton).performClick(); }); idle();
            VoiceInputController[] controller = {null}; File[] audio = {null};
            scenario.onActivity(a -> {
                controller[0] = (VoiceInputController) reflect(a, "voiceInput");
                assertTrue((Boolean) reflect(controller[0], "recording"));
                audio[0] = (File) reflect(controller[0], "audio"); assertTrue(audio[0].exists());
            }); capture("voice-recording");
            scenario.onActivity(a -> controller[0].background()); idle();
            scenario.onActivity(a -> { assertEquals("保留这段草稿", a.chatInput.getText().toString()); assertFalse((Boolean) reflect(controller[0], "recording")); });
            assertFalse(audio[0].exists());
        } finally { services.put("speech.engine", previous); services.clearSecret("aux:speech"); }
    }
    @Test public void modelSheetUsesServiceDisplayNameAndOneSelection() throws Exception {
        java.util.concurrent.atomic.AtomicInteger selections = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> com.hawson.timeassistant.design.ModelSelectionSheet.show(a, "演示连接", "model-a", () -> Arrays.asList(
                new com.hawson.timeassistant.design.ModelSelectionSheet.Option("model-a", "GPT 演示模型"),
                new com.hawson.timeassistant.design.ModelSelectionSheet.Option("model-b", "轻量演示模型")), () -> { }, item -> {
                    assertEquals("model-b", item.id); selections.incrementAndGet();
                }, Collections.singletonList(new com.hawson.timeassistant.design.ModelSelectionSheet.Action("账号与用量", () -> { }))));
            idle(); capture("model-picker");
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("轻量演示模型")).perform(androidx.test.espresso.action.ViewActions.click()); idle();
            assertEquals(1, selections.get());
        }
    }
    @Test public void modelSearchFiltersByIdAndClosePreservesSelection() throws Exception {
        java.util.concurrent.atomic.AtomicInteger selections = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> com.hawson.timeassistant.design.ModelSelectionSheet.show(a, "搜索演示", "model-0", () -> {
                List<com.hawson.timeassistant.design.ModelSelectionSheet.Option> values = new ArrayList<>();
                for (int i = 0; i < 8; i++) values.add(new com.hawson.timeassistant.design.ModelSelectionSheet.Option("model-" + i, "演示模型 " + i));
                return values;
            }, () -> { }, option -> selections.incrementAndGet(), Collections.emptyList()));
            idle();
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withHint("搜索模型"))
                .perform(androidx.test.espresso.action.ViewActions.replaceText("model-7"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
            idle(); capture("model-search");
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("演示模型 7"))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("演示模型 1"))
                .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("关闭搜索演示"))
                .perform(androidx.test.espresso.action.ViewActions.click());
            assertEquals(0, selections.get());
        }
    }
    @Test public void usagePeriodHasOneSelectedState() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AiToolsController[] tool = {null};
            scenario.onActivity(a -> { tool[0] = new AiToolsController(a, ai, () -> { }); tool[0].showAccountsAndUsage(); }); idle();
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("本应用统计"))
                .perform(androidx.test.espresso.action.ViewActions.click()); idle();
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("7 天"))
                .perform(androidx.test.espresso.action.ViewActions.click()); idle();
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("7 天"))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isSelected()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("今日"))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(org.hamcrest.Matchers.not(androidx.test.espresso.matcher.ViewMatchers.isSelected())));
            scenario.onActivity(a -> {
                @SuppressWarnings("unchecked") Set<com.google.android.material.bottomsheet.BottomSheetDialog> sheets = (Set<com.google.android.material.bottomsheet.BottomSheetDialog>) reflect(tool[0], "sheets");
                for (com.google.android.material.bottomsheet.BottomSheetDialog sheet : sheets) sheet.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }); capture("usage-periods"); scenario.onActivity(a -> tool[0].close());
        }
    }
    private static Object reflect(Object target, String name) {
        try { java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (NoSuchFieldException missing) {
            try { java.lang.reflect.Field field = TimeAssistantActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (Exception error) { throw new AssertionError(error); }
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private void capture(String name) throws Exception {
        idle(); android.graphics.Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(image);
        File folder = new File(context.getExternalFilesDir(null), "ui-chat-voice"); assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)); }
        image.recycle();
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server; final Thread thread; String request = "", body = ""; volatile Exception failure;
        Fixture(String result, int status) throws Exception {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000); InputStream stream = socket.getInputStream(); StringBuilder headers = new StringBuilder(); int end = 0, c;
                    while ((c = stream.read()) != -1) { headers.append((char)c); end = headers.toString().endsWith("\r\n\r\n") ? 4 : 0; if (end == 4) break; }
                    request = headers.toString();
                    if (request.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
                        ByteArrayOutputStream content = new ByteArrayOutputStream();
                        while (true) { String size = line(stream); int count = Integer.parseInt(size.split(";")[0], 16); if (count == 0) { line(stream); break; } for (int i = 0; i < count; i++) content.write(stream.read()); line(stream); }
                        body = content.toString("UTF-8");
                    }
                    byte[] data = result.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII)); socket.getOutputStream().write(data); socket.getOutputStream().flush();
                } catch (Exception error) { failure = error; }
            }); thread.start();
        }
        static String line(InputStream stream) throws IOException { StringBuilder text = new StringBuilder(); int c; while ((c = stream.read()) != -1 && c != '\n') if (c != '\r') text.append((char)c); return text.toString(); }
        String url() { return "http://127.0.0.1:" + server.getLocalPort(); }
        void await() throws Exception { thread.join(12000); if (failure != null) throw failure; assertFalse(thread.isAlive()); }
        public void close() throws Exception { server.close(); thread.join(1000); }
    }
}
