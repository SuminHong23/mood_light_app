package com.hong.bluetooth



import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hong.bluetooth.databinding.ActivityMainBinding
import android.Manifest
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val REQUEST_CODE_PERMISSIONS = 1001

    private var isBluetoothFullyConnected = false


    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == "com.hong.BLUETOOTH_FULLY_CONNECTED") {
                    Log.d("MainActivity", "Updating UI")
                    binding.btnBlueTooth.text = "연결됨"
                    binding.btnBlueTooth.isEnabled = false
                    isBluetoothFullyConnected = true

            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBlueTooth.setOnClickListener {
            if (!checkPermissions()) {
                requestPermissions()
            } else {
                startBluetoothService()
            }
        }

        binding.btnMusic.setOnClickListener {
            if (isBluetoothFullyConnected) {
                startActivity(Intent(this, MusicActivity::class.java))
            } else {
                Toast.makeText(this, "블루투스 연결이 완료되지 않았습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnTime.setOnClickListener {
            if (isBluetoothFullyConnected) {
                startActivity(Intent(this, TimeActivity::class.java))
            } else {
                Toast.makeText(this, "블루투스 연결이 완료되지 않았습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnColor.setOnClickListener {
            if (isBluetoothFullyConnected) {
                startActivity(Intent(this, ColorActivity::class.java))
            } else {
                Toast.makeText(this, "블루투스 연결이 완료되지 않았습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.hong.BLUETOOTH_FULLY_CONNECTED")
        LocalBroadcastManager.getInstance(this).registerReceiver(connectionReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(connectionReceiver)
    }

    private fun startBluetoothService() {
        val serviceIntent = Intent(this, BluetoothConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_CODE_PERMISSIONS
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBluetoothService()
            } else {
                Toast.makeText(this, "권한이 필요합니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
