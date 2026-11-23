package com.pentax.k1sync

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pentax.k1sync.ui.components.GlobalTransferProgressBar
import com.pentax.k1sync.ui.gallery.GalleryScreen
import com.pentax.k1sync.ui.gallery.PhotoDetailOverlay
import com.pentax.k1sync.ui.remote.RemoteScreen
import com.pentax.k1sync.ui.sync.SyncScreen
import com.pentax.k1sync.ui.theme.ElevatedSurface
import com.pentax.k1sync.ui.theme.InkSecondary
import com.pentax.k1sync.ui.theme.ObsidianBg
import com.pentax.k1sync.ui.theme.PentaxGreen
import com.pentax.k1sync.ui.theme.PentaxK1Theme
import com.pentax.k1sync.ui.theme.SubtleDivider
import com.pentax.k1sync.ui.watermark.WatermarkFullscreenOverlay
import com.pentax.k1sync.ui.watermark.WatermarkScreen
import com.pentax.k1sync.viewmodel.AppTab
import com.pentax.k1sync.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        requestRuntimePermissions()

        setContent {
            PentaxK1Theme {
                K1SyncRootScreen()
            }
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.READ_MEDIA_IMAGES
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }
}

@Composable
fun K1SyncRootScreen(viewModel: MainViewModel = viewModel()) {
    val activeTab by viewModel.activeTab.collectAsState()
    val toastMsg by viewModel.toastMessage.collectAsState()
    val transferTasks by viewModel.transferTasks.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-detect K-1 hotspot when returning from system Wi-Fi settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAppForegrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(toastMsg) {
        if (!toastMsg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(toastMsg!!)
            viewModel.consumeToast()
        }
    }

    // Root Box hosts main Scaffold + In-Window Fullscreen Overlays + Top SnackbarHost
    Box(modifier = Modifier.fillMaxSize().background(ObsidianBg)) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(ElevatedSurface)
                .statusBarsPadding(),
            containerColor = ObsidianBg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (activeTab != AppTab.SYNC) {
                        GlobalTransferProgressBar(
                            tasks = transferTasks,
                            onClickQueue = { viewModel.selectTab(AppTab.SYNC) }
                        )
                    }
                    ProDarkroomBottomBar(
                        activeTab = activeTab,
                        onSelect = { viewModel.selectTab(it) }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (activeTab) {
                    AppTab.GALLERY -> GalleryScreen(viewModel)
                    AppTab.REMOTE -> RemoteScreen(viewModel)
                    AppTab.WATERMARK -> WatermarkScreen(viewModel)
                    AppTab.SYNC -> SyncScreen(viewModel)
                }
            }
        }

        // Full-Screen Photo Detail Overlay (In-Window! Guaranteed 100% visible above gesture bar!)
        PhotoDetailOverlay(viewModel = viewModel)

        // Full-Screen Watermark Inspection Overlay (In-Window!)
        WatermarkFullscreenOverlay(viewModel = viewModel)

        // Global Snackbar sits ABOVE overlays so toast messages are never hidden!
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 68.dp, start = 12.dp, end = 12.dp)
        )
    }
}

@Composable
private fun ProDarkroomBottomBar(
    activeTab: AppTab,
    onSelect: (AppTab) -> Unit
) {
    val items: List<Triple<AppTab, String, ImageVector>> = listOf(
        Triple(AppTab.GALLERY, "图库", Icons.Rounded.PhotoLibrary),
        Triple(AppTab.REMOTE, "遥控", Icons.Rounded.CameraAlt),
        Triple(AppTab.WATERMARK, "水印", Icons.Rounded.AutoFixHigh),
        Triple(AppTab.SYNC, "机身", Icons.Rounded.Router)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElevatedSurface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = SubtleDivider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (tab, label, icon) ->
                val selected = activeTab == tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (selected) PentaxGreen else InkSecondary,
                        modifier = Modifier.size(21.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = label,
                        color = if (selected) PentaxGreen else InkSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .size(if (selected) 3.dp else 0.dp)
                            .clip(CircleShape)
                            .background(PentaxGreen)
                    )
                }
            }
        }
    }
}
