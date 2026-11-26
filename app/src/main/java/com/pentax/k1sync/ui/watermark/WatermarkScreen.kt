package com.pentax.k1sync.ui.watermark

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.RawOn
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pentax.k1sync.image.CropAspectRatio
import com.pentax.k1sync.image.CubeLutEngine
import com.pentax.k1sync.spektra.BwCameraFilter
import com.pentax.k1sync.spektra.DiffusionFilterFamily
import com.pentax.k1sync.spektra.FilmCategory
import com.pentax.k1sync.spektra.FilmFormatSpec
import com.pentax.k1sync.spektra.LightLeakKind
import com.pentax.k1sync.spektra.SpektraCatalog
import com.pentax.k1sync.spektra.SpektraLatitudeReadout
import com.pentax.k1sync.ui.components.RgbHistogramView
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
import com.pentax.k1sync.viewmodel.ColorGradeMode
import com.pentax.k1sync.viewmodel.MainViewModel
import com.pentax.k1sync.watermark.PentaxAccentColor
import com.pentax.k1sync.watermark.WatermarkStyle
import kotlin.math.abs

private enum class StudioPanelTab(val label: String) {
    LUT("胶片/暗房"),
    PS_GRADE("PS混色"),
    CROP("裁切拉直"),
    TEMPLATES("水印模板"),
    APPEARANCE("配色外观"),
    EXIF("镜头参数")
}

@Composable
fun WatermarkScreen(viewModel: MainViewModel) {
    val state by viewModel.watermarkState.collectAsState()
    val lensPresets by viewModel.lensPresets.collectAsState()
    val manualLensFocalMap by viewModel.manualLensFocalMap.collectAsState()
    val darkroomRecipes by viewModel.darkroomRecipes.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val showStudioHistogram by viewModel.showStudioHistogram.collectAsState()
    val studioHistogram by viewModel.studioHistogram.collectAsState()
    val cfg = state.config
    val cropCfg = state.cropConfig
    val spk = state.spektraParams

    var activePanel by remember { mutableStateOf(StudioPanelTab.LUT) }
    var filmCategory by remember { mutableStateOf(FilmCategory.COLOR_NEG) }
    var holdingCompareOriginal by remember { mutableStateOf(false) }
    var showSaveRecipeInput by remember { mutableStateOf(false) }
    var newRecipeName by remember { mutableStateOf("") }
    var newMapFocalText by remember { mutableStateOf("") }
    var newMapLensText by remember { mutableStateOf("") }

    val singlePhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.loadLocalPhotoForWatermark(uri) }

    val rawFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.loadLocalRawForStudio(uri) }

    val batchPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris -> if (uris.isNotEmpty()) viewModel.batchApplyWatermark(uris) }

    val cubeFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importCustomCubeLut(uri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        // 1. Studio Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElevatedSurface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                Text("暗房后期与水印", color = InkPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    text = if (state.isRawSource) "14-bit RAW · 双通道高速预览" else "长按预览对比原图 · 支持 121 卷胶片",
                    color = if (state.isRawSource) WarmAmber else InkSecondary,
                    fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canUndo) MatteCard else ObsidianBg)
                        .border(0.5.dp, if (canUndo) HairlineBorder else SubtleDivider, RoundedCornerShape(8.dp))
                        .clickable(enabled = canUndo) { viewModel.undoStudioEdit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Undo, contentDescription = "撤销", tint = if (canUndo) InkPrimary else InkSecondary.copy(alpha = 0.35f), modifier = Modifier.size(15.dp))
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canRedo) MatteCard else ObsidianBg)
                        .border(0.5.dp, if (canRedo) HairlineBorder else SubtleDivider, RoundedCornerShape(8.dp))
                        .clickable(enabled = canRedo) { viewModel.redoStudioEdit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Redo, contentDescription = "重做", tint = if (canRedo) InkPrimary else InkSecondary.copy(alpha = 0.35f), modifier = Modifier.size(15.dp))
                }
                HeaderToolButton(Icons.Rounded.AddPhotoAlternate, "选图", PentaxGreen) {
                    singlePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                HeaderToolButton(Icons.Rounded.RawOn, "RAW", WarmAmber) {
                    rawFilePicker.launch(arrayOf("*/*"))
                }
                HeaderToolButton(Icons.Rounded.Collections, "批量", InkPrimary, enabled = state.batchProgress == null) {
                    batchPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        // 2. Preview Stage: Interactive 60fps Draggable/Zoomable Crop Stage in CROP tab,
        //    or Tap-to-Zoom-8x / Long-Press-Before-After Preview in all other tabs!
        if (activePanel == StudioPanelTab.CROP && (state.unCroppedGradedBitmap != null || state.sourceBitmap != null)) {
            InteractiveCropStage(
                bitmap = state.unCroppedGradedBitmap ?: state.sourceBitmap!!,
                cropConfig = cropCfg,
                onPanZoomChanged = { z, px, py -> viewModel.updateCropPanZoom(z, px, py) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.95f)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.95f)
                    .background(Color(0xFF070709))
                    .pointerInput(state.sourceBitmap, state.previewBitmap) {
                        detectTapGestures(
                            onTap = {
                                if (state.previewBitmap != null) viewModel.setWatermarkFullscreen(true)
                            },
                            onLongPress = {
                                if (state.sourceBitmap != null) holdingCompareOriginal = true
                            },
                            onPress = {
                                tryAwaitRelease()
                                holdingCompareOriginal = false
                            }
                        )
                    }
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                val shownBitmap = if (holdingCompareOriginal && state.sourceBitmap != null) {
                    state.sourceBitmap
                } else {
                    state.previewBitmap
                }
                if (shownBitmap != null) {
                    Image(
                        bitmap = shownBitmap.asImageBitmap(),
                        contentDescription = "Studio Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (state.isRendering && !holdingCompareOriginal) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xCC0B0B0D))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        CircularProgressIndicator(color = PentaxGreen, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        if (state.colorGradeMode == ColorGradeMode.SPEKTRA_DARKROOM) {
                            Spacer(Modifier.height(6.dp))
                            Text("81 波长暗房演算中...", color = InkPrimary, fontSize = 10.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.TopStart),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (holdingCompareOriginal) {
                        PreviewMicroBadge("对比原图 (松手恢复)", WarmAmber)
                    } else {
                        if (state.isRawSource) PreviewMicroBadge("14-bit RAW", WarmAmber)
                        if (state.colorGradeMode == ColorGradeMode.SPEKTRA_DARKROOM) {
                            val stockName = SpektraCatalog.findFilm(spk.filmStockId).shortName
                            PreviewMicroBadge("Spektra · $stockName", PentaxGreen)
                        } else if (state.selectedLutId != CubeLutEngine.ID_NONE) {
                            PreviewMicroBadge("${state.activeLutPreset.name} ${(state.lutIntensity * 100).toInt()}%", PentaxGreen)
                        }
                        if (!state.postTone.isIdentity) {
                            val psLabel = if (state.postTone.hasBlendLayer) "PS混色·${state.postTone.blendMode.label}" else "PS混色"
                            PreviewMicroBadge(psLabel, WarmAmber)
                        }
                        if (!cropCfg.isIdentity) {
                            PreviewMicroBadge(
                                text = buildString {
                                    append(cropCfg.aspectRatio.label)
                                    if (cropCfg.cropZoom > 1.02f) append(" ${"%.1f".format(cropCfg.cropZoom)}x")
                                    if (abs(cropCfg.straightenDegrees) >= 0.1f) append(" ${"%+.1f".format(cropCfg.straightenDegrees)}°")
                                },
                                tint = InkPrimary
                            )
                        }
                    }
                }

                if (state.previewBitmap != null && !holdingCompareOriginal) {
                    if (showStudioHistogram && studioHistogram != null) {
                        RgbHistogramView(
                            histogram = studioHistogram!!,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .width(126.dp)
                                .height(42.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (showStudioHistogram) WarmAmber.copy(alpha = 0.28f) else Color(0xB8000000))
                                .border(0.5.dp, if (showStudioHistogram) WarmAmber else Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                                .clickable { viewModel.toggleStudioHistogram() }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.BarChart, contentDescription = "直方图", tint = if (showStudioHistogram) WarmAmber else Color.White, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("直方图", color = Color.White, fontSize = 10.sp)
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xB8000000))
                                .border(0.5.dp, Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                                .clickable { viewModel.setWatermarkFullscreen(true) }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.ZoomIn, contentDescription = "放大检视", tint = PentaxGreen, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("放大检视", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        state.batchProgress?.let { bp ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15221A))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (state.colorGradeMode == ColorGradeMode.SPEKTRA_DARKROOM) "正在用 Spektra 暗房批量出片..." else "正在批量套色与水印...",
                        color = PentaxGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${bp.current}/${bp.total}", color = PentaxGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            text = "取消",
                            color = PentaxRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PentaxRed.copy(alpha = 0.16f))
                                .border(0.5.dp, PentaxRed.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .clickable { viewModel.cancelBatchWatermark() }
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { bp.current.toFloat() / bp.total.coerceAtLeast(1) },
                    color = PentaxGreen, trackColor = ObsidianBg,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                )
            }
        }

        // 3. 5-Tab Workbench
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.05f)
                .background(ElevatedSurface)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(StudioPanelTab.entries) { tab ->
                    val sel = activePanel == tab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (sel) PentaxGreen.copy(alpha = 0.18f) else ObsidianBg)
                            .border(0.6.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(9.dp))
                            .clickable {
                                val leavingCrop = (activePanel == StudioPanelTab.CROP && tab != StudioPanelTab.CROP)
                                activePanel = tab
                                if (leavingCrop) viewModel.commitCropPreview()
                            }
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Text(tab.label, color = if (sel) PentaxGreen else InkSecondary, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activePanel) {
                    StudioPanelTab.LUT -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SegmentedPillRow(
                                items = ColorGradeMode.entries,
                                selectedItem = state.colorGradeMode,
                                labelProvider = { it.label },
                                onSelect = { viewModel.setColorGradeMode(it) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (state.colorGradeMode == ColorGradeMode.CUBE_LUT) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("3D LUT 滤镜（支持导入 .cube）", color = InkSecondary, fontSize = 11.sp)
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(PentaxGreen.copy(alpha = 0.18f))
                                            .border(0.5.dp, PentaxGreen, RoundedCornerShape(8.dp))
                                            .clickable { cubeFilePicker.launch(arrayOf("*/*")) }
                                            .padding(horizontal = 9.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.FileOpen, null, tint = PentaxGreen, modifier = Modifier.size(13.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("导入 .cube", color = PentaxGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(state.availableLuts, key = { it.id }) { preset ->
                                        val selected = state.selectedLutId == preset.id
                                        Column(
                                            modifier = Modifier
                                                .width(92.dp)
                                                .clip(RoundedCornerShape(11.dp))
                                                .background(if (selected) PentaxGreen.copy(alpha = 0.16f) else MatteCard)
                                                .border(if (selected) 1.2.dp else 0.5.dp, if (selected) PentaxGreen else HairlineBorder, RoundedCornerShape(11.dp))
                                                .clickable { viewModel.selectLutPreset(preset.id) }
                                                .padding(horizontal = 8.dp, vertical = 9.dp)
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(preset.name, color = if (selected) PentaxGreen else InkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                                if (preset.isCustomFile) {
                                                    Icon(Icons.Rounded.Close, "删除 LUT", tint = InkSecondary, modifier = Modifier.size(13.dp).clickable { viewModel.deleteCustomLut(preset) })
                                                }
                                            }
                                            Spacer(Modifier.height(3.dp))
                                            Text(preset.subtitle, color = InkSecondary, fontSize = 9.sp, maxLines = 1)
                                        }
                                    }
                                }

                                if (state.selectedLutId != CubeLutEngine.ID_NONE) {
                                    DarkroomSliderCard("LUT 滤镜强度", "${(state.lutIntensity * 100).toInt()}%", state.lutIntensity, 0f..1f) {
                                        viewModel.updateLutIntensity(it)
                                    }
                                }
                            } else {
                                // === SPEKTRALAB 81-WAVELENGTH FULL DARKROOM PANEL ===
                                // 1. Engine Status + Auto Solve + Scene Latitude Meter & Auto-Fit!
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MatteCard)
                                        .border(0.5.dp, if (state.spektraError != null) PentaxRed.copy(alpha = 0.6f) else HairlineBorder, RoundedCornerShape(12.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = state.spektraStatusText,
                                            color = if (state.spektraError != null) PentaxRed else PentaxGreen,
                                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (spk.sceneLatitudeActive) WarmAmber.copy(alpha = 0.22f) else ObsidianBg)
                                                    .border(0.5.dp, if (spk.sceneLatitudeActive) WarmAmber else HairlineBorder, RoundedCornerShape(8.dp))
                                                    .clickable { viewModel.toggleSceneLatitudeAutoFit() }
                                                    .padding(horizontal = 7.dp, vertical = 5.dp)
                                            ) {
                                                Text(
                                                    if (spk.sceneLatitudeActive) "场景置位 ✓" else "场景置位",
                                                    color = if (spk.sceneLatitudeActive) WarmAmber else InkPrimary,
                                                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(PentaxGreen.copy(alpha = 0.18f))
                                                    .border(0.5.dp, PentaxGreen, RoundedCornerShape(8.dp))
                                                    .clickable(enabled = !state.spektraSolving) { viewModel.solveSpektraAutoBalance() }
                                                    .padding(horizontal = 7.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Rounded.AutoAwesome, null, tint = PentaxGreen, modifier = Modifier.size(12.dp))
                                                Spacer(Modifier.width(3.dp))
                                                Text(if (state.spektraSolving) "求解中" else "求滤色", color = PentaxGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    state.spektraLatitude?.let { lat -> SpektraLatitudeMeterView(lat) }
                                }

                                // 2. Saved Darkroom Recipes Book
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("我的暗房配方 (${darkroomRecipes.size})", color = InkSecondary, fontSize = 11.sp)
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(MatteCard)
                                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(7.dp))
                                            .clickable { showSaveRecipeInput = !showSaveRecipeInput }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.BookmarkAdd, null, tint = PentaxGreen, modifier = Modifier.size(13.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("存为配方", color = PentaxGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                if (showSaveRecipeInput) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        StudioField("输入配方名称 (如: 日系通透)", newRecipeName, Modifier.weight(1f)) { newRecipeName = it }
                                        Box(
                                            modifier = Modifier
                                                .height(48.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(PentaxGreen)
                                                .clickable {
                                                    viewModel.saveCurrentDarkroomRecipe(newRecipeName)
                                                    newRecipeName = ""
                                                    showSaveRecipeInput = false
                                                }
                                                .padding(horizontal = 14.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("保存", color = Color(0xFF04170B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                if (darkroomRecipes.isNotEmpty()) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(darkroomRecipes, key = { it.id }) { recipe ->
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MatteCard)
                                                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(8.dp))
                                                    .clickable { viewModel.applyDarkroomRecipe(recipe) }
                                                    .padding(start = 9.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(recipe.name, color = InkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                                Spacer(Modifier.width(5.dp))
                                                Icon(
                                                    Icons.Rounded.Close, "删除配方", tint = InkSecondary,
                                                    modifier = Modifier.size(13.dp).clickable { viewModel.deleteDarkroomRecipe(recipe.id) }
                                                )
                                            }
                                        }
                                    }
                                }

                                // 3. Film Stock Category Tabs + "全部 121 卷" Search Drawer Trigger!
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("实测 81 波长胶片卷", color = InkSecondary, fontSize = 11.sp)
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(PentaxGreen.copy(alpha = 0.16f))
                                            .border(0.5.dp, PentaxGreen.copy(alpha = 0.6f), RoundedCornerShape(7.dp))
                                            .clickable { viewModel.setShowAllStocksPicker(true) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Search, null, tint = PentaxGreen, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("搜索全部 ${state.allFilmStocks.size} 卷 ▸", color = PentaxGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilmCategory.entries.forEach { cat ->
                                        val sel = filmCategory == cat
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) PentaxGreen.copy(alpha = 0.18f) else MatteCard)
                                                .border(0.5.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                                                .clickable { filmCategory = cat }
                                                .padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(cat.label, color = if (sel) PentaxGreen else InkSecondary, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }

                                val stocksInCat = remember(filmCategory, state.allFilmStocks) {
                                    state.allFilmStocks.filter { it.category == filmCategory }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(stocksInCat, key = { it.id }) { stock ->
                                        val sel = spk.filmStockId == stock.id
                                        Column(
                                            modifier = Modifier
                                                .width(104.dp)
                                                .clip(RoundedCornerShape(11.dp))
                                                .background(if (sel) PentaxGreen.copy(alpha = 0.15f) else MatteCard)
                                                .border(if (sel) 1.2.dp else 0.5.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(11.dp))
                                                .clickable { viewModel.selectSpektraFilmStock(stock) }
                                                .padding(8.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(stock.swatchHex)))
                                                Text("ISO ${stock.iso}", color = InkSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(stock.shortName, color = if (sel) PentaxGreen else InkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }

                                // 4. Print Paper & Film Format
                                val activeStock = SpektraCatalog.findFilm(spk.filmStockId)
                                if (!activeStock.isSlide) {
                                    Text("放大机相纸 (底片缓存毫秒重印)", color = InkSecondary, fontSize = 11.sp)
                                    val matchingPapers = remember(activeStock.isBw) { SpektraCatalog.PRINT_PAPERS.filter { it.isBw == activeStock.isBw } }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(matchingPapers, key = { it.id }) { paper ->
                                            val sel = !spk.scanFilm && spk.printStockId == paper.id
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (sel) PentaxGreen.copy(alpha = 0.2f) else MatteCard)
                                                    .border(0.5.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                                                    .clickable { viewModel.selectSpektraPrintPaper(paper.id) }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                Text(paper.shortName, color = if (sel) PentaxGreen else InkPrimary, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }

                                Text("底片物理画幅（微米尺度颗粒与光晕）", color = InkSecondary, fontSize = 11.sp)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(FilmFormatSpec.entries) { fmt ->
                                        val sel = spk.filmFormat == fmt
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) PentaxGreen.copy(alpha = 0.2f) else MatteCard)
                                                .border(0.5.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                                                .clickable { viewModel.selectSpektraFilmFormat(fmt) }
                                                .padding(horizontal = 9.dp, vertical = 6.dp)
                                        ) {
                                            Text(fmt.label, color = if (sel) PentaxGreen else InkPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }

                                // 5. RAW Decode White Balance (Shown when a 14-bit PEF/DNG RAW is loaded!)
                                if (state.isRawSource) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MatteCard)
                                            .border(0.5.dp, WarmAmber.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("RAW 解码白平衡 (LibRaw DNG 色温)", color = WarmAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            if (spk.rawCustomWb) {
                                                Text("恢复拍摄白平衡", color = PentaxGreen, fontSize = 10.sp, modifier = Modifier.clickable { viewModel.resetRawWhiteBalanceToAsShot() })
                                            }
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("色温", color = InkPrimary, fontSize = 11.sp)
                                            Text("${spk.rawTempKelvin.toInt()} K", color = PentaxGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                        Slider(
                                            value = spk.rawTempKelvin,
                                            onValueChange = { viewModel.updateRawWhiteBalance(it, spk.rawTint) },
                                            valueRange = 2400f..10000f,
                                            colors = SliderDefaults.colors(thumbColor = WarmAmber, activeTrackColor = WarmAmber, inactiveTrackColor = ObsidianBg)
                                        )
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("色调 (绿 ↔ 品红)", color = InkPrimary, fontSize = 11.sp)
                                            Text("${"%+.0f".format(spk.rawTint)}", color = PentaxGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                        Slider(
                                            value = spk.rawTint,
                                            onValueChange = { viewModel.updateRawWhiteBalance(spk.rawTempKelvin, it) },
                                            valueRange = -60f..60f,
                                            colors = SliderDefaults.colors(thumbColor = WarmAmber, activeTrackColor = WarmAmber, inactiveTrackColor = ObsidianBg)
                                        )
                                    }
                                }

                                // 6. Optical Diffusion Filter (Black Pro-Mist / Glimmerglass / CineBloom)
                                Text("光学柔光滤镜（前置镜头物理扩散）", color = InkSecondary, fontSize = 11.sp)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(DiffusionFilterFamily.entries) { fam ->
                                        val sel = spk.diffusionFamily == fam
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) PentaxGreen.copy(alpha = 0.2f) else MatteCard)
                                                .border(0.5.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                                                .clickable { viewModel.selectDiffusionFamily(fam) }
                                                .padding(horizontal = 9.dp, vertical = 6.dp)
                                        ) {
                                            Text(fam.label, color = if (sel) PentaxGreen else InkPrimary, fontSize = 11.sp)
                                        }
                                    }
                                }
                                if (spk.diffusionFamily != DiffusionFilterFamily.OFF) {
                                    DarkroomSliderCard("柔光滤镜强度 (${spk.diffusionFamily.label})", "${"%.2f".format(spk.diffusionStrength)}", spk.diffusionStrength, 0f..2.0f) { v ->
                                        viewModel.updateSpektraParams { it.copy(diffusionStrength = v) }
                                    }
                                }

                                // 7. Physical Light Leak
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("胶片物理漏光 (Light Leak)", color = InkSecondary, fontSize = 11.sp)
                                    if (spk.leakKind != LightLeakKind.OFF) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp)).background(MatteCard)
                                                .clickable { viewModel.randomizeLightLeakSeed() }
                                                .padding(horizontal = 7.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Shuffle, null, tint = PentaxGreen, modifier = Modifier.size(12.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text("换随机形态", color = PentaxGreen, fontSize = 10.sp)
                                        }
                                    }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(LightLeakKind.entries) { kind ->
                                        val sel = spk.leakKind == kind
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) WarmAmber.copy(alpha = 0.2f) else MatteCard)
                                                .border(0.5.dp, if (sel) WarmAmber else HairlineBorder, RoundedCornerShape(8.dp))
                                                .clickable { viewModel.selectLightLeakKind(kind) }
                                                .padding(horizontal = 9.dp, vertical = 6.dp)
                                        ) {
                                            Text(kind.label, color = if (sel) WarmAmber else InkPrimary, fontSize = 11.sp)
                                        }
                                    }
                                }
                                if (spk.leakKind != LightLeakKind.OFF) {
                                    DarkroomSliderCard("漏光强度", "${"%.2f".format(spk.leakAmount)}", spk.leakAmount, 0f..3.0f, defaultValue = 1.0f) { v ->
                                        viewModel.updateSpektraParams { it.copy(leakAmount = v) }
                                    }
                                }

                                // 7.5 B&W Physical Wratten Optical Color Filters (auto-appears on Black & White stocks!)
                                if (activeStock.isBw) {
                                    Text("黑白物理色镜（81 波长柯达 Wratten 滤光片）", color = InkSecondary, fontSize = 11.sp)
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(BwCameraFilter.entries) { flt ->
                                            val sel = spk.cameraFilter == flt
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (sel) PentaxGreen.copy(alpha = 0.2f) else MatteCard)
                                                    .border(0.6.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                                                    .clickable { viewModel.selectBwCameraFilter(flt) }
                                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(flt.swatchHex)))
                                                Spacer(Modifier.width(5.dp))
                                                Text(flt.label, color = if (sel) PentaxGreen else InkPrimary, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }

                                // 8. Core 6-Stage Darkroom Sliders
                                DarkroomSliderCard("胶片曝光补偿 (环节 2)", "${"%+.2f".format(spk.exposureCompEv)} EV", spk.exposureCompEv, -4f..4f) { v ->
                                    viewModel.updateSpektraParams { it.copy(exposureCompEv = v, autoExposure = false) }
                                }
                                if (!spk.scanFilm) {
                                    DarkroomSliderCard("相纸亮度 (环节 4 · 毫秒重印)", "${"%+.2f".format(spk.printBrightnessEv)} EV", spk.printBrightnessEv, -2.5f..2.5f) { v ->
                                        viewModel.updateSpektraParams { it.copy(printBrightnessEv = v) }
                                    }
                                }
                                DarkroomSliderCard("高光物理光晕 Halation", "${"%.2f".format(spk.halationAmount)}", spk.halationAmount, 0f..3.0f, defaultValue = 1.0f) { v ->
                                    viewModel.updateSpektraParams { it.copy(halationActive = v > 0.02f, halationAmount = v) }
                                }
                                DarkroomSliderCard("物理银盐颗粒 Grain", "${"%.2f".format(spk.grainAmount)}", spk.grainAmount, 0f..2.0f, defaultValue = 1.0f) { v ->
                                    viewModel.updateSpektraParams { it.copy(grainActive = v > 0.02f, grainAmount = v) }
                                }
                                DarkroomSliderCard("DIR 成色剂层间抑制", "${"%.2f".format(spk.dirCouplersAmount)}", spk.dirCouplersAmount, 0f..1.6f, defaultValue = 1.0f) { v ->
                                    viewModel.updateSpektraParams { it.copy(dirCouplersActive = v > 0.02f, dirCouplersAmount = v) }
                                }

                                // 9. Physical Enlarger Calibration (refined ±25 CC global lamp shift, plus 1-tap jump to PS Blend Grading!)
                                if (!spk.scanFilm) {
                                    HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("物理放大机校色 (全局光源 · 建议微调)", color = InkSecondary, fontSize = 11.sp)
                                        if (abs(spk.mFilterShift) >= 0.01f || abs(spk.yFilterShift) >= 0.01f) {
                                            Text("归零", color = PentaxGreen, fontSize = 11.sp, modifier = Modifier.clickable { viewModel.resetSpektraEnlargerFilters() })
                                        }
                                    }
                                    DarkroomSliderCard("光源品红 ↔ 绿校正 (±25 CC)", "${"%+.0f".format(spk.mFilterShift * 25f)} CC", spk.mFilterShift, -1.0f..1.0f) { v ->
                                        viewModel.updateSpektraParams { it.copy(mFilterShift = v) }
                                    }
                                    DarkroomSliderCard("光源黄 ↔ 蓝校正 (±25 CC)", "${"%+.0f".format(spk.yFilterShift * 25f)} CC", spk.yFilterShift, -1.0f..1.0f) { v ->
                                        viewModel.updateSpektraParams { it.copy(yFilterShift = v) }
                                    }
                                }
                            }

                            // Quick jump banner to the dedicated PS Blend & 3-Way Color Balance studio
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PentaxGreen.copy(alpha = 0.14f))
                                    .border(0.6.dp, PentaxGreen.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                                    .clickable { activePanel = StudioPanelTab.PS_GRADE }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Rounded.Tune, null, tint = PentaxGreen, modifier = Modifier.size(15.dp))
                                    Text("分区不蒙灰调色 · 前往「PS 混色」工作台", color = PentaxGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text("→", color = PentaxGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    StudioPanelTab.PS_GRADE -> PsColorGradingPanel(state = state, viewModel = viewModel)

                    StudioPanelTab.CROP -> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("裁切画幅比例", color = InkSecondary, fontSize = 11.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(7.dp)).background(MatteCard)
                                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(7.dp))
                                            .clickable { viewModel.rotateQuarterTurn() }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Rotate90DegreesCw, null, tint = InkPrimary, modifier = Modifier.size(13.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("90°", color = InkPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    if (!cropCfg.isIdentity) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(7.dp)).background(MatteCard)
                                                .border(0.5.dp, HairlineBorder, RoundedCornerShape(7.dp))
                                                .clickable { viewModel.resetCropAndStraighten() }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.RestartAlt, null, tint = PentaxGreen, modifier = Modifier.size(13.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text("重置", color = PentaxGreen, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(CropAspectRatio.entries) { ratio ->
                                    val sel = cropCfg.aspectRatio == ratio
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) PentaxGreen.copy(alpha = 0.2f) else MatteCard)
                                            .border(0.6.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                                            .clickable { viewModel.updateCropAspectRatio(ratio) }
                                            .padding(horizontal = 11.dp, vertical = 7.dp)
                                    ) {
                                        Text(ratio.label, color = if (sel) PentaxGreen else InkPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MatteCard)
                                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("水平拉直（自动防黑边内切）", color = InkPrimary, fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${"%+.1f".format(cropCfg.straightenDegrees)}°", color = if (abs(cropCfg.straightenDegrees) >= 0.1f) PentaxGreen else InkSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        if (abs(cropCfg.straightenDegrees) >= 0.1f) {
                                            Text("归零", color = PentaxGreen, fontSize = 11.sp, modifier = Modifier.clickable { viewModel.updateStraightenDegrees(0f) })
                                        }
                                    }
                                }
                                Slider(
                                    value = cropCfg.straightenDegrees,
                                    onValueChange = { viewModel.updateStraightenDegrees(it, deferRender = true) },
                                    valueRange = -15f..15f,
                                    colors = SliderDefaults.colors(thumbColor = PentaxGreen, activeTrackColor = PentaxGreen, inactiveTrackColor = ObsidianBg)
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("裁切框内缩放（可直接在上方单指拖动/双指缩放）", color = InkPrimary, fontSize = 11.sp)
                                    Text("${"%.2f".format(cropCfg.cropZoom)}x", color = PentaxGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = cropCfg.cropZoom,
                                    onValueChange = { z -> viewModel.updateCropPanZoom(z, cropCfg.panX, cropCfg.panY) },
                                    valueRange = 1f..4f,
                                    colors = SliderDefaults.colors(thumbColor = PentaxGreen, activeTrackColor = PentaxGreen, inactiveTrackColor = ObsidianBg)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PentaxGreen.copy(alpha = 0.18f))
                                    .border(0.6.dp, PentaxGreen, RoundedCornerShape(10.dp))
                                    .clickable {
                                        viewModel.commitCropPreview()
                                        activePanel = StudioPanelTab.LUT
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("应用当前裁切并返回预览", color = PentaxGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    StudioPanelTab.TEMPLATES -> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("启用宾得水印画框（关掉可纯暗房出图）", color = InkSecondary, fontSize = 11.sp)
                                Switch(
                                    checked = cfg.watermarkEnabled,
                                    onCheckedChange = { enabled -> viewModel.updateWatermarkConfig { it.copy(watermarkEnabled = enabled) } },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PentaxGreen, uncheckedTrackColor = ObsidianBg, uncheckedBorderColor = HairlineBorder)
                                )
                            }
                            if (cfg.watermarkEnabled) {
                                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(WatermarkStyle.entries) { style ->
                                        WatermarkTemplateCard(
                                            style = style, selected = cfg.style == style,
                                            resolvedAccent = Color(cfg.resolveAccentForSurface(style.isDarkBackground)),
                                            onClick = { viewModel.updateWatermarkConfig { it.copy(style = style) } }
                                        )
                                    }
                                }
                                Text("${cfg.style.title} · ${cfg.style.subtitle}", color = InkSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }

                    StudioPanelTab.APPEARANCE -> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("官方 PENTAX 标与点缀色", color = InkSecondary, fontSize = 12.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                items(PentaxAccentColor.entries) { accent ->
                                    val chosen = cfg.accentColor == accent
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp)).background(if (chosen) MatteCard else ObsidianBg)
                                            .border(if (chosen) 1.dp else 0.5.dp, if (chosen) PentaxGreen else HairlineBorder, RoundedCornerShape(10.dp))
                                            .clickable { viewModel.updateWatermarkConfig { it.copy(accentColor = accent) } }
                                            .padding(horizontal = 11.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(accent.colorInt)).border(0.5.dp, Color(0x66FFFFFF), CircleShape))
                                        Spacer(Modifier.width(6.dp))
                                        Text(accent.label, color = if (chosen) PentaxGreen else InkPrimary, fontSize = 12.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MatteCard)
                                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                            ) {
                                StudioToggleRow("显示拍摄日期时间", cfg.showDateTime) { v -> viewModel.updateWatermarkConfig { it.copy(showDateTime = v) } }
                                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                                StudioToggleRow("显示镜头名称（无元数据自动隐藏）", cfg.showLensName) { v -> viewModel.updateWatermarkConfig { it.copy(showLensName = v) } }
                                if (cfg.style == WatermarkStyle.MINIMAL_WHITE) {
                                    HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                                    StudioToggleRow("画面底缘叠加半透明署名角标", cfg.showOverlaySignature) { v -> viewModel.updateWatermarkConfig { it.copy(showOverlaySignature = v) } }
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                                StudioToggleRow("画面右下角发光复古红字日期 ('26 11 24)", cfg.showVintageDateImprint) { v -> viewModel.updateWatermarkConfig { it.copy(showVintageDateImprint = v) } }
                                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                                StudioToggleRow("原分辨率高清导出 (自动回写 EXIF/IPTC)", cfg.highQualityExport) { v -> viewModel.updateWatermarkConfig { it.copy(highQualityExport = v) } }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    StudioPanelTab.EXIF -> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("常用镜头快捷填入（点击即填）", color = InkSecondary, fontSize = 11.sp)
                                if (cfg.lensTitle.isNotBlank() && !lensPresets.contains(cfg.lensTitle.trim())) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp)).background(PentaxGreen.copy(alpha = 0.16f))
                                            .clickable { viewModel.addLensPreset(cfg.lensTitle) }
                                            .padding(horizontal = 7.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Add, null, tint = PentaxGreen, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("存为常用", color = PentaxGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            if (lensPresets.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(lensPresets) { preset ->
                                        val active = cfg.lensTitle == preset
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (active) PentaxGreen.copy(alpha = 0.18f) else MatteCard)
                                                .border(0.5.dp, if (active) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                                                .clickable { viewModel.updateWatermarkConfig { it.copy(lensTitle = preset, showLensName = true) } }
                                                .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(preset, color = if (active) PentaxGreen else InkPrimary, fontSize = 11.sp)
                                            Spacer(Modifier.width(4.dp))
                                            Icon(Icons.Rounded.Close, "删除预设", tint = InkSecondary, modifier = Modifier.size(14.dp).clickable { viewModel.removeLensPreset(preset) })
                                        }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StudioField("机身型号", cfg.cameraTitle, Modifier.weight(1f)) { v -> viewModel.updateWatermarkConfig { it.copy(cameraTitle = v) } }
                                StudioField(if (cfg.lensTitle.isBlank()) "镜头 (留空不显示)" else "镜头名称", cfg.lensTitle, Modifier.weight(1.35f)) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(lensTitle = v) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StudioField("焦距", cfg.focalLength, Modifier.weight(1f)) { v -> viewModel.updateWatermarkConfig { it.copy(focalLength = v) } }
                                StudioField("光圈", cfg.aperture, Modifier.weight(1f)) { v -> viewModel.updateWatermarkConfig { it.copy(aperture = v) } }
                                StudioField("快门", cfg.shutterSpeed, Modifier.weight(1f)) { v -> viewModel.updateWatermarkConfig { it.copy(shutterSpeed = v) } }
                                StudioField("ISO", cfg.iso, Modifier.weight(1f)) { v -> viewModel.updateWatermarkConfig { it.copy(iso = v) } }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StudioField("拍摄时间", cfg.dateTime, Modifier.weight(1.1f)) { v -> viewModel.updateWatermarkConfig { it.copy(dateTime = v) } }
                                StudioField("摄影师 / Artist", cfg.signature, Modifier.weight(1f)) { v -> viewModel.updateWatermarkConfig { it.copy(signature = v) } }
                            }
                            StudioField("版权声明 / IPTC Copyright (自动写入 EXIF)", cfg.copyrightNotice, Modifier.fillMaxWidth()) { v ->
                                viewModel.updateWatermarkConfig { it.copy(copyrightNotice = v) }
                            }

                            // Manual Vintage Lens (K/M/A/M42) Focal-Length -> Lens Name Auto-Mapping
                            HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                            Text("宾得老手动头按焦段自动填镜头名 (K/M/A/M42)", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "当 K-1 导入的照片只有防抖焦距而无镜头名时，自动按下方映射写入水印与 EXIF",
                                color = InkSecondary,
                                fontSize = 10.sp
                            )
                            if (manualLensFocalMap.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(manualLensFocalMap.entries.toList()) { entry ->
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MatteCard)
                                                .border(0.5.dp, PentaxGreen.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("${entry.key}mm", color = PentaxGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.width(4.dp))
                                            Text(entry.value, color = InkPrimary, fontSize = 10.sp)
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                Icons.Rounded.Close,
                                                contentDescription = "删除映射",
                                                tint = InkSecondary,
                                                modifier = Modifier
                                                    .size(13.dp)
                                                    .clickable { viewModel.removeManualLensMapping(entry.key) }
                                            )
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newMapFocalText,
                                    onValueChange = { s -> newMapFocalText = s.filter { it.isDigit() }.take(4) },
                                    label = { Text("焦距mm", fontSize = 10.sp) },
                                    placeholder = { Text("50", fontSize = 11.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(9.dp),
                                    textStyle = TextStyle(color = InkPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = PentaxGreen, unfocusedBorderColor = HairlineBorder,
                                        focusedContainerColor = MatteCard, unfocusedContainerColor = MatteCard
                                    ),
                                    modifier = Modifier.width(78.dp)
                                )
                                OutlinedTextField(
                                    value = newMapLensText,
                                    onValueChange = { newMapLensText = it },
                                    label = { Text("对应老镜头名", fontSize = 10.sp) },
                                    placeholder = { Text("smc PENTAX-A 50mmF1.2", fontSize = 11.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(9.dp),
                                    textStyle = TextStyle(color = InkPrimary, fontSize = 12.sp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = PentaxGreen, unfocusedBorderColor = HairlineBorder,
                                        focusedContainerColor = MatteCard, unfocusedContainerColor = MatteCard
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(PentaxGreen)
                                        .clickable {
                                            val f = newMapFocalText.toIntOrNull()
                                            if (f != null && newMapLensText.isNotBlank()) {
                                                viewModel.setManualLensMapping(f, newMapLensText)
                                                newMapFocalText = ""
                                                newMapLensText = ""
                                            }
                                        }
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("添加", color = Color(0xFF04170B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            // Sticky Export + Share Footer
            HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
            Row(
                modifier = Modifier.fillMaxWidth().background(ElevatedSurface).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f).height(45.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (state.isSaving || (state.sourceBitmap == null && !state.isRawSource)) MatteCard else PentaxGreen)
                        .clickable(enabled = !state.isSaving && (state.sourceBitmap != null || state.isRawSource)) { viewModel.exportWatermarkedPhoto() },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(color = PentaxGreen, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在全分辨率导出...", color = InkPrimary, fontSize = 13.sp)
                    } else {
                        Icon(Icons.Rounded.Save, null, tint = Color(0xFF04170B), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (cfg.watermarkEnabled) "保存成片 (保留EXIF)" else "保存调色成片", color = Color(0xFF04170B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row(
                    modifier = Modifier
                        .width(92.dp).height(45.dp).clip(RoundedCornerShape(12.dp)).background(MatteCard)
                        .border(0.5.dp, HairlineBorder, RoundedCornerShape(12.dp))
                        .clickable(enabled = !state.isSharing && (state.sourceBitmap != null || state.isRawSource)) { viewModel.shareWatermarkedPhoto() },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isSharing) {
                        CircularProgressIndicator(color = PentaxGreen, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Rounded.Share, null, tint = InkPrimary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("分享", color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * Full-Screen Searchable 121-Film Stock Catalog Overlay (In-Window, Zero Dialog Clipping!)
 */
@Composable
private fun HeaderToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp)).background(MatteCard)
            .border(0.5.dp, HairlineBorder, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, color = InkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SpektraLatitudeMeterView(lat: SpektraLatitudeReadout) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (lat.histogram.size >= 4) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp))
                    .background(ObsidianBg).padding(horizontal = 3.dp, vertical = 2.dp)
            ) {
                val bins = lat.histogram.size
                val step = size.width / (bins - 1)
                val path = Path().apply {
                    moveTo(0f, size.height)
                    for (i in 0 until bins) lineTo(i * step, size.height * (1f - lat.histogram[i]))
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path, color = Color(0x88E5E7EB))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${"%.2f".format(lat.shadowEv)} EV", color = InkSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("${"%.2f".format(lat.retainedStops)} 档宽容度保留", color = InkPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("+${"%.2f".format(lat.highlightEv)} EV", color = InkSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
internal fun DarkroomSliderCard(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float = 0f,
    onValueChange: (Float) -> Unit
) {
    val isModified = abs(value - defaultValue) >= 0.015f
    Column(
        modifier = Modifier
            .fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(MatteCard)
            .border(0.5.dp, if (isModified) PentaxGreen.copy(alpha = 0.4f) else HairlineBorder, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = InkPrimary, fontSize = 11.sp)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isModified) PentaxGreen.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(enabled = isModified) { onValueChange(defaultValue.coerceIn(range.start, range.endInclusive)) }
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = valueLabel,
                    color = if (isModified) PentaxGreen else InkSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (isModified) {
                    Spacer(Modifier.width(3.dp))
                    Text("↺", color = PentaxGreen, fontSize = 10.sp)
                }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = PentaxGreen, activeTrackColor = PentaxGreen, inactiveTrackColor = ObsidianBg)
        )
    }
}

@Composable
internal fun PreviewMicroBadge(text: String, tint: Color = PentaxGreen) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp)).background(Color(0xB8000000))
            .border(0.5.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text, color = tint, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

