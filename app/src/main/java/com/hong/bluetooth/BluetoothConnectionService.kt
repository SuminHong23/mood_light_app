package com.hong.bluetooth

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.IOException
import java.util.UUID

class BluetoothConnectionService : Service() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var connectedDevice: BluetoothDevice? = null
    private var bluetoothA2dp: BluetoothA2dp? = null
    private var isA2dpReady = false
    private var isBondReceiverRegistered = false

    private val TARGET_DEVICE_ADDRESS = "F0:24:F9:59:45:12"

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val bondedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (bondedDevice != null && bondedDevice.bondState == BluetoothDevice.BOND_BONDED &&
                    bondedDevice.address == TARGET_DEVICE_ADDRESS) {
                    connectToDevice(bondedDevice)
                    unregisterBondReceiver()
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = proxy as BluetoothA2dp
                isA2dpReady = true
                Log.d("BluetoothService", "A2DP 준비 완료")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = null
                isA2dpReady = false
                Log.d("BluetoothService", "A2DP 해제됨")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        connectToTargetDevice()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "bluetooth_service_channel"
        val channelName = "Bluetooth 연결 서비스"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bluetooth 연결 중")
            .setContentText("블루투스 기기와 연결 상태 유지 중입니다.")
            .setContentIntent(pendingIntent)
            .build()


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToTargetDevice() {
        val device = bluetoothAdapter.getRemoteDevice(TARGET_DEVICE_ADDRESS) ?: run {
            Log.e("BluetoothService", "디바이스 없음")
            stopSelf()
            return
        }

        when (device.bondState) {
            BluetoothDevice.BOND_NONE -> {
                device.createBond()
                registerBondReceiver()
            }
            BluetoothDevice.BOND_BONDING -> {
                registerBondReceiver()
            }
            BluetoothDevice.BOND_BONDED -> {
                connectToDevice(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                )

                if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()

                socket.connect()
                BluetoothSocketHolder.socket = socket
                connectedDevice = device

                connectA2dp(device)
                checkAndBroadcastIfFullyConnected()

            } catch (e: IOException) {
                Log.e("BluetoothService", "소켓 연결 실패: ${e.message}")
                stopSelf()
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun connectA2dp(device: BluetoothDevice) {
        if (!isA2dpReady || bluetoothA2dp == null) return

        try {
            val method = bluetoothA2dp!!.javaClass.getMethod("connect", BluetoothDevice::class.java)
            method.invoke(bluetoothA2dp, device)
            Log.d("A2DP", "A2DP 연결 시도")

            Handler(Looper.getMainLooper()).postDelayed({
                checkAndBroadcastIfFullyConnected()
            }, 1500)

        } catch (e: Exception) {
            Log.e("A2DP", "A2DP 연결 실패: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun isA2dpConnectedTo(device: BluetoothDevice?): Boolean {
        return isA2dpReady && bluetoothA2dp?.connectedDevices?.contains(device) == true
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterBondReceiver()
        bluetoothA2dp?.let {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, it)
        }

        BluetoothSocketHolder.socket?.apply {
            if (isConnected) close()
        }
        BluetoothSocketHolder.socket = null

        Log.d("BluetoothService", "종료 및 리소스 해제")
    }

    private fun registerBondReceiver() {
        if (!isBondReceiverRegistered) {
            registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            isBondReceiverRegistered = true
        }
    }

    private fun unregisterBondReceiver() {
        if (isBondReceiverRegistered) {
            unregisterReceiver(bondReceiver)
            isBondReceiverRegistered = false
        }
    }

    private fun checkAndBroadcastIfFullyConnected() {
        if (connectedDevice != null && isA2dpConnectedTo(connectedDevice)) {
            Log.d("BluetoothService", "블루투스 완전 연결 완료")

            val intent = Intent("com.hong.BLUETOOTH_FULLY_CONNECTED")
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        } else {
            Log.d("BluetoothService", "아직 완전 연결 아님")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
