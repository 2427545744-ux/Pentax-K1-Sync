package com.pentax.k1sync.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pentax.k1sync.model.DownloadQuality
import com.pentax.k1sync.ui.components.RgbHistogramView
import com.pentax.k1sync.ui.theme.ElevatedSurface
import com.pentax.k1sync.ui.theme.HairlineBorder
import com.pentax.k1sync.ui.theme.InkPrimary
import com.pentax.k1sync.ui.theme.InkSecondary
import com.pentax.k1sync.ui.theme.MatteCard
import com.pentax.k1sync.ui.theme.ObsidianBg
import com.pentax.k1sync.ui.theme.PentaxGreen
import com.pentax.k1sync.ui.theme.PentaxRed
import com.pentax.k1sync.ui.theme.SubtleDivider
import com.pentax.k1sync.viewmodel.MainViewModel

@Composable
fun PhotoDetailDialog(viewModel: MainViewModel) {
    val photo by viewModel.detailPhoto.collectAsState()
    val bitmap by viewModel.detailBitmap.collectAsState()
    val exif by viewModel.detailExif.collectAsState()
    val histogram by viewModel.detailHistogram.collectAsState()
    val isLoading by viewModel.isLoadingDetail.collectAsState()

    val currentPhoto = photo ?: return

    // Expandable histogram/full-EXIF drawer (collapsed by default so the photo commands ~75% of screen!)
    var expandFullInfo by remember(currentPhoto.uniqueId) { mutableStateOf(false) }
    var scale by remember(currentPhoto.uniqueId) { mutableFloatStateOf(1f) }
    var offset by remember(currentPhoto.uniqueId) { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = { viewModel.closePhotoDetail() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianBg)
                .systemBarsPadding()
        ) {
            // 1. Clean Top Navigation Bar (52dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(ElevatedSurface)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = { viewModel.closePhotoDetail() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = InkPrimary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentPhoto.file,
                            color = InkPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "${currentPhoto.slot.displayName} · ${currentPhoto.dir}",
                                color = InkSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (currentPhoto.isRaw) {
                                Text(
                                    text = currentPhoto.extension,
                                    color = PentaxRed,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = { expandFullInfo = !expandFullInfo }) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = "直方图与参数",
                        tint = if (expandFullInfo) PentaxGreen else InkSecondary
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

            // 2. Properly Proportioned Clipped Image Stage (Never overlaps top/bottom bars!)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .clipToBounds()
                    .pointerInput(currentPhoto.uniqueId) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(currentPhoto.uniqueId) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = nextScale
                            offset = if (nextScale > 1.02f) {
                                val maxX = size.width * (nextScale - 1f) / 2f
                                val maxY = size.height * (nextScale - 1f) / 2f
                                Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY)
                                )
                            } else Offset.Zero
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = currentPhoto.file,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        color = PentaxGreen,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // 3. Compact Darkroom Telemetry & Action Footer
            HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Compact single-line EXIF strip (always clean and proportional)
                val paramLine = exif?.formatParamsSingleLine().orEmpty()
                if (paramLine.isNotBlank() || expandFullInfo) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MatteCard)
                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
                            .clickable { expandFullInfo = !expandFullInfo }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = paramLine.ifBlank { "点击查看参数与直方图" },
                            color = InkPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (expandFullInfo) "收起" else "直方图",
                            color = PentaxGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Expandable RGB Histogram + Camera/Lens/Date details
                if (expandFullInfo) {
                    Spacer(Modifier.height(8.dp))
                    histogram?.let {
                        RgbHistogramView(histogram = it)
                        Spacer(Modifier.height(8.dp))
                    }
                    exif?.let { info ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MatteCard)
                                .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(info.cameraModel.ifBlank { "PENTAX K-1" }, color = PentaxGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                if (info.dateTime.isNotBlank()) {
                                    Text(info.dateTime, color = InkSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            if (info.lensModel.isNotBlank()) {
                                Text(info.lensModel, color = InkSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Balanced 3-Button Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailActionButton(
                        label = "预览下载",
                        icon = Icons.Rounded.Download,
                        primary = false,
                        modifier = Modifier.weight(1f)
                    ) {
                        viewModel.enqueueDownload(listOf(currentPhoto), DownloadQuality.VIEW)
                    }
                    DetailActionButton(
                        label = if (currentPhoto.isRaw) "无损 RAW" else "下载原图",
                        icon = Icons.Rounded.Download,
                        primary = false,
                        modifier = Modifier.weight(1f)
                    ) {
                        val q = if (currentPhoto.isRaw) DownloadQuality.RAW else DownloadQuality.FULL_JPG
                        viewModel.enqueueDownload(listOf(currentPhoto), q)
                    }
                    DetailActionButton(
                        label = "宾得水印",
                        icon = Icons.Rounded.AutoFixHigh,
                        primary = true,
                        modifier = Modifier.weight(1.1f)
                    ) {
                        viewModel.sendPhotoToWatermarkStudio(currentPhoto, bitmap, exif)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) PentaxGreen else MatteCard)
            .then(if (!primary) Modifier.border(0.5.dp, HairlineBorder, RoundedCornerShape(12.dp)) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (primary) Color(0xFF04170B) else InkPrimary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = if (primary) Color(0xFF04170B) else InkPrimary,
            fontSize = 12.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
