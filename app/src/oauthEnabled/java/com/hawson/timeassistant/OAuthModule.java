package com.hawson.timeassistant;

import android.content.Context;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.oauth.OpenAiOAuth;

final class OAuthModule {
    static AiOAuthExtension create(Context context, AiCredentialsPort credentials) {
        return new OpenAiOAuth(context, credentials);
    }
}
