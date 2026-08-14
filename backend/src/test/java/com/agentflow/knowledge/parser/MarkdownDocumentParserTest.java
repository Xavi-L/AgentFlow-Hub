package com.agentflow.knowledge.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Verifies the deliberately small Markdown heading interpretation used by V4. */
class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    @Test
    void shouldBuildHeadingPathsWithoutTreatingFencedCodeAsHeadings() throws Exception {
        String markdown = "# Payment ##\n"
                + "intro\n\n"
                + "```text\n"
                + "# literal heading\n\n\n\n"
                + "keep\n"
                + "```\n\n"
                + "## Refund\n"
                + "body";

        ParsedDocument document = parser.parse(new ByteArrayInputStream(
                markdown.getBytes(StandardCharsets.UTF_8)
        ));

        assertThat(document.sections()).extracting(ParsedSection::titlePath)
                .containsExactly("Payment", "Payment / Refund");
        assertThat(document.titlePathAt(document.text().indexOf("intro"))).isEqualTo("Payment");
        assertThat(document.titlePathAt(document.text().indexOf("literal heading"))).isEqualTo("Payment");
        assertThat(document.titlePathAt(document.text().indexOf("body"))).isEqualTo("Payment / Refund");
        assertThat(document.text()).contains("# literal heading\n\n\n\nkeep");
    }
}
