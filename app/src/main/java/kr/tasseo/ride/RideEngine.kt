package kr.tasseo.ride

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「탔어?」 라이딩 판정 엔진 — 차량 신호만으로 아이의 탑승·벨트·하차를 판정한다.
 *
 * 입력: 2열 도어 열림/닫힘, 2열 좌석 착석, 2열 벨트 체결, 기어, 내비 도착예정(초)·도착 이벤트
 * 출력: 음성 안내(say), 가족 알림(send), 화면 상태(phase/banner), 타임라인(log)
 *
 * 판정 규칙(한 곳에 모아 둔다 — 화면·SDK 코드와 분리해 시험하기 쉽게):
 *  - 탑승 = 픽업 정차 중 「뒷문 열림→닫힘」 + 「착석」. 벨트까지 체결돼야 「탑승·벨트 확인」 알림을 보낸다.
 *  - 착석했는데 벨트 미체결 상태로 기어 D → 출발 전 경고(음성+빨간 배너).
 *  - 하차 = 하차 정차 중 「뒷문 열림→닫힘」 + 「좌석 비움」.
 *  - 정차 판정 = 내비 도착 이벤트, 또는 목적지로 가는 중 기어 P (학원 앞에 세우는 실제 동작).
 */
class RideEngine(private val leg: Leg, private val out: Output) {

    enum class Phase(val label: String) {
        READY("출발 전"),
        TO_PICKUP("픽업 가는 중"),
        AT_PICKUP("학원 앞 대기"),
        BOARDED("탑승 확인"),
        TO_DROPOFF("다음 학원 가는 중"),
        AT_DROPOFF("하차 대기"),
        DONE("하차 확인 · 완료"),
    }

    enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

    data class Place(val name: String, val lat: Double, val lon: Double, val address: String)

    data class Leg(
        val child: String,          // 아이 이름(앱 안에서만 쓰는 호칭)
        val pickup: Place,          // 픽업할 학원
        val dropoff: Place,         // 내려줄 학원
        val pickupTime: String,     // 수업 끝나는 시각
        val guardian2: String,      // 함께 알림 받는 보호자
    )

    interface Output {
        fun say(text: String)
        fun send(to: String, text: String)
        fun phase(p: Phase)
        fun banner(text: String, level: Int)   // 0=정보 1=확인 2=경고
        fun log(text: String)
        fun startRoute(place: Place)
    }

    var phase = Phase.READY
        private set
    var doorOpen = false; private set
    var occupied = false; private set
    var belted = false; private set
    var gear = Gear.UNKNOWN; private set

    private var doorCycled = false      // 정차 중 뒷문이 열렸다 닫혔는가
    private var etaSent = false
    private var beltWarned = false
    private var boardedWaitingBelt = false

    private fun now(): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())

    private fun go(p: Phase) {
        phase = p
        doorCycled = false
        out.phase(p)
    }

    // ── 사용자 동작 ────────────────────────────────────────────────
    fun startPickup() {
        etaSent = false
        go(Phase.TO_PICKUP)
        out.startRoute(leg.pickup)
        out.say("${leg.child} 데리러 ${leg.pickup.name}으로 갈게요.")
        out.log("출발 — ${leg.pickup.name} (${leg.pickupTime} 수업 끝)")
    }

    // ── 내비 신호 ──────────────────────────────────────────────────
    fun onEta(remainSec: Int) {
        if (phase == Phase.TO_PICKUP && !etaSent && remainSec in 1..300) {
            etaSent = true
            val min = (remainSec + 59) / 60
            out.send(leg.child, "${min}분 뒤 ${leg.pickup.name} 앞 도착해. 준비하고 나와~")
            out.log("도착 예정 ${min}분 — ${leg.child}에게 미리 알림")
        }
    }

    fun onArrived() {
        when (phase) {
            Phase.TO_PICKUP -> arrivePickup("내비 도착")
            Phase.TO_DROPOFF -> arriveDropoff("내비 도착")
            else -> Unit
        }
    }

    private fun arrivePickup(by: String) {
        go(Phase.AT_PICKUP)
        out.send(leg.child, "${leg.pickup.name} 앞에 도착했어. 뒷문으로 타!")
        out.banner("${leg.pickup.name} 앞 — ${leg.child} 기다리는 중", 0)
        out.log("픽업 정차($by) — ${leg.child}에게 도착 알림")
    }

    private fun arriveDropoff(by: String) {
        go(Phase.AT_DROPOFF)
        out.say("${leg.dropoff.name}에 도착했어요. ${leg.child}야, 잘 다녀와.")
        out.banner("${leg.dropoff.name} 앞 — 하차 확인 중", 0)
        out.log("하차 정차($by)")
    }

    // ── 차량 신호 ──────────────────────────────────────────────────
    fun onDoor(open: Boolean) {
        if (doorOpen && !open && (phase == Phase.AT_PICKUP || phase == Phase.AT_DROPOFF)) {
            doorCycled = true
            out.log("뒷문 열림→닫힘")
        }
        doorOpen = open
        evaluate()
    }

    fun onOccupancy(b: Boolean) {
        occupied = b
        out.log(if (b) "2열 좌석 착석" else "2열 좌석 비움")
        evaluate()
    }

    fun onBelt(b: Boolean) {
        val was = belted
        belted = b
        if (b != was) out.log(if (b) "2열 벨트 체결" else "2열 벨트 풀림")
        if (b) beltWarned = false
        evaluate()
    }

    fun onGear(g: Gear) {
        val was = gear
        gear = g
        if (g == was) return
        out.log("기어 ${g.name}")
        // 목적지로 가는 중 P = 학원 앞에 세움
        if (g == Gear.PARK) {
            if (phase == Phase.TO_PICKUP) arrivePickup("기어 P")
            else if (phase == Phase.TO_DROPOFF) arriveDropoff("기어 P")
        }
        if (g == Gear.DRIVE) {
            if (occupied && !belted) {
                warnBelt()
                return
            }
            if (phase == Phase.BOARDED) {
                go(Phase.TO_DROPOFF)
                out.startRoute(leg.dropoff)
                out.say("${leg.dropoff.name}으로 출발할게요.")
                out.log("출발 — ${leg.dropoff.name}")
            }
        }
        evaluate()
    }

    private fun warnBelt() {
        if (beltWarned) return
        beltWarned = true
        out.say("잠깐요. 뒷좌석 벨트가 아직 안 채워졌어요. ${leg.child}야, 벨트 먼저 매 줘.")
        out.banner("⚠ 뒷좌석 벨트 미체결 — 출발 전에 확인하세요", 2)
        out.log("벨트 미체결 상태로 기어 D — 출발 전 경고")
    }

    private fun evaluate() {
        when (phase) {
            Phase.AT_PICKUP -> {
                if (doorCycled && occupied) {
                    if (belted) {
                        boardedWaitingBelt = false
                        go(Phase.BOARDED)
                        val t = now()
                        out.send(leg.guardian2, "${leg.child} 탑승·안전벨트 확인 ($t, ${leg.pickup.name} 앞)")
                        out.say("${leg.child}야 반가워. 벨트까지 맸구나. 이제 ${leg.dropoff.name}으로 갈게.")
                        out.banner("✔ ${leg.child} 탑승 · 벨트 확인 $t", 1)
                        out.log("탑승 확인(뒷문·착석·벨트) — ${leg.guardian2}에게 알림")
                    } else if (!boardedWaitingBelt) {
                        boardedWaitingBelt = true
                        out.say("${leg.child}야, 안전벨트 매 줘.")
                        out.banner("${leg.child} 탑승 — 벨트를 기다리는 중", 0)
                        out.log("착석 확인, 벨트 대기")
                    }
                }
            }
            Phase.AT_DROPOFF -> {
                if (doorCycled && !occupied) {
                    go(Phase.DONE)
                    val t = now()
                    out.send(leg.guardian2, "${leg.child} ${leg.dropoff.name} 하차 확인 ($t)")
                    out.send(leg.child, "잘 다녀와! 끝나면 또 데리러 갈게.")
                    out.banner("✔ ${leg.child} 하차 확인 $t", 1)
                    out.log("하차 확인(뒷문·좌석 비움) — ${leg.guardian2}에게 알림")
                }
            }
            else -> Unit
        }
    }
}
