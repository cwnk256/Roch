package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 文档任务创建请求。
 *
 * <p>Phase 2 要求必须显式指定资料（{@code documentIds} 非空），
 * 不支持"根据整个知识库生成"，以保证检索范围可控。</p>
 */
@Schema(description = "文档任务创建请求")
public record DocumentTaskRequest(

        @Schema(description = "任务类型：SUMMARY（总结）/ REPORT（报告）",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "SUMMARY")
        DocumentTaskType type,

        @Schema(description = "指定资料文档 ID 集合（doc_md5），至少 1 个，不允许为空",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> documentIds,

        @Schema(description = "自然语言任务要求",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "根据这些资料生成一份设备维护总结，重点说明维护周期和安全注意事项。")
        String instruction
) {
}
