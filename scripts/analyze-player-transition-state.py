import sys

import cv2


def read(path: str):
    image = cv2.imread(path)
    if image is None:
        raise ValueError(f"could not decode {path}")
    return image


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: analyze-player-transition-state.py <collapsed-before.png> <expanded.png> <collapsed-after.png>")
        return 2

    before, expanded, after = (read(path) for path in sys.argv[1:])
    if before.shape != expanded.shape or before.shape != after.shape:
        print("ERROR: transition screenshots do not have the same dimensions")
        return 2

    upper = slice(0, min(1600, before.shape[0]))
    expanded_delta = float(cv2.absdiff(before[upper], expanded[upper]).mean())
    collapsed_delta = float(cv2.absdiff(before[upper], after[upper]).mean())
    if expanded_delta < 20.0:
        print(f"RED: mini-player tap did not visibly expand the player; delta={expanded_delta:.1f}")
        return 1
    if collapsed_delta > 20.0:
        print(f"RED: collapse gesture did not return to the original Apple Music page; delta={collapsed_delta:.1f}")
        return 1

    print(
        "GREEN: feedback loop exercised full-player expansion and returned to the mini-player; "
        f"expanded_delta={expanded_delta:.1f} collapsed_delta={collapsed_delta:.1f}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
