package com.wuyunbin.rag.service;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * HTML 转 Markdown 转换器：去噪后递归渲染正文，输出标准 Markdown。
 *
 * <p>输出的 ATX 标题（# ~ ######）可直接被 heading-aware 切片管线识别章节。
 * 覆盖：标题、段落、列表（含嵌套）、代码块与行内代码、引用、链接、图片、表格、分隔线；
 * 不追求 100% HTML 覆盖，目标是 RAG 可读的纯内容文本。</p>
 */
@Component
public class HtmlToMarkdownConverter {

    /**
     * 整体移除的噪声标签：脚本、样式、导航、页头页脚、广告侧栏、表单控件、
     * 面包屑、上下篇导航、分页、评论、相关推荐、分享、标签云、广告位等。
     */
    private static final String NOISE_SELECTOR =
            "script,style,noscript,iframe,svg,canvas,nav,header,footer,aside,form,"
                    + "button,input,select,textarea,template,[aria-hidden=true],"
                    + ".sidebar,#sidebar,.aside,#aside,.breadcrumb,.breadcrumbs,"
                    + ".pagination,.pager,.page-nav,.prev,.next,.previous,.post-nav,.article-nav,.prev-next,"
                    + ".related,.recommend,.related-posts,.recommend-list,.comments,#comments,.comment-list,"
                    + ".share,.social,.social-share,.advertisement,.ads,.ad,.tag-list,.tags,.tag-cloud,"
                    + ".footer-links,.copyright,.back-to-top,.toc,.table-of-contents";

    private static final Pattern MULTI_NEWLINE = Pattern.compile("\n{3,}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * 转换文档正文为 Markdown。
     *
     * @param document jsoup 已解析的文档（含 baseUri，用于补全相对链接）
     * @param url      页面地址，作为来源附注追加到末尾
     */
    public String convert(Document document, String url) {
        document.select(NOISE_SELECTOR).remove();

        Element root = pickContentRoot(document);
        StringBuilder sb = new StringBuilder();
        renderChildren(root, sb, 0);

        String body = normalize(sb);
        StringBuilder out = new StringBuilder();
        String title = cleanTitle(document.title());
        // 正文首个 h1 与页面标题一致时不重复添加
        String firstLine = body.isEmpty() ? "" : body.split("\n", 2)[0].strip();
        if (!title.isEmpty() && !("# " + title).equals(firstLine)) {
            out.append("# ").append(title).append("\n\n");
        }
        out.append(body).append("\n\n---\n> 来源: ").append(url).append('\n');
        return normalize(out);
    }

    /**
     * 正文容器候选选择器：覆盖通用语义标签与主流教程/博客站点正文容器。
     */
    private static final List<String> CONTENT_SELECTORS = List.of(
            "article", "main", "[role=main]",
            "#content", ".content", "#article", ".article",
            ".article-intro", ".article-content", ".post-content", ".entry-content",
            ".markdown-body", ".rich_media_content", "#detail", ".detail",
            ".content-article", ".w3cnote", ".post", ".post-body", ".entry",
            ".hentry", ".single-content", ".article-body", "#article-content",
            "body"
    );

    /**
     * 从候选容器中选择正文最密集的那个：综合文本长度与段落/标题数，
     * 避免命中仅含少量链接的侧栏/页脚容器。
     */
    private Element pickContentRoot(Document document) {
        Element best = null;
        double bestScore = 0;
        for (String selector : CONTENT_SELECTORS) {
            for (Element el : document.select(selector)) {
                if (el == document.body() && best != null) {
                    // body 仅作兜底，已有更具体容器命中时跳过
                    continue;
                }
                String text = el.text().strip();
                if (text.length() < 80) {
                    continue;
                }
                long pCount = el.select("p,h1,h2,h3,h4,h5,h6,li,pre,blockquote").size();
                // 文本长度权重高，段落数提供结构信号；分母用容器选择器层级避免大容器天然占优
                double score = text.length() + pCount * 50.0;
                if (score > bestScore) {
                    bestScore = score;
                    best = el;
                }
            }
        }
        return best != null ? best : document.body();
    }

    private void renderChildren(Node node, StringBuilder sb, int listDepth) {
        for (Node child : node.childNodes()) {
            renderNode(child, sb, listDepth);
        }
    }

    private void renderNode(Node node, StringBuilder sb, int listDepth) {
        if (node instanceof TextNode textNode) {
            String text = textNode.getWholeText();
            // 代码块外的文本折叠空白
            sb.append(WHITESPACE.matcher(text).replaceAll(" "));
            return;
        }
        if (!(node instanceof Element el)) {
            return;
        }
        String tag = el.tagName().toLowerCase();

        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '0';
                appendBlock(sb, "#".repeat(level) + " " + inlineText(el).strip());
            }
            case "p", "div", "section", "article", "main", "figure",
                 "figcaption", "address", "details", "summary", "dl" -> {
                // 通用容器：递归保留内部块级结构（标题/列表/表格），不能降级为纯文本
                StringBuilder container = new StringBuilder();
                renderChildren(el, container, listDepth);
                if (!container.toString().isBlank()) {
                    appendBlock(sb, container.toString().strip());
                }
            }
            case "br" -> sb.append("  \n");
            case "hr" -> appendBlock(sb, "---");
            case "pre" -> renderCodeBlock(el, sb);
            case "blockquote" -> renderBlockquote(el, sb);
            case "ul", "ol", "menu" -> renderList(el, sb, listDepth, tag.equals("ol"));
            case "table" -> renderTable(el, sb);
            case "a" -> appendLink(el, sb);
            case "img" -> appendImage(el, sb);
            case "strong", "b" -> appendInlineWrap(el, sb, "**");
            case "em", "i" -> appendInlineWrap(el, sb, "*");
            case "code" -> {
                String code = el.text();
                if (!code.isBlank()) {
                    sb.append('`').append(code.replace("`", "``")).append('`');
                }
            }
            case "li", "dd" -> renderChildren(el, sb, listDepth);
            case "dt" -> appendBlock(sb, "**" + inlineText(el).strip() + "**");
            default -> renderChildren(el, sb, listDepth);
        }
    }

    private void renderCodeBlock(Element el, StringBuilder sb) {
        // 优先取内部 code 元素文本，pre 内可能嵌套 code
        Element codeEl = el.selectFirst("code");
        String code = codeEl != null ? codeEl.wholeText() : el.wholeText();
        String lang = "";
        if (codeEl != null) {
            String cls = codeEl.className();
            java.util.regex.Matcher m = Pattern.compile("language-([\\w+-]+)").matcher(cls);
            if (m.find()) {
                lang = m.group(1);
            }
        }
        appendBlock(sb, "```" + lang + "\n" + code.strip() + "\n```");
    }

    private void renderBlockquote(Element el, StringBuilder sb) {
        StringBuilder inner = new StringBuilder();
        renderChildren(el, inner, 0);
        // 按行加 > 前缀，保留引用内的块级结构
        StringBuilder quoted = new StringBuilder();
        for (String line : inner.toString().strip().split("\n")) {
            quoted.append("> ").append(line).append('\n');
        }
        appendBlock(sb, quoted.toString().stripTrailing());
    }

    private void renderList(Element list, StringBuilder sb, int depth, boolean ordered) {
        int index = 1;
        String indent = "  ".repeat(depth);
        // 只处理列表的直接 li，避免把嵌套列表内的 li 提前吞掉
        for (Element li : list.children().select("li")) {
            if (li.parent() != list) {
                continue;
            }
            String marker = ordered ? (index++ + ". ") : "- ";
            // 排除嵌套列表后取本项文本，避免与缩进渲染的子项重复
            Element textOnly = li.clone();
            textOnly.select("ul,ol,menu").remove();
            String itemText = inlineText(textOnly).strip();
            sb.append(indent).append(marker).append(itemText).append('\n');
            // 只递归直接嵌套的列表，防止多层嵌套时重复渲染
            for (Element nested : li.children()) {
                String nestedTag = nested.tagName().toLowerCase();
                if (nestedTag.equals("ul") || nestedTag.equals("ol") || nestedTag.equals("menu")) {
                    renderList(nested, sb, depth + 1, nestedTag.equals("ol"));
                }
            }
        }
        sb.append('\n');
    }

    private void renderTable(Element table, StringBuilder sb) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder md = new StringBuilder();
        boolean headerWritten = false;
        for (Element row : rows) {
            Elements cells = row.select("th,td");
            if (cells.isEmpty()) {
                continue;
            }
            md.append('|');
            int colCount = 0;
            for (Element cell : cells) {
                md.append(' ').append(escapeCell(cell.text())).append(" |");
                colCount++;
            }
            md.append('\n');
            if (!headerWritten) {
                md.append("|").append(" --- |".repeat(colCount)).append('\n');
                headerWritten = true;
            }
        }
        if (md.length() > 0) {
            appendBlock(sb, md.toString().stripTrailing());
        }
    }

    private String escapeCell(String text) {
        return text.replace('|', '｜').replace('\n', ' ').strip();
    }

    private void appendLink(Element el, StringBuilder sb) {
        String text = inlineText(el).strip();
        String href = el.absUrl("href");
        if (text.isEmpty()) {
            return;
        }
        if (href.isEmpty() || href.startsWith("javascript:")) {
            sb.append(text);
        } else {
            sb.append('[').append(text).append("](").append(href).append(')');
        }
    }

    private void appendImage(Element el, StringBuilder sb) {
        String alt = el.attr("alt").strip();
        String src = el.absUrl("src");
        if (!src.isEmpty()) {
            sb.append("![").append(alt).append("](").append(src).append(')');
        }
    }

    private void appendInlineWrap(Element el, StringBuilder sb, String marker) {
        // 加粗/斜体是行内语义，直接取文本即可
        String text = inlineText(el);
        if (!text.isEmpty()) {
            sb.append(marker).append(text).append(marker);
        }
    }

    private String inlineText(Element el) {
        return WHITESPACE.matcher(el.text()).replaceAll(" ").strip();
    }

    private void appendBlock(StringBuilder sb, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!sb.isEmpty() && !sb.toString().endsWith("\n\n")) {
            if (sb.charAt(sb.length() - 1) == '\n') {
                sb.append('\n');
            } else {
                sb.append("\n\n");
            }
        }
        sb.append(content.strip()).append("\n\n");
    }

    private String normalize(StringBuilder sb) {
        return MULTI_NEWLINE.matcher(sb.toString()).replaceAll("\n\n").strip();
    }

    /**
     * 去掉常见站点标题后缀，如「文章标题 - 某某网」「文章标题_某某网」。
     */
    private String cleanTitle(String rawTitle) {
        if (rawTitle == null) {
            return "";
        }
        String title = rawTitle.strip();
        int cut = title.length();
        for (String sep : new String[]{" - ", " – ", " — ", " | ", "_"}) {
            int idx = title.indexOf(sep);
            if (idx > 0 && idx < cut) {
                cut = idx;
            }
        }
        // 只有在后缀明显较短（站点名）时才截断，避免误伤含破折号的真实标题
        String candidate = title.substring(0, cut).strip();
        if (!candidate.isEmpty() && cut < title.length() && title.length() - cut <= 12) {
            return candidate;
        }
        return title;
    }
}
