package com.zmf.agent.capability.loop

import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/**
 * 一步内工具调用的执行段：并发段（若干并发安全调用重叠）或独占段（一个调用，构成屏障）。
 *
 * 分段口径对齐 DSH 的 `executionMode`（`dsh-tools/lib/pipeline.js`）：连续多个并发安全调用
 * 合成一段重叠执行，其余调用各自独占一段；整步的结果仍按模型给出的调用顺序返回。
 */
internal class ToolBatch private constructor(
    val calls: List<ToolCall>,
    /** true = 段内调用可重叠；false = 独占，段内只有一个调用。 */
    val parallel: Boolean
) {
    companion object {
        /**
         * 按 [parallelSafe] 结果切段。
         *
         * 判定只看调用自身（工具声明 + 本次是否写盘），与已启动的调用无关，
         * 因此「连续并发安全 → 一段」与 DSH 在补位时重新分类的效果一致。
         */
        fun plan(calls: List<ToolCall>, parallelSafe: (ToolCall) -> Boolean): List<ToolBatch> {
            val batches = ArrayList<ToolBatch>()
            var index = 0
            while (index < calls.size) {
                if (!parallelSafe(calls[index])) {
                    batches.add(ToolBatch(listOf(calls[index]), parallel = false))
                    index++
                    continue
                }
                var end = index
                while (end < calls.size && parallelSafe(calls[end])) end++
                batches.add(ToolBatch(calls.subList(index, end).toList(), parallel = true))
                index = end
            }
            return batches
        }
    }
}

/**
 * 执行一段工具调用，结果与 [ToolBatch.calls] **同下标同序**。
 *
 * 并发段的每个调用各占一个子协程：回合被停止时全部随之取消，且 `supervisorScope` 会等
 * 已开始的调用停稳后才把取消继续上抛（对齐 DSH「中止后排空已开始的工作」）。
 * 单个调用被单独中止（例如工具自身的时限以取消形式结束）不影响同段其他调用，
 * 该位置折成一条失败结果，保证调用与结果一一对应。
 */
internal suspend fun executeToolBatch(
    batch: ToolBatch,
    run: suspend (ToolCall) -> ToolResult
): List<ToolResult> {
    if (!batch.parallel || batch.calls.size == 1) {
        return listOf(run(batch.calls[0]))
    }
    return supervisorScope {
        val jobs = batch.calls.map { call -> async { run(call) } }
        jobs.map { it.awaitOrFold() }
    }
}

/** 收割一个调用；父协程仍存活而该调用被单独取消时，折成失败结果而不是连带终止整段。 */
private suspend fun Deferred<ToolResult>.awaitOrFold(): ToolResult =
    try {
        await()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
        ToolResult.Error("工具执行已取消：该调用被单独中止，其他工具调用不受影响")
    }
