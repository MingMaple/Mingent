package com.zmf.agent.capability.tools.shell.support

import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.core.model.PermissionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 终端默认工作目录的工作区级派生（纯逻辑，不依赖 Android）。
 *
 * 钉住四条判据：可映射的工作区落在工作区根；不可映射或已失效回落 `/root`；
 * 显式 `workdir` 优先于派生值；越界的显式参数仍然拒绝。
 */
class SessionCwdTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 假环境：App 外部目录位于共享存储根之下，工作区放在该目录里才能映射成 `/sdcard/...`。 */
    private fun env(): HighRiskGate.SandboxEnv {
        val files = tmp.newFolder("files")
        val externalRoot = tmp.newFolder("storage")
        val externalFiles = File(externalRoot, "Android/data/com.test/files").apply { mkdirs() }
        val rootfs = tmp.newFolder("rootfs")
        return HighRiskGate.SandboxEnv(
            filesDir = files,
            appPrivateRoot = files.parentFile ?: files,
            externalRoot = externalRoot,
            externalFilesDir = externalFiles,
            rootfsDir = rootfs,
            packageName = "com.test",
            userId = 0
        )
    }

    /** 工作区根等于映射后的工作区目录（真实设备上的 `externalFilesDir/workspace`）。 */
    private fun mappedWorkspace(env: HighRiskGate.SandboxEnv): File =
        File(env.externalFilesDir, "workspace").apply { mkdirs() }

    @Test
    fun mappedWorkspaceDerivesToWorkspaceRoot() {
        val env = env()
        val workspace = mappedWorkspace(env)
        assertEquals(
            "/sdcard/Android/data/com.test/files/workspace",
            SessionCwd.workspaceTerminalGuest(env, workspace, "s1")
        )
        assertEquals(
            "/sdcard/Android/data/com.test/files/workspace",
            SessionCwd.resolveGuest(env, PermissionLevel.WORKSPACE, workspace, "s1", "")
        )
    }

    @Test
    fun unmappedOrMissingWorkspaceFallsBackToRoot() {
        val env = env()
        // 物理 SD 卡等共享存储根之外的位置：不在 bind 表内
        val unmapped = File(tmp.root, "sdcard-card/workspace").apply { mkdirs() }
        assertEquals(SessionCwd.DEFAULT_GUEST, SessionCwd.workspaceTerminalGuest(env, unmapped, "s1"))
        // 工作区在运行中被移动或删除：目录不存在同样回落，且不信任任何缓存
        val gone = File(tmp.root, "gone/workspace")
        assertEquals(SessionCwd.DEFAULT_GUEST, SessionCwd.workspaceTerminalGuest(env, gone, "s1"))
    }

    @Test
    fun explicitWorkdirWinsOverDerived() {
        val env = env()
        val workspace = mappedWorkspace(env)
        val sub = "/sdcard/Android/data/com.test/files/workspace/sub"
        assertEquals(sub, SessionCwd.resolveGuest(env, PermissionLevel.WORKSPACE, workspace, "s1", sub))
        // 完全档不做工作区映射，guest 绝对路径原样使用
        assertEquals("/tmp", SessionCwd.resolveGuest(env, PermissionLevel.FULL, workspace, "s1", "/tmp"))
    }

    @Test
    fun outsideExplicitWorkdirIsStillRejected() {
        val env = env()
        val workspace = mappedWorkspace(env)
        assertNull(SessionCwd.resolveGuest(env, PermissionLevel.WORKSPACE, workspace, "s1", "../../.."))
        assertNull(SessionCwd.resolveGuest(env, PermissionLevel.WORKSPACE, workspace, "s1", "/../../.."))
        // 非完全档下，映射回宿主后落在工作区外的 guest 绝对路径同样拒绝
        assertNull(SessionCwd.resolveGuest(env, PermissionLevel.WORKSPACE, workspace, "s1", "/sdcard/proj"))
        assertTrue(SessionCwd.ERR_OUTSIDE.contains("E_CWD_OUTSIDE"))
    }
}
