package com.hong.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hong.bluetooth.databinding.ActivityMusicBinding
import java.io.IOException
import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jtransforms.fft.DoubleFFT_1D
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MusicActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMusicBinding
    private var mediaPlayer: MediaPlayer? = null

    private val REQUEST_CODE_READ_STORAGE = 2001
    private val REQUEST_CODE_PICK_MP3 = 2002
    private var selectedMp3Uri: Uri? = null

    private var bluetoothA2dp: BluetoothA2dp? = null
    private var isA2dpReady = false

    private val musicList = mutableListOf<Uri>()
    private lateinit var adapter: MusicAdapter
    private var selectedIndex: Int = -1

    private var fftJob: Job? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = proxy as BluetoothA2dp
                isA2dpReady = true
                Log.d("MusicActivity", "A2DP 프로필 연결됨")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = null
                isA2dpReady = false
                Log.d("MusicActivity", "A2DP 프로필 해제됨")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicBinding.inflate(layoutInflater)
        setContentView(binding.root)


        val bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)

        binding.btnAdd.setOnClickListener {
            if (checkStoragePermission()) {
                pickMp3File()
            } else {
                requestStoragePermission()
            }
        }

        adapter = MusicAdapter(musicList) { position ->
            selectedIndex = position
            selectedMp3Uri = musicList[position]
            binding.controlPanel.visibility = View.VISIBLE
            binding.btnPlayStop.text = "Play"
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnPlayStop.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                stopMusic()
            } else {
                playMusic()
            }
        }
    }

    private fun pickMp3File() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/mpeg"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_CODE_PICK_MP3)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_MP3 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            musicList.add(uri)
            adapter.notifyItemInserted(musicList.size - 1)
            Toast.makeText(this, "MP3 파일 추가됨", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_AUDIO), REQUEST_CODE_READ_STORAGE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_READ_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "권한 허용됨. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "저장소 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playMusic() {
        if (!isA2dpConnected()) {
            Toast.makeText(this, "블루투스 A2DP 오디오 장치와 연결해 주세요", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedMp3Uri == null) {
            Toast.makeText(this, "먼저 음악을 추가해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer()

        try {
            BluetoothSocketHolder.socket?.outputStream?.write(byteArrayOf(10, 10, 10)) // 초기화

            mediaPlayer?.setDataSource(this, selectedMp3Uri!!)
            mediaPlayer?.prepare()
            mediaPlayer?.start()

            binding.btnPlayStop.text = "Stop"
            Toast.makeText(this, "재생 시작", Toast.LENGTH_SHORT).show()

            decodeAndAnalyzeMp3(selectedMp3Uri!!)

        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "재생 오류 발생", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopMusic() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        fftJob?.cancel()
        fftJob = null

        binding.btnPlayStop.text = "재생"
        binding.controlPanel.visibility = View.GONE

        try {
            BluetoothSocketHolder.socket?.outputStream?.write(byteArrayOf(0, 0, 0)) // LED 끄기
        } catch (e: Exception) {
            Log.e("Bluetooth", "LED 초기화 실패: ${e.message}")
        }

        Toast.makeText(this, "재생 중지", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun isA2dpConnected(): Boolean {
        return isA2dpReady && bluetoothA2dp?.connectedDevices?.isNotEmpty() == true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMusic()
        val bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bluetoothA2dp?.let {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, it)
        }
    }

    private fun decodeAndAnalyzeMp3(uri: Uri) {
        val extractor = MediaExtractor()
        val fd = contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor ?: return
        extractor.setDataSource(fd)

        var audioTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i
                break
            }
        }

        if (audioTrackIndex == -1) {
            Log.e("Decoder", "No audio track found")
            return
        }

        extractor.selectTrack(audioTrackIndex)
        val format = extractor.getTrackFormat(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val inputBuffers = codec.inputBuffers
        val outputBuffers = codec.outputBuffers
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmBuffer = LinkedList<Byte>()
        val fftWindowSize = 2048

        fftJob = CoroutineScope(Dispatchers.Default).launch {
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS && isActive) {
                if (!sawInputEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = inputBuffers[inputBufferIndex]
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0 && bufferInfo.size > 0) {
                    val outputBuffer = outputBuffers[outputBufferIndex]
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.get(chunk)
                    outputBuffer.clear()
                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    pcmBuffer.addAll(chunk.toList())

                    while (pcmBuffer.size >= fftWindowSize) {
                        // 음악 재생 여부는 UI 스레드에서 안전하게 확인
                        val isPlaying = withContext(Dispatchers.Main) {
                            try {
                                mediaPlayer?.isPlaying == true
                            } catch (e: IllegalStateException) {
                                false
                            }
                        }

                        if (!isPlaying) break

                        val frame = pcmBuffer.take(fftWindowSize).toByteArray()
                        repeat(fftWindowSize) { pcmBuffer.removeFirst() }

                        val shortBuf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val samples = ShortArray(shortBuf.remaining())
                        shortBuf.get(samples)

                        val rgb = calculateRgbFromFft(samples)

                        try {
                            BluetoothSocketHolder.socket?.outputStream?.write(rgb)
                        } catch (e: Exception) {
                            Log.e("Bluetooth", "Failed to send data: ${e.message}")
                        }
                    }


                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()
        }
    }

    private fun calculateRgbFromFft(samples: ShortArray): ByteArray {
        val fftSize = 1024
        val fftInput = DoubleArray(fftSize * 2)

        for (i in 0 until fftSize.coerceAtMost(samples.size)) {
            fftInput[2 * i] = samples[i].toDouble()
            fftInput[2 * i + 1] = 0.0
        }

        DoubleFFT_1D(fftSize.toLong()).complexForward(fftInput)

        var totalMagnitude = 0.0
        var weightedFreqSum = 0.0

        for (i in 1 until fftSize / 2) {
            val re = fftInput[2 * i]
            val im = fftInput[2 * i + 1]
            val mag = sqrt(re * re + im * im)
            totalMagnitude += mag
            weightedFreqSum += i * mag
        }

        val brightness = max(0.1, min(1.0, totalMagnitude / 10000.0)).toFloat()

        val centerFreq = if (totalMagnitude > 0) weightedFreqSum / totalMagnitude else 0.0
        val maxFreq = (fftSize / 2).toDouble()

        // log 스케일로 hue 계산, 전체 색상환 0~360도 순환
        val hue = ((Math.log10(1 + centerFreq) / Math.log10(1 + maxFreq)) * 360.0)
            .toFloat()
            .coerceIn(0f, 360f)

        val hsv = floatArrayOf(hue, 1f, brightness)
        val color = Color.HSVToColor(hsv)
        return byteArrayOf(
            Color.red(color).toByte(),
            Color.green(color).toByte(),
            Color.blue(color).toByte()
        )
    }

}
