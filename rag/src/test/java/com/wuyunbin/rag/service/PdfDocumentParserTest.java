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
    @Test void rejectsRepeatedColumnsWithoutAnExplicitGroupAxis() throws Exception {
        var rows = physicalRows(new String[][]{{"Metric","Group","Group"},{"Delay","17","19"}});
        assertEquals(1, rows.size());
        assertEquals(java.util.List.of(), rows.getFirst().facts());
    }

    @Test void bindsPhysicalRowsToUniqueColumnHeaders() throws Exception {
        var rows = physicalRows(new String[][]{{"Metric", "Group A", "Group B"}, {"Delay", "17", "19"}});
        assertEquals(1, rows.size());
        assertEquals(java.util.List.of("Group A的Delay为17", "Group B的Delay为19"), rows.getFirst().facts());
    }

    @Test void offersScoreAndRankTogetherOnlyWhenTheyShareAnAlignedMetric() throws Exception {
        var rows = physicalRows(new String[][]{{"Object", "score", "rank"}, {"Alpha", "17.5", "2"}});
        assertEquals(java.util.List.of("Alpha的score为17.5", "Alpha的排名第2", "Alpha的score为17.5，排名第2"),
                rows.getFirst().facts());
        var ambiguous = physicalRows(new String[][]{{"Object", "score", "rank"}, {"Alpha", "17.5", "2.4"}});
        assertEquals(java.util.List.of("Alpha的score为17.5"), ambiguous.getFirst().facts());
    }

    private static java.util.List<PdfDocumentParser.TableRow> physicalRows(String[][] cells) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {

                for (int row=0; row<cells.length; row++) for (int col=0; col<cells[row].length; col++) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(60 + col * 100, 700 - row * 16);
                    content.showText(cells[row][col]);
                    content.endText();
                }
            }
            document.save(output);
        }
        return new PdfDocumentParser(new TableTextEnhancer()).physicalTableRows(new ByteArrayInputStream(output.toByteArray()));
    }

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
