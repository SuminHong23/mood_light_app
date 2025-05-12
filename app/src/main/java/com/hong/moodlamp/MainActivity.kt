package com.hong.moodlamp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.flask.colorpicker.BuildConfig
import com.hong.moodlamp.ble.ConnectionManager.parcelableExtraCompat
import com.hong.moodlamp.ble.ConnectionEventListener
import com.hong.moodlamp.ble.ConnectionManager
import com.hong.moodlamp.databinding.ActivityMainBinding
import timber.log.Timber




class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var connectedDevice: BluetoothDevice? = null

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private var isScanning = false
        set(value) {
            field = value
            runOnUiThread { updateBleButtonText() }
        }

    private val scanResults = mutableListOf<ScanResult>()

    private val bluetoothEnablingResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Timber.i("Bluetooth is enabled")
        } else {
            Timber.e("Bluetooth enabling denied")
            promptEnableBluetooth()
        }
    }

    private val bleOperationsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val device = result.data?.parcelableExtraCompat<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (device != null) {
                connectedDevice = device
                ConnectionManager.connect(device, this)
                updateBleButtonText()
            }
        }
    }

    private val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 권한 확인 및 요청
        if (!hasRequiredBluetoothPermissions()) {
            requestRelevantBluetoothPermissions(PERMISSION_REQUEST_CODE)
        }

        binding.bleButton.setOnClickListener {
            if (isScanning) stopBleScan() else startBleScan()
        }

        binding.colorButton.setOnClickListener {
            connectedDevice?.let { device ->
                val intent = Intent(this, ColorActivity::class.java).apply {
                    putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                }
                bleOperationsLauncher.launch(intent)
            } ?: run {
                Toast.makeText(this, "먼저 블루투스를 연결하세요.", Toast.LENGTH_SHORT).show()
            }
        }
        binding.timeButton.setOnClickListener {
            connectedDevice?.let { device ->
                val intent = Intent(this, TimeActivity::class.java).apply {
                    putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                }
                bleOperationsLauncher.launch(intent)
            } ?: run {
                Toast.makeText(this, "먼저 블루투스를 연결하세요.", Toast.LENGTH_SHORT).show()
            }
        }
        binding.musicButton.setOnClickListener {
            connectedDevice?.let { device ->
                val intent = Intent(this, MusicActivity::class.java).apply {
                    putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                }
                bleOperationsLauncher.launch(intent)
            } ?: run {
                Toast.makeText(this, "먼저 블루투스를 연결하세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.registerListener(connectionEventListener)
        if (!bluetoothAdapter.isEnabled) {
            promptEnableBluetooth()
        } else {
            updateBleButtonText()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isScanning) {
            stopBleScan()
        }
        ConnectionManager.unregisterListener(connectionEventListener)
    }

    private fun updateBleButtonText() {
        binding.bleButton.text = if (bluetoothAdapter.isEnabled && connectedDevice != null) {
            "블루투스 연결됨"
        } else {
            "블루투스를 연결해 주세요"
        }
    }

    private fun promptEnableBluetooth() {
        if (hasRequiredBluetoothPermissions() && !bluetoothAdapter.isEnabled) {
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                bluetoothEnablingResult.launch(this)
            }
        }
    }

    private fun hasRequiredBluetoothPermissions(): Boolean {
        return bluetoothPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRelevantBluetoothPermissions(requestCode: Int) {
        ActivityCompat.requestPermissions(this, bluetoothPermissions, requestCode)
    }

    private fun startBleScan() {
        if (!hasRequiredBluetoothPermissions()) {
            requestRelevantBluetoothPermissions(PERMISSION_REQUEST_CODE)
            return
        }

        try {
            scanResults.clear()
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Timber.e("BLUETOOTH_SCAN permission missing.")
                return
            }
            bleScanner.startScan(null, scanSettings, scanCallback)
            isScanning = true
        } catch (e: SecurityException) {
            Timber.e("SecurityException during startBleScan: ${e.message}")
        }
    }

    private fun stopBleScan() {
        if (!hasRequiredBluetoothPermissions()) {
            Timber.e("Required Bluetooth permissions are not granted.")
            return
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Timber.e("BLUETOOTH_SCAN permission missing.")
                return
            }
            bleScanner.stopScan(scanCallback)
            isScanning = false
        } catch (e: SecurityException) {
            Timber.e("SecurityException during stopBleScan: ${e.message}")
        }
    }

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onConnectionSetupComplete = { gatt ->
                runOnUiThread {
                    connectedDevice = gatt.device
                    updateBleButtonText()
                }
            }

            onDisconnect = {
                runOnUiThread {
                    try {
                        if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            Timber.e("Bluetooth CONNECT permission is missing during disconnect")
                        }
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.disconnected)
                            .setMessage(getString(R.string.disconnected_or_unable_to_connect_to_device, it.name))
                            .setPositiveButton(R.string.ok, null)
                            .show()
                        connectedDevice = null
                        updateBleButtonText()
                    } catch (e: SecurityException) {
                        Timber.e("SecurityException during onDisconnect: ${e.message}")
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                with(result.device) {
                    if (address == TARGET_DEVICE_ADDRESS) {
                        Timber.w("Found target device: $address, connecting...")
                        stopBleScan()
                        ConnectionManager.connect(this, this@MainActivity)
                    }
                }
            } catch (e: SecurityException) {
                Timber.e("SecurityException during onScanResult: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("onScanFailed: code $errorCode")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBleScan()
            } else {
                Toast.makeText(this, "블루투스 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val TARGET_DEVICE_ADDRESS = "F0:24:F9:59:45:12" // 타겟 디바이스 주소로 변경
    }
}
