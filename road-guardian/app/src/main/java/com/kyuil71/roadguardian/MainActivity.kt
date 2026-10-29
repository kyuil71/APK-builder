package com.kyuil71.roadguardian

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kyuil71.roadguardian.core.DEG
import com.kyuil71.roadguardian.core.Risk
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/** 연구·시험용. 인터넷 권한 없음. 모든 인식·판단은 폰 안에서. */
class MainActivity : AppCompatActivity(), SensorEventListener {
    private val engine = Engine()
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    // 화면
    private lateinit var previewView: PreviewView
    private lateinit var videoView: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var bird: BirdView
    private lateinit var riskCard: TextView
    private lateinit var msgText: TextView
    private lateinit var speedText: TextView
    private lateinit var chipText: TextView
    private lateinit var statusText: TextView
    private lateinit var lensBtn: TextView
    private lateinit var soundBtn: TextView
    private lateinit var videoBtn: TextView
    private lateinit var alarmView: LinearLayout
    private lateinit var alarmDir: TextView
    private lateinit var alarmTitle: TextView
    private lateinit var alarmSub: TextView

    // 카메라 · 렌즈
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    @Volatile private var hwZoom = 1.0
    @Volatile private var baseHfov = 72.0
    @Volatile private var lens = "wide"
    @Volatile private var lensBusyUntil = 0L
    @Volatile private var resetPending = false
    private var lensHi = 0.0; private var lensLo = 0.0

    // 센서 · GPS
    private lateinit var sm: SensorManager
    private var gx = 0.0; private var gy = 0.0; private var gz = 0.0; private var gInit = false
    @Volatile private var sensorPitch = 5 * DEG
    @Volatile private var sensorOk = false
    @Volatile private var yawRate = 0.0
    @Volatile private var yawSlow = 0.0
    @Volatile private var gpsSpeed: Double? = null
    @Volatile private var gpsAcc = 0f
    @Volatile private var gpsTime = 0L
    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    // 상태
    @Volatile private var lastFrameMs = 0L
    @Volatile private var lastSnap: Snapshot? = null
    @Volatile private var cameraRunning = false
    @Volatile private var videoRunning = false
    private var frameCount = 0; private var fps = 0.0; private var fpsT = 0L
    @Volatile private var videoProgress = ""

    // 소리
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsOk = false
    private var vib: Vibrator? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else showState("카메라 권한이 없어 영상을 분석할 수 없습니다")
        startGps()
    }
    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) startVideo(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        vib = getSystemService(Vibrator::class.java)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) { null }
        tts = TextToSpeech(this) { st -> if (st == TextToSpeech.SUCCESS) { tts?.language = Locale.KOREAN; ttsOk = true } }
        buildUi()
        // 모델은 분석 스레드에서 미리 준비
        exec.execute {
            try { engine.detector = YoloDetector(applicationContext) }
            catch (e: Throwable) { engine.initError = e.message ?: e.javaClass.simpleName }
        }
        ui.postDelayed(tick, 300)
        AlertDialog.Builder(this)
            .setTitle("AI Road Guardian · 연구·시험용")
            .setMessage("이 앱은 실제 도로에서 검증된 충돌 방지 장치가 아닙니다.\n\n" +
                "• 단일 카메라의 거리는 추정값이라 범위로만 표시합니다.\n" +
                "• '징후 없음(초록)'도 안전을 보장하지 않습니다. 인식이 불가능하면 회색 '판단 불가'로 표시합니다.\n" +
                "• 먼저 '영상 시험'으로 녹화된 주행 영상에서, 또는 통제된 환경에서 시험하세요.\n" +
                "• 운전 중에는 화면을 조작하거나 주시하지 마세요. 차량의 안전장치와 운전자의 판단을 대신하지 않습니다.")
            .setCancelable(false)
            .setPositiveButton("시험용으로 사용") { _, _ -> Settings.setAgreed(); requestPerms() }
            .setNegativeButton("종료") { _, _ -> finish() }
            .show()
    }

    /* ───────────── 화면 구성 ───────────── */
    private fun dp(v: Int) = (resources.displayMetrics.density * v).roundToInt()
    private fun tv(text: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private fun rounded(color: Int, r: Int = 12, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun button(label: String, onClick: () -> Unit) = tv(label, 16f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(6), dp(12), dp(6), dp(12))
        background = rounded(Color.rgb(20, 24, 29), 12, Color.rgb(38, 44, 52))
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(11, 13, 16)) }
        // 세로 화면: 위험 단계 → 카메라 → 속도·상태 → 지도 + 버튼
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        // 위험 단계 + 한 줄 설명
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        riskCard = tv("● 판단 불가", 24f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(Color.rgb(90, 98, 110), 12)
        }
        head.addView(riskCard, LinearLayout.LayoutParams(-2, -2))
        head.addView(tv("AI ROAD GUARDIAN\n시험용", 13f, Color.rgb(139, 150, 163), true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        main.addView(head)
        msgText = tv("AI 모델 준비 중", 16f, Color.rgb(232, 237, 242)).apply { setPadding(dp(2), dp(6), dp(2), dp(6)); maxLines = 2 }
        main.addView(msgText)

        // 카메라 영역 (세로 9:16 영상)
        val camArea = FrameLayout(this).apply { background = rounded(Color.BLACK, 12) ; clipToOutline = true }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        videoView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        overlay = OverlayView(this)
        camArea.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        camArea.addView(videoView, FrameLayout.LayoutParams(-1, -1))
        camArea.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        statusText = tv("준비 중", 14f, Color.rgb(219, 227, 234)).apply {
            background = rounded(Color.argb(170, 8, 10, 13), 9); setPadding(dp(9), dp(5), dp(9), dp(5))
        }
        camArea.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        main.addView(camArea, LinearLayout.LayoutParams(-1, 0, 0.60f))

        // 속도 + 상태
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(6), dp(2), dp(4)) }
        speedText = tv("-- km/h", 28f, Color.WHITE, true)
        row.addView(speedText, LinearLayout.LayoutParams(-2, -2))
        chipText = tv("", 14f, Color.rgb(170, 180, 192)).apply { setPadding(dp(12), 0, 0, 0) }
        row.addView(chipText, LinearLayout.LayoutParams(0, -2, 1f))
        main.addView(row)

        // 위에서 본 지도 + 버튼 4개
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bird = BirdView(this).apply { background = rounded(Color.rgb(14, 17, 22), 12, Color.rgb(38, 44, 52)) }
        bottom.addView(bird, LinearLayout.LayoutParams(0, -1, 0.58f).apply { rightMargin = dp(6) })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lensBtn = button(lensLabel()) { cycleLens() }
        soundBtn = button(if (Settings.bool("sound")) "경고음 ON" else "경고음 OFF") { toggleSound() }
        videoBtn = button("영상 시험") { if (videoRunning) stopVideo() else videoPicker.launch("video/*") }
        val setBtn = button("설정") { openSettings() }
        for (b in listOf(lensBtn, soundBtn, videoBtn, setBtn)) btns.addView(b, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, dp(3), 0, dp(3)) })
        bottom.addView(btns, LinearLayout.LayoutParams(0, -1, 0.42f))
        main.addView(bottom, LinearLayout.LayoutParams(-1, 0, 0.40f))
        main.addView(tv("연구·시험 전용 · 충돌 방지 장치 아님 · 운전 중 조작 금지", 13f, Color.rgb(199, 154, 58)).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) },
            LinearLayout.LayoutParams(-1, -2))

        // 전체 화면 경고
        alarmView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.argb(235, 225, 18, 18)); visibility = View.GONE
            isClickable = false
        }
        alarmDir = tv("▲", 96f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        alarmTitle = tv("충돌 위험", 44f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0) }
        alarmSub = tv("", 24f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), 0) }
        alarmView.addView(alarmDir); alarmView.addView(alarmTitle); alarmView.addView(alarmSub)
        root.addView(alarmView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun lensLabel() = when (Settings.lensMode) { "wide" -> "렌즈: 광각"; "tele" -> "렌즈: 망원"; else -> "렌즈: 자동" }
    private fun cycleLens() {
        val next = when (Settings.lensMode) { "auto" -> "wide"; "wide" -> "tele"; else -> "auto" }
        Settings.saveLens(next); lensBtn.text = lensLabel()
        if (next != "auto") setLens(next)
    }
    private fun toggleSound() {
        val on = !Settings.bool("sound"); Settings.setBool("sound", on)
        soundBtn.text = if (on) "경고음 ON" else "경고음 OFF"
    }

    /* ───────────── 설정 ───────────── */
    private fun openSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        for (s in Settings.nums) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val name = tv(s.label, 16f)
            val value = tv("", 16f, Color.rgb(56, 189, 248), true)
            fun show(v: Double) { value.text = if (s.zeroLabel != null && v == 0.0) s.zeroLabel else (if (s.step < 1) "%.${if (s.step < 0.1) 2 else 1}f".format(v) else v.roundToInt().toString()) + " " + s.unit }
            head.addView(name, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(value)
            val steps = ((s.max - s.min) / s.step).roundToInt()
            val bar = SeekBar(this).apply {
                max = steps; progress = ((Settings.num(s.key) - s.min) / s.step).roundToInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { val v = s.min + p * s.step; show(v); if (fromUser) Settings.setNum(s.key, v) }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { if (s.key == "teleZoom" && lens == "tele") applyZoom() }
                })
            }
            show(Settings.num(s.key))
            box.addView(head, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            box.addView(bar)
            if (s.hint.isNotEmpty()) box.addView(tv(s.hint, 14f, Color.rgb(139, 150, 163)))
        }
        for (b in Settings.bools) {
            val sw = Switch(this).apply {
                text = b.label; textSize = 16f; isChecked = Settings.bool(b.key)
                setOnCheckedChangeListener { _, c -> Settings.setBool(b.key, c); if (b.key == "sound") soundBtn.text = if (c) "경고음 ON" else "경고음 OFF" }
            }
            box.addView(sw, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        AlertDialog.Builder(this).setTitle("설정").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("닫기", null).show()
    }

    /* ───────────── 권한 · 카메라 ───────────── */
    private fun requestPerms() {
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            try {
                val pr = fut.get(); provider = pr
                val sel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val preview = Preview.Builder().setResolutionSelector(sel).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setResolutionSelector(sel)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
                pr.unbindAll()
                val cam = pr.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                cam.cameraInfo.zoomState.observe(this) { z -> hwZoom = z.zoomRatio.toDouble() }
                readBaseFov(cam)
                cameraRunning = true
                lens = if (Settings.lensMode == "tele") "tele" else "wide"
                applyZoom()
            } catch (e: Exception) { showState("카메라 시작 실패: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 1배 렌즈의 실제 화각(센서 긴 변 기준): 초점거리와 센서 크기로 계산 (보정 없이 거리 계산의 기준) */
    private fun readBaseFov(cam: Camera) {
        try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val fl = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val ss = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (fl != null && fl.isNotEmpty() && ss != null) {
                val h = 2 * atan(ss.width / (2.0 * fl[0])) / DEG
                if (h in 45.0..100.0) baseHfov = h
            }
        } catch (e: Throwable) { }
    }

    private fun hfovNow(): Double {
        val base = Settings.num("fov").let { if (it > 0) it else baseHfov }
        return 2 * atan(tan(base * DEG / 2) / max(1.0, hwZoom)) / DEG
    }

    private fun setLens(l: String) {
        if (l == lens) return
        lens = l; lensHi = 0.0; lensLo = 0.0
        applyZoom()
        toast(if (l == "tele") "망원 · 원거리 감시" else "광각 1× · 주변 감시")
    }
    private fun applyZoom() {
        val cam = camera ?: return
        val target = if (lens == "tele") Settings.num("teleZoom") else 1.0
        val maxZ = cam.cameraInfo.zoomState.value?.maxZoomRatio?.toDouble() ?: 1.0
        lensBusyUntil = SystemClock.elapsedRealtime() + 700; resetPending = true
        try { cam.cameraControl.setZoomRatio(target.coerceAtMost(maxZ).toFloat()) } catch (e: Exception) { }
    }
    private fun toast(t: String) { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show() }

    /* ───────────── 프레임 처리 ───────────── */
    private fun analyze(proxy: ImageProxy) {
        try {
            if (videoRunning) return
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val bmp = if (rot != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else raw
            runFrame(bmp, proxy.imageInfo.timestamp / 1e9, true)
            if (bmp !== raw) bmp.recycle()
            raw.recycle()
        } catch (e: Throwable) {
            ui.post { showState("프레임 처리 오류: ${e.message ?: e.javaClass.simpleName}") }
        } finally { proxy.close() }
    }

    private fun egoSpeed(live: Boolean): Double? {
        val t = Settings.num("testSpd"); if (t > 0) return t / 3.6
        if (!live) { val v = Settings.num("videoKmh"); return if (v > 0) v / 3.6 else null }
        val s = gpsSpeed
        return if (s != null && SystemClock.elapsedRealtime() - gpsTime < 4000 && gpsAcc <= 35f) s else null
    }

    private fun runFrame(bmp: Bitmap, tSec: Double, live: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (resetPending) { engine.reset(); resetPending = false }
        val tele = lens == "tele" && live
        val snap = engine.process(
            bmp, tSec, now,
            if (live) hfovNow() else Settings.num("videoFov"),
            if (live) sensorPitch else Settings.num("videoPitch") * DEG,
            if (live) sensorOk else true,
            if (live) yawRate else 0.0, if (live) yawSlow else 0.0,
            egoSpeed(live), tele, tele && hwZoom < 1.5, live && now < lensBusyUntil)
        lastFrameMs = now; lastSnap = snap
        frameCount++
        if (now - fpsT > 1000) { fps = frameCount * 1000.0 / (now - fpsT); frameCount = 0; fpsT = now }
        engine.lastAlert?.let { a -> playAlert(a) }
        ui.post { render(snap) }
    }

    /* ───────────── 소리 · 진동 ───────────── */
    private fun playAlert(a: com.kyuil71.roadguardian.core.AlertOut) {
        if (Settings.bool("sound")) when (a.beep) {
            1 -> tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            2 -> tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            3 -> tone?.startTone(ToneGenerator.TONE_PROP_ACK, 220)
        }
        if (a.vibrate && Settings.bool("vibe")) try { vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 60, 150), -1)) } catch (e: Exception) { }
        val sp = a.speech
        if (sp != null && ttsOk) {
            val allowed = if (a.speechPri >= 2) Settings.bool("voice") else Settings.bool("infoVoice")
            if (allowed) tts?.speak(sp, TextToSpeech.QUEUE_FLUSH, null, "rg")
        }
    }

    /* ───────────── 화면 갱신 ───────────── */
    private fun riskColor(r: Risk) = when (r) {
        Risk.UNKNOWN -> Color.rgb(96, 104, 116); Risk.LOW -> Color.rgb(22, 128, 74)
        Risk.CAUTION -> Color.rgb(196, 140, 8); Risk.HIGH -> Color.rgb(220, 38, 38)
    }

    private fun render(s: Snapshot) {
        overlay.show(s); bird.show(s)
        riskCard.text = "● ${s.risk.label}"
        riskCard.background = rounded(riskColor(s.risk), 12)
        msgText.text = s.msg
        alarmView.visibility = if (s.alarmOn) View.VISIBLE else View.GONE
        if (s.alarmOn) { alarmDir.text = s.alarmDir; alarmTitle.text = s.alarmTitle; alarmSub.text = s.alarmSub }
        val laneTxt = when (s.laneBasis) { 2 -> "차선 ✓"; 1 -> "차선 한쪽"; else -> if (Settings.bool("laneOn")) "차선 미인식" else "차선 끔" }
        val gpsTxt = when {
            Settings.num("testSpd") > 0 -> "테스트 속도"
            videoRunning -> "영상 속도 설정값"
            s.ego != null -> "GPS ±${gpsAcc.roundToInt()}m"
            else -> "GPS 신호 불확실"
        }
        val lensTxt = if (videoRunning) "녹화 영상" else if (lens == "tele") (if (hwZoom > 1.4) "망원 %.1f×".format(hwZoom) else "중앙 확대") else "광각 1×"
        speedText.text = if (s.ego == null) "-- km/h" else "${(s.ego * 3.6).roundToInt()} km/h"
        val cabinTxt = if (s.cabinLearned) "가림 학습됨" else "가림 보닛 계산"
        chipText.text = "$laneTxt · $gpsTxt\n$lensTxt · $cabinTxt" + (if (s.cabinRejected > 0) " (내부 ${s.cabinRejected}개 제외)" else "")
        statusText.text = (if (videoRunning) "$videoProgress · " else "") +
            "AI ${s.accel} ${s.procMs}ms · %.0ffps · 감지 ${s.detCount} · 기울기 %.1f°".format(fps, s.pitchDeg) +
            (if (kotlin.math.abs(s.autoDeg) > 0.05) " (자동 %+.1f°)".format(s.autoDeg) else "")
    }

    private fun showState(m: String) {
        riskCard.text = "● 판단 불가"; riskCard.background = rounded(riskColor(Risk.UNKNOWN), 12); msgText.text = m
        alarmView.visibility = View.GONE
    }

    /** 0.3초마다: 응답 감시(멈추면 판단 불가) · 렌즈 자동 전환 */
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if ((cameraRunning || videoRunning) && lastFrameMs > 0 && now - lastFrameMs > 1200) {
                showState("카메라/AI 응답 없음 (%.1f초) · 판단 불가".format((now - lastFrameMs) / 1000.0))
                overlay.show(null)
            }
            if (cameraRunning && !videoRunning && Settings.lensMode == "auto" && now > lensBusyUntil && lastSnap?.alarmOn != true) {
                val kmh = (egoSpeed(true) ?: 0.0) * 3.6
                val nearVru = lastSnap?.nearVru == true
                lensHi = if (lens == "wide" && kmh >= Settings.num("teleKmh") && !nearVru) lensHi + 0.3 else 0.0
                lensLo = if (lens == "tele" && kmh < Settings.num("teleKmh") - 10) lensLo + 0.3 else 0.0
                if (lensHi >= 3) setLens("tele") else if (lensLo >= 3) setLens("wide")
            }
            ui.postDelayed(this, 300)
        }
    }

    /* ───────────── 녹화 영상 시험 ───────────── */
    private fun startVideo(uri: Uri) {
        videoRunning = true; videoBtn.text = "영상 중지"
        provider?.unbindAll(); cameraRunning = false
        previewView.visibility = View.GONE; videoView.visibility = View.VISIBLE
        resetPending = true; lastFrameMs = SystemClock.elapsedRealtime()
        Thread {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(this, uri)
                val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var tMs = 0L
                val wall0 = SystemClock.elapsedRealtime()
                while (videoRunning && tMs < dur) {
                    val us = tMs * 1000
                    val b0 = (if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, 1280, 1280)
                              else mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)) ?: break
                    val b = if (b0.config != Bitmap.Config.ARGB_8888) b0.copy(Bitmap.Config.ARGB_8888, false) else b0
                    videoProgress = "영상 %d:%02d / %d:%02d".format(tMs / 60000, tMs / 1000 % 60, dur / 60000, dur / 1000 % 60)
                    ui.post { videoView.setImageBitmap(b) }
                    val tNow = tMs
                    exec.submit(Runnable { runFrame(b, tNow / 1000.0, false) }).get()
                    tMs += 100     // 0.1초 간격 프레임
                    val ahead = (wall0 + tMs) - SystemClock.elapsedRealtime()
                    if (ahead > 0) Thread.sleep(ahead)      // 실제 속도보다 빠르지 않게
                }
            } catch (e: Throwable) { ui.post { showState("영상 열기 실패: ${e.message}") } }
            finally { try { mmr.release() } catch (e: Throwable) { } }
            ui.post { stopVideo() }
        }.start()
    }

    private fun stopVideo() {
        if (!videoRunning && previewView.visibility == View.VISIBLE) return
        videoRunning = false; videoBtn.text = "영상 시험"
        videoView.visibility = View.GONE; previewView.visibility = View.VISIBLE
        resetPending = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    /* ───────────── 센서 ───────────── */
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val k = if (e.sensor.type == Sensor.TYPE_GRAVITY) 0.05 else 0.02     // 차의 가감속이 섞이지 않게 천천히
                if (!gInit) { gx = e.values[0].toDouble(); gy = e.values[1].toDouble(); gz = e.values[2].toDouble(); gInit = true }
                else { gx += (e.values[0] - gx) * k; gy += (e.values[1] - gy) * k; gz += (e.values[2] - gz) * k }
                val n = sqrt(gx * gx + gy * gy + gz * gz)
                if (n > 5) { sensorPitch = asin((gz / n).coerceIn(-1.0, 1.0)); sensorOk = true }   // 후면 카메라 = 기기 -z
            }
            Sensor.TYPE_GYROSCOPE -> if (gInit) {
                val n = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6)
                val w = (e.values[0] * gx + e.values[1] * gy + e.values[2] * gz) / n    // 수직축 회전 (+ = 좌회전)
                yawRate = yawRate * 0.7 + w * 0.3
                yawSlow = yawSlow * 0.95 + w * 0.05
            }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (l.hasSpeed()) { gpsSpeed = l.speed.toDouble(); gpsAcc = l.accuracy; gpsTime = SystemClock.elapsedRealtime() }
        }
        @Deprecated("") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }
    private fun startGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, gpsListener, Looper.getMainLooper()) } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        (sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        startGps()
    }
    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        try { lm.removeUpdates(gpsListener) } catch (e: Exception) { }
        tts?.stop()
    }
    override fun onDestroy() {
        ui.removeCallbacks(tick)
        videoRunning = false
        exec.execute { try { engine.detector?.close() } catch (e: Throwable) { } }
        exec.shutdown()
        tts?.shutdown(); tone?.release()
        super.onDestroy()
    }
}
