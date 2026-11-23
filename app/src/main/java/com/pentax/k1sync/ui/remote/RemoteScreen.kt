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
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pentax.k1sync.model.GridOverlayMode
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
import com.pentax.k1sync.viewmodel.AppTab
import com.pentax.k1sync.viewmodel.MainViewModel

@Composable
fun RemoteScreen(viewModel: MainViewModel) {
    val cameraInfo by viewModel.cameraInfo.collectAsState()
    val params by viewModel.shootingParams.collectAsState()
    val liveFrame by viewModel.liveViewFrame.collectAsState()
    val liveActive by viewModel.liveViewActive.collectAsState()
    val liveError by viewModel.liveViewError.collectAsState()
    val manualLens by viewModel.manualLensMode.collectAsState()
    val tetherEnabled by viewModel.tetherEnabled.collectAsState()
    val gridMode by viewModel.gridMode.collectAsState()
    val shotMode by viewModel.remoteShotMode.collectAsState()
    val intervalState by viewModel.intervalState.collectAsState()
    val bulbState by viewModel.bulbState.collectAsState()

    var activeDialKey by remember { mutableStateOf<String?>(null) }
    // 1f -> 2f -> 4f cycle, strictly clipped to viewfinder bounds!
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var focusIndicator by remember { mutableStateOf<Offset?>(null) }

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
        // Minimal Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElevatedSurface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("无线遥控取景", color = InkPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            ConnectionPillBadge(cameraInfo) { viewModel.selectTab(AppTab.SYNC) }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        // Live View 3:2 Monitor — STRICTLY CLIPPED TO BOUNDS (Fixes zoom overlap bug!)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 2f)
                .background(Color.Black)
                .clipToBounds()
                .pointerInput(manualLens) {
                    detectTapGestures(
                        onDoubleTap = { cycleZoom() },
                        onTap = { tap ->
                            focusIndicator = tap
                            val xPct = ((tap.x / size.width) * 100).toInt().coerceIn(5, 95)
                            val yPct = ((tap.y / size.height) * 100).toInt().coerceIn(5, 95)
                            viewModel.touchFocus(xPct, yPct)
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
                            scaleX = zoomLevel,
                            scaleY = zoomLevel,
                            translationX = panOffset.x,
                            translationY = panOffset.y
                        )
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = liveError ?: if (cameraInfo.connected) "准备就绪，点击下方开启实时取景" else "请先在「机身」页连接 PENTAX K-1",
                        color = InkSecondary,
                        fontSize = 13.sp
                    )
                    if (cameraInfo.connected) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(PentaxGreen)
                                .clickable { viewModel.startLiveView() }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text("开启 Live View", color = Color(0xFF04170B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Composition Grid
            if (gridMode != GridOverlayMode.NONE) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Color(0x55FFFFFF)
                    when (gridMode) {
                        GridOverlayMode.THIRDS -> {
                            for (i in 1..2) {
                                val x = size.width * i / 3f
                                val y = size.height * i / 3f
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
                        GridOverlayMode.NONE -> Unit
                    }
                }
            }

            // Touch Focus Box
            focusIndicator?.let { pt ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(
                        color = PentaxGreen,
                        topLeft = Offset(pt.x - 24f, pt.y - 24f),
                        size = Size(48f, 48f),
                        style = Stroke(width = 2f)
                    )
                }
            }

            // Top-Left Minimal Status Tag
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
                        color = InkPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Top-Right Floating Pill Tools (Grid + 1x/2x/4x Magnifier)
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
                    Icon(
                        Icons.Rounded.GridOn,
                        contentDescription = "网格",
                        tint = if (gridMode != GridOverlayMode.NONE) PentaxGreen else Color.White,
                        modifier = Modifier.size(15.dp)
                    )
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
                    Icon(
                        Icons.Rounded.ZoomIn,
                        contentDescription = "放大检焦",
                        tint = if (zoomLevel > 1.05f) PentaxGreen else Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("${zoomLevel.toInt()}x", color = if (zoomLevel > 1.05f) PentaxGreen else Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Precision Exposure Dial Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElevatedSurface)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ExposureDialCell("Av", "F${params.av}", activeDialKey == "av", Modifier.weight(1f)) {
                activeDialKey = if (activeDialKey == "av") null else "av"
            }
            ExposureDialCell("Tv", ShootingParams.formatTvDisplay(params.tv), activeDialKey == "tv", Modifier.weight(1f)) {
                activeDialKey = if (activeDialKey == "tv") null else "tv"
            }
            ExposureDialCell("ISO", params.sv, activeDialKey == "sv", Modifier.weight(1f)) {
                activeDialKey = if (activeDialKey == "sv") null else "sv"
            }
            ExposureDialCell("EV", params.xv, activeDialKey == "xv", Modifier.weight(1f)) {
                activeDialKey = if (activeDialKey == "xv") null else "xv"
            }
            ExposureDialCell("WB", ShootingParams.formatWbDisplay(params.wbMode), activeDialKey == "wbMode", Modifier.weight(1f)) {
                activeDialKey = if (activeDialKey == "wbMode") null else "wbMode"
            }
        }

        // Expandable Value Ribbon
        activeDialKey?.let { key ->
            val options = when (key) {
                "av" -> params.avList
                "tv" -> params.tvList
                "sv" -> params.svList
                "xv" -> params.xvList
                "wbMode" -> params.wbList
                else -> emptyList()
            }
            val currentVal = when (key) {
                "av" -> params.av; "tv" -> params.tv; "sv" -> params.sv; "xv" -> params.xv; "wbMode" -> params.wbMode
                else -> ""
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161A18))
            ) {
                items(options) { opt ->
                    val label = when (key) {
                        "av" -> "F$opt"
                        "tv" -> ShootingParams.formatTvDisplay(opt)
                        "wbMode" -> ShootingParams.formatWbDisplay(opt)
                        else -> opt
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
                        Text(
                            text = label,
                            color = if (chosen) Color(0xFF04170B) else InkPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        // Shooting Mode Controls & Tactile Shutter
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(78.dp)
                                .clip(CircleShape)
                                .border(2.5.dp, InkPrimary.copy(alpha = 0.85f), CircleShape)
                                .padding(6.dp)
                                .clip(CircleShape)
                                .background(PentaxRed)
                                .clickable { viewModel.shootSingle() },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
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
                        PresetChipsRow(
                            title = "拍摄间隔 (${intervalState.intervalSec}s)",
                            values = listOf(3, 5, 10, 30),
                            selected = intervalState.intervalSec,
                            format = { "${it}s" }
                        ) { viewModel.updateIntervalConfig(it, intervalState.totalShots) }

                        PresetChipsRow(
                            title = "总张数 (${intervalState.totalShots} 张)",
                            values = listOf(10, 30, 60, 120),
                            selected = intervalState.totalShots,
                            format = { "$it" }
                        ) { viewModel.updateIntervalConfig(intervalState.intervalSec, it) }

                        if (intervalState.running) {
                            LinearProgressIndicator(
                                progress = { intervalState.shotsTaken.toFloat() / intervalState.totalShots.coerceAtLeast(1) },
                                color = PentaxGreen,
                                trackColor = ObsidianBg,
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                            )
                            Text(
                                "已拍 ${intervalState.shotsTaken}/${intervalState.totalShots} · 倒计时 ${intervalState.nextShotCountdown}s",
                                color = PentaxGreen,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (intervalState.running) PentaxRed else PentaxGreen)
                                .clickable { viewModel.toggleIntervalometer() },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (intervalState.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                null,
                                tint = if (intervalState.running) Color.White else Color(0xFF04170B),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (intervalState.running) "停止间隔拍摄" else "开始间隔延时拍摄",
                                color = if (intervalState.running) Color.White else Color(0xFF04170B),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                RemoteShotMode.BULB -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MatteCard)
                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PresetChipsRow(
                            title = "B门目标时长 (机身需置 B 档)",
                            values = listOf(15, 30, 60, 120, 300),
                            selected = bulbState.targetSec,
                            format = { "${it}s" }
                        ) { viewModel.updateBulbTarget(it) }

                        if (bulbState.running) {
                            Text(
                                "长曝进行中  ${bulbState.elapsedSec}s / ${bulbState.targetSec}s",
                                color = PentaxRed,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (bulbState.running) PentaxRed else PentaxGreen)
                                .clickable { viewModel.toggleBulbExposure() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (bulbState.running) "立即结束 B 门" else "启动 ${bulbState.targetSec}s B门长曝",
                                color = if (bulbState.running) Color.White else Color(0xFF04170B),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Grouped Pro Switches Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                RemoteSwitchRow(
                    title = "手动头安全快门",
                    subtitle = "跳过 AF 直接释放，适配 K/M/A/M42 经典手动镜头",
                    checked = manualLens,
                    onCheckedChange = { viewModel.setManualLensMode(it) }
                )
                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                RemoteSwitchRow(
                    title = "拍完即传 (Tether)",
                    subtitle = "按快门后自动将最新预览图同步至手机",
                    checked = tetherEnabled,
                    onCheckedChange = { viewModel.toggleTetherMode(it) }
                )
            }
        }
    }
}

@Composable
private fun ExposureDialCell(
    label: String,
    value: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
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
        Text(
            text = value,
            color = if (selected) PentaxGreen else InkPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
private fun PresetChipsRow(
    title: String,
    values: List<Int>,
    selected: Int,
    format: (Int) -> String,
    onSelect: (Int) -> Unit
) {
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
                    Text(
                        format(v),
                        color = if (isSel) PentaxGreen else InkPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = InkSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PentaxGreen,
                uncheckedTrackColor = ObsidianBg,
                uncheckedBorderColor = HairlineBorder
            )
        )
    }
}
