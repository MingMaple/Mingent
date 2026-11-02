package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.store.Todo
import com.zmf.agent.core.store.TodoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 当前会话的待办清单：读取，以及新一轮提问前清除「全部完成」的旧清单。
 *
 * 从 [ChatViewModel] 拆出，行为不变：状态字段名与原成员逐字一致，方法体原样保留；
 * 会话 id 与协程域由构造参数注入。
 */
class TodoController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val currentSessionId: () -> String
) {

    /** 当前会话的待办清单（模型通过 todo_write 写入、由 TodoStore 落盘）；切换会话与每轮结束后均重新读取。 */
    var todos by mutableStateOf<List<Todo>>(emptyList())
        private set

    /**
     * 重新读取当前会话的待办清单：在 IO 中读取、主线程赋值。
     *
     * 清单由模型通过 todo_write 写入，App 侧只读不计算，因此切换会话与每轮结束后各同步一次。
     * 读取期间若会话已切换，则丢弃本次结果，避免将上一会话的清单显示到当前会话。
     */
    fun reloadTodos() {
        val id = currentSessionId()
        if (id.isEmpty()) {
            todos = emptyList()
            return
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { TodoStore.load(appContext, id) }
            if (currentSessionId() == id) todos = loaded
        }
    }

    /** 切换会话或回滚时清空界面上的清单（写盘由会话控制器负责）。 */
    fun clearTodos() {
        todos = emptyList()
    }

    /**
     * 新一轮提问前清掉上一轮「全部完成」的清单；还有 pending / in_progress 就保留。
     *
     * 清单是本轮的执行计划：全部完成说明本轮已收尾，保留只会干扰新一轮进度；
     * 未完成项需继续显示，否则会被误认为任务自行消失。
     * 落盘必须在这一轮开跑之前同步做完：模型的 todo_write 紧接着就会写同一份文件，
     * 若放入 IO 线程异步清除，可能反向覆盖刚写入的新清单。
     */
    fun dropFinishedTodosBeforeNewRound() {
        val id = currentSessionId()
        if (id.isEmpty()) return
        // 以磁盘上的清单为准：reloadTodos 为异步操作，刚结束的一轮可能尚未读取完成
        val stored = TodoStore.load(appContext, id)
        if (stored.isEmpty() || stored.any { it.status != TodoStore.COMPLETED }) return
        TodoStore.save(appContext, id, emptyList())
        todos = emptyList()
        FileLog.log("todo", "上一轮清单已全部完成，新一轮提问时清空")
    }
}
