package com.hawson.timeassistant.oauth;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.hawson.timeassistant.ai.AiTransportPolicy;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.network.AiHttp;
import com.nimbusds.jwt.JWTClaimsSet;
import net.openid.appauth.*;
import org.json.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Public-client SIWC extension. It never accepts a configurable authorization or inference origin. */
public final class OpenAiOAuth implements AiOAuthExtension {
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String AUTHORIZE = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String PENDING_SIGN_IN = "registration:oauth-sign-in";
    private static final String SIGN_IN_STATUS = "registration:oauth-status";
    private static final long SIGN_IN_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private final AiCredentialsPort store;
    private final String applicationId;
    private volatile AiSignInStatus signInStatus = AiSignInStatus.IDLE;
    private String failureDiagnostics = "token_error_type=-1;token_error_code=-1;network_error=NONE";
    private final AuthorizationService service;
    private final AuthorizationServiceConfiguration config = new AuthorizationServiceConfiguration(
            Uri.parse(AUTHORIZE), Uri.parse(TOKEN));
    private final AiHttp http = new AiHttp(AiTransportPolicy.httpsOnly());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private PendingSignIn pendingSignIn;
    private LoopbackAuthorization callbackListener;
    private AiCancellation attemptCancellation;
    private boolean completingSignIn;
    private volatile long generation;
    private final Object sessionLock = new Object();
    private final OAuthTokenRefresh tokenRefresh;
    private volatile java.util.function.Consumer<Account> accountListener = account -> { };
    private static final Listener SILENT = new Listener() {
        public void finished(Account account) { }
        public void failed(String message) { }
    };
    private Listener signInListener = SILENT;

    public OpenAiOAuth(Context context, AiCredentialsPort store) {
        this.store = store;
        applicationId = context.getPackageName();
        try {
            String saved = store.read(SIGN_IN_STATUS);
            if (saved != null) signInStatus = AiSignInStatus.valueOf(saved);
            if (signInStatus == AiSignInStatus.EXCHANGING) updateStatus(AiSignInStatus.INTERRUPTED);
        } catch (Exception ignored) { }
        AppAuthConfiguration options = new AppAuthConfiguration.Builder().setConnectionBuilder(uri -> {
            requireAuthUrl(uri.toString());
            return AiTransportPolicy.httpsOnly().open(new URL(uri.toString()));
        }).build();
        service = new AuthorizationService(context, options);
        tokenRefresh = new OAuthTokenRefresh(store, sessionLock, this::refreshTokens, replacement -> {
            if (replacement.optBoolean("verifyRefreshIdentity")) {
                validate(replacement.getString("idToken"), replacement.getString("client"), null,
                        replacement.getString("subject"), new AiCancellation());
            }
        });
        resumeSignIn();
    }
    @Override public void setAccountListener(java.util.function.Consumer<Account> listener) {
        accountListener = listener == null ? account -> { } : listener;
    }
    @Override public synchronized void resumeSignIn() {
        if (pendingSignIn != null || completingSignIn) return;
        try {
            PendingSignIn saved = storedPending();
            if (saved == null) return;
            JSONObject raw = new JSONObject(store.read(PENDING_SIGN_IN));
            if (raw.optBoolean("exchangeStarted")) {
                boolean committed = false;
                for (Account account : accounts()) {
                    if (saved.sessionId.equals(record(account.reference).optString("session"))) committed = true;
                }
                discardPendingQuietly();
                updateStatus(committed ? AiSignInStatus.CONNECTED : AiSignInStatus.INTERRUPTED);
                return;
            }
            if (raw.has("callback")) { completePendingSignIn(raw.getString("callback"), SILENT); return; }
            long remaining = SIGN_IN_WINDOW_MILLIS - (System.currentTimeMillis() - saved.startedAt);
            LoopbackAuthorization loopback = new LoopbackAuthorization(applicationId,
                    URI.create(saved.redirectUri).getPort(), remaining);
            pendingSignIn = saved; callbackListener = loopback;
            attemptCancellation = new AiCancellation();
            updateStatus(AiSignInStatus.WAITING);
            long attempt = generation;
            listenerExecutor.execute(() -> awaitBrowserCallback(loopback, saved, attempt));
        } catch (Exception unavailable) { updateStatus(AiSignInStatus.CALLBACK); }
    }
    @Override public boolean available() { return true; }
    @Override public List<Account> accounts() {
        List<Account> result = new ArrayList<>();
        for (String reference : store.references()) {
            if (!reference.startsWith("oauth:")) continue;
            try {
                JSONObject record = new JSONObject(store.read(reference));
                result.add(new Account(reference, record.getString("label"),
                        !record.optString("access").isEmpty() && granted(record.optString("scope"))));
            } catch (Exception failure) {
                result.add(new Account(reference, "账号凭据不可读取", false));
            }
        }
        return Collections.unmodifiableList(result);
    }
    @Override public synchronized String beginSignIn(String reference, Listener listener) throws Exception {
        cancelSignIn();
        JSONObject previous = reference == null || reference.isEmpty() ? null : record(reference);
        String host = store.read("host-id");
        if (host == null) { host = "urn:uuid:" + UUID.randomUUID(); store.write("host-id", host); }
        String retained = previous == null ? store.read("registration:pending") : null;
        String client = previous == null
                ? (retained == null ? "dynamic_agent_client" : retained) : previous.getString("client");
        boolean registering = "dynamic_agent_client".equals(client);
        LoopbackAuthorization loopback = new LoopbackAuthorization(applicationId);
        String redirectUri = loopback.redirectUri;
        String verifier = CodeVerifierUtil.generateRandomCodeVerifier();
        String state = UUID.randomUUID().toString() + UUID.randomUUID();
        String nonce = UUID.randomUUID().toString() + UUID.randomUUID();
        PendingSignIn signIn = new PendingSignIn(UUID.randomUUID().toString(), reference == null ? "" : reference,
                client, registering, verifier, state, nonce, redirectUri, System.currentTimeMillis());
        store.write(PENDING_SIGN_IN, signIn.toJson().toString());
        pendingSignIn = signIn;
        signInListener = listener;
        callbackListener = loopback;
        failureDiagnostics = "token_error_type=-1;token_error_code=-1;network_error=NONE";
        updateStatus(AiSignInStatus.WAITING);
        attemptCancellation = new AiCancellation();
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("resource", RESOURCE); extra.put("ext_agent_host_id", host);
        if (registering) extra.put("agent_name_hint", "时屿");
        else if (previous != null && previous.has("idToken")) extra.put("id_token_hint", previous.getString("idToken"));
        AuthorizationRequest request = new AuthorizationRequest.Builder(config, client,
                ResponseTypeValues.CODE, Uri.parse(redirectUri))
                .setScope("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                .setState(state).setNonce(nonce).setCodeVerifier(verifier).setAdditionalParameters(extra).build();
        long attempt = generation;
        listenerExecutor.execute(() -> awaitBrowserCallback(loopback, signIn, attempt));
        return request.toUri().toString();
    }
    @Override public synchronized boolean hasPendingSignIn() {
        try { return storedPending() != null; }
        catch (Exception ignored) { return false; }
    }
    @Override public synchronized AiSignInStatus signInStatus() {
        if (signInStatus == AiSignInStatus.WAITING && !hasPendingSignIn()) {
            generation++;
            if (attemptCancellation != null) attemptCancellation.cancel();
            closeCallbackListener();
            pendingSignIn = null;
            updateStatus(AiSignInStatus.EXPIRED);
        }
        return signInStatus;
    }
    /** Fixed fields only, consumed by the shell-protected QA diagnostics receiver. */
    public synchronized String signInDiagnostics() {
        return "phase=" + signInStatus().name() + ";pending=" + hasPendingSignIn()
                + ";callback_transport=loopback_return_page"
                + ";listener_open=" + (callbackListener != null && callbackListener.isOpen())
                + ";callback_port=" + (pendingSignIn == null ? -1 : URI.create(pendingSignIn.redirectUri).getPort())
                + ";" + failureDiagnostics;
    }
    @Override public synchronized void completePendingSignIn(String callbackUri, Listener listener) throws Exception {
        if (completingSignIn) throw new AiSignInException(AiSignInException.Reason.BUSY);
        if (store.read(PENDING_SIGN_IN) == null) throw new AiSignInException(AiSignInException.Reason.MISSING);
        PendingSignIn signIn = storedPending();
        if (signIn == null) throw new AiSignInException(AiSignInException.Reason.EXPIRED);
        Uri response = parseCallback(callbackUri, signIn);
        JSONObject received = signIn.toJson().put("callback", response.toString());
        store.write(PENDING_SIGN_IN, received.toString());
        generation++;
        if (attemptCancellation != null) attemptCancellation.cancel();
        closeCallbackListener();
        pendingSignIn = signIn;
        attemptCancellation = new AiCancellation();
        completingSignIn = true;
        long attempt = generation;
        updateStatus(AiSignInStatus.EXCHANGING);
        finishSignInLater(signIn, response, listener, attempt, attemptCancellation);
    }
    @Override public synchronized void cancelSignIn() {
        generation++;
        if (attemptCancellation != null) attemptCancellation.cancel();
        closeCallbackListener();
        pendingSignIn = null; completingSignIn = false; discardPendingQuietly();
        updateStatus(AiSignInStatus.CANCELLED);
    }
    private void awaitBrowserCallback(LoopbackAuthorization listener, PendingSignIn signIn, long attempt) {
        try (LoopbackAuthorization ignored = listener) {
            listener.await(signIn.state, callback -> {
                synchronized (this) {
                    if (generation != attempt || pendingSignIn == null
                            || !signIn.sessionId.equals(pendingSignIn.sessionId)) return;
                    completePendingSignIn(callback.toString(), signInListener);
                }
            });
        } catch (Exception ignored) {
            main.post(() -> markBrowserCallbackUnavailable(listener, signIn, attempt));
        }
    }
    private synchronized void markBrowserCallbackUnavailable(LoopbackAuthorization listener,
                                                             PendingSignIn signIn, long attempt) {
        if (generation != attempt || pendingSignIn == null
                || completingSignIn || !signIn.sessionId.equals(pendingSignIn.sessionId)) return;
        if (callbackListener == listener) callbackListener = null;
        try {
            if (storedPending() == null) {
                pendingSignIn = null;
                updateStatus(AiSignInStatus.EXPIRED);
                return;
            }
        } catch (Exception unavailable) {
            updateStatus(AiSignInStatus.CALLBACK);
            return;
        }
        updateStatus(AiSignInStatus.CALLBACK);
    }
    private void closeCallbackListener() {
        LoopbackAuthorization listener = callbackListener;
        callbackListener = null;
        if (listener == null) return;
        try { listener.close(); } catch (Exception ignored) { }
    }
    private void finishSignInLater(PendingSignIn signIn, Uri response, Listener listener,
                                   long attempt, AiCancellation cancellation) {
        executor.execute(() -> {
            try { finishSignIn(signIn, response, listener, attempt, cancellation); }
            catch (Exception failure) { reportSignInFailure(attempt, listener, failure); }
        });
    }
    private void finishSignIn(PendingSignIn signIn, Uri response, Listener listener,
                              long attempt, AiCancellation cancellation) throws Exception {
        synchronized (sessionLock) {
            cancellation.check();
            parseCallback(response.toString(), signIn);
            synchronized (this) {
                if (generation != attempt || !completingSignIn || pendingSignIn == null
                        || !signIn.sessionId.equals(pendingSignIn.sessionId)) return;
                if (storedPending() == null) throw new IllegalStateException("登录会话已过期");
                completingSignIn = true;
                updateStatus(AiSignInStatus.EXCHANGING);
                JSONObject raw = new JSONObject(store.read(PENDING_SIGN_IN));
                raw.put("exchangeStarted", true);
                store.write(PENDING_SIGN_IN, raw.toString());
            }
            if (response.getQueryParameter("error") != null) {
                throw new SignInFailure(AiSignInStatus.CANCELLED);
            }
            JSONObject previous = signIn.accountReference.isEmpty() ? null : record(signIn.accountReference);
            String issued = response.getQueryParameter("client_id");
            if (signIn.registering && (issued == null || !issued.startsWith("oaiapp_"))) {
                throw new SecurityException("动态注册未完成");
            }
            if (!signIn.registering && issued != null && !signIn.client.equals(issued)) {
                throw new SecurityException("返回的客户端身份不匹配");
            }
            String actualClient = signIn.registering ? issued : signIn.client;
            // Preserve an issued ID if code exchange expires; it is not an active account yet.
            if (previous == null) store.write("registration:pending", actualClient);
            String code = response.getQueryParameter("code");
            if (code == null || code.isEmpty()) throw new SecurityException("缺少授权码");
            TokenRequest token = authorizationCodeRequest(actualClient, code, signIn.redirectUri,
                    signIn.verifier, signIn.nonce);
            TokenResponse tokens = exchange(token, cancellation);
            JWTClaimsSet identity = validate(tokens.idToken, actualClient, signIn.nonce,
                    previous == null ? null : previous.getString("subject"), cancellation);
            String accountRef = previous == null ? matchingAccount(actualClient, identity.getSubject()) : signIn.accountReference;
            String label = identity.getStringClaim("email");
            if (label == null || label.isEmpty()) label = "ChatGPT 账号";
            JSONObject next = new JSONObject().put("client", actualClient)
                    .put("session", signIn.sessionId)
                    .put("subject", identity.getSubject()).put("label", label + " · " + actualClient.substring(0, Math.min(14, actualClient.length())))
                    .put("idToken", tokens.idToken).put("access", tokens.accessToken)
                    .put("refresh", tokens.refreshToken).put("expires", tokens.accessTokenExpirationTime)
                    .put("scope", tokens.scope == null ? "" : tokens.scope);
            synchronized (this) {
                if (generation != attempt || !signIn.sessionId.equals(pendingSignIn == null ? null : pendingSignIn.sessionId)) return;
                cancellation.check(); tokenRefresh.forget(accountRef); store.write(accountRef, next.toString());
                if (previous == null) store.remove("registration:pending");
                store.remove(PENDING_SIGN_IN); pendingSignIn = null; completingSignIn = false;
                updateStatus(granted(tokens.scope) ? AiSignInStatus.CONNECTED : AiSignInStatus.PERMISSION);
            }
            Account account = new Account(accountRef, next.getString("label"), granted(tokens.scope));
            main.post(() -> {
                synchronized (this) { if (generation != attempt) return; signInListener = SILENT; }
                accountListener.accept(account);
                listener.finished(account);
            });
        }
    }
    private String matchingAccount(String client, String subject) throws Exception {
        for (Account account : accounts()) {
            JSONObject value = record(account.reference);
            if (client.equals(value.optString("client")) && subject.equals(value.optString("subject"))) return account.reference;
        }
        return "oauth:" + UUID.randomUUID();
    }
    private void reportSignInFailure(long attempt, Listener listener, Exception failure) {
        main.post(() -> { synchronized (this) {
            if (generation != attempt) return;
            pendingSignIn = null; completingSignIn = false; discardPendingQuietly();
            if (failure instanceof SignInFailure) {
                SignInFailure specific = (SignInFailure) failure;
                failureDiagnostics = specific.diagnostics;
                updateStatus(specific.status);
            }
            else if (signInStatus == AiSignInStatus.EXCHANGING) updateStatus(AiSignInStatus.FAILED);
            listener.failed(signInStatus.message);
        } });
    }
    private PendingSignIn storedPending() throws Exception {
        String raw = store.read(PENDING_SIGN_IN);
        if (raw == null || raw.isEmpty()) return null;
        PendingSignIn result = PendingSignIn.fromJson(raw);
        long age = System.currentTimeMillis() - result.startedAt;
        if (age < 0 || age >= SIGN_IN_WINDOW_MILLIS) {
            store.remove(PENDING_SIGN_IN); return null;
        }
        return result;
    }
    private void discardPendingQuietly() {
        try { store.remove(PENDING_SIGN_IN); } catch (Exception ignored) { }
    }
    private void updateStatus(AiSignInStatus status) {
        signInStatus = status;
        try { store.write(SIGN_IN_STATUS, status.name()); } catch (Exception ignored) { }
    }
    private static Uri parseCallback(String callbackUri, PendingSignIn signIn) throws Exception {
        return Uri.parse(OAuthCallbackValidator.validate(callbackUri, signIn.redirectUri,
                signIn.state).toString());
    }
    private static final class PendingSignIn {
        final String sessionId, accountReference, client, verifier, state, nonce, redirectUri;
        final boolean registering;
        final long startedAt;
        PendingSignIn(String sessionId, String accountReference, String client, boolean registering,
                      String verifier, String state, String nonce, String redirectUri, long startedAt) {
            this.sessionId = sessionId; this.accountReference = accountReference; this.client = client;
            this.registering = registering; this.verifier = verifier; this.state = state;
            this.nonce = nonce; this.redirectUri = redirectUri; this.startedAt = startedAt;
        }
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("session", sessionId).put("reference", accountReference)
                    .put("client", client).put("registering", registering).put("verifier", verifier)
                    .put("state", state).put("nonce", nonce).put("redirect", redirectUri).put("started", startedAt);
        }
        static PendingSignIn fromJson(String raw) throws JSONException {
            JSONObject value = new JSONObject(raw);
            return new PendingSignIn(value.getString("session"), value.getString("reference"),
                    value.getString("client"), value.getBoolean("registering"), value.getString("verifier"),
                    value.getString("state"), value.getString("nonce"), value.getString("redirect"),
                    value.getLong("started"));
        }
    }
    @Override public String resolve(AiProfile profile) throws Exception {
        return resolve(profile, new AiCancellation());
    }
    @Override public String resolve(AiProfile profile, AiCancellation cancel) throws Exception {
        return tokenRefresh.resolve(profile, null, cancel);
    }
    @Override public String refreshAfterRejection(AiProfile profile, String rejected, AiCancellation cancel) throws Exception {
        return tokenRefresh.resolve(profile, rejected, cancel);
    }
    private JSONObject refreshTokens(JSONObject saved, AiCancellation cancel) throws Exception {
        try {
            cancel.check();
            // AppAuth cannot abort an in-flight grant. Finish and persist rotation even if the caller leaves.
            AiCancellation transaction = new AiCancellation();
            TokenRequest request = new TokenRequest.Builder(config, saved.getString("client"))
                    .setGrantType(GrantTypeValues.REFRESH_TOKEN).setRefreshToken(saved.getString("refresh"))
                    .setAdditionalParameters(Collections.singletonMap("resource", RESOURCE)).build();
            TokenResponse tokens = exchange(request, transaction);
            if (tokens.idToken != null) {
                saved.put("idToken", tokens.idToken);
                saved.put("verifyRefreshIdentity", true);
            }
            saved.put("access", tokens.accessToken).put("expires", tokens.accessTokenExpirationTime);
            if (tokens.refreshToken != null) saved.put("refresh", tokens.refreshToken);
            if (tokens.scope != null) saved.put("scope", tokens.scope);
            return saved;
        } catch (SignInFailure failed) {
            if (!failed.code.isEmpty()) throw new OAuthTokenRefresh.Failure(failed.code);
            throw new AiConnectionException(AiConnectionException.Status.UNAVAILABLE,
                    "暂时无法续期，登录信息已保留，请检查网络后重试");
        }
    }
    @Override public boolean signOut(String reference) throws Exception {
        cancelSignIn();
        synchronized (sessionLock) {
            return signOutLocked(reference);
        }
    }
    @Override public void clearLocal() throws Exception {
        cancelSignIn();
        synchronized (sessionLock) {
            for (String reference : new HashSet<>(store.references())) {
                tokenRefresh.forget(reference);
                if (reference.startsWith("oauth:") || reference.startsWith("registration:")
                        || reference.equals("host-id")) store.remove(reference);
            }
        }
    }
    private boolean signOutLocked(String reference) throws Exception {
        tokenRefresh.forget(reference);
        JSONObject saved = record(reference);
        boolean revoked = false;
        try {
            JSONObject metadata = metadata(new AiCancellation());
            String endpoint = metadata.getString("revocation_endpoint"); requireAuthUrl(endpoint);
            String body = "token=" + URLEncoder.encode(saved.getString("refresh"), "UTF-8")
                    + "&token_type_hint=refresh_token&client_id=" + URLEncoder.encode(saved.getString("client"), "UTF-8");
            http.request(endpoint, "POST", null, body, "application/x-www-form-urlencoded", new AiCancellation());
            revoked = true;
        } catch (Exception ignored) { }
        saved.remove("access"); saved.remove("refresh"); saved.remove("idToken"); saved.remove("scope");
        store.write(reference, saved.toString());
        main.post(() -> accountListener.accept(new Account(reference, saved.optString("label"), false)));
        return revoked;
    }
    private JSONObject record(String reference) throws Exception {
        if (reference == null || !reference.startsWith("oauth:")) throw new SecurityException("账号引用无效");
        String raw = store.read(reference);
        if (raw == null) throw new IllegalStateException("账号不存在");
        return new JSONObject(raw);
    }
    private TokenRequest authorizationCodeRequest(String client, String code, String redirectUri,
                                                  String verifier, String nonce) {
        return new TokenRequest.Builder(config, client)
                .setGrantType(GrantTypeValues.AUTHORIZATION_CODE).setAuthorizationCode(code)
                .setRedirectUri(Uri.parse(redirectUri)).setCodeVerifier(verifier).setNonce(nonce)
                .setAdditionalParameters(Collections.singletonMap("resource", RESOURCE)).build();
    }
    private TokenResponse exchange(TokenRequest request, AiCancellation cancel) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        TokenResponse[] result = new TokenResponse[1];
        AuthorizationException[] error = new AuthorizationException[1];
        service.performTokenRequest(request, (response, failure) -> {
            result[0] = response; error[0] = failure; done.countDown();
        });
        for (int i = 0; i < 60 && !done.await(1, TimeUnit.SECONDS); i++) cancel.check();
        cancel.check();
        if (result[0] == null || result[0].accessToken == null) {
            AiSignInStatus status = AiSignInStatus.EXCHANGE_FAILED;
            if (error[0] != null) {
                if ("invalid_grant".equals(error[0].error)) status = AiSignInStatus.INVALID_GRANT;
                else if ("invalid_client".equals(error[0].error)) status = AiSignInStatus.CLIENT_FAILED;
                else if (error[0].type == AuthorizationException.TYPE_GENERAL_ERROR
                        && error[0].code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code) {
                    status = AiSignInStatus.NETWORK_FAILED;
                } else if (error[0].type == AuthorizationException.TYPE_GENERAL_ERROR
                        && (error[0].code == AuthorizationException.GeneralErrors.ID_TOKEN_VALIDATION_ERROR.code
                        || error[0].code == AuthorizationException.GeneralErrors.ID_TOKEN_PARSING_ERROR.code)) {
                    status = AiSignInStatus.IDENTITY_FAILED;
                }
            }
            throw new SignInFailure(status, "token_error_type=" + (error[0] == null ? -1 : error[0].type)
                    + ";token_error_code=" + (error[0] == null ? -1 : error[0].code)
                    + ";network_error=" + networkFailure(error[0]), error[0] == null ? "" : error[0].error);
        }
        return result[0];
    }
    private JSONObject metadata(AiCancellation cancel) throws Exception {
        JSONObject result = new JSONObject(http.request("https://auth.openai.com/.well-known/openid-configuration",
                "GET", null, null, null, cancel));
        if (!"https://auth.openai.com".equals(result.getString("issuer"))) throw new SecurityException("签发方不匹配");
        return result;
    }
    private JWTClaimsSet validate(String token, String client, String nonce, String subject,
                                 AiCancellation cancel) throws Exception {
        try {
            String url = metadata(cancel).getString("jwks_uri"); requireAuthUrl(url);
            return OpenAiIdentityVerifier.verify(token,
                    http.request(url, "GET", null, null, null, cancel), client, nonce, subject);
        } catch (Exception failure) {
            cancel.check();
            throw new SignInFailure(AiSignInStatus.IDENTITY_FAILED);
        }
    }
    private static final class SignInFailure extends SecurityException {
        final AiSignInStatus status;
        final String diagnostics;
        final String code;
        SignInFailure(AiSignInStatus status) {
            this(status, "token_error_type=-1;token_error_code=-1;network_error=NONE");
        }
        SignInFailure(AiSignInStatus status, String diagnostics) {
            this(status, diagnostics, "");
        }
        SignInFailure(AiSignInStatus status, String diagnostics, String code) {
            super(status.message); this.status = status; this.diagnostics = diagnostics;
            this.code = code == null ? "" : code;
        }
    }
    private static String networkFailure(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof UnknownHostException) return "DNS";
            if (failure instanceof java.net.SocketTimeoutException) return "TIMEOUT";
            if (failure instanceof javax.net.ssl.SSLException) return "TLS";
            if (failure instanceof java.net.ConnectException) return "CONNECT";
        }
        return "NONE";
    }
    private static void requireAuthUrl(String value) throws java.io.IOException {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new java.io.IOException("授权端点不在官方来源");
        }
    }
    private static boolean granted(String scope) {
        return scope != null && Arrays.asList(scope.split(" ")).contains("chatgpt.tokens.use.direct");
    }
}
