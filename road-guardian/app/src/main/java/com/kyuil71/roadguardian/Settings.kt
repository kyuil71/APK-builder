package com.kyuil71.roadguardian

import android.content.Context
import android.content.SharedPreferences

/** 설정 항목 (슬라이더) */
class NumSpec(val key: String, val label: String, val min: Double, val max: Double, val step: Double, val def: Double,
              val unit: String, val hint: String = "", val zeroLabel: String? = null)
class BoolSpec(val key: String, val label: String, val def: Boolean)

object Settings {
    val nums = listOf(
        NumSpec("reactT", "반응 시간", 0.6, 2.0, 0.1, 1.0, "초", "경고를 보고 브레이크를 밟기까지 걸리는 시간. 길게 잡을수록 일찍 경고합니다."),
        NumSpec("brakeA", "급제동 한계", 4.0, 8.0, 0.5, 6.0, "m/s²", "이보다 세게 밟아야만 멈출 수 있게 되기 직전에 빨간 경고. 낮출수록 일찍 경고합니다."),
        NumSpec("laneWf", "차선 폭 (차선을 못 찾을 때)", 2.6, 4.0, 0.1, 3.3, "m"),
        NumSpec("camH", "폰 높이 (지면에서)", 0.8, 2.2, 0.05, 1.25, "m", "거리 계산의 기준입니다. 줄자로 재서 맞추면 정확해집니다."),
        NumSpec("bonnet", "보닛 길이 (폰 → 앞범퍼)", 0.5, 3.5, 0.1, 1.8, "m"),
        NumSpec("pitchOff", "수평 보정", -10.0, 10.0, 0.5, 0.0, "°", "점선 수평선이 지평선과 맞게. 양쪽 차선이 보이면 주행 중 자동 보정됩니다."),
        NumSpec("fov", "카메라 화각 (1배)", 0.0, 100.0, 1.0, 0.0, "°", "0 = 카메라 정보로 자동 계산", "자동"),
        NumSpec("teleZoom", "망원 배율", 2.0, 5.0, 0.5, 3.0, "배", "폴드8 울트라의 광학 망원은 3배입니다."),
        NumSpec("teleKmh", "망원 자동 전환 속도", 30.0, 90.0, 5.0, 55.0, "km/h", "렌즈 '자동'일 때 이 속도로 3초 이상 달리면 망원, 10km/h 아래로 내려가면 광각."),
        NumSpec("testSpd", "테스트 속도 (GPS 대신)", 0.0, 100.0, 5.0, 0.0, "km/h", "정차 중 동작 확인용. 0 = GPS 사용", "끔"),
        NumSpec("videoKmh", "녹화 영상 속 주행 속도", 0.0, 120.0, 5.0, 50.0, "km/h", "녹화 영상 시험 모드에서만 사용합니다."),
        NumSpec("videoPitch", "녹화 영상 카메라 기울기", -5.0, 15.0, 0.5, 3.0, "°", "녹화 영상 시험 모드에서만. 차선이 보이면 자동 보정됩니다."),
        NumSpec("videoFov", "녹화 영상 화각", 40.0, 130.0, 1.0, 75.0, "°", "블랙박스 영상은 보통 100° 이상입니다."),
    )
    val bools = listOf(
        BoolSpec("laneOn", "차선 인식", true),
        BoolSpec("sound", "경고음", true),
        BoolSpec("voice", "음성 경고", true),
        BoolSpec("infoVoice", "주변 보행자 안내 음성 (경고 아님)", true),
        BoolSpec("vibe", "진동", true),
    )

    private lateinit var sp: SharedPreferences
    @Volatile private var numVals = HashMap<String, Double>()
    @Volatile private var boolVals = HashMap<String, Boolean>()
    @Volatile var lensMode = "auto"

    fun load(ctx: Context) {
        sp = ctx.getSharedPreferences("road_guardian", Context.MODE_PRIVATE)
        val n = HashMap<String, Double>(); for (s in nums) n[s.key] = sp.getFloat(s.key, s.def.toFloat()).toDouble()
        val b = HashMap<String, Boolean>(); for (s in bools) b[s.key] = sp.getBoolean(s.key, s.def)
        numVals = n; boolVals = b
        lensMode = sp.getString("lensMode", "auto") ?: "auto"
    }
    fun num(k: String) = numVals[k] ?: nums.first { it.key == k }.def
    fun bool(k: String) = boolVals[k] ?: bools.first { it.key == k }.def
    fun setNum(k: String, v: Double) { val m = HashMap(numVals); m[k] = v; numVals = m; sp.edit().putFloat(k, v.toFloat()).apply() }
    fun setBool(k: String, v: Boolean) { val m = HashMap(boolVals); m[k] = v; boolVals = m; sp.edit().putBoolean(k, v).apply() }
    fun saveLens(m: String) { lensMode = m; sp.edit().putString("lensMode", m).apply() }
    fun agreed() = sp.getBoolean("agreed_v1", false)
    fun setAgreed() = sp.edit().putBoolean("agreed_v1", true).apply()
}
