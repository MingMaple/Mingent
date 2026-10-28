package com.zmf.agent.core.capability

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import com.zmf.agent.core.log.FileLog
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService

/**
 * Shizuku 通道：借 shell(uid 2000) 的身份执行 adb 级命令。
 *
 * 只用它做「一键授权 + 少量系统操作」，不做多执行器那套体系。
 *
 * 三条硬规矩：
 * 1. **动态生效**：binder 到手/丢失、权限授予回调都会刷新 [state]，不需要重启 App；
 * 2. **所有 Shizuku.* 调用都必须包 runCatching**：Shizuku 没装/没启动时这些静态方法会抛
 *    IllegalStateException 或 SecurityException，崩一次用户就再也不敢开；
 * 3. 状态判断顺序是「装没装 → 服务活没活 → 有没有授权」，顺序错了会把「没装」报成「没授权」。
 */
object ShizukuBridge {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class State {
        /** 手机上没装 Shizuku（或 Sui）。 */
        NOT_INSTALLED,

        /** 装了但服务没起来（用户没启动 Shizuku / 没开无线调试授权）。 */
        NOT_RUNNING,

        /** 服务活着，但本 App 没被授权。 */
        NO_PERMISSION,

        /** 可用。 */
        READY
    }

    /** 一次 shell 执行的结果：ok = 退出码为 0，output 是 stdout 与 stderr 合并后的文本。 */
    data class ExecResult(val ok: Boolean, val output: String, val exitCode: Int)

    private val _state = MutableStateFlow(State.NOT_INSTALLED)

    /** 当前可用状态；binder 与授权变化时自动刷新，界面可直接收集。 */
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var listenersRegistered = false

    /** 权限授予结果回调（界面要刷新状态时挂一个）。 */
    @Volatile var onPermissionResult: ((granted: Boolean) -> Unit)? = null

    // 必须持有引用，否则被 GC 后监听失效（Shizuku 内部弱引用不保证）
    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        refresh()
        onPermissionResult?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
        FileLog.log("shizuku", "授权结果：" + if (grantResult == PackageManager.PERMISSION_GRANTED) "已授予" else "被拒绝")
    }

    /** App 启动时调一次；重复调用无副作用。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!listenersRegistered) {
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(binderReceived)
                Shizuku.addBinderDeadListener(binderDead)
                Shizuku.addRequestPermissionResultListener(permissionResult)
                listenersRegistered = true
            }.onFailure { FileLog.log("shizuku", "注册监听失败：" + (it.message ?: it.javaClass.simpleName)) }
        }
        refresh()
    }

    /** 重新判定状态（界面每次进前台都可以调）。 */
    fun refresh() {
        val prev = _state.value
        val next = computeState()
        if (next != prev) {
            _state.value = next
            FileLog.log("shizuku", "状态 " + prev.name + " → " + next.name)
        }
    }

    private fun computeState(): State {
        val ctx = appContext ?: return State.NOT_INSTALLED
        if (!isInstalled(ctx)) return State.NOT_INSTALLED
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return State.NOT_RUNNING
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        return if (granted) State.READY else State.NO_PERMISSION
    }

    /** 手机上是否装了 Shizuku（Sui 同包名，也算装了）。 */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 通道当前是否可用（状态为 [State.READY]）。 */
    fun isReady(): Boolean = _state.value == State.READY

    /** 申请授权；只能从 Activity 里调（Shizuku 会自己弹框）。 */
    fun requestPermission(): Boolean = runCatching {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } else {
            refresh()
            false
        }
    }.getOrElse {
        FileLog.log("shizuku", "申请授权失败：" + (it.message ?: it.javaClass.simpleName))
        false
    }

    /**
     * 用 Shizuku 的 shell 跑一条命令。
     *
     * 注意：stdout/stderr 必须并发读，只读一路会在大输出时把进程管道写满、waitFor 永远不返回。
     */
    suspend fun exec(command: String, timeoutMs: Long = 20_000): ExecResult = withContext(Dispatchers.IO) {
        if (_state.value != State.READY) {
            return@withContext ExecResult(false, "Shizuku 不可用（" + _state.value.name + "）", -1)
        }
        runCatching {
            // Shizuku 13 起 Shizuku.newProcess 是 private，只能走 binder 上的 IShizukuService.newProcess
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)
            val outStream = runCatching { ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream) }.getOrNull()
            val errStream = runCatching { ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream) }.getOrNull()
            val outText = StringBuilder()
            val errText = StringBuilder()
            val outThread = Thread {
                runCatching { outStream?.bufferedReader()?.forEachLine { outText.appendLine(it) } }
            }
            val errThread = Thread {
                runCatching { errStream?.bufferedReader()?.forEachLine { errText.appendLine(it) } }
            }
            outThread.start()
            errThread.start()
            // waitFor() 本身没有超时：放到独立线程上 join，超时就 destroy，别把 IO 线程挂死
            val waiter = Thread { runCatching { remote.waitFor() } }
            waiter.start()
            waiter.join(timeoutMs)
            val timedOut = waiter.isAlive
            if (timedOut) {
                runCatching { remote.destroy() }
                waiter.join(500)
            }
            outThread.join(1500)
            errThread.join(1500)
            val exit = runCatching { remote.exitValue() }.getOrDefault(-1)
            val merged = (outText.toString() + errText.toString()).trim()
            FileLog.log("shizuku", "exec(" + exit + ") " + command.take(160))
            if (timedOut) {
                ExecResult(false, "命令超时（" + timeoutMs + "ms）：" + command + (if (merged.isEmpty()) "" else "\n" + merged), -1)
            } else {
                ExecResult(exit == 0, merged, exit)
            }
        }.getOrElse {
            ExecResult(false, "Shizuku 执行失败：" + (it.message ?: it.javaClass.simpleName), -1)
        }
    }

    // ---- 常用的一键授权 / 系统操作 ----

    /** 授予本 App 一条运行时权限（shell 身份 pm grant，不需要弹框）。 */
    suspend fun grantRuntimePermission(permission: String): ExecResult {
        val pkg = appContext?.packageName ?: return ExecResult(false, "没有 Context", -1)
        return exec("pm grant " + pkg + " " + permission)
    }

    /** 撤销运行时权限。 */
    suspend fun revokeRuntimePermission(permission: String): ExecResult {
        val pkg = appContext?.packageName ?: return ExecResult(false, "没有 Context", -1)
        return exec("pm revoke " + pkg + " " + permission)
    }

    /** appops 放行（例如 MANAGE_EXTERNAL_STORAGE / LEGACY_STORAGE）。 */
    suspend fun allowAppOp(op: String): ExecResult {
        val pkg = appContext?.packageName ?: return ExecResult(false, "没有 Context", -1)
        return exec("appops set " + pkg + " " + op + " allow")
    }

    private const val REQUEST_CODE = 4001
}
