package com.zmf.agent.capability.tools.plugin

import com.zmf.agent.capability.port.ScreenCapture
import com.zmf.agent.capability.port.ToolEnv
import com.zmf.agent.capability.port.UserNotifier
import com.zmf.agent.capability.port.WebControl
import com.zmf.agent.capability.prompt.PromptSection
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.agent.list_agents.ListAgentsTool
import com.zmf.agent.capability.tools.agent.send_message.SendMessageTool
import com.zmf.agent.capability.tools.agent.subagent.TaskTool
import com.zmf.agent.capability.tools.agent.subagent_fork.SubAgentForkTool
import com.zmf.agent.capability.tools.browser.browser_click.BrowserClickTool
import com.zmf.agent.capability.tools.browser.browser_extract.BrowserExtractTool
import com.zmf.agent.capability.tools.browser.browser_open.BrowserOpenTool
import com.zmf.agent.capability.tools.browser.browser_scroll.BrowserScrollTool
import com.zmf.agent.capability.tools.browser.browser_snapshot.BrowserSnapshotTool
import com.zmf.agent.capability.tools.browser.browser_type.BrowserTypeTool
import com.zmf.agent.capability.tools.browser.browser_wait.BrowserWaitTool
import com.zmf.agent.capability.tools.capture.screenshot.ScreenshotTool
import com.zmf.agent.capability.tools.context.context_status.ContextStatusTool
import com.zmf.agent.capability.tools.file.apply_patch.ApplyPatchTool
import com.zmf.agent.capability.tools.file.delete_file.DeleteFileTool
import com.zmf.agent.capability.tools.file.edit_file.EditFileTool
import com.zmf.agent.capability.tools.file.glob.GlobTool
import com.zmf.agent.capability.tools.file.grep.GrepTool
import com.zmf.agent.capability.tools.file.present.PresentTool
import com.zmf.agent.capability.tools.file.read_file.ReadFileTool
import com.zmf.agent.capability.tools.file.write_file.WriteFileTool
import com.zmf.agent.capability.tools.goal.goal.GoalTool
import com.zmf.agent.capability.tools.interaction.ask_user_question.AskUserQuestionTool
import com.zmf.agent.capability.tools.interaction.notify_user.NotifyUserTool
import com.zmf.agent.capability.tools.media.generate_image.GenerateImageTool
import com.zmf.agent.capability.tools.media.read_image.ReadImageTool
import com.zmf.agent.capability.tools.memory.mem.MemTool
import com.zmf.agent.capability.tools.plan.write_plan.PlanTool
import com.zmf.agent.capability.tools.schedule.scheduled_task_create.ScheduleCreateTool
import com.zmf.agent.capability.tools.schedule.scheduled_task_delete.ScheduleDeleteTool
import com.zmf.agent.capability.tools.schedule.scheduled_task_edit.ScheduleEditTool
import com.zmf.agent.capability.tools.schedule.scheduled_task_view.ScheduleViewTool
import com.zmf.agent.capability.tools.session.peer.PeerTool
import com.zmf.agent.capability.tools.session.session_archive.SessionArchiveTool
import com.zmf.agent.capability.tools.session.session_create.SessionCreateTool
import com.zmf.agent.capability.tools.session.session_delete.SessionDeleteTool
import com.zmf.agent.capability.tools.session.session_list.SessionListTool
import com.zmf.agent.capability.tools.session.session_move.SessionMoveTool
import com.zmf.agent.capability.tools.session.session_rename.SessionRenameTool
import com.zmf.agent.capability.tools.session.session_search.SessionSearchTool
import com.zmf.agent.capability.tools.session.session_update_settings.SessionUpdateSettingsTool
import com.zmf.agent.capability.tools.shell.job_kill.JobKillTool
import com.zmf.agent.capability.tools.shell.job_list.JobListTool
import com.zmf.agent.capability.tools.shell.job_output.JobOutputTool
import com.zmf.agent.capability.tools.shell.job_start.JobStartTool
import com.zmf.agent.capability.tools.shell.terminal.TerminalTool
import com.zmf.agent.capability.tools.skill.skill.SkillTool
import com.zmf.agent.capability.tools.skill.skill_save.SkillSaveTool
import com.zmf.agent.capability.tools.system.shizuku.ShizukuTool
import com.zmf.agent.capability.tools.task.todo_write.TodoTool
import com.zmf.agent.capability.tools.web.web_fetch.WebTool
import com.zmf.agent.capability.tools.web.web_search.WebSearchTool
import com.zmf.agent.capability.tools.workspace.workspace_create.WorkspaceCreateTool
import com.zmf.agent.capability.tools.workspace.workspace_delete.WorkspaceDeleteTool
import com.zmf.agent.capability.tools.workspace.workspace_list.WorkspaceListTool
import com.zmf.agent.capability.tools.workspace.workspace_rename.WorkspaceRenameTool
import com.zmf.agent.capability.tools.workspace.workspace_set_root.WorkspaceSetRootTool
import com.zmf.agent.core.log.FileLog

/**
 * 内置工具插件清单。
 *
 * 注册单位是单个工具：一个工具即一个插件，`id` 与 [Tool.name] 相同。[group] 只用于文档
 * 与界面分组，不参与注册，也不产生组级依赖；[ToolPlugin.requires] 只约束本工具。
 *
 * [all] 按工具名排序，提示词片段按此顺序编号：请求前缀因此逐字节稳定。
 */
object ToolPlugins {

    private const val TAG = "tools"

    /** 全部内置工具插件，按工具名排序。 */
    val all: List<ToolPlugin> = listOf(
        ToolPlugin(
            ApplyPatchTool(),
            group = "file",
            prompt = "多文件或多块协同修改使用 apply_patch。"
        ),
        ToolPlugin(
            AskUserQuestionTool(),
            group = "interaction",
            prompt = "存在歧义且无法从工作区或历史中推断时，使用 ask_user_question 提出结构化问题；" +
                "回答到达后继续执行，不得重复提问。"
        ),
        ToolPlugin(BrowserClickTool(), group = "browser", requires = setOf(WebControl::class)),
        ToolPlugin(BrowserExtractTool(), group = "browser", requires = setOf(WebControl::class)),
        ToolPlugin(
            BrowserOpenTool(),
            group = "browser",
            requires = setOf(WebControl::class),
            prompt = "操作网页时先使用 browser_open 打开地址，再以 browser_snapshot 取得可交互节点与 ref；" +
                "点击与输入只使用 ref，页面导航后必须重新获取快照。助手操作期间该标签只读，" +
                "用户仍可上下滚动；用户终止后当次操作返回固定文案，不得立即重试同一操作。"
        ),
        ToolPlugin(BrowserScrollTool(), group = "browser", requires = setOf(WebControl::class)),
        ToolPlugin(BrowserSnapshotTool(), group = "browser", requires = setOf(WebControl::class)),
        ToolPlugin(BrowserTypeTool(), group = "browser", requires = setOf(WebControl::class)),
        ToolPlugin(BrowserWaitTool(), group = "browser", requires = setOf(WebControl::class)),
        ToolPlugin(ContextStatusTool(), group = "context"),
        ToolPlugin(DeleteFileTool(), group = "file"),
        ToolPlugin(
            EditFileTool(),
            group = "file",
            prompt = "修改已有文件前先读取。单处精确替换使用 edit_file。"
        ),
        ToolPlugin(
            GenerateImageTool(),
            group = "media",
            prompt = "生成图片使用 generate_image，调用前先确定 output_path；产出后必须调用 present 声明该文件。" +
                "未配置图像模型时应说明原因，不得反复重试。"
        ),
        ToolPlugin(
            GlobTool(),
            group = "file",
            prompt = "路径不确定时先用 glob 或 grep 定位，不得猜测。"
        ),
        ToolPlugin(GoalTool(), group = "goal"),
        ToolPlugin(GrepTool(), group = "file"),
        ToolPlugin(JobKillTool(), group = "shell"),
        ToolPlugin(JobListTool(), group = "shell"),
        ToolPlugin(JobOutputTool(), group = "shell"),
        ToolPlugin(
            JobStartTool(),
            group = "shell",
            prompt = "长命令使用 job_start 转为后台任务，再以 job_output 读取输出、以 job_kill 终止；" +
                "不得以轮询方式反复读取同一后台任务。"
        ),
        ToolPlugin(ListAgentsTool(), group = "agent"),
        ToolPlugin(MemTool(), group = "memory"),
        ToolPlugin(
            NotifyUserTool(),
            group = "interaction",
            requires = setOf(UserNotifier::class),
            prompt = "需要用户在稍后处理某事项时使用 notify_user，并在正文中说明事项与下一步。"
        ),
        ToolPlugin(PeerTool(), group = "session"),
        ToolPlugin(PresentTool(), group = "file"),
        ToolPlugin(ReadFileTool(), group = "file"),
        ToolPlugin(
            ReadImageTool(),
            group = "media",
            prompt = "查看图片使用 read_image，图片内容直接交给模型，不使用文字转述。"
        ),
        ToolPlugin(ScheduleCreateTool(), group = "schedule"),
        ToolPlugin(ScheduleDeleteTool(), group = "schedule"),
        ToolPlugin(ScheduleEditTool(), group = "schedule"),
        ToolPlugin(ScheduleViewTool(), group = "schedule"),
        ToolPlugin(SendMessageTool(), group = "agent"),
        ToolPlugin(SessionArchiveTool(), group = "session"),
        ToolPlugin(SessionCreateTool(), group = "session"),
        ToolPlugin(SessionDeleteTool(), group = "session"),
        ToolPlugin(SessionListTool(), group = "session"),
        ToolPlugin(SessionMoveTool(), group = "session"),
        ToolPlugin(SessionRenameTool(), group = "session"),
        ToolPlugin(SessionSearchTool(), group = "session"),
        ToolPlugin(SessionUpdateSettingsTool(), group = "session"),
        ToolPlugin(ShizukuTool(), group = "system"),
        ToolPlugin(
            SkillTool(),
            group = "skill",
            prompt = "技能正文只在需要时加载；加载后按其步骤执行，不得自行改写技能内容。" +
                "技能脚本产出的文件与其他工具产出同等对待，可直接交付。"
        ),
        ToolPlugin(SkillSaveTool(), group = "skill"),
        ToolPlugin(
            ScreenshotTool(),
            group = "capture",
            requires = setOf(ScreenCapture::class),
            prompt = "截屏使用 screenshot，调用前先确定 output_path；截取本应用窗口使用 source=self，不需要申请权限；" +
                "截取其他应用必须使用 source=foreground_app，并要求已启用无障碍服务且目标应用位于前台。" +
                "截屏产出后必须调用 present 声明该文件，不得用于屏幕点击。"
        ),
        ToolPlugin(
            TaskTool(),
            group = "agent",
            prompt = "子智能体拥有独立上下文，只回传结论。需要交叉验证或并行调查时使用 subagent；" +
                "不得把同一任务重复派发给多个子智能体。"
        ),
        ToolPlugin(SubAgentForkTool(), group = "agent"),
        ToolPlugin(TerminalTool(), group = "shell"),
        ToolPlugin(TodoTool(), group = "task"),
        ToolPlugin(WebTool(), group = "web"),
        ToolPlugin(WebSearchTool(), group = "web"),
        ToolPlugin(WorkspaceCreateTool(), group = "workspace"),
        ToolPlugin(WorkspaceDeleteTool(), group = "workspace"),
        ToolPlugin(WorkspaceListTool(), group = "workspace"),
        ToolPlugin(WorkspaceRenameTool(), group = "workspace"),
        ToolPlugin(WorkspaceSetRootTool(), group = "workspace"),
        ToolPlugin(
            WriteFileTool(),
            group = "file",
            prompt = "新建文件或整文件重写使用 write_file。"
        ),
        ToolPlugin(PlanTool(), group = "plan")
    ).sortedBy { it.id }

    /**
     * 依赖齐备的插件。
     *
     * 逐条校验 [ToolPlugin.requires]：缺失服务时只跳过该工具，同组其他工具照常注册。
     */
    fun available(): List<ToolPlugin> =
        all.filter { ToolEnv.verify(it.id, it.requires).isEmpty() }

    /** 已装配的工具本体。 */
    fun tools(): List<Tool> = available().map { it.tool }

    /** 提示词片段；序号按 [all] 下标分配，与装配结果无关。 */
    fun promptSections(): List<PromptSection> =
        all.mapIndexedNotNull { index, plugin -> plugin.promptSection(index) }

    /** 启动日志：逐工具一行；依赖缺失时补记一条缺失详情。 */
    fun logSummary() {
        for (plugin in all) {
            val missing = ToolEnv.services().missing(plugin.requires)
            FileLog.log(
                TAG,
                "工具「" + plugin.id + "」（分组 " + plugin.group + "）：" +
                    (if (missing.isEmpty()) "已装配" else "未装配")
            )
            if (missing.isNotEmpty()) {
                FileLog.log(
                    TAG,
                    "工具「" + plugin.id + "」缺少服务：" + missing.joinToString(", ") + "，仅跳过该工具"
                )
            }
        }
    }
}
