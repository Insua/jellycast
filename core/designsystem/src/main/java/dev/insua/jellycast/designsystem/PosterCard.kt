package dev.insua.jellycast.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** [PosterCardTest] 用来定位"听过进度"指示条/收藏/已看按钮的测试标签,不依赖具体样式做断言。 */
object PosterCardTestTags {
    const val PROGRESS_BAR = "poster_card_progress_bar"
    const val FAVORITE_BUTTON = "poster_card_favorite_button"
    const val PLAYED_BUTTON = "poster_card_played_button"
}

/** [PosterCardWideTest] 用来定位宽幅卡片的测试标签。 */
object PosterCardWideTestTags {
    const val PROGRESS_BAR = "poster_card_wide_progress_bar"
    const val PLAY_OVERLAY = "poster_card_wide_play_overlay"
}

/**
 * 剧集/电影海报卡:封面(专辑封面位)+ 标题 + 可选副标题。用于首页/剧库的网格与横向列表。
 * 封面是海报图,不是视频缩略图——这里绝不出现 Surface/PlayerView。
 *
 * [progress] 是可选的"听过比例"(0f~1f),只有"继续收听"这类分区会传值——其余调用方
 * (剧库网格、下一集、最近添加)保持不传,行为与新增该参数之前完全一致。传入 null 时
 * 不画任何指示条,不会出现宽度为 0 的空条。参考小宇宙/Spotify 的处理:进度条叠在封面
 * 图底边、窄、圆角,而不是 Material 默认的整条 LinearProgressIndicator。
 *
 * [isFavorite]/[onToggleFavorite] 与 [isPlayed]/[onTogglePlayed] 是收藏/已看的叠加按钮
 * ——**成对**出现:只有状态和回调都非 null 才画那个按钮,调用方不需要这个交互(比如首页
 * "最近添加"分组)时两个都不传,外观与新增这两组参数之前完全一致。按钮叠在封面图右上角
 * (收藏)/左上角(已看),半透明圆形底避免在浅色封面上看不清;点击本身只触发回调,乐观更新
 * /失败回滚是调用方(ViewModel)的职责,这里只负责画出当前状态。
 */
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    isFavorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
    isPlayed: Boolean? = null,
    onTogglePlayed: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .width(140.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            if (progress != null) {
                val fraction = progress.coerceIn(0f, 1f)
                // 叠在封面底边的窄条,而不是卡片下方独立一行的 Material LinearProgressIndicator——
                // 呼应小宇宙/Spotify"进度直接刻在封面上"的读法。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .testTag(PosterCardTestTags.PROGRESS_BAR),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }

            if (isPlayed != null && onTogglePlayed != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                ) {
                    IconButton(
                        onClick = onTogglePlayed,
                        modifier = Modifier.size(28.dp).testTag(PosterCardTestTags.PLAYED_BUTTON),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = if (isPlayed) "标记为未看" else "标记为已看",
                            tint = if (isPlayed) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (isFavorite != null && onToggleFavorite != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                ) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(28.dp).testTag(PosterCardTestTags.FAVORITE_BUTTON),
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFavorite) "取消收藏" else "收藏",
                            tint = if (isFavorite) MaterialTheme.colorScheme.error else Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "继续收听"用的宽幅卡:16:9 缩略图 + 底边通栏进度条 + 居中播放三角形叠层,参照 Jellyfin Web
 * mobile 的继续收听卡片(设计文档 §3.6)。**不是** [PosterCard] 的替代——剧库网格/下一集/最近添加
 * 仍然用 1:1 的 [PosterCard],这里是加一个新的组合,不改任何既有调用点。
 *
 * [progress] 语义和 [PosterCard] 的同名参数一致(听过比例 0f~1f,由调用方用
 * `resumePositionMs / runTimeMs` 算好传入,null/0 总时长的退化已经在调用方处理——这里只管画):
 * 非 null 且 > 0 才画进度条,避免出现一条 0 宽的空条。播放三角形叠层始终显示,不随进度变化——
 * 它标识"这是可以继续播放的一集",不是当前播放状态。
 */
@Composable
fun PosterCardWide(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .testTag(PosterCardWideTestTags.PLAY_OVERLAY),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }

            if (progress != null && progress > 0f) {
                val fraction = progress.coerceIn(0f, 1f)
                // 通栏进度条,贴底边、不做左右内边距——呼应 Jellyfin Web mobile 继续收听卡片的读法,
                // 和 [PosterCard] 那条内缩的窄条是刻意不同的两种视觉语言。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .testTag(PosterCardWideTestTags.PROGRESS_BAR),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
