package com.pentax.k1sync.ui.gallery

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import com.pentax.k1sync.model.CameraPhoto
import com.pentax.k1sync.model.DownloadQuality
import com.pentax.k1sync.model.PhotoExif
import com.pentax.k1sync.ui.theme.ElevatedSurface
import com.pentax.k1sync.ui.theme.HairlineBorder
import com.pentax.k1sync.ui.theme.InkPrimary
import com.pentax.k1sync.ui.theme.InkSecondary
import com.pentax.k1sync.ui.theme.MatteCard
import com.pentax.k1sync.ui.theme.ObsidianBg
import com.pentax.k1sync.ui.theme.PentaxGreen
import com.pentax.k1sync.ui.theme.SubtleDivider
import com.pentax.k1sync.ui.theme.WarmAmber
import com.pentax.k1sync.viewmodel.MainViewModel

@Composable
fun PhotoCompareOverlay(viewModel: MainViewModel) {
    val pair by viewModel.comparePhotos.collectAsState()
    val previewBitmaps by viewModel.previewBitmaps.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val exifMap by viewModel.photoExifMap.collectAsState()
    val loadingIds by viewModel.loadingPreviewIds.collectAsState()

    val (photoA, photoB) = pair ?: return

    BackHandler { viewModel.closeCompare() }

    // Shared synchronized transform state: pinching/panning either pane moves BOTH to exact same coordinates!
    var syncScale by remember(photoA.uniqueId, photoB.uniqueId) { mutableFloatStateOf(1f) }
    var syncOffset by remember(photoA.uniqueId, photoB.uniqueId) { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(ElevatedSurface)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.closeCompare() }) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = InkPrimary)
                }
                Column {
                    Text("双图同步放大对比", color = InkPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("双指捏合或双击同步检焦 (${"%.1f".format(syncScale)}x)", color = PentaxGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Row(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(8.dp))
                    .clickable { syncScale = 1f; syncOffset = Offset.Zero }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.RestartAlt, null, tint = InkPrimary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text("复位 1x", color = InkPrimary, fontSize = 11.sp)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        // Upper Pane: Photo A
        ComparePhotoPane(
            tag = "A",
            photo = photoA,
            bitmap = previewBitmaps[photoA.uniqueId] ?: thumbnails[photoA.uniqueId],
            exif = exifMap[photoA.uniqueId] ?: photoA.exif,
            isLoading = loadingIds.contains(photoA.uniqueId),
            scale = syncScale,
            offset = syncOffset,
            onTransform = { newScale, newOffset -> syncScale = newScale; syncOffset = newOffset },
            onToggleStar = { viewModel.togglePhotoStar(photoA) },
            onDownload = { viewModel.downloadSingleFromDetail(photoA, DownloadQuality.FULL_JPG) },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        HorizontalDivider(thickness = 1.5.dp, color = PentaxGreen.copy(alpha = 0.55f))

        // Lower Pane: Photo B
        ComparePhotoPane(
            tag = "B",
            photo = photoB,
            bitmap = previewBitmaps[photoB.uniqueId] ?: thumbnails[photoB.uniqueId],
            exif = exifMap[photoB.uniqueId] ?: photoB.exif,
            isLoading = loadingIds.contains(photoB.uniqueId),
            scale = syncScale,
            offset = syncOffset,
            onTransform = { newScale, newOffset -> syncScale = newScale; syncOffset = newOffset },
            onToggleStar = { viewModel.togglePhotoStar(photoB) },
            onDownload = { viewModel.downloadSingleFromDetail(photoB, DownloadQuality.FULL_JPG) },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

@Composable
private fun ComparePhotoPane(
    tag: String,
    photo: CameraPhoto,
    bitmap: android.graphics.Bitmap?,
    exif: PhotoExif?,
    isLoading: Boolean,
    scale: Float,
    offset: Offset,
    onTransform: (Float, Offset) -> Unit,
    onToggleStar: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(photo.uniqueId) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.2f) onTransform(1f, Offset.Zero)
                        else onTransform(3.0f, Offset.Zero)
                    }
                )
            }
            .pointerInput(photo.uniqueId) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 6f)
                    val nextOffset = if (nextScale > 1.02f) {
                        val maxX = size.width * (nextScale - 1f) / 2f
                        val maxY = size.height * (nextScale - 1f) / 2f
                        Offset(
                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                            (offset.y + pan.y).coerceIn(-maxY, maxY)
                        )
                    } else Offset.Zero
                    onTransform(nextScale, nextOffset)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = photo.file,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
            )
        }
        if (isLoading) {
            CircularProgressIndicator(color = PentaxGreen, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }

        // Top-Left Label + EXIF Badge
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xB8000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(tag, color = PentaxGreen, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(photo.file, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val paramStr = exif?.formatParamsSingleLine().orEmpty()
            if (paramStr.isNotBlank()) {
                Text(paramStr, color = InkSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }

        // Top-Right Quick Star & Download
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xB8000000)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleStar, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (photo.isStarred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = "星标",
                    tint = if (photo.isStarred) WarmAmber else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDownload, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Download, contentDescription = "下载原图", tint = PentaxGreen, modifier = Modifier.size(18.dp))
            }
        }
    }
}
