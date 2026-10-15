package com.example.proxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Route-specific deadline budgets for new requests entering the API gateway. */
public final class GatewayDeadlineRules {
    public record DeadlineBudget(String method, String route, long deadlineMillis) {}
    public record Selection(String route, long deadlineMillis, boolean configured) {}
    private record Rule(String method, String path, long deadlineMillis, int literalSegments) {}

    private final List<Rule> rules;

    private GatewayDeadlineRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Parses semicolon-separated entries in the form {@code METHOD /path=milliseconds}.
     * A method may be {@code *}; path segments written as {@code {name}} match any
     * single segment. Query strings are not part of the path used for matching.
     */
    public static GatewayDeadlineRules parse(String value) {
        List<Rule> parsed = new ArrayList<>();
        if (value == null || value.isBlank()) return new GatewayDeadlineRules(parsed);
        for (String entry : value.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int equals = trimmed.lastIndexOf('=');
            if (equals < 0) throw invalid(entry);
            String selector = trimmed.substring(0, equals).trim();
            String budget = trimmed.substring(equals + 1).trim();
            String[] parts = selector.split("\\s+", 2);
            if (parts.length != 2) throw invalid(entry);
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1].trim();
            if (!path.startsWith("/") || path.contains("?") || path.contains("#")) throw invalid(entry);
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            long millis;
            try {
                millis = Long.parseLong(budget);
            } catch (NumberFormatException ex) {
                throw invalid(entry);
            }
            if (millis <= 0) throw new IllegalArgumentException("Gateway route deadline must be greater than 0: " + entry);
            int literals = (int) java.util.Arrays.stream(path.split("/"))
                    .filter(segment -> !segment.isEmpty() && !(segment.startsWith("{") && segment.endsWith("}")))
                    .count();
            parsed.add(new Rule(method, path, millis, literals));
        }
        parsed.sort(Comparator.comparingInt(Rule::literalSegments).reversed()
                .thenComparing(rule -> rule.method().equals("*") ? 1 : 0));
        return new GatewayDeadlineRules(parsed);
    }

    public long deadlineMillis(String method, String requestTarget, long defaultMillis) {
        return select(method, requestTarget, defaultMillis).deadlineMillis();
    }

    public Selection select(String method, String requestTarget, long defaultMillis) {
        String path = requestTarget;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int fragment = path.indexOf('#');
        if (fragment >= 0) path = path.substring(0, fragment);
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        for (Rule rule : rules) {
            if (!(rule.method().equals("*") || rule.method().equals(normalizedMethod))) continue;
            if (matches(rule.path(), path)) return new Selection(rule.path(), rule.deadlineMillis(), true);
        }
        return new Selection("/_unmatched", defaultMillis, false);
    }

    /** Configured route budgets for bounded-cardinality Prometheus labels. */
    public List<DeadlineBudget> metricBudgets() {
        List<DeadlineBudget> budgets = new ArrayList<>();
        for (Rule rule : rules) {
            budgets.add(new DeadlineBudget(rule.method(), rule.path(), rule.deadlineMillis()));
        }
        return List.copyOf(budgets);
    }

    private static boolean matches(String pattern, String path) {
        String[] expected = pattern.split("/", -1);
        String[] actual = path.split("/", -1);
        if (expected.length != actual.length) return false;
        for (int i = 0; i < expected.length; i++) {
            String segment = expected[i];
            boolean variable = segment.startsWith("{") && segment.endsWith("}") && segment.length() > 2;
            if (!variable && !segment.equals(actual[i])) return false;
            if (variable && actual[i].isEmpty()) return false;
        }
        return true;
    }

    private static IllegalArgumentException invalid(String entry) {
        return new IllegalArgumentException("Invalid GATEWAY_DEADLINE_RULES entry '" + entry
                + "'; expected METHOD /path=milliseconds (entries separated by ';')");
    }
}
