package com.wuyunbin.rag.service.ocr;

import com.wuyunbin.rag.service.DocumentService;
import com.wuyunbin.rag.service.ocr.OcrEngineClient.EngineFailure;
import com.wuyunbin.rag.service.ocr.OcrImportJobStore.OcrWork;
import com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntConsumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OcrImportServiceTest {
    private final OcrPdfPages pdfPages = mock(OcrPdfPages.class);
    private final OcrEngineClient engine = mock(OcrEngineClient.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final OcrImportJobStore jobs = mock(OcrImportJobStore.class);
    private final OcrImportService service = new OcrImportService(true, pdfPages, engine, documents, jobs,
            mock(PlatformTransactionManager.class));

    @AfterEach
    void stop() {
        service.stop();
    }

    @Test
    void anUnavailableEngineFailsWithoutGuessingAFailedPage() {
        UUID jobId = prepareWork();
        doThrow(new EngineFailure("OCR 服务未就绪")).when(engine).requireReady();

        service.schedule();

        verify(jobs, timeout(3000)).fail(jobId, false, "OCR 服务未就绪");
        verifyNoInteractions(pdfPages, documents);
    }

    @Test
    void aLaterPageFailureKeepsProgressAndSkipsIndexing() throws Exception {
        UUID jobId = prepareWork();
        when(engine.recognize(any(byte[].class))).thenThrow(new EngineFailure("OCR 页面识别超时"));
        when(pdfPages.recognize(any(), any(), any(), any())).thenAnswer(call -> {
            call.<IntConsumer>getArgument(3).accept(3);
            call.<IntConsumer>getArgument(2).accept(2);
            call.<Function<byte[], OcrPage>>getArgument(1).apply(new byte[]{2});
            return null;
        });

        service.schedule();

        verify(jobs, timeout(3000)).fail(jobId, true, "OCR 页面识别超时");
        var order = inOrder(jobs, engine);
        order.verify(jobs).totalPages(jobId, 3);
        order.verify(jobs).currentPage(jobId, 2);
        order.verify(engine).recognize(any(byte[].class));
        order.verify(jobs).fail(jobId, true, "OCR 页面识别超时");
        verify(jobs, never()).markIndexing(eq(jobId));
        verifyNoInteractions(documents);
    }

    private UUID prepareWork() {
        UUID jobId = UUID.randomUUID();
        var work = new OcrWork(jobId, new StoredDocument("digest", "scan.pdf", new byte[]{1}));
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(jobs).withWorkerLock(any());
        when(jobs.claimNext()).thenReturn(Optional.of(work), Optional.empty());
        return jobId;
    }
}
