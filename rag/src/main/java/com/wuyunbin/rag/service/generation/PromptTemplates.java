package com.wuyunbin.rag.service.generation;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板加载与简单占位符渲染。
 *
 * <p>所有 Prompt 统一放在 {@code src/main/resources/prompts/}，不写死在 Java 方法中。
 * 渲染使用 {@code {name}} 占位符 + 字符串替换（避免模板引擎与 JSON 花括号冲突）。</p>
 */
@Component
public class PromptTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");
    private final ResourceLoader resourceLoader;

    public PromptTemplates(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 将模板中的 {key} 替换为 values 中对应值；null 值替换为空串。
     */
    public static String render(String template, Map<String, String> values) {
        // Replace only placeholders in the template. Source quotes may themselves contain literal placeholders.
        return PLACEHOLDER.matcher(template).replaceAll(match -> Matcher.quoteReplacement(values.containsKey(match.group(1))
                ? Objects.toString(values.get(match.group(1)), "") : match.group()));
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
