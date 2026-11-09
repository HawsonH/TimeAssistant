package com.hawson.timeassistant.connections;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable provider report; unknown values remain null and expired windows never refill locally. */
public final class AiQuotaSnapshot {
    public static final long FRESH_FOR_MS = 5 * 60 * 1000;
    public final String accountId, email, plan;
    public final long observedAt, receivedAt;
    public final List<Limit> limits;
    public final Credits credits;
    public final Integer resetCredits;

    public AiQuotaSnapshot(String accountId, String email, String plan, long observedAt, long receivedAt,
                           List<Limit> limits, Credits credits, Integer resetCredits) {
        this.accountId = accountId; this.email = email; this.plan = plan;
        this.observedAt = observedAt; this.receivedAt = receivedAt;
        this.limits = Collections.unmodifiableList(new ArrayList<>(limits));
        this.credits = credits; this.resetCredits = resetCredits;
    }
    public boolean stale(long now) {
        return observedAt <= 0 || observedAt > now || now - observedAt > FRESH_FOR_MS;
    }
    public static final class Limit {
        public final String name;
        public final Boolean allowed, reached;
        public final List<Window> windows;
        public Limit(String name, Boolean allowed, Boolean reached, List<Window> windows) {
            this.name = name; this.allowed = allowed; this.reached = reached;
            this.windows = Collections.unmodifiableList(new ArrayList<>(windows));
        }
    }
    public static final class Window {
        public final String slot;
        public final Long seconds, resetAt;
        public final Double usedPercent;
        public Window(String slot, Long seconds, Double usedPercent, Long resetAt) {
            this.slot = slot; this.seconds = seconds; this.usedPercent = usedPercent; this.resetAt = resetAt;
        }
        public Double remainingPercent() { return usedPercent == null ? null : Math.max(0, 100 - usedPercent); }
        public boolean resetPassed(long now) { return resetAt != null && resetAt <= now; }
    }
    public static final class Credits {
        public final Boolean available, unlimited;
        public final String balance;
        public Credits(Boolean available, Boolean unlimited, String balance) {
            this.available = available; this.unlimited = unlimited; this.balance = balance;
        }
    }
}
