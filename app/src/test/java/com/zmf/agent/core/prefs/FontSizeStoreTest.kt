package com.zmf.agent.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/** 字号偏好的纯逻辑：旧档迁移与越界夹取。 */
class FontSizeStoreTest {

    @Test
    fun legacyScaleMapsToBaseSp() {
        // 四档旧值：floor(14 × 倍率) 后夹取到 12–16
        assertEquals(12, FontSizeStore.migrate(0.85f))
        assertEquals(14, FontSizeStore.migrate(1.0f))
        assertEquals(16, FontSizeStore.migrate(1.15f))
        assertEquals(16, FontSizeStore.migrate(1.3f))
    }

    @Test
    fun legacyScaleOutsideStepsUsesSameFormula() {
        // 手工改成非四档时不做特殊分支：floor(14 × 1.07) = 14
        assertEquals(14, FontSizeStore.migrate(1.07f))
        assertEquals(12, FontSizeStore.migrate(0.5f))
        assertEquals(16, FontSizeStore.migrate(3.0f))
    }

    @Test
    fun nonFiniteLegacyScaleFallsBackToDefault() {
        assertEquals(FontSizeStore.DEFAULT_SP, FontSizeStore.migrate(Float.NaN))
        assertEquals(FontSizeStore.DEFAULT_SP, FontSizeStore.migrate(Float.POSITIVE_INFINITY))
    }

    @Test
    fun storedValueIsRoundedAndClamped() {
        assertEquals(14, FontSizeStore.normalize(14.4f))
        assertEquals(15, FontSizeStore.normalize(14.6f))
        assertEquals(FontSizeStore.MIN_SP, FontSizeStore.normalize(1.07f))
        assertEquals(FontSizeStore.MAX_SP, FontSizeStore.normalize(30f))
        assertEquals(FontSizeStore.DEFAULT_SP, FontSizeStore.normalize(Float.NaN))
    }

    @Test
    fun fontScaleUsesDesignBase() {
        assertEquals(1f, FontSizeStore.fontScaleOf(14), 0.0001f)
        assertEquals(12f / 14f, FontSizeStore.fontScaleOf(12), 0.0001f)
        assertEquals(16f / 14f, FontSizeStore.fontScaleOf(16), 0.0001f)
    }
}
