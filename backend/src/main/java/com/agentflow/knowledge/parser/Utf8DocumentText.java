package com.agentflow.knowledge.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared UTF-8 decoding and deliberately light normalization for TXT and Markdown. */
final class Utf8DocumentText {
    private Utf8DocumentText() {
    }

    static String readAndNormalize(InputStream content, boolean preserveFencedCode) throws IOException {
        Objects.requireNonNull(content, "content must not be null");

        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content.readAllBytes()))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new DocumentParseException("Document content is not valid UTF-8", ex);
        }

        if (!decoded.isEmpty() && decoded.charAt(0) == '\uFEFF') {
            decoded = decoded.substring(1);
        }
        String normalizedLineEndings = decoded.replace("\r\n", "\n").replace('\r', '\n');
        return collapseExcessBlankLines(normalizedLineEndings, preserveFencedCode);
    }

    static boolean isFenceDelimiter(String line) {
        String withoutIndent = line.stripLeading();
        return withoutIndent.startsWith("```") || withoutIndent.startsWith("~~~");
    }

    private static String collapseExcessBlankLines(String text, boolean preserveFencedCode) {
        String[] lines = text.split("\n", -1);
        List<String> retainedLines = new ArrayList<>(lines.length);
        boolean insideFence = false;
        int blankLineRun = 0;

        for (String line : lines) {
            boolean fenceDelimiter = preserveFencedCode && isFenceDelimiter(line);
            boolean collapsibleBlankLine = !insideFence && !fenceDelimiter && line.isBlank();
            if (collapsibleBlankLine) {
                blankLineRun++;
                if (blankLineRun > 2) {
                    continue;
                }
            } else {
                blankLineRun = 0;
            }

            retainedLines.add(line);
            if (fenceDelimiter) {
                insideFence = !insideFence;
            }
        }
        return String.join("\n", retainedLines);
    }
}
