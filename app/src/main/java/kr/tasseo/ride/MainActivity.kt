package kr.tasseo.ride

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 운전 중 화면 원칙: 글자 크게, 한 화면 한 메시지, 조작은 출발 버튼 하나(나머지는 차 신호가 대신).
 * 운전자에게 필요한 건 「아이가 탔나·벨트 맸나」 한 줄이고, 가족에게 보낼 말은 차가 대신 보낸다.
 */
class MainActivity : Activity(), RideEngine.Output {

    companion object { const val SIM_ACTION = "kr.tasseo.ride.SIM" }

    private val leg = RideEngine.Leg(
        child = "지우",
        pickup = RideEngine.Place("수학학원", 37.4979, 127.0276, "서울 강남구 테헤란로 (시연용 위치)"),
        dropoff = RideEngine.Place("영어학원", 37.5009, 127.0366, "서울 강남구 역삼로 (시연용 위치)"),
        pickupTime = "17:50",
        guardian2 = "아빠",
    )
    private lateinit var engine: RideEngine
    private lateinit var bridge: CarBridge
    private var tts: TextToSpeech? = null

    private lateinit var phaseView: TextView
    private lateinit var statusView: TextView
    private lateinit var bannerView: TextView
    private lateinit var chipDoor: TextView
    private lateinit var chipSeat: TextView
    private lateinit var chipBelt: TextView
    private lateinit var chipGear: TextView
    private lateinit var msgView: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: Button

    private val bg = Color.parseColor("#0E141B")
    private val card = Color.parseColor("#18222D")
    private val fg = Color.parseColor("#EAF0F6")
    private val sub = Color.parseColor("#8FA3B5")
    private val ok = Color.parseColor("#2FBF71")
    private val warn = Color.parseColor("#F05A4F")
    private val idle = Color.parseColor("#2A3644")
    private val accent = Color.parseColor("#3D8BFD")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = RideEngine(leg, this)
        setContentView(buildUi())
        tts = TextToSpeech(this) { s -> if (s == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN }
        requestPermissions(arrayOf(
            "pleos.car.permission.CAR_DOORS", "pleos.car.permission.CAR_SEATS",
            "pleos.car.permission.CAR_SAFETY", "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.NAVI_ROUTE_SEARCH", "pleos.car.permission.NAVI_ROUTE"), 1)
        bridge = CarBridge(this, { engine }, { s -> statusView.text = s })
        bridge.start()
        registerReceiver(simReceiver, IntentFilter(SIM_ACTION), Context.RECEIVER_EXPORTED)
        phase(engine.phase)
        refreshChips()
    }

    /**
     * 시연 신호 — 에뮬레이터는 user 빌드라 착석·벨트·기어 값을 바꿀 공식 수단이 없다(도어만 SDK 제어 API 있음).
     * 그래서 시연 영상에서는 adb 브로드캐스트로 같은 판정 엔진에 신호를 넣고, 화면 기록에 「시연 신호」로 표시한다.
     * 실차·실제 에뮬레이터 값은 CarBridge 의 SDK 리스너로 들어온다(기어 P·2열 착석 수신 확인됨).
     *   adb shell am broadcast -a kr.tasseo.ride.SIM --es sig seat --es val 1
     */
    private val simReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val sig = i.getStringExtra("sig") ?: return
            val v = i.getStringExtra("val") ?: ""
            if (sig != "start") line(logView, "[시연 신호] $sig=$v")
            when (sig) {
                "door" -> { val open = v == "open"; bridge.setRearDoor(open) { engine.onDoor(open) } }
                "seat" -> engine.onOccupancy(v == "1")
                "belt" -> engine.onBelt(v == "1")
                "gear" -> engine.onGear(RideEngine.Gear.valueOf(v))
                "eta" -> engine.onEta(v.toInt())
                "arrive" -> engine.onArrived()
                "start" -> engine.startPickup()
            }
            refreshChips()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(simReceiver) } catch (_: Exception) { }
        bridge.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ── RideEngine.Output ─────────────────────────────────────────
    override fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        line(logView, "🔊 $text")
    }

    override fun send(to: String, text: String) {
        // 시연판: 화면 「가족 알림」 칸에 남긴다. 서비스판: 가족 앱 푸시(자체 서버) — README 참고.
        line(msgView, "📱 ${to}에게 · $text")
        refreshChips()
    }

    override fun phase(p: RideEngine.Phase) {
        phaseView.text = p.label
        startBtn.visibility = if (p == RideEngine.Phase.READY || p == RideEngine.Phase.DONE) View.VISIBLE else View.GONE
        startBtn.text = if (p == RideEngine.Phase.DONE) "다음 라이딩 출발" else "${leg.child} 데리러 출발"
        refreshChips()
    }

    override fun banner(text: String, level: Int) {
        bannerView.text = text
        val c = when (level) { 2 -> warn; 1 -> ok; else -> idle }
        bannerView.background = round(c, 28f)
        refreshChips()
    }

    override fun log(text: String) {
        line(logView, text)
        refreshChips()
    }

    override fun startRoute(place: RideEngine.Place) = bridge.route(place)

    // ── 화면 ─────────────────────────────────────────────────────
    private fun refreshChips() {
        if (!::chipDoor.isInitialized) return
        chip(chipDoor, "뒷문", if (engine.doorOpen) "열림" else "닫힘", if (engine.doorOpen) accent else idle)
        chip(chipSeat, "착석", if (engine.occupied) "앉음" else "비어 있음", if (engine.occupied) ok else idle)
        val beltColor = when {
            engine.belted -> ok
            engine.occupied -> warn
            else -> idle
        }
        chip(chipBelt, "벨트", if (engine.belted) "체결" else "풀림", beltColor)
        chip(chipGear, "기어", when (engine.gear) {
            RideEngine.Gear.PARK -> "P"; RideEngine.Gear.DRIVE -> "D"
            RideEngine.Gear.REVERSE -> "R"; RideEngine.Gear.NEUTRAL -> "N"; else -> "-"
        }, idle)
    }

    private fun chip(v: TextView, k: String, value: String, color: Int) {
        v.text = "$k\n$value"
        v.background = round(color, 24f)
    }

    private fun line(v: TextView, text: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        v.text = "$t  $text\n" + v.text
    }

    private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun tv(size: Float, color: Int = fg, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(64, 48, 64, 48)
        }
        // 위: 앱 이름 + 현재 단계 + 연결 상태
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv(44f, fg, true).apply { text = "탔어?" })
        phaseView = tv(40f, accent, true).apply { setPadding(48, 0, 0, 0) }
        top.addView(phaseView, LinearLayout.LayoutParams(0, -2, 1f))
        statusView = tv(20f, sub).apply { text = "차량 연결 중…"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = 900 }
        top.addView(statusView)
        root.addView(top)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 40, 0, 0) }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        // 왼쪽: 오늘 라이딩 + 신호 4칸 + 큰 안내 + 출발 버튼
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(card, 32f); setPadding(48, 40, 48, 40) }
        left.addView(tv(26f, sub).apply { text = "오늘 라이딩" })
        left.addView(tv(40f, fg, true).apply {
            text = "${leg.child} · ${leg.pickup.name} ${leg.pickupTime} 픽업 → ${leg.dropoff.name}"
            setPadding(0, 8, 0, 32)
        })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun mk() = tv(30f, fg, true).apply { gravity = Gravity.CENTER; setPadding(16, 28, 16, 28) }
        chipDoor = mk(); chipSeat = mk(); chipBelt = mk(); chipGear = mk()
        for (c in listOf(chipDoor, chipSeat, chipBelt, chipGear)) {
            chips.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 24, 0) })
        }
        left.addView(chips)
        left.addView(tv(22f, sub).apply { text = "2열 오른쪽 자리 · Pleos Vehicle SDK 실시간 신호"; setPadding(0, 12, 0, 32) })
        bannerView = tv(40f, fg, true).apply {
            text = "출발 버튼을 누르면 학원 도착 5분 전에 ${leg.child}에게 먼저 알려요"
            setPadding(40, 36, 40, 36)
            background = round(idle, 28f)
        }
        left.addView(bannerView, LinearLayout.LayoutParams(-1, -2))
        startBtn = Button(this).apply {
            textSize = 34f
            setTextColor(Color.WHITE)
            background = round(accent, 28f)
            setOnClickListener { engine.startPickup() }
        }
        left.addView(startBtn, LinearLayout.LayoutParams(-1, 150).apply { setMargins(0, 36, 0, 0) })
        body.addView(left, LinearLayout.LayoutParams(0, -1, 3f))

        // 오른쪽: 가족에게 간 알림 + 차 신호 기록
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 0, 0, 0) }
        right.addView(tv(26f, sub).apply { text = "가족에게 보낸 알림" })
        msgView = tv(26f, fg)
        right.addView(ScrollView(this).apply {
            background = round(card, 32f); setPadding(32, 24, 32, 24); addView(msgView)
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, 12, 0, 24) })
        right.addView(tv(26f, sub).apply { text = "차 신호 기록" })
        logView = tv(20f, sub)
        right.addView(ScrollView(this).apply {
            background = round(card, 32f); setPadding(32, 24, 32, 24); addView(logView)
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, 12, 0, 0) })
        body.addView(right, LinearLayout.LayoutParams(0, -1, 2f))
        return root
    }
}
