package com.pentax.k1sync.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pentax.k1sync.model.CameraInfo
import com.pentax.k1sync.model.HistogramData
import com.pentax.k1sync.model.TransferStatus
import com.pentax.k1sync.model.TransferTask
import com.pentax.k1sync.ui.theme.HairlineBorder
import com.pentax.k1sync.ui.theme.InkPrimary
import com.pentax.k1sync.ui.theme.InkSecondary
import com.pentax.k1sync.ui.theme.MatteCard
import com.pentax.k1sync.ui.theme.ObsidianBg
import com.pentax.k1sync.ui.theme.PentaxGreen
import com.pentax.k1sync.ui.theme.PentaxRed
import com.pentax.k1sync.ui.theme.WarmAmber

@Composable
fun PentaxBrandHeader(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "PENTAX",
            color = PentaxRed,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.2.sp
        )
        Spacer(Modifier.width(7.dp))
        Box(modifier = Modifier.width(1.dp).height(12.dp).background(HairlineBorder))
        Spacer(Modifier.width(7.dp))
        Text(
            text = "K-1",
            color = InkPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun ConnectionPillBadge(
    cameraInfo: CameraInfo,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val dotColor = when {
        cameraInfo.connected && cameraInfo.isMock -> WarmAmber
        cameraInfo.connected -> PentaxGreen
        else -> InkSecondary
    }
    val label = when {
        cameraInfo.connected && cameraInfo.isMock -> "模拟 · ${cameraInfo.batteryPercent}%"
        cameraInfo.connected -> "已连 · ${cameraInfo.batteryPercent}%"
        else -> "未连接"
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MatteCard)
            .border(0.5.dp, HairlineBorder, CircleShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (cameraInfo.connected) InkPrimary else InkSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
fun <T> SegmentedPillRow(
    items: List<T>,
    selectedItem: T,
    labelProvider: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianBg)
            .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { item ->
            val selected = item == selectedItem
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) MatteCard else Color.Transparent)
                    .then(
                        if (selected) Modifier.border(0.5.dp, Color(0xFF3A3A44), RoundedCornerShape(7.dp))
                        else Modifier
                    )
                    .clickable { onSelect(item) }
                    .padding(vertical = 7.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = labelProvider(item),
                    color = if (selected) InkPrimary else InkSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun PentaxK1SilhouetteIcon(
    tint: Color = InkPrimary,
    accent: Color = PentaxGreen,
    modifier: Modifier = Modifier.size(44.dp)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = (h * 0.06f).coerceAtLeast(2f)
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

        val l = w * 0.08f
        val r = w * 0.92f
        val top = h * 0.16f
        val bottom = h * 0.84f
        val shoulder = h * 0.38f

        val body = Path().apply {
            moveTo(l, shoulder)
            lineTo(l + (r - l) * 0.27f, shoulder)
            lineTo(l + (r - l) * 0.40f, top)
            lineTo(l + (r - l) * 0.60f, top)
            lineTo(l + (r - l) * 0.73f, shoulder)
            lineTo(r, shoulder)
            lineTo(r, bottom)
            lineTo(l, bottom)
            close()
        }
        drawPath(body, color = tint, style = stroke)

        val cx = w * 0.50f
        val cy = h * 0.61f
        val rad = h * 0.17f
        drawCircle(color = tint, radius = rad, center = Offset(cx, cy), style = stroke)
        drawCircle(color = accent, radius = rad * 0.55f, center = Offset(cx, cy), style = Stroke(width = sw * 0.9f))
    }
}

@Composable
fun RgbHistogramView(
    histogram: HistogramData,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xCC0D0D10), Color(0xEE08080A))))
            .border(0.5.dp, HairlineBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp)
    ) {
        val bins = histogram.luma.size
        if (bins < 2) return@Canvas
        val stepX = size.width / (bins - 1)

        fun drawChannel(values: FloatArray, color: Color) {
            val path = Path().apply {
                moveTo(0f, size.height)
                for (i in 0 until bins) {
                    lineTo(i * stepX, size.height * (1f - values[i].coerceIn(0f, 1f)))
                }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, color = color)
        }

        drawChannel(histogram.red, Color(0x55FF453A))
        drawChannel(histogram.green, Color(0x5530D158))
        drawChannel(histogram.blue, Color(0x550A84FF))
        drawChannel(histogram.luma, Color(0x66FFFFFF))
    }
}

@Composable
fun GlobalTransferProgressBar(
    tasks: List<TransferTask>,
    onClickQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = tasks.filter { it.status == TransferStatus.QUEUED || it.status == TransferStatus.DOWNLOADING }
    if (active.isEmpty()) return

    val current = active.firstOrNull { it.status == TransferStatus.DOWNLOADING } ?: active.first()
    val completed = tasks.count { it.status == TransferStatus.COMPLETED }
    val total = active.size + completed

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xF2181B1A))
            .border(0.5.dp, PentaxGreen.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable { onClickQueue() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Download, null, tint = PentaxGreen, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "传输中 (${completed + 1}/$total) · ${current.photo.file}",
                    color = InkPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${(current.progress * 100).toInt()}%",
                color = PentaxGreen,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { current.progress.coerceIn(0f, 1f) },
            color = PentaxGreen,
            trackColor = ObsidianBg,
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
        )
    }
}
