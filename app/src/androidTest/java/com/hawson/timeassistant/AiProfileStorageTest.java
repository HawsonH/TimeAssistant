package com.hawson.timeassistant;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.data.connections.LocalAiProfiles;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Isolated preference namespace only; does not change the QA app's saved connections. */
public class AiProfileStorageTest {
    private Context context;
    private MemoryCredentials keys;
    @Before public void prepare() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context = new android.content.ContextWrapper(target) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("test_ai_profiles_" + name, mode);
            }
        };
        context.getSharedPreferences(LocalAiProfiles.FILE, 0).edit().clear().commit();
        keys = new MemoryCredentials();
    }
    @After public void clear() { context.getSharedPreferences(LocalAiProfiles.FILE, 0).edit().clear().commit(); }
    @Test public void migrationIsIdempotentAndKeepsIdentity() throws Exception {
        LocalAiProfiles profiles = new LocalAiProfiles(context, keys);
        profiles.migrate("https://api.openai.com/v1", "model", "secret");
        profiles.migrate("https://different.example/v1", "other", "different");
        assertEquals(1, profiles.list().size());
        assertEquals("legacy-default", profiles.active().id);
        assertEquals("secret", keys.read(profiles.active().credentialRef));
        assertEquals(0, profiles.contextStartedAt());
    }
    @Test public void copyContainsNoSecretAndCannotActivate() throws Exception {
        LocalAiProfiles profiles = new LocalAiProfiles(context, keys);
        AiProfile saved = profiles.save(draft(), "secret");
        profiles.activate(saved.id);
        AiProfile copy = profiles.duplicate(saved.id);
        assertEquals("", copy.credentialRef);
        try { profiles.activate(copy.id); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(saved.id, profiles.active().id);
    }
    @Test public void credentialWriteFailureRetainsOldSelection() throws Exception {
        LocalAiProfiles profiles = new LocalAiProfiles(context, keys);
        AiProfile original = profiles.save(draft(), "original"); profiles.activate(original.id);
        keys.fail = true;
        try { profiles.save(draft(), "replacement"); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, profiles.list().size()); assertEquals(original.id, profiles.active().id);
    }
    @Test public void deletingOneConnectionKeepsOtherKey() throws Exception {
        LocalAiProfiles profiles = new LocalAiProfiles(context, keys);
        AiProfile one = profiles.save(draft(), "one"), two = profiles.save(draft(), "two");
        profiles.delete(one.id);
        assertEquals("two", keys.read(two.credentialRef));
        assertNull(keys.read(one.credentialRef));
    }
    private AiProfile draft() {
        return new AiProfile("", "relay", AiProfile.Kind.RELAY, AiProfile.Protocol.CHAT_COMPLETIONS,
                "https://relay.example/v1", "model", "");
    }
    @Test public void replacingKeyReleasesOnlyOldCredential() throws Exception {
        LocalAiProfiles profiles = new LocalAiProfiles(context, keys);
        AiProfile original = profiles.save(draft(), "old"), other = profiles.save(draft(), "other");
        AiProfile updated = profiles.save(original, "new");
        assertNull(keys.read(original.credentialRef));
        assertEquals("new", keys.read(updated.credentialRef));
        assertEquals("other", keys.read(other.credentialRef));
    }
    private static class MemoryCredentials implements AiCredentialsPort {
        final Map<String, String> values = new HashMap<>();
        boolean fail;
        public String read(String ref) { return values.get(ref); }
        public void write(String ref, String value) { if (fail) throw new IllegalStateException("fail"); values.put(ref, value); }
        public void remove(String ref) { values.remove(ref); }
        public Set<String> references() { return new HashSet<>(values.keySet()); }
    }
}
