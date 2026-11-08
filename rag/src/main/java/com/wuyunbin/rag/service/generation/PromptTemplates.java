package com.wuyunbin.rag.service.generation;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Prompt 模板加载与简单占位符渲染。
 *
 * <p>所有 Prompt 统一放在 {@code src/main/resources/prompts/}，不写死在 Java 方法中。
 * 渲染使用 {@code {name}} 占位符 + 字符串替换（避免模板引擎与 JSON 花括号冲突）。</p>
 */
@Component
public class PromptTemplates {

    private final ResourceLoader resourceLoader;

    public PromptTemplates(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 将模板中的 {key} 替换为 values 中对应值；null 值替换为空串。
     */
    public static String render(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace("{" + entry.getKey() + "}", value);
        }
        return result;
    }

    /**
     * 以 UTF-8 读取 classpath 或文件资源为模板字符串。
     */
    public String load(String location) {
        Resource resource = resourceLoader.getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + location, e);
        }
    }
}
