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
 * 当前会话的待办清单：读回来、以及新一轮提问前清掉「全部完成」的旧清单。
 *
 * 从 [ChatViewModel] 搬出，行为不变：状态字段名与原成员逐字一致，方法体原样照搬；
 * 会话 id 与协程域由构造参数注入。
 */
class TodoController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val currentSessionId: () -> String
) {

    /** 当前会话的待办清单（模型用 todo_write 写、TodoStore 落盘）；换会话与每轮结束后都重读一次。 */
    var todos by mutableStateOf<List<Todo>>(emptyList())
        private set

    /**
     * 重读当前会话的待办清单：IO 里读、主线程赋值。
     *
     * 清单是模型用 todo_write 写的，App 侧只读不算，所以换会话与每轮结束后各对齐一次。
     * 读盘期间又切走了会话就丢弃这次结果，别把上一个会话的清单显示到当前会话上。
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

    /** 换会话 / 回滚时清空界面上的清单（写盘由会话控制器负责）。 */
    fun clearTodos() {
        todos = emptyList()
    }

    /**
     * 新一轮提问前清掉上一轮「全部完成」的清单；还有 pending / in_progress 就保留。
     *
     * 清单是这一轮的执行计划：全部打勾说明这一轮已经收尾，留着只会挡住新一轮的进度；
     * 没做完的还得继续挂着，否则用户会以为任务自己消失了。
     * 落盘必须在这一轮开跑之前同步做完：模型的 todo_write 紧接着就会写同一份文件，
     * 放到 IO 线程里异步清，可能反过来把刚写好的新清单抹掉。
     */
    fun dropFinishedTodosBeforeNewRound() {
        val id = currentSessionId()
        if (id.isEmpty()) return
        // 以盘上的清单为准：reloadTodos 是异步的，刚结束的那一轮可能还没读回来
        val stored = TodoStore.load(appContext, id)
        if (stored.isEmpty() || stored.any { it.status != TodoStore.COMPLETED }) return
        TodoStore.save(appContext, id, emptyList())
        todos = emptyList()
        FileLog.log("todo", "上一轮清单已全部完成，新一轮提问时清空")
    }
}
