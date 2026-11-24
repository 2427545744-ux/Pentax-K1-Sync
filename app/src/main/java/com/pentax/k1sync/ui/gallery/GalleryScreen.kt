package com.pentax.k1sync.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pentax.k1sync.model.CameraPhoto
import com.pentax.k1sync.model.DownloadQuality
import com.pentax.k1sync.model.PhotoFormatFilter
import com.pentax.k1sync.model.GallerySlotFilter
import com.pentax.k1sync.ui.components.ConnectionPillBadge
import com.pentax.k1sync.ui.components.PentaxBrandHeader
import com.pentax.k1sync.ui.components.PentaxK1SilhouetteIcon
import com.pentax.k1sync.ui.components.SegmentedPillRow
import com.pentax.k1sync.ui.theme.ElevatedSurface
import com.pentax.k1sync.ui.theme.HairlineBorder
import com.pentax.k1sync.ui.theme.InkPrimary
import com.pentax.k1sync.ui.theme.InkSecondary
import com.pentax.k1sync.ui.theme.InkTertiary
import com.pentax.k1sync.ui.theme.MatteCard
import com.pentax.k1sync.ui.theme.ObsidianBg
import com.pentax.k1sync.ui.theme.PentaxGreen
import com.pentax.k1sync.ui.theme.PentaxRed
import com.pentax.k1sync.ui.theme.SubtleDivider
import com.pentax.k1sync.ui.theme.WarmAmber
import com.pentax.k1sync.viewmodel.AppTab
import com.pentax.k1sync.viewmodel.MainViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(viewModel: MainViewModel) {
    val cameraInfo by viewModel.cameraInfo.collectAsState()
    val activeSlot by viewModel.activeSlot.collectAsState()
    val formatFilter by viewModel.formatFilter.collectAsState()
    val starredOnly by viewModel.starredOnlyFilter.collectAsState()
    val starredCount by viewModel.starredCount.collectAsState()
    val columns by viewModel.galleryColumns.collectAsState()
    val pairRawJpg by viewModel.pairRawJpg.collectAsState()
    val photos by viewModel.displayPhotos.collectAsState()
    val isLoading by viewModel.isLoadingPhotos.collectAsState()
    val selectedIds by viewModel.selectedPhotoIds.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val isGeneratingCollage by viewModel.isGeneratingCollage.collectAsState()

    val groupedPhotos = remember(photos) { photos.groupBy { it.dateGroup } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Minimal Leica-inspired Top Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PentaxBrandHeader()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        ConnectionPillBadge(cameraInfo) { viewModel.selectTab(AppTab.SYNC) }
                        if (cameraInfo.connected) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MatteCard)
                                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.cycleGalleryColumns() }
                                    .padding(horizontal = 7.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.GridView, contentDescription = "网格密度", tint = InkSecondary, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("${columns}列", color = InkPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            IconButton(
                                onClick = { viewModel.refreshPhotos() },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = InkSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                if (cameraInfo.connected) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SegmentedPillRow(
                            items = GallerySlotFilter.entries,
                            selectedItem = activeSlot,
                            labelProvider = { it.displayName },
                            onSelect = { viewModel.switchStorageSlot(it) },
                            modifier = Modifier.weight(1.05f)
                        )
                        SegmentedPillRow(
                            items = PhotoFormatFilter.entries,
                            selectedItem = formatFilter,
                            labelProvider = { it.label },
                            onSelect = { viewModel.setFormatFilter(it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (activeSlot == GallerySlotFilter.ALL) "双卡按时间降序 · 共 ${photos.size} 张" else "${activeSlot.displayName} · 共 ${photos.size} 张",
                            color = InkSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            // ★ Starred Favorites Filter Chip
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (starredOnly) WarmAmber.copy(alpha = 0.20f) else ObsidianBg)
                                    .border(0.5.dp, if (starredOnly) WarmAmber else HairlineBorder, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.toggleStarredOnlyFilter() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Star,
                                    contentDescription = "星标精选",
                                    tint = if (starredOnly || starredCount > 0) WarmAmber else InkSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = if (starredCount > 0) "精选 $starredCount" else "精选",
                                    color = if (starredOnly) WarmAmber else InkSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = if (starredOnly) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            // RAW+JPG Merge Toggle Chip
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (pairRawJpg) PentaxGreen.copy(alpha = 0.16f) else ObsidianBg)
                                    .border(0.5.dp, if (pairRawJpg) PentaxGreen else HairlineBorder, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.togglePairRawJpg() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Layers,
                                    contentDescription = "合并RAW+JPG",
                                    tint = if (pairRawJpg) PentaxGreen else InkSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = "合并RAW+",
                                    color = if (pairRawJpg) PentaxGreen else InkSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = if (pairRawJpg) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

            // Main Gallery Body
            if (!cameraInfo.connected) {
                DisconnectedHeroView(
                    onConnectWifi = { viewModel.smartConnectPentaxWifi() },
                    onEnableMock = { viewModel.toggleMockMode(true) }
                )
            } else if (isLoading && photos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = PentaxGreen, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("正在索引 ${activeSlot.displayName}...", color = InkSecondary, fontSize = 13.sp)
                    }
                }
            } else if (photos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (starredOnly) "还没有打星标的照片" else "当前筛选条件下暂无照片",
                            color = InkSecondary,
                            fontSize = 13.sp
                        )
                        if (starredOnly) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "点开大图点击右上角 ★ 即可加入精选",
                                color = InkTertiary,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(14.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(MatteCard)
                                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(9.dp))
                                    .clickable { viewModel.toggleStarredOnlyFilter() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text("显示全部照片", color = InkPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(
                        start = 2.dp, end = 2.dp, top = 4.dp,
                        bottom = if (selectedIds.size >= 2) 138.dp else if (selectedIds.isNotEmpty()) 96.dp else 20.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    groupedPhotos.forEach { (dateGroup, itemsInGroup) ->
                        item(span = { GridItemSpan(columns) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (starredOnly) "★ 精选 · $dateGroup" else dateGroup,
                                    color = if (starredOnly) WarmAmber else InkPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${itemsInGroup.size} 张",
                                    color = InkTertiary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        items(itemsInGroup, key = { it.uniqueId }) { photo ->
                            GalleryGridTile(
                                photo = photo,
                                bitmap = thumbnails[photo.uniqueId],
                                showSlotBadge = activeSlot == GallerySlotFilter.ALL,
                                isSelected = selectedIds.contains(photo.uniqueId),
                                inSelectionMode = selectedIds.isNotEmpty(),
                                onRequestThumb = { viewModel.requestThumbnail(photo) },
                                onClick = {
                                    if (selectedIds.isNotEmpty()) {
                                        viewModel.togglePhotoSelection(photo)
                                    } else {
                                        viewModel.openPhotoDetail(photo)
                                    }
                                },
                                onLongClick = { viewModel.togglePhotoSelection(photo) }
                            )
                        }
                    }
                }
            }
        }

        // Floating Glass Multi-Select Action Dock (with Compare / Cinema Triptych / Contact Sheet!)
        if (selectedIds.isNotEmpty()) {
            Surface(
                color = Color(0xF518181D),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .border(0.5.dp, Color(0xFF34343E), RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.clearSelection() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = "取消", tint = InkSecondary, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = "已选 ${selectedIds.size}",
                                color = InkPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.selectAllVisible() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.SelectAll, contentDescription = "全选", tint = InkSecondary, modifier = Modifier.size(18.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MatteCard)
                                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
                                    .clickable {
                                        val chosen = photos.filter { selectedIds.contains(it.uniqueId) }
                                        viewModel.enqueueDownload(chosen, DownloadQuality.VIEW)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                Text("预览图", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PentaxGreen)
                                    .clickable {
                                        val chosen = photos.filter { selectedIds.contains(it.uniqueId) }
                                        viewModel.enqueueDownload(chosen, DownloadQuality.FULL_JPG)
                                    }
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Download, null, tint = Color(0xFF04170B), modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("传原图", color = Color(0xFF04170B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Second action strip when >= 2 photos selected: Compare / Cinema Triptych / Contact Sheet!
                    if (selectedIds.size >= 2) {
                        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectedIds.size == 2) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(WarmAmber.copy(alpha = 0.18f))
                                        .border(0.6.dp, WarmAmber, RoundedCornerShape(9.dp))
                                        .clickable { viewModel.openCompareSelected() }
                                        .padding(vertical = 7.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Compare, null, tint = WarmAmber, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("双图对比", color = WarmAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (selectedIds.size in 2..4) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(MatteCard)
                                        .border(0.5.dp, HairlineBorder, RoundedCornerShape(9.dp))
                                        .clickable(enabled = !isGeneratingCollage) { viewModel.generateCinemaTriptychFromSelected() }
                                        .padding(vertical = 7.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.ViewAgenda, null, tint = PentaxGreen, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isGeneratingCollage) "拼图中..." else "电影多格", color = InkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(MatteCard)
                                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(9.dp))
                                    .clickable(enabled = !isGeneratingCollage) { viewModel.generateContactSheetFromSelected() }
                                    .padding(vertical = 7.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.ContactPage, null, tint = WarmAmber, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isGeneratingCollage) "生成中..." else "接触印相表", color = InkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryGridTile(
    photo: CameraPhoto,
    bitmap: android.graphics.Bitmap?,
    showSlotBadge: Boolean,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    onRequestThumb: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    LaunchedEffect(photo.uniqueId) {
        if (bitmap == null) onRequestThumb()
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MatteCard)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = photo.file,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PhotoLibrary, null, tint = InkTertiary.copy(alpha = 0.45f), modifier = Modifier.size(22.dp))
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PentaxGreen.copy(alpha = 0.18f))
                    .border(2.dp, PentaxGreen)
            )
        }

        // Top-left ★ Starred favorite indicator
        if (photo.isStarred) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(0xB8000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Star, contentDescription = "已星标", tint = WarmAmber, modifier = Modifier.size(12.dp))
            }
        }

        if (photo.badgeText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (photo.hasRawCompanion) Color(0xCC1C1C22) else PentaxRed.copy(alpha = 0.90f))
                    .border(
                        width = if (photo.hasRawCompanion) 0.5.dp else 0.dp,
                        color = if (photo.hasRawCompanion) Color(0x66FFFFFF) else Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = photo.badgeText,
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (showSlotBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xA6000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = photo.slot.displayName,
                    color = Color(0xFFD8D8E0),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (photo.isDownloaded && !inSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xC2000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "已下载", tint = PentaxGreen, modifier = Modifier.size(11.dp))
            }
        }

        if (inSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) PentaxGreen else Color(0x88000000))
                    .border(1.5.dp, if (isSelected) PentaxGreen else Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Rounded.Check, null, tint = Color(0xFF04170B), modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun DisconnectedHeroView(
    onConnectWifi: () -> Unit,
    onEnableMock: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                PentaxK1SilhouetteIcon(tint = InkPrimary, accent = PentaxGreen, modifier = Modifier.size(50.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("连接 PENTAX K-1", color = InkPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "开启 K-1 机身 Wi-Fi 后点击下方按钮，将自动绑定相机热点并防安卓切回移动数据。",
                color = InkSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PentaxGreen)
                    .clickable { onConnectWifi() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Wifi, null, tint = Color(0xFF04170B), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("直连 PENTAX K-1 热点", color = Color(0xFF04170B), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(14.dp))
                    .clickable { onEnableMock() },
                contentAlignment = Alignment.Center
            ) {
                Text("开启虚拟 K-1 演示模式", color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
