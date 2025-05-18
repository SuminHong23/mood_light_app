package com.hong.bluetooth

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hong.bluetooth.databinding.ActivityTimeBinding
import java.io.IOException
import java.io.OutputStream
import java.util.Calendar

class TimeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimeBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isSending = false
    private var outputStream: OutputStream? = null

    private val sendTimeRunnable = object : Runnable {
        override fun run() {
            sendColorByTime()
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

        val socket = BluetoothSocketHolder.socket
        if (socket == null || !socket.isConnected) {
            Toast.makeText(this, "Bluetooth 연결이 되어 있지 않습니다.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        outputStream = socket.outputStream

        startSendingTime()
    }

    override fun onDestroy() {
        stopSendingTime()
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

    private fun sendColorByTime() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        val (r, g, b, lightText, bgResId) = when (hour) {
            in 6..8 -> Quint(100, 150, 255, "아침을 깨우는 청량한 푸른빛", R.drawable.bg_morning)
            in 9..16 -> Quint(180, 220, 255, "활동을 돕는 밝은 낮빛", R.drawable.bg_day)
            in 17..20 -> Quint(255, 170, 100, "저녁의 따뜻한 빛", R.drawable.bg_evening)
            else -> Quint(80, 50, 30, "수면을 위한 은은한 주황빛", R.drawable.bg_night)
        }

        // RGB 전송
        val rgb = byteArrayOf(r.toByte(), g.toByte(), b.toByte())
        try {
            outputStream?.write(rgb)
            Log.d("TimeActivity", "Sent RGB: $r, $g, $b")
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "전송 실패", Toast.LENGTH_SHORT).show()
        }

        // 배경 설정
        binding.root.background = ContextCompat.getDrawable(this, bgResId)

        // 텍스트 표시
        binding.timeText.text = "지금은 ${hour}시 ${minute}분입니다.\n$lightText 을 켜드릴게요."
    }

    data class Quint(val r: Int, val g: Int, val b: Int, val description: String, val bgResId: Int)
}
