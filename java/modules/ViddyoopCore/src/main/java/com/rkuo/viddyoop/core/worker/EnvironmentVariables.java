package com.rkuo.viddyoop.core.worker;

import java.util.Map;

/** Canonical application environment names, with a fallback for pre-rename deployments. */
final class EnvironmentVariables {
    private EnvironmentVariables() {}

    static String get(String name, String fallback) {
        return get(System.getenv(), name, fallback);
    }

    static String get(Map<String, String> values, String name, String fallback) {
        String value = values.get(name);
        if (value == null && name.startsWith("VIDDYOOP_")) {
            value = values.get("VIDYOOP_" + name.substring("VIDDYOOP_".length()));
        }
        return value == null ? fallback : value;
    }
}
