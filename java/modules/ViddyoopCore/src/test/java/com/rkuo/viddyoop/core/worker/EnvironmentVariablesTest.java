package com.rkuo.viddyoop.core.worker;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class EnvironmentVariablesTest {
    @Test public void correctedNameWinsOverLegacyAlias() {
        assertEquals("current", EnvironmentVariables.get(Map.of(
                "VIDDYOOP_WORK", "current", "VIDYOOP_WORK", "legacy"), "VIDDYOOP_WORK", "default"));
    }

    @Test public void legacyConfigurationStillWorks() {
        assertEquals("legacy", EnvironmentVariables.get(Map.of("VIDYOOP_WORK", "legacy"),
                "VIDDYOOP_WORK", "default"));
    }

    @Test public void missingConfigurationUsesDefault() {
        assertEquals("default", EnvironmentVariables.get(Map.of(), "VIDDYOOP_WORK", "default"));
        assertNull(EnvironmentVariables.get(Map.of(), "VIDDYOOP_ITUNES_REJECTED", null));
    }

    @Test public void explicitEmptyCanonicalValueDoesNotReviveLegacyPath() {
        assertEquals("", EnvironmentVariables.get(Map.of(
                "VIDDYOOP_ITUNES_REJECTED", "", "VIDYOOP_ITUNES_REJECTED", "/legacy"),
                "VIDDYOOP_ITUNES_REJECTED", null));
    }

    @Test public void unrelatedNamesDoNotGetAnAlias() {
        assertEquals("fallback", EnvironmentVariables.get(Map.of("VIDYOOP_WORK", "legacy"),
                "OTHER_WORK", "fallback"));
    }
}
