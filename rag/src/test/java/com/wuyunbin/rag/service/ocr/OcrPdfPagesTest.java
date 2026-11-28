package com.wuyunbin.rag.service.ocr;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OcrPdfPagesTest {
    @Test
    void recognizesOnlyScanAndMixedPagesWithOriginalPhysicalNumbers() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            addPage(document, true, false, 0);
            addPage(document, false, true, 90);
            addPage(document, true, true, 0);
            addPage(document, false, false, 0);
            document.save(bytes);

            OcrPdfPages pages = new OcrPdfPages(120);
            assertThat(pages.needsOcr(bytes.toByteArray())).isTrue();
            List<Integer> requested = new ArrayList<>();
            List<Integer> count = new ArrayList<>();
            var output = pages.recognize(bytes.toByteArray(), png -> new OcrPage(0, "fixture",
                    List.of(new OcrPage.Block("Scanned content", List.of(.3, .4, .6, .5), .99)), List.of()),
                    requested::add, count::add);
            assertThat(requested).containsExactly(2, 3);
            assertThat(count).containsExactly(4);
            assertThat(output).extracting(OcrPage::page).containsExactly(2, 3);
            assertThat(pages.preview(bytes.toByteArray(), 2)).isNotEmpty();
        }
    }

    @Test
    void digitallySearchableAndBlankPagesDoNotCallOcr() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            addPage(document, true, false, 0);
            addPage(document, false, false, 0);
            document.save(bytes);
            assertThat(new OcrPdfPages(120).needsOcr(bytes.toByteArray())).isFalse();
        }
    }

    @Test
    void nativeHeaderAndFooterDoNotHideTheScanBetweenThem() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            var image = new BufferedImage(612, 792, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 612, 792);
            graphics.setColor(Color.BLACK);
            graphics.drawString("Scan body", 100, 400);
            graphics.dispose();
            try (var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 612, 792);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 740);
                content.showText("A digitally searchable header");
                content.newLineAtOffset(0, -680);
                content.showText("A digitally searchable footer");
                content.endText();
            }
            document.save(bytes);
            assertThat(new OcrPdfPages(120).needsOcr(bytes.toByteArray())).isTrue();
        }
    }

    private void addPage(PDDocument document, boolean text, boolean scan, int rotation) throws Exception {
        PDPage page = new PDPage();
        page.setRotation(rotation);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            if (text) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(100, 710);
                content.showText("This is originally searchable text.");
                content.endText();
            }
            if (scan) {
                var image = new BufferedImage(480, 400, BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, 480, 400);
                graphics.setColor(Color.BLACK);
                graphics.drawString("Scanned content", 80, 150);
                graphics.dispose();
                content.drawImage(LosslessFactory.createFromImage(document, image), 60, 180, 480, 400);
            }
        }
    }
}
