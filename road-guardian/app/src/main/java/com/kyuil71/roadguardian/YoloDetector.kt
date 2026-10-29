package com.kyuil71.roadguardian

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.kyuil71.roadguardian.core.Det
import com.kyuil71.roadguardian.core.YoloPost
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * 온디바이스 YOLOv8n (ONNX Runtime). 인터넷 없이 APK 안의 모델만 사용.
 * 가속: XNNPACK(ARM CPU 최적화) → 실패 시 기본 CPU.
 */
class YoloDetector(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    val accel: String
    private val side = 640
    private val canvasBmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBmp)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rgb = IntArray(side * side)
    private val chw = FloatArray(3 * side * side)

    init {
        val bytes = context.assets.open("yolov8n.onnx").use { it.readBytes() }
        var s: OrtSession? = null; var a = "CPU"
        try {
            val o = OrtSession.SessionOptions()
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            o.setIntraOpNumThreads(1)
            o.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            s = env.createSession(bytes, o); a = "XNNPACK"
        } catch (e: Throwable) { s = null }
        if (s == null) {
            val o = OrtSession.SessionOptions()
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            o.setIntraOpNumThreads(4)
            s = env.createSession(bytes, o); a = "CPU"
        }
        session = s!!; accel = a
        inputName = session.inputNames.first()
        val shape = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape ?: error("모델 입력 정보 없음")
        require(shape.size == 4 && shape[1] == 3L && shape[2] == 640L && shape[3] == 640L) { "모델 입력 형태 미지원: ${shape.contentToString()}" }
    }

    /** src 전체 또는 crop 영역을 640×640 레터박스로 넣어 감지. 결과 좌표는 src 기준 */
    fun detect(src: Bitmap, crop: Rect? = null, threshold: Double = 0.30): List<Det> {
        val r = crop ?: Rect(0, 0, src.width, src.height)
        val w = r.width().toDouble(); val h = r.height().toDouble()
        val scale = min(side / w, side / h)
        val dw = w * scale; val dh = h * scale
        val padX = (side - dw) / 2; val padY = (side - dh) / 2
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(src, r, RectF(padX.toFloat(), padY.toFloat(), (padX + dw).toFloat(), (padY + dh).toFloat()), paint)
        canvasBmp.getPixels(rgb, 0, side, 0, 0, side, side)
        val n = side * side
        for (i in 0 until n) {     // NCHW, RGB, 0~1
            val p = rgb[i]
            chw[i] = ((p shr 16) and 255) / 255f
            chw[n + i] = ((p shr 8) and 255) / 255f
            chw[2 * n + i] = (p and 255) / 255f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, 640, 640)).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val out = res.get(0) as OnnxTensor
                return YoloPost.decode(out.floatBuffer, out.info.shape, scale, padX, padY, r.left.toDouble(), r.top.toDouble(), threshold)
            }
        }
    }

    override fun close() { session.close(); canvasBmp.recycle() }
}
