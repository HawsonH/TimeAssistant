package com.hawson.timeassistant.data.connections;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import android.content.ContentValues;
import com.hawson.timeassistant.connections.*;

/** Local call metadata only. No prompt, response, recording, or credential is stored. */
public final class AiUsageStore extends SQLiteOpenHelper implements AiCallObserver {
    public AiUsageStore(Context context) { super(context, "ai_call_usage.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, started INTEGER NOT NULL, finished INTEGER, profile TEXT, account TEXT, model TEXT, kind TEXT, outcome TEXT, input INTEGER, output INTEGER, cached INTEGER)");
        db.execSQL("CREATE INDEX calls_started ON calls(started)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int before, int after) { throw new IllegalStateException("Unsupported usage database version"); }
    @Override public Call begin(AiProfile profile, String kind) {
        String id = java.util.UUID.randomUUID().toString();
        ContentValues row = new ContentValues(); row.put("id", id); row.put("started", System.currentTimeMillis());
        row.put("profile", profile.id); row.put("account", profile.credentialRef); row.put("model", profile.model);
        row.put("kind", kind); row.put("outcome", "running");
        try { getWritableDatabase().insertOrThrow("calls", null, row); }
        catch (SQLiteException unavailable) {
            return new Call() { public void usage(Usage value) { } public void finish(String outcome) { } };
        }
        return new Call() {
            boolean done;
            @Override public synchronized void usage(Usage value) {
                if (done || value == null) return;
                ContentValues update = new ContentValues();
                if (value.input != null) update.put("input", value.input);
                if (value.output != null) update.put("output", value.output);
                if (value.cached != null) update.put("cached", value.cached);
                if (update.size() > 0) try { getWritableDatabase().update("calls", update, "id=?", new String[]{id}); }
                catch (SQLiteException unavailable) { }
            }
            @Override public synchronized void finish(String outcome) {
                if (done) return; done = true;
                ContentValues update = new ContentValues(); update.put("outcome", outcome);
                update.put("finished", System.currentTimeMillis());
                try { getWritableDatabase().update("calls", update, "id=?", new String[]{id}); }
                catch (SQLiteException unavailable) { }
            }
        };
    }
    public void clear() { getWritableDatabase().delete("calls", null, null); }
    public String summary(long since, String reference) {
        String where = "started>=?" + (reference == null ? "" : " AND account=?");
        String[] args = reference == null ? new String[]{"" + since} : new String[]{"" + since, reference};
        StringBuilder text = new StringBuilder();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT kind, COUNT(*), SUM(CASE WHEN outcome='success' THEN 1 ELSE 0 END), SUM(CASE WHEN outcome='failed' THEN 1 ELSE 0 END), SUM(CASE WHEN outcome='cancelled' THEN 1 ELSE 0 END), SUM(input), SUM(output), SUM(cached), SUM(CASE WHEN input IS NULL AND output IS NULL THEN 1 ELSE 0 END), SUM(CASE WHEN outcome='running' THEN 1 ELSE 0 END) FROM calls WHERE " + where + " GROUP BY kind", args)) {
            while (rows.moveToNext()) {
                if (text.length() > 0) text.append("\n\n");
                text.append("speech".equals(rows.getString(0)) ? "语音转写" : "模型调用").append(" · ").append(rows.getLong(1)).append(" 次\n成功 ")
                    .append(rows.getLong(2)).append(" · 失败 ").append(rows.getLong(3)).append(" · 取消 ").append(rows.getLong(4));
                if (rows.getLong(9) > 0) text.append(" · 处理中/已中断 ").append(rows.getLong(9));
                text.append("\n输入 Token：").append(rows.isNull(5) ? "未返回" : rows.getString(5))
                    .append("\n输出 Token：").append(rows.isNull(6) ? "未返回" : rows.getString(6));
                if (!rows.isNull(7)) text.append("\n缓存输入 Token：").append(rows.getString(7));
                if (rows.getLong(8) > 0) text.append("\n其中 ").append(rows.getLong(8)).append(" 次未返回 Token 用量");
            }
        }
        return text.length() == 0 ? "此期间暂无调用记录" : text.toString();
    }
}
