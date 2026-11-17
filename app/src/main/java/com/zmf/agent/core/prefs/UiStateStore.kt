package com.zmf.agent.core.prefs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 每条会话的**界面格局**（D3）：键 `ui_state_<sessionId>`，值为一段 JSON。
 *
 * 只存结构：停在哪一页（仅 PLAN / PREVIEW / BROWSER——其余是设置层，重启一律回主页）、
 * 计划页选中哪份、预览停在哪条标签、浏览页开着哪些地址。
 * 滚动位置、窗口几何、输入草稿与待发附件一律不存：它们是运行态，恢复它们等于「追像素」。
 *
 * 写入前比对旧值，值没变不写盘；删除会话时由删除点同步调 [forget]，不留孤儿键。
 * 本文件是独立的 SharedPreferences（`ui_state`），不进 `BackupArchive.DATA_FILES` 的备份白名单：
 * 格局属于设备上的摆放习惯，跟着新设备迁移没有意义。
 */
object UiStateStore {

    private const val PREFS_NAME = "ui_state"
    private const val KEY_PREFIX = "ui_state_"

    /** 落盘结构版本：字段增删时递增，版本不匹配按「没有记录」处理。 */
    private const val VERSION = 1

    /** 可持久化的页面名；其余页（设置层、轨迹等）重启后一律回主页。 */
    private val PERSISTED_PAGES = setOf("PLAN", "PREVIEW", "BROWSER")

    /** 某条会话上次留下的格局；没有记录（或版本不匹配）返回 null。 */
    class Saved(
        val page: String?,
        val planId: String?,
        val previewPath: String?,
        val browserUrls: List<String>
    )

    /** 读取某条会话的格局；解析失败的字段按空处理，不让一条坏记录拖垮启动。 */
    fun load(context: Context, sessionId: String): Saved? {
        val raw = prefs(context).getString(KEY_PREFIX + sessionId, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (obj.optInt("version", -1) != VERSION) return null
        val page = obj.optString("page", "").ifEmpty { null }?.takeIf { it in PERSISTED_PAGES }
        val urls = runCatching {
            val arr = obj.optJSONArray("browser_urls") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { u -> u.isNotBlank() } }
        }.getOrDefault(emptyList())
        return Saved(
            page = page,
            planId = obj.optString("plan_id", "").ifEmpty { null },
            previewPath = obj.optString("preview_path", "").ifEmpty { null },
            browserUrls = urls
        )
    }

    /**
     * 记录某条会话的格局。与上次相同则不写盘。
     *
     * **同步刷盘**：用 `commit` 而不是 `apply`。`am force-stop`、从最近任务划掉应用这类结束方式
     * 不会投递 `ON_STOP`，`apply` 的异步写在进程被杀时可能整段丢失（D3 实测）。
     * 本方法会阻塞到写入完成，调用方在主线程时必须先切到 IO 线程。
     * [page] 不在白名单内时按主页处理（存空串），调用方负责在「确实回到主页」时改调 [forget]。
     */
    fun save(
        context: Context,
        sessionId: String,
        page: String?,
        planId: String?,
        previewPath: String?,
        browserUrls: List<String>
    ) {
        if (sessionId.isEmpty()) return
        val obj = JSONObject()
            .put("version", VERSION)
            .put("page", page?.takeIf { it in PERSISTED_PAGES } ?: "")
            .put("plan_id", planId.orEmpty())
            .put("preview_path", previewPath.orEmpty())
            .put("browser_urls", JSONArray(browserUrls))
        val raw = obj.toString()
        val key = KEY_PREFIX + sessionId
        // 比对旧值：恢复流程把同一份内容写回时不产生新的磁盘写
        if (prefs(context).getString(key, null) == raw) return
        prefs(context).edit().putString(key, raw).commit()
    }

    /** 删除会话时清掉它的格局键；同时把「回到主页」时清掉旧记录也走这里。 */
    fun forget(context: Context, sessionId: String) {
        if (sessionId.isEmpty()) return
        prefs(context).edit().remove(KEY_PREFIX + sessionId).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
