package com.pentax.k1sync.ui.watermark

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.pentax.k1sync.viewmodel.MainViewModel
import com.pentax.k1sync.watermark.PentaxAccentColor
import com.pentax.k1sync.watermark.WatermarkStyle

private enum class StudioPanelTab(val label: String) {
    TEMPLATES("模板 (7)"),
    APPEARANCE("外观"),
    EXIF("参数微调")
}

@Composable
fun WatermarkScreen(viewModel: MainViewModel) {
    val state by viewModel.watermarkState.collectAsState()
    val cfg = state.config

    var activePanel by remember { mutableStateOf(StudioPanelTab.TEMPLATES) }
    var showFullPreview by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.loadLocalPhotoForWatermark(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        // 1. Minimal Studio Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElevatedSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("宾得水印工坊", color = InkPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("官方矢量徽标 · 自动解析 EXIF/MakerNote", color = InkSecondary, fontSize = 11.sp)
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
                    .clickable {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, tint = PentaxGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("选手机图", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        // 2. Generous Studio Preview Stage (Proportional weight so image always breathes cleanly)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.05f)
                .background(Color(0xFF070709))
                .clickable(enabled = state.previewBitmap != null) { showFullPreview = true }
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            if (state.previewBitmap != null) {
                Image(
                    bitmap = state.previewBitmap!!.asImageBitmap(),
                    contentDescription = "Watermark Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (state.isRendering) {
                CircularProgressIndicator(color = PentaxGreen, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            if (state.previewBitmap != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(Color(0x99000000))
                        .padding(6.dp)
                ) {
                    Icon(Icons.Rounded.ZoomIn, contentDescription = "全屏预览", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }

        // 3. Sleek Tabbed Control Sheet
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.95f)
                .background(ElevatedSurface)
        ) {
            SegmentedPillRow(
                items = StudioPanelTab.entries,
                selectedItem = activePanel,
                labelProvider = { it.label },
                onSelect = { activePanel = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (activePanel) {
                    StudioPanelTab.TEMPLATES -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(WatermarkStyle.entries) { style ->
                                    WatermarkTemplateCard(
                                        style = style,
                                        selected = cfg.style == style,
                                        onClick = {
                                            viewModel.updateWatermarkConfig { it.copy(style = style) }
                                        }
                                    )
                                }
                            }
                            Text(
                                text = "${cfg.style.title} · ${cfg.style.subtitle}",
                                color = InkSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    StudioPanelTab.APPEARANCE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("官方 PENTAX 徽标配色", color = InkSecondary, fontSize = 12.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PentaxAccentColor.entries.forEach { accent ->
                                    val chosen = cfg.accentColor == accent
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (chosen) MatteCard else ObsidianBg)
                                            .border(
                                                width = if (chosen) 1.dp else 0.5.dp,
                                                color = if (chosen) PentaxGreen else HairlineBorder,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                viewModel.updateWatermarkConfig { it.copy(accentColor = accent) }
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(11.dp)
                                                .clip(CircleShape)
                                                .background(Color(accent.colorInt))
                                                .border(0.5.dp, Color(0x55FFFFFF), CircleShape)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(accent.label, color = InkPrimary, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MatteCard)
                                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                            ) {
                                StudioToggleRow("显示拍摄日期时间", cfg.showDateTime) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(showDateTime = v) }
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                                StudioToggleRow("显示镜头名称（无镜头信息自动隐藏）", cfg.showLensName) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(showLensName = v) }
                                }
                                if (cfg.style == WatermarkStyle.MINIMAL_WHITE) {
                                    HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                                    StudioToggleRow("画面底缘叠加半透明署名角标", cfg.showOverlaySignature) { v ->
                                        viewModel.updateWatermarkConfig { it.copy(showOverlaySignature = v) }
                                    }
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                                StudioToggleRow("原分辨率高清导出 (JPG 97%)", cfg.highQualityExport) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(highQualityExport = v) }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    StudioPanelTab.EXIF -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StudioField("机身型号", cfg.cameraTitle, Modifier.weight(1f)) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(cameraTitle = v) }
                                }
                                StudioField(
                                    label = if (cfg.lensTitle.isBlank()) "镜头 (留空不显示)" else "镜头名称",
                                    value = cfg.lensTitle,
                                    modifier = Modifier.weight(1.3f)
                                ) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(lensTitle = v) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StudioField("焦距", cfg.focalLength, Modifier.weight(1f)) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(focalLength = v) }
                                }
                                StudioField("光圈", cfg.aperture, Modifier.weight(1f)) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(aperture = v) }
                                }
                                StudioField("快门", cfg.shutterSpeed, Modifier.weight(1f)) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(shutterSpeed = v) }
                                }
                                StudioField("ISO", cfg.iso, Modifier.weight(1f)) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(iso = v) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StudioField("拍摄时间", cfg.dateTime, Modifier.weight(1.2f)) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(dateTime = v) }
                                }
                                StudioField("摄影师署名", cfg.signature, Modifier.weight(1f)) { v ->
                                    viewModel.updateWatermarkConfig { it.copy(signature = v) }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            // Sticky Export Footer (Always visible!)
            HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (state.isSaving || state.sourceBitmap == null) MatteCard else PentaxGreen)
                        .clickable(enabled = !state.isSaving && state.sourceBitmap != null) {
                            viewModel.exportWatermarkedPhoto()
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(color = PentaxGreen, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在高清渲染导出...", color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    } else {
                        Icon(Icons.Rounded.Save, null, tint = Color(0xFF04170B), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存宾得水印照到相册", color = Color(0xFF04170B), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Fullscreen Watermark Inspection Dialog
    if (showFullPreview && state.previewBitmap != null) {
        Dialog(
            onDismissRequest = { showFullPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .systemBarsPadding()
                    .clickable { showFullPreview = false },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = state.previewBitmap!!.asImageBitmap(),
                    contentDescription = "Fullscreen Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp)
                )
                IconButton(
                    onClick = { showFullPreview = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun WatermarkTemplateCard(
    style: WatermarkStyle,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PentaxGreen.copy(alpha = 0.14f) else MatteCard)
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) PentaxGreen else HairlineBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Miniature Visual Wireframe Icon showing the exact frame layout
        TemplateWireframeThumbnail(style = style, modifier = Modifier.fillMaxWidth().height(54.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = style.title,
            color = if (selected) PentaxGreen else InkPrimary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = style.shortTag,
            color = InkSecondary,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun TemplateWireframeThumbnail(style: WatermarkStyle, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF101014))
    ) {
        val w = size.width
        val h = size.height
        val photoColor = Color(0xFF3A4B5C)
        val whitePaper = Color(0xFFF4F4F6)
        val redMark = PentaxRed

        when (style) {
            WatermarkStyle.CLASSIC_DIVIDER -> {
                drawRect(whitePaper)
                drawRect(photoColor, topLeft = Offset.Zero, size = Size(w, h * 0.74f))
                drawRect(Color(0xFF222226), Offset(w * 0.08f, h * 0.84f), Size(w * 0.24f, h * 0.07f))
                drawRect(redMark, Offset(w * 0.45f, h * 0.84f), Size(w * 0.16f, h * 0.07f))
                drawRect(Color(0xFF888890), Offset(w * 0.67f, h * 0.82f), Size(w * 0.24f, h * 0.05f))
                drawRect(Color(0xFFBBBBBF), Offset(w * 0.67f, h * 0.90f), Size(w * 0.18f, h * 0.04f))
            }
            WatermarkStyle.MINIMAL_WHITE -> {
                drawRect(whitePaper)
                drawRect(photoColor, topLeft = Offset.Zero, size = Size(w, h * 0.76f))
                drawRect(Color(0xFF222226), Offset(w * 0.08f, h * 0.85f), Size(w * 0.22f, h * 0.06f))
                drawRect(redMark, Offset(w * 0.33f, h * 0.86f), Size(h * 0.05f, h * 0.05f))
                drawRect(Color(0xFF55555C), Offset(w * 0.64f, h * 0.85f), Size(w * 0.28f, h * 0.06f))
            }
            WatermarkStyle.BLUR_FRAME -> {
                drawRect(Color(0xFF293544))
                drawRoundRect(
                    color = photoColor,
                    topLeft = Offset(w * 0.11f, h * 0.10f),
                    size = Size(w * 0.78f, h * 0.62f),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                drawRect(Color.White, Offset(w * 0.32f, h * 0.80f), Size(w * 0.36f, h * 0.05f))
                drawRect(Color(0xAAFFFFFF), Offset(w * 0.26f, h * 0.89f), Size(w * 0.48f, h * 0.04f))
            }
            WatermarkStyle.GALLERY_POLAROID -> {
                drawRect(whitePaper)
                drawRect(photoColor, Offset(w * 0.10f, h * 0.09f), Size(w * 0.80f, h * 0.58f))
                drawRect(redMark, Offset(w * 0.37f, h * 0.74f), Size(w * 0.26f, h * 0.06f))
                drawRect(Color(0xFF7A7A82), Offset(w * 0.28f, h * 0.86f), Size(w * 0.44f, h * 0.05f))
            }
            WatermarkStyle.DARKROOM_BLACK -> {
                drawRect(Color(0xFF101012))
                drawRect(photoColor, topLeft = Offset.Zero, size = Size(w, h * 0.74f))
                drawRect(Color.White, Offset(w * 0.08f, h * 0.84f), Size(w * 0.24f, h * 0.07f))
                drawRect(PentaxGreen, Offset(w * 0.46f, h * 0.84f), Size(w * 0.16f, h * 0.07f))
                drawRect(Color(0xFFAAAAAA), Offset(w * 0.68f, h * 0.85f), Size(w * 0.24f, h * 0.05f))
            }
            WatermarkStyle.FILM_35MM -> {
                drawRect(Color(0xFF141312))
                drawRect(photoColor, Offset(w * 0.04f, h * 0.20f), Size(w * 0.92f, h * 0.60f))
                for (i in 0..5) {
                    val x = w * (0.08f + i * 0.16f)
                    drawRect(Color(0xFFE6E0D6), Offset(x, h * 0.05f), Size(w * 0.07f, h * 0.08f))
                    drawRect(Color(0xFFE6E0D6), Offset(x, h * 0.87f), Size(w * 0.07f, h * 0.08f))
                }
                drawRect(Color(0xFFE8A244), Offset(w * 0.08f, h * 0.81f), Size(w * 0.32f, h * 0.04f))
            }
            WatermarkStyle.OVERLAY_MINIMAL -> {
                drawRect(photoColor)
                drawRect(Color(0x88000000), Offset(0f, h * 0.72f), Size(w, h * 0.28f))
                drawRect(Color.White, Offset(w * 0.07f, h * 0.82f), Size(w * 0.26f, h * 0.07f))
                drawRect(Color(0xCCFFFFFF), Offset(w * 0.60f, h * 0.83f), Size(w * 0.33f, h * 0.06f))
            }
        }
    }
}

@Composable
private fun StudioField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        textStyle = TextStyle(color = InkPrimary, fontSize = 12.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PentaxGreen,
            unfocusedBorderColor = HairlineBorder,
            focusedContainerColor = ObsidianBg,
            unfocusedContainerColor = ObsidianBg,
            focusedLabelColor = PentaxGreen,
            unfocusedLabelColor = InkSecondary
        )
    )
}

@Composable
private fun StudioToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = InkPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
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
