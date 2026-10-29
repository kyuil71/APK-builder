package com.kyuil71.roadguardian

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.kyuil71.roadguardian.core.Grp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// 위험 단계 색: 진로 밖(흰 반투명) · 진로 안(초록) · 주의(노랑) · 위험(빨강). 회색은 '판단 불가' 전용
val LEVEL_COLORS = intArrayOf(Color.argb(150, 230, 236, 242), Color.rgb(74, 222, 128), Color.rgb(251, 191, 36), Color.rgb(239, 68, 68))
val PATH_COLORS = intArrayOf(Color.rgb(56, 189, 248), Color.rgb(56, 189, 248), Color.rgb(251, 191, 36), Color.rgb(239, 68, 68))

/** 카메라 위 오버레이: 진로, 차선, 진행 방향, 제동 한계선, 인식 박스(+신뢰도·거리 범위) */
class OverlayView(ctx: Context) : View(ctx) {
    @Volatile var snap: Snapshot? = null
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val dash = DashPathEffect(floatArrayOf(12 * dp, 10 * dp), 0f)

    fun show(s: Snapshot?) { snap = s; postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        val s = snap ?: return
        // FIT_CENTER 와 같은 배치
        val scale = min(width.toFloat() / s.vw, height.toFloat() / s.vh)
        val ox = (width - s.vw * scale) / 2; val oy = (height - s.vh * scale) / 2
        fun X(u: Double) = (ox + u * scale).toFloat()
        fun Y(v: Double) = (oy + v * scale).toFloat()
        fun proj(x: Double, d: Double): FloatArray? { val q = s.cam.project(x, d) ?: return null; return floatArrayOf(X(q[0]), Y(q[1])) }
        c.save(); c.clipRect(ox, oy, ox + s.vw * scale, oy + s.vh * scale)

        // 내 진로 (차선 사이)
        val path = Path(); var first = true
        for (k in s.corrD.indices) { val q = proj(s.corrL[k], s.corrD[k]) ?: continue; if (first) { path.moveTo(q[0], q[1]); first = false } else path.lineTo(q[0], q[1]) }
        for (k in s.corrD.indices.reversed()) { val q = proj(s.corrR[k], s.corrD[k]) ?: continue; path.lineTo(q[0], q[1]) }
        path.close()
        pt.style = Paint.Style.FILL; pt.color = PATH_COLORS[s.pathLevel]; pt.alpha = if (s.pathLevel >= 2) 60 else 32
        if (!first) c.drawPath(path, pt)
        // 인식된 차선 도색 점
        pt.color = Color.argb(200, 74, 222, 128)
        var i = 0; while (i + 1 < s.lanePts.size) { c.drawRect(X(s.lanePts[i].toDouble()) - 2, Y(s.lanePts[i + 1].toDouble()) - 2, X(s.lanePts[i].toDouble()) + 2, Y(s.lanePts[i + 1].toDouble()) + 2, pt); i += 2 }
        // 차선 (실선 = 인식, 점선 = 반대편에서 추정)
        pt.style = Paint.Style.STROKE; pt.strokeCap = Paint.Cap.ROUND
        s.laneLines.forEachIndexed { idx, (ds, xs) ->
            val lp = Path(); var f0 = true
            for (k in ds.indices) { val q = proj(xs[k], ds[k]) ?: continue; if (f0) { lp.moveTo(q[0], q[1]); f0 = false } else lp.lineTo(q[0], q[1]) }
            val inf = s.laneInferred[idx]
            pt.strokeWidth = 4 * dp; pt.color = if (inf) Color.argb(130, 74, 222, 128) else Color.argb(235, 74, 222, 128)
            pt.pathEffect = if (inf) dash else null
            c.drawPath(lp, pt)
        }
        pt.pathEffect = null
        // 진행 방향: 진로 중심선 + 화살표
        val cp = Path(); var f1 = true
        for (k in s.corrD.indices) { if (s.corrD[k] > 50) break; val q = proj((s.corrL[k] + s.corrR[k]) / 2, s.corrD[k]) ?: continue; if (f1) { cp.moveTo(q[0], q[1]); f1 = false } else cp.lineTo(q[0], q[1]) }
        pt.strokeWidth = 3 * dp; pt.color = Color.argb(220, 255, 255, 255); pt.pathEffect = dash; c.drawPath(cp, pt); pt.pathEffect = null
        for (D in doubleArrayOf(8.0, 16.0, 26.0, 38.0)) {
            val k = s.corrD.indexOfFirst { it >= D }; if (k < 1 || k >= s.corrD.size - 1) continue
            val a = proj((s.corrL[k] + s.corrR[k]) / 2, s.corrD[k]) ?: continue
            val b = proj((s.corrL[k + 1] + s.corrR[k + 1]) / 2, s.corrD[k + 1]) ?: continue
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val ln = hypot(dx, dy).coerceAtLeast(1f)
            val ux = dx / ln; val uy = dy / ln; val nx = -uy; val ny = ux
            val sz = (260 / D).toFloat().coerceIn(6f, 24f) * dp / 1.5f
            val ar = Path(); ar.moveTo(a[0] - ux * sz * .4f + nx * sz * .6f, a[1] - uy * sz * .4f + ny * sz * .6f)
            ar.lineTo(a[0] + ux * sz * .4f, a[1] + uy * sz * .4f); ar.lineTo(a[0] - ux * sz * .4f - nx * sz * .6f, a[1] - uy * sz * .4f - ny * sz * .6f)
            pt.color = Color.WHITE; c.drawPath(ar, pt)
        }
        // 제동 한계선: 이 안에 '서 있는 물체'가 있으면 빨간 경고가 되는 거리
        s.brakeD?.let { d ->
            val k = s.corrD.indexOfFirst { it >= d }
            if (k >= 0) { val a = proj(s.corrL[k], d); val b = proj(s.corrR[k], d); if (a != null && b != null) { pt.strokeWidth = 2.5f * dp; pt.color = Color.argb(220, 239, 68, 68); c.drawLine(a[0], a[1], b[0], b[1], pt) } }
        }
        // 수평선
        val yh = s.cam.horizonY()
        if (yh > 0 && yh < s.vh) { pt.strokeWidth = 1 * dp; pt.color = Color.argb(110, 255, 255, 255); pt.pathEffect = DashPathEffect(floatArrayOf(6 * dp, 6 * dp), 0f); c.drawLine(X(0.0), Y(yh), X(s.vw.toDouble()), Y(yh), pt); pt.pathEffect = null }
        // 차 내부 가림 영역 (분석 제외)
        if (s.cabinPoly.size >= 4) {
            val cp2 = Path(); cp2.moveTo(X(0.0), Y(s.vh.toDouble()))
            var k = 0; while (k + 1 < s.cabinPoly.size) { cp2.lineTo(X(s.cabinPoly[k].toDouble()), Y(s.cabinPoly[k + 1].toDouble())); k += 2 }
            cp2.lineTo(X(s.vw.toDouble()), Y(s.vh.toDouble())); cp2.close()
            pt.style = Paint.Style.FILL; pt.color = Color.argb(120, 0, 0, 0); c.drawPath(cp2, pt)
            val edge = Path(); k = 0
            while (k + 1 < s.cabinPoly.size) { val xx = X(s.cabinPoly[k].toDouble()); val yy = Y(s.cabinPoly[k + 1].toDouble()); if (k == 0) edge.moveTo(xx, yy) else edge.lineTo(xx, yy); k += 2 }
            pt.style = Paint.Style.STROKE; pt.strokeWidth = 2 * dp; pt.color = Color.argb(200, 251, 146, 60); pt.pathEffect = DashPathEffect(floatArrayOf(10 * dp, 8 * dp), 0f)
            c.drawPath(edge, pt); pt.pathEffect = null
            val mid = s.cabinPoly.size / 2 / 2 * 2
            if (s.cabinPoly[mid + 1] < s.vh - 40) {
                pt.style = Paint.Style.FILL; pt.color = Color.argb(220, 251, 146, 60); pt.textSize = 14 * sp; pt.typeface = Typeface.DEFAULT_BOLD
                val label = "차 내부 · 분석 제외" + (if (s.cabinLearned) " (학습됨)" else "")
                c.drawText(label, X(s.vw / 2.0) - pt.measureText(label) / 2, Y(s.cabinPoly[mid + 1].toDouble()) + 22 * dp, pt)
            }
        }
        // 원거리 확대 영역
        s.crop?.let { b -> pt.strokeWidth = 1 * dp; pt.color = Color.argb(80, 255, 255, 255); pt.pathEffect = DashPathEffect(floatArrayOf(4 * dp, 6 * dp), 0f); c.drawRect(X(b.x), Y(b.y), X(b.r), Y(b.b), pt); pt.pathEffect = null }

        // 인식 박스
        pt.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); pt.textSize = 15 * sp
        for (t in s.tracks) {
            val col = LEVEL_COLORS[t.level]
            val r = RectF(X(t.box.x), Y(t.box.y), X(t.box.r), Y(t.box.b))
            pt.style = Paint.Style.STROKE; pt.color = col; pt.strokeWidth = (if (t.level == 3) 4f else if (t.level == 0) 1.5f else 2.5f) * dp
            c.drawRoundRect(r, 6f, 6f, pt)
            val label = if (t.level == 0) "${t.kind.label} ${(t.conf * 100).toInt()}%"
                else "${t.kind.label} ${(t.conf * 100).toInt()}% · ${t.dist}" + (if (t.level >= 2 && t.ttc != null) " · 약 ${t.ttc}초" else "")
            val tw = pt.measureText(label) + 12 * dp; val th = 21 * dp
            val top = max(oy, r.top - th - 2)
            pt.style = Paint.Style.FILL
            pt.color = if (t.level == 0) Color.argb(150, 8, 10, 13) else col
            c.drawRoundRect(RectF(r.left, top, r.left + tw, top + th), 5f, 5f, pt)
            pt.color = if (t.level == 0) Color.argb(230, 230, 236, 242) else if (t.level == 3) Color.WHITE else Color.rgb(17, 17, 17)
            c.drawText(label, r.left + 6 * dp, top + th - 6 * dp, pt)
        }
        c.restore()
    }
}

/** 위에서 내려다본 지도: 내 차, 진로, 차선, 제동 한계선, 주변 물체 */
class BirdView(ctx: Context) : View(ctx) {
    @Volatile var snap: Snapshot? = null
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    fun show(s: Snapshot?) { snap = s; postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(14, 17, 22))
        val s = snap ?: return
        val far = s.far
        val m = 10 * dp
        val h = height - 2 * m; val w = width - 2 * m
        val egoLen = 4.5
        val sc = min(h / (far + egoLen), w / 16.0).toFloat()      // 가로 ±8m
        val cx = width / 2f; val by = height - m - (egoLen * sc).toFloat()
        fun X(x: Double) = (cx + x * sc).toFloat()
        fun Y(d: Double) = (by - d * sc).toFloat()
        // 거리 눈금
        pt.style = Paint.Style.STROKE; pt.strokeWidth = 1f; pt.color = Color.rgb(32, 40, 50)
        pt.textSize = 13 * sp; pt.typeface = Typeface.DEFAULT
        var d = 10.0
        while (d <= far) { c.drawLine(m, Y(d), width - m, Y(d), pt); d += if (far > 80) 20.0 else 10.0 }
        pt.style = Paint.Style.FILL; pt.color = Color.rgb(90, 102, 115)
        d = 20.0; while (d <= far) { c.drawText("${d.toInt()}m", m + 2, Y(d) - 3, pt); d += if (far > 80) 40.0 else 20.0 }
        // 진로
        val path = Path()
        for (k in s.corrD.indices) { val x = X(s.corrL[k]); val y = Y(s.corrD[k]); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        for (k in s.corrD.indices.reversed()) path.lineTo(X(s.corrR[k]), Y(s.corrD[k]))
        path.close()
        pt.color = PATH_COLORS[s.pathLevel]; pt.alpha = if (s.pathLevel >= 2) 80 else 45; c.drawPath(path, pt)
        // 차선
        pt.style = Paint.Style.STROKE; pt.strokeWidth = 3 * dp
        s.laneLines.forEachIndexed { idx, (ds, xs) ->
            val lp = Path(); for (k in ds.indices) { if (k == 0) lp.moveTo(X(xs[k]), Y(ds[k])) else lp.lineTo(X(xs[k]), Y(ds[k])) }
            pt.color = if (s.laneInferred[idx]) Color.argb(110, 74, 222, 128) else Color.rgb(74, 222, 128); c.drawPath(lp, pt)
        }
        // 진행 방향 중심선
        val cpth = Path(); for (k in s.corrD.indices) { val x = X((s.corrL[k] + s.corrR[k]) / 2); val y = Y(s.corrD[k]); if (k == 0) cpth.moveTo(x, y) else cpth.lineTo(x, y) }
        pt.strokeWidth = 1.5f * dp; pt.color = Color.argb(170, 255, 255, 255); pt.pathEffect = DashPathEffect(floatArrayOf(8 * dp, 8 * dp), 0f); c.drawPath(cpth, pt); pt.pathEffect = null
        // 제동 한계선
        s.brakeD?.let { bd -> val k = s.corrD.indexOfFirst { it >= bd }; if (k >= 0) { pt.strokeWidth = 3 * dp; pt.color = Color.rgb(239, 68, 68); c.drawLine(X(s.corrL[k]), Y(bd), X(s.corrR[k]), Y(bd), pt) } }
        // 내 차 (폰 위치 = 0, 앞 범퍼 = bonnet)
        pt.style = Paint.Style.FILL; pt.color = Color.rgb(56, 189, 248)
        c.drawRoundRect(RectF(X(-0.9), Y(Settings.num("bonnet")), X(0.9), Y(-egoLen + Settings.num("bonnet"))), 6f, 6f, pt)
        // 물체
        pt.textSize = 13 * sp; pt.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        for (t in s.tracks) {
            val col = LEVEL_COLORS[t.level]
            val hw = (if (t.kind.grp == Grp.VRU) 0.35 else t.kind.w / 2)
            val len = if (t.kind.grp == Grp.VRU) 0.7 else t.kind.len
            pt.style = Paint.Style.FILL; pt.color = col
            c.drawRoundRect(RectF(X(t.X - hw), Y(t.Z + len), X(t.X + hw), Y(t.Z)), 4f, 4f, pt)
            if (t.level >= 1) {   // 3초 뒤 예상 상대 위치
                pt.style = Paint.Style.STROKE; pt.strokeWidth = 2 * dp
                c.drawLine(X(t.X), Y(t.Z), X(t.X + t.vX * 3), Y(max(0.0, t.Z + t.vZ * 3)), pt)
            }
        }
    }
}
