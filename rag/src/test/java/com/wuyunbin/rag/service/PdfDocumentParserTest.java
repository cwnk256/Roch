package com.wuyunbin.rag.service;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PdfDocumentParserTest {
    @Test
    void removesNullCharacterIntroducedByThePdfFontMapping() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var toUnicode = new PDStream(document);
            try (var stream = toUnicode.createOutputStream()) {
                stream.write("""
                        /CIDInit /ProcSet findresource begin
                        12 dict begin
                        begincmap
                        /CMapName /NullGlyph def
                        /CMapType 2 def
                        1 begincodespacerange
                        <00> <FF>
                        endcodespacerange
                        1 beginbfchar
                        <58> <0000>
                        endbfchar
                        endcmap
                        CMapName currentdict /CMap defineresource pop
                        end
                        end
                        """.getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, toUnicode);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(60, 700);
                content.showText("beforeXafter");
                content.endText();
            }
            document.save(output);
        }
        var parser = new PdfDocumentParser(new TableTextEnhancer());
        var pages = parser.parsePages(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(java.util.List.of(new PdfDocumentParser.PdfPage(1, "beforeafter")), pages);
    }

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
