package com.hawson.timeassistant.data;

import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.domain.FocusState;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AppRepositoryQueryTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Test
    public void queriesSeparateTodayUpcomingUnscheduledAndOverdue() {
        long now = utcTime(2030, Calendar.JANUARY, 10, 12, 0);
        long todayStart = utcTime(2030, Calendar.JANUARY, 10, 0, 0);
        long tomorrowStart = utcTime(2030, Calendar.JANUARY, 11, 0, 0);

        TaskItem today = new TaskItem("今天安排")
                .schedule(todayStart + 9 * 60 * 60_000L, todayStart + 10 * 60 * 60_000L, false);
        TaskItem future = new TaskItem("明天安排")
                .schedule(tomorrowStart + 9 * 60 * 60_000L, tomorrowStart + 10 * 60 * 60_000L, false);
        TaskItem unscheduled = new TaskItem("待安排")
                .setRequiresTimeArrangement(true)
                .setPriority(TaskItem.PRIORITY_HIGH)
                .setProject("课程")
                .setTags(Arrays.asList("写作", "重要"));
        TaskItem todo = new TaskItem("无需排期待办")
                .setRequiresTimeArrangement(false);
        TaskItem overdue = new TaskItem("已延期")
                .setRequiresTimeArrangement(true)
                .setDueAt(now - 60_000L)
                .setStatus(TaskItem.Status.NEEDS_RESCHEDULING);
        TaskItem completed = new TaskItem("已完成")
                .setDueAt(now - 60_000L)
                .setStatus(TaskItem.Status.COMPLETED);
        List<TaskItem> tasks = Arrays.asList(today, future, unscheduled, todo, overdue, completed);

        assertEquals(Arrays.asList(today.getId(), overdue.getId(), completed.getId()),
                ids(AppRepository.queryToday(tasks, now, UTC)));
        assertEquals(Collections.singletonList(future.getId()),
                ids(AppRepository.queryUpcoming(tasks, now, 7, UTC)));
        assertEquals(Arrays.asList(overdue.getId(), unscheduled.getId()),
                ids(AppRepository.queryUnscheduled(tasks)));
        assertEquals(Arrays.asList(today.getId(), overdue.getId()),
                ids(AppRepository.queryOverdue(tasks, now)));
    }

    @Test
    public void projectAndTagFilterRequiresProjectAndAllTags() {
        TaskItem matching = new TaskItem("匹配")
                .setProject("课程")
                .setTags(Arrays.asList("写作", "重要"));
        TaskItem wrongTag = new TaskItem("少标签")
                .setProject("课程")
                .setTags(Collections.singletonList("写作"));
        TaskItem wrongProject = new TaskItem("错项目")
                .setProject("工作")
                .setTags(Arrays.asList("写作", "重要"));

        List<TaskItem> result = AppRepository.filterByProjectAndTags(
                Arrays.asList(matching, wrongTag, wrongProject),
                "课程",
                Arrays.asList("写作", "重要"));

        assertEquals(1, result.size());
        assertEquals(matching.getId(), result.get(0).getId());
        assertTrue(result.get(0) != matching);
    }

    @Test
    public void todayQueryKeepsPartialStartOutOfTimelineAndIncludesDueWithoutStart() {
        long day = utcTime(2030, Calendar.JANUARY, 10, 0, 0);
        long now = day + 12 * 60 * 60_000L;
        TaskItem startOnly = new TaskItem("只有开始")
                .setRequiresTimeArrangement(true)
                .setDueAt(day + 23 * 60 * 60_000L)
                .setScheduleFields(day, day + 9 * 60 * 60_000L, 0, false);
        TaskItem endOnly = new TaskItem("只有结束")
                .setRequiresTimeArrangement(true)
                .setDueAt(day + 23 * 60 * 60_000L)
                .setScheduleFields(day, 0, day + 10 * 60 * 60_000L, false);

        assertEquals(Collections.singletonList(endOnly.getId()),
                ids(AppRepository.queryToday(Arrays.asList(startOnly, endOnly), now, UTC)));
        assertEquals(Arrays.asList(startOnly.getId(), endOnly.getId()),
                ids(AppRepository.queryUnscheduled(Arrays.asList(startOnly, endOnly))));
    }

    @Test
    public void noTaskFocusIsAValidRepositoryState() {
        FocusState unbound = FocusState.ready(null, FocusState.Mode.FOCUS, 25 * 60_000L, 1)
                .startOrResume(utcTime(2030, Calendar.JANUARY, 10, 12, 0));

        assertTrue(!AppRepository.hasInvalidFocusBinding(unbound, null));
    }

    @Test
    public void legacyArchivedStatusLoadsAsCancelled() {
        assertEquals(TaskItem.Status.CANCELLED, TaskItem.Status.fromStorage("archived"));
        assertEquals(TaskItem.Status.CANCELLED, TaskItem.Status.fromStorage("已归档"));
    }

    @Test
    public void awaitingConfirmationIsNotClassifiedAsOverdue() {
        long now = utcTime(2030, Calendar.JANUARY, 10, 12, 0);
        TaskItem awaiting = new TaskItem("等待结束确认")
                .schedule(now - 60 * 60_000L, now - 30 * 60_000L, false)
                .setStatus(TaskItem.Status.AWAITING_CONFIRMATION);

        assertTrue(AppRepository.queryOverdue(
                Collections.singletonList(awaiting), now).isEmpty());
    }

    private static List<String> ids(List<TaskItem> tasks) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (TaskItem task : tasks) ids.add(task.getId());
        return ids;
    }

    private static long utcTime(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(UTC);
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
