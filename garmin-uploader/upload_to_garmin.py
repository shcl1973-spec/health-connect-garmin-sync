from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from garminconnect import Garmin


DEFAULT_STATE_FILE = "uploaded-records.json"


@dataclass(frozen=True)
class WeightEntry:
    time: str
    kg: float


@dataclass(frozen=True)
class BloodPressureEntry:
    time: str
    systolic: int
    diastolic: int
    pulse: int | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Upload Health Connect weight and blood-pressure JSON to Garmin Connect."
    )
    parser.add_argument("json_file", help="JSON exported by the Android Health Connect exporter.")
    parser.add_argument(
        "--state",
        default=DEFAULT_STATE_FILE,
        help=f"Local upload state file. Default: {DEFAULT_STATE_FILE}",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print what would be uploaded without writing to Garmin.",
    )
    parser.add_argument(
        "--allow-missing-pulse",
        action="store_true",
        help="Upload blood-pressure entries without pulse using fallback pulse 0. Not recommended.",
    )
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError("export JSON must contain an object at the top level")
    return data


def parse_export(data: dict[str, Any]) -> tuple[list[WeightEntry], list[BloodPressureEntry]]:
    weights: list[WeightEntry] = []
    blood_pressures: list[BloodPressureEntry] = []

    for item in data.get("weight", []):
        weights.append(
            WeightEntry(
                time=require_iso_time(item, "weight"),
                kg=float(item["kg"]),
            )
        )

    for item in data.get("blood_pressure", []):
        pulse = item.get("pulse")
        blood_pressures.append(
            BloodPressureEntry(
                time=require_iso_time(item, "blood_pressure"),
                systolic=int(item["systolic"]),
                diastolic=int(item["diastolic"]),
                pulse=int(pulse) if pulse is not None else None,
            )
        )

    return weights, blood_pressures


def require_iso_time(item: dict[str, Any], kind: str) -> str:
    value = item.get("time")
    if not isinstance(value, str):
        raise ValueError(f"{kind} record is missing string field 'time'")
    datetime.fromisoformat(value.replace("Z", "+00:00"))
    return value


def load_state(path: Path) -> set[str]:
    if not path.exists():
        return set()
    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, list):
        raise ValueError("state file must contain a JSON array")
    return {str(item) for item in data}


def save_state(path: Path, uploaded_ids: set[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(sorted(uploaded_ids), handle, indent=2)
        handle.write("\n")


def record_id(kind: str, value: Any) -> str:
    payload = json.dumps(
        {"kind": kind, "record": value.__dict__},
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def get_garmin_client() -> Garmin:
    email = os.getenv("GARMIN_EMAIL") or input("Garmin email: ").strip()
    password = os.getenv("GARMIN_PASSWORD") or getpass.getpass("Garmin password: ")

    client = Garmin(
        email,
        password,
        prompt_mfa=lambda: input("Garmin MFA code: ").strip(),
    )
    client.login("~/.garminconnect")
    return client


def upload(
    weights: list[WeightEntry],
    blood_pressures: list[BloodPressureEntry],
    state_path: Path,
    dry_run: bool,
    allow_missing_pulse: bool,
) -> None:
    uploaded_ids = load_state(state_path)
    client = None if dry_run else get_garmin_client()

    uploaded_now = 0
    skipped_duplicates = 0
    skipped_missing_pulse = 0

    for entry in weights:
        rid = record_id("weight", entry)
        if rid in uploaded_ids:
            skipped_duplicates += 1
            continue
        print(f"weight {entry.kg:.2f} kg at {entry.time}")
        if not dry_run:
            assert client is not None
            client.add_weigh_in(weight=entry.kg, unitKey="kg", timestamp=entry.time)
            uploaded_ids.add(rid)
            save_state(state_path, uploaded_ids)
        uploaded_now += 1

    for entry in blood_pressures:
        rid = record_id("blood_pressure", entry)
        if rid in uploaded_ids:
            skipped_duplicates += 1
            continue
        if entry.pulse is None and not allow_missing_pulse:
            skipped_missing_pulse += 1
            print(
                "skip blood pressure "
                f"{entry.systolic}/{entry.diastolic} at {entry.time}: missing pulse"
            )
            continue

        pulse = entry.pulse if entry.pulse is not None else 0
        print(
            "blood pressure "
            f"{entry.systolic}/{entry.diastolic}, pulse {pulse}, at {entry.time}"
        )
        if not dry_run:
            assert client is not None
            client.set_blood_pressure(
                systolic=entry.systolic,
                diastolic=entry.diastolic,
                pulse=pulse,
                timestamp=entry.time,
                notes="Imported from Health Connect",
            )
            uploaded_ids.add(rid)
            save_state(state_path, uploaded_ids)
        uploaded_now += 1

    print(
        "done: "
        f"{uploaded_now} processed, "
        f"{skipped_duplicates} duplicate skipped, "
        f"{skipped_missing_pulse} missing-pulse skipped"
    )


def main() -> None:
    args = parse_args()
    data = load_json(Path(args.json_file))
    weights, blood_pressures = parse_export(data)
    upload(
        weights=weights,
        blood_pressures=blood_pressures,
        state_path=Path(args.state),
        dry_run=args.dry_run,
        allow_missing_pulse=args.allow_missing_pulse,
    )


if __name__ == "__main__":
    main()
