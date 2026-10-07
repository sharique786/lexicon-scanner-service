package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.model.Models.StrippedMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HtmlStrippingService - real-world HTML messages")
class HtmlMessageHandlingTest {

    private final HtmlStrippingService service = new HtmlStrippingService();

    @Test
    @DisplayName("entities are decoded so terms match the visible text")
    void entitiesDecoded() {
        StrippedMessage m = service.strip("<p>AT&amp;T said it&#39;s &quot;fine&quot; &lt;ok&gt; caf&#xE9;</p>");
        assertThat(m.strippedText()).isEqualTo("AT&T said it's \"fine\" <ok> café");
    }

    @Test
    @DisplayName("&nbsp; separates words instead of gluing them")
    void nbspIsWordSeparator() {
        assertThat(service.strip("foo&nbsp;bar&nbsp;&nbsp;baz").strippedText()).isEqualTo("foo bar baz");
    }

    @Test
    @DisplayName("an unknown or malformed entity is left literal")
    void unknownEntityLiteral() {
        assertThat(service.strip("a &bogus; b & c &#xZZ; d").strippedText()).isEqualTo("a &bogus; b & c &#xZZ; d");
    }

    @Test
    @DisplayName("a match covering a decoded entity maps to the WHOLE entity in the original text")
    void entitySpanMapsToWholeEntity() {
        String html = "<b>x</b> AT&amp;T y";
        StrippedMessage m = service.strip(html);
        int s = m.strippedText().indexOf("AT&T");
        int[] span = m.mapSpanToOriginal(s, s + 4);
        assertThat(html.substring(span[0], span[1])).isEqualTo("AT&amp;T");
    }

    @Test
    @DisplayName("script/style bodies, comments and doctype contribute no text")
    void nonContentBlocksSkipped() {
        String html = "<!DOCTYPE html><html><head><style>.a{color:red}</style><script>var insider=1;</script></head>"
                + "<body><!-- insider hidden --><p>visible text</p></body></html>";
        assertThat(service.strip(html).strippedText()).isEqualTo("visible text");
    }

    @Test
    @DisplayName("Outlook/namespaced tags such as <o:p> are stripped")
    void namespacedTagsStripped() {
        assertThat(service.strip("<p class=MsoNormal>insider<o:p></o:p> trading<o:p>&nbsp;</o:p></p>").strippedText())
                .isEqualTo("insider trading");
    }

    @Test
    @DisplayName("upper-case tags are stripped")
    void upperCaseTags() {
        assertThat(service.strip("<DIV><P>Hello</P><BR>World</DIV>").strippedText()).isEqualTo("Hello World");
    }
}
