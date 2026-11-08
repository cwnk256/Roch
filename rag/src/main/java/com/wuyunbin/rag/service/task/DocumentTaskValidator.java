package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentMetadata;
import com.wuyunbin.rag.dto.DocumentStatus;
import com.wuyunbin.rag.dto.DocumentTaskRequest;
import com.wuyunbin.rag.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档任务校验器：请求参数校验 + 指定资料范围校验。
 *
 * <p>资料范围校验保证：用户选择的每个 documentId 都真实存在且状态为 READY，
 * 绝不静默忽略缺失文档，避免"用户以为依据 A/B/C，实际只用了 A/C"。</p>
 */
@Component
@RequiredArgsConstructor
public class DocumentTaskValidator {

    /**
     * instruction 最大长度，防止超长 prompt。
     */
    private static final int MAX_INSTRUCTION_LENGTH = 4000;

    private final DocumentService documentService;

    /**
     * 请求参数校验。
     *
     * @throws IllegalArgumentException 参数不合法（映射为 400）
     */
    public void validateRequest(DocumentTaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.type() == null) {
            throw new IllegalArgumentException("type 不能为空（SUMMARY / REPORT）");
        }
        List<String> documentIds = request.documentIds();
        if (documentIds == null || documentIds.isEmpty()) {
            throw new IllegalArgumentException("documentIds 至少需要指定 1 个文档");
        }
        for (String id : documentIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("documentIds 不能包含空值");
            }
        }
        if (request.instruction() == null || request.instruction().isBlank()) {
            throw new IllegalArgumentException("instruction 不能为空");
        }
        if (request.instruction().length() > MAX_INSTRUCTION_LENGTH) {
            throw new IllegalArgumentException(
                    "instruction 长度不能超过 " + MAX_INSTRUCTION_LENGTH + " 字符");
        }
    }

    /**
     * 逐个校验指定文档存在且为 READY。
     *
     * @throws IllegalStateException 文档不存在或未就绪（映射为 404 / 业务错误）
     */
    public void validateDocumentsExist(List<String> documentIds) {
        for (String id : documentIds) {
            DocumentMetadata metadata = documentService.getDocument(id);
            if (metadata == null) {
                throw new IllegalStateException("文档不存在: " + id);
            }
            if (!DocumentStatus.READY.name().equals(metadata.status())) {
                throw new IllegalStateException(
                        "文档未就绪，无法用于生成任务: " + id + "，当前状态=" + metadata.status());
            }
        }
    }
}
