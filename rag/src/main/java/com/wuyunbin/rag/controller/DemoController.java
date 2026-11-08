package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.CurlFileInfo;
import com.wuyunbin.rag.dto.CurlRequest;
import com.wuyunbin.rag.service.CurlService;
import com.wuyunbin.rag.service.PageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;


@Slf4j
@RestController
@RequestMapping("/api/curl")
@Tag(name = "Curl接口", description = "请求外部网络数据并落盘为 JSON 文件")
public class DemoController {

    @Resource
    private CurlService curlService;

    @Resource
    private PageService pageService;

    @PostMapping("")
    @Operation(summary = "请求网络数据", description = "请求给定 URL，解析 JSON 后美化保存到本地 JSON 文件，返回解析数据与文件路径")
    public ResponseEntity<?> curl(@RequestBody CurlRequest curlRequest) {
        try {
            String url = curlRequest.url();
            log.info("curl {}", url);
            return ResponseEntity.ok(curlService.curl(url));
        } catch (IllegalArgumentException | IllegalStateException e) {
            // URL 为空、目标非 JSON、JSON 语法非法、空响应：均为请求/上游输入问题
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            // 连接超时、DNS 解析失败、目标不可达等上游网络问题
            return ResponseEntity.badRequest().body(Map.of("error", safeMessage(e)));
        }
    }

    @GetMapping("/files")
    @Operation(summary = "已保存 JSON 文件列表", description = "按最后修改时间倒序返回抓取落盘的 JSON 文件概要（文件名、大小、修改时间）")
    public List<CurlFileInfo> listFiles() {
        return curlService.listFiles();
    }

    @PostMapping("/page")
    @Operation(summary = "抓取网页转 Markdown",
            description = "请求网页 URL，抽取正文转为标准 Markdown（保留标题层级），保存为 .txt 以便导入 RAG 知识库；"
                    + "engine 可选 jsoup（本地直连）/ firecrawl（云端增强抓取，支持 JS 渲染与反爬）/ auto（默认，失败自动回退）")
    public ResponseEntity<?> fetchPage(@RequestBody CurlRequest curlRequest) {
        try {
            return ResponseEntity.ok(pageService.fetchPage(curlRequest.url(), curlRequest.engine()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            // 连接超时、DNS 解析失败、目标不可达、文件写入失败等：均为请求/上游/环境问题，向用户返回可读原因而非 500
            return ResponseEntity.badRequest().body(Map.of("error", safeMessage(e)));
        }
    }

    /**
     * 取异常消息，兜底返回异常类名，避免向前端暴露空字符串。
     */
    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        if (msg != null && !msg.isBlank()) {
            return msg;
        }
        return e.getClass().getSimpleName();
    }
}
