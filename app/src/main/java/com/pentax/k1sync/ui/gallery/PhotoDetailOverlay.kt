package com.pentax.k1sync.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RawOn
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pentax.k1sync.model.DownloadQuality
import com.pentax.k1sync.model.TransferStatus
import com.pentax.k1sync.model.TransferTask
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
import com.pentax.k1sync.ui.theme.WarmAmber
import com.pentax.k1sync.viewmodel.MainViewModel
import kotlin.math.abs

@Composable
fun PhotoDetailOverlay(viewModel: MainViewModel) {
    val isOpen by viewModel.detailOpen.collectAsState()
    val photos by viewModel.detailPhotos.collectAsState()
    val startIndex by viewModel.detailStartIndex.collectAsState()
    val previewBitmaps by viewModel.previewBitmaps.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val exifMap by viewModel.photoExifMap.collectAsState()
    val histogramMap by viewModel.photoHistogramMap.collectAsState()
    val loadingIds by viewModel.loadingPreviewIds.collectAsState()
    val selectedIds by viewModel.selectedPhotoIds.collectAsState()
    val transferTasks by viewModel.transferTasks.collectAsState()

    if (!isOpen || photos.isEmpty()) return

    BackHandler { viewModel.closePhotoDetail() }

    val safeStart = startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = safeStart, pageCount = { photos.size })

    var immersiveHideHud by remember { mutableStateOf(false) }
    var expandFullInfo by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val isDeleting by viewModel.isDeletingPhotos.collectAsState()
    var activePageZoomed by remember { mutableStateOf(false) }
    var dismissDragY by remember { mutableFloatStateOf(0f) }

    val currentPhoto = photos.getOrElse(pagerState.currentPage) { photos.first() }
    val currentExif = exifMap[currentPhoto.uniqueId] ?: currentPhoto.exif
    val currentHistogram = histogramMap[currentPhoto.uniqueId]
    val isSelected = selectedIds.contains(currentPhoto.uniqueId)

    LaunchedEffect(pagerState.currentPage, photos.size) {
        activePageZoomed = false
        dismissDragY = 0f
        viewModel.onDetailPageActive(pagerState.currentPage)
    }

    val previewTask = transferTasks.firstOrNull {
        it.photo.uniqueId == currentPhoto.uniqueId && it.quality == DownloadQuality.VIEW
    }
    val fullJpgTask = transferTasks.firstOrNull {
        it.photo.uniqueId == currentPhoto.uniqueId && it.quality == DownloadQuality.FULL_JPG
    }
    val rawTargetId = currentPhoto.companionRaw?.uniqueId ?: currentPhoto.uniqueId
    val rawTask = transferTasks.firstOrNull {
        it.photo.uniqueId == rawTargetId && it.quality == DownloadQuality.RAW
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (!immersiveHideHud) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(ElevatedSurface)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = { viewModel.closePhotoDetail() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = InkPrimary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = currentPhoto.file,
                                color = InkPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = "${pagerState.currentPage + 1}/${photos.size}",
                                color = PentaxGreen,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "${currentPhoto.slot.displayName} · ${currentPhoto.dir}",
                                color = InkSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (currentPhoto.badgeText.isNotBlank()) {
                                Text(
                                    text = currentPhoto.badgeText,
                                    color = if (currentPhoto.hasRawCompanion) PentaxGreen else PentaxRed,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !isDeleting,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "删除或标废",
                        tint = if (currentPhoto.isRejected) PentaxRed else InkSecondary,
                        modifier = Modifier.size(21.dp)
                    )
                }

                // ★ Star / Favorite Rating Toggle
                IconButton(
                    onClick = { viewModel.togglePhotoStar(currentPhoto) },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = if (currentPhoto.isStarred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = "星标精选",
                        tint = if (currentPhoto.isStarred) WarmAmber else InkSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Quick-select checkbox for batch download
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.togglePhotoSelection(currentPhoto) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(21.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) PentaxGreen else Color.Transparent)
                            .border(1.5.dp, if (isSelected) PentaxGreen else InkSecondary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Rounded.Check, contentDescription = "已勾选", tint = Color(0xFF04170B), modifier = Modifier.size(13.dp))
                        }
                    }
                }

                IconButton(
                    onClick = { expandFullInfo = !expandFullInfo },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = "直方图与信息",
                        tint = if (expandFullInfo) PentaxGreen else InkSecondary,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
        }

        // HorizontalPager Image Stage
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .clipToBounds()
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !activePageZoomed,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dismissDragY
                        alpha = (1f - (abs(dismissDragY) / 700f)).coerceIn(0.45f, 1f)
                    }
            ) { pageIndex ->
                val pagePhoto = photos[pageIndex]
                val pageBmp = previewBitmaps[pagePhoto.uniqueId] ?: thumbnails[pagePhoto.uniqueId]
                val pageLoading = loadingIds.contains(pagePhoto.uniqueId)

                ZoomablePagerPhotoItem(
                    bitmap = pageBmp,
                    contentDesc = pagePhoto.file,
                    isLoading = pageLoading,
                    onZoomChanged = { zoomed ->
                        if (pageIndex == pagerState.currentPage) activePageZoomed = zoomed
                    },
                    onSingleTap = { immersiveHideHud = !immersiveHideHud },
                    onDismissDrag = { dy -> dismissDragY = dy },
                    onDismissTriggered = { viewModel.closePhotoDetail() }
                )
            }
        }

        if (!immersiveHideHud) {
            HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                val paramLine = currentExif?.formatParamsSingleLine().orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MatteCard)
                        .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
                        .clickable { expandFullInfo = !expandFullInfo }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = paramLine.ifBlank { "点击查看直方图与拍摄参数" },
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
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (expandFullInfo) {
                    Spacer(Modifier.height(8.dp))
                    currentHistogram?.let {
                        RgbHistogramView(histogram = it)
                        Spacer(Modifier.height(8.dp))
                    }
                    currentExif?.let { info ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MatteCard)
                                .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DetailDownloadTile(
                        defaultLabel = "预览图",
                        icon = Icons.Rounded.Download,
                        task = previewTask,
                        alreadyDownloaded = false,
                        modifier = Modifier.weight(1f)
                    ) { viewModel.downloadSingleFromDetail(currentPhoto, DownloadQuality.VIEW) }

                    if (!currentPhoto.isRaw) {
                        DetailDownloadTile(
                            defaultLabel = "原图JPG",
                            icon = Icons.Rounded.Download,
                            task = fullJpgTask,
                            alreadyDownloaded = currentPhoto.isDownloaded && !currentPhoto.hasRawCompanion,
                            modifier = Modifier.weight(1f)
                        ) { viewModel.downloadSingleFromDetail(currentPhoto, DownloadQuality.FULL_JPG) }
                    }

                    if (currentPhoto.hasAnyRaw) {
                        DetailDownloadTile(
                            defaultLabel = "无损RAW",
                            icon = Icons.Rounded.RawOn,
                            task = rawTask,
                            alreadyDownloaded = currentPhoto.isRaw && currentPhoto.isDownloaded,
                            modifier = Modifier.weight(1f)
                        ) { viewModel.downloadSingleFromDetail(currentPhoto, DownloadQuality.RAW) }
                    }

                    DetailToolTile(
                        label = "分享",
                        icon = Icons.Rounded.Share,
                        highlighted = false,
                        modifier = Modifier.weight(0.95f)
                    ) { viewModel.sharePhotoFromDetail(currentPhoto) }

                    if (currentPhoto.hasAnyRaw) {
                        DetailToolTile(
                            label = "RAW暗房",
                            icon = Icons.Rounded.AutoAwesome,
                            highlighted = false,
                            modifier = Modifier.weight(1.05f)
                        ) { viewModel.sendRawPhotoToSpektraStudio(currentPhoto) }
                    }

                    DetailToolTile(
                        label = "后期水印",
                        icon = Icons.Rounded.AutoFixHigh,
                        highlighted = true,
                        modifier = Modifier.weight(1.08f)
                    ) { viewModel.sendPhotoToWatermarkStudio(currentPhoto) }
                }
            }
        }

        if (showDeleteDialog) {
            val compSuffix = if (currentPhoto.hasRawCompanion) "（含伴生 ${currentPhoto.companionRaw?.file}）" else ""
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                containerColor = ElevatedSurface,
                title = {
                    Text(
                        text = "处理废片 · ${currentPhoto.file}",
                        color = InkPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "可选择「标记废片隐藏」（安全且随时可恢复，批量传图自动跳过），或尝试「永久删除机身卡内文件」$compSuffix。",
                            color = InkSecondary,
                            fontSize = 12.sp
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(WarmAmber.copy(alpha = 0.16f))
                                .border(0.6.dp, WarmAmber, RoundedCornerShape(10.dp))
                                .clickable {
                                    showDeleteDialog = false
                                    viewModel.togglePhotoReject(currentPhoto, closeDetailIfEmpty = true)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (currentPhoto.isRejected) "取消废片标记（恢复至正常图库）" else "标记为废片并隐藏（推荐 · 安全筛片）",
                                color = WarmAmber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PentaxRed.copy(alpha = 0.16f))
                                .border(0.6.dp, PentaxRed, RoundedCornerShape(10.dp))
                                .clickable {
                                    showDeleteDialog = false
                                    viewModel.deleteCameraPhotos(listOf(currentPhoto), includeCompanionRaw = true)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "尝试永久删除机身 SD 卡文件（不可恢复）",
                                color = PentaxRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("取消", color = InkSecondary)
                    }
                }
            )
        }
    }
}

@Composable
private fun ZoomablePagerPhotoItem(
    bitmap: android.graphics.Bitmap?,
    contentDesc: String,
    isLoading: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onSingleTap: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissTriggered: () -> Unit
) {
    var scale by remember(contentDesc) { mutableFloatStateOf(1f) }
    var offset by remember(contentDesc) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(contentDesc) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = {
                        if (scale > 1.1f) {
                            scale = 1f; offset = Offset.Zero; onZoomChanged(false)
                        } else {
                            scale = 2.6f; onZoomChanged(true)
                        }
                    }
                )
            }
            .pointerInput(contentDesc) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var totalVerticalDrag = 0f
                    var verticalDismissActive = false

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }

                        if (pressedCount >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
                            scale = nextScale
                            onZoomChanged(nextScale > 1.02f)
                            offset = if (nextScale > 1.02f) {
                                val maxX = size.width * (nextScale - 1f) / 2f
                                val maxY = size.height * (nextScale - 1f) / 2f
                                Offset(
                                    (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                    (offset.y + panChange.y).coerceIn(-maxY, maxY)
                                )
                            } else Offset.Zero
                            event.changes.forEach { it.consume() }
                        } else if (pressedCount == 1 && scale > 1.02f) {
                            val panChange = event.calculatePan()
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offset = Offset(
                                (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                (offset.y + panChange.y).coerceIn(-maxY, maxY)
                            )
                            event.changes.forEach { it.consume() }
                        } else if (pressedCount == 1 && scale <= 1.02f) {
                            val pan = event.calculatePan()
                            if (!verticalDismissActive && pan.y > 14f && abs(pan.y) > abs(pan.x) * 1.6f) {
                                verticalDismissActive = true
                            }
                            if (verticalDismissActive) {
                                totalVerticalDrag = (totalVerticalDrag + pan.y).coerceAtLeast(0f)
                                onDismissDrag(totalVerticalDrag)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (verticalDismissActive) {
                        if (totalVerticalDrag > 140f) onDismissTriggered() else onDismissDrag(0f)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDesc,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
            )
        }
        if (isLoading) {
            CircularProgressIndicator(color = PentaxGreen, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun DetailDownloadTile(
    defaultLabel: String,
    icon: ImageVector,
    task: TransferTask?,
    alreadyDownloaded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDownloading = task?.status == TransferStatus.DOWNLOADING || task?.status == TransferStatus.QUEUED
    val isCompleted = task?.status == TransferStatus.COMPLETED || alreadyDownloaded
    val isFailed = task?.status == TransferStatus.FAILED

    val label = when {
        task?.status == TransferStatus.DOWNLOADING -> "${(task.progress * 100).toInt()}%"
        task?.status == TransferStatus.QUEUED -> "排队中"
        isCompleted -> "已存相册"
        isFailed -> "重试"
        else -> defaultLabel
    }
    val tint = when {
        isDownloading || isCompleted -> PentaxGreen
        isFailed -> PentaxRed
        else -> InkPrimary
    }

    Column(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCompleted) PentaxGreen.copy(alpha = 0.14f) else MatteCard)
            .border(0.5.dp, if (isDownloading || isCompleted) PentaxGreen.copy(alpha = 0.65f) else HairlineBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = !isDownloading) { onClick() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(if (isCompleted) Icons.Rounded.Check else icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 10.sp, fontWeight = if (isDownloading || isCompleted) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        if (isDownloading) {
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { (task?.progress ?: 0f).coerceIn(0f, 1f) },
                color = PentaxGreen, trackColor = ObsidianBg,
                modifier = Modifier.fillMaxWidth(0.75f).height(2.dp).clip(CircleShape)
            )
        }
    }
}

@Composable
private fun DetailToolTile(
    label: String,
    icon: ImageVector,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) PentaxGreen else MatteCard)
            .then(if (!highlighted) Modifier.border(0.5.dp, HairlineBorder, RoundedCornerShape(12.dp)) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (highlighted) Color(0xFF04170B) else InkPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (highlighted) Color(0xFF04170B) else InkPrimary, fontSize = 10.sp, fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}
