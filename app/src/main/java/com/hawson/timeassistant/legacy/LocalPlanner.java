/**
 * 本地规则排期（遗留）
 *
 * 用正则从中文自然语言提取时间与时长，现由 AI 对话流程替代，仅测试保留。
 */
package com.hawson.timeassistant.legacy;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LocalPlanner {
    private static final Pattern CLOCK_PATTERN = Pattern.compile("(凌晨|早上|上午|中午|下午|傍晚|晚上)?\\s*(\\d{1,2})(?:[:：点时](\\d{1,2})?分?)");
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(小时|分钟)");

    public PlanProposal plan(String request) {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        if (request.contains("后天")) start.add(Calendar.DAY_OF_YEAR, 2);
        else if (request.contains("明天")) start.add(Calendar.DAY_OF_YEAR, 1);

        Matcher clockMatcher = CLOCK_PATTERN.matcher(request);
        boolean inferredTime = true;
        if (clockMatcher.find()) {
            inferredTime = false;
            String period = clockMatcher.group(1);
            int hour = safeInt(clockMatcher.group(2), 9);
            int minute = safeInt(clockMatcher.group(3), 0);
            if (("下午".equals(period) || "傍晚".equals(period) || "晚上".equals(period)) && hour < 12) hour += 12;
            if ("中午".equals(period) && hour < 11) hour += 12;
            if (("凌晨".equals(period) || "早上".equals(period) || "上午".equals(period)) && hour == 12) hour = 0;
            start.set(Calendar.HOUR_OF_DAY, Math.min(hour, 23));
            start.set(Calendar.MINUTE, Math.min(minute, 59));
        } else {
            int minute = start.get(Calendar.MINUTE);
            start.add(Calendar.MINUTE, minute < 30 ? 30 - minute : 60 - minute);
        }

        int duration = 60;
        Matcher durationMatcher = DURATION_PATTERN.matcher(request);
        if (durationMatcher.find()) {
            double value = safeDouble(durationMatcher.group(1), 1);
            duration = "小时".equals(durationMatcher.group(2)) ? (int) (value * 60) : (int) value;
            duration = Math.max(5, Math.min(duration, 8 * 60));
        }

        String title = request
                .replaceAll("今天|明天|后天", "")
                .replaceAll("\\d+(?:\\.\\d+)?\\s*(小时|分钟)", "")
                .replaceAll("(凌晨|早上|上午|中午|下午|傍晚|晚上)?\\s*\\d{1,2}(?:[:：点时]\\d{0,2}分?)?", "")
                .replaceAll("帮我|请|想要|需要|计划|安排|添加|创建|一个|日程|任务|提醒我", "")
                .replaceAll("[，。,.！!？?：:]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        if (title.isEmpty()) title = "专注事项";
        if (title.length() > 22) title = title.substring(0, 22);

        String category = categoryFor(title);
        int color = colorFor(category);
        ScheduleEvent event = new ScheduleEvent(title, start.getTimeInMillis(), duration, color, category);
        return new PlanProposal(event, inferredTime);
    }

    private static String categoryFor(String title) {
        if (title.contains("跑") || title.contains("健身") || title.contains("运动") || title.contains("瑜伽")) return "健康";
        if (title.contains("学习") || title.contains("阅读") || title.contains("复习")) return "成长";
        if (title.contains("会议") || title.contains("工作") || title.contains("方案") || title.contains("客户")) return "工作";
        return "生活";
    }

    private static int colorFor(String category) {
        switch (category) {
            case "健康": return rgb(244, 161, 92);
            case "成长": return rgb(90, 139, 235);
            case "工作": return rgb(101, 88, 232);
            default: return rgb(49, 182, 155);
        }
    }

    private static int rgb(int red, int green, int blue) {
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private static int safeInt(String value, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static double safeDouble(String value, double fallback) {
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    public static class PlanProposal {
        public final ScheduleEvent event;
        public final boolean inferredTime;

        PlanProposal(ScheduleEvent event, boolean inferredTime) {
            this.event = event;
            this.inferredTime = inferredTime;
        }
    }
}
