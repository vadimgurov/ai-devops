package com.vadim.devops.kb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vadim.devops.config.DevopsProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private static final Set<String> SUBCOMMAND_BASES = Set.of(
            "docker", "systemctl", "docker-compose", "docker-compose-plugin");
    private static final Set<String> OPTION_KEY_BASES = Set.of(
            "mysql", "psql", "mongosh", "journalctl", "java");
    private static final Set<String> MODULE_BASES = Set.of("python", "python3");
    private static final Set<String> SKIPPED_ALWAYS_ALLOW_BASES = Set.of(
            "sleep", "echo", "true", "false", ":");
    private static final Pattern LEADING_ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=");

    private final Path registryFile;
    private final ObjectMapper yaml;
    private Registry registry = new Registry(
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

    public CommandRegistry(DevopsProperties props, @Qualifier("yamlMapper") ObjectMapper yaml) {
        this.registryFile = Path.of(props.kb().path(), "allowed_commands.yaml");
        this.yaml = yaml;
    }

    @PostConstruct
    void load() {
        if (!Files.exists(registryFile)) {
            log.info("No allowed_commands.yaml found, starting with empty registry");
            return;
        }
        try {
            registry = yaml.readValue(registryFile.toFile(), Registry.class);
            log.info("Loaded {} read-only, {} write, {} filter prefixes",
                    registry.readOnlyPrefixes().size(),
                    registry.writeActionPrefixes().size(),
                    registry.pipeFilters().size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public enum Classification { READ_ONLY, WRITE_ACTION, UNKNOWN }

    public synchronized Classification classify(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) return Classification.UNKNOWN;

        var overall = Classification.READ_ONLY;

        for (var chainPart : splitTopLevelOperators(rawCommand, Mode.CHAIN)) {
            var trimmed = chainPart.text().trim();
            if (trimmed.isEmpty()) continue;

            var stages = splitTopLevelOperators(trimmed, Mode.PIPELINE);
            if (stages.isEmpty()) continue;

            for (int i = 0; i < stages.size(); i++) {
                var stage = stages.get(i).text().trim();
                var stageClass = classifyStage(stage);
                if (stageClass == Classification.UNKNOWN) {
                    if (i == 0) {
                        log.debug("Unknown stage '{}', escalating to approval: {}", stage, rawCommand);
                    } else {
                        log.debug("Unknown pipeline stage '{}', escalating to approval: {}", stage, rawCommand);
                    }
                    return Classification.UNKNOWN;
                }
                if (stageClass.ordinal() > overall.ordinal()) overall = stageClass;
            }
        }

        return overall;
    }

    public synchronized void addReadOnlyPrefix(String command) {
        var added = new ArrayList<String>();
        for (var prefix : canonicalReadOnlyPrefixes(command)) {
            if (registry.readOnlyPrefixes().stream().anyMatch(prefix::equals)) continue;
            registry.readOnlyPrefixes().add(prefix);
            added.add(prefix);
        }
        if (added.isEmpty()) return;
        persist();
        added.forEach(prefix -> log.info("Added read-only prefix to registry: {}", prefix));
    }

    public synchronized List<String> allReadOnly() { return List.copyOf(registry.readOnlyPrefixes()); }
    public synchronized List<String> allWriteActions() { return List.copyOf(registry.writeActionPrefixes()); }

    // ── Private ───────────────────────────────────────────────────────────────

    private Classification classifyStage(String cmd) {
        var substitutions = extractCommandSubstitutions(cmd);
        for (var substitution : substitutions) {
            var nested = classify(substitution);
            if (nested != Classification.READ_ONLY) {
                log.debug("Command substitution is not read-only: {}", substitution);
                return Classification.UNKNOWN;
            }
        }

        var normalized = normalizeForMatching(cmd);
        if (normalized.isEmpty()) return substitutions.isEmpty() ? Classification.UNKNOWN : Classification.READ_ONLY;

        if (isSed(normalized) && containsFlag(normalized, "-i")) {
            return Classification.WRITE_ACTION;
        }

        var candidates = matchingCandidates(normalized);
        for (var prefix : registry.writeActionPrefixes()) {
            if (candidates.stream().anyMatch(candidate -> matches(candidate, prefix))) {
                return Classification.WRITE_ACTION;
            }
        }
        for (var prefix : registry.readOnlyPrefixes()) {
            if (candidates.stream().anyMatch(candidate -> matches(candidate, prefix))) {
                return Classification.READ_ONLY;
            }
        }
        for (var filter : registry.pipeFilters()) {
            if (candidates.stream().anyMatch(candidate -> matches(candidate, filter))) {
                return Classification.READ_ONLY;
            }
        }
        return Classification.UNKNOWN;
    }

    private String stripWrappers(String cmd) {
        var result = cmd;
        boolean stripped;
        do {
            stripped = false;
            for (var wrapper : registry.stripPrefixes()) {
                if (result.startsWith(wrapper + " ") || result.equals(wrapper)) {
                    result = result.substring(wrapper.length()).trim();
                    // timeout/nice take a numeric argument — strip it too
                    if (wrapper.equals("timeout") || wrapper.equals("nice")) {
                        result = result.replaceFirst("^-?\\S+\\s*", "").trim();
                    }
                    stripped = true;
                    break;
                }
            }
        } while (stripped && !result.isEmpty());
        return result;
    }

    private String normalizeForMatching(String cmd) {
        var normalized = stripWrappers(cmd.trim());
        if (normalized.isEmpty()) return "";

        while (true) {
            var withoutAssignments = stripLeadingAssignments(normalized);
            if (withoutAssignments.equals(normalized)) break;
            normalized = withoutAssignments;
        }

        normalized = stripRedirections(normalized).trim();
        if (normalized.isEmpty()) return "";
        return normalizeSpacing(replaceCommandSubstitutions(normalized));
    }

    private List<String> canonicalReadOnlyPrefixes(String command) {
        var prefixes = new ArrayList<String>();
        var seen = new HashSet<String>();
        collectReadOnlyPrefixes(command, prefixes, seen);
        return prefixes;
    }

    private void collectReadOnlyPrefixes(String command, List<String> prefixes, Set<String> seen) {
        for (var chainPart : splitTopLevelOperators(command, Mode.CHAIN)) {
            var segment = chainPart.text().trim();
            if (segment.isEmpty()) continue;

            var classification = classifyStage(segment);
            if (classification == Classification.WRITE_ACTION) continue;

            for (var substitution : extractCommandSubstitutions(segment)) {
                collectReadOnlyPrefixes(substitution, prefixes, seen);
            }

            var normalized = normalizeForMatching(segment);
            if (normalized.isEmpty()) continue;
            var prefix = canonicalPrefix(normalized);
            if (prefix == null || prefix.isBlank()) continue;
            if (registry.writeActionPrefixes().stream().anyMatch(write -> matches(prefix, write))) continue;
            if (!seen.add(prefix)) continue;
            prefixes.add(prefix);
        }
    }

    private String canonicalPrefix(String normalized) {
        var tokens = splitWords(normalized);
        if (tokens.isEmpty()) return null;

        var base = tokens.get(0).toLowerCase(Locale.ROOT);
        if (SKIPPED_ALWAYS_ALLOW_BASES.contains(base)) return null;

        if (SUBCOMMAND_BASES.contains(base)) {
            return tokens.size() >= 2 ? base + " " + tokens.get(1) : base;
        }
        if (MODULE_BASES.contains(base) && tokens.size() >= 3 && "-m".equals(tokens.get(1))) {
            return base + " -m " + tokens.get(2);
        }
        if (OPTION_KEY_BASES.contains(base)) {
            for (int i = 1; i < tokens.size(); i++) {
                var token = tokens.get(i);
                if (token.startsWith("-")) {
                    return base + " " + token;
                }
            }
            return base;
        }
        return base;
    }

    private List<String> matchingCandidates(String normalized) {
        var candidates = new ArrayList<String>();
        candidates.add(normalized);

        var canonical = canonicalPrefix(normalized);
        if (canonical != null && !canonical.isBlank() && !candidates.contains(canonical)) {
            candidates.add(canonical);
        }

        var tokens = splitWords(normalized);
        if (!tokens.isEmpty()) {
            var base = tokens.get(0).toLowerCase(Locale.ROOT);
            if (!candidates.contains(base)) candidates.add(base);
        }
        return candidates;
    }

    private static boolean matches(String cmd, String prefix) {
        return cmd.equals(prefix) || cmd.startsWith(prefix + " ");
    }

    private static boolean isSed(String cmd) {
        return cmd.equals("sed") || cmd.startsWith("sed ");
    }

    private static boolean containsFlag(String cmd, String flag) {
        // Match flag as a standalone word (not inside a string or part of another flag)
        return cmd.matches(".*\\s" + Pattern.quote(flag) + "(\\s.*|$)");
    }

    private void persist() {
        try {
            Files.createDirectories(registryFile.getParent());
            yaml.writeValue(registryFile.toFile(), registry);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stripLeadingAssignments(String cmd) {
        var remaining = cmd.trim();
        while (true) {
            var tokens = splitWords(remaining);
            if (tokens.isEmpty()) return "";
            var first = tokens.get(0);
            if (!LEADING_ASSIGNMENT.matcher(first).find()) return remaining;
            remaining = remaining.substring(first.length()).trim();
        }
    }

    private static String stripRedirections(String cmd) {
        var result = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        int substitutionDepth = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            char next = i + 1 < cmd.length() ? cmd.charAt(i + 1) : '\0';
            if (c == '\\') {
                result.append(c);
                if (i + 1 < cmd.length()) result.append(cmd.charAt(++i));
                continue;
            }
            if (!inDouble && c == '\'') {
                inSingle = !inSingle;
                result.append(c);
                continue;
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble;
                result.append(c);
                continue;
            }
            if (!inSingle && c == '$' && next == '(') {
                substitutionDepth++;
                result.append(c).append(next);
                i++;
                continue;
            }
            if (!inSingle && !inDouble && substitutionDepth > 0 && c == ')') {
                substitutionDepth--;
                result.append(c);
                continue;
            }
            if (!inSingle && !inDouble && substitutionDepth == 0 && (c == '>' || c == '<')) {
                while (i + 1 < cmd.length()) {
                    char peek = cmd.charAt(i + 1);
                    if (Character.isWhitespace(peek)) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            result.append(c);
        }
        return normalizeSpacing(result.toString());
    }

    private static String replaceCommandSubstitutions(String cmd) {
        var result = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            char next = i + 1 < cmd.length() ? cmd.charAt(i + 1) : '\0';
            if (c == '\\') {
                result.append(c);
                if (i + 1 < cmd.length()) result.append(cmd.charAt(++i));
                continue;
            }
            if (!inDouble && c == '\'') {
                inSingle = !inSingle;
                result.append(c);
                continue;
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble;
                result.append(c);
                continue;
            }
            if (!inSingle && c == '$' && next == '(') {
                int end = findMatchingParen(cmd, i + 1);
                if (end < 0) return cmd;
                result.append("__SUBST__");
                i = end;
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    private static List<String> extractCommandSubstitutions(String cmd) {
        var result = new ArrayList<String>();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            char next = i + 1 < cmd.length() ? cmd.charAt(i + 1) : '\0';
            if (c == '\\') {
                i++;
                continue;
            }
            if (!inDouble && c == '\'') {
                inSingle = !inSingle;
                continue;
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble;
                continue;
            }
            if (!inSingle && c == '$' && next == '(') {
                int end = findMatchingParen(cmd, i + 1);
                if (end < 0) return List.of();
                result.add(cmd.substring(i + 2, end));
                i = end;
            }
        }
        return result;
    }

    private static int findMatchingParen(String text, int openParenIndex) {
        int depth = 1;
        boolean inSingle = false, inDouble = false;
        for (int i = openParenIndex + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (c == '\\') {
                i++;
                continue;
            }
            if (!inDouble && c == '\'') {
                inSingle = !inSingle;
                continue;
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble;
                continue;
            }
            if (inSingle) continue;
            if (c == '$' && next == '(') {
                depth++;
                i++;
                continue;
            }
            if (!inDouble && c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<ShellPart> splitTopLevelOperators(String cmd, Mode mode) {
        var result = new ArrayList<ShellPart>();
        var current = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        int substitutionDepth = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            char next = i + 1 < cmd.length() ? cmd.charAt(i + 1) : '\0';
            if (c == '\\') {
                current.append(c);
                if (i + 1 < cmd.length()) current.append(cmd.charAt(++i));
                continue;
            }
            if (!inDouble && c == '\'') {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }
            if (!inSingle && c == '$' && next == '(') {
                substitutionDepth++;
                current.append(c).append(next);
                i++;
                continue;
            }
            if (!inSingle && !inDouble && substitutionDepth > 0 && c == ')') {
                substitutionDepth--;
                current.append(c);
                continue;
            }

            if (inSingle || inDouble || substitutionDepth > 0) {
                current.append(c);
                continue;
            }

            if (mode == Mode.PIPELINE && c == '|' && next != '|') {
                result.add(new ShellPart(current.toString(), "|"));
                current = new StringBuilder();
                continue;
            }
            if (mode == Mode.CHAIN) {
                if (c == ';') {
                    result.add(new ShellPart(current.toString(), ";"));
                    current = new StringBuilder();
                    continue;
                }
                if (c == '&' && next == '&') {
                    result.add(new ShellPart(current.toString(), "&&"));
                    current = new StringBuilder();
                    i++;
                    continue;
                }
                if (c == '|' && next == '|') {
                    result.add(new ShellPart(current.toString(), "||"));
                    current = new StringBuilder();
                    i++;
                    continue;
                }
            }
            current.append(c);
        }
        result.add(new ShellPart(current.toString(), null));
        return result;
    }

    private static List<String> splitWords(String cmd) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        int substitutionDepth = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            char next = i + 1 < cmd.length() ? cmd.charAt(i + 1) : '\0';
            if (c == '\\') {
                current.append(c);
                if (i + 1 < cmd.length()) current.append(cmd.charAt(++i));
                continue;
            }
            if (!inDouble && c == '\'') {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }
            if (!inSingle && c == '$' && next == '(') {
                substitutionDepth++;
                current.append(c).append(next);
                i++;
                continue;
            }
            if (!inSingle && !inDouble && substitutionDepth > 0 && c == ')') {
                substitutionDepth--;
                current.append(c);
                continue;
            }
            if (!inSingle && !inDouble && substitutionDepth == 0 && Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current = new StringBuilder();
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private static String normalizeSpacing(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    private enum Mode { CHAIN, PIPELINE }

    private record ShellPart(String text, String operator) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Registry(
            List<String> readOnlyPrefixes,
            List<String> writeActionPrefixes,
            List<String> pipeFilters,
            List<String> stripPrefixes
    ) {}
}
