package com.structurereset.api;

import java.util.Map;

/**
 * Substitutes {@code ${key}} placeholders in a template string.
 *
 * <p>Unknown keys and malformed placeholders are preserved verbatim, so a
 * mistyped placeholder reaches the user instead of silently disappearing.</p>
 */
public final class Placeholders {

    private Placeholders() {
    }

    /**
     * Resolves every known placeholder.
     *
     * @param template the template, null yields an empty string
     * @param vars the replacement values, null behaves like an empty map
     * @return the resolved string, never null
     */
    public static String resolve(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (vars == null || vars.isEmpty()) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length());
        int index = 0;
        while (index < template.length()) {
            int start = template.indexOf("${", index);
            if (start < 0) {
                out.append(template, index, template.length());
                break;
            }
            int end = template.indexOf('}', start + 2);
            if (end < 0) {
                out.append(template, index, template.length());
                break;
            }
            out.append(template, index, start);
            String key = template.substring(start + 2, end);
            String value = vars.get(key);
            if (value == null) {
                out.append(template, start, end + 1);
            } else {
                out.append(value);
            }
            index = end + 1;
        }
        return out.toString();
    }
}
