package com.zmf.agent.capability.tools

/** 过程组的分类计数：界面只读这里的映射，不自行解析工具名。 */
enum class ToolCategory(val label: String) {
    COMMAND("命令"),
    FILES("读写文件"),
    SEARCH("搜索"),
    WEB("网页"),
    BROWSER("浏览网页"),
    SESSION("会话"),
    WORKSPACE("工作区"),
    SCHEDULE("定时任务"),
    AGENT("子智能体"),
    INTERACTION("交互"),
    MEDIA("图像"),
    CONTEXT("上下文"),
    CAPTURE("截屏"),
    OTHER("其他")
}

/** 把工具名映射到过程组分类；未登记的工具归入 [ToolCategory.OTHER]。 */
fun toolCategoryOf(name: String): ToolCategory = when (name) {
    "terminal", "job_start", "job_output", "job_list", "job_kill" -> ToolCategory.COMMAND
    "read_file", "write_file", "edit_file", "delete_file", "present", "apply_patch" -> ToolCategory.FILES
    "glob", "grep", "web_search" -> ToolCategory.SEARCH
    "web_fetch" -> ToolCategory.WEB
    "browser_open", "browser_snapshot", "browser_click", "browser_type",
    "browser_scroll", "browser_extract", "browser_wait" -> ToolCategory.BROWSER
    "session_list", "session_create", "session_rename", "session_move", "session_archive",
    "session_delete", "session_update_settings", "session_search", "peer" -> ToolCategory.SESSION
    "workspace_list", "workspace_create", "workspace_rename", "workspace_set_root",
    "workspace_delete" -> ToolCategory.WORKSPACE
    "scheduled_task_create", "scheduled_task_view", "scheduled_task_edit", "scheduled_task_delete" ->
        ToolCategory.SCHEDULE
    "subagent", "send_message", "subagent_fork", "list_agents" -> ToolCategory.AGENT
    "ask_user_question", "notify_user" -> ToolCategory.INTERACTION
    "read_image", "generate_image" -> ToolCategory.MEDIA
    "context_status" -> ToolCategory.CONTEXT
    "screenshot" -> ToolCategory.CAPTURE
    "todo_write", "write_plan", "goal", "mem", "skill", "skill_save", "shizuku" -> ToolCategory.OTHER
    else -> ToolCategory.OTHER
}
