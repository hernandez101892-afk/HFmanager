package com.tuusuario.hfmanager.ui

import android.content.Context
import androidx.compose.ui.text.style.TextAlign
import androidx.documentfile.provider.DocumentFile
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuusuario.hfmanager.data.SettingsManager
import com.tuusuario.hfmanager.data.UploadTracker
import com.tuusuario.hfmanager.service.TelegramBotService
import com.tuusuario.hfmanager.network.ArchiveUploader
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

// ---- PALETA DE COLORES CYBERPUNK / HIGH-TECH ----
private val CyberDarkSpace = Color(0xFF060913)
private val CyberSlate = Color(0xFF0F1524)
private val CyberPanel = Color(0xFF0C101F)
private val CyberNeonCyan = Color(0xFF00F0FF)
private val CyberNeonPurple = Color(0xFFBD00FF)
private val CyberAlertRed = Color(0xFFFF0055)
private val CyberSuccessGreen = Color(0xFF00FF66)
private val CyberTextLight = Color(0xFFE2E8F0)
private val CyberTextMuted = Color(0xFF475569)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val borderAlpha by pulseTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(CyberNeonCyan)
                        )
                        Text(
                            text = "ARCHIVE_MANAGER_SYS",
                            color = CyberNeonCyan,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp,
                            letterSpacing = 1.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CyberDarkSpace,
                    titleContentColor = CyberNeonCyan
                ),
                modifier = Modifier.border(
                    width = 1.dp,
                    color = CyberSlate,
                    shape = RoundedCornerShape(0.dp)
                )
            )
        },
        containerColor = CyberDarkSpace
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(CyberDarkSpace)
        ) {
            // ---- NAVEGACIÓN HIGH-TECH TAB ----
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = CyberPanel,
                contentColor = CyberNeonCyan,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = CyberNeonCyan,
                        height = 2.dp
                    )
                },
                modifier = Modifier.border(width = 1.dp, color = CyberSlate)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "01_SETTINGS",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    },
                    selectedContentColor = CyberNeonCyan,
                    unselectedContentColor = CyberTextMuted
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "02_TELEMETRY",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    },
                    selectedContentColor = CyberNeonCyan,
                    unselectedContentColor = CyberTextMuted
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            "03_SYNC_ENGINE",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    },
                    selectedContentColor = CyberNeonCyan,
                    unselectedContentColor = CyberTextMuted
                )
            }

            when (selectedTab) {
                0 -> ConfigTabContent(context, settings)
                1 -> MonitorTabContent(context)
                2 -> PythonSyncTabContent(context, settings)
            }
        }
    }
}

// ==========================================
// PESTAÑA 1: CONFIGURACIÓN ORIGINAL MEJORADA
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigTabContent(context: Context, settings: SettingsManager) {
    val scrollState = rememberScrollState()

    var hfToken by remember { mutableStateOf(settings.getToken()) }
    var tmdbKey by remember { mutableStateOf(settings.getTmdbKey()) }
    var telegramToken by remember { mutableStateOf(settings.getTelegramToken()) }
    var telegramChatId by remember { mutableStateOf(settings.getTelegramChatId()) }
    var targetRepo by remember { mutableStateOf(settings.getTargetRepo()) }
    var targetRepoType by remember { mutableStateOf(settings.getTargetRepoType()) }
    var folderUriStr by remember { mutableStateOf(settings.getTargetFolderUri()) }
    var hfBackupToken by remember { mutableStateOf(settings.getHfBackupToken()) }
    var hfBackupRepo by remember { mutableStateOf(settings.getHfBackupRepo()) }

    var isServiceRunning by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            folderUriStr = it.toString()
            settings.saveTargetFolderUri(folderUriStr)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "NeonPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // MÓDULO: ESTADO DEL BOT
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = if (isServiceRunning) listOf(CyberNeonCyan, CyberNeonPurple.copy(alpha = 0.3f))
                        else listOf(CyberAlertRed, Color.Transparent)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ),
            colors = CardDefaults.cardColors(containerColor = CyberPanel),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isServiceRunning) CyberNeonCyan.copy(alpha = pulseAlpha)
                                    else CyberAlertRed.copy(alpha = pulseAlpha)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isServiceRunning) CyberNeonCyan else CyberAlertRed,
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = if (isServiceRunning) "BOT CONTROLLER ACTIVE" else "BOT CONTROLLER OFFLINE",
                            color = if (isServiceRunning) CyberNeonCyan else CyberAlertRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    IconButton(
                        onClick = {
                            val intent = Intent(context, TelegramBotService::class.java)
                            if (isServiceRunning) {
                                context.stopService(intent)
                                isServiceRunning = false
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                                isServiceRunning = true
                            }
                        },
                        modifier = Modifier
                            .background(
                                if (isServiceRunning) CyberAlertRed.copy(alpha = 0.2f) else CyberNeonCyan.copy(alpha = 0.2f),
                                shape = CircleShape
                            )
                            .border(
                                width = 1.dp,
                                color = if (isServiceRunning) CyberAlertRed else CyberNeonCyan,
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isServiceRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Encender/Apagar",
                            tint = if (isServiceRunning) CyberAlertRed else CyberNeonCyan
                        )
                    }
                }

                Text(
                    text = "Controla de manera remota tus subidas directas de hasta 4 GB y consulta tus colecciones en Internet Archive directamente desde tu chat seguro de Telegram.",
                    color = CyberTextLight.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )
            }
        }

        TechSectionHeader(title = "AUTHENTICATION NODES")

        TechTextField(
            value = hfToken,
            onValueChange = { hfToken = it; settings.saveToken(it) },
            label = "INTERNET_ARCHIVE_ACCESS_KEY",
            icon = Icons.Default.VpnKey,
            isPassword = true
        )

        TechTextField(
            value = telegramToken,
            onValueChange = { telegramToken = it; settings.saveTelegramToken(it) },
            label = "TELEGRAM_BOT_TOKEN",
            icon = Icons.Default.VpnKey,
            isPassword = true
        )

        TechTextField(
            value = telegramChatId,
            onValueChange = { telegramChatId = it; settings.saveTelegramChatId(it) },
            label = "TELEGRAM_USER_CHAT_ID",
            icon = Icons.Default.Person
        )

        TechTextField(
            value = tmdbKey,
            onValueChange = { tmdbKey = it; settings.saveTmdbKey(it) },
            label = "TMDB_API_KEY",
            icon = Icons.Default.Movie
        )

        TechSectionHeader(title = "CLOUD STORAGE SYNC")

        TechTextField(
            value = targetRepo,
            onValueChange = { targetRepo = it; settings.saveTargetRepo(it) },
            label = "ARCHIVE_ITEM_ID (IDENTIFICADOR)",
            icon = Icons.Default.CloudQueue
        )

        TechTextField(
            value = targetRepoType,
            onValueChange = { targetRepoType = it; settings.saveTargetRepoType(it) },
            label = "INTERNET_ARCHIVE_SECRET_KEY",
            icon = Icons.Default.Lock,
            isPassword = true
        )

        TechSectionHeader(title = "HUGGING FACE BACKUP STREAM")

        TechTextField(
            value = hfBackupToken,
            onValueChange = { hfBackupToken = it; settings.saveHfBackupToken(it) },
            label = "HUGGING_FACE_WRITE_TOKEN",
            icon = Icons.Default.VpnKey,
            isPassword = true
        )

        TechTextField(
            value = hfBackupRepo,
            onValueChange = { hfBackupRepo = it; settings.saveHfBackupRepo(it) },
            label = "HUGGING_FACE_BACKUP_REPO (ej: Hachetv/BOTtelegram)",
            icon = Icons.Default.CloudQueue
        )

        TechSectionHeader(title = "LOCAL HOST SYSTEM")

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { folderLauncher.launch(null) }
                .border(
                    width = 1.dp,
                    color = if (folderUriStr.isNotEmpty()) CyberNeonCyan.copy(alpha = 0.5f) else CyberNeonPurple.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp)
                ),
            colors = CardDefaults.cardColors(containerColor = CyberPanel)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = if (folderUriStr.isNotEmpty()) CyberNeonCyan else CyberNeonPurple,
                    modifier = Modifier.size(32.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (folderUriStr.isNotEmpty()) "LOCAL SYSTEM LINKED ✓" else "UNLINKED DIRECTORY",
                        color = if (folderUriStr.isNotEmpty()) CyberNeonCyan else CyberNeonPurple,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (folderUriStr.isNotEmpty()) "Permisos Scoped Storage concedidos." else "Selecciona la carpeta local con los archivos pesados.",
                        color = CyberTextLight.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (folderUriStr.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyberSlate)
                    .padding(8.dp)
            ) {
                Text(
                    text = "URI: $folderUriStr",
                    color = CyberTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.wrapContentHeight()
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

// ==========================================
// PESTAÑA 2: TRANSMISSION_MONITOR (NUEVA UI)
// ==========================================
@Composable
fun MonitorTabContent(context: Context) {
    val currentUploadState by UploadTracker.currentState.collectAsState()
    val terminalLogs by UploadTracker.logs.collectAsState()

    val lazyListState = rememberLazyListState()

    LaunchedEffect(terminalLogs.size) {
        if (terminalLogs.isNotEmpty()) {
            lazyListState.animateScrollToItem(terminalLogs.size - 1)
        }
    }

    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
    val batteryLevel = remember {
        batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = when (currentUploadState) {
                            is UploadTracker.UploadState.Idle -> listOf(CyberSlate, CyberSlate)
                            is UploadTracker.UploadState.Processing -> listOf(CyberNeonPurple, CyberSlate)
                            is UploadTracker.UploadState.Uploading -> listOf(CyberNeonCyan, CyberNeonPurple)
                            is UploadTracker.UploadState.Success -> listOf(CyberSuccessGreen, CyberNeonCyan)
                            is UploadTracker.UploadState.Error -> listOf(CyberAlertRed, CyberSlate)
                        }
                    ),
                    shape = RoundedCornerShape(16.dp)
                ),
            colors = CardDefaults.cardColors(containerColor = CyberPanel)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SYNC_NODE_STATUS",
                        color = CyberNeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )

                    Text(
                        text = when (currentUploadState) {
                            is UploadTracker.UploadState.Idle -> "STANDBY"
                            is UploadTracker.UploadState.Processing -> "PROCESSING"
                            is UploadTracker.UploadState.Uploading -> "TRANSMITTING"
                            is UploadTracker.UploadState.Success -> "PUBLISH_COMPLETED"
                            is UploadTracker.UploadState.Error -> "FATAL_ERROR"
                        },
                        color = when (currentUploadState) {
                            is UploadTracker.UploadState.Idle -> CyberTextMuted
                            is UploadTracker.UploadState.Processing -> CyberNeonPurple
                            is UploadTracker.UploadState.Uploading -> CyberNeonCyan
                            is UploadTracker.UploadState.Success -> CyberSuccessGreen
                            is UploadTracker.UploadState.Error -> CyberAlertRed
                        },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }

                HorizontalDivider(color = CyberSlate)

                when (val state = currentUploadState) {
                    is UploadTracker.UploadState.Idle -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = null,
                                tint = CyberTextMuted,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                "ESPERANDO TRANSMISIÓN DESDE EL BOT",
                                color = CyberTextMuted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "El sistema se activará de forma totalmente automatizada al mandarle comandos a tu Bot de Telegram.",
                                color = CyberTextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    is UploadTracker.UploadState.Processing -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "FILE: ${state.fileName}",
                                color = CyberTextLight,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = CyberNeonPurple,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = state.step,
                                        color = CyberNeonPurple,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                }
                                CancelButton()
                            }
                        }
                    }
                    is UploadTracker.UploadState.Uploading -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "ORIGINAL: ${state.originalName}",
                                        color = CyberTextLight,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "REMOTE: ${state.newName}",
                                        color = CyberNeonCyan,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                CancelButton()
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(CyberSlate)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(state.progress)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(CyberNeonPurple, CyberNeonCyan)
                                            )
                                        )
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val currentMb = state.bytesWritten / (1024 * 1024)
                                val totalMb = state.totalBytes / (1024 * 1024)
                                Text(
                                    text = "TRANSFERRED: $currentMb MB / $totalMb MB",
                                    color = CyberTextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "${(state.progress * 100).toInt()}%",
                                    color = CyberNeonCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    is UploadTracker.UploadState.Success -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = CyberSuccessGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "SUBIDA EXITOSA",
                                    color = CyberSuccessGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                            }
                            Text(
                                text = "File: ${state.fileName}",
                                color = CyberTextLight,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Enlace: ${state.commitUrl}",
                                color = CyberNeonCyan,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 4.dp).wrapContentHeight()
                            )
                        }
                    }
                    is UploadTracker.UploadState.Error -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    tint = CyberAlertRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "FALLO EN EL PROCESO DE RED",
                                    color = CyberAlertRed,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                            }
                            Text(
                                text = "File: ${state.fileName}",
                                color = CyberTextLight,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Details: ${state.errorMessage}",
                                color = CyberTextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "[ENGINE_TERMINAL_OUTPUT]",
            color = CyberNeonPurple,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CyberSlate.copy(alpha = 0.8f))
                .border(width = 1.dp, color = CyberSlate, shape = RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            if (terminalLogs.isEmpty()) {
                Text(
                    text = "Awaiting first system activity... terminal empty.",
                    color = CyberTextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(terminalLogs) { log ->
                        Text(
                            text = log,
                            color = when {
                                log.contains("SUCCESS") -> CyberSuccessGreen
                                log.contains("FAILURE") || log.contains("ERROR") -> CyberAlertRed
                                log.contains("PROCESS") -> CyberNeonPurple
                                log.contains("TELEGRAM") -> CyberNeonCyan
                                else -> CyberTextLight
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }

        // --- RETRO TERMINAL COMMAND CONSOLE ---
        val executeCommand = { cmdText: String ->
            if (cmdText.isNotEmpty()) {
                UploadTracker.addLog("TELEGRAM: UI Command: $cmdText")
                val intent = Intent(context, TelegramBotService::class.java).apply {
                    action = "com.tuusuario.hfmanager.ACTION_EXECUTE_COMMAND"
                    putExtra("EXTRA_COMMAND", cmdText)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (e: Exception) {
                    UploadTracker.addLog("SYSTEM_ERROR: Fallo de enlace de servicio: ${e.message}")
                }
            }
        }
        var commandInput by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "[BOT_COMMAND_PANEL]",
                color = CyberNeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )

            // Quick command chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // STATUS button
                Button(
                    onClick = { executeCommand("/status") },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSlate, contentColor = CyberNeonCyan),
                    modifier = Modifier.weight(1f).height(32.dp).border(1.dp, CyberNeonCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("STATUS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                // SYNC button
                Button(
                    onClick = { executeCommand("/sync mp4") },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSlate, contentColor = CyberNeonPurple),
                    modifier = Modifier.weight(1.2f).height(32.dp).border(1.dp, CyberNeonPurple.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("SYNC (MP4)", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                // M3U button
                Button(
                    onClick = { executeCommand("/m3u") },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSlate, contentColor = CyberNeonCyan),
                    modifier = Modifier.weight(1f).height(32.dp).border(1.dp, CyberNeonCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("IPTV M3U", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                // REPAIR button
                Button(
                    onClick = { executeCommand("/repair") },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSlate, contentColor = Color(0xFFFFCC00)),
                    modifier = Modifier.weight(1f).height(32.dp).border(1.dp, Color(0xFFFFCC00).copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("REPAIR", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                // CANCEL button
                Button(
                    onClick = { executeCommand("/cancel") },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSlate, contentColor = CyberAlertRed),
                    modifier = Modifier.weight(1f).height(32.dp).border(1.dp, CyberAlertRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("CANCEL", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            // Command input row
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                placeholder = {
                    Text(
                        text = "Enter raw bot command... (e.g. /sync mp4)",
                        color = CyberTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = CyberNeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                leadingIcon = {
                    Text(
                        text = ">",
                        color = CyberNeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (commandInput.isNotBlank()) {
                                executeCommand(commandInput.trim())
                                commandInput = ""
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send Command",
                            tint = CyberNeonCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CyberPanel,
                    unfocusedContainerColor = CyberPanel,
                    focusedBorderColor = CyberNeonCyan,
                    unfocusedBorderColor = CyberSlate,
                    focusedLabelColor = CyberNeonCyan,
                    unfocusedLabelColor = CyberTextMuted
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        if (commandInput.isNotBlank()) {
                            executeCommand(commandInput.trim())
                            commandInput = ""
                        }
                    }
                )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CyberPanel)
                .border(width = 1.dp, color = CyberSlate, shape = RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SYS_LOAD", color = CyberTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(2.dp))
                Text("STABLE", color = CyberSuccessGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            VerticalDivider(modifier = Modifier.width(1.dp).height(24.dp), color = CyberSlate)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PHONE_BATTERY", color = CyberTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(2.dp))
                Text("$batteryLevel%", color = if (batteryLevel > 20) CyberNeonCyan else CyberAlertRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            VerticalDivider(modifier = Modifier.width(1.dp).height(24.dp), color = CyberSlate)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("METRICS_OUTPUT", color = CyberTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(2.dp))
                Text("LIVE ✓", color = CyberNeonPurple, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun CancelButton() {
    Button(
        onClick = { UploadTracker.triggerCancellation() },
        colors = ButtonDefaults.buttonColors(
            containerColor = CyberAlertRed.copy(alpha = 0.2f),
            contentColor = CyberAlertRed
        ),
        modifier = Modifier
            .border(width = 1.dp, color = CyberAlertRed, shape = RoundedCornerShape(8.dp))
            .height(30.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Cancelar Subida",
            modifier = Modifier.size(14.dp).padding(end = 4.dp)
        )
        Text(
            text = "CANCEL",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
    }
}

// ==================================================
// PESTAÑA 3: PYTHON_SYNC (MOTOR NATIVO DE DIAGNÓSTICO)
// ==================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PythonSyncTabContent(context: Context, settings: SettingsManager) {
    val coroutineScope = rememberCoroutineScope()

    val accessKey = settings.getToken()
    val secretKey = settings.getTargetRepoType()
    val itemId = settings.getTargetRepo()
    val folderUriStr = settings.getTargetFolderUri()
    val tmdbApiKey = settings.getTmdbKey()

    var isRunning by remember { mutableStateOf(false) }
    val consoleLogs = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    var totalLocalFiles by remember { mutableIntStateOf(0) }
    var alreadySyncedCount by remember { mutableIntStateOf(0) }
    var pendingCommitsCount by remember { mutableIntStateOf(0) }
    var errorCount by remember { mutableIntStateOf(0) }
    var playlistGenerated by remember { mutableStateOf(false) }

    LaunchedEffect(consoleLogs.size) {
        if (consoleLogs.isNotEmpty()) {
            listState.animateScrollToItem(consoleLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = CyberNeonPurple.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ),
            colors = CardDefaults.cardColors(containerColor = CyberPanel)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = CyberNeonPurple,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = "ARCHIVE SYNC ENGINE NODE",
                        color = CyberNeonPurple,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Análisis, deduplicación asíncrona local y publicación inmediata por streaming S3 en Internet Archive.",
                        color = CyberTextLight.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricBox(
                label = "LOCALES",
                value = "$totalLocalFiles",
                color = CyberNeonCyan,
                modifier = Modifier.weight(1f)
            )
            MetricBox(
                label = "SINCRO",
                value = "$alreadySyncedCount",
                color = CyberSuccessGreen,
                modifier = Modifier.weight(1f)
            )
            MetricBox(
                label = "FALTANTES",
                value = "$pendingCommitsCount",
                color = if (pendingCommitsCount > 0) CyberAlertRed else CyberTextMuted,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (folderUriStr.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty() || itemId.isEmpty()) {
                        consoleLogs.add("❌ [ERROR]: Configura primero tus llaves (Access/Secret), el Item ID y la carpeta en los ajustes.")
                        return@Button
                    }
                    isRunning = true
                    consoleLogs.clear()
                    totalLocalFiles = 0
                    alreadySyncedCount = 0
                    pendingCommitsCount = 0
                    errorCount = 0
                    playlistGenerated = false

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            consoleLogs.add("⚙️ Iniciando diagnóstico de catálogo...")
                            consoleLogs.add("🔑 Conectando con la API S3 de Internet Archive (Identificador: $itemId)...")

                            val uploader = ArchiveUploader(context, accessKey, secretKey, tmdbApiKey)

                            // 1. Obtener lista de metadatos remotos
                            consoleLogs.add("📦 Solicitando metadatos de archivos publicados...")
                            val remoteFilesList = uploader.listArchiveItemFiles(itemId)
                            consoleLogs.add("🍿 Encontradas ${remoteFilesList.size} entradas registradas en tu Item de Archive.org.")

                            // 2. Escanear directorio local
                            consoleLogs.add("📁 Escaneando almacenamiento local de Android...")
                            val folderUri = folderUriStr.toUri()
                            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
                            if (documentFile == null || !documentFile.exists()) {
                                consoleLogs.add("❌ [ERROR]: No se pudo acceder a la carpeta de Scoped Storage.")
                                isRunning = false
                                return@launch
                            }

                            val localFiles = documentFile.listFiles()
                                .filter { it.name?.endsWith("mp4", ignoreCase = true) == true || it.name?.endsWith("mkv", ignoreCase = true) == true }

                            totalLocalFiles = localFiles.size
                            consoleLogs.add("📱 Se hallaron $totalLocalFiles archivos de video locales.")

                            if (localFiles.isEmpty()) {
                                consoleLogs.add("📂 La carpeta local está vacía.")
                                isRunning = false
                                return@launch
                            }

                            // 3. Iterar y sincronizar
                            for (file in localFiles) {
                                if (UploadTracker.isCancelled.value) {
                                    consoleLogs.add("🛑 Sincronización cancelada por el usuario. Deteniendo motor...")
                                    break
                                }
                                val oldName = file.name ?: ""
                                consoleLogs.add("🔍 Evaluando archivo local: '$oldName'...")

                                val extension = oldName.substringAfterLast(".", "mp4")
                                var tmdbId = extractTmdbId(oldName)
                                if (tmdbId.isEmpty()) {
                                    val cleanedName = uploader.cleanMovieName(oldName)
                                    try {
                                        tmdbId = uploader.fetchMovieIdFromTmdb(cleanedName)
                                        consoleLogs.add("🎯 Identificada en TMDB: '$cleanedName' -> ID: $tmdbId")
                                    } catch (e: Exception) {
                                        consoleLogs.add("⚠️ TMDB Error para '$cleanedName': No identificada. (${e.message})")
                                    }
                                }

                                if (tmdbId.isEmpty()) {
                                    consoleLogs.add("❌ '$oldName' omitida: Sin identificación de TMDB.")
                                    errorCount++
                                    continue
                                }

                                // Calcular metadatos nativos para Internet Archive
                                val metadata = uploader.calculateMetadata(file.uri, tmdbId, extension)

                                if (remoteFilesList.contains(metadata.newName)) {
                                    consoleLogs.add("✅ '${metadata.newName}' ya se encuentra publicada en Internet Archive. Omitido.")
                                    alreadySyncedCount++
                                } else {
                                    consoleLogs.add("⚠️ ¡DETECTADO ARCHIVO FALTANTE! '${metadata.newName}' se subirá ahora mismo.")
                                    pendingCommitsCount++

                                    try {
                                        val uploadResult = uploader.uploadFile(
                                            itemIdentifier = itemId,
                                            uri = file.uri,
                                            metadata = metadata
                                        )
                                        consoleLogs.add("🎉 ¡Sincronizado! $uploadResult")
                                        alreadySyncedCount++
                                        pendingCommitsCount--
                                    } catch (ex: Exception) {
                                        consoleLogs.add("❌ Error al subir: ${ex.message}")
                                        errorCount++
                                    }
                                }
                            }

                            consoleLogs.add("🏁 Sincronización y diagnóstico completados.")
                            consoleLogs.add("📈 Resumen: Publicados: $alreadySyncedCount | Fallos: $errorCount")

                        } catch (e: Exception) {
                            consoleLogs.add("❌ [ERROR CRÍTICO]: ${e.message}")
                        } finally {
                            isRunning = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberNeonPurple),
                modifier = Modifier
                    .weight(1.2f)
                    .border(1.dp, CyberNeonPurple, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                enabled = !isRunning
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("EJECUTANDO...", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("START SYNC ENGINE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = {
                    isRunning = true
                    playlistGenerated = false

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            consoleLogs.add("📝 Generando lista de reproducción IPTV M3U de Internet Archive...")
                            val uploader = ArchiveUploader(context, accessKey, secretKey, tmdbApiKey)

                            val m3uContent = StringBuilder("#EXTM3U\n")
                            val remoteFiles = uploader.listArchiveItemFiles(itemId)

                            val movieRegex = java.util.regex.Pattern.compile("\\[tmdb-(\\d+)\\]")

                            for (fileName in remoteFiles) {
                                if (fileName.endsWith(".mp4") || fileName.endsWith(".mkv")) {
                                    val matcher = movieRegex.matcher(fileName)
                                    if (matcher.find()) {
                                        val tmdbId = matcher.group(1) ?: ""
                                        var movieTitle = fileName.substringBefore(" [tmdb")
                                        var posterUrl = ""

                                        if (tmdbApiKey.isNotEmpty() && tmdbId.isNotEmpty()) {
                                            try {
                                                val url = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&language=es-MX"
                                                val client = okhttp3.OkHttpClient()
                                                val req = okhttp3.Request.Builder().url(url).build()
                                                client.newCall(req).execute().use { tRes ->
                                                    if (tRes.isSuccessful) {
                                                        val tBody = tRes.body?.string() ?: ""
                                                        val tJson = org.json.JSONObject(tBody)
                                                        movieTitle = tJson.getString("title")
                                                        val posterPath = tJson.optString("poster_path", "")
                                                        if (posterPath.isNotEmpty() && posterPath != "null") {
                                                            posterUrl = "https://image.tmdb.org/t/p/w500$posterPath"
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.e("SettingsScreen", "TMDB error", e)
                                            }
                                        }

                                        // URL directa de reproducción de Internet Archive: https://archive.org/download/item_id/file_name
                                        val directUrl = "https://archive.org/download/$itemId/${Uri.encode(fileName)}"
                                        val logoAttr = if (posterUrl.isNotEmpty()) " tvg-logo=\"$posterUrl\"" else ""
                                        m3uContent.append("#EXTINF:-1$logoAttr,$movieTitle\n")
                                        m3uContent.append("$directUrl\n")
                                    }
                                }
                            }

                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("peliculas_archive.m3u", m3uContent.toString())
                            clipboard.setPrimaryClip(clip)

                            consoleLogs.add("🎉 ¡Lista IPTV peliculas_archive.m3u generada exitosamente y copiada al portapapeles! ✓")
                            consoleLogs.add("💡 Ya puedes pegarla en tu reproductor IPTV (VLC, Televizo) para hacer streaming inmediato desde Internet Archive.")
                            playlistGenerated = true
                        } catch (e: Exception) {
                            consoleLogs.add("❌ Error al generar M3U: ${e.message}")
                        } finally {
                            isRunning = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberSlate),
                modifier = Modifier
                    .weight(0.8f)
                    .border(1.dp, CyberNeonCyan, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                enabled = !isRunning
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = CyberNeonCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("EXPORT M3U", color = CyberNeonCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            text = "[SYSTEM_CORE_LOG_STREAM]",
            color = CyberNeonPurple,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CyberDarkSpace.copy(alpha = 0.9f))
                .border(width = 1.dp, color = CyberSlate, shape = RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            if (consoleLogs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.CloudSync, contentDescription = null, tint = CyberTextMuted, modifier = Modifier.size(36.dp))
                    Text(
                        text = "Awaiting engine node activity...",
                        color = CyberTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(consoleLogs) { log ->
                        Text(
                            text = log,
                            color = when {
                                log.contains("SUCCESS") || log.contains("exitosamente") || log.contains("éxito") || log.contains("✓") || log.contains("🎉") || log.contains("Sincronizado") -> CyberSuccessGreen
                                log.contains("ERROR") || log.contains("Fallo") || log.contains("Falla") -> CyberAlertRed
                                log.contains("DETECTADO") || log.contains("FALTANTE") || log.contains("⚠️") -> Color(0xFFFFCC00)
                                log.contains("Iniciando") || log.contains("Consultando") -> CyberNeonPurple
                                else -> CyberTextLight
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .border(width = 1.dp, color = CyberSlate, shape = RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = CyberPanel),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = CyberTextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = color,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun TechSectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = CyberNeonPurple,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 1.sp
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(CyberSlate)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CyberNeonCyan,
                modifier = Modifier.size(18.dp)
            )
        },
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = CyberPanel,
            unfocusedContainerColor = CyberPanel,
            focusedBorderColor = CyberNeonCyan,
            unfocusedBorderColor = CyberSlate,
            focusedTextColor = CyberTextLight,
            unfocusedTextColor = CyberTextLight,
            focusedLabelColor = CyberNeonCyan,
            unfocusedLabelColor = CyberTextMuted
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun extractTmdbId(filename: String): String {
    val nameWithoutExt = filename.substringBeforeLast(".")
    val patternUnderscore = java.util.regex.Pattern.compile("tmdb_(\\d+)_", java.util.regex.Pattern.CASE_INSENSITIVE)
    val matcherUnderscore = patternUnderscore.matcher(nameWithoutExt)
    if (matcherUnderscore.find()) {
        return matcherUnderscore.group(1) ?: ""
    }
    val patternSimple = java.util.regex.Pattern.compile("tmdb_(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
    val matcherSimple = patternSimple.matcher(nameWithoutExt)
    if (matcherSimple.find()) {
        return matcherSimple.group(1) ?: ""
    }
    var cleaned = nameWithoutExt.replace("_", "").replace("/", "")
    val prefixes = listOf("videostmdb", "tmdb")
    for (prefix in prefixes) {
        if (cleaned.startsWith(prefix, ignoreCase = true)) {
            cleaned = cleaned.substring(prefix.length)
            break
        }
    }
    if (cleaned.length >= 9) {
        val tmdbPart = cleaned.substring(0, cleaned.length - 8)
        if (tmdbPart.all { it.isDigit() }) {
            return tmdbPart
        }
    }
    val patternFallback = java.util.regex.Pattern.compile("(\\d{5,8})")
    val matcherFallback = patternFallback.matcher(nameWithoutExt)
    if (matcherFallback.find()) {
        return matcherFallback.group(1) ?: ""
    }
    return ""
}
