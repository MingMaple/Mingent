package com.zmf.agent.capability.tools.file

import com.zmf.agent.capability.tools.HighRiskGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * edit_file 的精确替换与保护区判定（纯逻辑，不依赖 Android 框架）。
 *
 * 约定：按原文逐字匹配；replace_all=false 时恰好 1 处才替换；
 * 换行符与 BOM 原样保留，不做归一化。
 */
class EditFileTest {

    @Test
    fun uniqueMatch_replacesOnce() {
        val result = applyTextEdit("hello world", "world", "Mingent", false) as TextEditResult.Ok
        assertEquals("hello Mingent", result.text)
        assertEquals(1, result.replacements)
        assertEquals(1, result.firstLine)
        assertEquals(1, result.oldLines)
        assertEquals(1, result.newLines)
    }

    @Test
    fun zeroMatch_returnsNotFound() {
        assertEquals(TextEditResult.NotFound, applyTextEdit("hello world", "absent", "x", false))
    }

    @Test
    fun multipleMatches_returnsAmbiguous() {
        val result = applyTextEdit("a a a", "a", "b", false) as TextEditResult.Ambiguous
        assertEquals(3, result.matches)
    }

    @Test
    fun replaceAll_replacesEveryOccurrence() {
        val result = applyTextEdit("a\na\na", "a", "b", true) as TextEditResult.Ok
        assertEquals("b\nb\nb", result.text)
        assertEquals(3, result.replacements)
        assertEquals(3, result.oldLines)
        assertEquals(3, result.newLines)
    }

    @Test
    fun emptyOldString_isRejected() {
        assertEquals(TextEditResult.EmptyOld, applyTextEdit("hello", "", "x", true))
    }

    @Test
    fun bomAndCrlf_arePreserved() {
        val original = "\uFEFF第一行\r\n第二行\r\n"
        val result = applyTextEdit(original, "第二行", "第二行（改）", false) as TextEditResult.Ok
        assertTrue(result.text.startsWith("\uFEFF"))
        assertTrue(result.text.contains("\r\n第二行（改）\r\n"))
        assertEquals(2, result.oldLines)
        assertEquals(2, result.newLines)
    }

    @Test
    fun protectedPath_isRejected() {
        val root = File(System.getProperty("java.io.tmpdir"), "mingent-edit-" + System.nanoTime())
        try {
            val appPrivate = File(root, "app")
            val filesDir = File(appPrivate, "files")
            val externalRoot = File(root, "sdcard")
            val externalFiles = File(appPrivate, "ext")
            val rootfs = File(root, "rootfs")
            listOf(filesDir, externalRoot, externalFiles, rootfs).forEach { it.mkdirs() }
            HighRiskGate.bind(
                HighRiskGate.SandboxEnv(
                    filesDir = filesDir,
                    appPrivateRoot = appPrivate,
                    externalRoot = externalRoot,
                    externalFilesDir = externalFiles,
                    rootfsDir = rootfs,
                    packageName = "com.test.app",
                    userId = 0
                )
            )
            val protected = File(filesDir, "shared_prefs/secret.xml")
            protected.parentFile?.mkdirs()
            protected.writeText("<map/>")
            assertNotNull(editProtectedReason(protected))

            val normal = File(filesDir, "workspace/notes.md")
            normal.parentFile?.mkdirs()
            normal.writeText("ok")
            assertNull(editProtectedReason(normal))
        } finally {
            root.deleteRecursively()
        }
    }
}
