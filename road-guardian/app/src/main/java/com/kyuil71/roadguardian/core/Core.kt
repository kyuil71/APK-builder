package com.kyuil71.roadguardian.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * AI Road Guardian — 판단 엔진 (안드로이드 의존성 없음 → PC에서 그대로 테스트)
 *
 * 좌표계: 카메라 기준. X = 오른쪽(+) m, Z/D = 전방 거리 m.
 * 단일 카메라의 거리 추정은 불확실하다. 그래서
 *   - 판단은 '진로 안인가' + '지금 브레이크를 밟지 않으면 제동 한계를 넘는가'로 하고,
 *   - 접근 속도는 거리 변화와 '크기 변화율'(보정값과 무관)을 함께 쓰며,
 *   - 화면에는 거리를 범위로만, TTC는 근거가 있을 때만 보여준다.
 *   - 시스템이 볼 수 없는 상태(모델·프레임·지연·어둠)는 '판단 불가'이며 절대 안전으로 표시하지 않는다.
 */

const val DEG = PI / 180.0
fun clamp(v: Double, a: Double, b: Double) = max(a, min(b, v))

enum class Grp { VRU, MOTO, VEH }

/** h: 높이, w: 뒷모습 폭, len: 길이(m), vmax: 내 진행 방향으로 멀어질 수 있는 최대 속도, sizeRel: 크기 개체차(상대) */
enum class Kind(val label: String, val h: Double, val w: Double, val len: Double, val grp: Grp, val vmax: Double?, val sizeRel: Double) {
    PERSON("보행자", 1.70, 0.50, 0.4, Grp.VRU, 2.5, 0.12),
    BICYCLE("자전거", 1.10, 0.60, 1.7, Grp.VRU, 8.0, 0.18),
    MOTORCYCLE("오토바이", 1.30, 0.80, 2.0, Grp.MOTO, null, 0.18),
    CAR("차량", 1.50, 1.80, 4.5, Grp.VEH, null, 0.15),
    TRUCK("트럭", 3.00, 2.40, 9.0, Grp.VEH, null, 0.30),
    BUS("버스", 3.20, 2.50, 11.0, Grp.VEH, null, 0.12);
    /** 추적 시 같은 물체로 묶을 수 있는 무리 */
    val assoc: Int get() = when (this) { PERSON -> 0; BICYCLE, MOTORCYCLE -> 1; else -> 2 }
}

class Params {
    var reactT = 1.0      // 반응 시간 (s)
    var brakeA = 6.0      // 급제동 한계 (m/s²)
    var laneOn = true
    var laneWf = 3.3      // 차선을 못 찾을 때 진로 폭 (m)
    var camH = 1.25       // 카메라 높이 (m)
    var bonnet = 1.8      // 카메라 → 앞범퍼 (m)
}

data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
    val r get() = x + w
    val b get() = y + h
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    val area get() = w * h
}

data class Det(val box: Box, val kind: Kind, val conf: Double)

fun iou(a: Box, b: Box): Double {
    val x0 = max(a.x, b.x); val y0 = max(a.y, b.y); val x1 = min(a.r, b.r); val y1 = min(a.b, b.b)
    val i = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    val u = a.area + b.area - i
    return if (u > 0) i / u else 0.0
}

/** 카메라 기하: 영상 크기(px), 초점거리 f(px), 아래쪽 기울기 pitch(rad), 지면 높이 h(m) */
class Cam(val vw: Int, val vh: Int, val f: Double, val pitch: Double, val h: Double) {
    val cx = vw / 2.0
    val cy = vh / 2.0
    fun horizonY() = cy - f * tan(pitch)
    /** 지면 위 (X, D) → 영상 (u, v). 카메라 뒤면 null */
    fun project(X: Double, D: Double): DoubleArray? {
        val zc = D * cos(pitch) + h * sin(pitch)
        val yc = -h * cos(pitch) + D * sin(pitch)
        return if (zc > 0.3) doubleArrayOf(cx + f * X / zc, cy - f * yc / zc) else null
    }
}

/** 폰 센서에서 온 자세 */
class Motion {
    var pitch = 5 * DEG   // 최종 기울기 (센서 + 수동 + 자동 보정)
    var ok = false
    var yawRate = 0.0     // rad/s, + = 좌회전
    var yawSlow = 0.0     // 진로 곡률용 느린 필터
}

/* ───────────────────────── 차선 ───────────────────────── */

class LanePt(val X: Double, val D: Double, val u: Double, val v: Double)

/** 차선 경계 X(D) = a + b·D + c·D² */
class LaneB(var a: Double, var b: Double, var c: Double) {
    var dMin = 0.0; var dMax = 0.0; var n = 0; var conf = 0.0; var seen = 0L
    fun x(D: Double): Double {
        val lim = dMax + 15
        return if (D <= lim) a + b * D + c * D * D
        else a + b * lim + c * lim * lim + (b + 2 * c * lim) * (D - lim)   // 관측 범위 밖은 직선 연장
    }
}

class FitResult(val L: LaneB?, val R: LaneB?, val slope: Double?, val selL: List<LanePt>? = null, val selR: List<LanePt>? = null)

class Lanes(private val p: Params) {
    var L: LaneB? = null
    var R: LaneB? = null
    var width = 3.3
    var pitchAuto = 0.0
    var points: List<LanePt> = emptyList()
    private val vpBuf = ArrayDeque<List<LanePt>>()

    fun reset() { L = null; R = null; points = emptyList(); vpBuf.clear() }
    fun conf(m: LaneB?) = if (m != null && m.conf >= 0.3) m else null
    val cL get() = conf(L)
    val cR get() = conf(R)
    /** 0 = 차선 없음(회전 추정), 1 = 한쪽, 2 = 양쪽 */
    val basis get() = (if (cL != null) 1 else 0) + (if (cR != null) 1 else 0)

    /** 거리 D에서 내 진로의 [왼쪽, 오른쪽, 근거] */
    fun edges(D: Double, motion: Motion, v: Double?): DoubleArray {
        val l = cL; val r = cR
        if (l != null && r != null) return doubleArrayOf(l.x(D), r.x(D), 2.0)
        if (l != null) { val x = l.x(D); return doubleArrayOf(x, x + width, 1.0) }
        if (r != null) { val x = r.x(D); return doubleArrayOf(x - width, x, 1.0) }
        val c = pathCenter(D, motion, v)
        return doubleArrayOf(c - p.laneWf / 2, c + p.laneWf / 2, 0.0)
    }

    /** 차선이 없을 때: 차의 회전(요레이트)으로 진로 곡률 추정 */
    fun pathCenter(D: Double, motion: Motion, v: Double?): Double {
        if (v == null || v < 3) return 0.0
        val w = if (abs(motion.yawSlow) < 0.02) 0.0 else motion.yawSlow
        return clamp(-0.5 * clamp(w / v, -1.0 / 30, 1.0 / 30) * D * D, -15.0, 15.0)
    }

    // ── 영상 → 지면 점: '밝아짐-어두워짐' 경계 쌍이 차선 도색 폭만큼 떨어진 곳 ──
    /**
     * px: ARGB 픽셀 (W×H, 축소 영상), f: 이 영상 기준 초점거리(px), toSrc: 원본 좌표 배율,
     * boxes: 제외 영역 [x0,y0,x1,y1] (이 영상 좌표)
     */
    fun detectPoints(px: IntArray, W: Int, H: Int, f: Double, pitch: Double, toSrc: Double, boxes: List<DoubleArray>): List<LanePt> {
        val h = p.camH
        val cx = W / 2.0; val cy = H / 2.0
        val y0 = max(0, ceil(cy - f * tan(pitch) + 3).toInt())
        if (y0 >= H - 6) return emptyList()
        val dHood = clamp(p.bonnet * h / max(0.25, h - 0.85), 3.0, 12.0)   // 보닛이 가리는 노면 제외
        val I = IntArray(W); val G = IntArray(W); val hist = IntArray(64)
        val out = ArrayList<LanePt>()
        var y = y0
        while (y < H) {
            val ang = pitch + atan((y - cy) / f)
            if (ang < 0.012) { y += 1; continue }
            val D = h / tan(ang)
            val step = if (D < 12) 3 else if (D < 25) 2 else 1
            if (D < dHood || D > 70) { y += step; continue }
            val zc = D * cos(pitch) + h * sin(pitch)
            val mw = 0.15 * f / zc
            if (mw < 0.7) { y += step; continue }
            val wMin = max(1.0, mw * 0.4); val wMax = mw * 2.6 + 2
            hist.fill(0)
            val base = y * W
            for (x in 0 until W) {
                val c = px[base + x]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255
                val v = if (r > g) r else g          // 흰색·노란색 모두 밝게
                I[x] = v; hist[v shr 2]++
            }
            var acc = 0; var med = 0
            for (bi in 0 until 64) { acc += hist[bi]; if (acc >= W / 2) { med = bi * 4 + 2; break } }
            var gs = 0L
            for (x in 1 until W - 1) { G[x] = I[x + 1] - I[x - 1]; gs += abs(G[x]) }
            val noise = gs.toDouble() / (W - 2)
            val T = max(8.0, 2.5 * noise); val T2 = max(12.0, 3 * noise)
            var x = 2
            while (x < W - 3) {
                if (!(G[x] > T && G[x] >= G[x - 1] && G[x] >= G[x + 1])) { x++; continue }
                var xf = -1; var gmin = -T
                val x1 = min(W - 2, ceil(x + wMax).toInt())
                var j = ceil(x + wMin).toInt()
                while (j <= x1) { if (G[j] < gmin) { gmin = G[j].toDouble(); xf = j }; j++ }
                if (xf < 0) { x++; continue }
                val xc = (x + xf) / 2.0
                val ic = I[round(xc).toInt()]
                val side = max(I[max(0, x - 2)], I[min(W - 1, xf + 2)])
                if (ic > med + T2 && ic - side > T && boxes.none { xc > it[0] && xc < it[2] && y > it[1] && y < it[3] })
                    out += LanePt((xc - cx) * zc / f, D, xc * toSrc, y * toSrc)
                x = xf + 1
            }
            y += step
        }
        return out
    }

    // ── 피팅 ──
    private fun span(s: List<LanePt>): DoubleArray {
        var a = Double.POSITIVE_INFINITY; var b = 0.0
        for (q in s) { if (q.D < a) a = q.D; if (q.D > b) b = q.D }
        return doubleArrayOf(a, b)
    }
    private fun ok(s: List<LanePt>?) = s != null && s.size >= 8 && span(s).let { it[1] - it[0] >= 5 }

    private fun selectSide(pts: List<LanePt>, prior: LaneB?, side: Int): List<LanePt>? {
        if (prior != null) return pts.filter { abs(it.X - prior.x(it.D)) < 0.45 + 0.012 * it.D }
        val bins = DoubleArray(30)
        for (q in pts) { if (q.D > 22) continue; val k = Math.floor((q.X * side - 0.4) / 0.1).toInt(); if (k in 0..29) bins[k]++ }
        var best = -1; var bv = 0.0
        for (k in 0 until 30) { val s = bins[k] + 0.5 * ((if (k > 0) bins[k - 1] else 0.0) + (if (k < 29) bins[k + 1] else 0.0)); if (s > bv) { bv = s; best = k } }
        if (bv < 5) return null
        val x0 = side * (0.4 + (best + 0.5) * 0.1)
        return pts.filter { abs(it.X - x0) < 0.5 + 0.03 * it.D }
    }

    /** 한쪽 선을 알면 반대쪽은 차선 폭(2.4~4.6m)만큼 떨어진 평행선으로 찾는다 */
    private fun acquireByOffset(pts: List<LanePt>, mo: LaneB, dir: Int): List<LanePt>? {
        val bins = DoubleArray(23)
        for (q in pts) { if (q.D > 50) continue; val k = Math.floor(((q.X - mo.x(q.D)) * dir - 2.4) / 0.1).toInt(); if (k in 0..22) bins[k]++ }
        var best = -1; var bv = 0.0
        for (k in 0 until 23) { val s = bins[k] + 0.5 * ((if (k > 0) bins[k - 1] else 0.0) + (if (k < 22) bins[k + 1] else 0.0)); if (s > bv) { bv = s; best = k } }
        if (bv < 6) return null
        val off = 2.4 + (best + 0.5) * 0.1
        return pts.filter { abs((it.X - mo.x(it.D)) * dir - off) < 0.35 + 0.01 * it.D }
    }

    /** 곡률(c)은 공유, 위치(a)·방향(b)은 선마다 (shareB면 방향도 공유 = 완전 평행 모델) */
    private fun fitJoint(sides: List<List<LanePt>>, quad: Boolean, shareB: Boolean): List<LaneB>? {
        val na = sides.size; val nb = if (shareB) 1 else na; val n = na + nb + (if (quad) 1 else 0)
        val A = Array(n) { DoubleArray(n) }; val y = DoubleArray(n)
        sides.forEachIndexed { si, sel ->
            val ib = na + (if (shareB) 0 else si)
            for (q in sel) {
                val w = 1 / (1 + q.D / 15); val ph = DoubleArray(n)
                ph[si] = 1.0; ph[ib] = q.D; if (quad) ph[na + nb] = q.D * q.D
                for (i in 0 until n) { if (ph[i] == 0.0) continue; y[i] += w * ph[i] * q.X; for (j in 0 until n) A[i][j] += w * ph[i] * ph[j] }
            }
        }
        val s = solve(A, y, n) ?: return null
        return sides.indices.map { si -> LaneB(s[si], s[na + (if (shareB) 0 else si)], if (quad) s[na + nb] else 0.0) }
    }

    private fun lineSlope(s: List<LanePt>): Double? {
        val m = fitJoint(listOf(s), false, true) ?: return null
        return m[0].b
    }

    private fun fitOnce(pts: List<LanePt>, shareIters: Int): FitResult {
        var sL = selectSide(pts, L, -1); var sR = selectSide(pts, R, 1)
        if (!ok(sL)) sL = null; if (!ok(sR)) sR = null
        if ((sL != null) xor (sR != null)) {
            val s = sL ?: sR!!; val sp = span(s)
            val m1 = fitJoint(listOf(s), sp[1] - sp[0] > 16 && s.size >= 24, true)
            if (m1 != null) {
                m1[0].dMax = sp[1]
                val got = acquireByOffset(pts, m1[0], if (sL != null) 1 else -1)
                if (ok(got)) { if (sL != null) sR = got else sL = got }
            }
        }
        val DLIM = doubleArrayOf(22.0, 32.0, 48.0, 75.0)   // 가까운 곳 → 먼 곳으로 넓혀 커브 추종
        var mL: LaneB? = null; var mR: LaneB? = null
        for (it in 0 until 4) {
            val lim: (List<LanePt>?) -> List<LanePt>? = { s -> if (s == null) null else { val f = s.filter { q -> q.D < DLIM[it] }; if (ok(f)) f else s } }
            sL = lim(sL); sR = lim(sR)
            if (!ok(sL)) sL = null; if (!ok(sR)) sR = null
            if (sL == null && sR == null) return FitResult(null, null, null)
            val sides = listOfNotNull(sL, sR); val all = sides.flatten(); val sp = span(all)
            val ms = fitJoint(sides, sp[1] - sp[0] > 12 && all.size >= 16, it < shareIters) ?: return FitResult(null, null, null)
            var k = 0
            mL = if (sL != null) ms[k++] else null
            mR = if (sR != null) ms[k++] else null
            for ((m, s) in listOf(mL to sL, mR to sR)) if (m != null && s != null) { val q = span(s); m.dMin = q[0]; m.dMax = q[1]; m.n = s.size }
            val fl = mL; val fr = mR
            if (fl != null) sL = pts.filter { q -> abs(q.X - fl.x(q.D)) < 0.25 + 0.008 * q.D }
            if (fr != null) sR = pts.filter { q -> abs(q.X - fr.x(q.D)) < 0.25 + 0.008 * q.D }
        }
        fun valid(m: LaneB?, side: Int) = m != null && abs(m.b) <= 0.25 && abs(m.c) <= 0.006 && m.a * side >= 0.3 && m.a * side <= 3.6
        if (!valid(mL, -1)) mL = null; if (!valid(mR, 1)) mR = null
        val a = mL; val b = mR
        if (a != null && b != null) {
            val w = b.a - a.a
            if (w < 2.4 || w > 4.6 || abs(b.b - a.b) > 0.1) { if (a.n < b.n) mL = null else mR = null }
        }
        // 기울기 점검용: 35m 이내에서 각 선의 직선 기울기 차이
        var slope: Double? = null
        if (mL != null && mR != null && sL != null && sR != null) {
            val nl = sL.filter { it.D < 35 }; val nr = sR.filter { it.D < 35 }
            val sp = { s: List<LanePt> -> span(s).let { it[1] - it[0] } }
            if (nl.size >= 12 && nr.size >= 12 && sp(nl) >= 8 && sp(nr) >= 8) {
                val bl = lineSlope(nl); val br = lineSlope(nr)
                if (bl != null && br != null) slope = br - bl
            }
        }
        return FitResult(mL, mR, slope, sL, sR)
    }

    fun fit(pts: List<LanePt>): FitResult {
        // 평행 모델로 포착 → 한쪽을 잃으면(기울기 오차로 벌어져 보일 때) 선마다 방향을 둔 모델로
        val a = fitOnce(pts, 2)
        if (a.L != null && a.R != null) return a
        val b = fitOnce(pts, 0)
        val na = (if (a.L != null) 1 else 0) + (if (a.R != null) 1 else 0)
        val nb = (if (b.L != null) 1 else 0) + (if (b.R != null) 1 else 0)
        return if (nb > na) b else a
    }

    /** 소실점: 영상 좌표만으로 가장 뚜렷한 두 직선을 찾아 만나는 높이 (센서 기울기와 무관) */
    fun vanishRow(pts: List<LanePt>, imgW: Int): Double? {
        if (pts.size < 24) return null
        var seed = 12345L
        val rnd = { n: Int -> seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; (seed % n).toInt() }
        val tol = max(3.0, imgW * 0.006)
        class Ln(val m: Double, val k: Double, val rest: List<LanePt>)
        fun line(Q: List<LanePt>, avoidM: Double?): Ln? {
            if (Q.size < 10) return null
            var bm = 0.0; var bk = 0.0; var bn = 0
            for (i in 0 until 200) {
                val a = Q[rnd(Q.size)]; val b = Q[rnd(Q.size)]
                if (abs(a.v - b.v) < 6) continue
                val m = (a.u - b.u) / (a.v - b.v)
                if (abs(m) < 0.2 || abs(m) > 8) continue
                if (avoidM != null && abs(m - avoidM) < 0.25) continue
                val k = a.u - m * a.v
                var n = 0; for (q in Q) if (abs(q.u - m * q.v - k) < tol) n++
                if (n > bn) { bn = n; bm = m; bk = k }
            }
            if (bn < 10) return null
            val inl = Q.filter { abs(it.u - bm * it.v - bk) < tol }
            var n = 0.0; var sv = 0.0; var su = 0.0; var svv = 0.0; var svu = 0.0
            for (q in inl) { n++; sv += q.v; su += q.u; svv += q.v * q.v; svu += q.v * q.u }
            val den = n * svv - sv * sv
            if (abs(den) < 1e-6) return null
            val m = (n * svu - sv * su) / den; val k = (su - m * sv) / n
            return Ln(m, k, Q.filter { abs(it.u - m * it.v - k) >= tol * 1.5 })
        }
        val a = line(pts, null) ?: return null
        val b = line(a.rest, a.m) ?: return null
        val vr = (a.k - b.k) / (b.m - a.m); val ur = a.m * vr + a.k
        return if (ur > -imgW * 0.5 && ur < imgW * 1.5) vr else null
    }

    /** cam: 원본 영상 기준 기하 (소실점 보정용), yaw: 자이로 회전 속도(rad/s) */
    fun update(pts: List<LanePt>, nowMs: Long, v: Double?, cam: Cam?, yaw: Double = 0.0) {
        points = pts
        val fr = fit(pts)
        for (side in 0..1) {
            val f = if (side == 0) fr.L else fr.R
            val o = if (side == 0) L else R
            if (f != null) {
                if (o != null) {
                    val g = 0.35
                    o.a += (f.a - o.a) * g; o.b += (f.b - o.b) * g; o.c += (f.c - o.c) * g
                    o.dMin = f.dMin; o.dMax = f.dMax; o.conf = min(1.0, o.conf + 0.2); o.seen = nowMs
                } else { f.conf = 0.4; f.seen = nowMs; if (side == 0) L = f else R = f }
            } else if (o != null) {
                o.conf -= 0.08
                if (o.conf <= 0 || nowMs - o.seen > 1500) { if (side == 0) L = null else R = null }
            }
        }
        // 차선 변경: 경계선이 차 가운데를 지나가면 반대쪽 경계가 됨
        val l0 = L; val r0 = R
        if (l0 != null && l0.a > -0.25) { R = l0; L = null }
        else if (r0 != null && r0.a < 0.25) { L = r0; R = null }
        val l = cL; val r = cR
        // 커브를 도는 중(자이로 기준 회전 반경 400m 미만)에는 두 보정 모두 쉼.
        // 휘어진 선을 직선으로 보면 기울기가 치우치기 때문. 자이로는 카메라 기울기와 무관한 물리량이라 오판이 없다.
        val vv = v ?: 0.0
        val curving = vv > 3 && abs(yaw) / vv > 1.0 / 400
        // ① 큰 오차: 소실점 (영상에서 거의 직선인 35m 이내 점, 최근 3프레임)
        vpBuf.addLast(pts.filter { it.D < 35 }); while (vpBuf.size > 3) vpBuf.removeFirst()
        if (cam != null && (v ?: 0.0) > 6 && !curving) {
            val vr = vanishRow(vpBuf.flatten(), cam.vw)
            if (vr != null && vr > -cam.cy && vr < cam.cy * 2) {
                val e = atan((cam.cy - vr) / cam.f) - cam.pitch
                if (abs(e) > 0.25 * DEG && abs(e) < 10 * DEG)
                    pitchAuto = clamp(pitchAuto + clamp(e * 0.15, -0.5 * DEG, 0.5 * DEG), -8 * DEG, 8 * DEG)
            }
        }
        if (l != null && r != null) {
            val w = r.a - l.a
            if (w > 2.4 && w < 4.6) width += (w - width) * 0.05
            // ② 작은 오차: 평행한 두 선이 벌어지거나 모여 보이면 기울기 추정이 틀린 것 → 천천히
            val sl = fr.slope
            if (sl != null && !curving && l.conf >= 0.95 && r.conf >= 0.95 && (v ?: 0.0) > 6)
                pitchAuto = clamp(pitchAuto + 0.015 * clamp(sl, -0.05, 0.05), -8 * DEG, 8 * DEG)
        }
    }

    companion object {
        fun solve(A: Array<DoubleArray>, y: DoubleArray, n: Int): DoubleArray? {
            for (i in 0 until n) {
                var p = i
                for (r in i + 1 until n) if (abs(A[r][i]) > abs(A[p][i])) p = r
                val t = A[i]; A[i] = A[p]; A[p] = t
                val ty = y[i]; y[i] = y[p]; y[p] = ty
                if (abs(A[i][i]) < 1e-9) return null
                for (r in i + 1 until n) { val k = A[r][i] / A[i][i]; for (c in i until n) A[r][c] -= k * A[i][c]; y[r] -= k * y[i] }
            }
            val s = DoubleArray(n)
            for (i in n - 1 downTo 0) { var v = y[i]; for (c in i + 1 until n) v -= A[i][c] * s[c]; s[i] = v / A[i][i] }
            return s
        }
    }
}

/* ───────────────────────── 거리 측정 ───────────────────────── */

class Measured(val det: Det, val X: Double, val Z: Double, val ground: Boolean)

/** 바닥 접점(카메라 높이·기울기) + 클래스 높이 + 뒷모습 폭의 가중 평균 */
fun measure(det: Det, cam: Cam, motionOk: Boolean): Measured {
    val bb = det.box; val P = det.kind
    val topCut = bb.y < cam.vh * 0.01; val botCut = bb.b > cam.vh * 0.99
    val sideCut = bb.x < cam.vw * 0.01 || bb.r > cam.vw * 0.99
    var zs = 0.0; var ws = 0.0; var ground = false
    if (!botCut && motionOk) {
        val ang = cam.pitch + atan((bb.b - cam.cy) / cam.f)
        if (ang > 0.004) {
            val d = cam.h / tan(ang)
            if (d < 220) { val w = if (ang > 0.05) 2.0 else if (ang > 0.017) 1.0 else 0.3; zs += d * w; ws += w; ground = w >= 1.0 }
        }
    }
    if (!topCut && !botCut) { zs += P.h * cam.f / bb.h; ws += 1.0 }
    if (!sideCut && (P.grp != Grp.VEH || bb.w / bb.h < 1.7)) { zs += P.w * cam.f / bb.w * 0.4; ws += 0.4 }
    if (ws == 0.0) { zs = P.w * cam.f / bb.w; ws = 1.0 }
    val Z = clamp(zs / ws, 0.5, 200.0)
    return Measured(det, (bb.cx - cam.cx) * Z / cam.f, Z, ground)
}

/* ───────────────────────── 추적 ───────────────────────── */

class Track(var id: Int, var box: Box, var kind: Kind, var conf: Double, var X: Double, var Z: Double, var vX: Double, var vZ: Double, var t: Double) {
    val votes = IntArray(Kind.entries.size)
    var seen = t; var hits = 1; var ground = false
    var pX = X; var pZ = Z; var dt = 0.0
    // 크기 변화율 (d ln h / dt) — 다가오면 커진다. 접근속도 = 거리 × 변화율
    var lhInit = false; var lh = 0.0; var lhRate = 0.0; var lhN = 0; var lhOk = false
    // 판단 결과
    var level = 0; var why = ""; var rel = "out"; var ttc = Double.POSITIVE_INFINITY; var dist = Z; var closing = 0.0
    var inPath = false; var warned = false; var said = false
}

class Tracker {
    val tracks = ArrayList<Track>()
    private var nextId = 1
    fun reset() { tracks.clear() }

    fun update(meas: List<Measured>, t: Double, vh: Int, diag: Double, yawRate: Double, ego: Double?) {
        for (tr in tracks) {
            val dt = clamp(t - tr.t, 0.0, 0.5)
            tr.pX = tr.X + tr.vX * dt + yawRate * tr.Z * dt   // 차가 돌면 정지 물체도 옆으로 흐름 → 보상
            tr.pZ = tr.Z + tr.vZ * dt
            tr.dt = dt
        }
        val pairs = ArrayList<Triple<Double, Int, Int>>()
        tracks.forEachIndexed { i, tr ->
            meas.forEachIndexed { j, m ->
                if (tr.kind.assoc != m.det.kind.assoc) return@forEachIndexed
                val d = m.det.box
                val c = Math.hypot(tr.box.cx - d.cx, tr.box.cy - d.cy) / diag * 3 +
                    abs(ln(d.h / tr.box.h)) + 0.5 * abs(ln(d.w / tr.box.w))
                if (c < 1.0) pairs += Triple(c, i, j)
            }
        }
        pairs.sortBy { it.first }
        val ut = HashSet<Int>(); val ud = HashSet<Int>()
        for ((_, i, j) in pairs) {
            if (i in ut || j in ud) continue
            ut += i; ud += j
            val tr = tracks[i]; val m = meas[j]; val dt = max(0.03, tr.dt)
            val young = tr.hits < 4; val far = tr.Z > 40
            val aX = 0.5; val bX = 0.12
            val aZ = if (young) 0.8 else if (far) 0.2 else 0.3
            val bZ = if (young) 0.25 else if (far) 0.04 else 0.07
            val rX = m.X - tr.pX; val rZ = m.Z - tr.pZ
            tr.X = tr.pX + aX * rX; tr.vX = clamp(tr.vX + bX * rX / dt, -8.0, 8.0)
            tr.Z = tr.pZ + aZ * rZ; tr.vZ = clamp(tr.vZ + bZ * rZ / dt, -45.0, 25.0)
            val bb = m.det.box
            val cut = bb.y < vh * 0.01 || bb.b > vh * 0.99
            val lnh = ln(bb.h)
            if (cut) { tr.lhInit = false; tr.lhOk = false }
            else if (!tr.lhInit) { tr.lh = lnh; tr.lhRate = 0.0; tr.lhN = 0; tr.lhInit = true; tr.lhOk = false }
            else {
                val r = lnh - tr.lh
                tr.lh += 0.45 * r; tr.lhRate = clamp(tr.lhRate + 0.12 * r / dt, -1.5, 3.0)
                tr.lhN++; tr.lhOk = tr.lhN >= 6
            }
            tr.box = bb; tr.conf = m.det.conf; tr.ground = m.ground; tr.t = t; tr.seen = t; tr.hits++
            tr.votes[m.det.kind.ordinal]++
            tr.kind = Kind.entries.maxBy { tr.votes[it.ordinal] }
        }
        tracks.forEachIndexed { i, tr ->
            if (tr.lhInit) tr.lh += tr.lhRate * tr.dt
            if (i !in ut) { tr.X = tr.pX; tr.Z = tr.pZ; tr.t = t }
        }
        tracks.removeAll { t - it.seen > 0.8 }
        meas.forEachIndexed { j, m ->
            if (j in ud) return@forEachIndexed
            val v = ego ?: 0.0
            val tr = Track(nextId++, m.det.box, m.det.kind, m.det.conf, m.X, m.Z, 0.0, if (m.det.kind.grp == Grp.VEH) 0.0 else -v * 0.8, t)
            tr.votes[m.det.kind.ordinal] = 1; tr.ground = m.ground
            tracks += tr
        }
    }
}

/* ───────────────────────── 위험 판단 ───────────────────────── */

/** 위험 단계: 0 진로 밖 · 1 진로 안(징후 없음) · 2 주의 · 3 위험 */
fun assess(tr: Track, ego: Double?, p: Params, lanes: Lanes, motion: Motion) {
    val K = tr.kind; val vv = ego ?: 0.0
    val gap = max(0.3, tr.Z - p.bonnet)
    var closing = -tr.vZ
    if (tr.lhOk && tr.Z < 80) closing = 0.5 * closing + 0.5 * tr.Z * tr.lhRate
    if (ego != null && K.vmax != null) closing = max(closing, vv - K.vmax)
    val half = if (K == Kind.PERSON) 0.3 else K.w / 2
    val need = if (K.grp == Grp.VEH) 0.5 else 0.15
    val e = lanes.edges(tr.Z, motion, ego)
    val eL = e[0]; val eR = e[1]
    val ovNow = min(tr.X + half, eR) - max(tr.X - half, eL)
    val tReach = if (closing > 0.3) gap / closing else Double.POSITIVE_INFINITY
    var rel = if (ovNow > need) "in" else "out"
    if (rel == "out" && tr.hits >= 6 && tReach < 5 && abs(tr.vX) > 0.35) {
        val mid = (eL + eR) / 2; val hw = (eR - eL) / 2
        if (sign(mid - tr.X) == sign(tr.vX)) {     // 끼어들기·횡단: 도착할 때 진로 안에 있는가
            val offAt = tr.X - mid + tr.vX * tReach
            if (min(offAt + half, hw) - max(offAt - half, -hw) > need) rel = "enter"
        }
    }
    // 물리: 접근 속도 dv를 없애는 데 필요한 거리 = 반응 거리 + 제동 거리 + 여유
    val dv = max(0.0, closing)
    val dRed = dv * p.reactT + dv * dv / (2 * p.brakeA) + 2
    val dYel = dv * (p.reactT + 1.2) + dv * dv / (2 * 3.5) + 4
    val mature = tr.hits >= (if (tr.Z > 40) 8 else 4)
    var level: Int; var why: String
    if (rel == "out") {
        level = 0; why = "진로 밖"
        if (K.grp == Grp.VRU && vv > 3 && -ovNow < 0.6 && tReach < 2.5) { level = 2; why = "진로 옆 근접" }
    } else if (dv < 0.3) {
        level = 1; why = if (closing < -0.5) "멀어지는 중" else "같은 속도"
        if (K.grp == Grp.VEH && vv > 8 && gap / vv < 0.6) { level = 2; why = "차간 거리 부족" }
    } else {
        level = 1; why = "접근 중"
        if (gap <= dYel) { level = 2; why = if (rel == "enter") (if (K.grp == Grp.VEH) "끼어들기 주의" else "진로 진입 주의") else "접근 주의" }
        if (mature && (gap <= dRed || tReach < 1.4)) { level = 3; why = "충돌 위험" }
        if (rel == "enter" && level == 3 && !(tr.hits >= 10 && abs(tr.vX) > 0.6)) level = 2
        if (K.grp == Grp.VEH && vv < 2.5 && level == 3 && tReach > 1.0) level = 2     // 정체 구간 서행
        if (K.grp == Grp.VEH && vv < 4.2 && level == 2 && tReach > 2.0) level = 1     // 15km/h 이하 앞차 접근은 안내만
    }
    if (ego != null && vv < 1 && level >= 2 && !(closing > 3 && tReach < 2)) level = 1   // 내 차가 서 있을 때
    if (level == 1 && why.endsWith("주의")) why = if (dv >= 0.3) "접근 중" else "같은 속도"
    tr.level = level; tr.ttc = tReach; tr.dist = gap; tr.rel = rel; tr.closing = closing; tr.why = why
    tr.inPath = rel != "out"
}

/** 화면용 거리 범위 (m). 불확실성이 너무 크면 null → 숫자를 보이지 않는다 */
fun distRange(tr: Track, p: Params, lanes: Lanes): DoubleArray? {
    val D = tr.Z
    val dp = (if (lanes.basis == 2) 0.3 else 0.8) * DEG
    val sizeRel = tr.kind.sizeRel + 0.05
    val rel = if (tr.ground) { val g = D * dp / p.camH; 1 / sqrt(1 / (g * g) + 1 / (sizeRel * sizeRel)) } else sizeRel
    if (rel > 0.45 || tr.hits < 3) return null
    val gap = max(0.3, D - p.bonnet)
    val q = if (gap > 20) 5.0 else 1.0
    val lo = max(0.0, Math.floor((gap - D * rel) / q) * q); val hi = ceil((gap + D * rel) / q) * q
    return doubleArrayOf(lo, max(hi, lo + q))
}

fun fmtDist(r: DoubleArray?) = if (r == null) "거리 불확실" else "약 ${r[0].toInt()}~${r[1].toInt()}m"

/** TTC는 크기 변화율(보정과 무관)이 안정적으로 잡힐 때만 0.5초 단위로 */
fun ttcShown(tr: Track): Double? =
    if (tr.lhOk && tr.hits >= 8 && tr.closing > 0.5 && tr.ttc < 6) round(tr.ttc * 2) / 2 else null

/* ───────────────────────── 시스템 상태 · 경고 정책 ───────────────────────── */

enum class Risk(val label: String) { UNKNOWN("판단 불가"), LOW("징후 없음"), CAUTION("주의"), HIGH("위험") }

/** 시스템이 '볼 수 없는' 상태면 이유 문자열 (→ 회색). null이면 판단 가능 */
fun unknownReason(modelReady: Boolean, initError: String?, frameAgeMs: Long, procMs: Long, meanLuma: Double, lensBusy: Boolean): String? = when {
    initError != null -> "AI 모델 오류: ${initError.take(60)}"
    !modelReady -> "AI 모델 준비 중"
    lensBusy -> "렌즈 전환 중"
    frameAgeMs > 1200 -> "카메라/AI 응답 없음 (${frameAgeMs / 100 / 10.0}초)"
    procMs > 650 -> "처리 지연 ${procMs}ms · 결과 신뢰 불가"
    meanLuma < 18 -> "영상이 너무 어둡거나 가려짐"
    else -> null
}

class AlertOut(val alarmOn: Boolean, val beep: Int, val vibrate: Boolean, val speech: String?, val speechPri: Int, val worst: Track?)

/** 경고 정책: 빨강은 150ms 이상 지속 시, 사라져도 1초 유지. 주의는 대상당 한 번. 안내는 VRU 첫 등장 시 */
class AlertPolicy {
    var alarmOn = false; private set
    private var dangerSince = 0L; private var lastDanger = 0L; private var lastBeep = 0L; private var beepHi = false
    private var lastSpeak = 0L; private var lastPri = 0

    fun reset() { alarmOn = false; dangerSince = 0 }

    private fun canSpeak(now: Long, pri: Int): Boolean {
        val gap = when (pri) { 3 -> 2200; 2 -> 2500; else -> 7000 }
        if (now - lastSpeak < gap && pri <= lastPri) return false
        lastSpeak = now; lastPri = pri; return true
    }

    fun sideOf(tr: Track, lanes: Lanes, motion: Motion, ego: Double?): String {
        val e = lanes.edges(tr.Z, motion, ego); val m = (e[0] + e[1]) / 2
        return if (tr.X - m < -1.4) "왼쪽" else if (tr.X - m > 1.4) "오른쪽" else "전방"
    }

    fun update(now: Long, tracks: List<Track>, lanes: Lanes, motion: Motion, ego: Double?, judging: Boolean): AlertOut {
        if (!judging) { alarmOn = false; dangerSince = 0; return AlertOut(false, 0, false, null, 0, null) }
        var worst: Track? = null
        for (tr in tracks) if (tr.hits >= 3 && (worst == null || tr.level > worst.level || (tr.level == worst.level && tr.ttc < worst.ttc))) worst = tr
        val lvl = worst?.level ?: 0
        if (lvl == 3) { if (dangerSince == 0L) dangerSince = now; if (now - dangerSince > 150) { alarmOn = true; lastDanger = now } }
        else dangerSince = 0
        if (alarmOn && lvl < 3 && now - lastDanger > 1000) alarmOn = false
        if (alarmOn && worst != null) {
            var beep = 0; var vib = false
            if (now - lastBeep > 260) { lastBeep = now; beepHi = !beepHi; beep = if (beepHi) 1 else 2; vib = beepHi }
            val side = sideOf(worst, lanes, motion, ego)
            val sp = "$side ${worst.kind.label}, 브레이크"
            return AlertOut(true, beep, vib, if (canSpeak(now, 3)) sp else null, 3, worst)
        }
        for (tr in tracks) {
            if (tr.level == 2 && !tr.warned && tr.hits >= 4) {
                tr.warned = true
                val side = sideOf(tr, lanes, motion, ego)
                val sp = when (tr.why) { "차간 거리 부족" -> "차간 거리 주의"; "끼어들기 주의" -> "$side 끼어들기 주의"; else -> "$side ${tr.kind.label} 주의" }
                return AlertOut(false, 3, false, if (canSpeak(now, 2)) sp else null, 2, worst)
            }
            if (tr.level == 0 && tr.warned) tr.warned = false
        }
        if ((ego ?: 0.0) > 3) for (tr in tracks) {
            if (!tr.said && tr.hits >= 5 && tr.level <= 1 && tr.kind.grp == Grp.VRU && tr.Z < 35) {
                tr.said = true
                val sd = sideOf(tr, lanes, motion, ego)
                val sp = "${sd}에 ${tr.kind.label}가 있습니다"
                return AlertOut(false, 0, false, if (canSpeak(now, 1)) sp else null, 1, worst)
            }
        }
        return AlertOut(false, 0, false, null, 0, worst)
    }
}

/** 전체 위험 단계와 한 줄 설명 */
fun overall(unknown: String?, alarmOn: Boolean, tracks: List<Track>, p: Params, lanes: Lanes, motion: Motion, ego: Double?, policy: AlertPolicy): Pair<Risk, String> {
    if (unknown != null) return Risk.UNKNOWN to unknown
    val vis = tracks.filter { it.hits >= 3 }
    val top = vis.filter { it.level >= 2 }.sortedWith(compareBy({ -it.level }, { it.ttc })).firstOrNull()
    if (alarmOn || (top != null && top.level == 3)) {
        val t = top ?: vis.maxByOrNull { it.level }
        val s = if (t != null) "${policy.sideOf(t, lanes, motion, ego)} ${t.kind.label} ${fmtDist(distRange(t, p, lanes))} · 충돌 위험" else "충돌 위험"
        return Risk.HIGH to s
    }
    if (top != null) {
        val tt = ttcShown(top)
        return Risk.CAUTION to "${policy.sideOf(top, lanes, motion, ego)} ${top.kind.label} ${fmtDist(distRange(top, p, lanes))} · ${top.why}" + (if (tt != null) " · 약 ${tt}초" else "")
    }
    val lead = vis.filter { it.inPath }.minByOrNull { it.dist }
    val laneNote = if (p.laneOn && lanes.basis == 0) " · 차선 미인식(직진 가정)" else ""
    if (lead != null) return Risk.LOW to "내 차선 ${lead.kind.label} ${fmtDist(distRange(lead, p, lanes))} · ${lead.why}$laneNote"
    return Risk.LOW to "진로에 위험 징후 없음 (안전 보장 아님)$laneNote"
}
