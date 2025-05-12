package com.hong.moodlamp


import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hong.moodlamp.ble.ConnectionEventListener
import com.hong.moodlamp.ble.ConnectionManager
import com.hong.moodlamp.ble.ConnectionManager.parcelableExtraCompat
import com.hong.moodlamp.ble.isWritableWithoutResponse
import com.hong.moodlamp.databinding.ActivityTimeBinding
import java.util.Calendar

class TimeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimeBinding
    private val device: BluetoothDevice by lazy {
        intent.parcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice!")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isSending = false

    private val characteristics by lazy {
        ConnectionManager.servicesOnDevice(device)?.flatMap { it.characteristics ?: listOf() } ?: listOf()
    }

    private val writableWithoutResponseCharacteristics by lazy {
        characteristics.filter { it.isWritableWithoutResponse() }
    }

    private val sendTimeRunnable = object : Runnable {
        override fun run() {
            sendCurrentHour()
            handler.postDelayed(this, 5000) // 5초마다 반복 전송
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            title = "시간 모드"
        }

        ConnectionManager.registerListener(connectionEventListener)

        startSendingTime()
    }

    override fun onDestroy() {
        stopSendingTime()
        ConnectionManager.unregisterListener(connectionEventListener)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun startSendingTime() {
        isSending = true
        sendTimeRunnable.run()
    }

    private fun stopSendingTime() {
        isSending = false
        handler.removeCallbacks(sendTimeRunnable)
    }

    private fun sendCurrentHour() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        // ESP32에 보낼 payload: [0x02, hour]
        val payload = byteArrayOf(0x02, hour.toByte())

        writableWithoutResponseCharacteristics.forEach { characteristic ->
            ConnectionManager.writeCharacteristic(device, characteristic, payload)
        }

        // 화면에 시간 표시
        val lightText = when (hour) {
            in 6..8 -> "차가운 아침광"
            in 9..16 -> "선명한 낮광"
            in 17..18 -> "따뜻한 저녁광"
            in 19..21 -> "붉은 노을광"
            else -> "은은한 야간광"
        }

        binding.timeText.text = "지금은 ${hour}시 ${minute}분입니다.\n$lightText 을 켜드릴게요."
    }

    private val connectionEventListener = ConnectionEventListener().apply {
        onDisconnect = {
            runOnUiThread {
                Toast.makeText(this@TimeActivity, "기기 연결이 끊어졌습니다", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
