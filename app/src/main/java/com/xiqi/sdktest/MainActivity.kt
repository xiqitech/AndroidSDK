package com.xiqi.sdktest

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xiqi.printersdk.Printer
import com.xiqi.printersdk.PrinterState
import com.xiqi.printersdk.PrinterStatus
import com.xiqi.sdktest.ui.theme.XiqiTestTheme
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private lateinit var printer: Printer
    private var printJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val foundDevices = mutableStateListOf<Printer.DiscoveredDevice>()
        var connectedDeviceId by mutableStateOf<String?>(null)
        var currentConnectingDeviceId: String? = null

        // ✅ 新版：不再传 BleCallback
        printer = Printer(this)

        // ====== 订阅 Printer 的各类 Flow（生命周期感知）======
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 发现设备（结构化：id/name/mac/rssi/manufacturerId）
                launch {
                    printer.deviceFound.collect { d ->
                        if (!foundDevices.any { it.id == d.id }) {
                            foundDevices.add(d)
                        }
                    }
                }
                // 状态上报（低电/过热/缺纸）
                launch {
                    printer.statusReports.collect { status ->
                        if (status.isLowBattery) {
                            Toast.makeText(this@MainActivity, "打印机电量过低，请及时充电", Toast.LENGTH_SHORT).show()
                        }
                        if (status.isHot) {
                            Toast.makeText(this@MainActivity, "打印机过热，请及时关机", Toast.LENGTH_SHORT).show()
                        }
                        if (!status.hasPaper) {
                            Toast.makeText(this@MainActivity, "打印机缺纸，请及时补纸", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                // 打印结果
                launch {
                    printer.printResults.collect { result ->
                        Log.d("BLE", "Print done: ${result.success}, Error: ${result.errorMessage}")
                    }
                }
                // 状态机变化（控制 UI 的“已连接/未连接”标识）
                launch {
                    printer.state.collect { s ->
                        when (s) {
                            PrinterState.Connected -> connectedDeviceId = currentConnectingDeviceId
                            PrinterState.Disconnected -> connectedDeviceId = null
                            else -> Unit
                        }
                    }
                }
            }
        }

        val requestBluetoothPermissions = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val allGranted = result.values.all { it }
            if (allGranted) {
                printer.startScan(1000) // 权限通过后自动开始扫描
            } else {
                Toast.makeText(this, "蓝牙权限被拒绝，无法扫描设备", Toast.LENGTH_SHORT).show()
            }
        }

        setContent {
            XiqiTestTheme {
                BLEScanScreen(
                    devices = foundDevices,
                    connectedDeviceId = connectedDeviceId,
                    onScanClick = { checkAndRequestPermissions(requestBluetoothPermissions) },
                    onPrintPdfClick = {
                        printJob = CoroutineScope(Dispatchers.IO).launch {
                            val list = Utils.pdfToImages(this@MainActivity, "testcard.pdf")
                            val width = when (printer.deviceType) {
                                1 -> 384
                                2 -> 1664
                                else -> 384
                            }

                            var index = 1
                            for (bitmap in list) {
                                Log.d("Print", "Printing image $index/${list.size}")

                                val image = Utils.scaleAndCropImage(bitmap, width, 0)
                                val grayImage = Utils.grayImage(image)
                                val buffer = Utils.bitmapToBinaryBuffer(grayImage, 100)

                                // 1) 先发起本页打印
                                printer.print(buffer.array())

                                // 2) 等到状态从 Idle -> 非 Idle（开始了）
                                waitUntilStarted(timeoutMillis = 2_000)

                                // 3) 再等到状态回到 Idle（本页完成）
                                waitUntilIdle(timeoutMillis = 60_000)

                                index++
                            }
                        }
                    },
                    onPrintPicClick = {
                        printJob = CoroutineScope(Dispatchers.IO).launch {
                            val width = when (printer.deviceType) {
                                1 -> 384
                                2 -> 1664
                                else -> 384
                            }
                            val inputStream = assets.open("test1.png")
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            inputStream.close()

                            val image = Utils.scaleAndCropImage(bitmap, width, 0)
                            val grayImage = Utils.grayImage(image)
                            val grayArray = Utils.extractGrayscaleArray(grayImage)
                            val ditheringArray = Utils.applyFloydSteinbergDithering(
                                grayArray, image.width, image.height, 128
                            )
                            if (ditheringArray.isNotEmpty()) {
                                val buffer = Utils.booleanArrayToBinaryBuffer(ditheringArray)
                                printer.print(buffer.array())
                            }
                        }
                    },
                    onStopClick = {
                        printJob?.cancel()
                        printer.stopPrint()
                        Log.d("Print", "Stop requested")
                    },
                    onDeviceSelected = { device ->
                        currentConnectingDeviceId = device.id
                        printer.connect(device) // 或 printer.connectById(device.id)
                    }
                )
            }
        }
    }

    // === 等待状态辅助 ===
    private suspend fun waitUntilStarted(timeoutMillis: Long) {
        val start = System.currentTimeMillis()
        while (printer.state.value == PrinterState.Idle) {
            delay(10)
            if (System.currentTimeMillis() - start > timeoutMillis) {
                Log.w("Print", "等待进入打印超时（仍为 Idle），可能设备响应慢")
                return
            }
        }
    }

    private suspend fun waitUntilIdle(timeoutMillis: Long) {
        val start = System.currentTimeMillis()
        while (printer.state.value != PrinterState.Idle) {
            delay(50)
            if (System.currentTimeMillis() - start > timeoutMillis) {
                Log.e("Print", "等待打印完成回到 Idle 超时")
                return
            }
        }
    }

    private suspend fun waitForIdleState(timeoutMillis: Long = 20_000) {
        val startTime = System.currentTimeMillis()
        while (printer.state.value != PrinterState.Idle &&
            currentCoroutineContext().isActive) {
            delay(100)
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                Log.e("Print", "等待打印机空闲超时")
                break
            }
        }
    }
}

private fun checkAndRequestPermissions(requestLauncher: ActivityResultLauncher<Array<String>>) {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
    requestLauncher.launch(permissions)
}

@Composable
fun BLEScanScreen(
    devices: List<Printer.DiscoveredDevice>,
    connectedDeviceId: String?,
    onScanClick: () -> Unit,
    onPrintPdfClick: () -> Unit,
    onPrintPicClick: () -> Unit,
    onStopClick: () -> Unit,
    onDeviceSelected: (Printer.DiscoveredDevice) -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Button(onClick = onScanClick, modifier = Modifier.fillMaxWidth()) {
                Text("Scan Bluetooth Devices")
            }
            Button(onClick = onPrintPdfClick, modifier = Modifier.fillMaxWidth()) {
                Text("Print PDF")
            }
            Button(onClick = onPrintPicClick, modifier = Modifier.fillMaxWidth()) {
                Text("Print Pic")
            }
            Button(onClick = onStopClick, modifier = Modifier.fillMaxWidth()) {
                Text("Stop Print")
            }

            Spacer(modifier = Modifier.height(16.dp))
            DeviceList(devices, connectedDeviceId, onDeviceSelected)
        }
    }
}

@Composable
fun DeviceList(
    devices: List<Printer.DiscoveredDevice>,
    connectedDeviceId: String?,
    onDeviceSelected: (Printer.DiscoveredDevice) -> Unit
) {
    LazyColumn {
        items(devices) { device ->
            DeviceItem(
                device = device,
                isConnected = (device.id == connectedDeviceId),
                onDeviceSelected = onDeviceSelected
            )
        }
    }
}

@Composable
fun DeviceItem(
    device: Printer.DiscoveredDevice,
    isConnected: Boolean,
    onDeviceSelected: (Printer.DiscoveredDevice) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDeviceSelected(device) }
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = device.name ?: "未知设备", style = MaterialTheme.typography.bodyLarge)
            Text(text = "BLE: ${device.id}", style = MaterialTheme.typography.bodySmall)
            Text(text = "MAC: ${device.mac ?: "--"}", style = MaterialTheme.typography.bodySmall)
            Text(text = "RSSI: ${device.rssi}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = if (isConnected) "已连接" else "未连接",
            style = MaterialTheme.typography.bodySmall,
            color = if (isConnected) MaterialTheme.colorScheme.primary else Color.Gray
        )
    }
}
