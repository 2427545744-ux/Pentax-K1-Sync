package com.pentax.k1sync.ui.watermark

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pentax.k1sync.image.CropStraightenConfig
import com.pentax.k1sync.image.ImageCropStraighten
import com.pentax.k1sync.spektra.FilmCategory
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
import com.pentax.k1sync.viewmodel.StudioCameraPickerMode
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FilmStockCatalogOverlay(viewModel: MainViewModel) {
    val state by viewModel.watermarkState.collectAsState()
    if (!state.showAllStocksPicker) return

    BackHandler { viewModel.setShowAllStocksPicker(false) }

    var searchQuery by remember { mutableStateOf("") }
    var filterCat by remember { mutableStateOf<FilmCategory?>(null) }

    val filtered = remember(state.allFilmStocks, searchQuery, filterCat) {
        val q = searchQuery.trim().lowercase()
        state.allFilmStocks.filter { st ->
            (filterCat == null || st.category == filterCat) &&
                (q.isBlank() || st.fullName.lowercase().contains(q) || st.shortName.lowercase().contains(q) || st.iso.toString().contains(q))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(ElevatedSurface).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("全部实测 81 波长胶片库", color = InkPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("共 ${state.allFilmStocks.size} 卷 · 来自 SpektraLab 光谱库", color = InkSecondary, fontSize = 11.sp)
            }
            IconButton(onClick = { viewModel.setShowAllStocksPicker(false) }) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = InkPrimary)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索胶片名 / 品牌 / ISO (如 Portra, Velvia, Eterna, 800)", fontSize = 12.sp, color = InkSecondary) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = InkSecondary, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(11.dp),
                textStyle = TextStyle(color = InkPrimary, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PentaxGreen, unfocusedBorderColor = HairlineBorder,
                    focusedContainerColor = MatteCard, unfocusedContainerColor = MatteCard
                ),
                modifier = Modifier.fillMaxWidth()
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    val sel = filterCat == null
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) PentaxGreen.copy(alpha = 0.2f) else MatteCard)
                            .border(0.5.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { filterCat = null }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("全部 (${state.allFilmStocks.size})", color = if (sel) PentaxGreen else InkPrimary, fontSize = 11.sp) }
                }
                items(FilmCategory.entries) { cat ->
                    val sel = filterCat == cat
                    val cnt = state.allFilmStocks.count { it.category == cat }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) PentaxGreen.copy(alpha = 0.2f) else MatteCard)
                            .border(0.5.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { filterCat = cat }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("${cat.label} ($cnt)", color = if (sel) PentaxGreen else InkPrimary, fontSize = 11.sp) }
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filtered, key = { it.id }) { stock ->
                val active = state.spektraParams.filmStockId == stock.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (active) PentaxGreen.copy(alpha = 0.14f) else MatteCard)
                        .border(if (active) 1.dp else 0.5.dp, if (active) PentaxGreen else HairlineBorder, RoundedCornerShape(11.dp))
                        .clickable { viewModel.selectSpektraFilmStock(stock) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(Color(stock.swatchHex)))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(stock.fullName, color = if (active) PentaxGreen else InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${stock.category.label} · ISO ${stock.iso}" + if (stock.isTungsten) " · 3200K 灯光片" else "", color = if (stock.isTungsten) WarmAmber else InkSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (active) {
                        Text("当前", color = PentaxGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


/**
 * 60fps WYSIWYG Interactive Crop & Straighten Stage:
 * Shows the full uncropped photo with a dimmed semi-transparent outer matte and bright target-aspect window.
 * Supports 1-finger drag to frame subject + 2-finger pinch to zoom, mathematically clamped to zero black corners.
 */
@Composable
internal fun InteractiveCropStage(
    bitmap: android.graphics.Bitmap,
    cropConfig: CropStraightenConfig,
    onPanZoomChanged: (zoom: Float, panX: Float, panY: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestCfg by rememberUpdatedState(cropConfig)
    val latestCallback by rememberUpdatedState(onPanZoomChanged)

    BoxWithConstraints(
        modifier = modifier
            .background(Color(0xFF070709))
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val stageW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val stageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        val g = remember(bitmap.width, bitmap.height, cropConfig) {
            ImageCropStraighten.computeGeometry(bitmap.width, bitmap.height, cropConfig)
        }
        val cropRatio = (g.cropW / g.cropH.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
        val availW = stageW * 0.84f
        val availH = stageH * 0.84f
        val (frameW, frameH) = if (availW / availH > cropRatio) {
            (availH * cropRatio) to availH
        } else {
            availW to (availW / cropRatio)
        }

        val pxScale = frameW / g.cropW.coerceAtLeast(1f)
        val rawDrawW = bitmap.width * pxScale
        val rawDrawH = bitmap.height * pxScale
        val angleRad = Math.toRadians(g.angleDeg.toDouble())
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)

        val shiftBaseX = cropConfig.panX * g.maxShiftX * pxScale
        val shiftBaseY = cropConfig.panY * g.maxShiftY * pxScale
        val tx = (shiftBaseX * cosA - shiftBaseY * sinA).toFloat()
        val ty = (shiftBaseX * sinA + shiftBaseY * cosA).toFloat()
        val totalRot = ((cropConfig.quarterTurns % 4) * 90f) + g.angleDeg

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap.width, bitmap.height, frameW) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val cfg = latestCfg
                        val curG = ImageCropStraighten.computeGeometry(bitmap.width, bitmap.height, cfg)
                        val curShiftX = cfg.panX * curG.maxShiftX
                        val curShiftY = cfg.panY * curG.maxShiftY

                        val nextZoom = (cfg.cropZoom * zoom).coerceIn(1f, 4f)
                        val nextG = ImageCropStraighten.computeGeometry(
                            bitmap.width, bitmap.height, cfg.copy(cropZoom = nextZoom)
                        )
                        val nextScale = frameW / nextG.cropW.coerceAtLeast(1f)
                        val rad = Math.toRadians(nextG.angleDeg.toDouble())
                        val c = cos(rad)
                        val s = sin(rad)
                        val dBaseX = ((pan.x * c + pan.y * s) / nextScale).toFloat()
                        val dBaseY = ((-pan.x * s + pan.y * c) / nextScale).toFloat()

                        val nextPanX = if (nextG.maxShiftX > 0.5f) {
                            ((curShiftX + dBaseX) / nextG.maxShiftX).coerceIn(-1f, 1f)
                        } else 0f
                        val nextPanY = if (nextG.maxShiftY > 0.5f) {
                            ((curShiftY + dBaseY) / nextG.maxShiftY).coerceIn(-1f, 1f)
                        } else 0f

                        latestCallback(nextZoom, nextPanX, nextPanY)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Uncropped full image moving & scaling smoothly beneath the crop window
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Interactive Crop Photo",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .requiredSize(
                        width = with(density) { rawDrawW.toDp() },
                        height = with(density) { rawDrawH.toDp() }
                    )
                    .graphicsLayer(
                        rotationZ = totalRot,
                        translationX = tx,
                        translationY = ty
                    )
            )

            // Dimmed outer matte + crisp white crop border + rule-of-thirds & center crosshair
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = (size.width - frameW) * 0.5f
                val top = (size.height - frameH) * 0.5f
                val right = left + frameW
                val bottom = top + frameH
                val scrim = Color(0xA6000000)

                // 4 dimmed rectangles outside the active crop frame
                drawRect(scrim, Offset(0f, 0f), Size(size.width, top))
                drawRect(scrim, Offset(0f, bottom), Size(size.width, size.height - bottom))
                drawRect(scrim, Offset(0f, top), Size(left, frameH))
                drawRect(scrim, Offset(right, top), Size(size.width - right, frameH))

                // Rule of thirds inside crop frame
                val gridCol = Color(0x66FFFFFF)
                for (i in 1..2) {
                    val gx = left + frameW * i / 3f
                    val gy = top + frameH * i / 3f
                    drawLine(gridCol, Offset(gx, top), Offset(gx, bottom), 1.1f)
                    drawLine(gridCol, Offset(left, gy), Offset(right, gy), 1.1f)
                }

                // Crisp border & Pentax green corner handles
                drawRect(
                    color = Color.White.copy(alpha = 0.9f),
                    topLeft = Offset(left, top),
                    size = Size(frameW, frameH),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f)
                )
                val arm = 22f
                val sw = 4.5f
                drawLine(PentaxGreen, Offset(left, top), Offset(left + arm, top), sw)
                drawLine(PentaxGreen, Offset(left, top), Offset(left, top + arm), sw)
                drawLine(PentaxGreen, Offset(right, top), Offset(right - arm, top), sw)
                drawLine(PentaxGreen, Offset(right, top), Offset(right, top + arm), sw)
                drawLine(PentaxGreen, Offset(left, bottom), Offset(left + arm, bottom), sw)
                drawLine(PentaxGreen, Offset(left, bottom), Offset(left, bottom - arm), sw)
                drawLine(PentaxGreen, Offset(right, bottom), Offset(right - arm, bottom), sw)
                drawLine(PentaxGreen, Offset(right, bottom), Offset(right, bottom - arm), sw)
            }

            // Top-left interactive drag hint
            Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                PreviewMicroBadge(
                    text = "拖动照片调整构图 · ${cropConfig.aspectRatio.label} · ${"%.1f".format(cropConfig.cropZoom)}x",
                    tint = PentaxGreen
                )
            }
        }
    }
}

/**
 * Full-Screen 8x Pinch-to-Zoom & Pan Darkroom Inspector Overlay.
 * Supports 1x -> 3x -> 6x double-tap, smooth pinch up to 8x, 1:1 HD refresh (2800px), and hold-to-compare original!
 */
@Composable
fun WatermarkFullscreenOverlay(viewModel: MainViewModel) {
    val state by viewModel.watermarkState.collectAsState()
    if (!state.fullscreenPreview || state.previewBitmap == null) return

    BackHandler { viewModel.setWatermarkFullscreen(false) }

    var zoom by remember(state.fullscreenPreview) { mutableFloatStateOf(1f) }
    var pan by remember(state.fullscreenPreview) { mutableStateOf(Offset.Zero) }
    var holdCompareOriginal by remember { mutableStateOf(false) }
    var splitWiperMode by remember(state.fullscreenPreview) { mutableStateOf(false) }
    var splitFraction by remember(state.fullscreenPreview) { mutableFloatStateOf(0.5f) }

    val gradedBitmap = state.fullscreenHighResBitmap ?: state.previewBitmap!!
    val originalBitmap = state.sourceBitmap
    val wiperBeforeBmp = state.croppedOriginalBitmap ?: originalBitmap
    val wiperAfterBmp = state.gradedNoWatermarkBitmap ?: gradedBitmap
    val activeBitmap = if (holdCompareOriginal && originalBitmap != null) originalBitmap else gradedBitmap

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElevatedSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = when {
                        splitWiperMode -> "分屏"
                        holdCompareOriginal -> "原图"
                        else -> "${"%.1f".format(zoom)}x"
                    },
                    color = if (holdCompareOriginal || splitWiperMode) WarmAmber else PentaxGreen,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${activeBitmap.width}×${activeBitmap.height}",
                    color = InkSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (originalBitmap != null) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (splitWiperMode) PentaxGreen.copy(alpha = 0.22f) else MatteCard)
                            .border(0.5.dp, if (splitWiperMode) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                            .clickable {
                                splitWiperMode = !splitWiperMode
                                if (splitWiperMode) {
                                    holdCompareOriginal = false
                                    zoom = 1f
                                    pan = Offset.Zero
                                }
                            }
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.VerticalSplit, null, tint = if (splitWiperMode) PentaxGreen else InkPrimary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("分屏", color = if (splitWiperMode) PentaxGreen else InkPrimary, fontSize = 11.sp, fontWeight = if (splitWiperMode) FontWeight.Bold else FontWeight.Normal)
                    }

                    if (!splitWiperMode) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (holdCompareOriginal) WarmAmber.copy(alpha = 0.25f) else MatteCard)
                                .border(0.5.dp, if (holdCompareOriginal) WarmAmber else HairlineBorder, RoundedCornerShape(8.dp))
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            holdCompareOriginal = true
                                            tryAwaitRelease()
                                            holdCompareOriginal = false
                                        }
                                    )
                                }
                                .padding(horizontal = 7.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Compare, null, tint = WarmAmber, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("对比", color = InkPrimary, fontSize = 11.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (state.fullscreenHighResBitmap != null) PentaxGreen.copy(alpha = 0.2f) else MatteCard)
                        .border(0.5.dp, if (state.fullscreenHighResBitmap != null) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                        .clickable(enabled = !state.isLoadingFullscreenHd) { viewModel.refreshFullscreenHighRes() }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isLoadingFullscreenHd) {
                        CircularProgressIndicator(color = PentaxGreen, strokeWidth = 1.8.dp, modifier = Modifier.size(13.dp))
                    } else {
                        Icon(Icons.Rounded.Hd, null, tint = PentaxGreen, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(3.dp))
                    Text(if (state.fullscreenHighResBitmap != null) "已高清" else "1:1", color = PentaxGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                if (zoom > 1.05f && !splitWiperMode) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MatteCard)
                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { zoom = 1f; pan = Offset.Zero }
                            .padding(horizontal = 7.dp, vertical = 5.dp)
                    ) {
                        Text("复位", color = InkPrimary, fontSize = 11.sp)
                    }
                }

                IconButton(onClick = { viewModel.setWatermarkFullscreen(false) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        if (splitWiperMode && wiperBeforeBmp != null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            splitFraction = (splitFraction + dragAmount / w).coerceIn(0.06f, 0.94f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = wiperAfterBmp.asImageBitmap(),
                    contentDescription = "Graded After",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
                Image(
                    bitmap = wiperBeforeBmp.asImageBitmap(),
                    contentDescription = "Original Before",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .drawWithContent {
                            clipRect(right = size.width * splitFraction) {
                                this@drawWithContent.drawContent()
                            }
                        }
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val x = size.width * splitFraction
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.5f)
                    drawCircle(Color(0xDD0B0B0D), radius = 22f, center = Offset(x, size.height * 0.5f))
                    drawCircle(PentaxGreen, radius = 22f, center = Offset(x, size.height * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                    drawLine(PentaxGreen, Offset(x - 9f, size.height * 0.5f), Offset(x - 3f, size.height * 0.5f), 2.5f)
                    drawLine(PentaxGreen, Offset(x + 3f, size.height * 0.5f), Offset(x + 9f, size.height * 0.5f), 2.5f)
                }
                Box(modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    PreviewMicroBadge("原图 BEFORE", WarmAmber)
                }
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    PreviewMicroBadge("成片 AFTER", PentaxGreen)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                zoom = when {
                                    zoom < 1.5f -> 3.0f
                                    zoom < 4.5f -> 6.0f
                                    else -> 1.0f
                                }
                                if (zoom <= 1.05f) pan = Offset.Zero
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            val nextZoom = (zoom * zoomChange).coerceIn(1f, 8f)
                            zoom = nextZoom
                            pan = if (nextZoom > 1.02f) {
                                val maxX = size.width * (nextZoom - 1f) * 0.5f
                                val maxY = size.height * (nextZoom - 1f) * 0.5f
                                Offset(
                                    (pan.x + panChange.x).coerceIn(-maxX, maxX),
                                    (pan.y + panChange.y).coerceIn(-maxY, maxY)
                                )
                            } else Offset.Zero
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = activeBitmap.asImageBitmap(),
                    contentDescription = "Zoomable Darkroom Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = pan.x,
                            translationY = pan.y
                        )
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElevatedSurface)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (splitWiperMode) "左右拖动中间绿环分割线，实时对比原图与成片差异" else "双指捏合最高放大 8x · 双击 1x/3x/6x · 点「分屏」可拉杆对比",
                color = InkSecondary,
                fontSize = 10.sp
            )
        }
    }
}


@Composable
internal fun StudioCameraPickerOverlay(
    viewModel: MainViewModel,
    onPickLocalSingle: () -> Unit,
    onPickLocalRaw: () -> Unit,
    onPickLocalBatch: () -> Unit
) {
    val wmState by viewModel.watermarkState.collectAsState()
    val mode = wmState.cameraPickerMode
    if (mode == StudioCameraPickerMode.NONE) return

    BackHandler { viewModel.closeStudioCameraPicker() }

    val allPhotos by viewModel.studioPickerPhotos.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    var starredOnly by remember(mode) { mutableStateOf(false) }
    var selectedBatchIds by remember(mode) { mutableStateOf(emptySet<String>()) }

    val filteredPhotos = remember(allPhotos, mode, starredOnly) {
        val byType = if (mode == StudioCameraPickerMode.SINGLE_RAW) {
            allPhotos.filter { it.hasAnyRaw }
        } else {
            allPhotos
        }
        if (starredOnly) byType.filter { it.isStarred } else byType
    }

    val titleText = when (mode) {
        StudioCameraPickerMode.SINGLE_PHOTO -> "从 K-1 相机图库选图"
        StudioCameraPickerMode.SINGLE_RAW -> "从 K-1 选择 14-bit RAW"
        StudioCameraPickerMode.BATCH_MULTI -> "从 K-1 多选批量暗房出片"
        StudioCameraPickerMode.NONE -> ""
    }
    val localBtnText = when (mode) {
        StudioCameraPickerMode.SINGLE_PHOTO -> "手机相册"
        StudioCameraPickerMode.SINGLE_RAW -> "本地 RAW"
        StudioCameraPickerMode.BATCH_MULTI -> "手机多图"
        StudioCameraPickerMode.NONE -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElevatedSurface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(titleText, color = InkPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (mode == StudioCameraPickerMode.BATCH_MULTI) "已选 ${selectedBatchIds.size} 张 · 共 ${filteredPhotos.size} 张可选"
                    else "点击照片直接载入暗房 · 共 ${filteredPhotos.size} 张",
                    color = InkSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MatteCard)
                        .border(0.6.dp, PentaxGreen.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .clickable {
                            viewModel.closeStudioCameraPicker()
                            when (mode) {
                                StudioCameraPickerMode.SINGLE_PHOTO -> onPickLocalSingle()
                                StudioCameraPickerMode.SINGLE_RAW -> onPickLocalRaw()
                                StudioCameraPickerMode.BATCH_MULTI -> onPickLocalBatch()
                                StudioCameraPickerMode.NONE -> Unit
                            }
                        }
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    Text(localBtnText, color = PentaxGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { viewModel.closeStudioCameraPicker() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = InkPrimary)
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElevatedSurface.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (!starredOnly) PentaxGreen.copy(alpha = 0.18f) else ObsidianBg)
                        .border(0.5.dp, if (!starredOnly) PentaxGreen else HairlineBorder, RoundedCornerShape(7.dp))
                        .clickable { starredOnly = false }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("全部", color = if (!starredOnly) PentaxGreen else InkSecondary, fontSize = 11.sp)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (starredOnly) WarmAmber.copy(alpha = 0.2f) else ObsidianBg)
                        .border(0.5.dp, if (starredOnly) WarmAmber else HairlineBorder, RoundedCornerShape(7.dp))
                        .clickable { starredOnly = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Star, null, tint = if (starredOnly) WarmAmber else InkSecondary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("仅看星标精选", color = if (starredOnly) WarmAmber else InkSecondary, fontSize = 11.sp)
                }
            }
            if (mode == StudioCameraPickerMode.BATCH_MULTI && filteredPhotos.isNotEmpty()) {
                Text(
                    text = if (selectedBatchIds.size == filteredPhotos.size) "取消全选" else "全选 (${filteredPhotos.size})",
                    color = PentaxGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        selectedBatchIds = if (selectedBatchIds.size == filteredPhotos.size) {
                            emptySet()
                        } else {
                            filteredPhotos.map { it.uniqueId }.toSet()
                        }
                    }
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        if (filteredPhotos.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (mode == StudioCameraPickerMode.SINGLE_RAW) "当前相机列表中没有 PEF/DNG RAW 照片" else "当前筛选下暂无相机照片",
                        color = InkSecondary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "可点击右上角「$localBtnText」从手机选择",
                        color = InkSecondary.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                gridItems(filteredPhotos, key = { it.uniqueId }) { photo ->
                    val bmp = thumbnails[photo.uniqueId]
                        ?: photo.companionRaw?.let { thumbnails[it.uniqueId] }
                    val isSelected = selectedBatchIds.contains(photo.uniqueId)
                    LaunchedEffect(photo.uniqueId, starredOnly, mode) {
                        if (bmp == null) viewModel.requestThumbnail(photo)
                    }
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MatteCard)
                            .clickable {
                                if (mode == StudioCameraPickerMode.BATCH_MULTI) {
                                    selectedBatchIds = if (isSelected) selectedBatchIds - photo.uniqueId else selectedBatchIds + photo.uniqueId
                                } else {
                                    viewModel.pickCameraPhotoForStudio(photo, asRaw = mode == StudioCameraPickerMode.SINGLE_RAW)
                                }
                            }
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = photo.file,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Rounded.PhotoLibrary, null, tint = InkSecondary.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
                                    Text(
                                        text = photo.baseName,
                                        color = InkSecondary,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (photo.isStarred) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xB8000000)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Star, null, tint = WarmAmber, modifier = Modifier.size(10.dp))
                            }
                        }

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(PentaxGreen.copy(alpha = 0.22f))
                                    .border(2.dp, PentaxGreen, RoundedCornerShape(4.dp))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(5.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(PentaxGreen),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Check, null, tint = Color(0xFF04170B), modifier = Modifier.size(13.dp))
                            }
                        }

                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color(0xB80B0B0D))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = photo.baseName,
                                color = Color.White,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (photo.hasAnyRaw) {
                                Text(
                                    text = if (photo.hasRawCompanion) "R+J" else "RAW",
                                    color = WarmAmber,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (mode == StudioCameraPickerMode.BATCH_MULTI) {
            HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已勾选 ${selectedBatchIds.size} 张", color = InkPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selectedBatchIds.isNotEmpty()) PentaxGreen else MatteCard)
                        .clickable(enabled = selectedBatchIds.isNotEmpty()) {
                            val chosen = filteredPhotos.filter { selectedBatchIds.contains(it.uniqueId) }
                            viewModel.batchProcessCameraPhotoList(chosen)
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = "批量套暗房出片 (${selectedBatchIds.size})",
                        color = if (selectedBatchIds.isNotEmpty()) Color(0xFF04170B) else InkSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
