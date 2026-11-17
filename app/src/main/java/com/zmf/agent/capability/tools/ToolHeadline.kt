package com.zmf.agent.capability.tools

import org.json.JSONObject

/*
 * 工具调用的「步骤标题」推导。
 *
 * 界面（主页工具卡、轨迹页）与 peer 回执必须对同一次调用给出同一句话，否则用户看到的
 * 与统筹者读到的会分叉。因此把规则放在工具层，两处共用；界面侧只保留渲染。
 */

/** 标题的字符上限：界面标题行只有一行，再长就省略号。 */
const val TOOL_HEADLINE_LIMIT = 44

/** 参数中最可能充当宾语的字段，按优先级取第一个非空值。 */
private val SUMMARY_ARG_KEYS = listOf(
    "path", "file_path", "filePath", "command", "cmd", "query", "url",
    "description", "prompt", "title", "goal", "pattern", "name", "session_id"
)

/** 每个工具对应的动词：无需识别工具名即可读出「正在执行什么」。 */
private val TOOL_VERBS = mapOf(
    "read_file" to "读取",
    "write_file" to "写入",
    "edit_file" to "修改文件",
    "delete_file" to "删除文件",
    "glob" to "查找文件",
    "grep" to "检索内容",
    "present" to "交付文件",
    "apply_patch" to "应用补丁",
    "terminal" to "运行命令",
    "job_start" to "启动后台任务",
    "job_output" to "读取任务输出",
    "job_list" to "列出后台任务",
    "job_kill" to "终止后台任务",
    "session_list" to "列出会话",
    "session_create" to "新建会话",
    "session_rename" to "重命名会话",
    "session_move" to "移动会话",
    "session_archive" to "归档或恢复会话",
    "session_delete" to "删除会话",
    "session_update_settings" to "更新会话设置",
    "session_search" to "检索会话",
    "peer" to "协同会话",
    "workspace_list" to "列出工作区",
    "workspace_create" to "创建工作区",
    "workspace_rename" to "重命名工作区",
    "workspace_set_root" to "设置工作区根目录",
    "workspace_delete" to "删除工作区",
    "scheduled_task_create" to "创建定时任务",
    "scheduled_task_view" to "查看定时任务",
    "scheduled_task_edit" to "修改定时任务",
    "scheduled_task_delete" to "删除定时任务",
    "web_fetch" to "抓取网页",
    "web_search" to "联网检索",
    "browser_open" to "打开网页",
    "browser_snapshot" to "读取页面快照",
    "browser_click" to "点击页面元素",
    "browser_type" to "填写页面输入",
    "browser_scroll" to "滚动页面",
    "browser_extract" to "抽取页面内容",
    "browser_wait" to "等待页面",
    "subagent" to "委派子智能体",
    "send_message" to "给子智能体发消息",
    "subagent_fork" to "继承上下文委派子智能体",
    "list_agents" to "列出子智能体",
    "skill" to "加载 Skill",
    "skill_save" to "保存 Skill",
    "shizuku" to "系统级操作",
    "todo_write" to "更新待办",
    "write_plan" to "提交计划",
    "goal" to "更新目标",
    "mem" to "记忆操作",
    "ask_user_question" to "向用户提问",
    "notify_user" to "通知用户",
    "read_image" to "查看图片",
    "generate_image" to "生成图片",
    "context_status" to "查看上下文水位",
    "screenshot" to "截取屏幕"
)

/**
 * 用一句话说明该工具执行了什么。
 *
 * 规则：动词 + 参数中的宾语（路径 / 命令 / 查询词 …），截断至 [limit]。
 * 无法识别的工具退化为「工具名 · 首参数」，至少不为空。
 */
fun toolSummary(name: String, arguments: String, limit: Int = TOOL_HEADLINE_LIMIT): String {
    val verb = TOOL_VERBS[name]
    val arg = pickArg(arguments)
    val head = verb ?: name
    val text = when {
        arg.isBlank() -> head
        verb == null -> head + " · " + arg
        else -> verb + " " + arg
    }
    return if (text.length > limit) text.take((limit - 1).coerceAtLeast(0)) + "…" else text
}

/**
 * 工具卡、轨迹行与 peer 回执共用的标题。
 *
 * 取值顺序与 DSH 的 `bash` 工具一致：**先取模型写的 `description`**（这一步在做什么），
 * 缺失时退回按工具名与参数推断的 [toolSummary]（读取 xxx、列出 xxx）。
 * 两处显示必须由同一个函数决定，否则同一调用在主页与轨迹页会说成两句话。
 *
 * [limit] 只作用于推断出的摘要；`description` 是模型原话，不做截断。
 * peer 回执传 [Int.MAX_VALUE]：统筹者需要看到完整路径与命令。
 */
fun toolHeadline(
    name: String,
    arguments: String,
    limit: Int = TOOL_HEADLINE_LIMIT
): String = stepDescription(arguments) ?: toolSummary(name, arguments, limit)

/**
 * 模型写入的这一步说明；缺失、非字符串或空白时返回 null。
 *
 * 新调用只写 [STEP_DESCRIPTION_PARAM]；[LEGACY_ACTION_SUMMARY_PARAM] 是为 2026-11-10 之前
 * 的会话保留的读取路径，两者同义，读的时候都认。
 */
fun stepDescription(arguments: String): String? {
    if (arguments.isBlank()) return null
    val obj = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
    // 只认字符串：缺键、null 与数组 / 对象都不当作说明，避免标题行出现「null」
    val raw = (obj.opt(STEP_DESCRIPTION_PARAM) as? String)
        ?: (obj.opt(LEGACY_ACTION_SUMMARY_PARAM) as? String)
        ?: return null
    return raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim().ifEmpty { null }
}

/** 参数中最可能充当宾语的值；取不到时将整串压为一行。 */
private fun pickArg(arguments: String): String {
    if (arguments.isBlank()) return ""
    val flat = arguments.trim().let { raw ->
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj == null) {
            raw
        } else {
            SUMMARY_ARG_KEYS.firstNotNullOfOrNull { key ->
                val value = obj.opt(key) ?: return@firstNotNullOfOrNull null
                if (value === JSONObject.NULL || value is JSONObject || value is org.json.JSONArray) {
                    return@firstNotNullOfOrNull null
                }
                value.toString().takeIf { it.isNotBlank() }
            } ?: ""
        }
    }.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
    return flat.trim()
}
