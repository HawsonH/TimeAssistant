package com.hawson.timeassistant.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.hawson.timeassistant.domain.ChatMessage;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ChatPresentationTest {
    @Test public void unchangedRowsRetainViewsAndBackgroundUpdatesDoNotFollow() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ChatPageController controller = new ChatPageController();
                LinearLayout list = new LinearLayout(activity);
                AtomicInteger builds = new AtomicInteger();
                ChatPageController.Row message = new ChatPageController.Row("m1", "text", () -> {
                    builds.incrementAndGet(); return new TextView(activity);
                });
                controller.reconcile(list, Collections.singletonList(message));
                View retained = list.getChildAt(0);
                assertTrue(controller.foregroundChanged("m1"));
                controller.reconcile(list, Arrays.asList(message,
                        new ChatPageController.Row("suggestions:today", "new", () -> new TextView(activity))));
                assertSame(retained, list.getChildAt(0));
                assertEquals(1, builds.get());
                assertFalse(controller.foregroundChanged("m1"));
                assertTrue(controller.foregroundChanged("m1m2"));
            });
        }
    }

    @Test public void proposalRequiresAllRowsVisibleAndConfirmsOnce() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, "建议", "{}",
                        ChatMessage.ActionState.PENDING_CONFIRMATION);
                ChatDisplayState state = new ChatDisplayState();
                AtomicInteger submissions = new AtomicInteger();
                ProposalView.Host host = new ProposalView.Host() {
                    public String description(int index) { return "任务" + index; }
                    public String status(int index) { return "已完成"; }
                    public void confirm(List<Integer> indexes) { assertEquals(4, indexes.size()); submissions.incrementAndGet(); }
                    public void cancel(List<Integer> indexes) { fail("unexpected cancellation"); }
                    public void changed() { }
                };
                List<Integer> pending = Arrays.asList(0, 1, 2, 3);
                ProposalView collapsed = new ProposalView(activity, message, 4, pending, false, state, host);
                assertNull(find(collapsed, "确认 4 项"));
                assertNull(find(collapsed, "任务3"));
                find(collapsed, "查看全部 4 项").performClick();
                ProposalView expanded = new ProposalView(activity, message, 4, pending, false, state, host);
                assertNotNull(find(expanded, "任务3"));
                View confirm = find(expanded, "确认 4 项");
                confirm.performClick(); confirm.performClick();
                assertEquals(1, submissions.get());
            });
        }
    }

    @Test public void selectionAndExpansionRestoreWithoutSelectingDeselectedItems() {
        ChatDisplayState state = new ChatDisplayState();
        state.expandedPlans.add("m1"); state.selectingPlans.add("m1"); state.expandedDays.add("today");
        state.selections.put("m1", new HashSet<>(Arrays.asList(0, 2)));
        Bundle saved = state.save();
        ChatDisplayState restored = new ChatDisplayState(); restored.restore(saved);
        assertEquals(state.selections, restored.selections);
        assertEquals(state.expandedPlans, restored.expandedPlans);
        assertEquals(state.selectingPlans, restored.selectingPlans);
        assertEquals(state.expandedDays, restored.expandedDays);
    }

    @Test public void narrativeOutcomeAndFooterShareOneSurfaceAndUndoIsOneShot() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, "建议", "{}",
                        ChatMessage.ActionState.EXECUTED).withContextSources(Collections.singletonList("任务列表"));
                AtomicInteger undos = new AtomicInteger();
                AtomicInteger views = new AtomicInteger();
                ProposalView.Host host = new TestHost() {
                    @Override public void undo() { undos.incrementAndGet(); }
                    @Override public void view(boolean focusPrepared) { assertTrue(focusPrepared); views.incrementAndGet(); }
                };
                ProposalPresentation model = new ProposalPresentation(message, 1, Collections.emptyList(),
                        false, "已安排任务并准备专注", true, "", false, true);
                ProposalView bubble = new ProposalView(activity, message, "今天先处理这件事。", model,
                        new ChatDisplayState(), host);
                assertSame(bubble, find(bubble, "今天先处理这件事。").getParent());
                assertSame(bubble, find(bubble, "已安排任务并准备专注").getParent());
                assertSame(bubble, find(bubble, "撤销").getParent());
                assertSame(bubble, find(bubble, "进入专注").getParent());
                assertNotNull(findDescription(bubble, "安排选项"));
                find(bubble, "进入专注").performClick();
                assertEquals(1, views.get());
                View undo = find(bubble, "撤销");
                undo.performClick(); undo.performClick();
                assertEquals(1, undos.get());
            });
        }
    }

    @Test public void partialRetryRetainsDeselectionAndNeverResubmitsSuccess() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, "建议", "{}",
                        ChatMessage.ActionState.PENDING_CONFIRMATION).withActionItemStates(
                        Collections.singletonList(0), ChatMessage.ActionItemState.EXECUTED, 3);
                ChatDisplayState state = new ChatDisplayState();
                state.selections.put(message.getId(), new HashSet<>(Arrays.asList(0, 2)));
                AtomicInteger submits = new AtomicInteger();
                ProposalView.Host host = new TestHost() {
                    @Override public void confirm(List<Integer> indexes) {
                        assertEquals(Collections.singletonList(2), indexes); submits.incrementAndGet();
                    }
                };
                ProposalView bubble = new ProposalView(activity, message, "建议", new ProposalPresentation(
                        message, 3, Arrays.asList(0, 1, 2), false, "部分完成", false, "", false, false), state, host);
                assertEquals(Collections.singleton(2), state.selections.get(message.getId()));
                View confirm = find(bubble, "确认 1 项");
                confirm.performClick(); confirm.performClick();
                assertEquals(1, submits.get());
                ProposalView retry = new ProposalView(activity, message, 3, Arrays.asList(1, 2), false, state, host);
                assertNotNull(find(retry, "确认 1 项"));
            });
        }
    }

    @Test public void historyAndBusyHaveNoMutationControls() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, "建议", "{}",
                        ChatMessage.ActionState.EXECUTED);
                ChatDisplayState state = new ChatDisplayState();
                state.selections.put(message.getId(), new HashSet<>(Collections.singletonList(0)));
                ProposalView history = new ProposalView(activity, message, "建议", new ProposalPresentation(
                        message, 1, Collections.emptyList(), true, "过期的结果", true, "", true, true), state, new TestHost());
                assertNull(find(history, "撤销"));
                assertNull(find(history, "过期的结果"));
                assertNull(find(history, "进入专注"));
                assertNotNull(find(history, "已执行 1 项"));
                assertEquals(Collections.singleton(0), state.selections.get(message.getId()));
                ProposalView busy = new ProposalView(activity, message, "建议", new ProposalPresentation(
                        message, 1, Collections.emptyList(), false, "正在保存", true, "", true, false), state, new TestHost());
                assertNull(find(busy, "撤销"));
                assertFalse(find(busy, "查看安排").isEnabled());
            });
        }
    }

    private static class TestHost implements ProposalView.Host {
        public String description(int index) { return "任务" + index; }
        public String status(int index) { return "已执行"; }
        public void confirm(List<Integer> indexes) { fail("unexpected confirmation"); }
        public void cancel(List<Integer> indexes) { fail("unexpected cancellation"); }
        public void changed() { }
    }

    @Test public void busyAccessorTracksEachProposalAndCompletionClearsIt() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ChatPageViewModel model = new ChatPageViewModel(new androidx.lifecycle.MutableLiveData<>());
                try {
                    assertFalse(model.isProposalBusy("first"));
                    model.setProposalBusy("first", true);
                    model.setProposalBusy("second", true);
                    assertTrue(model.isProposalBusy("first"));
                    model.setProposalBusy("second", false);
                    assertFalse(model.isProposalBusy("second"));
                    assertTrue(model.isProposalBusy("first"));
                    model.recordProposalResult("first", "已完成", null, false);
                    assertFalse(model.isProposalBusy("first"));
                } finally {
                    model.onCleared();
                }
            });
        }
    }

    @Test public void previouslySelectedFailureSurvivesSuccessfulRowsBecomingTerminal() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, "建议", "{}",
                        ChatMessage.ActionState.PENDING_CONFIRMATION);
                ChatDisplayState state = new ChatDisplayState();
                state.selections.put(message.getId(), new HashSet<>(Arrays.asList(0, 1)));
                new ProposalView(activity, message, 3, Arrays.asList(0, 1, 2), false, state, new TestHost());
                ChatMessage partial = message.withActionItemStates(Collections.singletonList(0),
                        ChatMessage.ActionItemState.EXECUTED, 3).withActionItemStates(
                        Collections.singletonList(1), ChatMessage.ActionItemState.FAILED, 3);
                AtomicInteger retries = new AtomicInteger();
                ProposalView retry = new ProposalView(activity, partial, 3, Arrays.asList(1, 2),
                        false, state, new TestHost() {
                    @Override public void confirm(List<Integer> indexes) {
                        assertEquals(Collections.singletonList(1), indexes);
                        retries.incrementAndGet();
                    }
                });
                assertEquals(Collections.singleton(1), state.selections.get(message.getId()));
                find(retry, "重试 1 项").performClick();
                assertEquals(1, retries.get());
            });
        }
    }

    @Test public void failedRetryShowsStatusAndOnlyRetriesSelectedFailure() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, "建议", "{}",
                        ChatMessage.ActionState.PENDING_CONFIRMATION)
                        .withActionItemStates(Collections.singletonList(0), ChatMessage.ActionItemState.EXECUTED, 2)
                        .withActionItemStates(Collections.singletonList(1), ChatMessage.ActionItemState.FAILED, 2);
                AtomicInteger retries = new AtomicInteger();
                ProposalView bubble = new ProposalView(activity, message, "建议", new ProposalPresentation(
                        message, 2, Arrays.asList(0, 1), false, null, false, "", false, false),
                        new ChatDisplayState(), new TestHost() {
                    @Override public void confirm(List<Integer> indexes) {
                        assertEquals(Collections.singletonList(1), indexes); retries.incrementAndGet();
                    }
                });
                assertNotNull(find(bubble, "执行失败"));
                assertNull(find(bubble, "确认 2 项"));
                View retry = find(bubble, "重试 1 项");
                retry.performClick(); retry.performClick();
                assertEquals(1, retries.get());
            });
        }
    }

    @Test public void narrowBubbleWrapsNarrativeAndUsesThemeColors() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, "建议", "{}",
                        ChatMessage.ActionState.EXECUTED);
                String narrative = "这是一段用于检查窄屏和大字体的安排说明，所有内容都应该自然换行而不会被截断。";
                ProposalView bubble = new ProposalView(activity, message, narrative,
                        ProposalPresentation.from(message, 1, false), new ChatDisplayState(), new TestHost());
                TextView body = (TextView) find(bubble, narrative);
                body.setTextSize(28);
                int width = com.hawson.timeassistant.design.UiKit.dp(activity, 240);
                bubble.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                bubble.layout(0, 0, width, bubble.getMeasuredHeight());
                assertTrue(body.getLineCount() > 1);
                assertEquals(com.hawson.timeassistant.design.UiKit.text(activity), body.getCurrentTextColor());
                View inspect = find(bubble, "查看安排");
                assertTrue(inspect.getMeasuredHeight() >= com.hawson.timeassistant.design.UiKit.dp(activity, 48));
            });
        }
    }

    private View findDescription(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) view).getChildAt(i), description);
            if (found != null) return found;
        }
        return null;
    }

    private View find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View result = find(((ViewGroup) view).getChildAt(i), text); if (result != null) return result;
        }
        return null;
    }
}
