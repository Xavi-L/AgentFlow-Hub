package com.agentflow.knowledge.chat;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only strict V8-style {@code [S#]} references and rejects missing, unknown, or malformed S markers. */
public final class CitationReferenceExtractor {
    private static final Pattern STRICT_MARKER = Pattern.compile("\\[(S\\d+)]");
    // Match the complete S-like bracket run first. This deliberately treats [[S1]],
    // [S1]], and [[S1] as malformed instead of silently accepting an inner [S1].
    private static final Pattern S_LIKE_MARKER = Pattern.compile("\\[+S[^\\]]*(?:]+|$)");

    public List<String> extractAndValidate(String answer, Collection<String> allowedCitationIds) {
        if (answer == null || answer.isBlank()) {
            throw new CitationValidationException("answer contains no citation");
        }
        Objects.requireNonNull(allowedCitationIds, "allowedCitationIds must not be null");
        Set<String> allowed = Set.copyOf(allowedCitationIds);
        LinkedHashSet<String> citationIds = new LinkedHashSet<>();

        Matcher candidateMatcher = S_LIKE_MARKER.matcher(answer);
        while (candidateMatcher.find()) {
            String candidate = candidateMatcher.group();
            Matcher strictMatcher = STRICT_MARKER.matcher(candidate);
            if (!strictMatcher.matches()) {
                throw new CitationValidationException("answer contains a malformed citation");
            }
            String citationId = strictMatcher.group(1);
            if (!allowed.contains(citationId)) {
                throw new CitationValidationException("answer contains an unknown citation");
            }
            citationIds.add(citationId);
        }

        if (citationIds.isEmpty()) {
            throw new CitationValidationException("answer contains no valid citation");
        }
        return List.copyOf(citationIds);
    }
}
