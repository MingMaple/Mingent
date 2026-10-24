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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable

/**
 * 设置首页：七行入口，取代原来那个「三段式」分类列表——分类再多一级，
 * 用户找「字号」这类常用项要多点两下，所以这里直接按功能平铺。
 *
 * 上下文拆成「洞察 / 配置」两行：一个只是看、一个是要改，挤在一页里两件事互相干扰。
 * 余额不在首页（模型页已经有余额了，两处重复只会让人怀疑哪个是真的）。
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
                HomeCard {
                    HomeNavRow(title = "通用设置", onClick = onGeneral)
                    HomeDivider()
                    HomeNavRow(title = "模型", onClick = onModel)
                    HomeDivider()
                    HomeNavRow(title = "长期记忆", onClick = onMemory)
                    HomeDivider()
                    HomeNavRow(title = "上下文洞察", onClick = onContextInsight)
                    HomeDivider()
                    HomeNavRow(title = "上下文配置", onClick = onContextConfig)
                }
                HomeCard {
                    HomeNavRow(title = "包管理", onClick = onPackage)
                    HomeDivider()
                    HomeNavRow(title = "数据", onClick = onData)
                }
                // 关于单独一张卡：里面全是只读信息，与上面可改的入口分开摆
                HomeCard {
                    HomeNavRow(title = "关于", onClick = onAbout)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 分组卡片：Salt 的圆角 22dp，卡片底走 colorScheme.surface，不描边。 */
@Composable
private fun HomeCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

/** 卡片内的分隔线：从文字左边起，不横穿整张卡片。 */
@Composable
private fun HomeDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    )
}

/** 跳页的行：标题 + 可选右侧值 + 「›」。 */
@Composable
private fun HomeNavRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(RoundedCornerShape(0.dp)) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
