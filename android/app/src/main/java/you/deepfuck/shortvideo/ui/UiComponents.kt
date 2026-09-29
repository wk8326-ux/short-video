package you.deepfuck.shortvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal object UiDimens {
    val ScreenHorizontal = 16.dp
    val CompactHorizontal = 12.dp
    val NavigationHeight = 52.dp
    val NavigationGap = 10.dp
    val NavigationInset = NavigationHeight + NavigationGap
    val TouchTarget = 48.dp
    val SectionGap = 24.dp
    val GridGap = 10.dp
    val MovieGridMin = 148.dp
    val DramaGridMin = 112.dp
    val CardRadius = 8.dp
    val ControlRadius = 6.dp
}

internal val MediaCardShape = RoundedCornerShape(UiDimens.CardRadius)
internal val MetadataChipShape = RoundedCornerShape(999.dp)

@Composable
internal fun AppTopContentInset() {
    Spacer(Modifier.height(UiDimens.NavigationInset))
}

@Composable
internal fun AppScreenScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .padding(top = UiDimens.NavigationInset),
        content = content,
    )
}

@Composable
internal fun SectionHeader(
    title: String,
    trailing: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = UiDimens.TouchTarget)
        .then(
            if (onClick == null) {
                Modifier
            } else {
                Modifier
                    .clip(ControlShape)
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = "$title，打开" }
            },
        )
        .padding(horizontal = UiDimens.ScreenHorizontal, vertical = 6.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            modifier = Modifier.weight(1f, fill = false),
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            trailing,
            color = TextFaint,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
internal fun MetadataChip(
    label: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val chipModifier = modifier.defaultMinSize(minHeight = UiDimens.TouchTarget)
    if (onClick == null) {
        Box(
            modifier = chipModifier
                .clip(MetadataChipShape)
                .background(GlassFillSoft),
        ) {
            ChipText(label)
        }
    } else {
        Surface(
            onClick = onClick,
            modifier = chipModifier,
            shape = MetadataChipShape,
            color = GlassFillSoft,
            contentColor = TextSecondary,
        ) {
            ChipText(label)
        }
    }
}

@Composable
private fun ChipText(label: String) {
    Text(
        label,
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
