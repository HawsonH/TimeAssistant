# Schedule design component integration

## Segmented control

Class: `com.hawson.timeassistant.design.SlidingSegmentedControl`, extends native
`android.widget.LinearLayout`. Replace only the XML container tag. Retain the
existing `TextView` children, IDs, labels, accessibility descriptions and order.
No custom XML attributes. Standard padding, background, margins, weight and
gravity work; orientation is horizontal. For the current schedule layout use
`layout_height="56dp"`, `padding="4dp"`, and the existing weighted 48dp-high
children. Children must not have opaque selected backgrounds, since the parent
draws one shared indicator behind them. `UiKit.polish` skips the control subtree
to preserve its geometry and text metrics.

```java
control.setOnSelectionChangedListener(index -> hostSelectPage(index));
control.setSelectedIndex(index, false); // Restore state without callback.
int index = control.getSelectedIndex();
```

Indices follow XML child order, including in RTL. The control owns child click
listeners; do not replace these with the old per-label click binding. If legacy
binding must remain, it must call `setSelectedIndex(index, false)` itself.
Selection state and notification update synchronously. FULL slides the current
indicator position to the new target over 220ms, retargeting on rapid selection.
REDUCED moves immediately and fades the indicator for at most 100ms; OFF/system
animation-disabled updates immediately. Resize and detach cancel animation.
For dynamically created controls, `setSegments(CharSequence...)` creates labels;
do not call it for XML controls because it replaces children and their IDs.

## Swipe row

Classes in `com.hawson.timeassistant.ui.schedule`:
`ScheduleSwipeLayout`, `ScheduleSwipeGroup`, `ScheduleSwipeGesture`.

```java
ScheduleSwipeGroup group = new ScheduleSwipeGroup(); // One per list/page.
ScheduleSwipeLayout row = new ScheduleSwipeLayout(context);
row.setContent(existingTaskContent); // Content should have an opaque background.
row.setSwipeGroup(group);
row.bind(immutableSnapshot, mainDrawable, mainLabel,
        editDrawableOrNull, editLabelOrNull, (action, capturedState) -> {
    // Re-read current host state; reject stale identity/version/status/action.
    // Only after validation, route MAIN or EDIT to the appropriate host command.
});
```

`bind(Object, Drawable, CharSequence, Drawable, CharSequence, Callback)` closes
and invalidates pending gestures. Labels must be localized, nonempty action
names. The host supplies actual play/check/undo and edit icons (including their
tint). Null edit drawable omits edit entirely. There are no repository calls.
Treat the captured state as immutable: it is passed by reference, not copied.
Swipes capture it at touch-down; explicit clicks/accessibility actions use the
current binding. The host must validate it even if it appears unchanged.

`revealActions()` exposes actual keyboard-focusable icon buttons without a
gesture; `isOpen()` reports a settled-open row. The wrapper also exposes named
MAIN/EDIT accessibility actions while closed. Keep ordinary row controls/menu
for sighted non-swipe access, optionally invoking `revealActions()`.
`close()` invalidates any active gesture. Call `group.closeOpenRow()` on page,
date, filter or external list changes. Claiming another row cancels/closes the
previous row, including a previously armed gesture. Share the group; it is not
a global singleton. Detach, resize, disable, cancel and multitouch abort without
committing.

Trailing swipe is leftward in LTR and rightward in RTL. Normal reveal is 56dp
per action. Beyond full reveal, displayed travel is
`reveal + (fingerTravel - reveal) * 0.55`. Arming uses displayed travel at
`reveal + min(96dp, 25% rowWidth)`; disarm requires retreat below threshold minus
16dp. Main expands to the revealed width while armed, edit hides, and there is
at most one haptic per gesture even after disarm/rearm. Gesture callbacks occur
only on release while armed, never on animation completion or threshold entry.
An unarmed release opens at half reveal, otherwise closes. No velocity commit.

## Verification

`ScheduleSwipeGestureTest` contains 12 local JUnit tests: 56dp-per-action reveal,
0.55 resistance, width-dependent thresholds, release-only commit, hysteresis,
one haptic across rearming, retreat-before-release, cancellation/inactive moves,
half-reveal settling, reverse close, new-gesture haptic reset and invalid geometry.
No device or external service is needed. Native touch dispatch, accessibility
and animation rendering have not been instrumented on a device.
