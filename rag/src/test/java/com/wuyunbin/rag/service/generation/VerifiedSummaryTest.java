package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.service.generation.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedSummaryTest {
    @Test void evidenceWithLiteralTemplateTokensAppearsVerbatimInModelContext() {
        String source = "字段 {sectionTitle}：$10。";
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("source", source);
        values.put("sectionTitle", "预算");
        assertEquals("预算\n" + source, PromptTemplates.render("{sectionTitle}\n{source}", values));
    }

    @Test void rewrittenAndUnattributedSummaryLinesSurviveWithoutBorrowingSupport() {
        Claim original = new Claim("C1","建议每月巡检",List.of("E1"),SupportStatus.SUPPORTED,"有原文",true);
        var summary = LlmDocumentSummaryGenerator.resolve(List.of(
                new LlmDocumentSummaryGenerator.SummaryItem("C1", "建议每月巡检"),
                new LlmDocumentSummaryGenerator.SummaryItem("C1", "必须每月巡检"),
                new LlmDocumentSummaryGenerator.SummaryItem("fake", "成本将增长")
        ),Map.of("C1",original));
        assertEquals("建议每月巡检\n必须每月巡检\n成本将增长",summary.text());
        assertEquals(List.of("C1","S-1","S-2"),summary.claimIds());
        assertTrue(summary.additionalClaims().stream().allMatch(c -> c.evidenceIds().isEmpty()
                && c.supportStatus() == SupportStatus.INSUFFICIENT));
    }

    @Test void integrityValidatorRejectsPositiveJudgmentWithoutMatchedEvidence() {
        var claim = new Claim("C", "必须每月巡检", List.of(), SupportStatus.SUPPORTED,"假设",true);
        var document = new GeneratedDocumentAssembler().assemble("测试",new VerifiedSummary("",List.of(),List.of()),
                List.of(new GeneratedSection("第一章",claim.text(),List.of(),List.of(claim))));
        assertThrows(IllegalStateException.class,() -> new EvidenceIntegrityValidator().validate(document,Set.of("d")));
    }
}
