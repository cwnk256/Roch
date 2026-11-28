package com.wuyunbin.rag.service.ocr;

import com.wuyunbin.rag.service.DocumentService;
import com.wuyunbin.rag.service.ocr.OcrEngineClient.EngineFailure;
import com.wuyunbin.rag.service.ocr.OcrImportJobStore.OcrWork;
import com.wuyunbin.rag.service.ocr.OcrPdfPages.PageFailure;
import com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class OcrImportService {
    private static final String OCR_DISABLED = "此 PDF 需要 OCR 识别，请启用 OCR（RAG_OCR_ENABLED=true）后重新导入，或上传已添加文字层的 PDF";
    private final boolean enabled;
    private final OcrPdfPages pdfPages;
    private final OcrEngineClient engine;
    private final DocumentService documents;
    private final OcrImportJobStore jobs;
    private final TransactionTemplate transactions;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                var thread = new Thread(task, "ocr-pdf-import");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    OcrImportService(@Value("${rag.ocr.enabled:false}") boolean enabled, OcrPdfPages pdfPages, OcrEngineClient engine,
                     DocumentService documents, OcrImportJobStore jobs, PlatformTransactionManager transactions) {
        this.enabled = enabled;
        this.pdfPages = pdfPages;
        this.engine = engine;
        this.documents = documents;
        this.jobs = jobs;
        this.transactions = new TransactionTemplate(transactions);
    }

    public Optional<OcrImportJob> startIfRequired(MultipartFile file) {
        String fileName = validatePdf(file);
        byte[] content;
        try {
            content = file.getBytes();
            if (!pdfPages.needsOcr(content)) {
                return Optional.empty();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("PDF 文档解析失败: " + fileName, e);
        }
        requireEnabled();
        var source = new StoredDocument(DigestUtils.md5DigestAsHex(content), fileName, content);
        var job = transactions.execute(status -> {
            var queued = jobs.enqueue(source);
            documents.stageOcrOriginal(source.documentId());
            return queued;
        });
        schedule();
        return Optional.of(job);
    }

    public Optional<OcrImportJob> find(UUID jobId) {
        return jobs.find(jobId);
    }

    public Optional<OcrImportJob> retry(UUID jobId) {
        requireEnabled();
        var job = jobs.retry(jobId);
        schedule();
        return job;
    }

    public String legacyError(MultipartFile file, RuntimeException failure) {
        String message = failure.getMessage();
        boolean emptyText = (message != null && message.startsWith("文件没有可索引的正文"))
                || (failure.getCause() instanceof IOException cause && "PDF 文档正文内容为空".equals(cause.getMessage()));
        if (emptyText) {
            try {
                if (pdfPages.needsOcr(file.getBytes())) {
                    return enabled ? "此 PDF 需要 OCR 识别，请使用 OCR 自动导入" : OCR_DISABLED;
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // 判断所需导入方式失败时，保留已有的解析错误。
            }
        }
        return message;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(initialDelay = 5000, fixedDelay = 5000)
    public void schedule() {
        if (enabled) {
            worker.execute(this::drain);
        }
    }

    private void drain() {
        try {
            jobs.withWorkerLock(() -> {
                // 拿到会话锁后，RUNNING/INDEXING 只可能是已经退出的执行留下的。
                jobs.recoverInterrupted();
                while (!Thread.currentThread().isInterrupted()) {
                    var next = jobs.claimNext();
                    if (next.isEmpty()) {
                        return;
                    }
                    runJob(next.get());
                }
            });
        } catch (RuntimeException e) {
            log.warn("OCR 导入队列处理失败，下次调度将恢复未完成任务", e);
        }
    }

    private void runJob(OcrWork work) {
        UUID jobId = work.jobId();
        long startedAt = System.nanoTime();
        var currentPage = new AtomicInteger();
        boolean recognizing = false;
        try {
            engine.requireReady();
            recognizing = true;
            List<OcrPage> recognized = pdfPages.recognize(work.source().content(), engine::recognize,
                    current -> {
                        jobs.currentPage(jobId, current);
                        currentPage.set(current);
                    }, total -> jobs.totalPages(jobId, total));
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            long reviewPages = 0;
            long reviewTableEvidence = 0;
            for (OcrPage page : recognized) {
                var reviewAnchors = page.anchors().stream().filter(OcrPage.Anchor::reviewRequired).toList();
                if (!reviewAnchors.isEmpty()) {
                    reviewPages++;
                    reviewTableEvidence += reviewAnchors.stream().filter(OcrPage.Anchor::table).count();
                }
            }
            recognizing = false;
            jobs.markIndexing(jobId);
            transactions.executeWithoutResult(status -> {
                // 切片事务提交时一并确认任务成功，并释放暂存的上传字节。
                var result = documents.importRecognizedPdf(work.source(), recognized);
                jobs.complete(jobId, result);
            });
            log.info("OCR 导入完成 jobId={} durationMs={} recognizedPages={} reviewPages={} reviewTableEvidence={}",
                    jobId, elapsedMillis(startedAt), recognized.size(), reviewPages, reviewTableEvidence);
        } catch (EngineFailure e) {
            failUnlessInterrupted(jobId, recognizing ? currentPage.get() : 0, e.getMessage(), startedAt, e);
        } catch (PageFailure e) {
            failUnlessInterrupted(jobId, currentPage.get(), e.getMessage(), startedAt, e);
        } catch (IOException e) {
            failUnlessInterrupted(jobId, recognizing ? currentPage.get() : 0,
                    "无法渲染或识别 PDF 页面，请检查原始文件后重试", startedAt, e);
        } catch (RuntimeException e) {
            failUnlessInterrupted(jobId, recognizing ? currentPage.get() : 0, recognizing
                    ? "OCR 页面处理失败，请检查 OCR 服务和原始文件后重试"
                    : "OCR 导入失败，请检查文档索引服务后重试", startedAt, e);
        }
    }

    private void failUnlessInterrupted(UUID jobId, int page, String message, long startedAt, Exception cause) {
        if (!Thread.currentThread().isInterrupted()) {
            jobs.fail(jobId, page > 0, message);
            log.warn("OCR 导入失败 jobId={} durationMs={} failedPage={} type={}", jobId, elapsedMillis(startedAt),
                    page > 0 ? page : null, cause.getClass().getSimpleName());
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException(OCR_DISABLED);
        }
    }

    private static String validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 .pdf 文件");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("仅支持 .pdf 文件: " + name);
        }
        return name;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
