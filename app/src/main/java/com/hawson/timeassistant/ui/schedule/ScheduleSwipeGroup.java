package com.hawson.timeassistant.ui.schedule;

/** One instance per list/page. Holds at most one open or actively swiping row. */
public final class ScheduleSwipeGroup {
    private ScheduleSwipeLayout open;

    void claim(ScheduleSwipeLayout row) {
        if (open == row) return;
        ScheduleSwipeLayout previous = open;
        open = row;
        if (previous != null) previous.closeAnimated();
    }

    void release(ScheduleSwipeLayout row) { if (open == row) open = null; }

    public void closeOpenRow() {
        ScheduleSwipeLayout previous = open;
        open = null;
        if (previous != null) previous.close();
    }
}
