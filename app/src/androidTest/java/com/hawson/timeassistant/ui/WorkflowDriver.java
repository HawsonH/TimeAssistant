package com.hawson.timeassistant.ui;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/** Actual view actions; reflection is confined to the app's file import/export callbacks. */
final class WorkflowDriver {
    static void requireUnlockedDisplay(android.content.Context context) {
        assertTrue("UI test requires an awake display; wake and unlock the phone",
                context.getSystemService(android.os.PowerManager.class).isInteractive());
        assertFalse("UI test requires normal user unlock before fixtures are changed",
                context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
    }
    static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.os.SystemClock.sleep(240);
    }
    static View find(Predicate<View> predicate) {
        java.util.List<View> roots = WindowInspector.getGlobalWindowViews();
        for (int i = roots.size() - 1; i >= 0; i--) {
            View result = find(roots.get(i), predicate);
            if (result != null) return result;
        }
        return null;
    }
    private static View find(View root, Predicate<View> predicate) {
        if (!root.isShown()) return null;
        if (predicate.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup children = (ViewGroup) root;
            for (int i = 0; i < children.getChildCount(); i++) {
                View found = find(children.getChildAt(i), predicate);
                if (found != null) return found;
            }
        }
        return null;
    }
    static View text(String value) {
        return find(v -> v instanceof TextView && value.contentEquals(((TextView) v).getText()));
    }
    static void click(String value) { act(() -> text(value), value, null); }
    static void clickContaining(String value) {
        act(() -> find(v -> v instanceof TextView && ((TextView)v).getText().toString().contains(value)), value, null);
    }
    static void clickId(int id) { act(() -> find(v -> v.getId() == id), "id=" + id, null); }
    static void fill(String hint, String value) {
        act(() -> find(v -> v instanceof EditText && hint.contentEquals(((EditText) v).getHint())), hint, value);
    }
    static void fillId(int id, String value) { act(() -> find(v -> v.getId() == id), "id=" + id, value); }
    static void awaitCondition(String label, java.util.function.BooleanSupplier condition) {
        boolean[] ready = {false};
        long deadline = android.os.SystemClock.uptimeMillis() + 3_000;
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ready[0] = condition.getAsBoolean());
            if (!ready[0]) idle();
        } while (!ready[0] && android.os.SystemClock.uptimeMillis() < deadline);
        assertTrue("Timed out waiting for " + label, ready[0]);
    }
    private static void act(java.util.concurrent.Callable<View> lookup, String name, String input) {
        View[] target = {null};
        awaitCondition(name, () -> {
            try { target[0] = lookup.call(); } catch (Exception error) { throw new AssertionError(error); }
            return target[0] != null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            target[0].requestRectangleOnScreen(new Rect(0, 0, target[0].getWidth(), target[0].getHeight()), true);
        });
        idle();
        androidx.test.espresso.ViewInteraction action = androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.is(target[0]));
        if (input == null) action.perform(androidx.test.espresso.action.ViewActions.click());
        else action.perform(androidx.test.espresso.action.ViewActions.replaceText(input), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
        idle();
    }
    static void callback(TimeAssistantActivity activity, String method, android.net.Uri uri) {
        try {
            Method entry = TimeAssistantActivity.class.getDeclaredMethod(method, android.net.Uri.class);
            entry.setAccessible(true); entry.invoke(activity, uri);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    static void capture(String name) throws Exception {
        idle();
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "ui-workflows");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { screenshot.recycle(); }
    }
}
