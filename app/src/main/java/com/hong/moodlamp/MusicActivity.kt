package com.hong.moodlamp


import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hong.moodlamp.databinding.ActivityMusicBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jtransforms.fft.DoubleFFT_1D
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import kotlin.math.sqrt


class MusicActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMusicBinding
    private lateinit var musicFile: File
    private var analyzedData: List<Float> = listOf()

    // Wi-Fi 전송용 서버 주소 및 포트
    private val serverIp = "192.168.0.100" // ← ESP32 IP 주소로 수정하세요
    private val serverPort = 8888

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }

        val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                musicFile = uriToFile(it)
                Toast.makeText(this, "파일 선택됨: ${musicFile.name}", Toast.LENGTH_SHORT).show()

                lifecycleScope.launch {
                    withContext(Dispatchers.Default) {
                        analyzedData = analyzeAudio(musicFile)
                    }
                    Toast.makeText(this@MusicActivity, "분석 완료", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.selectButton.setOnClickListener {
            pickAudioLauncher.launch("audio/*")
        }

        binding.sendButton.setOnClickListener {
            lifecycleScope.launch {
                sendMusicFile(musicFile)
                sendAnalyzedData(analyzedData)
                Toast.makeText(this@MusicActivity, "전송 완료!", Toast.LENGTH_SHORT).show()
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

    private fun uriToFile(uri: Uri): File {
        val inputStream = contentResolver.openInputStream(uri)
        val tempFile = File.createTempFile("selected_music", ".mp3", cacheDir)
        inputStream?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
        return tempFile
    }

    private fun analyzeAudio(file: File): List<Float> {
        val amplitudes = mutableListOf<Float>()
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var audioTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                break
            }
        }

        if (audioTrackIndex == -1) return emptyList()

        extractor.selectTrack(audioTrackIndex)
        val format = extractor.getTrackFormat(audioTrackIndex)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val inputBuffers = codec.inputBuffers
        val outputBuffers = codec.outputBuffers
        val bufferInfo = MediaCodec.BufferInfo()

        val fftWindowSize = 1024
        val fft = DoubleFFT_1D(fftWindowSize.toLong())
        val fftBuffer = DoubleArray(fftWindowSize)
        val sampleBuffer = ShortArray(fftWindowSize)

        var isEOS = false
        var bufferOffset = 0

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val buffer = inputBuffers[inIndex]
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isEOS = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            while (outIndex >= 0) {
                val buffer = outputBuffers[outIndex]
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)

                val pcm = ByteArray(buffer.remaining())
                buffer.get(pcm)

                for (i in pcm.indices step 2) {
                    if (bufferOffset >= fftWindowSize) {
                        for (j in 0 until fftWindowSize) {
                            fftBuffer[j] = sampleBuffer[j].toDouble()
                        }

                        fft.realForward(fftBuffer)
                        var sum = 0.0
                        for (j in fftBuffer.indices step 2) {
                            val real = fftBuffer[j]
                            val imag = fftBuffer.getOrElse(j + 1) { 0.0 }
                            sum += sqrt(real * real + imag * imag)
                        }

                        amplitudes.add(sum.toFloat())
                        bufferOffset = 0
                    }

                    val low = pcm[i].toInt() and 0xff
                    val high = pcm[i + 1].toInt()
                    val sample = (high shl 8) or low
                    sampleBuffer[bufferOffset++] = sample.toShort()
                }

                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return amplitudes
    }

    private suspend fun sendMusicFile(file: File) = withContext(Dispatchers.IO) {
        val socket = Socket(serverIp, serverPort)
        val out = socket.getOutputStream()

        out.write(0x01) // WAV 파일 전송 시작을 알림
        out.flush()
        delay(100) // 수신 준비 시간 확보

        file.inputStream().use { input ->
            input.copyTo(out)
        }

        socket.close()
    }

    private suspend fun sendAnalyzedData(data: List<Float>) = withContext(Dispatchers.IO) {
        val byteBuffer = ByteArrayOutputStream()
        data.forEach { value ->
            val intBits = java.lang.Float.floatToIntBits(value)
            byteBuffer.write(byteArrayOf(
                (intBits shr 24).toByte(),
                (intBits shr 16).toByte(),
                (intBits shr 8).toByte(),
                intBits.toByte()
            ))
        }

        val socket = Socket(serverIp, serverPort)
        val out = socket.getOutputStream()

        out.write(0x02) // 분석 데이터 전송 시작을 알림
        out.flush()
        delay(100) // 수신 준비 시간 확보

        out.write(byteBuffer.toByteArray())
        out.flush()

        socket.close()
    }

}
