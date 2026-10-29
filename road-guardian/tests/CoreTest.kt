import com.kyuil71.roadguardian.core.*
import kotlin.math.*

/* PC에서 실행하는 핵심 로직 테스트 (안드로이드·모델 없이). 실행: tests/run_core_tests.sh */

var fails = 0
val P = Params()
val lanes = Lanes(P)
val motion = Motion()
val LV = arrayOf("진로밖", "진로안", "주의", "위험")

fun setLanes() {
    lanes.L = LaneB(-1.65, 0.0, 0.0).also { it.dMin = 5.0; it.dMax = 40.0; it.conf = 1.0 }
    lanes.R = LaneB(1.65, 0.0, 0.0).also { it.dMin = 5.0; it.dMax = 40.0; it.conf = 1.0 }
}

fun chk(name: String, kmh: Double, kind: Kind = Kind.CAR, X: Double = 0.0, Z: Double = 30.0, vX: Double = 0.0, vZ: Double = 0.0, hits: Int = 12, vararg expect: Int) {
    val tr = Track(1, Box(0.0, 0.0, 10.0, 10.0), kind, 0.9, X, Z, vX, vZ, 0.0)
    tr.hits = hits
    assess(tr, kmh / 3.6, P, lanes, motion)
    val ok = tr.level in expect
    if (!ok) fails++
    println("${if (ok) "OK " else "XX "} ${name.padEnd(30)} → ${LV[tr.level]} (${tr.why}) 거리 ${tr.dist.roundToInt()}m TTC ${if (tr.ttc.isFinite()) "%.1f".format(tr.ttc) else "∞"}")
}

fun scenarios() {
    setLanes()
    println("— 내 차선 안")
    chk("60km/h, 정지 차량 90m", 60.0, Z = 90.0, vZ = -16.7, expect = intArrayOf(1))
    chk("60km/h, 정지 차량 60m", 60.0, Z = 60.0, vZ = -16.7, expect = intArrayOf(2))
    chk("60km/h, 정지 차량 38m", 60.0, Z = 38.0, vZ = -16.7, expect = intArrayOf(3))
    chk("100km/h, 정체 끝 100m", 100.0, Z = 100.0, vZ = -27.8, expect = intArrayOf(2, 3))
    chk("60km/h, 앞차 같은 속도 25m", 60.0, Z = 25.0, expect = intArrayOf(1))
    chk("60km/h, 앞차가 더 빠름 20m", 60.0, Z = 20.0, vZ = 3.0, expect = intArrayOf(1))
    chk("60km/h, 앞차 바짝 붙음 8m", 60.0, Z = 9.0, expect = intArrayOf(2))
    chk("60km/h, 앞차 감속 접근 30m", 60.0, Z = 30.0, vZ = -9.0, expect = intArrayOf(2))
    chk("60km/h, 앞차 감속 접근 18m", 60.0, Z = 18.0, vZ = -9.0, expect = intArrayOf(3))
    chk("60km/h, 앞차 천천히 접근 40m", 60.0, Z = 40.0, vZ = -2.0, expect = intArrayOf(1))
    chk("정차 중, 앞차 6m", 0.0, Z = 6.0, expect = intArrayOf(1))
    chk("정체 서행 5km/h, 앞차 4m 접근", 5.0, Z = 6.0, vZ = -1.4, expect = intArrayOf(1))
    chk("서행 10km/h, 앞차 급정지 2m", 10.0, Z = 3.6, vZ = -2.8, expect = intArrayOf(2, 3))
    chk("40km/h, 서 있는 보행자 30m", 40.0, Kind.PERSON, Z = 30.0, vZ = -11.0, expect = intArrayOf(2))
    chk("40km/h, 서 있는 보행자 18m", 40.0, Kind.PERSON, Z = 18.0, vZ = -11.0, expect = intArrayOf(3))
    println("— 진로 밖")
    chk("60km/h, 옆 차선 정지 차량 30m", 60.0, X = 3.4, Z = 30.0, vZ = -16.7, expect = intArrayOf(0))
    chk("60km/h, 갓길 주차 차량 20m", 60.0, X = 2.7, Z = 20.0, vZ = -16.7, expect = intArrayOf(0))
    chk("60km/h, 반대 차선 마주 오는 차", 60.0, X = -3.5, Z = 40.0, vZ = -30.0, expect = intArrayOf(0))
    chk("40km/h, 인도 보행자 25m", 40.0, Kind.PERSON, X = 4.5, Z = 25.0, vZ = -11.0, expect = intArrayOf(0))
    chk("40km/h, 차선 끝 바로 옆 보행자 15m", 40.0, Kind.PERSON, X = 2.1, Z = 15.0, vZ = -11.0, expect = intArrayOf(2))
    chk("60km/h, 옆 차선 차 더 빠름", 60.0, X = 3.3, Z = 10.0, vZ = 4.0, expect = intArrayOf(0))
    println("— 진로 진입 예측")
    chk("40km/h, 횡단 보행자 30m", 40.0, Kind.PERSON, X = 4.0, Z = 30.0, vX = -1.4, vZ = -11.0, expect = intArrayOf(2))
    chk("40km/h, 횡단 보행자 20m", 40.0, Kind.PERSON, X = 2.6, Z = 20.0, vX = -1.4, vZ = -11.0, expect = intArrayOf(3))
    chk("40km/h, 보행자 진로에서 멀어짐", 40.0, Kind.PERSON, X = 3.0, Z = 20.0, vX = 1.4, vZ = -11.0, expect = intArrayOf(0))
    chk("60km/h, 완만한 끼어들기 15m", 60.0, X = 3.0, Z = 15.0, vX = -1.5, vZ = -5.0, expect = intArrayOf(2))
    chk("60km/h, 급 끼어들기 9m", 60.0, X = 2.6, Z = 9.0, vX = -2.0, vZ = -6.0, expect = intArrayOf(3))
    chk("60km/h, 옆 차 흔들림(오검출)", 60.0, X = 3.2, Z = 15.0, vX = -0.2, expect = intArrayOf(0))
    println("— 차선 미인식 (회전 진로 추정)")
    lanes.L = null; lanes.R = null
    chk("60km/h 직진, 정지 차량 38m", 60.0, Z = 38.0, vZ = -16.7, expect = intArrayOf(3))
    motion.yawSlow = 0.25
    chk("60km/h 좌회전, 정면 외곽 정지 차", 60.0, X = 0.0, Z = 38.0, vZ = -16.7, expect = intArrayOf(0))
    chk("60km/h 좌회전, 커브 안쪽 정지 차", 60.0, X = -10.7, Z = 38.0, vZ = -16.7, expect = intArrayOf(3))
    motion.yawSlow = 0.0
}

/* 판단 불가 규칙: 실패를 안전으로 보지 않는다 */
fun unknownRules() {
    println("— 판단 불가 (회색)")
    val cases = listOf(
        Triple("모델 준비 중", unknownReason(false, null, 50, 80, 100.0, false), true),
        Triple("모델 오류", unknownReason(true, "load fail", 50, 80, 100.0, false), true),
        Triple("프레임 1.5초 정지", unknownReason(true, null, 1500, 80, 100.0, false), true),
        Triple("처리 700ms 지연", unknownReason(true, null, 50, 700, 100.0, false), true),
        Triple("렌즈 가림/어둠", unknownReason(true, null, 50, 80, 10.0, false), true),
        Triple("렌즈 전환 중", unknownReason(true, null, 50, 80, 100.0, true), true),
        Triple("정상", unknownReason(true, null, 50, 80, 100.0, false), false))
    for ((n, r, expectUnknown) in cases) {
        val ok = (r != null) == expectUnknown
        if (!ok) fails++
        println("${if (ok) "OK " else "XX "} ${n.padEnd(14)} → ${r ?: "판단 가능"}")
    }
    // 판단 불가일 때 경고 정책은 절대 '안전'을 내지 않고 경고도 멈춘다
    val pol = AlertPolicy()
    val (risk, msg) = overall("AI 모델 준비 중", false, emptyList(), P, lanes, motion, 16.0, pol)
    val ok = risk == Risk.UNKNOWN
    if (!ok) fails++
    println("${if (ok) "OK " else "XX "} 판단 불가 시 전체 단계 → ${risk.label} ($msg)")
}

/* 경고 정책: 빨강 지속, 주의 1회, 서행 억제 */
fun alertPolicy() {
    println("— 경고 정책")
    setLanes()
    val pol = AlertPolicy()
    val tr = Track(7, Box(0.0, 0.0, 10.0, 10.0), Kind.CAR, 0.9, 0.0, 36.0, 0.0, -16.7, 0.0); tr.hits = 10
    assess(tr, 16.7, P, lanes, motion)
    val a1 = pol.update(1000, listOf(tr), lanes, motion, 16.7, true)
    val a2 = pol.update(1200, listOf(tr), lanes, motion, 16.7, true)
    tr.level = 1
    val a3 = pol.update(1800, listOf(tr), lanes, motion, 16.7, true)
    val a4 = pol.update(2400, listOf(tr), lanes, motion, 16.7, true)
    val ok = !a1.alarmOn && a2.alarmOn && a3.alarmOn && !a4.alarmOn && a2.speech != null
    if (!ok) fails++
    println("${if (ok) "OK " else "XX "} 위험 150ms 지속 후 경보 → 해소 1초 뒤 해제 (${a1.alarmOn},${a2.alarmOn},${a3.alarmOn},${a4.alarmOn}) 음성: ${a2.speech}")
    val t2 = Track(8, Box(0.0, 0.0, 10.0, 10.0), Kind.CAR, 0.9, 0.0, 60.0, 0.0, -16.7, 0.0); t2.hits = 10
    assess(t2, 16.7, P, lanes, motion)
    val b1 = pol.update(10000, listOf(t2), lanes, motion, 16.7, true)
    val b2 = pol.update(10100, listOf(t2), lanes, motion, 16.7, true)
    val ok2 = t2.level == 2 && b1.beep == 3 && b2.beep == 0
    if (!ok2) fails++
    println("${if (ok2) "OK " else "XX "} 주의는 대상당 한 번만 알림 (1회차 ${b1.beep}, 2회차 ${b2.beep}) 음성: ${b1.speech}")
    val off = pol.update(20000, listOf(tr), lanes, motion, 16.7, false)
    val ok3 = !off.alarmOn
    if (!ok3) fails++
    println("${if (ok3) "OK " else "XX "} 판단 불가 상태에서는 경보 중단")
    val tt = Track(9, Box(0.0, 0.0, 10.0, 10.0), Kind.CAR, 0.9, 0.0, 40.0, 0.0, -16.7, 0.0); tt.hits = 12; tt.ground = true
    val r = distRange(tt, P, lanes)
    val ok4 = r != null && r[0] < 38.2 && r[1] > 38.2
    if (!ok4) fails++
    println("${if (ok4) "OK " else "XX "} 거리 범위 표시 (참값 38m) → ${fmtDist(r)}")
}

/* ── 합성 도로 영상: 카메라 기하 그대로 그리고, 달리며 점선이 흘러가게 ── */
class Case(val name: String, val paint: Int, val noise: Int, val blur: Boolean, val hood: Boolean, val c: Double, val estP: Double, val adjL: Boolean)

fun roadTest() {
    println("— 차선 인식 (달리는 합성 도로, 시속 58km)")
    val cases = listOf(
        Case("선명한 직선", 225, 30, false, false, 0.0, 5.0, false),
        Case("가혹: 점선+보닛+그림자", 225, 30, false, true, 0.0008, 5.0, false),
        Case("가혹 + 기울기 1° 틀림", 225, 30, false, true, 0.0008, 4.0, false),
        Case("가혹 + 기울기 1° 반대", 225, 30, false, true, 0.0008, 6.0, false),
        Case("닳은 차선+흐림+보닛", 140, 40, true, true, 0.0008, 5.0, true),
        Case("급커브 R150", 200, 30, true, true, 0.0033, 5.0, true),
        Case("기울기 3° 틀림", 200, 30, true, true, 0.0008, 2.0, true),
        Case("기울기 3° 반대", 200, 30, true, true, 0.0008, 8.0, true),
        Case("차선 없는 노면", 0, 40, false, false, 0.0, 5.0, false))
    val SW = 1280; val SH = 720; val W = 480; val H = 270
    val FOV = 70.0; val CAMH = 1.25; val TRUEP = 5 * DEG
    val fS = (SW / 2.0) / tan(FOV * DEG / 2); val f = fS * W / SW
    for (C in cases) {
        val p = Params(); val ln = Lanes(p)
        val tL = { D: Double -> -1.7 + 0.01 * D + C.c * D * D }; val tR = { D: Double -> 1.6 + 0.01 * D + C.c * D * D }
        var seed = 11L
        val rnd = { seed = (seed * 16807) % 2147483647; seed / 2147483647.0 }
        val px = IntArray(W * H); val tmp = IntArray(W * H)
        var both = 0; var lock = -1
        val N = 150
        for (i in 0 until N) {
            val tv = i * 16 * 0.12
            for (y in 0 until H) {
                for (x in 0 until W) {
                    var acc = 0.0; var accG = 0.0; var accB = 0.0
                    for (sx in 0 until 3) for (sy in 0 until 2) {    // 원본 해상도 근사 (부분 화소)
                        val yy = (y + (sy + 0.5) / 2) * SH / H; val xx = (x + (sx + 0.5) / 3) * SW / SW * SW / W
                        val ang = TRUEP + atan((yy - SH / 2.0) / fS)
                        val D = if (ang > 0.002) CAMH / tan(ang) else Double.POSITIVE_INFINITY
                        var r: Double; var g: Double; var b: Double
                        if (!D.isFinite() || D > 300) { r = 170.0; g = 200.0; b = 230.0 }
                        else {
                            val zc = D * cos(TRUEP) + CAMH * sin(TRUEP)
                            val X = (xx - SW / 2.0) * zc / fS; val Dw = D + tv; val pp = C.paint.toDouble()
                            val n = (rnd() - 0.5) * C.noise
                            r = 95 + n + 10 * sin(X * 0.7 + Dw * 0.3); g = r; b = r
                            if (C.paint > 0) {
                                if (abs(X - tL(D)) < 0.075) { r = pp; g = pp * 0.8; b = pp * 0.3 }
                                if (abs(X - tR(D)) < 0.075 && Dw % 12 < 6) { r = pp; g = pp; b = pp }
                                if (abs(X - tR(D) - 3.3) < 0.075 && Dw % 12 < 6) { r = pp; g = pp; b = pp }
                                if (C.adjL && abs(X - tL(D) + 3.3) < 0.075) { r = pp * 0.9; g = r; b = r }
                            }
                            if (Dw % 60 > 14 && Dw % 60 < 20 && X > -2 && X < 3) { r *= 0.55; g *= 0.55; b *= 0.55 }
                        }
                        if (C.hood && yy > SH - 110) { val s = sin(xx * 0.05 + yy * 0.02); r = 40 + (if (s > 0.92) 170.0 else 0.0); g = r; b = r }
                        acc += r; accG += g; accB += b
                    }
                    val R = clamp(acc / 6, 0.0, 255.0).toInt(); val G = clamp(accG / 6, 0.0, 255.0).toInt(); val B = clamp(accB / 6, 0.0, 255.0).toInt()
                    px[y * W + x] = (255 shl 24) or (R shl 16) or (G shl 8) or B
                }
            }
            if (C.blur) {   // 3×3 상자 흐림
                for (y in 1 until H - 1) for (x in 1 until W - 1) {
                    var r = 0; var g = 0; var b = 0
                    for (dy in -1..1) for (dx in -1..1) { val c = px[(y + dy) * W + x + dx]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
                    tmp[y * W + x] = (255 shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
                }
                for (k in 0 until W * H) if (tmp[k] != 0) px[k] = tmp[k]
            }
            val pitch = C.estP * DEG + ln.pitchAuto
            val pts = ln.detectPoints(px, W, H, f, pitch, SW.toDouble() / W, emptyList())
            ln.update(pts, 1000L + i * 120L, 16.0, Cam(SW, SH, fS, pitch, CAMH), 16.0 * 2 * C.c)   // 자이로: ω = v·곡률(2c)
            if (ln.cL != null && ln.cR != null) { both++; if (lock < 0) lock = i }
        }
        val mot = Motion()
        val mid = { D: Double -> val e = ln.edges(D, mot, 16.0); (e[0] + e[1]) / 2 }
        val err = listOf(10.0, 20.0, 40.0).map { abs(mid(it) - (tL(it) + tR(it)) / 2) }
        val finalP = (C.estP * DEG + ln.pitchAuto) / DEG
        val ok = if (C.paint == 0) ln.basis == 0 else both * 100 / N >= 90 && err[2] < 0.5 && abs(finalP - 5) < 0.3
        if (!ok) fails++
        println("${if (ok) "OK " else "XX "} ${C.name.padEnd(22)} 양쪽 ${both * 100 / N}% (포착 ${if (lock < 0) "-" else "%.1f".format(lock * 0.12)}s) · 중심 오차 10/20/40m: ${err.joinToString(" / ") { "%.2f".format(it) }} m · 기울기 ${"%.2f".format(finalP)}°")
    }
}

/* 추적: 다가오는 차의 접근 속도를 거리 변화 + 크기 변화로 */
fun trackingTest() {
    println("— 추적 · 접근 속도")
    val p = Params(); val trk = Tracker()
    val f = 914.0; val cam = Cam(1280, 720, f, 5 * DEG, 1.25)
    var dist = 60.0; val closing = 10.0
    var t = 0.0
    repeat(40) {
        val zc = dist; val hpx = 1.5 * f / zc; val wpx = 1.8 * f / zc
        val yb = cam.cy + f * tan(atan(1.25 / dist) - 5 * DEG)
        val det = Det(Box(640 - wpx / 2, yb - hpx, wpx, hpx), Kind.CAR, 0.8)
        trk.update(listOf(measure(det, cam, true)), t, 720, hypot(1280.0, 720.0), 0.0, 16.7)
        t += 0.1; dist -= closing * 0.1
    }
    val tr = trk.tracks.single()
    setLanes()
    assess(tr, 16.7, p, lanes, motion)
    val ok = tr.hits == 40 && abs(tr.Z - dist - closing * 0.1) < 2.5 && abs(tr.closing - closing) < 2.5 && tr.lhOk
    if (!ok) fails++
    println("${if (ok) "OK " else "XX "} 60m→21m 접근(10m/s): 추정 거리 ${"%.1f".format(tr.Z)}m, 접근속도 ${"%.1f".format(tr.closing)}m/s, 크기변화 사용=${tr.lhOk}, 판단=${LV[tr.level]} TTC 표시=${ttcShown(tr)}")
}

fun main() {
    scenarios(); unknownRules(); alertPolicy(); trackingTest(); yoloTest(); roadTest()
    println("\n실패 $fails")
    if (fails > 0) kotlin.system.exitProcess(1)
}

/* YOLO 출력 해석: 84×8400 / 8400×84, 레터박스 되돌리기, 오프셋, NMS */
fun yoloTest() {
    println("— YOLO 출력 해석")
    val count = 8400
    for (chFirst in listOf(true, false)) {
        val buf = FloatArray(84 * count)
        fun set(i: Int, c: Int, v: Float) { if (chFirst) buf[c * count + i] = v else buf[i * 84 + c] = v }
        // 자동차(COCO 2) 하나 + 같은 자리 약한 중복 + 보행자(COCO 0) 하나
        set(0, 0, 320f); set(0, 1, 320f); set(0, 2, 100f); set(0, 3, 60f); set(0, 4 + 2, 0.9f)
        set(1, 0, 322f); set(1, 1, 321f); set(1, 2, 98f); set(1, 3, 61f); set(1, 4 + 2, 0.6f)
        set(2, 0, 100f); set(2, 1, 300f); set(2, 2, 20f); set(2, 3, 60f); set(2, 4 + 0, 0.7f)
        val shape = if (chFirst) longArrayOf(1, 84, count.toLong()) else longArrayOf(1, count.toLong(), 84)
        // 1280×720 원본 → 640 레터박스: scale 0.5, padY 140
        val d = YoloPost.decode(java.nio.FloatBuffer.wrap(buf), shape, 0.5, 0.0, 140.0, 0.0, 0.0)
        val car = d.firstOrNull { it.kind == Kind.CAR }
        val ok = d.size == 2 && car != null && abs(car.box.cx - 640) < 1 && abs(car.box.cy - 360) < 1 && abs(car.box.w - 200) < 1 && d.any { it.kind == Kind.PERSON }
        if (!ok) fails++
        println("${if (ok) "OK " else "XX "} ${if (chFirst) "1×84×8400" else "1×8400×84"} → ${d.size}개 (${d.joinToString { "${it.kind.label} ${(it.conf * 100).toInt()}%" }}), 차량 중심 ${car?.box?.cx?.roundToInt()},${car?.box?.cy?.roundToInt()}")
    }
}
