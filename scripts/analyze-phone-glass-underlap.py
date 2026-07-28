import sys

import cv2
import numpy as np


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: analyze-phone-glass-underlap.py <screenshot.png>")
        return 2
    image = cv2.imread(sys.argv[1])
    if image is None or image.shape[:2] != (2376, 1080):
        print("ERROR: expected a decoded 1080x2376 screenshot")
        return 2

    page = image[1700:1900, 0:35].mean(axis=(0, 1))
    underlap = np.concatenate(
        (image[2000:2250, 0:35], image[2000:2250, 1045:1080]),
        axis=1,
    ).mean(axis=(0, 1))
    color_delta = float(np.linalg.norm(page - underlap))
    underlap_luma = float(underlap.mean())
    gesture_region = np.concatenate(
        (image[2330:2370, 0:360], image[2330:2370, 720:1080]),
        axis=1,
    ).mean(axis=(0, 1))
    gesture_delta = float(np.linalg.norm(page - gesture_region))
    gesture_luma = float(gesture_region.mean())

    if underlap_luma > 220.0 and color_delta > 80.0:
        print(
            "RED: opaque bottom backing blocks page underlap; "
            f"page_bgr={page.round(1).tolist()} "
            f"underlap_bgr={underlap.round(1).tolist()} delta={color_delta:.1f}"
        )
        return 1
    if gesture_luma > 220.0 and gesture_delta > 80.0:
        print(
            "RED: the system gesture area still has an opaque light backing; "
            f"page_bgr={page.round(1).tolist()} "
            f"gesture_bgr={gesture_region.round(1).tolist()} delta={gesture_delta:.1f}"
        )
        return 1
    print(
        "GREEN: page background continues behind floating chrome; "
        f"page_bgr={page.round(1).tolist()} "
        f"underlap_bgr={underlap.round(1).tolist()} delta={color_delta:.1f} "
        f"gesture_delta={gesture_delta:.1f}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
