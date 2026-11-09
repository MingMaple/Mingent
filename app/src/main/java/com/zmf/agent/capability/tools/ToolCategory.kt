package com.zmf.agent.capability.tools

/** 过程组的分类计数：界面只读这里的映射，不自行解析工具名。 */
enum class ToolCategory(val label: String) {
    COMMAND("命令"),
    FILES("读写文件"),
    SEARCH("搜索"),
    WEB("网页"),
    AGENT("子智能体"),
    OTHER("其他")
}

/** 把工具名映射到过程组分类；未登记的工具归入 [ToolCategory.OTHER]。 */
fun toolCategoryOf(name: String): ToolCategory = when (name) {
    "terminal" -> ToolCategory.COMMAND
    "read_file", "write_file", "delete_file", "list_files", "present" -> ToolCategory.FILES
    "web_search" -> ToolCategory.SEARCH
    "web_fetch" -> ToolCategory.WEB
    "subagent", "send_message" -> ToolCategory.AGENT
    else -> ToolCategory.OTHER
}
