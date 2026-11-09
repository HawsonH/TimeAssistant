package com.hawson.timeassistant.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.AiServices;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.connections.AiProfile;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.ChatMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.hawson.timeassistant.ui.WorkflowDriver.*;

/** UI-to-HTTP workflows use only a loopback fixture and disposable emulator data. */
@RunWith(AndroidJUnit4.class)
public class AiConversationWorkflowTest {
    private Context context;
    private AppRepository repository;
    private AiServices ai;
    private AiProfile profile;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getPackageName().endsWith(".qa"));
        com.hawson.timeassistant.DeviceFixtureSafety.requireIsolatedTarget(context);
        repository = AppRepository.get(context); repository.clearAll();
        ai = ((TimeAssistantApplication) context).getAiServices();
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putInt("last_page", 0).commit();
    }

    @After public void cleanup() throws Exception {
        ai.cancelAll();
        if (profile != null) ai.profiles.delete(profile.id);
        repository.clearAll();
    }

    private void connect(Fixture fixture) throws Exception {
        String url = "http://127.0.0.1:" + fixture.server.getLocalPort() + "/v1";
        ai.approvePrivateHttp(url);
        profile = ai.profiles.save(new AiProfile("", "本地聊天测试", AiProfile.Kind.RELAY,
                AiProfile.Protocol.CHAT_COMPLETIONS, url, "fixture-model", ""), "fixture-key");
        ai.profiles.activate(profile.id);
    }

    @Test public void sentMessageCreatesProposalOnlyAfterConfirmationAndUndoWorks() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            connect(fixture);
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                fillId(R.id.chatInput, "请安排一次复习"); clickId(R.id.sendButton);
                await(() -> assistantCount() == 1);
                assertTrue(repository.getAllTasks().isEmpty());
                assertEquals(1, fixture.posts.get()); assertTrue(fixture.lastBody.contains("请安排一次复习"));
                androidx.test.espresso.Espresso.closeSoftKeyboard(); idle();
                capture("chat-http-proposal"); click("确认 1 项");
                assertEquals(1, repository.getAllTasks().size());
                assertEquals("本地服务建议的复习", repository.getAllTasks().get(0).getTitle());
                click("撤销"); assertTrue(repository.getAllTasks().isEmpty()); capture("chat-http-undone");
                assertTrue(ai.usage.summary(0, profile.credentialRef).contains("输入 Token：11"));
                fixture.check();
            }
        }
    }

    @Test public void failedRequestRetriesWithoutDuplicatingUserMessage() throws Exception {
        try (Fixture fixture = new Fixture(true, false)) {
            connect(fixture);
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                fillId(R.id.chatInput, "请生成复习建议"); clickId(R.id.sendButton);
                await(() -> visibleText(screen, "重试")); capture("chat-http-error"); click("重试");
                await(() -> assistantCount() == 1);
                assertEquals(2, fixture.posts.get()); assertEquals(1, userCount());
                assertTrue(repository.getAllTasks().isEmpty()); capture("chat-http-retry-result"); fixture.check();
            }
        }
    }

    @Test public void stopDiscardsLateReplyAndNextMessageStillWorks() throws Exception {
        try (Fixture fixture = new Fixture(false, true)) {
            connect(fixture);
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                fillId(R.id.chatInput, "请等待我确认"); clickId(R.id.sendButton);
                assertTrue(fixture.firstRequest.await(8, TimeUnit.SECONDS)); capture("chat-http-processing");
                clickId(R.id.sendButton); screen.onActivity(a -> assertFalse(a.chatBusy));
                fixture.release.countDown(); idle(); assertEquals(0, assistantCount());
                fillId(R.id.chatInput, "重新给出复习建议"); clickId(R.id.sendButton);
                await(() -> assistantCount() == 1);
                assertEquals(2, fixture.posts.get()); assertEquals(2, userCount());
                capture("chat-http-after-stop"); fixture.check();
            }
        }
    }

    @Test public void attachmentCallbackRemoveAndSendRetainDraftAndPayload() throws Exception {
        File file = new File(context.getCacheDir(), "workflow-attachment.txt");
        Files.write(file.toPath(), "fixture-attachment-data".getBytes(StandardCharsets.UTF_8));
        try (Fixture fixture = new Fixture(false, false)) {
            connect(fixture);
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                fillId(R.id.chatInput, "请查看附件并给出建议");
                screen.onActivity(a -> attach(a, Uri.fromFile(file))); idle(); capture("chat-attachment-added");
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers
                        .withContentDescription("移除附件：附件")).perform(androidx.test.espresso.action.ViewActions.click()); idle();
                screen.onActivity(a -> assertEquals("请查看附件并给出建议", a.chatInput.getText().toString()));
                screen.onActivity(a -> attach(a, Uri.fromFile(file))); idle(); clickId(R.id.sendButton);
                await(() -> assistantCount() == 1);
                ChatMessage sent = repository.getChatMessages().stream().filter(m -> m.getRole() == ChatMessage.Role.USER).findFirst().orElseThrow(AssertionError::new);
                assertEquals(1, sent.getAttachments().size());
                assertTrue(fixture.lastBody.contains("Zml4dHVyZS1hdHRhY2htZW50LWRhdGE="));
                capture("chat-attachment-sent"); fixture.check();
            }
        } finally { assertTrue(!file.exists() || file.delete()); }
    }

    private static void attach(TimeAssistantActivity activity, Uri uri) {
        try {
            java.lang.reflect.Method method = TimeAssistantActivity.class.getDeclaredMethod("onAttachmentsSelected", java.util.List.class);
            method.setAccessible(true); method.invoke(activity, Collections.singletonList(uri));
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private long assistantCount() { return repository.getChatMessages().stream().filter(m -> m.getRole() == ChatMessage.Role.ASSISTANT).count(); }
    private long userCount() { return repository.getChatMessages().stream().filter(m -> m.getRole() == ChatMessage.Role.USER).count(); }
    private static boolean visibleText(ActivityScenario<MainActivity> screen, String value) {
        boolean[] visible = {false}; screen.onActivity(a -> visible[0] = text(value) != null); return visible[0];
    }
    private static void await(BooleanSupplier condition) {
        long end = android.os.SystemClock.uptimeMillis() + 12_000;
        while (!condition.getAsBoolean() && android.os.SystemClock.uptimeMillis() < end) idle();
        assertTrue("Expected UI/result was not reached", condition.getAsBoolean()); idle();
    }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger posts = new AtomicInteger();
        final CountDownLatch firstRequest = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile String lastBody = "";
        volatile Throwable failure;
        volatile boolean closed;
        final Thread worker;
        Fixture(boolean failFirst, boolean delayFirst) throws Exception {
            worker = new Thread(() -> {
                while (!closed) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(8_000);
                        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                        ByteArrayOutputStream headers = new ByteArrayOutputStream(); int state = 0, value;
                        while (state < 4 && (value = input.read()) != -1) {
                            headers.write(value);
                            state = value == (state % 2 == 0 ? '\r' : '\n') ? state + 1 : (value == '\r' ? 1 : 0);
                            if (headers.size() > 65536) throw new IOException("Fixture headers too large");
                        }
                        String header = headers.toString("UTF-8"); int length = 0;
                        for (String line : header.split("\r\n")) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                        if (length < 0 || length > 2_000_000) throw new IOException("Fixture body too large");
                        byte[] body = new byte[length]; int offset = 0;
                        while (offset < length) { int read = input.read(body, offset, length - offset); if (read < 0) throw new EOFException(); offset += read; }
                        boolean post = header.startsWith("POST "); int index = post ? posts.incrementAndGet() : 0;
                        if (post) { lastBody = new String(body, StandardCharsets.UTF_8); firstRequest.countDown(); }
                        if (delayFirst && index == 1) release.await(10, TimeUnit.SECONDS);
                        int status = failFirst && index == 1 ? 503 : 200;
                        String plan = "{\"message\":\"本地服务返回的复习建议\",\"needsClarification\":false,\"actions\":[{\"type\":\"create_task\",\"title\":\"本地服务建议的复习\"}]}";
                        String response = post ? status == 503 ? "{\"error\":{\"message\":\"Fixture temporary failure\"}}"
                                : new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", new JSONObject().put("content", plan))))
                                .put("usage", new JSONObject().put("prompt_tokens",11).put("completion_tokens",5)).toString()
                                : "{\"data\":[{\"id\":\"fixture-model\"}]}";
                        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                        OutputStream output = socket.getOutputStream();
                        try {
                            output.write(("HTTP/1.1 " + status + " Fixture\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            output.write(bytes); output.flush();
                        } catch (IOException disconnected) { if (!(delayFirst && index == 1)) throw disconnected; }
                    } catch (Throwable error) { if (!closed) failure = error; }
                }
            }, "chat-workflow-fixture");
            worker.setDaemon(true); worker.start();
        }
        void check() { if (failure != null) throw new AssertionError(failure); }
        @Override public void close() throws Exception { closed = true; release.countDown(); server.close(); worker.join(1500); }
    }
}
