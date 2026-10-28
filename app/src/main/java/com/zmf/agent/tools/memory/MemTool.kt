package com.zmf.agent.tools.memory

import com.zmf.agent.agent.memory.MemoryKernel
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import org.json.JSONObject

/**
 * mem：记忆内核（mem-core）的唯一工具入口。
 *
 * 为什么只有一个工具，而不是「记忆写入 / 记忆检索 / 记忆维护」三个：
 * 内核本来就是一个 CLI 的九个子命令；拆成三个工具，模型得先猜「这句话算写入还是检索」，
 * 而子命令语义（以及 private/ask 的读不出、append-only 的不可改）由内核自己保证。
 * 一个工具 + 一个 command 枚举，等于把那份规则原样搬到工具描述里，不引入第二套判断。
 *
 * 为什么只做「必填项 / 枚举 / limit 夹取」这三类本地校验，其余交给内核：
 * 内核只认 CLI + --json；在 Kotlin 里再复制一遍参数语义，迟早会和内核 CLI 对不上。
 * 本地能明确说清的（缺 text、kind 用错命令、ns 给 status）先拦下来省一次往返；
 * 真正涉及数据/格式的（id 不存在、来源已巩固）一律让内核退出码 2 报，再原文转给模型。
 *
 * 权限档 READ_ONLY：库是 App 自己铺在工作区里的运行时资产（mingmem 包 + _Data/），
 * 用户文件一个都不碰；只读档下模型也该能回忆、能写入自己的长期记忆，不该被「写文件」拦住。
 */
class MemTool : Tool {

    override val name = "mem"

    override val description =
        "记忆内核（mem-core）的唯一入口：写入记忆、回忆检索、维护巩固、跨会话延续都走它，全部落到 append-only 的事件流上。" +
            "何时用：① 用户说「记住这个」「记一下」「把这条记下来」→ command=add，text 用用户原话，不要改写润色；" +
            "② 用户问「你还记得…吗」「回忆一下」「上次那个…」，或新会话要继续旧话题 → recall（返回结构化简报，不是片段堆砌），" +
            "只想看原始命中用 search；③ 维护 → 先 cycle（遗忘扫描 + 报出待巩固批次），读报告后用 consolidate 把同批 ids 合成语义卡，" +
            "旧记忆说错了用 correct 追加更正；④ export 导出给人看的视图，doctor 只读体检，status 看统计。" +
            "硬边界：_Data/events/*.jsonl 是唯一真相源且 append-only —— 只能通过本工具追加，任何情况下不得手改/覆盖/删除历史，" +
            "「遗忘」只体现为检索强度下降，改口只能用 correct 追加更正事件；private 级记忆永不进检索结果，" +
            "ask 级需要 --include-ask（本工具没开放这个开关，当前读不出来，不要假装记得）；" +
            "检索结果与衰减强度只认内核输出，不得自己估算分数、不得编造记忆内容或节点 id、不得声称「我记住了」除非写入成功；" +
            "失败（python3 缺失、库缺失、退出码 2）会显式返回原文，必须如实转告用户并退回「无记忆」模式，不得静默跳过或用样例顶替；" +
            "默认只写本项目命名空间（不传 ns 即 project），写跨项目层必须显式 ns=global；limit 上限 50。" +
            "返回 --json 结构化结果；若拿到的不是 JSON 会标注「非 JSON 输出」并原样给出。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "enum": ["add", "recall", "search", "status", "cycle", "correct", "consolidate", "export", "doctor"],
              "description": "子命令：add 写入｜recall 回忆（结构化简报）｜search 原始命中（调试）｜status 统计｜cycle 维护扫描｜correct 追加更正｜consolidate 巩固成语义卡｜export 导出视图｜doctor 只读体检"
            },
            "text": {
              "type": "string",
              "description": "正文。add / correct / consolidate 必填；写入必须是用户原话或已确认的事实，不确定处标注不确定，不得编造"
            },
            "query": {
              "type": "string",
              "description": "检索词。recall / search 必填；用关键词（空格分隔），不要写整句问题"
            },
            "kind": {
              "type": "string",
              "enum": ["episode", "note", "feel", "prefer", "decide", "semantic", "preference", "skill"],
              "description": "记忆类型。add 用 episode（经历，默认）/ note / feel / prefer / decide；consolidate 用 semantic（默认）/ preference / skill"
            },
            "tags": {
              "type": "string",
              "description": "标签，逗号分隔。add / consolidate 用"
            },
            "ids": {
              "type": "string",
              "description": "来源节点 id，逗号分隔。consolidate 必填，至少 2 条，通常是 cycle 报告里的待巩固批次"
            },
            "target": {
              "type": "string",
              "description": "被更正的节点 id。correct 必填"
            },
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 50,
              "description": "返回条数 1-50（默认 5）。只有 recall / search 生效"
            },
            "ns": {
              "type": "string",
              "enum": ["global", "project"],
              "description": "命名空间。project = 本项目私有（默认，不传即是）｜global = 跨项目层，写入跨项目层时必须显式传。status / doctor 没有这个参数"
            }
          },
          "required": ["command"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    /**
     * 读写一体：recall / search / status / export / doctor 只读，其余都会写记忆库。
     *
     * 只读档下只放行读的那几个：只读就是只读，不分 App 内部还是外部，
     * 所以记忆库写入也算越权。
     */
    override fun mutatesWith(args: JSONObject): Boolean =
        args.optString("command", "").trim().lowercase() in WRITE_COMMANDS

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val command = str(args, "command")
        if (command.isEmpty()) {
            return ToolResult.Error("缺 command：可选 " + COMMANDS.joinToString(" / ") + "（写入用 add，回忆用 recall，体检用 doctor）")
        }
        val argv = when (val built = plan(command, args)) {
            is Plan.Ok -> built.argv
            is Plan.Fail -> return ToolResult.Error(built.message)
        }
        // MemoryKernel.run 的失败信息里已经写明「卡在第几步 + 原文」，直接转给模型，
        // 不再包一层「工具执行失败」把关键信息挤掉（卡在哪一步、为什么、缺什么都要说清）。
        return MemoryKernel.run(ctx.context, ctx.workspaceRoot, argv).fold(
            onSuccess = { ToolResult.Ok(it) },
            onFailure = { error -> ToolResult.Error(error.message ?: ("记忆内核调用失败：" + error.javaClass.simpleName)) },
        )
    }

    // ---------------------------------------------------------------- 参数 → CLI

    /** 组参数的结果：要么拿到 argv，要么拿到一句给模型看的中文原因。 */
    private sealed interface Plan {
        data class Ok(val argv: List<String>) : Plan
        data class Fail(val message: String) : Plan
    }

    /**
     * 按子命令拼 CLI 参数。
     *
     * 一律用 --flag=value 的等号形式：正文以「-」开头（比如「-23」这种）时，
     * 分开写会被 argparse 当成另一个选项吃掉，等号形式不会有这个歧义。
     */
    private fun plan(command: String, args: JSONObject): Plan {
        if (command !in COMMANDS) {
            return Plan.Fail("不认识的 command：" + command + "（可选 " + COMMANDS.joinToString(" / ") + "）")
        }

        val text = str(args, "text")
        val query = str(args, "query")
        val kind = str(args, "kind")
        val tags = str(args, "tags")
        val ids = str(args, "ids")
        val target = str(args, "target")
        val ns = str(args, "ns")
        val limit = limitOf(args)

        if (ns.isNotEmpty() && ns != NS_PROJECT && ns != NS_GLOBAL) {
            return Plan.Fail("ns 只能是 " + NS_PROJECT + "（本项目，默认）或 " + NS_GLOBAL + "（跨项目层）；收到：" + ns)
        }
        if (ns == NS_GLOBAL && command !in NS_COMMANDS) {
            return Plan.Fail(command + " 没有命名空间参数，不能指定 ns（支持 ns 的子命令：" + NS_COMMANDS.joinToString(" / ") + "）")
        }
        // ns=project 时故意不传 --ns：接入层已经用 MINGMEM_NS 设好 project:<工作区目录名>，
        // 在 Kotlin 里再拼一遍命名规则，工作区改名或内核换命名约定时就会对不上。
        val nsArg: List<String> = if (ns == NS_GLOBAL) listOf("--ns=" + NS_GLOBAL) else emptyList()

        return when (command) {
            "add" -> {
                if (text.isEmpty()) {
                    return Plan.Fail("add 需要非空 text：要记住的那句话本身（用用户原话，不要自己润色）")
                }
                val addKind = kind.ifEmpty { "episode" }
                if (addKind !in ADD_KINDS) {
                    return Plan.Fail(
                        "add 的 kind 只能是 " + ADD_KINDS.joinToString(" / ") +
                            "；" + CONSOLIDATE_KINDS.joinToString(" / ") +
                            " 只能给 consolidate 用（巩固产物由内核合成，不能直接写）"
                    )
                }
                val argv = mutableListOf("add", "--kind=" + addKind, "--text=" + text)
                if (tags.isNotEmpty()) argv.add("--tags=" + tags)
                argv.addAll(nsArg)
                Plan.Ok(argv)
            }

            "recall", "search" -> {
                if (query.isEmpty()) {
                    return Plan.Fail(command + " 需要非空 query：给关键词（空格分隔），不要写整句话")
                }
                val argv = mutableListOf(command, "--query=" + query)
                if (limit != null) argv.add("--limit=" + limit)
                argv.addAll(nsArg)
                Plan.Ok(argv)
            }

            "correct" -> {
                if (target.isEmpty()) return Plan.Fail("correct 需要 target：被更正的节点 id（历史不改，只追加更正事件）")
                if (text.isEmpty()) return Plan.Fail("correct 需要非空 text：更正后的说法")
                val argv = mutableListOf("correct", "--target=" + target, "--text=" + text)
                argv.addAll(nsArg)
                Plan.Ok(argv)
            }

            "consolidate" -> {
                if (ids.isEmpty()) return Plan.Fail("consolidate 需要 ids：来源节点 id，逗号分隔（至少 2 条）")
                if (text.isEmpty()) return Plan.Fail("consolidate 需要非空 text：合成后的语义卡正文")
                val consolidateKind = kind.ifEmpty { "semantic" }
                if (consolidateKind !in CONSOLIDATE_KINDS) {
                    return Plan.Fail(
                        "consolidate 的 kind 只能是 " + CONSOLIDATE_KINDS.joinToString(" / ") +
                            "；收到：" + consolidateKind
                    )
                }
                val argv = mutableListOf(
                    "consolidate",
                    "--from=" + ids,
                    "--text=" + text,
                    "--kind=" + consolidateKind,
                )
                if (tags.isNotEmpty()) argv.add("--tags=" + tags)
                argv.addAll(nsArg)
                Plan.Ok(argv)
            }

            // cycle / export 只吃 --ns；status / doctor 连 --ns 都没有（硬塞会让 argparse 直接退出码 2）
            else -> {
                val argv = mutableListOf(command)
                argv.addAll(nsArg)
                Plan.Ok(argv)
            }
        }
    }

    /**
     * limit 只有 recall / search 有；内核上限 50、越界自己也会夹取。
     * 这里先夹一遍是为了「模型给 200 时不要靠内核的 stderr 警告才发现」，但那只是优化，
     * 数值范围语义仍以内核为准。
     */
    private fun limitOf(args: JSONObject): Int? {
        if (!args.has("limit") || args.isNull("limit")) return null
        return args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
    }

    /**
     * 读字符串参数：缺失、显式 null、字面 "null" 都算空。
     * org.json 的 optString 对 JSONObject.NULL 会返回字符串 "null"，不挡掉就会把「null」写进记忆里。
     */
    private fun str(args: JSONObject, key: String): String {
        if (!args.has(key) || args.isNull(key)) return ""
        val raw = args.optString(key, "").trim()
        return if (raw == "null") "" else raw
    }

    private companion object {
        /** 会写记忆库的 command：只读档下按能力拒绝（读的那些不在里面）。 */
        val WRITE_COMMANDS = setOf("add", "correct", "cycle", "consolidate")

        val COMMANDS = listOf(
            "add", "recall", "search", "status", "cycle", "correct", "consolidate", "export", "doctor"
        )

        /** 能直接 add 的类型；semantic/preference/skill 只出现在 consolidate 的 --kind 里。 */
        val ADD_KINDS = listOf("episode", "note", "feel", "prefer", "decide")

        val CONSOLIDATE_KINDS = listOf("semantic", "preference", "skill")

        /** 有 --ns 的子命令（status / doctor 没有，见 mingmem 的 argparse）。 */
        val NS_COMMANDS = listOf(
            "add", "recall", "search", "cycle", "correct", "consolidate", "export"
        )

        const val NS_GLOBAL = "global"
        const val NS_PROJECT = "project"

        /** 内核 MAX_LIMIT。 */
        const val MAX_LIMIT = 50

        /** 模型给了非数字 limit 时的兜底值，与内核 DEFAULT_LIMIT 一致。 */
        const val DEFAULT_LIMIT = 5
    }
}
