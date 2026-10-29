package com.kyuil71.roadguardian

import android.graphics.Bitmap
import android.graphics.Rect
import com.kyuil71.roadguardian.core.*
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.tan

/** 화면에 넘기는 한 프레임의 결과 (복사본 — 스레드 안전) */
class TrackView(val box: Box, val kind: Kind, val conf: Double, val level: Int, val dist: String, val ttc: Double?,
                val X: Double, val Z: Double, val vX: Double, val vZ: Double, val inPath: Boolean)

class Snapshot(
    val vw: Int, val vh: Int, val cam: Cam,
    val tracks: List<TrackView>,
    val corrD: DoubleArray, val corrL: DoubleArray, val corrR: DoubleArray,
    val laneLines: List<Pair<DoubleArray, DoubleArray>>,    // (D 배열, X 배열)
    val laneInferred: List<Boolean>,
    val lanePts: FloatArray,
    val brakeD: Double?, val far: Double, val pathLevel: Int, val crop: Box?,
    val risk: Risk, val msg: String, val laneBasis: Int, val procMs: Long, val ego: Double?,
    val pitchDeg: Double, val autoDeg: Double, val alarmOn: Boolean, val alarmTitle: String, val alarmSub: String,
    val alarmDir: String, val detCount: Int, val nearVru: Boolean, val accel: String, val timeMs: Long)

/** 프레임 1장: 차선 → 감지(+원거리 확대) → 거리 → 추적 → 판단 → 경고. 분석 스레드에서만 호출 */
class Engine {
    val p = Params()
    val lanes = Lanes(p)
    val tracker = Tracker()
    val motion = Motion()
    val policy = AlertPolicy()
    @Volatile var detector: YoloDetector? = null
    @Volatile var initError: String? = null
    private var laneBmp: Bitmap? = null
    private var lanePx = IntArray(0)
    private var cropTurn = false

    fun reset() { tracker.reset(); lanes.reset(); policy.reset() }

    private fun syncParams() {
        p.reactT = Settings.num("reactT"); p.brakeA = Settings.num("brakeA"); p.laneOn = Settings.bool("laneOn")
        p.laneWf = Settings.num("laneWf"); p.camH = Settings.num("camH"); p.bonnet = Settings.num("bonnet")
    }

    /**
     * frame: 원본(가로) 영상, tSec: 프레임 시각, hfovDeg: 지금 렌즈의 실제 가로 화각, basePitch: 센서(또는 영상 설정) 기울기,
     * yaw: 회전 속도, ego: 내 차 속도 (m/s, 모르면 null), tele: 망원 사용 중, lensBusy: 렌즈 전환 중
     */
    fun process(frame: Bitmap, tSec: Double, nowMs: Long, hfovDeg: Double, basePitch: Double, motionOk: Boolean,
                yaw: Double, yawSlow: Double, ego: Double?, tele: Boolean, swZoom: Boolean, lensBusy: Boolean): Snapshot {
        val t0 = android.os.SystemClock.elapsedRealtime()
        syncParams()
        val vw = frame.width; val vh = frame.height
        val f = (vw / 2.0) / tan(hfovDeg * DEG / 2)
        val pitch = basePitch + Settings.num("pitchOff") * DEG + lanes.pitchAuto
        motion.pitch = pitch; motion.ok = motionOk; motion.yawRate = yaw; motion.yawSlow = yawSlow
        val cam = Cam(vw, vh, f, pitch, p.camH)

        // ── 차선 (480폭 축소 영상) + 밝기 점검 ──
        val W = 480; val H = max(1, (480.0 * vh / vw).toInt())
        val small = Bitmap.createScaledBitmap(frame, W, H, true)
        if (lanePx.size != W * H) lanePx = IntArray(W * H)
        small.getPixels(lanePx, 0, W, 0, 0, W, H)
        if (small !== frame) small.recycle()
        var lum = 0L; var cnt = 0
        var i = 0; while (i < lanePx.size) { val c = lanePx[i]; lum += ((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255); cnt++; i += 97 }
        val meanLuma = if (cnt > 0) lum / 10.0 / cnt else 0.0
        if (p.laneOn && motionOk) {
            val sc = W.toDouble() / vw
            val boxes = tracker.tracks.filter { it.hits >= 2 && it.kind.grp != Grp.VRU }.map { doubleArrayOf(it.box.x * sc, it.box.y * sc, it.box.r * sc, it.box.b * sc) }
            val pts = lanes.detectPoints(lanePx, W, H, f * sc, pitch, 1 / sc, boxes)
            lanes.update(pts, nowMs, ego, cam, yaw)
        } else lanes.reset()

        // ── 감지 ──
        val det = detector
        var dets: List<Det> = emptyList()
        var crop: Box? = null
        if (det != null && !lensBusy) {
            dets = det.detect(frame)
            // 원거리 확대: 고속이거나 망원일 때 한 프레임 걸러, 수평선 주변을 원본 해상도로 잘라 한 번 더
            cropTurn = !cropTurn
            if (cropTurn && (tele || (ego ?: 0.0) > 50 / 3.6)) {
                val sideF = min(vw, vh) * (if (swZoom) 0.34 else 0.6)
                val s = sideF.toInt()
                val yh = cam.horizonY()
                val e = lanes.edges(40.0, motion, ego); val zc = 40 * kotlin.math.cos(pitch)
                val xc = (vw / 2.0 + f * ((e[0] + e[1]) / 2) / zc).coerceIn(s / 2.0, vw - s / 2.0)
                val x0 = (xc - s / 2).toInt(); val y0 = (yh - s * 0.45).coerceIn(0.0, (vh - s).toDouble()).toInt()
                crop = Box(x0.toDouble(), y0.toDouble(), s.toDouble(), s.toDouble())
                val cd = det.detect(frame, Rect(x0, y0, x0 + s, y0 + s), 0.35)
                    .filter { it.box.x > x0 + 2 && it.box.y > y0 + 2 && it.box.r < x0 + s - 2 && it.box.b < y0 + s - 2 }
                val merged = ArrayList(dets)
                for (c in cd) {
                    val dup = merged.indexOfFirst { it.kind.assoc == c.kind.assoc && iou(it.box, c.box) > 0.3 }
                    if (dup < 0) merged += c else if (merged[dup].box.h < 40) merged[dup] = c
                }
                dets = merged
            }
        }
        val meas = dets.map { measure(it, cam, motionOk) }
        tracker.update(meas, tSec, vh, hypot(vw.toDouble(), vh.toDouble()), yaw, ego)
        for (tr in tracker.tracks) assess(tr, ego, p, lanes, motion)
        val procMs = android.os.SystemClock.elapsedRealtime() - t0

        // ── 판단 불가? → 경고 정책 → 전체 단계 ──
        val unknown = unknownReason(det != null, initError, 0, procMs, meanLuma, lensBusy)
            ?: if (!motionOk) "기울기 센서 없음 · 거리 판단 불가" else null
        val alert = policy.update(nowMs, tracker.tracks, lanes, motion, ego, unknown == null)
        val (risk, msg) = overall(unknown, alert.alarmOn, tracker.tracks, p, lanes, motion, ego, policy)
        lastAlert = alert

        // ── 화면용 복사본 ──
        val far = if (tele) 120.0 else 70.0
        val N = 28
        val cD = DoubleArray(N) { p.bonnet + (far - p.bonnet) * it / (N - 1) }
        val cL = DoubleArray(N); val cR = DoubleArray(N)
        for (k in 0 until N) { val e = lanes.edges(cD[k], motion, ego); cL[k] = e[0]; cR[k] = e[1] }
        val lines = ArrayList<Pair<DoubleArray, DoubleArray>>(); val inferred = ArrayList<Boolean>()
        val l = lanes.cL; val r = lanes.cR
        for ((m, other, sgn) in listOf(Triple(l, r, 1.0), Triple(r, l, -1.0))) {
            if (m == null) continue
            val d0 = max(p.bonnet, m.dMin - 2); val d1 = min(far, m.dMax + 10)
            val ds = DoubleArray(16) { d0 + (d1 - d0) * it / 15 }
            lines += ds to DoubleArray(16) { m.x(ds[it]) }; inferred += false
            if (other == null) { lines += ds to DoubleArray(16) { m.x(ds[it]) + sgn * lanes.width }; inferred += true }
        }
        val lp = lanes.points; val stepP = max(1, lp.size / 400)
        val pts = FloatArray((lp.size / stepP + 1) * 2); var n = 0
        var j = 0; while (j < lp.size && n + 1 < pts.size) { pts[n++] = lp[j].u.toFloat(); pts[n++] = lp[j].v.toFloat(); j += stepP }
        val v = ego ?: 0.0
        val brakeD = if (v > 3) p.bonnet + v * p.reactT + v * v / (2 * p.brakeA) + 2 else null
        val vis = tracker.tracks.filter { it.hits >= 3 }
        val pathLevel = if (alert.alarmOn) 3 else (vis.filter { it.inPath || it.level >= 2 }.maxOfOrNull { it.level } ?: 0)
        val w = alert.worst
        val side = if (w != null) policy.sideOf(w, lanes, motion, ego) else "전방"
        val tt = if (w != null) ttcShown(w) else null
        return Snapshot(vw, vh, cam,
            vis.map { TrackView(it.box, it.kind, it.conf, it.level, fmtDist(distRange(it, p, lanes)), ttcShown(it), it.X, it.Z, it.vX, it.vZ, it.inPath) },
            cD, cL, cR, lines, inferred, pts.copyOf(n), brakeD, far, pathLevel, crop,
            risk, msg, lanes.basis, procMs, ego, pitch / DEG, lanes.pitchAuto / DEG,
            alert.alarmOn, if (w != null) "$side ${w.kind.label} 충돌 위험" else "충돌 위험",
            (if (w != null) fmtDist(distRange(w, p, lanes)) else "") + (if (tt != null) " · 약 ${tt}초" else "") + " · 브레이크",
            when (side) { "왼쪽" -> "◀"; "오른쪽" -> "▶"; else -> "▲" },
            dets.size, vis.any { it.Z < 25 && it.kind.grp != Grp.VEH }, det?.accel ?: "-", nowMs)
    }

    @Volatile var lastAlert: AlertOut? = null
}

fun Double.r1() = round(this * 10) / 10
