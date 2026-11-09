package com.hawson.timeassistant.design;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

/** Protects local form edits without changing save or business commands. */
public final class FormDraftGuard {
    private FormDraftGuard() { }

    public static final class Session {
        private final android.content.Context context;
        private final java.util.List<TextView> fields = new java.util.ArrayList<>();
        private final java.util.List<String> values = new java.util.ArrayList<>();
        private final java.util.List<Boolean> checked = new java.util.ArrayList<>();
        public Session(android.content.Context context, View... roots) {
            this.context = context;
            for (View root : roots) collect(root);
            accept();
        }
        private void collect(View view) {
            if (view instanceof android.widget.EditText || view instanceof CompoundButton) fields.add((TextView) view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) collect(group.getChildAt(index));
            }
        }
        public void accept() {
            values.clear(); checked.clear();
            for (TextView field : fields) {
                values.add(field.getText().toString());
                checked.add(field instanceof CompoundButton && ((CompoundButton) field).isChecked());
            }
        }
        public android.os.Bundle save() {
            android.os.Bundle state = new android.os.Bundle();
            state.putStringArrayList("initial", new java.util.ArrayList<>(values));
            java.util.ArrayList<String> current = new java.util.ArrayList<>();
            boolean[] initialChecks = new boolean[fields.size()];
            boolean[] currentChecks = new boolean[fields.size()];
            for (int index = 0; index < fields.size(); index++) {
                TextView field = fields.get(index);
                current.add(field.getText().toString());
                initialChecks[index] = checked.get(index);
                currentChecks[index] = field instanceof CompoundButton && ((CompoundButton) field).isChecked();
            }
            state.putStringArrayList("current", current);
            state.putBooleanArray("initialChecks", initialChecks);
            state.putBooleanArray("currentChecks", currentChecks);
            return state;
        }
        public void restore(android.os.Bundle state) {
            if (state == null) return;
            java.util.ArrayList<String> initial = state.getStringArrayList("initial");
            java.util.ArrayList<String> current = state.getStringArrayList("current");
            boolean[] initialChecks = state.getBooleanArray("initialChecks");
            boolean[] currentChecks = state.getBooleanArray("currentChecks");
            if (initial == null || current == null || initialChecks == null || currentChecks == null
                    || initial.size() != fields.size() || current.size() != fields.size()
                    || initialChecks.length != fields.size() || currentChecks.length != fields.size()) return;
            values.clear(); values.addAll(initial); checked.clear();
            for (boolean value : initialChecks) checked.add(value);
            for (int index = 0; index < fields.size(); index++) {
                TextView field = fields.get(index);
                if (field instanceof CompoundButton) ((CompoundButton) field).setChecked(currentChecks[index]);
                else field.setText(current.get(index));
            }
        }
        public boolean dirty() {
            for (int index = 0; index < fields.size(); index++) {
                TextView field = fields.get(index);
                if (!values.get(index).equals(field.getText().toString())) return true;
                if (field instanceof CompoundButton && checked.get(index) != ((CompoundButton) field).isChecked()) return true;
            }
            return false;
        }
        public void leave(Runnable action) {
            if (!dirty()) { action.run(); return; }
            new com.hawson.timeassistant.design.SoftAlertDialogBuilder(context).setTitle("放弃修改？")
                    .setMessage("尚未保存的内容将丢失。")
                    .setNegativeButton("继续编辑", null)
                    .setPositiveButton("放弃修改", (dialog, which) -> {
                        for (int index = 0; index < fields.size(); index++) {
                            TextView field = fields.get(index);
                            if (field instanceof CompoundButton) ((CompoundButton) field).setChecked(checked.get(index));
                            else field.setText(values.get(index));
                        }
                        action.run();
                    }).show();
        }
    }

    public static String snapshot(View view) {
        StringBuilder result = new StringBuilder();
        append(view, result);
        return result.toString();
    }

    private static void append(View view, StringBuilder result) {
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            result.append(value.length()).append(':').append(value).append(';');
        }
        if (view instanceof CompoundButton) result.append(((CompoundButton) view).isChecked());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) append(group.getChildAt(index), result);
        }
    }

    public static void attach(AlertDialog dialog, View form) {
        String initial = snapshot(form);
        dialog.setCanceledOnTouchOutside(false);
        Runnable leave = () -> {
            if (initial.equals(snapshot(form))) { dialog.dismiss(); return; }
            new com.hawson.timeassistant.design.SoftAlertDialogBuilder(form.getContext()).setTitle("放弃修改？")
                    .setMessage("尚未保存的内容将丢失。")
                    .setNegativeButton("继续编辑", null)
                    .setPositiveButton("放弃修改", (prompt, which) -> dialog.dismiss()).show();
        };
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> leave.run());
        }
        dialog.getOnBackPressedDispatcher().addCallback(dialog, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { leave.run(); }
        });
        dialog.setOnKeyListener((ignored, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) leave.run();
            return true;
        });
    }
}
