package com.hong.moodlamp

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.Manifest
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hong.moodlamp.ble.ConnectionEventListener
import com.hong.moodlamp.ble.ConnectionManager
import com.hong.moodlamp.ble.ConnectionManager.parcelableExtraCompat
import com.hong.moodlamp.ble.isWritableWithoutResponse
import com.hong.moodlamp.databinding.ActivityColorBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID


class ColorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityColorBinding
    private val device: BluetoothDevice by lazy {
        intent.parcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice from MainActivity!")
    }
    private val dateFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

    private val characteristics by lazy {
        ConnectionManager.servicesOnDevice(device)?.flatMap { service ->
            service.characteristics ?: listOf()
        } ?: listOf()
    }

    private val writableWithoutResponseCharacteristics by lazy {
        characteristics.filter { it.isWritableWithoutResponse() }
    }

    private val notifyingCharacteristics = mutableListOf<UUID>()

    private val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 권한 확인 및 요청
        if (!hasRequiredBluetoothPermissions()) {
            requestRelevantBluetoothPermissions(PERMISSION_REQUEST_CODE)
        }

        binding = ActivityColorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConnectionManager.registerListener(connectionEventListener)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            title = "BLE 무드등 제어"
        }

        setupColorPicker()
    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        super.onDestroy()
    }

    private fun setupColorPicker() {
        // 색상 피커에서 색상 변화 감지
        binding.colorPicker.addOnColorChangedListener { color ->

            //색상 값 추출
            val r = ((color shr 16) and 0xFF).toByte()
            val g = ((color shr 8) and 0xFF).toByte()
            val b = (color and 0xFF).toByte()

            // RGB 값 전송
            val payload = byteArrayOf(0x01,r, g, b)

            writableWithoutResponseCharacteristics.forEach { characteristic ->
                ConnectionManager.writeCharacteristic(device, characteristic, payload)

            }
        }


    }



    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finishWithResult()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finishWithResult()
    }

    private fun finishWithResult() {
        val resultIntent = Intent().apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    @SuppressLint("SetTextI18n")
    private fun log(message: String) {
        val formattedMessage = "${dateFormatter.format(Date())}: $message"
        Log.d("BLE_LOG", formattedMessage)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    AlertDialog.Builder(this@ColorActivity)
                        .setTitle("Disconnected")
                        .setMessage("Disconnected from device.")
                        .setPositiveButton("OK") { _, _ -> onBackPressed() }
                        .show()
                }
            }

            onCharacteristicWrite = { _, characteristic ->
                log("Wrote to ${characteristic.uuid}")
            }

            onCharacteristicChanged = { _, characteristic, value ->
                log("Value changed on ${characteristic.uuid}: ${value.toHexString()}")
            }

            onNotificationsEnabled = { _, characteristic ->
                log("Enabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.add(characteristic.uuid)
            }

            onNotificationsDisabled = { _, characteristic ->
                log("Disabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.remove(characteristic.uuid)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // 권한이 허용되었을 때
                setupColorPicker()
            } else {
                // 권한이 거부되었을 때
                Toast.makeText(this, "블루투스 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun String.hexToBytes() =
        this.chunked(2).map { it.uppercase(Locale.US).toInt(16).toByte() }.toByteArray()
}
