#!/usr/bin/env bash
# 시연 시나리오 — Pleos Connect 에뮬레이터에서 「탔어?」 한 바퀴를 재현한다.
# 착석·벨트·기어는 에뮬레이터(user 빌드)에서 바꿀 공식 수단이 없어 앱의 시연 신호(브로드캐스트)로 넣는다.
# 뒷문은 Vehicle SDK 제어 API(setDoorPosition)로 요청한다.
# 사용: ADB="adb -s <기기>" ./tools/demo_scenario.sh [쉬는초] [자막기록파일]
set -u
ADB=${ADB:-adb}
W=${1:-3}
CAP=${2:-/dev/null}
T0=$(date +%s.%N)
sim() {
  # $1 신호 $2 값 $3 자막(있으면 기록)
  if [ -n "${3:-}" ]; then
    echo "$(awk "BEGIN{printf \"%.2f\", $(date +%s.%N) - $T0}")|$3" >> "$CAP"
  fi
  $ADB shell am broadcast -a kr.tasseo.ride.SIM --es sig "$1" --es val "$2" >/dev/null
  sleep "$W"
}

: > "$CAP"
$ADB shell am force-stop kr.tasseo.ride
$ADB shell am start -n kr.tasseo.ride/.MainActivity >/dev/null
sleep 5
echo "$(awk "BEGIN{printf \"%.2f\", $(date +%s.%N) - $T0}")|오늘 라이딩: 지우 · 수학학원 픽업 → 영어학원" >> "$CAP"
sim seat 0
sim belt 0
sim gear DRIVE
sim start go "운전자는 「지우 데리러 출발」 한 번만 — 학원으로 경로 요청(NaviHelper)"
sim eta 290 "도착 5분 전 → 지우 휴대폰에 「5분 뒤 도착」 미리 알림"
sim gear PARK "학원 앞 정차(기어 P) → 지우에게 「도착했어, 뒷문으로 타!」"
sim door open "지우가 2열 오른쪽 뒷문을 열고"
sim seat 1 "자리에 앉음(2열 좌석 착석)"
sim door close "문이 닫힘 → 벨트를 아직 안 맸으니 「벨트 매 줘」 음성"
sim gear DRIVE "벨트 없이 D로 바꾸면 → 출발 전 경고(빨간 화면 + 음성)"
sim gear PARK
sim belt 1 "벨트 체결 → 아빠에게 「지우 탑승·안전벨트 확인」 자동 발송"
sim gear DRIVE "영어학원으로 출발 — 아이 구간은 편안한 길로 경로 요청"
sim gear PARK "영어학원 앞 정차"
sim door open "지우가 뒷문을 열고 내림"
sim belt 0
sim seat 0
sim door close "좌석 비움 + 문 닫힘 → 아빠에게 「영어학원 하차 확인」"
sleep 4
