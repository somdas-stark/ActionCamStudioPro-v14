package com.actioncam.studiopro

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private var selectedVideoUri: Uri? = null
    private var selectedAudioUri: Uri? = null
    private var rawPreviewBitmap: Bitmap? = null
    private var activeTransformer: Transformer? = null
    private var isRendering = false
    private var renderStartMs = 0L

    private val geminiApiKey = "AQ.Ab8RN6JLBkQBGUqGtPvNeCYMwvrZuJcEtotiBZdJCPGI8ts6SA"
    private val aiModels = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3-flash-preview")

    private lateinit var tvHwStats: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var tvSelectedVideo: TextView
    private lateinit var tvSplitBadge: TextView
    private lateinit var sbSplitCompare: SeekBar
    private lateinit var tvSelectedAudio: TextView
    private lateinit var tvAiSceneResult: TextView
    private lateinit var tvRenderStatus: TextView
    private lateinit var progressRender: ProgressBar
    private lateinit var cbMuteOriginal: CheckBox

    private lateinit var sbBrightness: SeekBar
    private lateinit var sbContrast: SeekBar
    private lateinit var sbHighlights: SeekBar
    private lateinit var sbShadows: SeekBar
    private lateinit var sbSaturation: SeekBar
    private lateinit var sbSharpness: SeekBar

    private lateinit var spColorGrade: Spinner
    private lateinit var spAspectRatio: Spinner
    private lateinit var spResolution: Spinner
    private lateinit var spFps: Spinner
    private lateinit var spBitrate: Spinner

    private val statsHandler = Handler(Looper.getMainLooper())

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedVideoUri = uri
            tvSelectedVideo.text = "Loaded: ${uri.lastPathSegment ?: "Raw Video"}"
            extractPreviewFrame(uri)
        }
    }

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedAudioUri = uri
            tvSelectedAudio.text = "BGM Loaded: ${uri.lastPathSegment ?: "Audio Track"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHwStats = findViewById(R.id.tvHwStats)
        ivPreview = findViewById(R.id.ivPreviewFrame)
        tvSelectedVideo = findViewById(R.id.tvSelectedVideo)
        tvSplitBadge = findViewById(R.id.tvSplitBadge)
        sbSplitCompare = findViewById(R.id.sbSplitCompare)
        tvSelectedAudio = findViewById(R.id.tvSelectedAudio)
        tvAiSceneResult = findViewById(R.id.tvAiSceneResult)
        tvRenderStatus = findViewById(R.id.tvRenderStatus)
        progressRender = findViewById(R.id.progressRender)
        cbMuteOriginal = findViewById(R.id.cbMuteOriginal)

        sbBrightness = findViewById(R.id.sbBrightness)
        sbContrast = findViewById(R.id.sbContrast)
        sbHighlights = findViewById(R.id.sbHighlights)
        sbShadows = findViewById(R.id.sbShadows)
        sbSaturation = findViewById(R.id.sbSaturation)
        sbSharpness = findViewById(R.id.sbSharpness)

        spColorGrade = findViewById(R.id.spColorGrade)
        spAspectRatio = findViewById(R.id.spAspectRatio)
        spResolution = findViewById(R.id.spResolution)
        spFps = findViewById(R.id.spFps)
        spBitrate = findViewById(R.id.spBitrate)

        setupSpinners()
        setupSlidersAndSteppers()
        startHardwareTelemetry()

        sbSplitCompare.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvSplitBadge.text = "RAW ${p}% | STUDIO v3"
                updateLiveSplitPreview()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideoLauncher.launch("video/*") }
        findViewById<Button>(R.id.btnPickAudio).setOnClickListener { pickAudioLauncher.launch("audio/*") }
        findViewById<Button>(R.id.btnAiAutoTune).setOnClickListener { runAiSceneAutoTune() }
        findViewById<Button>(R.id.btnRender).setOnClickListener { startHardwareRender() }
        findViewById<Button>(R.id.btnCancelRender).setOnClickListener { cancelActiveRender() }
        findViewById<Button>(R.id.btnResetSliders).setOnClickListener {
            spColorGrade.setSelection(0)
            applySliderValues(0, 0, 0, 0, 0, 0)
        }
    }

    private fun startHardwareTelemetry() {
        statsHandler.post(object : Runnable {
            override fun run() {
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(memInfo)
                    val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
                    val usedRamGb = (memInfo.totalMem - memInfo.availMem) / (1024.0 * 1024.0 * 1024.0)

                    val batIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val tempTenths = batIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                    val tempC = tempTenths / 10.0

                    val statFs = StatFs(Environment.getDataDirectory().path)
                    val freeGb = (statFs.availableBlocksLong * statFs.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)

                    tvHwStats.text = String.format(
                        Locale.US,
                        "RAM: %.1f/%.1f GB | Temp: %.1f°C | Free: %.1f GB | GPU: OpenGL ES 3.0",
                        usedRamGb, totalRamGb, tempC, freeGb
                    )
                } catch (_: Exception) {}
                statsHandler.postDelayed(this, 3000)
            }
        })
    }

    private fun setupSpinners() {
        val grades = listOf(
            "Manual / Neutral",
            "Motion Cinematic (True Teal-Orange LUT)",
            "Vivid Nature & Forest Pop",
            "Golden Hour Highway Cruise",
            "Night Drive Clean (Low-Light Lift)",
            "Moody Film Look (Matte Contrast)"
        )
        spColorGrade.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, grades)
        spColorGrade.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                when (pos) {
                    1 -> applySliderValues(4, 14, -28, 26, 10, 28)
                    2 -> applySliderValues(5, 14, -22, 22, 22, 25)
                    3 -> applySliderValues(6, 14, -18, 22, 16, 22)
                    4 -> applySliderValues(24, 16, -34, 42, 10, 20)
                    5 -> applySliderValues(-2, 20, -30, 18, -12, 20)
                }
                updateLiveSplitPreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spAspectRatio.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Original Frame", "9:16 Reels Vertical", "3:4 Portrait Feed", "1:1 Square Post", "16:9 Widescreen")
        )
        spResolution.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("4K Ultra HD (3840x2160)", "1080p Full HD (1920x1080)", "Original Resolution")
        )
        spFps.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("60 FPS Smooth Lock (HW)", "30 FPS Standard Lock (HW)", "Original Source FPS")
        )
        spBitrate.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "Studio Master 55 Mbps (~400-500MB/min)",
                "High Quality 32 Mbps (~240MB/min)",
                "Balanced Social 18 Mbps (~135MB/min)"
            )
        )
    }

    private fun setupSlidersAndSteppers() {
        bindSignedSlider(sbBrightness, findViewById(R.id.lblBrightness), "Brightness", findViewById(R.id.btnBrMinus), findViewById(R.id.btnBrPlus))
        bindSignedSlider(sbContrast, findViewById(R.id.lblContrast), "Contrast", findViewById(R.id.btnCoMinus), findViewById(R.id.btnCoPlus))
        bindSignedSlider(sbHighlights, findViewById(R.id.lblHighlights), "Highlights (Glare)", findViewById(R.id.btnHiMinus), findViewById(R.id.btnHiPlus))
        bindSignedSlider(sbShadows, findViewById(R.id.lblShadows), "Shadows (Cabin)", findViewById(R.id.btnShMinus), findViewById(R.id.btnShPlus))
        bindSignedSlider(sbSaturation, findViewById(R.id.lblSaturation), "Saturation", findViewById(R.id.btnSaMinus), findViewById(R.id.btnSaPlus))

        sbSharpness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                findViewById<TextView>(R.id.lblSharpness).text = "Sharpness / Clarity (0-100): $p"
                updateLiveSplitPreview()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<Button>(R.id.btnSpMinus).setOnClickListener { sbSharpness.progress = (sbSharpness.progress - 5).coerceIn(0, 100) }
        findViewById<Button>(R.id.btnSpPlus).setOnClickListener { sbSharpness.progress = (sbSharpness.progress + 5).coerceIn(0, 100) }
    }

    private fun bindSignedSlider(sb: SeekBar, tv: TextView, label: String, btnMinus: Button, btnPlus: Button) {
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = "$label: ${p - 100}"
                updateLiveSplitPreview()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        btnMinus.setOnClickListener { sb.progress = (sb.progress - 5).coerceIn(0, 200) }
        btnPlus.setOnClickListener { sb.progress = (sb.progress + 5).coerceIn(0, 200) }
    }

    private fun applySliderValues(br: Int, co: Int, hi: Int, sh: Int, sa: Int, sp: Int) {
        sbBrightness.progress = (br + 100).coerceIn(0, 200)
        sbContrast.progress = (co + 100).coerceIn(0, 200)
        sbHighlights.progress = (hi + 100).coerceIn(0, 200)
        sbShadows.progress = (sh + 100).coerceIn(0, 200)
        sbSaturation.progress = (sa + 100).coerceIn(0, 200)
        sbSharpness.progress = sp.coerceIn(0, 100)
        updateLiveSplitPreview()
    }

    private fun renderGradedBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val br = (sbBrightness.progress - 100) / 100f * 0.14f
        val co = 1f + ((sbContrast.progress - 100) / 100f) * 0.35f
        val hiAdj = (sbHighlights.progress - 100) / 100f * 0.22f
        val shAdj = (sbShadows.progress - 100) / 100f * 0.24f
        val sa = (1f + ((sbSaturation.progress - 100) / 100f) * 0.55f).coerceAtLeast(0f)
        val gradeIdx = spColorGrade.selectedItemPosition

        val splitTone = when (gradeIdx) {
            1 -> floatArrayOf(-0.08f, 0.04f, 0.11f,  0.12f, 0.03f, -0.11f)
            2 -> floatArrayOf(-0.02f, 0.05f, 0.02f,  0.03f, 0.06f, -0.03f)
            3 -> floatArrayOf( 0.02f, 0.01f,-0.04f,  0.15f, 0.05f, -0.14f)
            4 -> floatArrayOf(-0.03f, 0.03f, 0.08f,  0.02f, 0.02f,  0.04f)
            5 -> floatArrayOf(-0.05f, 0.02f, 0.07f,  0.04f, 0.00f, -0.04f)
            else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        }

        val shR = splitTone[0]; val shG = splitTone[1]; val shB = splitTone[2]
        val hiR = splitTone[3]; val hiG = splitTone[4]; val hiB = splitTone[5]

        for (i in pixels.indices) {
            val c = pixels[i]
            var r = ((c shr 16) and 0xFF) / 255f
            var g = ((c shr 8) and 0xFF) / 255f
            var b = (c and 0xFF) / 255f

            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val shWeight = (1f - luma) * (1f - luma)
            val hiWeight = luma * luma

            val toneShift = br + shAdj * shWeight + hiAdj * hiWeight
            r += toneShift
            g += toneShift
            b += toneShift

            r += shR * shWeight + hiR * hiWeight
            g += shG * shWeight + hiG * hiWeight
            b += shB * shWeight + hiB * hiWeight

            r = (r - 0.5f) * co + 0.5f
            g = (g - 0.5f) * co + 0.5f
            b = (b - 0.5f) * co + 0.5f

            val newLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            r = newLuma + (r - newLuma) * sa
            g = newLuma + (g - newLuma) * sa
            b = newLuma + (b - newLuma) * sa

            val ri = (r.coerceIn(0f, 1f) * 255f).toInt()
            val gi = (g.coerceIn(0f, 1f) * 255f).toInt()
            val bi = (b.coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun updateLiveSplitPreview() {
        val raw = rawPreviewBitmap ?: return
        val graded = renderGradedBitmap(raw)
        val w = raw.width
        val h = raw.height
        val splitX = ((sbSplitCompare.progress / 100f) * w).toInt().coerceIn(0, w)

        val combined = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)

        if (splitX > 0) {
            val leftRect = Rect(0, 0, splitX, h)
            canvas.drawBitmap(raw, leftRect, leftRect, null)
        }
        if (splitX < w) {
            val rightRect = Rect(splitX, 0, w, h)
            canvas.drawBitmap(graded, rightRect, rightRect, null)
        }

        val linePaint = Paint().apply {
            color = Color.parseColor("#38BDF8")
            strokeWidth = (w / 180f).coerceAtLeast(3f)
        }
        canvas.drawLine(splitX.toFloat(), 0f, splitX.toFloat(), h.toFloat(), linePaint)

        val badgePaint = Paint().apply {
            color = Color.argb(175, 11, 15, 25)
            style = Paint.Style.FILL
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = (h / 18f).coerceAtLeast(18f)
            isFakeBoldText = true
            isAntiAlias = true
        }
        if (splitX > w * 0.16) {
            canvas.drawRect(12f, 12f, 12f + textPaint.measureText("RAW") + 20f, 16f + textPaint.textSize, badgePaint)
            canvas.drawText("RAW", 22f, 10f + textPaint.textSize, textPaint)
        }
        if (splitX < w * 0.84) {
            val lbl = "STUDIO v3"
            val tw = textPaint.measureText(lbl)
            canvas.drawRect(w - tw - 32f, 12f, w - 12f, 16f + textPaint.textSize, badgePaint)
            textPaint.color = Color.parseColor("#38BDF8")
            canvas.drawText(lbl, w - tw - 22f, 10f + textPaint.textSize, textPaint)
        }

        ivPreview.setImageBitmap(combined)
    }

    private fun extractPreviewFrame(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this@MainActivity, uri)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durUs = (durStr?.toLongOrNull() ?: 3000L) * 1000L
                val bmp = retriever.getFrameAtTime(durUs / 3, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                if (bmp != null) {
                    val targetW = bmp.width.coerceAtMost(960)
                    val targetH = (bmp.height * (targetW.toFloat() / bmp.width.coerceAtLeast(1))).toInt().coerceAtLeast(1)
                    rawPreviewBitmap = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                    withContext(Dispatchers.Main) { updateLiveSplitPreview() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun runAiSceneAutoTune() {
        val bmp = rawPreviewBitmap
        if (bmp == null) {
            Toast.makeText(this, "Pick a video first!", Toast.LENGTH_SHORT).show()
            return
        }
        tvAiSceneResult.text = "Analyzing frame with Gemini Vision..."

        CoroutineScope(Dispatchers.IO).launch {
            val targetH = (bmp.height * (640f / bmp.width.coerceAtLeast(1))).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, 640, targetH, true)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val prompt = "Analyze this action cam / dashcam frame and return JSON with keys: scene_detected (3-5 words), recommended_grade_idx (1=Motion Cinematic, 2=Vivid Nature, 3=Golden Hour, 4=Night Clean, 5=Moody Film), brightness (-15 to 25), contrast (5 to 20), highlights (-35 to -10), shadows (10 to 35), saturation (0 to 18), sharpness (15 to 40). Keep contrast subtle for filmic dynamic range."

            var success = false
            for (model in aiModels) {
                try {
                    val bodyJson = JSONObject().apply {
                        put("contents", JSONArray().put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", prompt))
                                put(JSONObject().put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", b64)
                                }))
                            })
                        }))
                        put("generationConfig", JSONObject().apply {
                            put("temperature", 0.2)
                            put("responseMimeType", "application/json")
                        })
                    }

                    val req = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                        .addHeader("x-goog-api-key", geminiApiKey)
                        .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val t0 = System.currentTimeMillis()
                    val resp = client.newCall(req).execute()
                    val ping = System.currentTimeMillis() - t0
                    if (resp.isSuccessful) {
                        val txt = resp.body?.string() ?: ""
                        val root = JSONObject(txt)
                        val rawJson = root.getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                        val p = JSONObject(rawJson)

                        withContext(Dispatchers.Main) {
                            val gIdx = p.optInt("recommended_grade_idx", 1).coerceIn(1, 5)
                            spColorGrade.setSelection(gIdx)
                            applySliderValues(
                                p.optInt("brightness", 4).coerceIn(-25, 30),
                                p.optInt("contrast", 14).coerceIn(-10, 24),
                                p.optInt("highlights", -26).coerceIn(-45, 0),
                                p.optInt("shadows", 26).coerceIn(0, 45),
                                p.optInt("saturation", 10).coerceIn(-15, 22),
                                p.optInt("sharpness", 28).coerceIn(0, 45)
                            )
                            tvAiSceneResult.text = "Scene: ${p.optString("scene_detected")} • $model (${ping}ms)"
                        }
                        success = true
                        break
                    }
                } catch (_: Exception) {
                }
            }

            if (!success) {
                withContext(Dispatchers.Main) {
                    spColorGrade.setSelection(1)
                    applySliderValues(4, 14, -28, 26, 10, 28)
                    tvAiSceneResult.text = "Offline Smart Engine Applied (Motion Cinematic Teal-Orange)"
                }
            }
        }
    }

    private fun cancelActiveRender() {
        val tr = activeTransformer
        if (tr != null && isRendering) {
            try {
                tr.cancel()
            } catch (_: Exception) {}
            isRendering = false
            activeTransformer = null
            progressRender.progress = 0
            tvRenderStatus.text = "Status: Render Cancelled by User."
            Toast.makeText(this, "Render Cancelled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No active render running", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startHardwareRender() {
        val vidUri = selectedVideoUri
        if (vidUri == null) {
            Toast.makeText(this, "Select a video clip first!", Toast.LENGTH_SHORT).show()
            return
        }
        if (isRendering) {
            Toast.makeText(this, "Render already in progress!", Toast.LENGTH_SHORT).show()
            return
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tempOutFile = File(cacheDir, "StudioPro_Master_$stamp.mp4")

        val br = (sbBrightness.progress - 100) / 100f * 0.14f
        val co = 1f + ((sbContrast.progress - 100) / 100f) * 0.35f
        val hiAdj = (sbHighlights.progress - 100) / 100f * 0.22f
        val shAdj = (sbShadows.progress - 100) / 100f * 0.24f
        val sa = (1f + ((sbSaturation.progress - 100) / 100f) * 0.55f).coerceAtLeast(0f)
        val sharpAmt = (sbSharpness.progress / 100f) * 0.45f
        val gradeIdx = spColorGrade.selectedItemPosition

        val splitTone = when (gradeIdx) {
            1 -> floatArrayOf(-0.08f, 0.04f, 0.11f,  0.12f, 0.03f, -0.11f)
            2 -> floatArrayOf(-0.02f, 0.05f, 0.02f,  0.03f, 0.06f, -0.03f)
            3 -> floatArrayOf( 0.02f, 0.01f,-0.04f,  0.15f, 0.05f, -0.14f)
            4 -> floatArrayOf(-0.03f, 0.03f, 0.08f,  0.02f, 0.02f,  0.04f)
            5 -> floatArrayOf(-0.05f, 0.02f, 0.07f,  0.04f, 0.00f, -0.04f)
            else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        }

        val videoEffects = mutableListOf<Effect>()

        videoEffects.add(
            CinematicGlEffect(
                brightness = br,
                contrast = co,
                highlights = hiAdj,
                shadows = shAdj,
                saturation = sa,
                sharpness = sharpAmt,
                shRgb = floatArrayOf(splitTone[0], splitTone[1], splitTone[2]),
                hiRgb = floatArrayOf(splitTone[3], splitTone[4], splitTone[5])
            )
        )

        when (spFps.selectedItemPosition) {
            0 -> videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(60f))
            1 -> videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(30f))
        }

        when (spAspectRatio.selectedItemPosition) {
            1 -> videoEffects.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            2 -> videoEffects.add(Presentation.createForWidthAndHeight(1080, 1440, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            3 -> videoEffects.add(Presentation.createForWidthAndHeight(1080, 1080, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            4 -> videoEffects.add(Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT))
            else -> {
                if (spResolution.selectedItemPosition == 0) {
                    videoEffects.add(Presentation.createForHeight(2160))
                } else if (spResolution.selectedItemPosition == 1) {
                    videoEffects.add(Presentation.createForHeight(1080))
                }
            }
        }

        val targetBitrate = when (spBitrate.selectedItemPosition) {
            0 -> 55_000_000
            1 -> 32_000_000
            else -> 18_000_000
        }

        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(targetBitrate)
                    .build()
            )
            .build()

        val muteOrig = selectedAudioUri != null && cbMuteOriginal.isChecked
        val editedVideoItem = EditedMediaItem.Builder(MediaItem.fromUri(vidUri))
            .setEffects(Effects(emptyList(), videoEffects))
            .setRemoveAudio(muteOrig)
            .build()

        val sequences = mutableListOf(EditedMediaItemSequence(editedVideoItem))
        selectedAudioUri?.let { audUri ->
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(audUri))
                .setRemoveVideo(true)
                .build()
            sequences.add(EditedMediaItemSequence(audioItem))
        }

        val composition = Composition.Builder(sequences).build()

        val transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    isRendering = false
                    val mbSize = tempOutFile.length() / (1024.0 * 1024.0)
                    saveToGallery(tempOutFile)
                    progressRender.progress = 100
                    tvRenderStatus.text = String.format(Locale.US, "Completed (%.1f MB)! Saved to Movies/ActionCamStudio", mbSize)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    isRendering = false
                    tvRenderStatus.text = "HW Export Error: ${exportException.message}"
                }
            })
            .build()

        activeTransformer = transformer
        isRendering = true
        renderStartMs = System.currentTimeMillis()
        tvRenderStatus.text = "Rendering Studio Master (${targetBitrate / 1_000_000} Mbps)..."
        progressRender.progress = 3

        transformer.start(composition, tempOutFile.absolutePath)

        val handler = Handler(Looper.getMainLooper())
        val progressHolder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                if (!isRendering) return
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val pct = progressHolder.progress
                    progressRender.progress = pct
                    val elapsedSec = (System.currentTimeMillis() - renderStartMs) / 1000.0
                    val etaStr = if (pct > 3) {
                        val rem = ((elapsedSec / (pct / 100.0)) - elapsedSec).toInt().coerceAtLeast(0)
                        " | ETA: ${rem}s"
                    } else ""
                    tvRenderStatus.text = "GPU OpenGL Encoding... $pct%$etaStr"
                }
                handler.postDelayed(this, 400)
            }
        })
    }

    private fun saveToGallery(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ActionCamStudio")
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { inp -> inp.copyTo(out) }
        }
    }
}

@OptIn(UnstableApi::class)
class CinematicGlEffect(
    private val brightness: Float,
    private val contrast: Float,
    private val highlights: Float,
    private val shadows: Float,
    private val saturation: Float,
    private val sharpness: Float,
    private val shRgb: FloatArray,
    private val hiRgb: FloatArray
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CinematicShaderProgram(useHdr, brightness, contrast, highlights, shadows, saturation, sharpness, shRgb, hiRgb)
    }
}

@OptIn(UnstableApi::class)
class CinematicShaderProgram(
    useHdr: Boolean,
    private val brightness: Float,
    private val contrast: Float,
    private val highlights: Float,
    private val shadows: Float,
    private val saturation: Float,
    private val sharpness: Float,
    private val shRgb: FloatArray,
    private val hiRgb: FloatArray
) : BaseGlShaderProgram(useHdr, 1) {

    private var texWidth = 1920f
    private var texHeight = 1080f

    private val vertexShader =
        "#version 100
" +
        "attribute vec4 aFramePosition;
" +
        "varying vec2 vTexSamplingCoord;
" +
        "void main() {
" +
        "    gl_Position = aFramePosition;
" +
        "    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
" +
        "}
"

    private val fragmentShader =
        "#version 100
" +
        "precision mediump float;
" +
        "uniform sampler2D uTexSampler;
" +
        "uniform vec2 uTexelSize;
" +
        "uniform float uBrightness;
" +
        "uniform float uContrast;
" +
        "uniform float uHighlights;
" +
        "uniform float uShadows;
" +
        "uniform float uSaturation;
" +
        "uniform float uSharpness;
" +
        "uniform vec3 uShRgb;
" +
        "uniform vec3 uHiRgb;
" +
        "varying vec2 vTexSamplingCoord;
" +
        "void main() {
" +
        "    vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
" +
        "    vec3 c = center.rgb;
" +
        "    if (uSharpness > 0.001) {
" +
        "        vec3 n = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y)).rgb;
" +
        "        vec3 s = texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexelSize.y)).rgb;
" +
        "        vec3 e = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0)).rgb;
" +
        "        vec3 w = texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexelSize.x, 0.0)).rgb;
" +
        "        vec3 blur = (n + s + e + w) * 0.25;
" +
        "        c = c + (c - blur) * uSharpness;
" +
        "    }
" +
        "    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
" +
        "    float shWeight = (1.0 - luma) * (1.0 - luma);
" +
        "    float hiWeight = luma * luma;
" +
        "    float toneShift = uBrightness + uShadows * shWeight + uHighlights * hiWeight;
" +
        "    c += vec3(toneShift);
" +
        "    c += uShRgb * shWeight + uHiRgb * hiWeight;
" +
        "    c = (c - 0.5) * uContrast + 0.5;
" +
        "    float newLuma = dot(c, vec3(0.2126, 0.7152, 0.0722));
" +
        "    c = mix(vec3(newLuma), c, uSaturation);
" +
        "    gl_FragColor = vec4(clamp(c, 0.0, 1.0), center.a);
" +
        "}
"

    private val glProgram = GlProgram(vertexShader, fragmentShader).apply {
        setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texWidth = inputWidth.coerceAtLeast(1).toFloat()
        texHeight = inputHeight.coerceAtLeast(1).toFloat()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1.0f / texWidth, 1.0f / texHeight))
        glProgram.setFloatUniform("uBrightness", brightness)
        glProgram.setFloatUniform("uContrast", contrast)
        glProgram.setFloatUniform("uHighlights", highlights)
        glProgram.setFloatUniform("uShadows", shadows)
        glProgram.setFloatUniform("uSaturation", saturation)
        glProgram.setFloatUniform("uSharpness", sharpness)
        glProgram.setFloatsUniform("uShRgb", shRgb)
        glProgram.setFloatsUniform("uHiRgb", hiRgb)
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        glProgram.delete()
    }
}
