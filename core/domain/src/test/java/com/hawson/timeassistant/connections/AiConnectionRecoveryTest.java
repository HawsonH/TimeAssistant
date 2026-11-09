package com.hawson.timeassistant.connections;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiConnectionRecoveryTest {
    private final Profiles profiles = new Profiles();
    private final AccountCatalog catalog = new AccountCatalog();
    private final AiAccountPort.Account account = new AiAccountPort.Account("oauth:fixture", "Fixture", true);
    private final AiConnectionRecovery recovery = new AiConnectionRecovery(profiles, catalog, () -> { });

    @Test public void firstAuthorizationCreatesModelAndActivates() throws Exception {
        AiProfile ready = recovery.recover(account, new AiCancellation());
        assertEquals("first-model", ready.model); assertEquals(ready.id, profiles.active().id);
        assertEquals(1, profiles.saves); assertEquals(1, profiles.activations);
    }
    @Test public void coldStartDoesNotRewriteCompleteProfileOrFetchModels() throws Exception {
        AiProfile ready = recovery.recover(account, new AiCancellation());
        catalog.offline = true;
        recovery.recover(account, new AiCancellation());
        assertEquals(ready.id, profiles.active().id);
        assertEquals(1, profiles.saves); assertEquals(1, profiles.activations); assertEquals(1, catalog.calls);
    }
    @Test public void otherActiveConnectionIsRetained() throws Exception {
        AiProfile other = profiles.save(new AiProfile("other", "Other", AiProfile.Kind.RELAY,
                AiProfile.Protocol.RESPONSES, "https://relay.example/v1", "other", "key:fixture"), null);
        profiles.activate(other.id);
        AiProfile ready = recovery.recover(account, new AiCancellation());
        assertEquals(other.id, profiles.active().id); assertNotEquals(other.id, ready.id);
    }
    @Test public void incompleteExistingProfileIsRepairedInPlace() throws Exception {
        profiles.save(chat("old", ""), null);
        assertEquals("old", recovery.recover(account, new AiCancellation()).id);
        assertEquals(1, profiles.list().size());
    }
    @Test public void emptyCatalogDoesNotInventModelOrDestroyMetadata() throws Exception {
        profiles.save(chat("old", ""), null); catalog.models = Collections.emptyList();
        try { recovery.recover(account, new AiCancellation()); fail(); }
        catch (AiConnectionException expected) { assertEquals("", profiles.list().get(0).model); }
        assertNull(profiles.active());
    }
    @Test public void offlineRepairPreservesAccountProfile() throws Exception {
        profiles.save(chat("old", ""), null); catalog.offline = true;
        try { recovery.recover(account, new AiCancellation()); fail(); }
        catch (java.io.IOException expected) { assertEquals("oauth:fixture", profiles.list().get(0).credentialRef); }
    }
    @Test public void ungrantedAccountCannotCreateConnection() throws Exception {
        try { recovery.recover(new AiAccountPort.Account(account.reference, "Fixture", false), new AiCancellation()); fail(); }
        catch (AiConnectionException expected) { assertEquals(AiConnectionException.Status.PERMISSION_DENIED, expected.status); }
        assertTrue(profiles.list().isEmpty()); assertEquals(0, catalog.calls);
    }
    @Test public void cancelledCatalogCannotCommitConnection() throws Exception {
        AiCancellation cancel = new AiCancellation(); catalog.onModels = cancel::cancel;
        try { recovery.recover(account, cancel); fail(); }
        catch (java.io.InterruptedIOException expected) { assertTrue(profiles.list().isEmpty()); }
    }
    @Test public void secondAccountDoesNotTakeOverExistingSelection() throws Exception {
        AiProfile first = recovery.recover(account, new AiCancellation());
        recovery.recover(new AiAccountPort.Account("oauth:second", "Second", true), new AiCancellation());
        assertEquals(first.id, profiles.active().id); assertEquals(2, profiles.list().size());
    }
    private AiProfile chat(String id, String model) {
        return new AiProfile(id, "ChatGPT", AiProfile.Kind.CHATGPT, AiProfile.Protocol.RESPONSES,
                "https://api.openai.com/v1", model, account.reference);
    }
    private static final class AccountCatalog implements AiPort {
        int calls; boolean offline; Runnable onModels = () -> { };
        List<String> models = Arrays.asList("first-model", "second-model");
        public List<String> models(AiProfile profile, AiCancellation cancel) throws Exception {
            calls++; onModels.run(); if (offline) throw new java.io.IOException("Offline fixture"); return models;
        }
        public String complete(AiProfile profile, AiRequest request, AiCancellation cancel) { throw new AssertionError("No inference during recovery"); }
    }
    private static final class Profiles implements AiProfilesPort {
        final Map<String, AiProfile> values = new LinkedHashMap<>(); String active; int saves, activations;
        public List<AiProfile> list() { return new ArrayList<>(values.values()); }
        public AiProfile active() { return values.get(active); }
        public AiProfile save(AiProfile draft, String key) {
            saves++; AiProfile saved = draft.withIdentity(draft.id.isEmpty() ? "p" + saves : draft.id, draft.credentialRef);
            values.put(saved.id, saved); return saved;
        }
        public void activate(String id) { activations++; active = id; }
        public void delete(String id) { values.remove(id); }
        public AiProfile duplicate(String id) { throw new UnsupportedOperationException(); }
        public String contextBoundary() { return "boundary-" + activations; }
        public long contextStartedAt() { return activations; }
    }
}
