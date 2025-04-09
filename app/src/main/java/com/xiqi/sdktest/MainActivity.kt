package com.xiqi.sdktest
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xiqi.sdktest.ui.theme.XiqiTestTheme
import com.xiqi.printersdk.PrinterManager
import com.xiqi.printersdk.PrinterStatus
import android.os.Build
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var printerManager: PrinterManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val foundDevices = mutableStateListOf<BluetoothDevice>() // 设备列表

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
            }

            override fun onDisconnected() {
                Log.d("BLE", "Disconnected from device")
            }

            override fun onStatusReport(status: PrinterStatus) {
                Log.d("BLE", "Status updated: ${status.toString()}")
            }
        })

        val requestBluetoothPermissions = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val allGranted = result.values.all { it }
            if (allGranted) {
                Log.d("BLE", "All permissions granted")
                printerManager.startScan() // 🔹 权限通过后自动开始扫描
            } else {
                Toast.makeText(this, "蓝牙权限被拒绝，无法扫描设备", Toast.LENGTH_SHORT).show()
            }
        }

        setContent {
            XiqiTestTheme {
                BLEScanScreen(
                    devices = foundDevices,
                    onScanClick = { checkAndRequestPermissions(requestBluetoothPermissions) },
                    onGetDeviceClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val buffer = ByteArray(1000 * 96)
                            for (i in buffer.indices) {
                                buffer[i] = 0x08.toByte()
                            }
                            printerManager.print(buffer)
                        }
                    },
                    onDeviceSelected = { device ->
                        printerManager.connectToDevice(device)
                    }
                )
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
fun BLEScanScreen(devices: List<BluetoothDevice>, onScanClick: () -> Unit, onGetDeviceClick: () -> Unit, onDeviceSelected: (BluetoothDevice) -> Unit) {
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
                onClick = onGetDeviceClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Print")
            }
            Spacer(modifier = Modifier.height(16.dp))
            DeviceList(devices, onDeviceSelected)
        }
    }
}

@Composable
fun DeviceList(devices: List<BluetoothDevice>, onDeviceSelected: (BluetoothDevice) -> Unit) {
    LazyColumn {
        items(devices) { device ->
            DeviceItem(device, onDeviceSelected)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceItem(device: BluetoothDevice, onDeviceSelected: (BluetoothDevice) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable { onDeviceSelected(device) }
    ) {
        Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
        Text(text = device.address, style = MaterialTheme.typography.bodySmall)
    }
}