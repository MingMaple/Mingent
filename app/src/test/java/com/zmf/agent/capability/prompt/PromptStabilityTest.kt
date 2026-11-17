package com.zmf.agent.capability.prompt

import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolRegistry
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.llm.CacheAudit
import com.zmf.agent.core.model.PermissionLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前缀缓存的字节稳定性：system 只由静态段组成，运行时状态一律走尾部快照。
 *
 * 断言的是「发请求时到底拼了什么」，而不是某个函数内部结构：
 * [PromptBuilder.buildStable] 的返回值就是 system 消息正文，[PromptBuilder.buildRuntimeSnapshot]
 * 的返回值就是挂在当前 user 消息之后的那一条。任何一项随运行时状态变化，整段前缀缓存即失效。
 */
class PromptStabilityTest {

    private fun input(
        now: String = "2026-11-17 10:00（周一，GMT+08:00）",
        permission: PermissionLevel = PermissionLevel.WORKSPACE,
        planMode: Boolean = false,
        workspace: String = "/root/ws",
        goal: String = "",
        skills: List<Pair<String, String>> = listOf("demo" to "演示用技能")
    ) = PromptInput(
        model = "deepseek/deepseek-flash",
        workspacePath = workspace,
        permission = permission,
        androidVersion = "12",
        nowText = now,
        skills = skills,
        extraInstructions = "一律用中文回答",
        planMode = planMode,
        goalText = goal
    )

    @Test
    fun `system stays byte identical when runtime state changes`() {
        val base = PromptBuilder.buildStable(input())
        val changed = PromptBuilder.buildStable(
            input(
                now = "2027-01-01 23:59（周五，GMT+08:00）",
                permission = PermissionLevel.FULL,
                planMode = true,
                workspace = "/root/another",
                goal = "把缓存命中率提上去"
            )
        )
        assertEquals(base, changed)
    }

    @Test
    fun `runtime state only appears in the tail snapshot`() {
        val base = input()
        val changed = input(
            now = "2027-01-01 23:59（周五，GMT+08:00）",
            permission = PermissionLevel.READ_ONLY,
            planMode = true,
            workspace = "/root/another",
            goal = "目标变了"
        )
        val system = PromptBuilder.buildStable(base)!!
        listOf("10:00（周一", "/root/ws", "把缓存命中率提上去", "- 权限档：", "- 计划模式：").forEach { marker ->
            assertTrue("system 不应包含运行时状态：" + marker, !system.contains(marker))
        }
        val snapshot = PromptBuilder.buildRuntimeSnapshot(changed)!!
        assertTrue(snapshot.contains(PromptBuilder.SNAPSHOT_MARKER))
        assertTrue(snapshot.contains("/root/another"))
        assertTrue(snapshot.contains("目标变了"))
        assertNotEquals(PromptBuilder.buildRuntimeSnapshot(base), snapshot)
    }

    @Test
    fun `checkpoint shares one fixed header and changes only the summary body`() {
        val first = PromptBuilder.checkpointText("旧的摘要")
        val second = PromptBuilder.checkpointText("新的摘要")
        val header = PromptBuilder.CHECKPOINT_PREAMBLE + "\n\n" + PromptBuilder.CHECKPOINT_OPEN
        assertTrue(first.startsWith(header))
        assertTrue(second.startsWith(header))
        assertTrue(first.endsWith(PromptBuilder.CHECKPOINT_CLOSE))
        assertTrue(second.endsWith(PromptBuilder.CHECKPOINT_CLOSE))
        assertNotEquals(first, second)
    }

    @Test
    fun `system override replaces everything and injects no snapshot`() {
        val input = input().copy(systemOverride = "只用这一份")
        assertEquals("只用这一份", PromptBuilder.buildStable(input))
        assertEquals(null, PromptBuilder.buildRuntimeSnapshot(input))
    }

    /** 一个不执行的假工具：只提供声明字段，用于验证工具表排序。 */
    private fun fakeTool(name: String) = object : Tool {
        override val name = name
        override val description = "假工具 " + name
        override val parametersJson = "{}"
        override val minPermission = PermissionLevel.READ_ONLY
        override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
            ToolResult.Ok("not used")
    }

    @Test
    fun `tool specs are sorted by name and stable across calls`() {
        val registry = ToolRegistry(listOf(fakeTool("zeta"), fakeTool("alpha")))
        val first = registry.specs().map { it.name }
        val second = registry.specs().map { it.name }
        assertEquals(listOf("alpha", "zeta"), first)
        assertEquals(first, second)
    }

    @Test
    fun `checkpoint and runtime snapshot are separate message texts`() {
        val system = PromptBuilder.buildStable(input())!!
        // system 的正文会引用「本轮运行状态」这个说法，但不得包含快照块本身（判断真实抬头而非词）
        listOf(
            PromptBuilder.CHECKPOINT_PREAMBLE,
            PromptBuilder.CHECKPOINT_OPEN,
            PromptBuilder.CHECKPOINT_CLOSE,
            PromptBuilder.SNAPSHOT_MARKER + "（以此为准"
        ).forEach { marker ->
            assertTrue("system 不应包含独立消息文本：" + marker, !system.contains(marker))
        }
        val checkpoint = PromptBuilder.checkpointText("摘要正文")
        assertTrue(checkpoint.startsWith(PromptBuilder.CHECKPOINT_PREAMBLE))
        assertTrue(checkpoint.contains(PromptBuilder.CHECKPOINT_OPEN))
        assertTrue(checkpoint.endsWith(PromptBuilder.CHECKPOINT_CLOSE))
        val snapshot = PromptBuilder.buildRuntimeSnapshot(input())!!
        assertTrue(snapshot.startsWith(PromptBuilder.SNAPSHOT_MARKER))
    }

    @Test
    fun `cache audit does not change prompt bytes`() {
        val source = input()
        val systemBefore = PromptBuilder.buildStable(source)
        val snapshotBefore = PromptBuilder.buildRuntimeSnapshot(source)
        CacheAudit.audit(
            CacheAudit.Input(
                prevPromptTokens = 10_000,
                prevModel = "deepseek/deepseek-flash",
                prevReportedCache = true,
                promptTokens = 10_500,
                cacheReadTokens = 1_000,
                cacheWriteTokens = 0,
                idleMillis = 1_000L,
                model = "deepseek/deepseek-flash"
            )
        )
        assertEquals(systemBefore, PromptBuilder.buildStable(source))
        assertEquals(snapshotBefore, PromptBuilder.buildRuntimeSnapshot(source))
    }
}
