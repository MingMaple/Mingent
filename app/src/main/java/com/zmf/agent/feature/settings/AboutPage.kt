package com.zmf.agent.feature.settings

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.icons.BugGlyph
import com.zmf.agent.uikit.icons.CodeGlyph
import com.zmf.agent.uikit.icons.DocGlyph
import com.zmf.agent.uikit.icons.TagGlyph

/** 开源与致谢中使用的地址统一集中在此，便于集中维护。 */
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
 * 带协议的开源项目：名称、协议、主页。
 *
 * 与「开源」卡里的自建链接分开放：这一组是法律意义上的第三方来源，
 * 名称与协议必须成对出现，不能只留一个链接。
 */
private data class OpenSourceItem(val name: String, val license: String, val url: String)

private val OPEN_SOURCE_ITEMS = listOf(
    OpenSourceItem("Operit AI", "LGPL-3.0", AboutLinks.OPERIT),
    OpenSourceItem("DeepSeek Harness", "MIT", AboutLinks.DSH),
    OpenSourceItem("SaltUI", "Apache-2.0", AboutLinks.SALT_UI),
    OpenSourceItem("dsh-context", "Apache-2.0", AboutLinks.DSH_CONTEXT),
    OpenSourceItem("Shizuku", "Apache-2.0", AboutLinks.SHIZUKU)
)

/**
 * 「关于」页：应用信息、开源地址、开源与致谢。
 *
 * 全页只分层不分组：应用信息是一块，可点的链接是另一块，其余只读信息归入同一块。
 * 版式沿用设置层的卡片列表（16dp 圆角、surface 底、不描边），与 Salt 风格一致。
 * 版权行排在内容末尾并随页面滚动，与上方卡片之间留出 48dp：整页只有一处硬边界（页面顶端）。
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
                .stableStatusBarPadding()
                .navigationBarsPadding()
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppCard(
                        versionName = versionName,
                        versionCode = versionCode,
                        packageName = context.packageName
                    )

                    AboutCard(title = "开源") {
                        AboutLinkRow(
                            label = "源码仓库",
                            desc = "在 GitHub 查看全部源码",
                            glyph = { tint -> CodeGlyph(tint, 20.dp) }
                        ) { openLink(context, AboutLinks.REPO) }
                        AboutDivider()
                        AboutLinkRow(
                            label = "问题反馈",
                            desc = "报告问题或提出建议",
                            glyph = { tint -> BugGlyph(tint, 20.dp) }
                        ) { openLink(context, AboutLinks.ISSUES) }
                        AboutDivider()
                        AboutLinkRow(
                            label = "发布版本",
                            desc = "查看历史版本与更新",
                            glyph = { tint -> TagGlyph(tint, 20.dp) }
                        ) { openLink(context, AboutLinks.RELEASES) }
                        AboutDivider()
                        AboutLinkRow(
                            label = "第三方声明",
                            desc = "查看第三方依赖与协议",
                            glyph = { tint -> DocGlyph(tint, 20.dp) }
                        ) { openLink(context, AboutLinks.NOTICES) }
                    }

                    AboutCard(title = "开源与致谢") {
                        Text(
                            text = "内置终端基于 Operit AI 的 PRoot Ubuntu 环境实现；界面取向参考 Salt Player；" +
                                "Agent 循环与上下文压缩的思路参考 DeepSeek Harness 及其上下文插件。" +
                                "仅作思路参考的部分不携带其任何代码。另含 AndroidX 等 Apache-2.0 基础库。",
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = onSurface.copy(alpha = 0.45f)
                        )
                        OPEN_SOURCE_ITEMS.forEachIndexed { index, item ->
                            if (index > 0) AboutDivider()
                            AboutLicenseRow(item.name, item.license) { openLink(context, item.url) }
                        }
                    }
                }
                // 版权行随内容滚动，靠间距而非固定横切与上方卡片分开：
                // 若把它钉在屏幕底部，滚动中的卡片会被它切出一条硬边，读起来像版面被截断
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Copyright © 2026 Mingent",
                        fontSize = 12.sp,
                        color = onSurface.copy(alpha = 0.35f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Android " + Build.VERSION.RELEASE + " · " + Build.MODEL,
                        fontSize = 12.sp,
                        color = onSurface.copy(alpha = 0.25f)
                    )
                }
            }
        }
    }
}

/**
 * 应用信息：名称放大加粗作为页面主标题，版本与许可证同行、包名压到最弱。
 *
 * 包名对使用者没有决策价值，只在报障时有用，因此保留但降到几乎不参与视觉竞争的层级。
 */
@Composable
private fun AppCard(versionName: String, versionCode: Int, packageName: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                text = "Mingent",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = onSurface
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = versionName + "（" + versionCode + "）",
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.55f)
                )
                Text(
                    text = " · ",
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.35f)
                )
                Text(
                    text = "MIT",
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.55f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = packageName,
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.3f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 打开外部链接：无浏览器时静默忽略，此处没有可用的提示位。 */
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
        // 行自带内距，卡片 Column 不留上下留白，避免影响末行波纹的圆角裁剪
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

/**
 * 链接行：左侧一枚线稿图标，中间标题与一句说明，右侧「›」。
 *
 * 只放标题会留下一整条空白的右半行，四行并排时整张卡片显得左重右空；
 * 说明文字补的是行内中段，图标补的是左端起点，两者一起才让每一行读起来是「一块」而不是「一行字」。
 */
@Composable
private fun AboutLinkRow(
    label: String,
    desc: String,
    glyph: @Composable (Color) -> Unit,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltCardRow { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        glyph(onSurface.copy(alpha = 0.55f))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 15.sp,
                color = onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.45f)
            )
        }
        Chevron()
    }
}

/** 开源项目行：右侧是协议，协议后方跟「›」。 */
@Composable
private fun AboutLicenseRow(name: String, license: String, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltCardRow { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = license,
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
        Chevron()
    }
}

@Composable
private fun Chevron() {
    Text(
        text = "›",
        modifier = Modifier.padding(start = 6.dp),
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    )
}
