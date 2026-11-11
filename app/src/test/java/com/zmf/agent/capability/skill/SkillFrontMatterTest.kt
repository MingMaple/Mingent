package com.zmf.agent.capability.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skill frontmatter 的写入与解析往返（纯逻辑，不依赖 Android 框架）。
 *
 * 受管字段（name / description / whenToUse）必须能原样写回再读出；未识别字段
 * （license / allowed-tools 等）在编辑后仍须保留，且受管字段不得重复出现。
 */
class SkillFrontMatterTest {

    private fun roundTrip(description: String, whenToUse: String = ""): SkillStore.Parsed {
        val text = SkillStore.buildMarkdown(
            name = "release-apk",
            description = description,
            whenToUse = whenToUse,
            body = "第一步：检查版本号。"
        )
        return SkillStore.frontMatter(text)
    }

    @Test
    fun plainScalar_roundTrips() {
        val parsed = roundTrip("打包与装机")
        assertEquals("release-apk", parsed.meta["name"])
        assertEquals("打包与装机", parsed.meta["description"])
        assertTrue(parsed.body.contains("第一步"))
    }

    @Test
    fun colonAndHash_roundTrip() {
        val desc = "步骤：先看 #版本号"
        assertEquals(desc, roundTrip(desc).meta["description"])
    }

    @Test
    fun multilineDescription_roundTrips() {
        val desc = "第一行说明\n第二行说明"
        assertEquals(desc, roundTrip(desc).meta["description"])
    }

    @Test
    fun leadingHash_roundTrips() {
        val desc = "# 看起来像注释"
        assertEquals(desc, roundTrip(desc).meta["description"])
    }

    @Test
    fun quotesAndBackslashes_roundTrip() {
        val desc = "路径 C:\\tmp\\\"x\""
        assertEquals(desc, roundTrip(desc).meta["description"])
    }

    @Test
    fun whenToUse_roundTrips() {
        val parsed = roundTrip("打包", whenToUse = "用户要求出包时")
        assertEquals("用户要求出包时", parsed.meta["whenToUse"])
    }

    @Test
    fun unknownFields_arePreservedWithoutDuplicatingManaged() {
        val preserved = "license: MIT\nallowed-tools: [read, write]\nname: old\ndescription: 旧描述"
        val text = SkillStore.buildMarkdown(
            name = "new-skill",
            description = "新描述",
            body = "正文",
            preservedHeader = preserved
        )
        val parsed = SkillStore.frontMatter(text)
        val header = parsed.rawHeader.orEmpty()
        assertEquals("MIT", parsed.meta["license"])
        assertEquals("[read, write]", parsed.meta["allowed-tools"])
        assertEquals("new-skill", parsed.meta["name"])
        assertEquals("新描述", parsed.meta["description"])
        assertEquals(1, Regex("(?m)^name:").findAll(header).count())
        assertEquals(1, Regex("(?m)^description:").findAll(header).count())
    }

    @Test
    fun body_isPreserved() {
        val body = "第一步\n\n- 检查\n- 执行"
        val text = SkillStore.buildMarkdown("s", "d", body = body)
        assertEquals(body, SkillStore.frontMatter(text).body.trimEnd('\n'))
    }
}
