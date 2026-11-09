package com.hawson.timeassistant.data.local;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreferencePolicyTest {
    @Test public void explicitAndSpecificRulesWinResolutionOrder() {
        PreferenceRuleEntity inferredGlobal = rule("inferred-global", PreferenceRuleEntity.SCOPE_GLOBAL,
                "", PreferenceRuleEntity.ORIGIN_INFERRED, 600, 720);
        PreferenceRuleEntity explicitGlobal = rule("explicit-global", PreferenceRuleEntity.SCOPE_GLOBAL,
                "", PreferenceRuleEntity.ORIGIN_EXPLICIT, 480, 600);
        PreferenceRuleEntity inferredProject = rule("project", PreferenceRuleEntity.SCOPE_PROJECT,
                "写作", PreferenceRuleEntity.ORIGIN_INFERRED, 540, 660);

        List<PreferenceRuleEntity> resolved = PreferencePolicy.resolve(
                Arrays.asList(inferredGlobal, explicitGlobal, inferredProject),
                "写作", Arrays.asList("深度"), System.currentTimeMillis());

        assertEquals("project", resolved.get(0).id);
        assertEquals("explicit-global", resolved.get(1).id);
        assertEquals("inferred-global", resolved.get(2).id);
    }

    @Test public void candidateRequiresConfidenceEvidenceAndTwoDates() {
        PreferenceRuleEntity candidate = rule("candidate", PreferenceRuleEntity.SCOPE_GLOBAL,
                "", PreferenceRuleEntity.ORIGIN_INFERRED, 480, 600);
        candidate.status = PreferenceRuleEntity.STATUS_CANDIDATE;
        candidate.confidence = 0.70d;
        candidate.expiresAt = System.currentTimeMillis() + 1000;
        assertTrue(PreferencePolicy.isCandidateEligible(candidate, 2, 3,
                System.currentTimeMillis()));
        assertFalse(PreferencePolicy.isCandidateEligible(candidate, 1, 3,
                System.currentTimeMillis()));
        candidate.confidence = 0.69d;
        assertFalse(PreferencePolicy.isCandidateEligible(candidate, 2, 3,
                System.currentTimeMillis()));
    }

    private static PreferenceRuleEntity rule(String id, String scope, String scopeValue,
                                              String origin, int start, int end) {
        PreferenceRuleEntity rule = new PreferenceRuleEntity();
        rule.id = id; rule.scopeType = scope; rule.scopeValue = scopeValue;
        rule.origin = origin; rule.status = PreferenceRuleEntity.STATUS_ACTIVE;
        rule.type = PreferenceRuleEntity.TYPE_PREFERRED_WINDOW;
        rule.startMinute = start; rule.endMinute = end;
        return rule;
    }
}
