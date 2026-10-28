package com.zmf.agent.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.ui.common.saltCardRow
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable

/** 开源与致谢里用到的地址：只在这里出现一次，改动时不会漏掉某一行。 */
private object AboutLinks {
    const val REPO = "https://github.com/MingMaple/Mingent"
    const val ISSUES = REPO + "/issues"
    const val RELEASES = REPO + "/releases"
    const val NOTICES = REPO + "/blob/main/THIRD-PARTY-NOTICES.md"
    const val OPERIT = "https://github.com/AAswordman/Operit"
    const val SALT_UI = "https://github.com/Moriafly/SaltUI"
    const val SHIZUKU = "https://github.com/RikkaApps/Shizuku"
    const val DSH = "https://github.com/deepseek-ai/deepseek-harness"
    const val DSH_CONTEXT = "https://github.com/bowenliang123/dsh-context"
}

/**
 * 「关于」页：版本、开源地址、第三方组件与致谢。
 *
 * 单独成页而不是塞进通用设置：这里全是只读信息，混进可改的设置项里会让人以为能点。
 * 版式沿用设置层的卡片列表（16dp 圆角、surface 底、不描边），与 Salt 风格保持一致。
 */
@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val info = remember {
        @Suppress("DEPRECATION")
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = info?.versionName ?: "—"
    @Suppress("DEPRECATION")
    val versionCode = info?.versionCode ?: 0
    val onSurface = MaterialTheme.colorScheme.onSurface

    Surface(
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
                    text = "关于",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AboutCard(title = "应用") {
                    AboutRow(label = "名称", value = "Mingent")
                    AboutDivider()
                    AboutRow(label = "版本", value = versionName + "（" + versionCode + "）")
                    AboutDivider()
                    AboutRow(label = "包名", value = context.packageName, valueWidth = 236.dp)
                    AboutDivider()
                    AboutRow(label = "许可证", value = "MIT")
                }

                AboutCard(title = "开源") {
                    AboutRow(
                        label = "源码仓库",
                        value = "GitHub",
                        onClick = { openLink(context, AboutLinks.REPO) }
                    )
                    AboutDivider()
                    AboutRow(
                        label = "问题反馈",
                        value = "Issues",
                        onClick = { openLink(context, AboutLinks.ISSUES) }
                    )
                    AboutDivider()
                    AboutRow(
                        label = "发布版本",
                        value = "Releases",
                        onClick = { openLink(context, AboutLinks.RELEASES) }
                    )
                    AboutDivider()
                    AboutRow(
                        label = "第三方声明",
                        value = "THIRD-PARTY-NOTICES",
                        onClick = { openLink(context, AboutLinks.NOTICES) }
                    )
                }

                AboutCard(title = "第三方组件") {
                    AboutRow(
                        label = "Operit AI（终端环境）",
                        value = "LGPL-3.0",
                        onClick = { openLink(context, AboutLinks.OPERIT) }
                    )
                    AboutDivider()
                    AboutRow(
                        label = "SaltUI（界面组件）",
                        value = "Apache-2.0",
                        onClick = { openLink(context, AboutLinks.SALT_UI) }
                    )
                    AboutDivider()
                    AboutRow(
                        label = "Shizuku（系统接口）",
                        value = "Apache-2.0",
                        onClick = { openLink(context, AboutLinks.SHIZUKU) }
                    )
                    AboutDivider()
                    AboutRow(
                        label = "AndroidX 等基础库",
                        value = "Apache-2.0"
                    )
                }

                AboutCard(title = "致谢") {
                    Text(
                        text = "内置终端基于 Operit AI 的 PRoot Ubuntu 环境实现；" +
                            "界面取向参考 Salt Player；" +
                            "Agent 循环与上下文压缩的思路参考 DeepSeek Harness 及其上下文插件。" +
                            "以上仅作思路参考的部分不携带其任何代码。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = onSurface.copy(alpha = 0.55f)
                    )
                    AboutDivider()
                    AboutRow(
                        label = "DeepSeek Harness",
                        value = "MIT",
                        onClick = { openLink(context, AboutLinks.DSH) }
                    )
                    AboutDivider()
                    AboutRow(
                        label = "dsh-context",
                        value = "Apache-2.0",
                        onClick = { openLink(context, AboutLinks.DSH_CONTEXT) }
                    )
                }

                Text(
                    text = "Copyright © 2026 Mingent",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.35f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    text = "Android " + Build.VERSION.RELEASE + " · " + Build.MODEL,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.25f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/** 打开外部链接：没有浏览器时不崩，静默忽略（这里没有可用的提示位）。 */
private fun openLink(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        // 行自己带内距：卡片 Column 不垫上下留白，末行的波纹才能贴到 16dp 圆弧上被裁成圆角
        Column(modifier = Modifier) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            content()
        }
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    )
}

/** 一行「左标题 / 右值」；给了 onClick 就带「›」并且整行可点。 */
@Composable
private fun AboutRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    /** 右侧值的最大宽度：包名这类长值单独放宽，其它行保持统一。 */
    valueWidth: androidx.compose.ui.unit.Dp = 190.dp
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .saltCardRow() { onClick() }
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface
        )
        Text(
            text = value,
            modifier = Modifier.widthIn(max = valueWidth),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = onSurface.copy(alpha = 0.45f)
        )
        if (onClick != null) {
            Text(
                text = "›",
                modifier = Modifier.padding(start = 6.dp),
                fontSize = 16.sp,
                color = onSurface.copy(alpha = 0.35f)
            )
        }
    }
}
