package com.hawson.timeassistant.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.material.button.MaterialButton;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.design.UiKit;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.testing.AiSettingsHarnessActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Pure presentation, safe on the existing isolated phone package. No data or AI calls. */
@RunWith(AndroidJUnit4.class)
public class ChatLayoutPresentationTest {
    @Test public void bubbleAndButtonsAcrossWidthsFontsPalettesAndDarkMode() {
        try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
            screen.onActivity(activity -> {
                for (String theme : new String[]{"violet", "ocean", "forest", "clay"}) {
                    for (boolean dark : new boolean[]{false, true}) {
                        Configuration config = new Configuration(activity.getResources().getConfiguration());
                        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
                        for (int width : new int[]{320, 360, 412, 740}) for (float font : new float[]{1, 1.3f, 2}) {
                            config.fontScale = font;
                            Context context = new ContextThemeWrapper(activity.createConfigurationContext(config), AppTheme.styleFor(theme));
                            LinearLayout root = fixture(context);
                            int pixels = UiKit.dp(context, width);
                            root.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                            root.layout(0, 0, pixels, root.getMeasuredHeight());
                            assertBounds(root);
                            View user = root.findViewWithTag("user-bubble");
                            assertTrue(user.getWidth() < pixels / 2);
                            TextView userText = (TextView) ((ViewGroup) user).getChildAt(0);
                            TextView assistant = root.findViewWithTag("assistant-bubble");
                            assertEquals(assistant.getTextSize(), userText.getTextSize(), .01f);
                            assertEquals(assistant.getLineSpacingExtra(), userText.getLineSpacingExtra(), .01f);
                            assertEquals(0, userText.getPaddingTop());
                            View attachment = root.findViewWithTag("attachment-fixture");
                            assertTrue("Attachment must stay readable without filling a landscape viewport",
                                    attachment.getWidth() >= Math.min(UiKit.dp(context, 240), pixels / 2));
                            MaterialButton undo = (MaterialButton) find(root, "撤销");
                            assertEquals(0, Color.alpha(undo.getBackgroundTintList().getDefaultColor()));
                            assertEquals(UiKit.dp(context, 1), undo.getStrokeWidth());
                            assertEquals(0, Color.alpha(((MaterialButton) find(root, "查看安排")).getBackgroundTintList().getDefaultColor()));
                            capture(root, theme + (dark ? "-dark" : "-light") + "-" + width + "-" + font);
                        }
                    }
                }
                LinearLayout visible = fixture(activity);
                activity.setContentView(visible);
            });
        }
    }

    private static LinearLayout fixture(Context context) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(context, 12); root.setPadding(pad, pad, pad, pad); root.setBackgroundColor(UiKit.surface(context));
        root.addView(ChatBubbleFactory.user(context, new ChatMessage(ChatMessage.Role.USER, "这是什么"),
                AppTheme.resolveColor(context, R.attr.colorPrimarySoft, R.color.brand_primary_soft), UiKit.text(context), UiKit.secondary(context)), new LinearLayout.LayoutParams(-1, -2));
        root.addView(ChatBubbleFactory.assistant(context, "这是什么", context.getColor(R.color.button_neutral), UiKit.text(context)), new LinearLayout.LayoutParams(-2, -2));
        ChatMessage attached = new ChatMessage(ChatMessage.Role.USER, "请看附件", null, ChatMessage.ActionState.NONE,
                Arrays.asList(new ChatMessage.Attachment("这是一份文件名很长的测试截图与任务安排说明.png", "image/png", 250_000),
                        new ChatMessage.Attachment("quarterly-review-and-planning-notes-with-a-long-name.pdf", "application/pdf", 40_000)));
        LinearLayout line = ChatBubbleFactory.user(context, attached, AppTheme.resolveColor(context, R.attr.colorPrimarySoft, R.color.brand_primary_soft), UiKit.text(context), UiKit.secondary(context));
        line.getChildAt(0).setTag("attachment-fixture"); root.addView(line, new LinearLayout.LayoutParams(-1, -2));
        ChatMessage outcome = new ChatMessage(ChatMessage.Role.ASSISTANT, "已安排", "{}", ChatMessage.ActionState.EXECUTED);
        ProposalView proposal = new ProposalView(context, outcome, "已设置两项提醒，请确认提醒时间。", new ProposalPresentation(outcome, 1,
                Collections.emptyList(), false, "已完成", true, "", false, false), new ChatDisplayState(), new ProposalView.Host() {
            public String description(int index) { return "提醒 11月4日 21:30"; }
            public String status(int index) { return "已完成"; }
            public void confirm(java.util.List<Integer> indexes) { fail("No business commands"); }
            public void cancel(java.util.List<Integer> indexes) { fail("No business commands"); }
            public void changed() { }
        });
        root.addView(proposal, new LinearLayout.LayoutParams(-1, -2)); UiKit.polish(root); return root;
    }

    private static void assertBounds(View view) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child.getVisibility() == View.GONE) continue;
            assertTrue("left " + child, child.getLeft() >= 0);
            assertTrue("right " + child, child.getRight() <= group.getWidth() + 1);
            if (child instanceof TextView) {
                TextView text = (TextView) child;
                assertNotNull(text.getLayout());
                assertTrue("text height " + text.getText(), text.getHeight() >= text.getLayout().getHeight() + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom());
            }
            assertBounds(child);
        }
    }
    private static TextView find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView result = find(((ViewGroup) view).getChildAt(i), label); if (result != null) return result;
        }
        return null;
    }
    private static void capture(View root, String name) {
        File dir = new File(root.getContext().getExternalFilesDir(null), "attention-chat-layout");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(image));
        try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        catch (java.io.IOException error) { throw new AssertionError(error); }
        finally { image.recycle(); }
    }
}
