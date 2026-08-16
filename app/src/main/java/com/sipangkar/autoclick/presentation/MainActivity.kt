package com.sipangkar.autoclick.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sipangkar.autoclick.data.service.AutoClickAccessibilityService
import com.sipangkar.autoclick.data.service.OverlayMode
import com.sipangkar.autoclick.data.service.VisualEditorOverlayService
import com.sipangkar.autoclick.domain.model.Macro
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MacroViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4CAF50),
                    secondary = Color(0xFF2196F3),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh macro list when user returns to app
        viewModel.loadMacros()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MacroViewModel) {
    val context = LocalContext.current
    val macros by viewModel.macros.collectAsState()
    
    // Check permission states dynamically
    var isOverlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val isAccessibilityEnabled by AutoClickAccessibilityService.serviceConnected.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }

    // Periodically re-check overlay permission when screen composition recomposes
    LaunchedEffect(Unit) {
        while (true) {
            isOverlayGranted = Settings.canDrawOverlays(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Macro Auto-Clicker", 
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1C1C1C)
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Macro")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permissions card
            item {
                PermissionGuideCard(
                    isOverlayGranted = isOverlayGranted,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    onRequestOverlay = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    onRequestAccessibility = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )
            }

            item {
                Text(
                    text = "Daftar Makro Anda",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            if (macros.isEmpty()) {
                item {
                    EmptyStateCard()
                }
            } else {
                items(macros) { macro ->
                    MacroItemCard(
                        macro = macro,
                        onRun = {
                            if (isOverlayGranted && isAccessibilityEnabled) {
                                // Set service state
                                VisualEditorOverlayService.activeMacroState.value = macro
                                VisualEditorOverlayService.macroSteps.clear()
                                VisualEditorOverlayService.macroSteps.addAll(macro.steps)
                                VisualEditorOverlayService.activeMacroName.value = macro.name
                                VisualEditorOverlayService.setMode(OverlayMode.PLAYING)
                                startOverlayService(context)
                            } else {
                                // Show dialog or message
                            }
                        },
                        onEdit = {
                            if (isOverlayGranted && isAccessibilityEnabled) {
                                VisualEditorOverlayService.activeMacroState.value = macro
                                VisualEditorOverlayService.macroSteps.clear()
                                VisualEditorOverlayService.macroSteps.addAll(macro.steps)
                                VisualEditorOverlayService.activeMacroName.value = macro.name
                                VisualEditorOverlayService.setMode(OverlayMode.EDITING)
                                startOverlayService(context)
                            }
                        },
                        onDelete = {
                            viewModel.deleteMacro(macro)
                        }
                    )
                }
            }
        }

        if (showCreateDialog) {
            CreateMacroDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { name ->
                    showCreateDialog = false
                    if (isOverlayGranted && isAccessibilityEnabled) {
                        VisualEditorOverlayService.activeMacroState.value = null
                        VisualEditorOverlayService.macroSteps.clear()
                        VisualEditorOverlayService.activeMacroName.value = name
                        VisualEditorOverlayService.setMode(OverlayMode.EDITING)
                        startOverlayService(context)
                    }
                }
            )
        }
    }
}

@Composable
fun PermissionGuideCard(
    isOverlayGranted: Boolean,
    isAccessibilityEnabled: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Panduan Izin Layanan",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White
            )
            Text(
                "Aplikasi memerlukan izin berikut agar dapat melakukan klik otomatis di game/aplikasi target Anda.",
                fontSize = 12.sp,
                color = Color.Gray
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            // Overlay Row
            PermissionRow(
                title = "Tampilkan di Atas Aplikasi Lain",
                description = "Dibutuhkan untuk merender floating toolbar control panel.",
                isGranted = isOverlayGranted,
                onClick = onRequestOverlay
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            // Accessibility Row
            PermissionRow(
                title = "Layanan Aksesibilitas (Accessibility)",
                description = "Dibutuhkan untuk mensimulasikan klik, hold, geser secara otomatis.",
                isGranted = isAccessibilityEnabled,
                onClick = onRequestAccessibility
            )
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
            Text(description, fontSize = 11.sp, color = Color.Gray)
        }

        Spacer(modifier = Modifier.width(16.dp))

        if (isGranted) {
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x334CAF50)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Aktif", color = Color(0xFF4CAF50), fontSize = 12.sp)
            }
        } else {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Aktifkan", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun MacroItemCard(
    macro: Macro,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = macro.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Text(
                    text = "${macro.steps.size} Langkah • Loop: ${macro.loopCount}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onRun,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x224CAF50))
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = Color(0xFF4CAF50))
                }

                IconButton(
                    onClick = onEdit,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x222196F3))
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF2196F3))
                }

                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x22F44336))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF44336))
                }
            }
        }
    }
}

@Composable
fun EmptyStateCard() {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = Color.DarkGray,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "Belum Ada Makro",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Buat makro baru dengan mengeklik tombol + di sudut kanan bawah.",
                fontSize = 12.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMacroDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Buat Makro Baru", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Masukkan nama makro:", color = Color.Gray, fontSize = 12.sp)
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("misal: farm_gold") },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF333333),
                        unfocusedContainerColor = Color(0xFF333333)
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                enabled = text.isNotBlank()
            ) {
                Text("Buat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = Color.White)
            }
        },
        containerColor = Color(0xFF222222)
    )
}

fun startOverlayService(context: Context) {
    val serviceIntent = Intent(context, VisualEditorOverlayService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
    } else {
        context.startService(serviceIntent)
    }
}
