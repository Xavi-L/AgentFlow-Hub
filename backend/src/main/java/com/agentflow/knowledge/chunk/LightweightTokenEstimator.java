package com.agentflow.knowledge.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 中文：稳定、无模型依赖的估算器：每个 CJK、日文假名或韩文 code point 为 1 token，普通连续
 * 英文/数字词为 1 token（异常长的无空格词每 8 个 code point 分段），每个非空白符号为 1 token。
 * 它不是模型 tokenizer，但足以让当前 chunk 预算可重复验证。
 *
 * <p>English: Stable, model-free estimator: each CJK, Japanese kana, or Hangul code
 * point is one token; an ordinary contiguous Latin/digit word is one token, while an
 * exceptionally long unbroken run is segmented every eight code points; and each
 * non-whitespace symbol is one token. It is not a model tokenizer, but makes the
 * current chunk budget repeatable.
 */
@Component
public class LightweightTokenEstimator implements TokenEstimator {
    /**
     * Normal words remain one estimated token. An exceptionally long unbroken run
     * (URLs, base64, minified identifiers) is segmented so it cannot turn into one
     * arbitrarily large chunk despite a token-based chunk budget.
     */
    private static final int MAX_WORD_CODE_POINTS_PER_TOKEN = 8;

    @Override
    public List<TokenSpan> tokenize(String text) {
        Objects.requireNonNull(text, "text must not be null");
        List<TokenSpan> tokens = new ArrayList<>();
        int offset = 0;

        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            int width = Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                offset += width;
                continue;
            }

            int startOffset = offset;
            if (isEastAsianCharacter(codePoint)) {
                tokens.add(new TokenSpan(startOffset, startOffset + width));
                offset += width;
                continue;
            }

            if (Character.isLetterOrDigit(codePoint)) {
                offset += width;
                int segmentStartOffset = startOffset;
                int segmentCodePointCount = 1;
                while (offset < text.length()) {
                    int nextCodePoint = text.codePointAt(offset);
                    if (isEastAsianCharacter(nextCodePoint) || !Character.isLetterOrDigit(nextCodePoint)) {
                        break;
                    }
                    offset += Character.charCount(nextCodePoint);
                    segmentCodePointCount++;
                    if (segmentCodePointCount == MAX_WORD_CODE_POINTS_PER_TOKEN) {
                        tokens.add(new TokenSpan(segmentStartOffset, offset));
                        segmentStartOffset = offset;
                        segmentCodePointCount = 0;
                    }
                }
                if (segmentStartOffset < offset) {
                    tokens.add(new TokenSpan(segmentStartOffset, offset));
                }
                continue;
            }

            tokens.add(new TokenSpan(startOffset, startOffset + width));
            offset += width;
        }
        return List.copyOf(tokens);
    }

    private static boolean isEastAsianCharacter(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x20000 && codePoint <= 0x2EBEF)
                || (codePoint >= 0x3040 && codePoint <= 0x309F)
                || (codePoint >= 0x30A0 && codePoint <= 0x30FF)
                || (codePoint >= 0x31F0 && codePoint <= 0x31FF)
                || (codePoint >= 0x1100 && codePoint <= 0x11FF)
                || (codePoint >= 0x3130 && codePoint <= 0x318F)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF);
    }
}
