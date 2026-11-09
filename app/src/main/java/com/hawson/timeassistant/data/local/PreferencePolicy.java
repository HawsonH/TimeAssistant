package com.hawson.timeassistant.data.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure preference validation/resolution rules shared by UI, prompt compiler, and tests. */
public final class PreferencePolicy {
    private PreferencePolicy() {}

    public static boolean isValid(PreferenceRuleEntity rule) {
        if (rule == null || rule.id == null || rule.id.trim().isEmpty()) return false;
        if (!PreferenceRuleEntity.TYPE_PREFERRED_WINDOW.equals(rule.type)
                && !PreferenceRuleEntity.TYPE_AVOID_WINDOW.equals(rule.type)) return false;
        if (!PreferenceRuleEntity.SCOPE_GLOBAL.equals(rule.scopeType)
                && !PreferenceRuleEntity.SCOPE_PROJECT.equals(rule.scopeType)
                && !PreferenceRuleEntity.SCOPE_TAG.equals(rule.scopeType)) return false;
        if (!PreferenceRuleEntity.SCOPE_GLOBAL.equals(rule.scopeType)
                && rule.scopeValue.trim().isEmpty()) return false;
        return rule.startMinute >= 0 && rule.startMinute < 1440
                && rule.endMinute >= 0 && rule.endMinute < 1440
                && rule.startMinute != rule.endMinute && validDays(rule.daysOfWeek);
    }

    public static boolean isCandidateEligible(PreferenceRuleEntity rule, int distinctEvidenceDays,
                                              int evidenceCount, long now) {
        return isValid(rule) && PreferenceRuleEntity.ORIGIN_INFERRED.equals(rule.origin)
                && PreferenceRuleEntity.STATUS_CANDIDATE.equals(rule.status)
                && rule.confidence >= 0.70d && evidenceCount >= 3 && distinctEvidenceDays >= 2
                && (rule.expiresAt == 0 || rule.expiresAt > now);
    }

    public static List<PreferenceRuleEntity> resolve(List<PreferenceRuleEntity> rules,
                                                      String project, List<String> tags,
                                                      long now) {
        List<PreferenceRuleEntity> result = new ArrayList<>();
        Set<String> normalizedTags = new HashSet<>();
        if (tags != null) for (String tag : tags) normalizedTags.add(normalize(tag));
        for (PreferenceRuleEntity source : rules == null
                ? Collections.<PreferenceRuleEntity>emptyList() : rules) {
            if (!PreferenceRuleEntity.STATUS_ACTIVE.equals(source.status) || !isValid(source)) continue;
            if (source.expiresAt > 0 && source.expiresAt <= now) continue;
            if (PreferenceRuleEntity.SCOPE_PROJECT.equals(source.scopeType)
                    && !normalize(source.scopeValue).equals(normalize(project))) continue;
            if (PreferenceRuleEntity.SCOPE_TAG.equals(source.scopeType)
                    && !normalizedTags.contains(normalize(source.scopeValue))) continue;
            result.add(source.copy());
        }
        result.sort((first, second) -> {
            int scope = Integer.compare(scopeRank(second), scopeRank(first));
            if (scope != 0) return scope;
            int origin = Integer.compare(originRank(second), originRank(first));
            if (origin != 0) return origin;
            return Long.compare(second.updatedAt, first.updatedAt);
        });
        return Collections.unmodifiableList(result);
    }

    public static String fingerprint(PreferenceRuleEntity rule) {
        return normalize(rule.type) + '|' + normalize(rule.scopeType) + '|'
                + normalize(rule.scopeValue) + '|' + normalize(rule.daysOfWeek) + '|'
                + rule.startMinute + '|' + rule.endMinute;
    }

    private static boolean validDays(String value) {
        if (value == null || value.trim().isEmpty()) return true;
        for (String raw : value.split(",")) {
            try {
                int day = Integer.parseInt(raw.trim());
                if (day < 1 || day > 7) return false;
            } catch (NumberFormatException error) { return false; }
        }
        return true;
    }

    private static int scopeRank(PreferenceRuleEntity rule) {
        return PreferenceRuleEntity.SCOPE_GLOBAL.equals(rule.scopeType) ? 0 : 1;
    }

    private static int originRank(PreferenceRuleEntity rule) {
        return PreferenceRuleEntity.ORIGIN_EXPLICIT.equals(rule.origin) ? 1 : 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
