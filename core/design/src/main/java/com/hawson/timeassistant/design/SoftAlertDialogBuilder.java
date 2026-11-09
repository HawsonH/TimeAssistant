package com.hawson.timeassistant.design;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Keep native Material presentation while applying the application's reduced-motion policy. */
public final class SoftAlertDialogBuilder extends MaterialAlertDialogBuilder {
    public SoftAlertDialogBuilder(Context context) { super(context); }
    public SoftAlertDialogBuilder(Context context, int theme) { super(context, theme); }
    @Override public AlertDialog create() {
        AlertDialog dialog = super.create();
        MotionSpec.configureDialog(dialog);
        return dialog;
    }
}
