package com.zmf.agent.capability.tools.jobs

import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * job_output：读一个后台任务的输出，只给上次以来的增量。
 *
 * 语义对齐 DSH：
 * - `wait: true` 时最多等待 [MAX_WAIT_MS]，期间任务一旦结算即立刻返回结果，不会持续空等；
 * - 流式任务只给上次以来的增量，结算后的第一次读恰好把结果带回一次（[JobRegistry.read] 推进 delivered）；
 * - 结尾固定 `[status: ...]`，使模型能判断任务的当前状态。
 *
 * 只读内存注册表，不产生副作用，权限档是 READ_ONLY。
 */
class JobOutputTool : Tool {

    override val name = "job_output"

    override val description =
        "读取一个后台任务的输出，只返回上次读取以来的增量。任务还在跑时可以先做别的、等它结算再回来读；" +
            "传 wait=true 可以最多等 30 秒，任务在这期间结束会立刻返回。结尾固定带 [status: ...] 标明任务状态。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "job_id": {"type": "string", "description": "后台任务 id，见 job_list"},
            "wait": {"type": "boolean", "description": "是否等待任务结算，默认 false；true 时最多等 30 秒"},
            "timeout_ms": {"type": "number", "description": "wait=true 时的等待毫秒数，默认 30000，上限 30000"}
          },
          "required": ["job_id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val id = args.optString("job_id").trim()
        if (id.isEmpty()) return ToolResult.Error("缺少 job_id：要读哪个任务，见 job_list")

        val entry = JobRegistry.find(id)
            ?: return ToolResult.Error("没有这个后台任务：" + id + "（可能已结束并移出内存，或不属于当前会话；用 job_list 看当前有哪些）")
        if (entry.sessionId != ctx.sessionId) {
            return ToolResult.Error("这个任务不属于当前会话，无法读取：" + id)
        }

        val wait = args.optBoolean("wait", false)
        if (wait && entry.status == JobRegistry.Status.RUNNING) {
            val timeout = args.optLong("timeout_ms", MAX_WAIT_MS).coerceIn(1L, MAX_WAIT_MS)
            val deadline = System.currentTimeMillis() + timeout
            // 每 200ms 检查一次状态；任务结算后即退出等待
            while (System.currentTimeMillis() < deadline) {
                val now = JobRegistry.find(id)
                if (now == null || now.status != JobRegistry.Status.RUNNING) break
                delay(POLL_MS)
            }
        }

        val current = JobRegistry.find(id) ?: return ToolResult.Error("后台任务已移出内存：" + id)
        // since 用「上次交付到哪」：同一任务连续读两次，第二次只给新增的部分
        val (delta, total) = JobRegistry.read(id, current.deliveredBytes) ?: return ToolResult.Error("读不到任务：" + id)

        val body = if (delta.isEmpty()) "(无新增输出)" else delta
        val statusText = statusLine(current.status, current.note)
        return ToolResult.Ok(body + "\n[status: " + statusText + "]")
    }

    /** 状态行：采用 DSH 的 `[status: completed]` 固定结尾，模型可据此决定是否再次读取。 */
    private fun statusLine(status: JobRegistry.Status, note: String): String {
        val base = status.name.lowercase()
        return if (note.isBlank()) base else base + " — " + note
    }

    private companion object {
        const val MAX_WAIT_MS = 30_000L
        const val POLL_MS = 200L
    }
}
