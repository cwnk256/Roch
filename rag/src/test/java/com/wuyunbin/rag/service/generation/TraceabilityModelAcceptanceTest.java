package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.service.generation.model.SourceStatus;
import com.wuyunbin.rag.service.generation.model.SupportStatus;
import com.wuyunbin.rag.service.storage.DocumentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Set the flag only for the isolated QA instance after importing traceability/A.pdf. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ROCH_TRACE_MODEL_ACCEPTANCE", matches = "true")
class TraceabilityModelAcceptanceTest {
    @Autowired ClaimVerifier verifier;
    @Autowired DocumentChunkJpaRepository chunks;
    @Autowired DocumentStorage originals;

    @Test void strongerObligationAndUncitedSavingsCannotPassAsSupported() throws Exception {
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/traceability/A.pdf")) {
            assertNotNull(input);
            fixture = input.readAllBytes();
        }
        String id = org.springframework.util.DigestUtils.md5DigestAsHex(fixture);
        assertTrue(originals.isAvailable(id));
        DocumentChunkEntity row = chunks.findByDocMd5(id).stream().filter(c ->
                Integer.valueOf(2).equals(c.getPageStart())).findFirst().orElseThrow();
        var hit = new RetrievedChunk(row.getChunkId(), id, row.getFileName(), row.getContent(), row.getSectionPath(),
                row.getPageStart(), row.getPageEnd(), row.getChunkIndex(), 1);
        var stronger = verifier.verify("probe-1", "必须每月巡检 K17 设备。",
                List.of(new ClaimVerifier.Citation(1, "建议每月巡检 K17 设备。")), List.of(hit), Set.of(id));
        var snapshot = stronger.evidences().getFirst();
        assertEquals(SourceStatus.MATCHED, snapshot.sourceStatus());
        assertEquals("建议每月巡检 K17 设备。", snapshot.quote());
        assertNotEquals(SupportStatus.SUPPORTED, stronger.claim().supportStatus());
        var absent = verifier.verify("probe-2", "青松项目次年将节省 20 万元。", List.of(), List.of(hit), Set.of(id));
        assertEquals(SupportStatus.INSUFFICIENT, absent.claim().supportStatus());
        assertTrue(absent.evidences().isEmpty());
        System.out.printf("Traceability probes: strength=%s, no citation=%s%n",
                stronger.claim().supportStatus(), absent.claim().supportStatus());
    }
}
