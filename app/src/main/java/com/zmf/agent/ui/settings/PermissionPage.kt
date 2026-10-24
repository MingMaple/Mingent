package com.zmf.agent.ui.settings
import com.zmf.agent.ui.common.blockTapsOnly

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zmf.agent.core.capability.AndroidPermissions
import com.zmf.agent.core.capability.ShizukuBridge
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Palette

/**
 * 「权限管理」页（设置下的二级页）：把手机端真正卡住功能的几项权限集中成一张清单。
 *
 * 只做「看状态 + 送去授权」：能看到的就是系统里查得到的状态，点击就是把用户送到
 * 系统弹框 / 系统设置页 / Shizuku 自己的授权框，回来之后重查一次状态，不猜结果。
 * 版式照 Salt：整页铺满、分组圆角卡片、每行 56dp；行里只有名字和「未授权」这种最短状态，
 * 正常授权时不留任何解释小字。
 */
@Composable
fun PermissionPage(onBack: () -> Unit) {
    val context = LocalContext.current
    // 运行时权限框只能由 Activity 弹；悬浮窗里的 ComposeView 拿到的就不是 Activity
    val activity = context as? Activity

    var status by remember { mutableStateOf(AndroidPermissions.status(context)) }
    // 点过「存储权限」但系统没给：Android 14 + targetSdk 28 上权限框可能根本不弹，回调直接是 denied
    var storageDenied by remember { mutableStateOf(false) }
    val shizuku by ShizukuBridge.state.collectAsState()

    // 申请结果只用来触发重查：系统到底给了什么一律回系统看，不拿回调里的布尔值当真
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        status = AndroidPermissions.status(context)
        // 记下「点过但没拿到」，用来区分「没申请过」和「系统不给」，两种状态文案不一样
        storageDenied = !status.storage
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> status = AndroidPermissions.status(context) }

    // 进页面先对一次（Binder 可能在别的页面掉过，等不到 ON_RESUME）
    LaunchedEffect(Unit) {
        ShizukuBridge.refresh()
        status = AndroidPermissions.status(context)
    }

    // 用户去系统页授权完回来时 Activity 会走 ON_RESUME：顺手把两类状态一起刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = AndroidPermissions.status(context)
                ShizukuBridge.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 存储行的有效状态：运行时权限和「所有文件访问」任一在手都算能读写共享存储。
    // targetSdk 28 在 Android 14 上 READ/WRITE 不再授予，实际生效的是 MANAGE_EXTERNAL_STORAGE，
    // 所以这两行必须按同一个口径算，否则上面写「未授权」、下面写「已授权」就自相矛盾。
    val storageGranted = status.storage || status.allFiles
    val storageDetail = when {
        status.storage -> ""
        // 已经由整盘访问覆盖：一句最短状态，不再解释为什么
        status.allFiles -> "已由「所有文件访问」覆盖"
        // 点过但系统没弹框（Android 14 上常见）：别把用户扔回「未授权」原地打转
        storageDenied -> "系统未授予"
        else -> ""
    }

    Surface(
        // 页面铺满且不透明；最外层吞掉点击，避免穿透点到下面的设置页
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(50)) { onBack() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "权限管理",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // 存储：运行时权限 + 整盘访问
            PermissionCard {
                PermissionRow(
                    title = "存储权限",
                    detail = storageDetail,
                    granted = storageGranted,
                    onClick = {
                        if (activity == null) {
                            toastNoActivity(context)
                        } else {
                            storageLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                )
                            )
                        }
                    }
                )
                PermissionRow(
                    title = "所有文件访问",
                    detail = if (status.allFiles) "" else "未授权",
                    granted = status.allFiles,
                    onClick = {
                        // 低版本没有这项权限，跳不过去就用 Toast 说清楚，别让用户以为按钮坏了
                        if (!AndroidPermissions.openAllFilesSettings(context)) {
                            Toast.makeText(context, "当前系统没有整盘文件访问开关", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // 系统给的其余几项
            PermissionCard {
                PermissionRow(
                    title = "通知",
                    detail = if (status.notification) "" else "未授权",
                    granted = status.notification,
                    onClick = {
                        if (activity == null) {
                            toastNoActivity(context)
                        } else {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
                PermissionRow(
                    title = "悬浮窗",
                    detail = if (status.overlay) "" else "未授权",
                    granted = status.overlay,
                    onClick = { AndroidPermissions.openOverlaySettings(context) }
                )
                PermissionRow(
                    title = "电池优化",
                    detail = if (status.batteryIgnore) "" else "未授权",
                    granted = status.batteryIgnore,
                    onClick = { AndroidPermissions.openBatterySettings(context) }
                )
            }

            // Shizuku：状态由 ShizukuBridge 推，页面只读
            PermissionCard {
                PermissionRow(
                    title = "Shizuku",
                    detail = shizukuDetail(shizuku),
                    granted = shizuku == ShizukuBridge.State.READY,
                    actionText = shizukuActionText(shizuku),
                    onClick = {
                        when (shizuku) {
                            // 已就绪时行是禁用的，走到这里只可能是状态刚变
                            ShizukuBridge.State.READY -> Unit

                            ShizukuBridge.State.NO_PERMISSION -> {
                                if (activity == null) {
                                    toastNoActivity(context)
                                } else if (!ShizukuBridge.requestPermission()) {
                                    // 服务刚好掉线或其实已授权：重判一次，别让界面停在旧值
                                    ShizukuBridge.refresh()
                                }
                            }

                            // 没装 / 没启动：只能把用户送到 Shizuku 本体，剩下由它自己引导
                            else -> openShizuku(context)
                        }
                    }
                )
            }
        }
    }
}

/** 分组卡片：Surface + 22dp 圆角，跟侧边栏的卡片同一套观感。 */
@Composable
private fun PermissionCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            // 卡片自己裁到 22dp：行用的是矩形波纹，越界部分由这层裁掉
            .clip(RoundedCornerShape(22.dp))
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) { content() }
    }
}

/**
 * 一行权限：左边名字，右边「已授权」或动作文案。
 *
 * [detail] 只在未授权 / 异常时给一句最短状态（「未授权」「服务未启动」），
 * 已授权时传空串，整行不留任何解释小字。[granted] 的行不可点。
 */
@Composable
private fun PermissionRow(
    title: String,
    detail: String = "",
    granted: Boolean,
    actionText: String = "去授权 ›",
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .saltRowClickable(RoundedCornerShape(0.dp), enabled = !granted, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = onSurface)
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = onSurface.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = if (granted) "已授权" else actionText,
            fontSize = 12.sp,
            color = if (granted) Palette.AccentGreen else onSurface.copy(alpha = 0.6f)
        )
    }
}

/** Shizuku 那一行的小状态：只在非就绪时给最短状态，就绪时为空。 */
private fun shizukuDetail(state: ShizukuBridge.State): String = when (state) {
    ShizukuBridge.State.READY -> ""
    ShizukuBridge.State.NO_PERMISSION -> "未授权"
    ShizukuBridge.State.NOT_RUNNING -> "服务未启动"
    ShizukuBridge.State.NOT_INSTALLED -> "未安装"
}

/** Shizuku 行的右侧文案：状态不同，点下去要做的事也不同。 */
private fun shizukuActionText(state: ShizukuBridge.State): String = when (state) {
    ShizukuBridge.State.READY -> "已授权"
    ShizukuBridge.State.NO_PERMISSION -> "去授权 ›"
    ShizukuBridge.State.NOT_RUNNING -> "去启动 ›"
    ShizukuBridge.State.NOT_INSTALLED -> "去安装 ›"
}

/** 拉起 Shizuku 本体；拉不起来（没装 / 被停用）就退回本 App 的系统详情页兜底。 */
private fun openShizuku(context: Context): Boolean {
    val launch = runCatching {
        context.packageManager.getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE)
    }.getOrNull()
    if (launch != null) {
        val ok = runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return true
    }
    return AndroidPermissions.openAppSettings(context)
}

/** 没有 Activity 就弹不了权限框：给一句提示，不要静默什么都不做。 */
private fun toastNoActivity(context: Context) {
    Toast.makeText(context, "当前页面无法申请权限，请回到 App 内重试", Toast.LENGTH_SHORT).show()
}
