package com.pentax.k1sync.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pentax.k1sync.model.FocusPeakingColor
import com.pentax.k1sync.model.GridOverlayMode
import com.pentax.k1sync.model.PeakingSensitivity
import com.pentax.k1sync.model.RemoteShotMode
import com.pentax.k1sync.model.ShootingParams
import com.pentax.k1sync.ui.components.ConnectionPillBadge
import com.pentax.k1sync.ui.components.SegmentedPillRow
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
import com.pentax.k1sync.util.ExposureCalculator
import com.pentax.k1sync.util.NdFilterOption
import com.pentax.k1sync.util.SkyDeclination
import com.pentax.k1sync.viewmodel.AppTab
import com.pentax.k1sync.viewmodel.FocusStatus
import com.pentax.k1sync.viewmodel.MainViewModel

private enum class CalcMode(val label: String) {
    ND("ND 减光"),
    ASTRO("星空 NPF"),
    HYPER("超焦距"),
    SOLAR("蓝调日出")
}

@Composable
fun RemoteScreen(viewModel: MainViewModel) {
    val cameraInfo by viewModel.cameraInfo.collectAsState()
    val params by viewModel.shootingParams.collectAsState()
    val liveFrame by viewModel.liveViewFrame.collectAsState()
    val liveActive by viewModel.liveViewActive.collectAsState()
    val liveError by viewModel.liveViewError.collectAsState()
    val assistCfg by viewModel.liveAssistConfig.collectAsState()
    val manualLens by viewModel.manualLensMode.collectAsState()
    val smartEdgeSnap by viewModel.smartEdgeSnap.collectAsState()
    val focusHud by viewModel.touchFocusHud.collectAsState()
    val tetherEnabled by viewModel.tetherEnabled.collectAsState()
    val gridMode by viewModel.gridMode.collectAsState()
    val shotMode by viewModel.remoteShotMode.collectAsState()
    val intervalState by viewModel.intervalState.collectAsState()
    val bulbState by viewModel.bulbState.collectAsState()
    val aebState by viewModel.aebState.collectAsState()
    val astroRedMode by viewModel.astroRedMode.collectAsState()
    val volumeShutterEnabled by viewModel.volumeShutterEnabled.collectAsState()

    var activeDialKey by remember { mutableStateOf<String?>(null) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // ND & Astro Calculator local states
    var calcMode by remember { mutableStateOf(CalcMode.ND) }
    var baseShutterIdx by remember { mutableIntStateOf(6) } // default 1/60s
    var primaryNd by remember { mutableStateOf(NdFilterOption.ND1000) }
    var secondaryNd by remember { mutableStateOf(NdFilterOption.NONE) }
    var astroFocal by remember { mutableIntStateOf(15) }
    var astroAperture by remember { mutableDoubleStateOf(2.8) }
    var astroDecl by remember { mutableStateOf(SkyDeclination.EQUATOR) }
    var hyperFocalMm by remember { mutableIntStateOf(24) }
    var hyperAperture by remember { mutableDoubleStateOf(8.0) }
    var hyperDistM by remember { mutableDoubleStateOf(3.0) }
    var solarCityIdx by remember { mutableIntStateOf(0) }
    var showFieldCalc by remember { mutableStateOf(false) }

    LaunchedEffect(shotMode) {
        if (shotMode == RemoteShotMode.BULB) {
            showFieldCalc = true
        }
    }

    fun cycleZoom() {
        zoomLevel = when {
            zoomLevel < 1.5f -> 2f
            zoomLevel < 3f -> 4f
            else -> 1f
        }
        if (zoomLevel <= 1.05f) panOffset = Offset.Zero
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElevatedSurface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("无线遥控与光学辅助", color = InkPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (astroRedMode) PentaxRed.copy(alpha = 0.25f) else MatteCard)
                        .border(0.6.dp, if (astroRedMode) PentaxRed else HairlineBorder, RoundedCornerShape(8.dp))
                        .clickable { viewModel.toggleAstroRedMode() }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Bedtime, null, tint = if (astroRedMode) PentaxRed else InkSecondary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("暗夜红", color = if (astroRedMode) PentaxRed else InkSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                ConnectionPillBadge(cameraInfo) { viewModel.selectTab(AppTab.SYNC) }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        // Clipped 3:2 Live View Monitor with Focus Peaking + Zebra HUD
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 2f)
                .background(Color.Black)
                .clipToBounds()
                .pointerInput(manualLens, zoomLevel, panOffset) {
                    detectTapGestures(
                        onTap = { tap ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            // Inverse-map screen tap through graphicsLayer zoomLevel + panOffset back to exact sensor coordinates!
                            val sensorX = w * 0.5f + (tap.x - w * 0.5f - panOffset.x) / zoomLevel
                            val sensorY = h * 0.5f + (tap.y - h * 0.5f - panOffset.y) / zoomLevel
                            viewModel.touchFocusAtNormalized(
                                xFraction = (sensorX / w).coerceIn(0.05f, 0.95f),
                                yFraction = (sensorY / h).coerceIn(0.05f, 0.95f)
                            )
                        }
                    )
                }
                .pointerInput(zoomLevel) {
                    if (zoomLevel > 1.05f) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val maxX = size.width * (zoomLevel - 1f) / 2f
                            val maxY = size.height * (zoomLevel - 1f) / 2f
                            panOffset = Offset(
                                (panOffset.x + dragAmount.x).coerceIn(-maxX, maxX),
                                (panOffset.y + dragAmount.y).coerceIn(-maxY, maxY)
                            )
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (liveFrame != null) {
                Image(
                    bitmap = liveFrame!!.asImageBitmap(),
                    contentDescription = "Live View",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomLevel, scaleY = zoomLevel,
                            translationX = panOffset.x, translationY = panOffset.y
                        )
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    if (liveActive && liveError == null) {
                        CircularProgressIndicator(color = PentaxGreen, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "正在连接 K-1 Live View 视频流...",
                            color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "若长时间无画面，请先在 K-1 机身菜单关闭「构图微调 (Composition Adjustment)」",
                            color = InkSecondary, fontSize = 11.sp, textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = liveError ?: if (cameraInfo.connected) "准备就绪，点击下方开启实时取景" else "请先在「机身」页连接 PENTAX K-1",
                            color = if (liveError != null) WarmAmber else InkSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )
                        if (cameraInfo.connected) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "提示：K-1 一代需关闭机身「构图微调」才能正常无线实时取景",
                                color = InkSecondary, fontSize = 10.sp, textAlign = TextAlign.Center
                            )
                        }
                    }
                    if (cameraInfo.connected) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(PentaxGreen)
                                .clickable { viewModel.startLiveView() }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                if (liveActive) "重新连接 Live View" else "开启 Live View",
                                color = Color(0xFF04170B), fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (gridMode != GridOverlayMode.NONE) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Color(0x55FFFFFF)
                    when (gridMode) {
                        GridOverlayMode.THIRDS -> {
                            for (i in 1..2) {
                                val x = size.width * i / 3f; val y = size.height * i / 3f
                                drawLine(c, Offset(x, 0f), Offset(x, size.height), 1.2f)
                                drawLine(c, Offset(0f, y), Offset(size.width, y), 1.2f)
                            }
                        }
                        GridOverlayMode.CROSSHAIR -> {
                            val cx = size.width / 2f; val cy = size.height / 2f
                            drawLine(PentaxGreen.copy(alpha = 0.75f), Offset(cx - 24f, cy), Offset(cx + 24f, cy), 1.8f)
                            drawLine(PentaxGreen.copy(alpha = 0.75f), Offset(cx, cy - 24f), Offset(cx, cy + 24f), 1.8f)
                        }
                        GridOverlayMode.GOLDEN -> {
                            listOf(0.382f, 0.618f).forEach { r ->
                                drawLine(c, Offset(size.width * r, 0f), Offset(size.width * r, size.height), 1.2f)
                                drawLine(c, Offset(0f, size.height * r), Offset(size.width, size.height * r), 1.2f)
                            }
                        }
                        GridOverlayMode.MASK_3_4, GridOverlayMode.MASK_4_5, GridOverlayMode.MASK_235 -> {
                            val targetRatio = gridMode.maskRatio ?: 1.5f
                            val viewRatio = size.width / size.height.coerceAtLeast(1f)
                            val (fw, fh) = if (viewRatio > targetRatio) {
                                (size.height * targetRatio) to size.height
                            } else {
                                size.width to (size.width / targetRatio)
                            }
                            val left = (size.width - fw) * 0.5f
                            val top = (size.height - fh) * 0.5f
                            val scrim = Color(0x99000000)
                            if (left > 1f) {
                                drawRect(scrim, Offset.Zero, Size(left, size.height))
                                drawRect(scrim, Offset(left + fw, 0f), Size(left, size.height))
                            }
                            if (top > 1f) {
                                drawRect(scrim, Offset.Zero, Size(size.width, top))
                                drawRect(scrim, Offset(0f, top + fh), Size(size.width, top))
                            }
                            drawRect(Color(0xCCFFFFFF), Offset(left, top), Size(fw, fh), style = Stroke(width = 1.6f))
                        }
                        GridOverlayMode.NONE -> Unit
                    }
                }
            }

            if (focusHud.status != FocusStatus.IDLE) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width * 0.5f + (focusHud.xFraction * size.width - size.width * 0.5f) * zoomLevel + panOffset.x
                    val cy = size.height * 0.5f + (focusHud.yFraction * size.height - size.height * 0.5f) * zoomLevel + panOffset.y
                    val half = 28f
                    val arm = 11f
                    val col = when (focusHud.status) {
                        FocusStatus.FOCUSING -> Color.White
                        FocusStatus.LOCKED -> PentaxGreen
                        FocusStatus.FAILED -> PentaxRed
                        FocusStatus.IDLE -> Color.Transparent
                    }
                    val sw = 3f
                    // 4 pro camera viewfinder corner brackets
                    drawLine(col, Offset(cx - half, cy - half), Offset(cx - half + arm, cy - half), sw)
                    drawLine(col, Offset(cx - half, cy - half), Offset(cx - half, cy - half + arm), sw)
                    drawLine(col, Offset(cx + half, cy - half), Offset(cx + half - arm, cy - half), sw)
                    drawLine(col, Offset(cx + half, cy - half), Offset(cx + half, cy - half + arm), sw)
                    drawLine(col, Offset(cx - half, cy + half), Offset(cx - half + arm, cy + half), sw)
                    drawLine(col, Offset(cx - half, cy + half), Offset(cx - half, cy + half - arm), sw)
                    drawLine(col, Offset(cx + half, cy + half), Offset(cx + half - arm, cy + half), sw)
                    drawLine(col, Offset(cx + half, cy + half), Offset(cx + half, cy + half - arm), sw)
                    drawCircle(col, radius = 2.5f, center = Offset(cx, cy))
                }
            }

            // Top-Left Live Status Badge
            if (liveActive) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xA6000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(PentaxGreen))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (zoomLevel > 1.05f) "LV · ${zoomLevel.toInt()}X 拖动检焦" else "LV · ${params.exposureMode}",
                        color = InkPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Top-Right Grid + 1x/2x/4x Zoom Pill
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xA6000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable {
                            val all = GridOverlayMode.entries
                            viewModel.setGridMode(all[(all.indexOf(gridMode) + 1) % all.size])
                        }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.GridOn, "网格", tint = if (gridMode != GridOverlayMode.NONE) PentaxGreen else Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(gridMode.label, color = Color.White, fontSize = 10.sp)
                }
                Box(modifier = Modifier.width(0.5.dp).height(14.dp).background(Color(0x44FFFFFF)))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { cycleZoom() }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.ZoomIn, "放大检焦", tint = if (zoomLevel > 1.05f) PentaxGreen else Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${zoomLevel.toInt()}x", color = if (zoomLevel > 1.05f) PentaxGreen else Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Bottom-Left Quick Optical Assist Pills: Focus Peaking + Highlight Zebra
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (assistCfg.peakingEnabled) Color(assistCfg.peakingColor.argb).copy(alpha = 0.25f) else Color(0xA6000000))
                        .border(
                            0.7.dp,
                            if (assistCfg.peakingEnabled) Color(assistCfg.peakingColor.argb) else Color(0x44FFFFFF),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { viewModel.toggleFocusPeaking() }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.CenterFocusStrong,
                        contentDescription = "峰值对焦",
                        tint = if (assistCfg.peakingEnabled) Color(assistCfg.peakingColor.argb) else Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (assistCfg.peakingEnabled) "峰值·${assistCfg.peakingSensitivity.label}" else "峰值",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = if (assistCfg.peakingEnabled) FontWeight.Bold else FontWeight.Normal
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (assistCfg.zebraEnabled) WarmAmber.copy(alpha = 0.25f) else Color(0xA6000000))
                        .border(
                            0.7.dp,
                            if (assistCfg.zebraEnabled) WarmAmber else Color(0x44FFFFFF),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { viewModel.toggleZebraStripes() }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Exposure,
                        contentDescription = "高光斑马纹",
                        tint = if (assistCfg.zebraEnabled) WarmAmber else Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (assistCfg.zebraEnabled) "斑马 ${assistCfg.zebraThresholdPercent}%" else "斑马纹",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = if (assistCfg.zebraEnabled) FontWeight.Bold else FontWeight.Normal
                    )
                }

                // Smart High-Contrast Edge-Snap AF toggle
                if (!manualLens) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (smartEdgeSnap) PentaxGreen.copy(alpha = 0.22f) else Color(0xA6000000))
                            .border(0.7.dp, if (smartEdgeSnap) PentaxGreen else Color(0x44FFFFFF), RoundedCornerShape(8.dp))
                            .clickable { viewModel.toggleSmartEdgeSnap() }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.FilterCenterFocus,
                            contentDescription = "高反差边缘吸附",
                            tint = if (smartEdgeSnap) PentaxGreen else Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = if (smartEdgeSnap) "边缘吸附" else "原位点焦",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = if (smartEdgeSnap) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Optical Assist Fine-Tuning Ribbon (auto-appears when Peaking or Zebra is ON!)
        if (assistCfg.peakingEnabled || assistCfg.zebraEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15161B))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (assistCfg.peakingEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("峰值:", color = InkSecondary, fontSize = 10.sp)
                        FocusPeakingColor.entries.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color(c.argb))
                                    .border(
                                        if (assistCfg.peakingColor == c) 2.dp else 0.5.dp,
                                        if (assistCfg.peakingColor == c) Color.White else Color.Transparent,
                                        CircleShape
                                    )
                                    .clickable { viewModel.setPeakingColor(c) }
                            )
                        }
                        PeakingSensitivity.entries.forEach { s ->
                            val sel = assistCfg.peakingSensitivity == s
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) PentaxGreen.copy(alpha = 0.22f) else MatteCard)
                                    .clickable { viewModel.setPeakingSensitivity(s) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(s.label, color = if (sel) PentaxGreen else InkSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
                if (assistCfg.zebraEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("阈值:", color = InkSecondary, fontSize = 10.sp)
                        listOf(88, 92, 96).forEach { pct ->
                            val sel = assistCfg.zebraThresholdPercent == pct
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) WarmAmber.copy(alpha = 0.22f) else MatteCard)
                                    .clickable { viewModel.setZebraThreshold(pct) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text("$pct%", color = if (sel) WarmAmber else InkSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
        }

        // Parameter Dial Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElevatedSurface)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ExposureDialCell("Av", "F${params.av}", activeDialKey == "av", Modifier.weight(1f)) { activeDialKey = if (activeDialKey == "av") null else "av" }
            ExposureDialCell("Tv", ShootingParams.formatTvDisplay(params.tv), activeDialKey == "tv", Modifier.weight(1f)) { activeDialKey = if (activeDialKey == "tv") null else "tv" }
            ExposureDialCell("ISO", params.sv, activeDialKey == "sv", Modifier.weight(1f)) { activeDialKey = if (activeDialKey == "sv") null else "sv" }
            ExposureDialCell("EV", params.xv, activeDialKey == "xv", Modifier.weight(1f)) { activeDialKey = if (activeDialKey == "xv") null else "xv" }
            ExposureDialCell("WB", ShootingParams.formatWbDisplay(params.wbMode), activeDialKey == "wbMode", Modifier.weight(1f)) { activeDialKey = if (activeDialKey == "wbMode") null else "wbMode" }
        }

        activeDialKey?.let { key ->
            val options = when (key) {
                "av" -> params.avList; "tv" -> params.tvList; "sv" -> params.svList; "xv" -> params.xvList; "wbMode" -> params.wbList; else -> emptyList()
            }
            val currentVal = when (key) {
                "av" -> params.av; "tv" -> params.tv; "sv" -> params.sv; "xv" -> params.xv; "wbMode" -> params.wbMode; else -> ""
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().background(Color(0xFF161A18))
            ) {
                items(options) { opt ->
                    val label = when (key) {
                        "av" -> "F$opt"; "tv" -> ShootingParams.formatTvDisplay(opt); "wbMode" -> ShootingParams.formatWbDisplay(opt); else -> opt
                    }
                    val chosen = opt == currentVal
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (chosen) PentaxGreen else MatteCard)
                            .border(0.5.dp, if (chosen) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { viewModel.updateCameraParam(key, opt) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(label, color = if (chosen) Color(0xFF04170B) else InkPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SegmentedPillRow(
                items = RemoteShotMode.entries,
                selectedItem = shotMode,
                labelProvider = { it.label },
                onSelect = { viewModel.setRemoteShotMode(it) },
                modifier = Modifier.fillMaxWidth()
            )

            when (shotMode) {
                RemoteShotMode.SINGLE -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (focusHud.message.isNotBlank()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MatteCard)
                                    .border(
                                        0.5.dp,
                                        when (focusHud.status) {
                                            FocusStatus.LOCKED -> PentaxGreen.copy(alpha = 0.6f)
                                            FocusStatus.FAILED -> PentaxRed.copy(alpha = 0.6f)
                                            else -> HairlineBorder
                                        },
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = focusHud.message,
                                    color = when (focusHud.status) {
                                        FocusStatus.LOCKED -> PentaxGreen
                                        FocusStatus.FAILED -> PentaxRed
                                        else -> InkPrimary
                                    },
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (focusHud.lockedForShutter) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "解锁AF",
                                        color = InkSecondary,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(ObsidianBg)
                                            .clickable { viewModel.clearTouchFocusLock() }
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Independent AF Button (half-press equivalent)
                            Column(
                                modifier = Modifier
                                    .size(62.dp)
                                    .clip(CircleShape)
                                    .background(if (focusHud.status == FocusStatus.LOCKED) PentaxGreen.copy(alpha = 0.18f) else MatteCard)
                                    .border(
                                        1.dp,
                                        if (focusHud.status == FocusStatus.LOCKED) PentaxGreen else HairlineBorder,
                                        CircleShape
                                    )
                                    .clickable(enabled = !manualLens) { viewModel.triggerManualAf() },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (focusHud.status == FocusStatus.FOCUSING) "..." else "AF",
                                    color = if (manualLens) InkSecondary else if (focusHud.status == FocusStatus.LOCKED) PentaxGreen else InkPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (manualLens) "手动" else "对焦",
                                    color = InkSecondary,
                                    fontSize = 9.sp
                                )
                            }

                            Spacer(Modifier.width(24.dp))

                            // Tactile Main Shutter Release
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .border(2.5.dp, InkPrimary.copy(alpha = 0.85f), CircleShape)
                                    .padding(6.dp)
                                    .clip(CircleShape)
                                    .background(PentaxRed)
                                    .clickable { viewModel.shootSingle() },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(modifier = Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape))
                            }

                            Spacer(Modifier.width(24.dp))

                            // Symmetry spacer / Quick 2x-4x Focus Loupe button
                            Column(
                                modifier = Modifier
                                    .size(62.dp)
                                    .clip(CircleShape)
                                    .background(if (zoomLevel > 1.05f) PentaxGreen.copy(alpha = 0.18f) else MatteCard)
                                    .border(
                                        1.dp,
                                        if (zoomLevel > 1.05f) PentaxGreen else HairlineBorder,
                                        CircleShape
                                    )
                                    .clickable { cycleZoom() },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "${zoomLevel.toInt()}x",
                                    color = if (zoomLevel > 1.05f) PentaxGreen else InkPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text("检焦", color = InkSecondary, fontSize = 9.sp)
                            }
                        }

                        Text(
                            text = "点取景框任意位置即可精准对焦 · 放大至 2x/4x 后点选更准",
                            color = InkSecondary,
                            fontSize = 10.sp
                        )
                    }
                }

                RemoteShotMode.AEB -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MatteCard)
                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("自定义多档包围曝光 (HDR)", color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("拍完自动恢复原 EV", color = InkSecondary, fontSize = 10.sp)
                        }
                        PresetChipsRow("包围张数 (${aebState.shotCount} 张)", listOf(3, 5, 7), aebState.shotCount, { "${it}张" }) {
                            viewModel.updateAebConfig(it, aebState.stepEv)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("每档步长 (±${"%.1f".format(aebState.stepEv)} EV)", color = InkSecondary, fontSize = 12.sp)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0.7f, 1.0f, 1.5f, 2.0f).forEach { step ->
                                    val sel = kotlin.math.abs(aebState.stepEv - step) < 0.05f
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) PentaxGreen.copy(alpha = 0.2f) else ObsidianBg)
                                            .border(0.5.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                                            .clickable { viewModel.updateAebConfig(aebState.shotCount, step) }
                                            .padding(vertical = 7.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("±${"%.1f".format(step)}EV", color = if (sel) PentaxGreen else InkPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                        val resolvedAebSeq = remember(aebState.shotCount, aebState.stepEv, params.xvList) {
                            viewModel.computeResolvedAebSequence(aebState.shotCount, aebState.stepEv, params.xvList)
                        }
                        Text(
                            text = "实际档位 (${resolvedAebSeq.size}张去重): ${resolvedAebSeq.joinToString(" / ")}",
                            color = InkSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (aebState.running) {
                            val totalToShoot = aebState.activeTotalShots.coerceAtLeast(1)
                            LinearProgressIndicator(
                                progress = { aebState.currentShot.toFloat() / totalToShoot },
                                color = PentaxGreen, trackColor = ObsidianBg,
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                            )
                            Text(
                                "正在拍摄 ${aebState.currentShot}/$totalToShoot · 当前 ${aebState.currentEvLabel}",
                                color = PentaxGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth().height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (aebState.running) PentaxRed else PentaxGreen)
                                .clickable { viewModel.toggleAebBracketing() },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (aebState.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, tint = if (aebState.running) Color.White else Color(0xFF04170B), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (aebState.running) "中止包围曝光" else "开始 ${resolvedAebSeq.size} 张包围曝光",
                                color = if (aebState.running) Color.White else Color(0xFF04170B),
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                        }
                    }
                }

                RemoteShotMode.INTERVAL -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MatteCard)
                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PresetChipsRow("拍摄间隔 (${intervalState.intervalSec}s)", listOf(3, 5, 10, 30), intervalState.intervalSec, { "${it}s" }) {
                            viewModel.updateIntervalConfig(it, intervalState.totalShots)
                        }
                        PresetChipsRow("总张数 (${intervalState.totalShots} 张)", listOf(10, 30, 60, 120), intervalState.totalShots, { "$it" }) {
                            viewModel.updateIntervalConfig(intervalState.intervalSec, it)
                        }
                        if (intervalState.running) {
                            LinearProgressIndicator(
                                progress = { intervalState.shotsTaken.toFloat() / intervalState.totalShots.coerceAtLeast(1) },
                                color = PentaxGreen, trackColor = ObsidianBg,
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                            )
                            Text("已拍 ${intervalState.shotsTaken}/${intervalState.totalShots} · 倒计时 ${intervalState.nextShotCountdown}s", color = PentaxGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth().height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (intervalState.running) PentaxRed else PentaxGreen)
                                .clickable { viewModel.toggleIntervalometer() },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (intervalState.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, tint = if (intervalState.running) Color.White else Color(0xFF04170B), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (intervalState.running) "停止间隔拍摄" else "开始间隔延时拍摄", color = if (intervalState.running) Color.White else Color(0xFF04170B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                RemoteShotMode.BULB -> {
                    // 1. Active Bulb Timer Control Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MatteCard)
                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("B 门长曝计时器（机身需置 B 档）", color = InkSecondary, fontSize = 12.sp)
                            Text(
                                text = ExposureCalculator.formatDurationHuman(bulbState.targetSec.toDouble()),
                                color = PentaxGreen,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        PresetChipsRow("常用快捷秒数", listOf(15, 30, 60, 120, 300), bulbState.targetSec, { "${it}s" }) {
                            viewModel.updateBulbTarget(it)
                        }
                        if (bulbState.running) {
                            LinearProgressIndicator(
                                progress = { (bulbState.elapsedSec.toFloat() / bulbState.targetSec.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                color = PentaxRed, trackColor = ObsidianBg,
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                            )
                            Text(
                                "长曝进行中  ${bulbState.elapsedSec}s / ${bulbState.targetSec}s",
                                color = PentaxRed, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth().height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (bulbState.running) PentaxRed else PentaxGreen)
                                .clickable { viewModel.toggleBulbExposure() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (bulbState.running) "立即结束 B 门" else "启动 ${bulbState.targetSec}s B门长曝",
                                color = if (bulbState.running) Color.White else Color(0xFF04170B),
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                        }
                    }

                }
            }

            // Always-accessible 4-in-1 Optical & Field Calculator (auto-expanded on entering BULB, freely collapsible!)
            val calcExpanded = showFieldCalc
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFieldCalc = !showFieldCalc },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = PentaxGreen, modifier = Modifier.size(16.dp))
                        Text("风光 / 星空光学计算器 (ND · NPF · 超焦距 · 蓝调)", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Icon(
                        if (calcExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null, tint = InkSecondary, modifier = Modifier.size(18.dp)
                    )
                }

                if (calcExpanded) {
                    SegmentedPillRow(
                        items = CalcMode.entries,
                        selectedItem = calcMode,
                        labelProvider = { it.label },
                        onSelect = { calcMode = it },
                        modifier = Modifier.fillMaxWidth()
                    )

                    when (calcMode) {
                        CalcMode.ND -> {
                            val baseOpt = ExposureCalculator.BASE_SHUTTERS[baseShutterIdx]
                            val computedSec = ExposureCalculator.computeNdExposureSeconds(baseOpt.seconds, primaryNd, secondaryNd)

                            Text("无滤镜测光快门 (${baseOpt.label}s)", color = InkSecondary, fontSize = 11.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ExposureCalculator.BASE_SHUTTERS.indices.toList()) { idx ->
                                    SmallCalcChip(ExposureCalculator.BASE_SHUTTERS[idx].label, idx == baseShutterIdx) { baseShutterIdx = idx }
                                }
                            }
                            Text("主 ND 滤镜 (${primaryNd.label})", color = InkSecondary, fontSize = 11.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(NdFilterOption.entries.filter { it != NdFilterOption.NONE }) { nd ->
                                    SmallCalcChip(nd.label, primaryNd == nd) { primaryNd = nd }
                                }
                            }
                            Text("叠加第二片滤镜 (${secondaryNd.label})", color = InkSecondary, fontSize = 11.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(listOf(NdFilterOption.NONE, NdFilterOption.ND4, NdFilterOption.ND8, NdFilterOption.ND16, NdFilterOption.ND64)) { nd ->
                                    SmallCalcChip(nd.label, secondaryNd == nd) { secondaryNd = nd }
                                }
                            }
                            CalculatorResultFooter(
                                title = "计算结果 (${primaryNd.stops + secondaryNd.stops} 档减光)",
                                valueText = ExposureCalculator.formatDurationHuman(computedSec),
                                subNote = if (computedSec < 1.0) "小于 1 秒，可直接用机身快门档拍摄" else "可直接导入 B 门长曝计时器",
                                canApplyBulb = computedSec >= 1.0,
                                onLoadBulb = { viewModel.applyCalculatedBulbSeconds(computedSec, autoStart = false) },
                                onLoadAndStart = { viewModel.applyCalculatedBulbSeconds(computedSec, autoStart = true) }
                            )
                        }

                        CalcMode.ASTRO -> {
                            val npfSec = ExposureCalculator.computeK1AstroNpfSeconds(astroFocal, astroAperture, astroDecl)
                            val rule500Sec = ExposureCalculator.computeRule500Seconds(astroFocal)

                            Text("镜头焦距 (${astroFocal}mm · 全画幅 K-1 4.88μm)", color = InkSecondary, fontSize = 11.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ExposureCalculator.ASTRO_FOCAL_LENGTHS.take(10)) { f ->
                                    SmallCalcChip("${f}mm", astroFocal == f) { astroFocal = f }
                                }
                            }
                            Text("拍摄光圈 (f/$astroAperture)", color = InkSecondary, fontSize = 11.sp)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1.4, 1.8, 2.0, 2.8, 4.0).forEach { ap ->
                                    SmallCalcChip("f/$ap", astroAperture == ap, Modifier.weight(1f)) { astroAperture = ap }
                                }
                            }
                            Text("目标天区赤纬", color = InkSecondary, fontSize = 11.sp)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SkyDeclination.entries.forEach { d ->
                                    SmallCalcChip(d.label, astroDecl == d, Modifier.weight(1f)) { astroDecl = d }
                                }
                            }
                            CalculatorResultFooter(
                                title = "K-1 NPF 精准防拖尾快门",
                                valueText = ExposureCalculator.formatDurationHuman(npfSec),
                                subNote = "传统 500 法则参考：${ExposureCalculator.formatDurationHuman(rule500Sec)}",
                                canApplyBulb = true,
                                onLoadBulb = { viewModel.applyCalculatedBulbSeconds(npfSec, autoStart = false) },
                                onLoadAndStart = { viewModel.applyCalculatedBulbSeconds(npfSec, autoStart = true) }
                            )
                        }

                        CalcMode.HYPER -> {
                            val hRes = ExposureCalculator.computeHyperfocal(hyperFocalMm, hyperAperture, hyperDistM)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("全画幅焦距 (${hyperFocalMm}mm · CoC 0.03mm)", color = InkSecondary, fontSize = 11.sp)
                                val camAv = params.av.toDoubleOrNull()
                                if (camAv != null && camAv > 0.0) {
                                    Text(
                                        "同步机身 F${params.av}",
                                        color = PentaxGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(PentaxGreen.copy(alpha = 0.16f))
                                            .clickable { hyperAperture = camAv }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ExposureCalculator.ASTRO_FOCAL_LENGTHS) { f ->
                                    SmallCalcChip("${f}mm", hyperFocalMm == f) { hyperFocalMm = f }
                                }
                            }
                            Text("光圈 (f/$hyperAperture)", color = InkSecondary, fontSize = 11.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ExposureCalculator.ASTRO_APERTURES) { ap ->
                                    SmallCalcChip("f/$ap", hyperAperture == ap) { hyperAperture = ap }
                                }
                            }
                            Text("当前对焦距离 (${ExposureCalculator.formatMeters(hyperDistM)})", color = InkSecondary, fontSize = 11.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ExposureCalculator.FOCUS_DISTANCES_M) { d ->
                                    SmallCalcChip("${d}m", hyperDistM == d) { hyperDistM = d }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ObsidianBg)
                                    .border(0.5.dp, PentaxGreen.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("超焦距对焦点", color = InkSecondary, fontSize = 11.sp)
                                    Text(ExposureCalculator.formatMeters(hRes.hyperfocalMeters), color = PentaxGreen, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "对焦于超焦距时：从 ${ExposureCalculator.formatMeters(hRes.nearAtHyperfocalMeters)} 至 ∞ 全部清晰",
                                    color = InkPrimary, fontSize = 10.sp
                                )
                                Text(
                                    "对焦于 ${hyperDistM}m 时景深：${ExposureCalculator.formatMeters(hRes.nearFocusMeters)} ～ ${ExposureCalculator.formatMeters(hRes.farFocusMeters)}",
                                    color = InkSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        CalcMode.SOLAR -> {
                            val city = ExposureCalculator.SOLAR_CITY_PRESETS[solarCityIdx]
                            val sched = remember(solarCityIdx) {
                                ExposureCalculator.computeOfflineSolarSchedule(city.lat, city.lon)
                            }
                            Text("离线天文日出/蓝调计算（无信号深山可用 · ${city.name}）", color = InkSecondary, fontSize = 11.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ExposureCalculator.SOLAR_CITY_PRESETS.indices.toList()) { idx ->
                                    SmallCalcChip(ExposureCalculator.SOLAR_CITY_PRESETS[idx].name, idx == solarCityIdx) { solarCityIdx = idx }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ObsidianBg)
                                    .border(0.5.dp, WarmAmber.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("清晨蓝调 / 日出", color = InkSecondary, fontSize = 11.sp)
                                    Text("${sched.blueMorningStart}  /  ${sched.sunrise}", color = InkPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("上午黄金时刻结束", color = InkSecondary, fontSize = 11.sp)
                                    Text(sched.goldenMorningEnd, color = WarmAmber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("傍晚黄金时刻开始", color = InkSecondary, fontSize = 11.sp)
                                    Text(sched.goldenEveningStart, color = WarmAmber, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("日落 / 晚蓝调结束", color = InkSecondary, fontSize = 11.sp)
                                    Text("${sched.sunset}  /  ${sched.blueEveningEnd}", color = PentaxGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("天文全黑（拍银河最佳）", color = InkSecondary, fontSize = 11.sp)
                                    Text(sched.astroNightStart, color = InkPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                RemoteSwitchRow("音量键 / 蓝牙快门盲控", "按手机音量 +/- 键或蓝牙遥控器直接触发当前快门模式", volumeShutterEnabled) { viewModel.setVolumeShutterEnabled(it) }
                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                RemoteSwitchRow("手动头安全快门", "跳过 AF 直接释放，适配 K/M/A/M42 经典手动镜头", manualLens) { viewModel.setManualLensMode(it) }
                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                RemoteSwitchRow("拍完即传 (Tether)", "遥控或按机身快门拍照后，自动同步最新预览图至手机（图库默认常驻实时刷新）", tetherEnabled) { viewModel.toggleTetherMode(it) }
            }
        }
    }
}

@Composable
private fun SmallCalcChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PentaxGreen.copy(alpha = 0.20f) else ObsidianBg)
            .border(0.5.dp, if (selected) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) PentaxGreen else InkPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun CalculatorResultFooter(
    title: String,
    valueText: String,
    subNote: String,
    canApplyBulb: Boolean,
    onLoadBulb: () -> Unit,
    onLoadAndStart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianBg)
            .border(0.5.dp, PentaxGreen.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = InkSecondary, fontSize = 11.sp)
            Text(valueText, color = PentaxGreen, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Text(subNote, color = InkSecondary, fontSize = 10.sp)
        if (canApplyBulb) {
            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MatteCard)
                        .border(0.5.dp, HairlineBorder, RoundedCornerShape(9.dp))
                        .clickable { onLoadBulb() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("填入 B 门", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    modifier = Modifier
                        .weight(1.1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(PentaxGreen)
                        .clickable { onLoadAndStart() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("填入并立即开拍", color = Color(0xFF04170B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ExposureDialCell(label: String, value: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PentaxGreen.copy(alpha = 0.16f) else MatteCard)
            .border(0.5.dp, if (selected) PentaxGreen else HairlineBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = if (selected) PentaxGreen else InkSecondary, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = if (selected) PentaxGreen else InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun PresetChipsRow(title: String, values: List<Int>, selected: Int, format: (Int) -> String, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = InkSecondary, fontSize = 12.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { v ->
                val isSel = v == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) PentaxGreen.copy(alpha = 0.2f) else ObsidianBg)
                        .border(0.5.dp, if (isSel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                        .clickable { onSelect(v) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(format(v), color = if (isSel) PentaxGreen else InkPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun RemoteSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = InkSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PentaxGreen, uncheckedTrackColor = ObsidianBg, uncheckedBorderColor = HairlineBorder)
        )
    }
}
