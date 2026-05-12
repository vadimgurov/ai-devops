package com.vadim.devops.telegram;

/**
 * Converts LLM markdown output to Telegram HTML parse mode.
 * Telegram HTML supports: <b>, <i>, <code>, <pre>, <a href>
 */
public class TelegramMarkdownConverter {

    public static String convert(String text) {
        if (text == null || text.isBlank()) return text;

        var lines = text.split("\n", -1);
        var sb = new StringBuilder();
        var inCodeBlock = false;
        var codeLang = new StringBuilder();
        var codeContent = new StringBuilder();

        for (var line : lines) {
            if (line.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    codeLang.setLength(0);
                    codeContent.setLength(0);
                    var lang = line.substring(3).trim();
                    if (!lang.isEmpty()) codeLang.append(lang);
                } else {
                    inCodeBlock = false;
                    sb.append("<pre><code>").append(escapeHtml(codeContent.toString().stripTrailing()))
                      .append("</code></pre>\n");
                }
                continue;
            }
            if (inCodeBlock) {
                codeContent.append(line).append("\n");
                continue;
            }

            sb.append(convertLine(line)).append("\n");
        }

        // unclosed code block
        if (inCodeBlock) {
            sb.append("<pre><code>").append(escapeHtml(codeContent.toString().stripTrailing()))
              .append("</code></pre>\n");
        }

        return sb.toString().trim();
    }

    private static String convertLine(String line) {
        // headings → bold
        if (line.startsWith("#### ")) return "<b>" + inline(line.substring(5)) + "</b>";
        if (line.startsWith("### "))  return "<b>" + inline(line.substring(4)) + "</b>";
        if (line.startsWith("## "))   return "<b>" + inline(line.substring(3)) + "</b>";
        if (line.startsWith("# "))    return "<b>" + inline(line.substring(2)) + "</b>";

        // table separator row — skip
        if (line.matches("\\|[-|: ]+\\|")) return "";

        // table data row — strip outer pipes, join cells with │
        if (line.startsWith("|") && line.endsWith("|")) {
            var cells = line.substring(1, line.length() - 1).split("\\|");
            var row = new StringBuilder();
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) row.append(" │ ");
                row.append(inline(cells[i].trim()));
            }
            return row.toString();
        }

        // horizontal rule
        if (line.matches("[-*_]{3,}\\s*")) return "──────────";

        // unordered list
        if (line.matches("^(\\s*)[-*] .+")) {
            var indent = line.indexOf(line.stripLeading()) / 2;
            var content = line.replaceFirst("^\\s*[-*] ", "");
            return "  ".repeat(indent) + "• " + inline(content);
        }

        // ordered list — keep as-is, just format inline
        if (line.matches("^\\d+\\. .+")) {
            return line.replaceFirst("^(\\d+\\. )(.*)", "$1") + inline(line.replaceFirst("^\\d+\\. ", ""));
        }

        // blockquote
        if (line.startsWith("> ")) return "<i>" + inline(line.substring(2)) + "</i>";

        return inline(line);
    }

    /** Process inline markdown: **bold**, `code`, _italic_, *italic* */
    private static String inline(String text) {
        var sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (i + 1 < text.length() && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end != -1) {
                    sb.append("<b>").append(escapeHtml(text.substring(i + 2, end))).append("</b>");
                    i = end + 2;
                    continue;
                }
            }
            if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end != -1) {
                    sb.append("<code>").append(escapeHtml(text.substring(i + 1, end))).append("</code>");
                    i = end + 1;
                    continue;
                }
            }
            char c = text.charAt(i);
            if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '&') sb.append("&amp;");
            else sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
