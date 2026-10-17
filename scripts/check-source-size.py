"""Reject production Java files above the repository's 1000-line limit."""
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    violations = []
    for path in sorted((root / "src/main/java").rglob("*.java")):
        lines = len(path.read_text(encoding="utf-8").splitlines())
        if lines > 1000:
            violations.append(f"{path.relative_to(root).as_posix()}: {lines} lines (limit 1000)")
    if violations:
        print("\n".join(violations))
        return 1
    print("Production Java files: all within 1000 lines")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
