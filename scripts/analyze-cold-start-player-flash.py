import sys

import cv2


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: analyze-cold-start-player-flash.py <recording.mp4>")
        return 2

    capture = cv2.VideoCapture(sys.argv[1])
    fps = capture.get(cv2.CAP_PROP_FPS)
    if fps <= 0:
        print("ERROR: recording could not be decoded")
        return 2

    frames = []
    frame_index = 0
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        frames.append(frame)
        frame_index += 1

    if not frames:
        print("ERROR: recording contained no frames")
        return 2

    final_content = cv2.resize(frames[-1][180:1850, 40:1040], (200, 334))
    content_deltas = [
        float(cv2.absdiff(cv2.resize(frame[180:1850, 40:1040], (200, 334)), final_content).mean())
        for frame in frames
    ]
    ready_frame = None
    for index in range(max(0, int(1.2 * fps)), len(frames) - 6):
        if max(content_deltas[index:index + 6]) < 10.0:
            ready_frame = index
            break
    if ready_frame is not None:
        transient_frames = [
            index
            for index in range(ready_frame + 6, min(len(frames), int(4.0 * fps)))
            if content_deltas[index] > 35.0
        ]
        if transient_frames:
            first = transient_frames[0]
            print(
                "RED: cold start flashes after the page is already visible; "
                f"frames={len(transient_frames)} first={first / fps:.3f}s "
                f"settled_content_delta={content_deltas[first]:.1f}"
            )
            return 1

    flash_frames: list[tuple[int, float, float]] = []
    for index, frame in enumerate(frames):
        timestamp = index / fps
        if timestamp < 1.5:
            continue

        # The exact regression is the artwork-derived full-player surface
        # covering the content during cold start. On the pinned device/song it
        # creates a saturated, mostly-dark central viewport; splash and the
        # settled Library page do not satisfy both conditions.
        roi = frame[250:1900, 50:1030]
        hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
        saturation = float(hsv[:, :, 1].mean())
        dark_fraction = float((hsv[:, :, 2] < 150).mean())
        if saturation > 80.0 and dark_fraction > 0.60:
            flash_frames.append((index, saturation, dark_fraction))

    if flash_frames:
        first = flash_frames[0]
        print(
            "RED: cold start exposed full player "
            f"for {len(flash_frames)} frame(s); first={first[0] / fps:.3f}s "
            f"saturation={first[1]:.1f} dark_fraction={first[2]:.3f}"
        )
        return 1

    controls = frames[-1][2000:2135, 730:1000]
    controls_gray = cv2.cvtColor(controls, cv2.COLOR_BGR2GRAY)
    controls_dark_fraction = float((controls_gray < 80).mean())
    if controls_dark_fraction < 0.04:
        print(
            "RED: full-player flash is gone but the settled mini-player is hidden; "
            f"controls_dark_fraction={controls_dark_fraction:.3f}"
        )
        return 1

    print(
        "GREEN: cold start had no post-page flashes or full-player frames and the mini-player settled visible; "
        f"controls_dark_fraction={controls_dark_fraction:.3f}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
