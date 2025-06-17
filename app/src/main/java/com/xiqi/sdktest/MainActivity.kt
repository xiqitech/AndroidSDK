package com.xiqi.sdktest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xiqi.printersdk.PrinterManager
import com.xiqi.printersdk.PrinterState
import com.xiqi.printersdk.PrinterStatus
import com.xiqi.sdktest.ui.theme.XiqiTestTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var printerManager: PrinterManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val foundDevices = mutableStateListOf<BluetoothDevice>() // 设备列表
        var currentConnectingDevice: BluetoothDevice? = null
        var connectedDeviceAddress by mutableStateOf<String?>(null)

        printerManager = PrinterManager(this, object : PrinterManager.BleCallback {
            override fun onDeviceFound(device: BluetoothDevice?) {
                if (device != null) {
                    if (!foundDevices.any { it.address == device.address }) {
                        foundDevices.add(device)
                    }
                }
            }

            override fun onConnected() {
                Log.d("BLE", "Connected to device")
                connectedDeviceAddress = currentConnectingDevice?.address
            }

            override fun onDisconnected() {
                Log.d("BLE", "Disconnected from device")
                connectedDeviceAddress = null
            }

            override fun onPrintDone(success: Boolean, errorMessage: String?) {
                Log.d("BLE", "Print done: $success, Error: $errorMessage")
            }

            override fun onStatusReport(status: PrinterStatus) {
                Log.d("PrinterStatus", "Status: $status")
                runOnUiThread {
                    if (status.isLowBattery) {
                        Toast.makeText(
                            this@MainActivity,
                            "打印机电量过低，请及时充电",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (status.isHot) {
                        Toast.makeText(
                            this@MainActivity,
                            "打印机过热，请及时关机",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (!status.hasPaper) {
                        Toast.makeText(
                            this@MainActivity,
                            "打印机缺纸，请及时补纸",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (status.isLowBattery) {
                        Toast.makeText(
                            this@MainActivity,
                            "打印机电量过低，请及时充电",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })

        val requestBluetoothPermissions = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val allGranted = result.values.all { it }
            if (allGranted) {
                printerManager.startScan(1000) // 权限通过后自动开始扫描
            } else {
                Toast.makeText(this, "蓝牙权限被拒绝，无法扫描设备", Toast.LENGTH_SHORT).show()
            }
        }

        setContent {
            XiqiTestTheme {
                BLEScanScreen(
                    devices = foundDevices,
                    connectedDeviceAddress = connectedDeviceAddress,
                    onScanClick = { checkAndRequestPermissions(requestBluetoothPermissions) },
                    onPrintPdfClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val list = Utils.pdfToImages(this@MainActivity, "testcard.pdf")
                            // corp scale image
                            val width = when (printerManager.deviceType) {
                                1 -> 384
                                2 -> 1664
                                else -> 384
                            }

                            var index = 1
                            for (bitmap in list) {
                                waitForIdleState(20000)
                                Log.d("Print", "Printing image $index/${list.size}")
                                val image = Utils.scaleAndCropImage(bitmap, width, 0)
                                val grayImage = Utils.grayImage(image)
                                val buffer = Utils.bitmapToBinaryBuffer(grayImage, 100)

                                printerManager.print(buffer.array())
                                index++
                            }
                        }
                    },
                    onPrintPicClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val width = when (printerManager.deviceType) {
                                1 -> 384
                                2 -> 1664
                                else -> 384
                            }
                            // 1. 读取 assets/test.png
                            val inputStream = assets.open("test1.png")
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            inputStream.close()

                            val image = Utils.scaleAndCropImage(bitmap, width, 0)
                            val grayImage = Utils.grayImage(image)
                            val grayArray = Utils.extractGrayscaleArray(grayImage)
                            val ditheringArray = Utils.applyFloydSteinbergDithering(grayArray, image.width, image.height,128)
                            if (ditheringArray.isNotEmpty()) {
                                val buffer = Utils.booleanArrayToBinaryBuffer(ditheringArray)
                                printerManager.print(buffer.array())
                            }
                        }
                    },
                    onDeviceSelected = { device ->
                        currentConnectingDevice = device
                        printerManager.connectToDevice(device)
                    }
                )
            }
        }
    }

    private suspend fun waitForIdleState(timeoutMillis: Long = 20000) {
        val startTime = System.currentTimeMillis()
        while (printerManager.getState() != PrinterState.Idle) {
            delay(100) // 每 100ms 检查一次
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
            Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    requestLauncher.launch(permissions) // ✅ 直接调用 launch
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Composable
fun BLEScanScreen(devices: List<BluetoothDevice>, connectedDeviceAddress: String?, onScanClick: () -> Unit, onPrintPdfClick: () -> Unit, onPrintPicClick:()->Unit, onDeviceSelected: (BluetoothDevice) -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Button(onClick = onScanClick, modifier = Modifier.fillMaxWidth()) {
                Text("Scan Bluetooth Devices")
            }
            Button(
                onClick = onPrintPdfClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Print PDF")
            }
            Button(
                onClick = onPrintPicClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Print Pic")
            }
            Spacer(modifier = Modifier.height(16.dp))
            DeviceList(devices, connectedDeviceAddress, onDeviceSelected)
        }
    }
}

@Composable
fun DeviceList(devices: List<BluetoothDevice>, connectedDeviceAddress: String?, onDeviceSelected: (BluetoothDevice) -> Unit) {
    LazyColumn {
        items(devices) { device ->
            DeviceItem(device, isConnected = (device.address == connectedDeviceAddress), onDeviceSelected)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceItem(
    device: BluetoothDevice,
    isConnected: Boolean,
    onDeviceSelected: (BluetoothDevice) -> Unit
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
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            text = if (isConnected) "已连接" else "未连接",
            style = MaterialTheme.typography.bodySmall,
            color = if (isConnected) MaterialTheme.colorScheme.primary else Color.Gray
        )
    }
}

