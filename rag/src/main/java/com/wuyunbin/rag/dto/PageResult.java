package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 分页响应：通用包装，content 为本页数据，total 为全量计数。
 *
 * <p>注意：与 {@link PageResponse}（网页抓取响应）不同，本类用于分页接口。</p>
 */
@Schema(description = "分页响应")
public record PageResult<T>(

        @Schema(description = "本页数据")
        List<T> content,

        @Schema(description = "当前页码（从 0 开始）")
        int page,

        @Schema(description = "每页大小")
        int size,

        @Schema(description = "总条数")
        long total
) {

    /**
     * 内存分页：对已取回的全量列表做 subList，避免引入复杂分页插件。
     */
    public static <T> PageResult<T> of(List<T> all, int page, int size) {
        int p = Math.max(0, page);
        int s = size <= 0 ? 20 : size;
        int from = Math.min(p * s, all.size());
        int to = Math.min(from + s, all.size());
        List<T> sub = all.subList(from, to);
        return new PageResult<>(sub, p, s, all.size());
    }
}
