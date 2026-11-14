package kr.tasseo.ride

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Pleos Connect SDK 연결부 — Vehicle SDK(2열 도어·좌석·벨트·기어) + NaviHelper SDK(경로·도착예정·도착).
 * SDK 콜백은 바인더 스레드에서 올 수 있으므로 전부 메인 스레드로 넘겨 엔진에 전달한다.
 *
 * 아이 자리는 기본 2열 오른쪽(조수석 뒤 — 인도 쪽으로 타고 내리는 자리). 설정에서 바꿀 수 있게 확장 예정.
 */
class CarBridge(
    context: Context,
    private val engine: () -> RideEngine,
    private val status: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context)
    private val navi = NaviHelper(context)

    private val doorArea = DoorArea(DoorValue.ROW_2_RIGHT)
    private val seatArea = SeatArea(SeatValue.ROW_2_RIGHT)

    private fun ui(block: () -> Unit) = main.post(block)

    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(p0: Int?) { ui { engine().onDoor((p0 ?: 0) > 0) } }
        override fun onFailed(p0: Exception) { ui { status("도어 신호 오류: ${p0.message}") } }
    }
    private val seatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(p0: Boolean?) { ui { engine().onOccupancy(p0 == true) } }
        override fun onFailed(p0: Exception) { ui { status("좌석 신호 오류: ${p0.message}") } }
    }
    private val beltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(p0: Boolean?) { ui { engine().onBelt(p0 == true) } }
        override fun onFailed(p0: Exception) { ui { status("벨트 신호 오류: ${p0.message}") } }
    }
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(p0: VehicleGear?) { ui { engine().onGear(map(p0)) } }
        override fun onFailed(p0: Exception) { ui { status("기어 신호 오류: ${p0.message}") } }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onDrivingInfo(info: DrivingInfo) {
            val sec = info.destination?.duration ?: return
            ui { engine().onEta(sec) }
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) { ui { engine().onArrived() } }
        override fun onError(code: NaviErrorCode) { ui { status("내비 오류: $code") } }
    }

    private fun map(g: VehicleGear?): RideEngine.Gear = when (g) {
        VehicleGear.GEAR_PARK -> RideEngine.Gear.PARK
        VehicleGear.GEAR_REVERSE -> RideEngine.Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> RideEngine.Gear.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> RideEngine.Gear.UNKNOWN
        else -> RideEngine.Gear.DRIVE        // D·1~n단 = 주행
    }

    fun start() {
        try {
            vehicle.initialize()
            // 서비스 바인딩이 끝난 뒤 등록되도록 잠깐 기다린다
            main.postDelayed({ registerVehicle() }, 1500)
        } catch (e: Exception) {
            status("차량 SDK 연결 실패: ${e.message}")
        }
        try {
            navi.initialize({
                navi.addListener(naviListener)
                ui { status("내비 연결됨") }
            }, { e -> ui { status("내비 연결 실패: ${e.message}") } })
        } catch (e: Exception) {
            status("내비 SDK 연결 실패: ${e.message}")
        }
    }

    private fun registerVehicle() {
        try {
            vehicle.getDoor().registerDoorPosition(doorArea, doorListener)
            vehicle.getSeat().registerSeatOccupancy(seatArea, seatListener)
            vehicle.getSeat().registerSeatBeltBuckledState(seatArea, beltListener)
            vehicle.getGear().registerGearSelection(gearListener)
            vehicle.getGear().getCurrentGearSelectionState({ g -> ui { engine().onGear(map(g)) } }, { })
            status("차량 연결됨 — 2열 오른쪽 도어·좌석·벨트, 기어 수신 중")
        } catch (e: Exception) {
            status("차량 신호 등록 실패: ${e.message} — 다시 시도")
            main.postDelayed({ registerVehicle() }, 3000)
        }
    }

    fun route(p: RideEngine.Place) {
        try {
            // 아이가 탄 구간은 편안한 길(급회전·급정거가 적은 경로) 우선
            val opt = if (engine().phase == RideEngine.Phase.TO_DROPOFF) RouteOption.COMFORTABLE else RouteOption.RECOMMENDED
            navi.requestRoute(RouteInfo(p.lat, p.lon, null, null, p.name, "", p.address, "", opt, "", false))
            status("경로 요청 — ${p.name}")
        } catch (e: Exception) {
            status("경로 요청 실패: ${e.message}")
        }
    }

    /**
     * 시연용: 에뮬레이터에서 2열 뒷문을 실제 SDK 제어 API로 열고 닫는다(Door만 제어 API가 있음).
     * 성공하면 DoorPositionListener 로 실제 신호가 되돌아와 엔진에 들어간다.
     */
    fun setRearDoor(open: Boolean, fallback: () -> Unit) {
        try {
            vehicle.getDoor().setDoorPosition(doorArea, if (open) 1 else 0,
                { _, _ -> ui { fallback() } }, { _ -> ui { status("도어 제어 실패 — 시연 신호로 대체"); fallback() } })
        } catch (e: Exception) {
            status("도어 제어 불가 — 시연 신호로 대체")
            fallback()
        }
    }

    fun stop() {
        try {
            vehicle.getDoor().unregisterDoorPosition(doorArea, doorListener)
            vehicle.getSeat().unregisterSeatOccupancy(seatArea, seatListener)
            vehicle.getSeat().unregisterSeatBeltBuckledState(seatArea, beltListener)
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.release()
        } catch (_: Exception) { }
        try {
            navi.removeListener(naviListener)
            navi.release()
        } catch (_: Exception) { }
    }
}
