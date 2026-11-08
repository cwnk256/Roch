package com.wuyunbin.rag.cleaning;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据清洗配置项。
 */
@Getter
@Setter
@Component
public class CleaningProperties {

    /**
     * 清洗总开关；关闭后走纯语义切片，行为与旧版一致。
     */
    @Value("${rag.cleaning.enabled:true}")
    private boolean enabled;

    /**
     * 普通正文与表格单元格中的英文小写化（URL、代码块除外）。
     */
    @Value("${rag.cleaning.lowercase:true}")
    private boolean lowercase;

    /**
     * 过滤显式目录标题区（# 目录 / ## 目录 / CONTENTS 等）。
     */
    @Value("${rag.cleaning.filter-toc:true}")
    private boolean filterToc;
}
