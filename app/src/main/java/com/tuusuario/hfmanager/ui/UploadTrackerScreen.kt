import com.tuusuario.hfmanager.data.UploadTracker
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


// ---- PALETA DE COLORES CYBERPUNK (Consistente con SettingsScreen) ----
private val CyberDarkSpace = Color(0xFF060913)
private val CyberSlate = Color(0xFF0F1524)
private val CyberPanel = Color(0x9911192E)
private val CyberNeonCyan = Color(0xFF00F0FF)
private val CyberNeonPurple = Color(0xFFD600FF)
private val CyberTextLight = Color(0xFFE2E8F0)
private val CyberTextMuted = Color(0xFF64748B)

@Composable
fun UploadTrackerScreen() {
    val currentState by UploadTracker.currentState.collectAsState()
    val logs by UploadTracker.logs.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberDarkSpace)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(listOf(CyberNeonPurple, CyberNeonCyan)),
                    shape = RoundedCornerShape(12.dp),
                ),
            colors = CardDefaults.cardColors(containerColor = CyberPanel),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "SYSTEM_NODE_STATUS",
                    color = CyberNeonCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )

                StatusDisplay(state = currentState)
            }
        }

        Text(
            text = "> LIVE_LOG_STREAM",
            color = CyberNeonPurple,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(CyberSlate, RoundedCornerShape(8.dp))
                .border(0.5.dp, CyberTextMuted.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        color = if (log.contains("FAILURE") || log.contains("ERROR")) Color.Red 
                                else if (log.contains("SUCCESS")) CyberNeonCyan 
                                else CyberTextLight,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                    )
                }
            }
        }

        TextButton(
            onClick = { UploadTracker.clearLogs() },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("CLEAR_CONSOLE", color = CyberTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun StatusDisplay(state: UploadTracker.UploadState) {
    when (state) {
        is UploadTracker.UploadState.Idle -> {
            Text("STANDBY_IDLE", color = CyberTextMuted, fontFamily = FontFamily.Monospace)
        }
        is UploadTracker.UploadState.Processing -> {
            Column {
                Text("NODE: ${state.fileName}", color = CyberTextLight, fontWeight = FontWeight.Bold)
                Text("STEP: ${state.step}", color = CyberNeonCyan, fontSize = 12.sp)
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = CyberNeonCyan,
                    trackColor = CyberSlate,
                )
            }
        }
        is UploadTracker.UploadState.Uploading -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("UPLOADING: ${state.newName}", color = CyberTextLight, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val progressPct = (state.progress * 100).toInt()
                    Text("PROGRESS: $progressPct%", color = CyberNeonCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("${state.bytesWritten / 1024 / 1024}MB / ${state.totalBytes / 1024 / 1024}MB", color = CyberTextMuted, fontSize = 11.sp)
                }

                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = CyberNeonCyan,
                    trackColor = CyberSlate,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
            }
        }
        is UploadTracker.UploadState.Success -> {
            Text("COMPLETED: ${state.fileName}", color = CyberNeonCyan, fontWeight = FontWeight.Bold)
        }
        is UploadTracker.UploadState.Error -> {
            Text("FAILURE: ${state.fileName}", color = Color.Red, fontWeight = FontWeight.Bold)
            Text(state.errorMessage, color = Color.Red.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}
