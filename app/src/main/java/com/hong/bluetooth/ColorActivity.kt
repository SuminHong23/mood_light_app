package com.hong.bluetooth


import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hong.bluetooth.databinding.ActivityColorBinding
import java.io.IOException
import java.io.OutputStream


class ColorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityColorBinding
    private var outputStream: OutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityColorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            title = "무드등 제어"
        }

        // 소켓 받아오기
        val socket = BluetoothSocketHolder.socket
        if (socket == null || !socket.isConnected) {
            Toast.makeText(this, "Bluetooth 연결이 되어 있지 않습니다.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        outputStream = socket.outputStream
        setupColorPicker()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // LED 끄기 (검정색: R=0, G=0, B=0)
            outputStream?.write(byteArrayOf(0, 0, 0))
            Log.d("ColorActivity", "Sent RGB: 0, 0, 0 (LED off)")


        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun setupColorPicker() {
        binding.colorPicker.addOnColorChangedListener { color ->
            val r = ((color shr 16) and 0xFF).toByte()
            val g = ((color shr 8) and 0xFF).toByte()
            val b = (color and 0xFF).toByte()

            val rgb = byteArrayOf(r, g, b)
            try {
                outputStream?.write(rgb)
                Log.d("ColorActivity", "Sent RGB: ${r}, ${g}, ${b}")
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(this, "전송 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}


