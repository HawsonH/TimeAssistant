package com.hawson.timeassistant.settings;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hawson.timeassistant.settingsfeature.R;

/** Recovery instructions contain no live authorization URL or account data. */
public final class OAuthSignInHelp {
    private OAuthSignInHelp() { }

    public static AlertDialog show(Context context, boolean pending, Runnable pasteCallback) {
        MaterialAlertDialogBuilder builder = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(context)
                .setTitle(R.string.oauth_login_help_title)
                .setMessage(R.string.oauth_login_help_message)
                .setPositiveButton(R.string.oauth_login_help_done, null);
        if (pending) {
            builder.setNeutralButton(R.string.oauth_login_help_paste,
                    (dialog, which) -> pasteCallback.run());
        }
        return builder.show();
    }
}
