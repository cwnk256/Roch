package com.wuyunbin.rag.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlToMarkdownConverterTest {

    private final HtmlToMarkdownConverter converter = new HtmlToMarkdownConverter();

    private String convert(String html) {
        Document doc = Jsoup.parse(html, "https://example.com/");
        return converter.convert(doc, "https://example.com/page");
    }

    @Test
    void convertsHeadingsParagraphsLinksAndRemovesNoise() {
        String md = convert("""
                <html><head><title>新生指南 - 集美大学</title></head>
                <body>
                  <nav>导航栏文字应被移除</nav>
                  <article>
                    <h1>新生指南</h1>
                    <p>欢迎来到 <a href="/welcome">集美大学</a> 报到。</p>
                    <h2>所需材料</h2>
                    <p>身份证与<strong>录取通知书</strong>。</p>
                    <script>alert('脚本应移除')</script>
                  </article>
                  <footer>版权所有应移除</footer>
                </body></html>
                """);

        assertTrue(md.contains("# 新生指南"), "应以页面标题开头: " + md);
        assertTrue(md.contains("## 所需材料"), "h2 应转为 ATX 标题");
        assertTrue(md.contains("[集美大学](https://example.com/welcome)"), "相对链接应补全为绝对链接");
        assertTrue(md.contains("**录取通知书**"), "strong 应转 Markdown 加粗");
        assertFalse(md.contains("导航栏文字"), "nav 噪声应移除");
        assertFalse(md.contains("版权所有"), "footer 噪声应移除");
        assertFalse(md.contains("alert"), "script 应移除");
        assertTrue(md.contains("> 来源: https://example.com/page"), "应附来源");
    }

    @Test
    void convertsNestedListAndTableAndCode() {
        String md = convert("""
                <html><body><article>
                  <h3>清单</h3>
                  <ul>
                    <li>证件
                      <ul><li>身份证</li><li>户口本</li></ul>
                    </li>
                    <li>照片</li>
                  </ul>
                  <table>
                    <tr><th>项目</th><th>数量</th></tr>
                    <tr><td>照片</td><td>8</td></tr>
                  </table>
                  <pre><code class="language-java">int a = 1;</code></pre>
                </article></body></html>
                """);

        assertTrue(md.contains("- 证件"), md);
        assertTrue(md.contains("  - 身份证"), "嵌套列表应缩进");
        assertTrue(md.contains("- 照片"));
        assertTrue(md.contains("| 项目 | 数量 |"), "表头应转换");
        assertTrue(md.contains("| --- | --- |"), "应有表格分隔行");
        assertTrue(md.contains("| 照片 | 8 |"), "表格数据应转换");
        assertTrue(md.contains("```java"), "代码块应保留语言标注");
        assertTrue(md.contains("int a = 1;"), "代码内容应保留");
        // 嵌套项文本不应同时出现在父项行
        assertFalse(md.contains("- 证件 身份证"), "父项不应拼接嵌套列表文本");
    }

    @Test
    void orderedListUsesNumbers() {
        String md = convert("""
                <html><body><article>
                  <ol><li>第一步</li><li>第二步</li></ol>
                </article></body></html>
                """);
        assertTrue(md.contains("1. 第一步"));
        assertTrue(md.contains("2. 第二步"));
    }
}
