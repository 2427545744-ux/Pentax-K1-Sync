package com.pentax.k1sync.ui.sync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pentax.k1sync.model.DownloadQuality
import com.pentax.k1sync.model.TransferStatus
import com.pentax.k1sync.model.TransferTask
import com.pentax.k1sync.ui.components.ConnectionPillBadge
import com.pentax.k1sync.ui.components.PentaxK1SilhouetteIcon
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
import com.pentax.k1sync.viewmodel.MainViewModel

@Composable
fun SyncScreen(viewModel: MainViewModel) {
    val cameraInfo by viewModel.cameraInfo.collectAsState()
    val wifiStatus by viewModel.wifiStatus.collectAsState()
    val cellularOnline by viewModel.cellularInternetAlive.collectAsState()
    val wifiPassword by viewModel.cameraWifiPassword.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val isMock by viewModel.isMockMode.collectAsState()
    val defaultQuality by viewModel.defaultQuality.collectAsState()
    val autoWatermark by viewModel.autoWatermarkOnTransfer.collectAsState()
    val tasks by viewModel.transferTasks.collectAsState()
    val apiLogEnabled by viewModel.apiLogEnabled.collectAsState()
    val apiLogs by viewModel.apiLogEntries.collectAsState()

    var expandApiLogs by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportConfigBackup(uri) }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importConfigBackup(uri) }

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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("机身连接与系统设置", color = InkPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            ConnectionPillBadge(cameraInfo)
        }
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)

        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // 1. PENTAX K-1 Hardware Hero Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianBg)
                            .border(1.dp, if (cameraInfo.connected) PentaxGreen.copy(alpha = 0.6f) else HairlineBorder, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        PentaxK1SilhouetteIcon(
                            tint = if (cameraInfo.connected) InkPrimary else InkSecondary,
                            accent = if (cameraInfo.connected) PentaxGreen else InkSecondary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (cameraInfo.connected) cameraInfo.model else "PENTAX K-1 (一代)", color = InkPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(wifiStatus, color = if (cameraInfo.connected) PentaxGreen else InkSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (isConnecting) {
                        CircularProgressIndicator(color = PentaxGreen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }

                if (cameraInfo.connected) {
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("机身电量", color = InkSecondary, fontSize = 11.sp)
                        Text("${cameraInfo.batteryPercent}%", color = PentaxGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = { (cameraInfo.batteryPercent / 100f).coerceIn(0f, 1f) },
                        color = PentaxGreen, trackColor = ObsidianBg,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cameraInfo.storages.forEach { st ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ObsidianBg)
                                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.SdCard, null, tint = if (st.available) PentaxGreen else InkSecondary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(st.slot.displayName, color = InkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(if (st.available) "余 ${st.remainShots} 张" else "未插卡", color = InkSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // K-1 WPA2 Passphrase input — enables Android 10+ local-only P2P Wi-Fi so 5G internet stays online!
                OutlinedTextField(
                    value = wifiPassword,
                    onValueChange = { viewModel.updateCameraWifiPassword(it) },
                    label = { Text("K-1 热点密码（填一次永久记住 · 直连可保 5G 联网）", fontSize = 10.sp) },
                    placeholder = { Text("在 K-1 菜单「无线LAN → 通信信息」查看 8 位密码", fontSize = 11.sp, color = InkSecondary) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = TextStyle(color = InkPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PentaxGreen,
                        unfocusedBorderColor = HairlineBorder,
                        focusedContainerColor = ObsidianBg,
                        unfocusedContainerColor = ObsidianBg,
                        focusedLabelColor = PentaxGreen,
                        unfocusedLabelColor = InkSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .weight(1.15f).height(42.dp).clip(RoundedCornerShape(11.dp)).background(PentaxGreen)
                            .clickable { viewModel.smartConnectPentaxWifi() },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Wifi, null, tint = Color(0xFF04170B), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (wifiPassword.trim().length >= 8) "直连 K-1 (保5G)" else "直连 K-1 热点",
                            color = Color(0xFF04170B), fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(0.85f).height(42.dp).clip(RoundedCornerShape(11.dp)).background(ObsidianBg)
                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(11.dp))
                            .clickable { viewModel.bindCurrentWifiAndConnect() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("绑定当前 Wi-Fi", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (cellularOnline && cameraInfo.connected) {
                        "✓ 双网并发已生效：App 走 K-1 Wi-Fi，微信/网页继续走 4G/5G 上网。"
                    } else {
                        "提示：如需连 K-1 同时用 5G 上网，请先在手机系统 WLAN 中「取消保存」PENTAX 热点，填入上方密码后用「直连 K-1 (保5G)」连接。"
                    },
                    color = if (cellularOnline && cameraInfo.connected) PentaxGreen else InkSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )

                if (cameraInfo.connected) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth().height(40.dp).clip(RoundedCornerShape(11.dp))
                            .background(PentaxRed.copy(alpha = 0.14f))
                            .border(0.5.dp, PentaxRed.copy(alpha = 0.5f), RoundedCornerShape(11.dp))
                            .clickable { viewModel.disconnectAndRestoreMobileNetwork() },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.SignalCellularAlt, null, tint = PentaxRed, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("断开相机热点并恢复手机 4G/5G 上网", color = InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // 2. Utilities, Mock Mode & Personal Config Backup / Restore
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("虚拟 K-1 演示模式 (Mock)", color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("无真机时体验全部界面，连实机请关闭", color = InkSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = isMock, onCheckedChange = { viewModel.toggleMockMode(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = WarmAmber, uncheckedTrackColor = ObsidianBg, uncheckedBorderColor = HairlineBorder)
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.syncPhoneTimeToCamera() }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, null, tint = PentaxGreen, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("同步手机时间到 K-1 机身", color = InkPrimary, fontSize = 13.sp)
                    }
                    Text("立即同步", color = PentaxGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.clearThumbnailCache() }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = InkSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("清理本地缩略图与预览缓存", color = InkPrimary, fontSize = 13.sp)
                    }
                    Text("${"%.1f".format(viewModel.cacheSizeMb())} MB", color = InkSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                // Config Backup & Restore Row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(ObsidianBg)
                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
                            .clickable { exportBackupLauncher.launch("PentaxK1Sync_Backup.json") },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.CloudUpload, null, tint = PentaxGreen, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("备份个人配置", color = InkPrimary, fontSize = 12.sp)
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(ObsidianBg)
                            .border(0.5.dp, HairlineBorder, RoundedCornerShape(10.dp))
                            .clickable { importBackupLauncher.launch(arrayOf("application/json", "*/*")) },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Restore, null, tint = WarmAmber, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("恢复个人配置", color = InkPrimary, fontSize = 12.sp)
                    }
                }
            }

            // 3. K-1 Real-Device Protocol Debug Logger (For instant firmware troubleshooting!)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.BugReport, null, tint = if (apiLogEnabled) PentaxGreen else InkSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("真机通信抓包日志 (${apiLogs.size}/30)", color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("实机联调遇到兼容问题时，一键复制日志发给 AI 秒修", color = InkSecondary, fontSize = 10.sp)
                        }
                    }
                    Switch(
                        checked = apiLogEnabled,
                        onCheckedChange = { viewModel.toggleApiLogging(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PentaxGreen, uncheckedTrackColor = ObsidianBg, uncheckedBorderColor = HairlineBorder)
                    )
                }

                if (apiLogEnabled || apiLogs.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp))
                                .background(PentaxGreen.copy(alpha = 0.16f))
                                .border(0.5.dp, PentaxGreen, RoundedCornerShape(8.dp))
                                .clickable {
                                    clipboard.setText(AnnotatedString(viewModel.getApiLogTextForCopy()))
                                    viewModel.showToast("已复制 ${apiLogs.size} 条通信日志到剪贴板")
                                },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = PentaxGreen, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("复制全部日志", color = PentaxGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .height(34.dp).clip(RoundedCornerShape(8.dp)).background(ObsidianBg)
                                .border(0.5.dp, HairlineBorder, RoundedCornerShape(8.dp))
                                .clickable { expandApiLogs = !expandApiLogs }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (expandApiLogs) "收起" else "展开", color = InkPrimary, fontSize = 11.sp)
                        }
                        Box(
                            modifier = Modifier
                                .height(34.dp).clip(RoundedCornerShape(8.dp)).background(ObsidianBg)
                                .border(0.5.dp, HairlineBorder, RoundedCornerShape(8.dp))
                                .clickable { viewModel.clearApiLogs() }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("清空", color = InkSecondary, fontSize = 11.sp)
                        }
                    }

                    if (expandApiLogs && apiLogs.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(ObsidianBg)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            apiLogs.take(12).forEach { entry ->
                                val ok = entry.statusCode in 200..299
                                Text(
                                    text = "[${entry.time}] ${entry.method} ${entry.path} -> ${if (entry.statusCode > 0) entry.statusCode else "ERR"} (${entry.elapsedMs}ms)",
                                    color = if (ok) PentaxGreen else PentaxRed,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = entry.detail,
                                    color = InkSecondary,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 4. Transfer Strategy Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MatteCard)
                    .border(0.5.dp, HairlineBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("默认传输画质（后台保活不断传）", color = InkSecondary, fontSize = 12.sp)
                SegmentedPillRow(
                    items = DownloadQuality.entries,
                    selectedItem = defaultQuality,
                    labelProvider = { it.label },
                    onSelect = { viewModel.setDefaultQuality(it) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(defaultQuality.desc, color = InkSecondary, fontSize = 11.sp)
                HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("传图自动附带宾得水印副本", color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("保留干净原图的同时额外存入 PentaxK1_Watermark（含 EXIF）", color = InkSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoWatermark, onCheckedChange = { viewModel.setAutoWatermarkOnTransfer(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PentaxGreen, uncheckedTrackColor = ObsidianBg, uncheckedBorderColor = HairlineBorder)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth().height(42.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (cameraInfo.connected) PentaxGreen else ObsidianBg)
                        .clickable(enabled = cameraInfo.connected) { viewModel.enqueueOnlyUndownloaded() },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Download, null, tint = if (cameraInfo.connected) Color(0xFF04170B) else InkSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("一键同步当前卡内所有未下载新照片", color = if (cameraInfo.connected) Color(0xFF04170B) else InkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // 5. Transfer Queue
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("后台传输队列 (${tasks.size})", color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (tasks.isNotEmpty()) {
                    val hasRetryable = tasks.any { it.status == TransferStatus.FAILED || it.status == TransferStatus.CANCELLED }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (hasRetryable) {
                            Text("重试失败", color = WarmAmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { viewModel.retryAllFailedTasks() })
                        }
                        Text("清理已完成", color = PentaxGreen, fontSize = 12.sp, modifier = Modifier.clickable { viewModel.clearCompletedTasks() })
                        Text("全部取消", color = PentaxRed, fontSize = 12.sp, modifier = Modifier.clickable { viewModel.cancelAllPendingTasks() })
                    }
                }
            }

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MatteCard)
                        .border(0.5.dp, HairlineBorder, RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    Text("队列空闲。在大图页单张下载、图库长按多选，或点击上方一键同步即可开始。", color = InkSecondary, fontSize = 12.sp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tasks.forEach { task -> TransferTaskItem(task = task, onRetry = { viewModel.retryTransferTask(task.id) }) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TransferTaskItem(task: TransferTask, onRetry: () -> Unit) {
    val speedEta = task.formatSpeedAndEta()
    val subtitle = when {
        task.status == TransferStatus.DOWNLOADING && speedEta.isNotBlank() ->
            "${task.quality.label} · $speedEta" + if (task.retryAttempt > 0) " (重试#${task.retryAttempt})" else ""
        task.savedPath != null -> "${task.quality.label} · ${task.savedPath}"
        task.errorMessage != null -> "${task.quality.label} · ${task.errorMessage}"
        else -> "${task.quality.label} · ${task.status.name}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MatteCard)
            .border(0.5.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(task.photo.file, color = InkPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = if (task.retryAttempt > 0 && task.status == TransferStatus.DOWNLOADING) WarmAmber else InkSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (task.status) {
                TransferStatus.COMPLETED -> Icon(Icons.Rounded.CheckCircle, null, tint = PentaxGreen, modifier = Modifier.size(18.dp))
                TransferStatus.FAILED, TransferStatus.CANCELLED -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (task.status == TransferStatus.FAILED) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = PentaxRed, modifier = Modifier.size(16.dp))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(WarmAmber.copy(alpha = 0.18f))
                            .border(0.5.dp, WarmAmber, RoundedCornerShape(7.dp))
                            .clickable { onRetry() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("重试", color = WarmAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TransferStatus.DOWNLOADING -> Text("${(task.progress * 100).toInt()}%", color = PentaxGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                TransferStatus.QUEUED -> Text("排队中", color = InkSecondary, fontSize = 11.sp)
            }
        }
        if (task.status == TransferStatus.DOWNLOADING) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { task.progress }, color = PentaxGreen, trackColor = ObsidianBg,
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
            )
        }
    }
}
