package com.zmf.agent.capability.tools.file.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 补丁解析器的格式约定（纯逻辑，不依赖 Android）。
 *
 * 约定：`@@` 开启一个块，`-` 属于旧内容，`+` 属于新内容，空格开头属于上下文；
 * 同一文件可出现多个块，块内行序即匹配顺序。
 */
class PatchParserTest {

    @Test
    fun singleFileReplacement() {
        val plan = PatchParser.parse(
            """
            *** Begin Patch
            *** Update File: src/a.kt
            @@
            -old line
            +new line
            *** End Patch
            """.trimIndent()
        )

        assertEquals(1, plan.files.size)
        val file = plan.files[0]
        assertEquals("src/a.kt", file.path)
        assertEquals(PatchFileKind.UPDATE, file.kind)
        assertEquals(listOf(Hunk(listOf("old line"), listOf("new line"))), file.hunks)
    }

    @Test
    fun multipleFilesAreParsedInOrder() {
        val plan = PatchParser.parse(
            """
            *** Begin Patch
            *** Update File: src/a.kt
            @@
            -a
            +b
            *** Add File: notes/b.md
            +content
            *** Delete File: old/c.txt
            *** End Patch
            """.trimIndent()
        )

        assertEquals(3, plan.files.size)
        assertEquals("src/a.kt", plan.files[0].path)
        assertEquals(PatchFileKind.UPDATE, plan.files[0].kind)
        assertEquals("notes/b.md", plan.files[1].path)
        assertEquals(PatchFileKind.ADD, plan.files[1].kind)
        assertEquals("old/c.txt", plan.files[2].path)
        assertEquals(PatchFileKind.DELETE, plan.files[2].kind)
        assertTrue(plan.files[2].hunks.isEmpty())
    }

    @Test
    fun addFileKeepsContentLines() {
        val plan = PatchParser.parse(
            """
            *** Begin Patch
            *** Add File: notes/b.md
            +first
            +second
            *** End Patch
            """.trimIndent()
        )

        val file = plan.files.single()
        assertEquals(PatchFileKind.ADD, file.kind)
        assertEquals(listOf(Hunk(emptyList(), listOf("first", "second"))), file.hunks)
    }

    @Test
    fun deleteFileHasNoHunks() {
        val plan = PatchParser.parse(
            """
            *** Begin Patch
            *** Delete File: old/c.txt
            *** End Patch
            """.trimIndent()
        )

        val file = plan.files.single()
        assertEquals(PatchFileKind.DELETE, file.kind)
        assertTrue(file.hunks.isEmpty())
    }

    @Test
    fun hunksKeepContextOrderAndRepetition() {
        val plan = PatchParser.parse(
            """
            *** Begin Patch
            *** Update File: a.txt
            @@
             alpha
            -one
            +ONE
            @@ 第二块
             beta
            -two
            +TWO
            *** End Patch
            """.trimIndent()
        )

        val hunks = plan.files.single().hunks
        assertEquals(2, hunks.size)
        assertEquals(Hunk(listOf("alpha", "one"), listOf("alpha", "ONE")), hunks[0])
        assertEquals(Hunk(listOf("beta", "two"), listOf("beta", "TWO")), hunks[1])
    }

    @Test
    fun contentBeforeHunkReportsLineNumber() {
        try {
            PatchParser.parse(
                """
                *** Begin Patch
                *** Update File: a.kt
                -not in hunk
                *** End Patch
                """.trimIndent()
            )
            fail("应当抛出 PatchFormatException")
        } catch (e: PatchFormatException) {
            assertTrue(e.message!!.contains("第 3 行"))
        }
    }

    @Test
    fun missingEndPatchReportsLineNumber() {
        try {
            PatchParser.parse(
                """
                *** Begin Patch
                *** Add File: a.txt
                +x
                """.trimIndent()
            )
            fail("应当抛出 PatchFormatException")
        } catch (e: PatchFormatException) {
            assertTrue(e.message!!.contains("第 4 行"))
        }
    }
}
