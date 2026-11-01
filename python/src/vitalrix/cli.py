"""`vitalrix check`: sends one test log so you know the key and URL work."""
import sys

from .core import check


def main(argv=None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if args[:1] != ["check"]:
        print("usage: vitalrix check", file=sys.stderr)
        return 2
    try:
        print(check())
        return 0
    except Exception as err:
        print(f"vitalrix: {err}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
