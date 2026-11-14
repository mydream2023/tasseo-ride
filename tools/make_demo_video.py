# -*- coding: utf-8 -*-
"""에뮬레이터 녹화 + 자막 기록 → 제출용 시연 영상(인트로·자막·아웃트로) 만들기.

사용: python make_demo_video.py <녹화.mp4> <captions.txt> <출력.mp4> [녹화시작-시나리오시작 오프셋초]
"""
import pathlib, subprocess, sys, tempfile

src, cap, out = sys.argv[1], sys.argv[2], sys.argv[3]
off = float(sys.argv[4]) if len(sys.argv) > 4 else 1.0
FONT = "C\\:/Windows/Fonts/malgunbd.ttf"
tmp = pathlib.Path(tempfile.mkdtemp(dir=pathlib.Path(out).parent))

dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                                     "-of", "csv=p=0", src]).decode().strip())
rows = []
for ln in pathlib.Path(cap).read_text(encoding="utf-8").splitlines():
    if "|" in ln:
        t, txt = ln.split("|", 1)
        rows.append((float(t) + off, txt.strip()))


def ts(x):
    h = int(x // 3600); m = int(x % 3600 // 60); s = x % 60
    return f"{h}:{m:02d}:{s:05.2f}"


ass = ["[Script Info]", "ScriptType: v4.00+", "PlayResX: 1280", "PlayResY: 720", "",
       "[V4+ Styles]",
       "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
       "Style: Cap,Malgun Gothic,30,&H00FFFFFF,&H00FFFFFF,&H00000000,&HC0101820,1,0,0,0,100,100,0,0,3,14,0,2,40,40,96,1",
       "Style: Tag,Malgun Gothic,18,&H00D0E0F0,&H00FFFFFF,&H00000000,&H90101820,0,0,0,0,100,100,0,0,3,8,0,9,20,20,12,1",
       "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"]
for i, (t, txt) in enumerate(rows):
    end = rows[i + 1][0] if i + 1 < len(rows) else dur
    ass.append(f"Dialogue: 0,{ts(t)},{ts(end)},Cap,,0,0,0,,{txt}")
ass.append(f"Dialogue: 0,{ts(0)},{ts(dur)},Tag,,0,0,0,,Pleos Connect 에뮬레이터 v2.1.0 실제 실행 화면 · 착석·벨트·기어는 시연 신호")
(tmp / "cap.ass").write_text("\n".join(ass), encoding="utf-8-sig")

main = tmp / "main.mp4"
ass_path = str(tmp / "cap.ass").replace("\\", "/").replace(":", "\\:")
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src,
                "-vf", f"scale=1280:720,fps=30,subtitles='{ass_path}'",
                "-c:v", "libx264", "-crf", "26", "-preset", "medium", "-pix_fmt", "yuv420p", "-an", str(main)], check=True)


def card(path, lines, sec):
    vf = []
    y0 = 360 - (len(lines) * 70) // 2
    for i, (txt, size, color) in enumerate(lines):
        t = txt.replace(":", "\\:").replace("'", "’")
        vf.append(f"drawtext=fontfile='{FONT}':text='{t}':fontsize={size}:fontcolor={color}:x=(w-text_w)/2:y={y0 + i * 70}")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"color=c=0x0E141B:s=1280x720:d={sec}:r=30",
                    "-vf", ",".join(vf), "-c:v", "libx264", "-crf", "26", "-pix_fmt", "yuv420p", str(path)], check=True)


intro, outro = tmp / "intro.mp4", tmp / "outro.mp4"
card(intro, [("탔어?", 72, "white"),
             ("학원 라이딩 탑승·안전벨트 확인 앱", 40, "0x9FC4FF"),
             ("운전 중인 부모 대신, 차가 아이의 탑승과 벨트를 확인해 가족에게 알립니다", 26, "0xC8D3DE"),
             ("Vehicle SDK(2열 도어·좌석·벨트·기어) + NaviHelper SDK(경로·도착 예정)", 24, "0x8FA3B5"),
             ("※ 에뮬레이터에선 착석·벨트·기어 값을 바꿀 수 없어 앱의 시연 신호로 넣었습니다", 22, "0xF0B060")], 6)
card(outro, [("운전 중 휴대폰 조작 0회", 48, "white"),
             ("「탔어?」 확인 연락 0회 · 벨트 안 맨 출발은 출발 전에 경고", 32, "0x9FC4FF"),
             ("Pleos 앱으로만 만들 수 있는 가족 이동 서비스", 28, "0xC8D3DE")], 4)

lst = tmp / "list.txt"
lst.write_text("".join(f"file '{p.as_posix()}'\n" for p in (intro, main, outro)), encoding="utf-8")
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(lst),
                "-c", "copy", "-movflags", "+faststart", out], check=True)
print("완성:", out)
