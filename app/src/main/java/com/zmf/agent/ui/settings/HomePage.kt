package com.zmf.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.ui.common.saltCardRow
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.chat.DshIcons
import com.zmf.agent.tools.HighRiskGate
import com.zmf.agent.ui.theme.Palette

/**
 * 设置首页：八行入口分三张卡，取代原来那个「三段式」分类列表：分类再多一级，
 * 用户找「字号」这类常用项要多点两下，所以只按「日常要不要动」分组，不再多一层跳转。
 *
 * 三张卡的分法：
 *  - 通用设置 / 包管理 / 数据：日常会动的，跟「这个 App 怎么用」有关；
 *  - 模型 / 上下文洞察 / 上下文配置 / 长期记忆：偏 AI 底层，改一次管很久，放一起；
 *  - 关于：全是只读信息，单独一张，免得混进可改的项里让人以为能点。
 *
 * 上下文拆成「洞察 / 配置」两行：一个只是看、一个是要改，挤在一页里两件事互相干扰。
 * 余额不在首页（模型页已经有余额了，两处重复只会让人怀疑哪个是真的）。
 *
 * 每行左边一枚 18dp 灰色线条图标（[DshIcons] 里那套 24 网格线稿，与抽屉「主页 / 终端 / 设置」同一口径），
 * 只负责帮眼睛扫行，不动触控，可点区域仍是整行。
 */
@Composable
fun SettingsHomePage(
    onBack: () -> Unit,
    onGeneral: () -> Unit,
    onModel: () -> Unit,
    onMemory: () -> Unit,
    onContextInsight: () -> Unit,
    onContextConfig: () -> Unit,
    onPackage: () -> Unit,
    onData: () -> Unit,
    onAbout: () -> Unit
) {
    // 删除能力被自检停用时，原因只在进程内：进设置首页时读一次就够，重启后会重新自检
    val deletionDisabled = remember { HighRiskGate.deletionDisabled() }

    Surface(
        // 铺满且不透明；最外层吞掉点击，避免穿透点到下面的页面
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
                    text = "设置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 自检失败时删除能力已被停用：这件事必须摆在最上面，不然用户只会觉得「工具坏了」
                deletionDisabled?.let { reason ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = "删除能力已停用",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = reason,
                                modifier = Modifier.padding(top = 4.dp),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "路径 / 命令 / 令牌自检未通过时为防误删而停用；重启 App 会重新自检。",
                                modifier = Modifier.padding(top = 4.dp),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                HomeCard {
                    HomeNavRow(icon = DshIcons.Gear, title = "通用设置", onClick = onGeneral)
                    HomeDivider()
                    HomeNavRow(icon = DshIcons.Package, title = "包管理", onClick = onPackage)
                    HomeDivider()
                    HomeNavRow(icon = DshIcons.Database, title = "数据", onClick = onData)
                }
                // 第二张卡是 AI 底层的一组：模型、上下文（看 / 改）、长期记忆
                HomeCard {
                    HomeNavRow(icon = DshIcons.Chip, title = "模型", onClick = onModel)
                    HomeDivider()
                    HomeNavRow(icon = DshIcons.Eye, title = "上下文洞察", onClick = onContextInsight)
                    HomeDivider()
                    HomeNavRow(icon = DshIcons.Sliders, title = "上下文配置", onClick = onContextConfig)
                    HomeDivider()
                    HomeNavRow(icon = DshIcons.Bookmark, title = "长期记忆", onClick = onMemory)
                }
                // 关于单独一张卡：里面全是只读信息，与上面可改的入口分开摆
                HomeCard {
                    HomeNavRow(icon = DshIcons.Info, title = "关于", onClick = onAbout)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 分组卡片：圆角 16dp，卡片底走 colorScheme.surface，不描边。 */
@Composable
private fun HomeCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier) {
            content()
        }
    }
}

/** 卡片内的分隔线：从文字左边起，不横穿整张卡片。 */
@Composable
private fun HomeDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    )
}

/** 跳页的行：图标 + 标题 + 可选右侧值 + 「›」。波纹形状由 [saltCardRow] 交给卡片裁剪。 */
@Composable
private fun HomeNavRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltCardRow() { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            // 18dp：正文 15sp 的 1.2 倍，与抽屉「20dp 配 16sp」同一比例；24 网格缩到 18，线宽落在 1.35dp
            modifier = Modifier.size(18.dp),
            tint = Palette.Neutral
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface
        )
        if (value != null) {
            Text(
                text = value,
                modifier = Modifier.widthIn(max = 180.dp),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.45f)
            )
        }
        Text(
            text = "›",
            modifier = Modifier.padding(start = 6.dp),
            fontSize = 16.sp,
            color = onSurface.copy(alpha = 0.35f)
        )
    }
}
