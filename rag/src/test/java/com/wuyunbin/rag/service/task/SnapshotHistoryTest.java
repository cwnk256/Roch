package com.wuyunbin.rag.service.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.persistence.task.*;
import com.wuyunbin.rag.service.generation.*;
import com.wuyunbin.rag.service.generation.model.*;
import com.wuyunbin.rag.service.storage.LocalDocumentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotHistoryTest {
    @TempDir Path directory;

    @Test void sourceDeletionAndReindexDoNotRewriteGenerationSnapshot() throws Exception {
        var originals = new LocalDocumentStorage(directory.resolve("originals").toString());
        originals.save("doc", "原文.txt", new byte[] {1});
        var chunks = mock(DocumentChunkJpaRepository.class);
        var row = new DocumentChunkEntity();
        row.setContent("年度预算 100 万元");
        row.setChunkId("c");
        row.setDocMd5("doc");
        row.setFileName("原文.txt");
        row.setPipelineVersion("v1");
        when(chunks.findById("c")).thenReturn(Optional.of(row));
        when(chunks.findAllById(anyIterable())).thenReturn(List.of(row));

        var judge = mock(ClaimSupportJudge.class);
        when(judge.judge(anyString(), anyList())).thenReturn(
                new ClaimSupportJudge.Judgment(SupportStatus.SUPPORTED, "原文给出了该预算。"));
        var claim = new ClaimVerifier(chunks, judge).verify("C1", row.getContent(),
                List.of(new ClaimVerifier.Citation(1, row.getContent())),
                List.of(new RetrievedChunk("c","doc","原文.txt",row.getContent(),null,null,null,1,0.5)),Set.of("doc"));
        String evidenceId = claim.evidences().getFirst().evidenceId();
        var document = new GeneratedDocumentAssembler().assemble("测试",new VerifiedSummary(row.getContent(),
                List.of("C1"),List.of()),List.of(new GeneratedSection("预算",row.getContent(),claim.evidences(),List.of(claim.claim()))));
        var properties = new DocumentTaskProperties();
        properties.setOutputDir(directory.resolve("reports").toString());
        var tasks = mock(DocumentTaskJpaRepository.class);
        when(tasks.findByTaskId("report")).thenReturn(Optional.of(new DocumentTaskEntity()));
        var results = mock(DocumentResultJpaRepository.class);
        var storage = new GeneratedDocumentStorage(new ObjectMapper(),properties,tasks,results,chunks,originals);
        Path saved = storage.save("report",document);
        byte[] initial = Files.readAllBytes(saved);
        var captured = ArgumentCaptor.forClass(DocumentResultEntity.class);
        verify(results).save(captured.capture());
        assertEquals("v2", captured.getValue().getSchemaVersion());
        assertEquals(new CurrentSource(true, CurrentSource.ChunkStatus.UNCHANGED),storage.read("report").currentSources().get(evidenceId));

        row.setContent("年度预算 60 万元");
        row.setPipelineVersion("v2");
        Files.delete(directory.resolve("originals/doc/source.bin"));
        var historic = storage.read("report");
        assertEquals(new CurrentSource(false,CurrentSource.ChunkStatus.CHANGED),historic.currentSources().get(evidenceId));
        assertEquals("年度预算 100 万元", historic.evidenceSnapshots().getFirst().quote());
        assertEquals(SupportStatus.SUPPORTED,historic.sections().getFirst().claims().getFirst().supportStatus());
        when(chunks.findAllById(anyIterable())).thenReturn(List.of());
        assertEquals(CurrentSource.ChunkStatus.MISSING, storage.read("report").currentSources().get(evidenceId).chunkStatus());
        assertArrayEquals(initial, Files.readAllBytes(saved));
    }
}
