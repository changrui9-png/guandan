package com.guandan.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvHandCards: TextView
    private lateinit var tvRemainingCards: TextView
    private lateinit var tvAiAdvice: TextView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    
    private var lastCaptureTime: Long = 0
    private val captureInterval: Long = 2000
    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tvHandCards = findViewById(R.id.tvHandCards)
        tvRemainingCards = findViewById(R.id.tvRemainingCards)
        tvAiAdvice = findViewById(R.id.tvAiAdvice)

        if (allPermissionsGranted()) {
            startCamera()
            startAudioRecognitionService()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 10
            )
        }
    }

    private fun allPermissionsGranted() = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processCameraImage(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                )
            } catch (exc: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processCameraImage(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureTime >= captureInterval) {
            lastCaptureTime = currentTime
            val bitmap = imageProxy.toBitmap()
            val base64Image = bitmapToBase64(bitmap)
            callAiModel(base64Image, getLatestVoiceTranscript())
        }
        imageProxy.close()
    }

    private fun bitmapToBase64(bitmap: android.graphics.Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
    }

    private fun startAudioRecognitionService() {}

    private fun getLatestVoiceTranscript(): String {
        return ""
    }

    private fun callAiModel(imageBase64: String, voiceText: String) {
        val apiKey = AQ.Ab8RN6IxcjG8Wc57uFInetQBcZOXH-qqRcnJzDvNiVyzT8jdxg
        val url = "https://api.openai.com/v1/chat/completions"

        val jsonBody = JSONObject().apply {
            put("model", "gpt-4o")
            put("messages", org.json.JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", org.json.JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "当前掼蛋局势。语音识别出牌（主）: $voiceText。请结合画面中的手牌、已出牌、打法策略，输出：1. 当前手牌；2. 剩余未出牌盘点；3. AI出牌指导。如检测到新一局开始，请自动重置状态。返回JSON格式：{hand: '', remaining: '', advice: '', isNewGame: false}"))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")))
                )
            }))
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { responseBody ->
                    parseAndUpdateUi(responseBody)
                }
            }
        })
    }

    private fun parseAndUpdateUi(jsonResponse: String) {
        handler.post {
            try {
                val json = JSONObject(jsonResponse)
                val choices = json.getJSONArray("choices")
                val content = choices.getJSONObject(0).getJSONObject("message").getString("content")
                val resultJson = JSONObject(content)

                if (resultJson.optBoolean("isNewGame", false)) {
                    resetGameSession()
                }

                tvHandCards.text = "我的手牌: ${resultJson.optString("hand")}"
                tvRemainingCards.text = "剩余未出牌: ${resultJson.optString("remaining")}"
                tvAiAdvice.text = "出牌指导: ${resultJson.optString("advice")}"
            } catch (e: Exception) {}
        }
    }

    private fun resetGameSession() {}

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

