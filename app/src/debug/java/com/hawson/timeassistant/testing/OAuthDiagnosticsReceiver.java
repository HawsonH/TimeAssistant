package com.hawson.timeassistant.testing;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.connections.AiAccountPort;

/** Read-only QA state, restricted to callers holding the system DUMP permission. */
public final class OAuthDiagnosticsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!context.getPackageName().contains(".qa")) return;
        AiAccountPort accounts = ((TimeAssistantApplication) context.getApplicationContext())
                .getAiServices().accounts;
        int authorized = 0;
        for (AiAccountPort.Account account : accounts.accounts()) if (account.authorized) authorized++;
        String state = "phase=" + accounts.signInStatus().name();
        try {
            state = (String) accounts.getClass().getMethod("signInDiagnostics").invoke(accounts);
        } catch (ReflectiveOperationException unavailable) { }
        setResultCode(0);
        com.hawson.timeassistant.AiServices ai = ((TimeAssistantApplication) context.getApplicationContext()).getAiServices();
        com.hawson.timeassistant.connections.AiProfile active = ai.profiles.active();
        setResultData(state + ";authorized_accounts=" + authorized + ";active_profile=" + (active != null)
                + ";model_selected=" + (active != null && !active.model.isEmpty()) + ";configured=" + ai.configured()
                + ";process_id=" + android.os.Process.myPid()
                + ";battery_exempt=" + context.getSystemService(android.os.PowerManager.class)
                        .isIgnoringBatteryOptimizations(context.getPackageName())
                + ";" + com.hawson.timeassistant.auth.OAuthBrowser.returnDiagnostics());
    }
}
