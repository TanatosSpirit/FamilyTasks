package com.familytasks.app.ui.onboarding

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.familytasks.app.PortraitCaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeScreen(
    onDone: () -> Unit,
    vm: QrCodeViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            vm.onQrScanned(result.contents, onDone)
        }
    }
    val pendingPairing by vm.pendingPairing.collectAsStateWithLifecycle()

    pendingPairing?.let { pending ->
        val formerNames = pending.formerPartners.joinToString { "«${it.displayName}»" }
        AlertDialog(
            onDismissRequest = vm::cancelPairing,
            title = { Text("Сменить партнёра?") },
            text = {
                Text(
                    "Задачи, назначенные на $formerNames или созданные им, перейдут к " +
                        "«${pending.newPartner.displayName}». Прежнее устройство будет забыто.\n\n" +
                        "Отменить это действие нельзя."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmPairing(onDone) }) { Text("Перенести") }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelPairing) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Сопряжение устройств") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Мой QR-код", modifier = Modifier.padding(vertical = 12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("Сканировать", modifier = Modifier.padding(vertical = 12.dp))
                }
            }

            when (selectedTab) {
                0 -> MyQrTab(content = vm.ownQrContent, onSkip = { vm.skip(onDone) })
                1 -> ScanTab(
                    onScan = {
                        val opts = ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Сканируйте QR-код второго устройства")
                            setBeepEnabled(false)
                            setCaptureActivity(PortraitCaptureActivity::class.java)
                        }
                        scanLauncher.launch(opts)
                    },
                    onSkip = { vm.skip(onDone) }
                )
            }
        }
    }
}

@Composable
private fun MyQrTab(content: String, onSkip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Покажите этот QR-код второму устройству", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(24.dp))
        val bitmap = remember(content) { generateQrBitmap(content) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.size(240.dp)
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Пропустить")
        }
    }
}

@Composable
private fun ScanTab(onScan: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Сканируйте QR-код второго устройства", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Text("Открыть камеру")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Пропустить")
        }
    }
}

private fun generateQrBitmap(content: String, size: Int = 512): Bitmap? {
    return try {
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        bmp
    } catch (_: Exception) { null }
}
