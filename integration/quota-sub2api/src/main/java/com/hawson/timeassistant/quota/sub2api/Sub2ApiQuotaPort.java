package com.hawson.timeassistant.quota.sub2api;

import com.hawson.timeassistant.ai.AiTransportPolicy;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.network.AiHttp;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import org.json.JSONObject;

/** Only GET quota is supported: no credit consumption, account import or OAuth forwarding. */
public final class Sub2ApiQuotaPort implements AiQuotaPort {
    public interface Sources { Source resolve(String reference) throws Exception; }
    public interface Credentials { String resolve(String sourceReference) throws Exception; }
    public static final class Source {
        public final String root, accountId, credentialReference;
        public final boolean bearer;
        public Source(String root, String accountId, boolean bearer) {
            this(root, accountId, bearer, null);
        }
        public Source(String root, String accountId, boolean bearer, String credentialReference) {
            this.root = root; this.accountId = accountId; this.bearer = bearer;
            this.credentialReference = credentialReference;
        }
    }
    private final AiTransportPolicy policy;
    private final Sources sources;
    private final Credentials credentials;
    public Sub2ApiQuotaPort(AiTransportPolicy policy, Sources sources, Credentials credentials) {
        this.policy = policy; this.sources = sources; this.credentials = credentials;
    }
    @Override public AiQuotaSnapshot read(String reference, AiCancellation cancel) throws Exception {
        cancel.check();
        Source source = sources.resolve(reference);
        String endpoint = endpoint(source);
        policy.requireAllowed(endpoint);
        String key = credentials.resolve(source.credentialReference == null ? reference : source.credentialReference);
        if (key == null || key.isEmpty() || key.contains("\r") || key.contains("\n"))
            throw new IOException("请配置此 sub2api 来源的独立凭据");
        HttpURLConnection connection = policy.open(URI.create(endpoint).toURL());
        cancel.onCancel(connection::disconnect);
        try {
            cancel.check();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(source.bearer ? "Authorization" : "x-api-key", source.bearer ? "Bearer " + key : key);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException(status == 401 || status == 403
                    ? "sub2api 来源凭据或权限未被接受，不影响官方登录"
                    : status >= 300 && status < 400 ? "额度来源重定向已拒绝" : "额度来源暂不可用（" + status + "）");
            JSONObject body = new JSONObject(AiHttp.read(connection.getInputStream(), 256 * 1024));
            if (body.has("code") && (!(body.opt("code") instanceof Number)
                    || ((Number) body.opt("code")).doubleValue() != 0d))
                throw new IOException("额度来源返回失败，未更新快照");
            JSONObject data = body.has("data") ? body.optJSONObject("data") : body;
            if (data == null || (!data.has("rate_limit") && !data.has("plan_type")
                    && !data.has("additional_rate_limits") && !data.has("credits"))) throw new IOException("额度返回格式不支持");
            cancel.check();
            return Sub2ApiQuotaCodec.decode(data, System.currentTimeMillis());
        } finally { connection.disconnect(); }
    }
    public static String endpoint(Source source) {
        if (source == null || source.accountId == null || !source.accountId.matches("[1-9][0-9]{0,17}"))
            throw new IllegalArgumentException("请填写有效 sub2api 账号 ID");
        String root = source.root == null ? "" : source.root.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        URI uri = URI.create(root);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("额度来源地址无效");
        return (root.endsWith("/api/v1") ? root : root + "/api/v1") + "/admin/openai/accounts/" + source.accountId + "/quota";
    }
}
