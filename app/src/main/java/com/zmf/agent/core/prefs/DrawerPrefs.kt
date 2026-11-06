package com.zmf.agent.core.prefs

import android.content.Context

/** 抽屉里已归档会话的可见档位，语义与 DSH 的 hide／show／only 一致。 */
enum class ArchivedFilter { HIDE, SHOW, ONLY }

/**
 * 抽屉里各工作区分组的展开状态，以及已归档会话的可见档位。
 *
 * 只记录用户亲手点开或收起过的分组：没有记录的分组沿用界面默认值（仅承载当前会话的分组展开），
 * 因此「未分类」与新建的分组不需要额外的初始化步骤。
 *
 * 键是分组 id（未分类用空串），与工作区名称无关：改名或移动会话都不会影响它。
 * 筛选档位使用独立键，不与 `open_` 前缀的展开状态混用。
 */
object DrawerPrefs {

    private const val PREFS_NAME = "drawer_prefs"
    private const val KEY_PREFIX = "open_"
    private const val KEY_ARCHIVED_FILTER = "archived_filter"

    /** 读取全部已记录的展开状态：键为分组 id，未分类是空串。 */
    fun load(context: Context): Map<String, Boolean> {
        val result = HashMap<String, Boolean>()
        for ((key, value) in prefs(context).all) {
            if (!key.startsWith(KEY_PREFIX)) continue
            (value as? Boolean)?.let { result[key.removePrefix(KEY_PREFIX)] = it }
        }
        return result
    }

    /** 记录一个分组的展开状态。 */
    fun save(context: Context, groupKey: String, open: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX + groupKey, open).apply()
    }

    /** 分组消失（工作区被删除）后清掉记录，避免留下永远读不到的数据。 */
    fun forget(context: Context, groupKey: String) {
        prefs(context).edit().remove(KEY_PREFIX + groupKey).apply()
    }

    /** 读取已归档会话的可见档位；未记录时按 DSH 默认隐藏已归档。 */
    fun loadArchivedFilter(context: Context): ArchivedFilter {
        val stored = prefs(context).getString(KEY_ARCHIVED_FILTER, null) ?: return ArchivedFilter.HIDE
        return runCatching { ArchivedFilter.valueOf(stored) }.getOrDefault(ArchivedFilter.HIDE)
    }

    /** 记录已归档会话的可见档位。 */
    fun saveArchivedFilter(context: Context, filter: ArchivedFilter) {
        prefs(context).edit().putString(KEY_ARCHIVED_FILTER, filter.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
