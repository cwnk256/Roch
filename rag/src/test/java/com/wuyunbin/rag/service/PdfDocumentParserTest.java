package com.wuyunbin.rag.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PdfDocumentParserTest {
    @Test
    void preservesSingleLinePagesAndTheFinalLineExactlyOnce() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var document = new PDDocument()) {
            for (String[] lines : new String[][]{{"first-page"}, {}, {"second-first", "second-last"}}) {
                var page = new PDPage();
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(60, 700);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLineAtOffset(0, -16);
                    }
                    content.endText();
                }
            }
            document.save(output);
        }
        var parser = new PdfDocumentParser(new TableTextEnhancer());
        var pages = parser.parsePages(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(java.util.List.of(1, 3), pages.stream().map(PdfDocumentParser.PdfPage::number).toList());
        String text = parser.parseToMarkdown(new ByteArrayInputStream(output.toByteArray()));
        assertEquals("first-page second-first second-last", text.replaceAll("\\s+", " ").strip());
    }
}
