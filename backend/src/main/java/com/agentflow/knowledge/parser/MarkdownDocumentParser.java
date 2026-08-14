package com.agentflow.knowledge.parser;

import com.agentflow.knowledge.model.DocumentFileType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 中文：Markdown 的轻量 V4 解析器。它保留原始 Markdown 文本，只识别 fenced code 之外的 ATX
 * 标题来生成 titlePath；不引入完整 Markdown AST 或 HTML 渲染。
 *
 * <p>English: Lightweight V4 Markdown parser. It retains source Markdown and only
 * recognizes ATX headings outside fenced code to create titlePath metadata; it does
 * not introduce a full Markdown AST or HTML rendering.
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {
    private static final Pattern ATX_HEADING = Pattern.compile(
            "^[ \\t]{0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*$"
    );

    @Override
    public boolean supports(DocumentFileType fileType) {
        return fileType == DocumentFileType.MD;
    }

    @Override
    public ParsedDocument parse(InputStream content) throws IOException {
        String text = Utf8DocumentText.readAndNormalize(content, true);
        List<ParsedSection> sections = new ArrayList<>();
        String[] hierarchy = new String[6];
        boolean insideFence = false;
        int offset = 0;

        String[] lines = text.split("\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            if (Utf8DocumentText.isFenceDelimiter(line)) {
                insideFence = !insideFence;
            } else if (!insideFence) {
                Matcher matcher = ATX_HEADING.matcher(line);
                if (matcher.matches()) {
                    int headingLevel = matcher.group(1).length();
                    String title = stripClosingHeadingMarks(matcher.group(2));
                    if (!title.isEmpty()) {
                        hierarchy[headingLevel - 1] = title;
                        for (int index = headingLevel; index < hierarchy.length; index++) {
                            hierarchy[index] = null;
                        }
                        sections.add(new ParsedSection(offset, joinHierarchy(hierarchy, headingLevel)));
                    }
                }
            }

            offset += line.length();
            if (lineIndex < lines.length - 1) {
                offset++;
            }
        }
        return new ParsedDocument(text, sections);
    }

    private static String stripClosingHeadingMarks(String rawTitle) {
        return rawTitle.replaceFirst("[ \\t]+#+[ \\t]*$", "").strip();
    }

    private static String joinHierarchy(String[] hierarchy, int headingLevel) {
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < headingLevel; index++) {
            if (hierarchy[index] != null) {
                parts.add(hierarchy[index]);
            }
        }
        return String.join(" / ", parts);
    }
}
