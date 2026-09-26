package dev.bilby.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bilby.agent.AgentAnswer
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.theme.Spacing

/**
 * 助理的回答:上面是回答正文,下面是它提到的视频。
 *
 * **两者分开摆。** 视频原先是就地插在句子中间的卡片,一句话被劈成两半,读的时候得跳过卡片
 * 才接得上。现在正文里只留一个可点的角标,视频按角标的编号列在下面,和论文的出处一样:
 * 读答案时不被打断,要去看哪一个时再往下找。
 *
 * 搜索页和播放页的「找相关」共用这一份 —— 两处显示同一个助理的同一种输出,分成两套写法
 * 只会让它们慢慢长歪。
 *
 * 正文过 [MarkdownText]:模型本来就会写 `**` 和 `- `,不解析的话那些记号会原样印在答案里。
 */
@Composable
fun AgentAnswerView(
    answer: AgentAnswer,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        if (answer.text.isNotBlank()) {
            MarkdownText(
                text = answer.text,
                onCitationClick = { number -> answer.sources.getOrNull(number - 1)?.let { onVideoClick(it.bvid) } },
            )
        }
        if (answer.sources.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.agent_sources),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.Tight),
            )
            Column {
                answer.sources.forEachIndexed { index, source ->
                    // 行首是它在正文里的编号,和角标对得上:读到 [2] 往下找 2。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(min = SourceNumberWidth),
                        )
                        CompactVideoRow(
                            // 拿不到展示信息时只剩 bvid:正文里提到了它,少一行会让角标指向空处。
                            title = source.trace?.title ?: source.bvid,
                            coverUrl = source.trace?.coverUrl.orEmpty(),
                            subtitle = source.trace?.upName?.takeIf { it.isNotBlank() },
                            selected = false,
                            onClick = { onVideoClick(source.bvid) },
                        )
                    }
                }
            }
        }
    }
}

/** 出处编号那一列的宽度,两位数也放得下,几行的封面左沿对齐。 */
private val SourceNumberWidth = 16.dp
