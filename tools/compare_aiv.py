"""Compare the newest Flip3 AIV CSV from each app. Requires only Python 3.10+."""

import argparse
import csv
import math
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path
from statistics import mean


EXPORT_NAME = re.compile(r"AutoISF_(?:.+_)?(\d{8}|\d{4}-\d{2}-\d{2})_(\d{6})\.csv", re.IGNORECASE)
METRICS = ("BGL", "IOB", "Final", "acce", "bg", "pp", "dura", "Req", "SMB", "TBR")
REQUIRED = {"Time", "Notes", "Req", "SMB", "TBR", "IOB", "Final"}


def export_stamp(path):
    match = EXPORT_NAME.fullmatch(path.name)
    if not match:
        raise ValueError(f"Expected a dated AutoISF CSV: {path}")
    return datetime.strptime(match[1].replace("-", "") + match[2], "%Y%m%d%H%M%S")


def newest_export(path):
    if not path.is_dir():
        return export_stamp(path), path
    candidates = [
        (export_stamp(file), file)
        for file in path.rglob("*.csv")
        if file.is_file() and EXPORT_NAME.fullmatch(file.name)
    ]
    if not candidates:
        raise ValueError(f"No dated AutoISF CSVs in {path}")
    latest = max(stamp for stamp, _ in candidates)
    matches = [file for stamp, file in candidates if stamp == latest]
    if len(matches) != 1:
        raise ValueError(f"Multiple exports share the newest timestamp in {path}; pass the CSV explicitly")
    return latest, matches[0]


def local_clock(value):
    value = " ".join(value.split()).upper()
    if value.endswith(("AM", "PM")):
        # Parse English AM/PM regardless of the computer's configured locale.
        clock = datetime.strptime(value[:-2].strip(), "%H:%M")
        if not 1 <= clock.hour <= 12:
            raise ValueError(f"Invalid 12-hour clock: {value}")
        return clock.replace(hour=clock.hour % 12 + 12 * value.endswith("PM")).time()
    return datetime.strptime(value, "%H:%M").time()


def read_export(path, stamp):
    rows = []
    previous = stamp
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source, strict=True)
        headers = set(reader.fieldnames or ())
        missing = REQUIRED - headers
        if missing:
            raise ValueError(f"{path}: missing columns {', '.join(sorted(missing))}")
        for row in reader:
            try:
                if None in row or None in row.values():
                    raise ValueError("wrong number of CSV cells")
                timestamp = datetime.combine(previous.date(), local_clock(row["Time"]))
                if timestamp > previous:
                    timestamp -= timedelta(days=1)
                if stamp - timestamp > timedelta(hours=24):
                    raise ValueError("cannot date rows from newest-first local times")
                previous = timestamp
                rows.append((timestamp, row))
            except ValueError as error:
                raise ValueError(f"{path}:{reader.line_num}: {error}") from error
    if not rows:
        raise ValueError(f"{path}: no AIV rows")
    return rows, headers


def number(value):
    value = value.strip()
    if value in {"", "--"}:
        return None
    result = float(value.replace(",", "."))
    if not math.isfinite(result):
        raise ValueError(f"Invalid numeric value: {value}")
    return result


def by_hour(rows):
    hours = defaultdict(list)
    for timestamp, row in rows:
        hours[timestamp.replace(minute=0)].append((timestamp, row))
    return hours


def notes_seen(rows):
    notes = defaultdict(set)
    for timestamp, row in rows:
        for note in row["Notes"].split("|"):
            note = note.strip()
            if note and note not in {"--", "RawMiss"}:
                notes[note].add(timestamp)
    return notes


def show_exclusive_notes(left, right, label):
    print(f"\n{label}-only notes")
    exclusive = sorted(left.keys() - right.keys(), key=lambda note: (min(left[note]), note))
    if not exclusive:
        print("  None")
    for note in exclusive:
        times = ", ".join(f"{timestamp:%Y-%m-%d %H:%M}" for timestamp in sorted(left[note]))
        print(f"  {note} [{times}]")


def matched_minutes(left, right):
    # A minute is comparable only when each app recorded exactly one cycle in it.
    left_counts = Counter(timestamp for timestamp, _ in left)
    right_counts = Counter(timestamp for timestamp, _ in right)
    right_rows = dict(right)
    return [
        (row, right_rows[timestamp])
        for timestamp, row in left
        if left_counts[timestamp] == right_counts[timestamp] == 1
    ]


def summarize(pairs, field):
    values = [(number(left[field]), number(right[field])) for left, right in pairs]
    complete = [(left, right) for left, right in values if left is not None and right is not None]
    if field == "TBR":
        complete = [(left, right) for left, right in complete if left >= 0 and right >= 0]
    if not complete:
        return "—"
    left, right = zip(*complete)
    aggregate = sum if field == "SMB" else mean
    v3, v4 = aggregate(left), aggregate(right)
    return f"{v3:.3f} / {v4:.3f} / {v4 - v3:+.3f} (n={len(complete)})"


def smb_total(rows):
    values = [number(row["SMB"]) for _, row in rows]
    complete = [value for value in values if value is not None]
    return f"{sum(complete):.3f} (n={len(complete)})" if complete else "—"


def compare(v3_path, v4_path):
    v3_stamp, v3_path = newest_export(v3_path)
    v4_stamp, v4_path = newest_export(v4_path)
    if v3_path.samefile(v4_path):
        raise ValueError("v3 and v4 resolve to the same CSV; use separate app folders or files")
    v3, v3_headers = read_export(v3_path, v3_stamp)
    v4, v4_headers = read_export(v4_path, v4_stamp)
    start = max(v3[-1][0], v4[-1][0])
    end = min(v3[0][0], v4[0][0])
    if start > end:
        raise ValueError("the exports have no overlapping AIV history")
    left = [(time, row) for time, row in v3 if start <= time <= end]
    right = [(time, row) for time, row in v4 if start <= time <= end]
    left_hours, right_hours = by_hour(left), by_hour(right)
    metrics = [field for field in METRICS if field in v3_headers & v4_headers]

    print(f"v3  {v3_path.resolve()}")
    print(f"v4  {v4_path.resolve()}")
    print(f"Overlap (Flip3 local time)  {start:%Y-%m-%d %H:%M} to {end:%Y-%m-%d %H:%M}")
    print("Matched cycles share a unique minute in both apps. Values are v3 / v4 / v4 minus v3.")
    print("SMB is summed in U. Other matched metrics are means. TBR is a rate in U/h.")
    print("TBR averages exclude pairs with negative rates.")
    print("All-row SMB includes unmatched cycles. Both SMB columns summarize exported loop results.")
    print()
    print("\t".join(("Hour", "v3 rows", "v4 rows", "Matched", "v3 SMB all", "v4 SMB all", *metrics)))
    hour = start.replace(minute=0)
    while hour <= end:
        v3_rows, v4_rows = left_hours[hour], right_hours[hour]
        pairs = matched_minutes(v3_rows, v4_rows)
        coverage = (len(v3_rows), len(v4_rows), len(pairs))
        print("\t".join((
            f"{hour:%Y-%m-%d %H:%M}", *map(str, coverage),
            smb_total(v3_rows), smb_total(v4_rows),
            *(summarize(pairs, field) for field in metrics),
        )))
        hour += timedelta(hours=1)

    print("\nNotes are compared by exact text across the overlap. Times are AIV row times.")
    v3_notes, v4_notes = notes_seen(left), notes_seen(right)
    show_exclusive_notes(v3_notes, v4_notes, "v3")
    show_exclusive_notes(v4_notes, v3_notes, "v4")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--v3", required=True, type=Path, help="v3 AutoISF CSV or dedicated export folder")
    parser.add_argument("--v4", required=True, type=Path, help="v4 AutoISF CSV or dedicated export folder")
    args = parser.parse_args()
    try:
        compare(args.v3, args.v4)
    except (OSError, ValueError, csv.Error) as error:
        print(f"compare_aiv: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
