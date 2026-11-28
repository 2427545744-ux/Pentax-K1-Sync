package com.pentax.k1sync.ui.watermark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pentax.k1sync.image.ColorBalanceZone
import com.pentax.k1sync.image.CurveChannel
import com.pentax.k1sync.image.PS_BLEND_SWATCHES
import com.pentax.k1sync.image.PostDarkroomAdjuster
import com.pentax.k1sync.image.PostToneConfig
import com.pentax.k1sync.image.PsBlendMode
import com.pentax.k1sync.image.PsBlendRange
import com.pentax.k1sync.image.PsGradePreset
import com.pentax.k1sync.image.ToneCurvePreset
import com.pentax.k1sync.ui.theme.HairlineBorder
import com.pentax.k1sync.ui.theme.InkPrimary
import com.pentax.k1sync.ui.theme.InkSecondary
import com.pentax.k1sync.ui.theme.MatteCard
import com.pentax.k1sync.ui.theme.ObsidianBg
import com.pentax.k1sync.ui.theme.PentaxGreen
import com.pentax.k1sync.ui.theme.SubtleDivider
import com.pentax.k1sync.ui.theme.WarmAmber
import com.pentax.k1sync.viewmodel.MainViewModel
import com.pentax.k1sync.viewmodel.WatermarkEditorState
import kotlin.math.abs

private enum class PsSubTab(val label: String) {
    BASIC("预设/基础"),
    CURVE("色调曲线"),
    BALANCE("三区/混色"),
    HSL_POLISH("HSL/质感")
}

@Composable
internal fun PsColorGradingPanel(
    state: WatermarkEditorState,
    viewModel: MainViewModel
) {
    val pt = state.postTone
    var activeSubTab by remember { mutableStateOf(PsSubTab.BASIC) }
    var activeCbZone by remember { mutableStateOf(ColorBalanceZone.SHADOWS) }
    var activeCurveChannel by remember { mutableStateOf(CurveChannel.LUMA) }

    val basicModified = pt.hasWhiteBalance || pt.hasToneAdjust || abs(pt.dehaze) >= 0.01f || abs(pt.vibrance) >= 0.01f || abs(pt.saturation) >= 0.01f
    val curveModified = pt.hasToneCurve
    val balanceModified = pt.hasColorBalance || pt.hasBlendLayer
    val hslModified = pt.hasHslAdjust || abs(pt.clarity) >= 0.01f || pt.sharpen >= 0.01f || abs(pt.vignette) >= 0.01f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PsSubTab.entries.forEach { tab ->
                val sel = activeSubTab == tab
                val dot = when (tab) {
                    PsSubTab.BASIC -> basicModified
                    PsSubTab.CURVE -> curveModified
                    PsSubTab.BALANCE -> balanceModified
                    PsSubTab.HSL_POLISH -> hslModified
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) PentaxGreen.copy(alpha = 0.20f) else MatteCard)
                        .border(0.6.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                        .clickable { activeSubTab = tab }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tab.label,
                        color = if (sel) PentaxGreen else InkPrimary,
                        fontSize = 10.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                    if (dot) {
                        Spacer(Modifier.width(3.dp))
                        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(WarmAmber))
                    }
                }
            }
            if (!pt.isIdentity) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ObsidianBg)
                        .border(0.5.dp, HairlineBorder, RoundedCornerShape(8.dp))
                        .clickable { viewModel.resetPostTone() }
                        .padding(horizontal = 7.dp, vertical = 6.dp)
                ) {
                    Text("重置", color = WarmAmber, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            when (activeSubTab) {
                PsSubTab.BASIC -> {
                    Text("PS 混色风格预设（一键套用 · 可二次精调）", color = InkSecondary, fontSize = 11.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(PsGradePreset.entries) { preset ->
                            val isCurrent = (preset == PsGradePreset.NONE && pt.isIdentity) || (preset != PsGradePreset.NONE && pt == preset.config)
                            Column(
                                modifier = Modifier
                                    .width(106.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isCurrent) PentaxGreen.copy(alpha = 0.16f) else MatteCard)
                                    .border(0.7.dp, if (isCurrent) PentaxGreen else HairlineBorder, RoundedCornerShape(10.dp))
                                    .clickable { viewModel.applyPsGradePreset(preset) }
                                    .padding(horizontal = 9.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(preset.primaryHex)))
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(preset.secondaryHex)))
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        preset.label,
                                        color = if (isCurrent) PentaxGreen else InkPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(preset.desc, color = InkSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("通用白平衡（JPG / 暗房全格式生效）", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (pt.hasWhiteBalance) {
                            Text(
                                "归零白平衡",
                                color = PentaxGreen,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { viewModel.updatePostTone { it.copy(temperature = 0f, tint = 0f) } }
                            )
                        }
                    }
                    DarkroomSliderCard("色温 · 冷蓝 ↔ 暖金 (Temp)", "${"%+.0f".format(pt.temperature * 100f)}", pt.temperature, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(temperature = v) }
                    }
                    DarkroomSliderCard("色调 · 偏绿 ↔ 洋红 (Tint)", "${"%+.0f".format(pt.tint * 100f)}", pt.tint, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(tint = v) }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                    Text("光影塑形 · 去雾 · 自然饱和度", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    DarkroomSliderCard("高光 (Highlights)", "${"%+.0f".format(pt.highlights * 100f)}", pt.highlights, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(highlights = v) }
                    }
                    DarkroomSliderCard("阴影 (Shadows)", "${"%+.0f".format(pt.shadows * 100f)}", pt.shadows, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(shadows = v) }
                    }
                    DarkroomSliderCard("对比度 (Contrast)", "${"%+.0f".format(pt.contrast * 100f)}", pt.contrast, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(contrast = v) }
                    }
                    DarkroomSliderCard("去雾 · 柔雾 ↔ 通透去霾 (Dehaze)", "${"%+.0f".format(pt.dehaze * 100f)}", pt.dehaze, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(dehaze = v) }
                    }
                    DarkroomSliderCard("自然饱和度 (PS Vibrance · 保护肤色)", "${"%+.0f".format(pt.vibrance * 100f)}", pt.vibrance, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(vibrance = v) }
                    }
                    DarkroomSliderCard("全局饱和度 (Saturation)", "${"%+.0f".format(pt.saturation * 100f)}", pt.saturation, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(saturation = v) }
                    }
                }

                PsSubTab.CURVE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("交互式色调曲线（5点平滑样条 · 双击节点归零）", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (pt.hasChannelCurve(activeCurveChannel)) {
                            Text(
                                "归零${activeCurveChannel.label}",
                                color = PentaxGreen,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    viewModel.updatePostTone { it.withCurve(activeCurveChannel, PostToneConfig.DEFAULT_CURVE) }
                                }
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CurveChannel.entries.forEach { ch ->
                            val sel = activeCurveChannel == ch
                            val mod = pt.hasChannelCurve(ch)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) Color(ch.accentHex).copy(alpha = 0.20f) else MatteCard)
                                    .border(0.7.dp, if (sel) Color(ch.accentHex) else HairlineBorder, RoundedCornerShape(8.dp))
                                    .clickable { activeCurveChannel = ch }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Color(ch.accentHex)))
                                Spacer(Modifier.width(4.dp))
                                Text(ch.label, color = if (sel) InkPrimary else InkSecondary, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                if (mod) {
                                    Spacer(Modifier.width(3.dp))
                                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(WarmAmber))
                                }
                            }
                        }
                    }

                    val curCurvePoints = pt.curveFor(activeCurveChannel)
                    ToneCurveEditorCanvas(
                        points = curCurvePoints,
                        curveColor = Color(activeCurveChannel.accentHex),
                        onPointsChanged = { updatedPts ->
                            viewModel.updatePostTone { it.withCurve(activeCurveChannel, updatedPts) }
                        }
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ToneCurvePreset.entries) { cp ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(MatteCard)
                                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(7.dp))
                                    .clickable {
                                        viewModel.updatePostTone { it.withCurve(activeCurveChannel, cp.points) }
                                    }
                                    .padding(horizontal = 9.dp, vertical = 6.dp)
                            ) {
                                Text(cp.label, color = InkPrimary, fontSize = 11.sp)
                            }
                        }
                    }
                }

                PsSubTab.BALANCE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("PS 三区色彩平衡（黑白锚定不蒙灰）", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (pt.hasZoneChanges(activeCbZone)) {
                            Text(
                                "归零${activeCbZone.label}",
                                color = PentaxGreen,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { viewModel.resetPsColorBalanceZone(activeCbZone) }
                            )
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ColorBalanceZone.entries.forEach { zone ->
                            val sel = activeCbZone == zone
                            val hasMod = pt.hasZoneChanges(zone)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (sel) PentaxGreen.copy(alpha = 0.2f) else MatteCard)
                                    .border(0.7.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(9.dp))
                                    .clickable { activeCbZone = zone }
                                    .padding(vertical = 7.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    zone.label,
                                    color = if (sel) PentaxGreen else InkPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                                )
                                if (hasMod) {
                                    Spacer(Modifier.width(4.dp))
                                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(WarmAmber))
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MatteCard)
                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("保持明度 (Preserve Luminosity)", color = InkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Text("调色不改曝光反差，锁定纯黑与纸白端点", color = InkSecondary, fontSize = 9.sp)
                        }
                        Switch(
                            checked = pt.preserveLuminosity,
                            onCheckedChange = { checked -> viewModel.updatePostTone { it.copy(preserveLuminosity = checked) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF04170B), checkedTrackColor = PentaxGreen)
                        )
                    }

                    val (curCR, curMG, curYB) = when (activeCbZone) {
                        ColorBalanceZone.SHADOWS -> Triple(pt.shadowCyanRed, pt.shadowMagentaGreen, pt.shadowYellowBlue)
                        ColorBalanceZone.MIDTONES -> Triple(pt.midCyanRed, pt.midMagentaGreen, pt.midYellowBlue)
                        ColorBalanceZone.HIGHLIGHTS -> Triple(pt.highCyanRed, pt.highMagentaGreen, pt.highYellowBlue)
                    }
                    DarkroomSliderCard(
                        label = "${activeCbZone.label} · 青 Cyan ↔ 红 Red",
                        valueLabel = "${"%+.0f".format(curCR * 100f)}",
                        value = curCR,
                        range = -1f..1f
                    ) { v ->
                        viewModel.updatePostTone {
                            when (activeCbZone) {
                                ColorBalanceZone.SHADOWS -> it.copy(shadowCyanRed = v)
                                ColorBalanceZone.MIDTONES -> it.copy(midCyanRed = v)
                                ColorBalanceZone.HIGHLIGHTS -> it.copy(highCyanRed = v)
                            }
                        }
                    }
                    DarkroomSliderCard(
                        label = "${activeCbZone.label} · 洋红 Magenta ↔ 绿 Green",
                        valueLabel = "${"%+.0f".format(curMG * 100f)}",
                        value = curMG,
                        range = -1f..1f
                    ) { v ->
                        viewModel.updatePostTone {
                            when (activeCbZone) {
                                ColorBalanceZone.SHADOWS -> it.copy(shadowMagentaGreen = v)
                                ColorBalanceZone.MIDTONES -> it.copy(midMagentaGreen = v)
                                ColorBalanceZone.HIGHLIGHTS -> it.copy(highMagentaGreen = v)
                            }
                        }
                    }
                    DarkroomSliderCard(
                        label = "${activeCbZone.label} · 黄 Yellow ↔ 蓝 Blue",
                        valueLabel = "${"%+.0f".format(curYB * 100f)}",
                        value = curYB,
                        range = -1f..1f
                    ) { v ->
                        viewModel.updatePostTone {
                            when (activeCbZone) {
                                ColorBalanceZone.SHADOWS -> it.copy(shadowYellowBlue = v)
                                ColorBalanceZone.MIDTONES -> it.copy(midYellowBlue = v)
                                ColorBalanceZone.HIGHLIGHTS -> it.copy(highYellowBlue = v)
                            }
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                    Text("PS 混合模式调色层（柔光 / 颜色 / 叠加 / 滤色）", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(PsBlendMode.entries) { mode ->
                            val sel = pt.blendMode == mode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) WarmAmber.copy(alpha = 0.22f) else MatteCard)
                                    .border(0.6.dp, if (sel) WarmAmber else HairlineBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.updatePostTone {
                                            val nextOp = if (mode != PsBlendMode.NONE && it.blendOpacity < 0.02f) 0.18f else if (mode == PsBlendMode.NONE) 0f else it.blendOpacity
                                            it.copy(blendMode = mode, blendOpacity = nextOp)
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(mode.label, color = if (sel) WarmAmber else InkPrimary, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }

                    if (pt.blendMode != PsBlendMode.NONE) {
                        Text(pt.blendMode.desc, color = InkSecondary, fontSize = 10.sp)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            PsBlendRange.entries.forEach { rng ->
                                val sel = pt.blendRange == rng
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(if (sel) PentaxGreen.copy(alpha = 0.18f) else ObsidianBg)
                                        .border(0.5.dp, if (sel) PentaxGreen else HairlineBorder, RoundedCornerShape(7.dp))
                                        .clickable { viewModel.updatePostTone { it.copy(blendRange = rng) } }
                                        .padding(vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(rng.label, color = if (sel) PentaxGreen else InkSecondary, fontSize = 9.sp, maxLines = 1)
                                }
                            }
                        }

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(PS_BLEND_SWATCHES) { sw ->
                                val sel = abs(pt.blendHueDeg - sw.hueDeg) < 8f
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) Color(sw.argbHex).copy(alpha = 0.25f) else MatteCard)
                                        .border(0.6.dp, if (sel) Color(sw.argbHex) else HairlineBorder, RoundedCornerShape(8.dp))
                                        .clickable { viewModel.updatePostTone { it.copy(blendHueDeg = sw.hueDeg) } }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(sw.argbHex)))
                                    Spacer(Modifier.width(4.dp))
                                    Text(sw.label, color = InkPrimary, fontSize = 10.sp)
                                }
                            }
                        }

                        DarkroomSliderCard("混合色相 (Hue)", "${pt.blendHueDeg.toInt()}°", pt.blendHueDeg, 0f..360f, defaultValue = 198f) { v ->
                            viewModel.updatePostTone { it.copy(blendHueDeg = v) }
                        }
                        DarkroomSliderCard("混合不透明度 (Opacity)", "${(pt.blendOpacity * 100f).toInt()}%", pt.blendOpacity, 0f..0.85f) { v ->
                            viewModel.updatePostTone { it.copy(blendOpacity = v) }
                        }
                        DarkroomSliderCard("色层纯度 (Chroma)", "${(pt.blendChroma * 100f).toInt()}%", pt.blendChroma, 0.15f..1.0f, defaultValue = 0.68f) { v ->
                            viewModel.updatePostTone { it.copy(blendChroma = v) }
                        }
                    }
                }

                PsSubTab.HSL_POLISH -> {
                    Text("三大高频局部 HSL 精修（肤色 / 植被 / 天空）", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    DarkroomSliderCard("人像橙肤 · 明度 (Skin Lum)", "${"%+.0f".format(pt.skinLuminance * 100f)}", pt.skinLuminance, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(skinLuminance = v) }
                    }
                    DarkroomSliderCard("人像橙肤 · 饱和 (Skin Sat)", "${"%+.0f".format(pt.skinSaturation * 100f)}", pt.skinSaturation, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(skinSaturation = v) }
                    }
                    DarkroomSliderCard("植被绿 · 秋黄 ↔ 富士青翠 (Green Hue)", "${"%+.0f".format(pt.greenHue * 100f)}", pt.greenHue, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(greenHue = v) }
                    }
                    DarkroomSliderCard("植被绿 · 饱和度 (Green Sat)", "${"%+.0f".format(pt.greenSaturation * 100f)}", pt.greenSaturation, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(greenSaturation = v) }
                    }
                    DarkroomSliderCard("天空蓝 · 日系青 ↔ 深宝蓝 (Blue Hue)", "${"%+.0f".format(pt.blueHue * 100f)}", pt.blueHue, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(blueHue = v) }
                    }
                    DarkroomSliderCard("天空蓝 · 压暗云层 ↔ 通透 (Blue Lum)", "${"%+.0f".format(pt.blueLuminance * 100f)}", pt.blueLuminance, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(blueLuminance = v) }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                    Text("收尾质感（清晰度 · 输出锐化 · 光学暗角）", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    DarkroomSliderCard("清晰度 (Clarity · 中间调微反差)", "${"%+.0f".format(pt.clarity * 100f)}", pt.clarity, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(clarity = v) }
                    }
                    DarkroomSliderCard("输出亮度锐化 (Sharpen · 零彩噪)", "${(pt.sharpen * 100f).toInt()}%", pt.sharpen, 0f..1f) { v ->
                        viewModel.updatePostTone { it.copy(sharpen = v) }
                    }
                    DarkroomSliderCard("光学暗角 (Vignette · 暗角 ↔ 柔白)", "${"%+.0f".format(pt.vignette * 100f)}", pt.vignette, -1f..1f) { v ->
                        viewModel.updatePostTone { it.copy(vignette = v) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ToneCurveEditorCanvas(
    points: List<Float>,
    curveColor: Color,
    onPointsChanged: (List<Float>) -> Unit
) {
    val latestPoints by rememberUpdatedState(points)
    val latestOnChanged by rememberUpdatedState(onPointsChanged)
    var activeNode by remember { mutableIntStateOf(-1) }
    val lut = remember(points) { PostDarkroomAdjuster.buildCurveLut256(points) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0A0D))
            .border(0.6.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(152.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val idx = ((tap.x / w) * 4f + 0.5f).toInt().coerceIn(0, 4)
                            val next = latestPoints.toMutableList()
                            next[idx] = PostToneConfig.DEFAULT_CURVE[idx]
                            latestOnChanged(next)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            activeNode = ((start.x / w) * 4f + 0.5f).toInt().coerceIn(0, 4)
                        },
                        onDragEnd = { activeNode = -1 },
                        onDragCancel = { activeNode = -1 },
                        onDrag = { change, _ ->
                            change.consume()
                            val idx = activeNode
                            if (idx in 0..4) {
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val newY = (1f - change.position.y / h).coerceIn(0f, 1f)
                                val next = latestPoints.toMutableList()
                                next[idx] = newY
                                latestOnChanged(next)
                            }
                        }
                    )
                }
        ) {
            val pad = 10f
            val gw = (size.width - pad * 2f).coerceAtLeast(1f)
            val gh = (size.height - pad * 2f).coerceAtLeast(1f)

            val gridCol = Color(0x26FFFFFF)
            for (i in 0..4) {
                val gx = pad + gw * (i / 4f)
                val gy = pad + gh * (i / 4f)
                drawLine(gridCol, Offset(gx, pad), Offset(gx, pad + gh), 1f)
                drawLine(gridCol, Offset(pad, gy), Offset(pad + gw, gy), 1f)
            }
            drawLine(Color(0x38FFFFFF), Offset(pad, pad + gh), Offset(pad + gw, pad), 1.2f)

            val path = Path().apply {
                for (i in 0..64) {
                    val lutIdx = (i * 255) / 64
                    val x = pad + gw * (i / 64f)
                    val y = pad + gh * (1f - lut[lutIdx])
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(path, color = curveColor, style = Stroke(width = 3.0f, cap = StrokeCap.Round))

            for (i in 0..4) {
                val cx = pad + gw * (i / 4f)
                val cy = pad + gh * (1f - points.getOrElse(i) { PostToneConfig.DEFAULT_CURVE[i] })
                val isDragging = (i == activeNode)
                drawCircle(Color(0xFF0B0B0D), radius = if (isDragging) 11f else 8f, center = Offset(cx, cy))
                drawCircle(
                    color = if (isDragging) PentaxGreen else curveColor,
                    radius = if (isDragging) 11f else 8f,
                    center = Offset(cx, cy),
                    style = Stroke(width = if (isDragging) 3f else 2.2f)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("黑场", "阴影", "中间调", "高光", "白场").forEach { lbl ->
                Text(lbl, color = InkSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
