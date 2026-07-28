import sys

import cv2


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: analyze-bottom-chrome-flicker.py <recording.mp4>")
        return 2

    capture = cv2.VideoCapture(sys.argv[1])
    fps = capture.get(cv2.CAP_PROP_FPS)
    if fps <= 0:
        print("ERROR: recording could not be decoded")
        return 2

    frames = []
    saturations = []
    values = []
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        bottom = cv2.resize(frame[1900:2376], (270, 119))
        frames.append(bottom)
        hsv = cv2.cvtColor(bottom, cv2.COLOR_BGR2HSV)
        saturations.append(float(hsv[:, :, 1].mean()))
        values.append(float(hsv[:, :, 2].mean()))
    if len(frames) < 8:
        print("ERROR: recording did not contain enough frames")
        return 2

    settled = frames[-1]
    deltas = [float(cv2.absdiff(frame, settled).mean()) for frame in frames]
    spikes = []
    for index in range(max(3, int(fps)), len(frames) - 3):
        before = min(deltas[index - 3:index])
        after = min(deltas[index + 1:index + 4])
        isolated_delta = deltas[index] > 30.0 and before < 15.0 and after < 15.0
        white_material_frame = (
            saturations[index] < 25.0
            and values[index] > 220.0
            and max(saturations[index - 2:index]) > 45.0
            and max(saturations[index + 1:index + 3]) > 45.0
        )
        if isolated_delta or white_material_frame:
            spikes.append((index, deltas[index], before, after))

    if spikes:
        first = spikes[0]
        print(
            "RED: bottom chrome contains an isolated flash frame; "
            f"count={len(spikes)} first={first[0] / fps:.3f}s "
            f"delta={first[1]:.1f} before={first[2]:.1f} after={first[3]:.1f}"
        )
        return 1

    print("GREEN: bottom chrome reaches its settled state without isolated flash frames")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
