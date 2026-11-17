package com.zmf.agent.core.llm

import java.time.LocalDate

/**
 * 中国法定节假日表，只服务于一件事：峰谷计费的「工作日」判定。
 *
 * 官方口径（[com.zmf.agent.core.llm.CostBook.isPeak]）：高峰时段为北京时间周一至周五
 * 09:00–12:00、14:00–18:00，**不含法定节假日**；节假日全天按空闲价（半价）。
 *
 * 两条约定：
 * - **放假日**取自国务院办公厅的年度通知，节日连休按通知给的整段区间登记；
 * - **调休补班日**单列：那是被挪成工作日的周末，按工作日时段计费，不能当作周末。
 *
 * 表里没有的年份不做推测：判定退回「周末即空闲、其余按工作日时段」，宁可少算折扣，
 * 也不把一整年误判成空闲。每年国务院办公厅发通知后在这里加一年即可（版本发布时生效）。
 *
 * 已收录：2025 年（国办发明电〔2024〕7 号）、2026 年（国办发明电〔2025〕7 号）。
 */
object HolidayCalendar {

    /** 已收录的最新年份；界面用它说明覆盖范围。 */
    const val COVERED_UNTIL = 2026

    /** 放假日区间（含首尾），按年登记。 */
    private val HOLIDAY_RANGES: List<Pair<LocalDate, LocalDate>> = listOf(
        // 2025 年
        "2025-01-01" to "2025-01-01", // 元旦
        "2025-01-28" to "2025-02-04", // 春节
        "2025-04-04" to "2025-04-06", // 清明节
        "2025-05-01" to "2025-05-05", // 劳动节
        "2025-05-31" to "2025-06-02", // 端午节
        "2025-10-01" to "2025-10-08", // 国庆节与中秋节
        // 2026 年
        "2026-01-01" to "2026-01-03", // 元旦
        "2026-02-15" to "2026-02-23", // 春节
        "2026-04-04" to "2026-04-06", // 清明节
        "2026-05-01" to "2026-05-05", // 劳动节
        "2026-06-19" to "2026-06-21", // 端午节
        "2026-09-25" to "2026-09-27", // 中秋节
        "2026-10-01" to "2026-10-07" // 国庆节
    ).map { (start, end) -> LocalDate.parse(start) to LocalDate.parse(end) }

    /** 调休补班的周末：按通知原文单列，这些日子按工作日时段计费。 */
    private val MAKEUP_WORKDAYS: Set<LocalDate> = setOf(
        // 2025 年
        "2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11",
        // 2026 年
        "2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10"
    ).map { LocalDate.parse(it) }.toSet()

    /** 该日是否为法定节假日（含调休连休的整段区间）。 */
    fun isHoliday(date: LocalDate): Boolean =
        HOLIDAY_RANGES.any { (start, end) -> !date.isBefore(start) && !date.isAfter(end) }

    /** 该日是否为调休补班日：是周末但按工作日计。 */
    fun isMakeupWorkday(date: LocalDate): Boolean = date in MAKEUP_WORKDAYS

    /** 该年份是否在表内；不在时判定退回纯时段口径。 */
    fun covers(year: Int): Boolean =
        HOLIDAY_RANGES.any { (start, _) -> start.year == year } || MAKEUP_WORKDAYS.any { it.year == year }
}
