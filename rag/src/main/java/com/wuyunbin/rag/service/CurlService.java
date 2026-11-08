package com.wuyunbin.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyunbin.rag.dto.CurlFileInfo;
import com.wuyunbin.rag.dto.CurlResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 外部网络数据抓取：请求 URL → 校验并解析 JSON → 美化落盘为本地 JSON 文件。
 * 落盘文件可直接用 Jackson/Files 读取，方便离线解析与复现。
 */
@Slf4j
@Service
public class CurlService {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * JSON 文件存储目录，默认工作目录下的 data/curl。
     */
    @Value("${rag.curl.storage-dir:data/curl}")
    private String storageDir;

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    public CurlResponse curl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("请求URL不能为空");
        }

        String raw = fetch(url);
        JsonNode data = parseJson(url, raw);
        return saveToFile(url, data);
    }

    /**
     * 列出存储目录下已保存的 JSON 文件，按最后修改时间倒序（最新在前）。
     * 目录尚未创建时返回空列表。
     */
    public List<CurlFileInfo> listFiles() {
        Path dir = Paths.get(storageDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(dir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(this::toFileInfo)
                    .sorted(Comparator.comparing(CurlFileInfo::lastModified).reversed())
                    .toList();
        } catch (IOException e) {
            log.error("读取 JSON 文件列表失败，目录: {}", storageDir, e);
            throw new RuntimeException("读取文件列表失败: " + e.getMessage(), e);
        }
    }

    private CurlFileInfo toFileInfo(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            LocalDateTime modified = LocalDateTime.ofInstant(
                    attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault());
            return new CurlFileInfo(path.getFileName().toString(), attrs.size(), modified);
        } catch (IOException e) {
            log.warn("读取文件属性失败: {}", path, e);
            return new CurlFileInfo(path.getFileName().toString(), 0L, LocalDateTime.MIN);
        }
    }

    private String fetch(String url) {
        try {
            log.info("发起外部网络请求，URL: {}", url);
            String body = restTemplate.getForObject(url, String.class);
            if (body == null || body.isBlank()) {
                log.error("外部接口返回空响应，URL: {}", url);
                throw new IllegalStateException("外部接口返回空响应");
            }
            return body;
        } catch (ResourceAccessException e) {
            log.error("网络请求失败（连接/超时/IO），URL: {}", url, e);
            throw new RuntimeException("网络请求失败: " + e.getMessage(), e);
        } catch (HttpStatusCodeException e) {
            log.error("外部接口返回错误状态码，URL: {}, 状态码: {}, 响应体: {}",
                    url, e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("外部接口返回错误状态码: " + e.getStatusCode(), e);
        }
    }

    private JsonNode parseJson(String url, String raw) {
        String trimmed = raw.stripLeading();
        // JSON 文档只能以 { 或 [ 开头；其余内容（HTML/纯文本/XML 等）提前明确拒绝
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
            String hint = trimmed.startsWith("<")
                    ? "目标地址返回的是 HTML/XML 页面而不是 JSON 接口"
                    : "目标地址返回的内容不是 JSON（应以 { 或 [ 开头）";
            log.warn("非 JSON 响应被拒绝，URL: {}, {}, 前200字符: {}",
                    url, hint, trimmed.substring(0, Math.min(200, trimmed.length())));
            throw new IllegalArgumentException(hint + "，请确认 URL 是返回 JSON 的接口地址");
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.error("JSON解析失败，URL: {}, 原始数据前500字符: {}",
                    url, raw.substring(0, Math.min(500, raw.length())), e);
            // 内容形似 JSON 但语法非法：属于调用方传入地址问题，返回 400
            throw new IllegalArgumentException("目标地址返回的内容不是合法 JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 以美化格式写入 JSON 文件。文件名：curl-时间戳-url哈希前8位.json，避免同秒覆盖。
     */
    private CurlResponse saveToFile(String url, JsonNode data) {
        try {
            Path dir = Paths.get(storageDir);
            Files.createDirectories(dir);

            // URL 的 SHA-256 前 8 位：同一 URL 文件名后缀稳定，配合毫秒时间戳避免覆盖
            String urlHash = sha256Hex(url).substring(0, 8);
            String fileName = "curl-" + LocalDateTime.now().format(FILE_TS) + "-" + urlHash + ".json";
            Path file = dir.resolve(fileName);

            // 缩进美化，方便人工查看与后续解析
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
            log.info("外部数据已保存到 JSON 文件: {}", file.toAbsolutePath());

            return new CurlResponse(url, fileName, file.toAbsolutePath().toString(),
                    LocalDateTime.now(), data);
        } catch (IOException e) {
            log.error("JSON 文件写入失败，URL: {}, 目录: {}", url, storageDir, e);
            throw new RuntimeException("JSON文件写入失败: " + e.getMessage(), e);
        }
    }
}
