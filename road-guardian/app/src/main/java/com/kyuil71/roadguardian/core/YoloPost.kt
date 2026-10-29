package com.kyuil71.roadguardian.core

import java.nio.FloatBuffer

/**
 * COCO 사전학습 YOLOv8n 출력 해석 (안드로이드 의존성 없음).
 * 출력: 1×84×8400 (또는 전치 1×8400×84). 0~3 = cx,cy,w,h (640 입력 좌표), 4~83 = 80개 클래스 점수.
 * 레터박스(scale, padX, padY)를 되돌리고 원본 영상 좌표 + 오프셋(ox, oy)으로 돌려준다.
 */
object YoloPost {
    // COCO 클래스 번호 → 우리 분류
    private val ids = intArrayOf(0, 1, 2, 3, 5, 7)
    private val kinds = arrayOf(Kind.PERSON, Kind.BICYCLE, Kind.CAR, Kind.MOTORCYCLE, Kind.BUS, Kind.TRUCK)

    fun decode(out: FloatBuffer, shape: LongArray, scale: Double, padX: Double, padY: Double,
               ox: Double, oy: Double, threshold: Double = 0.30): List<Det> {
        require(shape.size == 3 && shape[0] == 1L) { "YOLO 출력 형태 미지원: ${shape.contentToString()}" }
        val chFirst = shape[1] == 84L
        val count = (if (chFirst) shape[2] else shape[1]).toInt()
        val ch = (if (chFirst) shape[1] else shape[2]).toInt()
        require(ch == 84 && count in 100..30000 && out.limit() >= count * ch) { "YOLO 출력 배치 미지원: ${shape.contentToString()}" }
        fun v(i: Int, c: Int): Float = if (chFirst) out.get(c * count + i) else out.get(i * ch + c)
        val cand = ArrayList<Det>(64)
        for (i in 0 until count) {
            var best = threshold.toFloat(); var k = -1
            for (j in ids.indices) { val s = v(i, ids[j] + 4); if (s.isFinite() && s > best) { best = s; k = j } }
            if (k < 0) continue
            val cx = v(i, 0); val cy = v(i, 1); val w = v(i, 2); val h = v(i, 3)
            if (!cx.isFinite() || !cy.isFinite() || !(w > 0f) || !(h > 0f)) continue
            val x0 = (cx - w / 2 - padX) / scale + ox; val y0 = (cy - h / 2 - padY) / scale + oy
            val bw = w / scale; val bh = h / scale
            if (bw < 4 || bh < 4) continue
            cand += Det(Box(x0, y0, bw, bh), kinds[k], best.toDouble())
        }
        return nms(cand)
    }

    /** 같은 분류끼리 겹침 0.45 이상이면 점수 높은 것만 */
    fun nms(cand: List<Det>, thr: Double = 0.45, maxN: Int = 30): List<Det> {
        val picked = ArrayList<Det>()
        for (d in cand.sortedByDescending { it.conf }.take(200)) {
            if (picked.none { it.kind == d.kind && iou(it.box, d.box) > thr }) { picked += d; if (picked.size >= maxN) break }
        }
        return picked
    }
}
