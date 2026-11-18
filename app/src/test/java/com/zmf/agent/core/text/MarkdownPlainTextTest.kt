package com.zmf.agent.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯文本转换：按块级与行内结构逐项核对输出，全部使用全文精确相等。 */
class MarkdownPlainTextTest {

    @Test
    fun headingKeepsTextOnly() {
        val source = "# 一级标题\n\n## 二级标题"
        assertEquals("一级标题\n\n二级标题", MarkdownPlainText.toPlainText(source))
    }

    @Test
    fun paragraphsKeepInnerBreaksAndBlockGap() {
        val source = "第一段第一行\n第一段第二行\n\n\n第二段"
        assertEquals(
            "第一段第一行\n第一段第二行\n\n第二段",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun nestedBulletListUsesTwoSpacesPerLevel() {
        val source = "- 一级\n  - 二级\n    - 三级\n- 回到一级"
        assertEquals(
            "· 一级\n  · 二级\n    · 三级\n· 回到一级",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun orderedListKeepsNumbers() {
        val source = "1. 第一项\n2. 第二项"
        assertEquals("1. 第一项\n2. 第二项", MarkdownPlainText.toPlainText(source))
    }

    @Test
    fun taskListUsesCheckboxGlyphs() {
        val source = "- [ ] 未完成\n- [x] 已完成\n- [X] 也已完成"
        assertEquals(
            "□ 未完成\n☑ 已完成\n☑ 也已完成",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun quoteIsIndentedAsBlock() {
        val source = "> 引用第一行\n> 引用第二行"
        assertEquals(
            "  引用第一行\n  引用第二行",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun codeBlockDropsFenceAndLanguage() {
        val source = "```kotlin\nfun main() {\n    println(1)\n}\n```"
        assertEquals(
            "fun main() {\n    println(1)\n}",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun inlineMarkersAreRemovedButCodeSpanIsKept() {
        val source = "**粗体** 与 *斜体* 与 ~~删除~~ 与 `**code**`"
        assertEquals(
            "粗体 与 斜体 与 删除 与 **code**",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun linksKeepLabelAndAppendDifferentUrl() {
        val source = "[OpenAI](https://openai.com) 与 [https://a.b](https://a.b)"
        assertEquals(
            "OpenAI (https://openai.com) 与 https://a.b",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun imageKeepsAltText() {
        val source = "前缀 ![示意图](https://img/x.png) 后缀"
        assertEquals("前缀 示意图 后缀", MarkdownPlainText.toPlainText(source))
        assertEquals("AB", MarkdownPlainText.toPlainText("A![](https://img/y.png)B"))
    }

    @Test
    fun tableDropsPipesAndSeparator() {
        val source = "| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |\n| 香蕉 | 5 |"
        assertEquals(
            "名称  数量\n苹果  3\n香蕉  5",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun tablePadsColumnsToWidestCell() {
        val source = "| a | 名称 |\n| --- | --- |\n| bbb | 苹果 |"
        assertEquals(
            "a    名称\nbbb  苹果",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun tableCountsCjkAsTwoColumns() {
        val source = "| 名称 | 值 |\n| --- | --- |\n| ab | 12 |"
        assertEquals(
            "名称  值\nab    12",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun tableWithoutDataKeepsHeader() {
        val source = "| a | b |\n| --- | --- |"
        assertEquals("a  b", MarkdownPlainText.toPlainText(source))
    }

    @Test
    fun dividerLeavesOneBlankLine() {
        val source = "上文\n\n---\n\n下文"
        assertEquals("上文\n\n下文", MarkdownPlainText.toPlainText(source))
    }

    @Test
    fun htmlTagsAndEntitiesAreDecoded() {
        val source = "你好 <b>世界</b> &amp; &quot;朋友&quot; &nbsp;!"
        assertEquals(
            "你好 世界 & \"朋友\"  !",
            MarkdownPlainText.toPlainText(source)
        )
    }

    @Test
    fun mixedDocumentKeepsStructure() {
        val source = "# 报告\n\n这是**第一**段。\n第二行。\n\n- 苹果\n- 香蕉\n\n```py\nprint(1)\n```\n\n| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |"
        val expected = "报告\n\n这是第一段。\n第二行。\n\n· 苹果\n· 香蕉\n\nprint(1)\n\n名称  数量\n苹果  3"
        assertEquals(expected, MarkdownPlainText.toPlainText(source))
    }

    @Test
    fun emptyInputReturnsEmptyString() {
        assertEquals("", MarkdownPlainText.toPlainText(""))
        assertEquals("", MarkdownPlainText.toPlainText("   \n\n  "))
    }

    @Test
    fun unclosedMarkersStayLiteral() {
        val source = "**未闭合 与 `半个代码"
        assertEquals(source, MarkdownPlainText.toPlainText(source))
    }

    @Test
    fun snakeCaseUnderscoreIsNotEmphasis() {
        val source = "foo_bar_baz 与 __强调__"
        assertEquals("foo_bar_baz 与 强调", MarkdownPlainText.toPlainText(source))
    }

    @Test
    fun carriageReturnsAreNormalized() {
        val source = "第一行\r\n\r\n第二行"
        assertEquals("第一行\n\n第二行", MarkdownPlainText.toPlainText(source))
    }

    @Test
    fun fallbackKeepsOriginalWhenConversionIsEmpty() {
        // 仅由分隔线组成的文档转不出文字，回退原文而不是写入空串
        assertEquals("---", MarkdownPlainText.toPlainTextOrOriginal("---"))
    }

    @Test
    fun keepCodeFenceKeepsFenceAndLanguage() {
        // 框选页 TXT：代码块不能套代码框，改用围栏符号圈出边界
        val source = "```kotlin\nfun main() {\n    println(1)\n}\n```"
        assertEquals(
            "```kotlin\nfun main() {\n    println(1)\n}\n```",
            MarkdownPlainText.toPlainText(source, keepCodeFence = true)
        )
    }

    @Test
    fun inlineUrlOffKeepsLabelOnly() {
        // 框选页 TXT：纯文本场景链接不可点，只留文字去掉 URL
        val source = "[OpenAI](https://openai.com) 与 [https://a.b](https://a.b)"
        assertEquals(
            "OpenAI 与 https://a.b",
            MarkdownPlainText.toPlainText(source, inlineUrlInLink = false)
        )
    }

    @Test
    fun defaultKeepCodeFenceAndUrlPreserveExistingSemantics() {
        // 复制按钮仍走旧语义：去围栏、链接带 URL，避免回归
        val source = "```kotlin\nfun main() {\n    println(1)\n}\n```\n\n[OpenAI](https://openai.com)"
        assertEquals(
            "fun main() {\n    println(1)\n}\n\nOpenAI (https://openai.com)",
            MarkdownPlainText.toPlainText(source)
        )
    }
}
