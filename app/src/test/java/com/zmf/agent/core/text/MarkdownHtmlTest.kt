package com.zmf.agent.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** HTML 输出：按块级与行内结构核对标签与转义，全部使用全文精确相等。 */
class MarkdownHtmlTest {

    @Test
    fun headingAndInlineMarkersBecomeTags() {
        val source = "# 标题\n\n正文 **粗体** 与 `代码`"
        assertEquals(
            "<h1>标题</h1><p>正文 <strong>粗体</strong> 与 <code>代码</code></p>",
            MarkdownPlainText.toHtml(source)
        )
    }

    @Test
    fun nestedBulletListNestsUlInsideLi() {
        val source = "- 一级\n  - 二级\n- 回到一级"
        assertEquals(
            "<ul><li>一级<ul><li>二级</li></ul></li><li>回到一级</li></ul>",
            MarkdownPlainText.toHtml(source)
        )
    }

    @Test
    fun orderedListUsesOl() {
        val source = "1. 第一项\n2. 第二项"
        assertEquals(
            "<ol><li>第一项</li><li>第二项</li></ol>",
            MarkdownPlainText.toHtml(source)
        )
    }

    @Test
    fun taskListKeepsCheckboxGlyph() {
        val source = "- [x] 已完成"
        assertEquals("<ul><li>☑ 已完成</li></ul>", MarkdownPlainText.toHtml(source))
    }

    @Test
    fun tableKeepsStructureAndColumnAlign() {
        val source = "| 名 | 值 |\n| :--- | ---: |\n| a | 1 |"
        assertEquals(
            "<table border=\"1\" cellspacing=\"0\" cellpadding=\"6\" style=\"border-collapse:collapse\">" +
                "<thead><tr><th style=\"text-align:left\">名</th><th style=\"text-align:right\">值</th></tr></thead>" +
                "<tbody><tr><td style=\"text-align:left\">a</td><td style=\"text-align:right\">1</td></tr></tbody></table>",
            MarkdownPlainText.toHtml(source)
        )
    }

    @Test
    fun codeBlockKeepsLanguageAndEscapesContent() {
        val source = "```kotlin\nfun main() { println(\"<hi>\") }\n```"
        assertEquals(
            "<pre><code class=\"language-kotlin\">fun main() { println(&quot;&lt;hi&gt;&quot;) }</code></pre>",
            MarkdownPlainText.toHtml(source)
        )
    }

    @Test
    fun linkKeepsHrefAndRawHtmlTagsAreDropped() {
        val source = "[示例](https://example.com?a=1&b=2) 与 <b>标签</b> &amp; 结束"
        assertEquals(
            "<p><a href=\"https://example.com?a=1&amp;b=2\">示例</a> 与 标签 &amp; 结束</p>",
            MarkdownPlainText.toHtml(source)
        )
    }

    @Test
    fun javascriptLinkKeepsLabelOnly() {
        val source = "[点我](javascript:alert(1))"
        assertEquals("<p>点我</p>", MarkdownPlainText.toHtml(source))
    }

    @Test
    fun emptyInputReturnsEmptyString() {
        assertEquals("", MarkdownPlainText.toHtml("   "))
    }
}
