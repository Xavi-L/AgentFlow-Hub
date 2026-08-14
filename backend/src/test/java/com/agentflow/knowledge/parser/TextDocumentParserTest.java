package com.agentflow.knowledge.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests the UTF-8 and normalization contract independently from persistence. */
class TextDocumentParserTest {

    private final TextDocumentParser parser = new TextDocumentParser();

    @Test
    void shouldDecodeUtf8RemoveBomAndNormalizeLineEndingsDeterministically() throws Exception {
        ParsedDocument document = parser.parse(new ByteArrayInputStream(
                "\uFEFFfirst\r\n\r\n\r\n\r\nsecond\r".getBytes(StandardCharsets.UTF_8)
        ));

        assertThat(document.text()).isEqualTo("first\n\n\nsecond\n");
        assertThat(document.sections()).isEmpty();
    }

    @Test
    void shouldRejectMalformedUtf8InsteadOfSilentlyReplacingIt() {
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(
                new byte[] {(byte) 0xC3, (byte) 0x28}
        ))).isInstanceOf(DocumentParseException.class)
                .hasMessage("Document content is not valid UTF-8");
    }
}
