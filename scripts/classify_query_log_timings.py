#!/usr/bin/env python3
"""
Classify BenchBase JDBC query log rows by transaction type, SQL template, and event.

Expected input CSV columns:
- txn_id
- txn_type
- sql
- event
- duration_ns
- stmt_anlz_code (optional; derived if absent)

Default output is XLSX. Optional CSV output can also be requested.

Aggregation output columns:
- txn_type
- statement_template
- example_statement
- event
- combination_count
- total_duration_ns
- avg_duration_ns
- txn_type_count
- statement_occurrence_count
- avg_statement_occurrences_per_txn
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, Iterable, List, Optional, Set, Tuple


SQL_EVENT_PREFIXES = (
    "QUERY_",
    "UPDATE_",
    "EXECUTE_",
    "BATCH_",
    "RS_",
    "PREPARE_",
)

TXN_EVENTS = {
    "TXN_START",
    "TXN_COMMIT",
    "TXN_ROLLBACK",
    "CONN_COMMIT",
    "CONN_ROLLBACK",
    "CONN_CLOSE",
}

RDB_SOURCE_EVENTS = {"QUERY_END", "BATCH_END", "UPDATE_END"}
RDB_PSEUDO_EVENT = "RDB_STMT_TIME"
RDB_RUNTIME_TAG = "User-Client.Total-Run-Time"

TXN_TIME_PSEUDO_EVENT = "TXN_TIME"
TXN_END_EVENTS = {"TXN_COMMIT", "TXN_ROLLBACK"}

OUTPUT_COLUMNS = [
    "txn_type",
    "stmt_id",
    "statement_template",
    "example_statement",
    "event",
    "combination_count",
    "total_duration_ns",
    "avg_duration_ns",
    "txn_type_count",
    "statement_occurrence_count",
    "avg_statement_occurrences_per_txn",
]

# Columns for the driver_attribution tab (one row per stmt_id).
ATTRIBUTION_COLUMNS = [
    "stmt_id",
    "txn_type",
    "statement_template",
    "exec_event",
    "exec_count",
    "QUERY_END_avg_ns",
    "RDB_STMT_TIME_avg_ns",
    "driver_overhead_ns",
    "rs_time_per_exec_ns",
    "rs_get_string_per_exec_ns",
]


@dataclass
class Aggregator:
    combo_counts: Dict[Tuple[str, str, str], int] = field(default_factory=lambda: defaultdict(int))
    combo_duration_ns: Dict[Tuple[str, str, str], int] = field(default_factory=lambda: defaultdict(int))
    stmt_example_sql: Dict[Tuple[str, str], str] = field(default_factory=dict)
    stmt_code_by_txn_type_stmt: Dict[Tuple[str, str], str] = field(default_factory=dict)
    txn_ids_by_type: Dict[str, Set[str]] = field(default_factory=lambda: defaultdict(set))
    stmt_occurrences_by_txn_type: Dict[Tuple[str, str], int] = field(
        default_factory=lambda: defaultdict(int)
    )

    def add_row(self, row: Dict[str, str]) -> None:
        txn_id = (row.get("txn_id") or "").strip()
        txn_type = (row.get("txn_type") or "").strip()
        event = (row.get("event") or "").strip()
        duration_ns = parse_duration_ns(row.get("duration_ns") or "0")

        decoded_sql = decode_sql_field(row.get("sql") or "")
        template = normalize_sql_template(decoded_sql)
        stmt_code = normalize_stmt_anlz_code(row.get("stmt_anlz_code") or "")
        if not stmt_code:
            stmt_code = derive_stmt_code(txn_type, template)

        combo_key = (txn_type, template, event)
        stmt_key = (txn_type, template)

        self.combo_counts[combo_key] += 1
        self.combo_duration_ns[combo_key] += duration_ns
        self.stmt_occurrences_by_txn_type[stmt_key] += 1
        if stmt_code:
            self.stmt_code_by_txn_type_stmt[stmt_key] = stmt_code

        current_example = self.stmt_example_sql.get(stmt_key)
        if current_example is None or sql_example_score(decoded_sql) > sql_example_score(current_example):
            self.stmt_example_sql[stmt_key] = decoded_sql

        if txn_id:
            self.txn_ids_by_type[txn_type].add(txn_id)

    def to_rows(self) -> List[Dict[str, Any]]:
        rows: List[Dict[str, Any]] = []
        sorted_keys = sorted(self.combo_counts.keys(), key=lambda k: (k[0], k[1], k[2]))

        for txn_type, template, event in sorted_keys:
            combo_key = (txn_type, template, event)
            stmt_key = (txn_type, template)

            combination_count = self.combo_counts[combo_key]
            total_duration = self.combo_duration_ns[combo_key]
            avg_duration = total_duration / combination_count if combination_count else 0.0

            txn_type_count = len(self.txn_ids_by_type[txn_type])
            statement_occurrence_count = self.stmt_occurrences_by_txn_type[stmt_key]
            avg_statement_occurrences_per_txn = (
                statement_occurrence_count / txn_type_count if txn_type_count else 0.0
            )

            rows.append(
                {
                    "txn_type": txn_type,
                    "stmt_id": self.stmt_code_by_txn_type_stmt.get(stmt_key, ""),
                    "statement_template": template,
                    "example_statement": self.stmt_example_sql.get(stmt_key, ""),
                    "event": event,
                    "combination_count": combination_count,
                    "total_duration_ns": total_duration,
                    "avg_duration_ns": round(avg_duration, 3),
                    "txn_type_count": txn_type_count,
                    "statement_occurrence_count": statement_occurrence_count,
                    "avg_statement_occurrences_per_txn": round(
                        avg_statement_occurrences_per_txn, 6
                    ),
                }
            )

        return rows


def decode_sql_field(raw_sql: str) -> str:
    """Decode CSV-escaped SQL field from log rows."""
    text = (raw_sql or "").strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        text = text[1:-1].replace('""', '"')
    return text


def normalize_sql_template(sql: str) -> str:
    """Best-effort SQL template normalization using lightweight regex rules."""
    if sql is None:
        return ""

    s = sql.strip()
    s = s.rstrip(";")

    s = re.sub(r"E'([^'\\]|\\.)*'", "?", s)
    s = re.sub(r"'([^']|'')*'", "?", s)

    s = re.sub(r"(?<![\w.])[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?(?![\w.])", "?", s)
    s = re.sub(r"(?<!\w)0x[0-9a-fA-F]+(?!\w)", "?", s)

    s = re.sub(
        r"\bIN\s*\(\s*(?:\?\s*,\s*){2,}\?\s*\)",
        "IN (?)",
        s,
        flags=re.IGNORECASE,
    )

    s = re.sub(
        r"\bVALUES\s*\((?:[^)(]|\([^)(]*\))*\)(?:\s*,\s*\((?:[^)(]|\([^)(]*\))*\))+",
        "VALUES (...)",
        s,
        flags=re.IGNORECASE,
    )

    s = re.sub(r"\s+", " ", s).strip()
    s = s.upper()
    return s


def parse_duration_ns(raw: str) -> int:
    text = (raw or "").strip()
    if text == "":
        return 0
    try:
        return int(text)
    except ValueError:
        return int(float(text))


def derive_stmt_code(txn_type: str, statement_template: str) -> str:
    safe_txn = re.sub(r"[^a-z0-9]+", "_", (txn_type or "unknown").lower()).strip("_")
    if not safe_txn:
        safe_txn = "unknown"
    key = f"{txn_type}::{statement_template}"
    suffix = hashlib.sha1(key.encode("utf-8")).hexdigest()[:10]
    return f"{safe_txn}_{suffix}"

def sql_example_score(sql: str) -> Tuple[int, int]:
    text = sql or ""
    has_literal = bool(re.search(r"\d|'.*?'", text))
    return (1 if has_literal else 0, len(text))


def _split_csv_line_keep_tail(line: str, columns: int) -> List[str]:
    """Split a CSV line into fixed columns while keeping tail commas in the last column."""
    parts = line.rstrip("\r\n").split(",", columns - 1)
    if len(parts) < columns:
        parts.extend([""] * (columns - len(parts)))
    return parts


def normalize_stmt_anlz_code(raw: str) -> str:
    text = (raw or "").strip()
    if not text:
        return ""

    # Legacy malformed rows may collapse sql and stmt_anlz_code into one CSV field.
    try:
        parsed = next(csv.reader([text]))
        if parsed:
            return parsed[-1].strip().strip('"')
    except Exception:
        pass

    if "," in text:
        return text.rsplit(",", 1)[-1].strip().strip('"')
    return text.strip('"')


def read_rows(path: str) -> Iterable[Dict[str, str]]:
    csv.field_size_limit(1024 * 1024 * 1024)
    required = {"txn_id", "txn_type", "sql", "event", "duration_ns"}

    with open(path, "r", newline="", encoding="utf-8") as f:
        reader = csv.reader(f)
        header = next(reader, None)
        if not header:
            raise ValueError("Input file has no header row.")

        missing = sorted(required - set(header))
        if missing:
            raise ValueError("Input is missing required columns: " + ", ".join(missing))

        cols = len(header)

        for parts in reader:
            if not parts:
                continue
            if len(parts) < cols:
                parts.extend([""] * (cols - len(parts)))
            elif len(parts) > cols:
                parts = parts[: cols - 1] + [",".join(parts[cols - 1 :])]
            yield dict(zip(header, parts))


def is_sql_event(event: str) -> bool:
    return event.startswith(SQL_EVENT_PREFIXES) or event == "METADATA_FETCH"


def is_txn_event(event: str) -> bool:
    return event in TXN_EVENTS


def make_event_predicate(mode: str, include_events: Optional[str]) -> Callable[[str], bool]:
    if mode == "all":
        return lambda _e: True
    if mode == "sql":
        return is_sql_event
    if mode == "txn":
        return is_txn_event

    requested = {
        token.strip()
        for token in (include_events or "").split(",")
        if token.strip()
    }
    if not requested:
        raise ValueError("--include-events must be provided for --events custom")
    return lambda e: e in requested


def classify(input_csv: str, events_mode: str, include_events: Optional[str]) -> Dict[str, List[Dict[str, Any]]]:
    # Default output set includes all + two focused pivots when mode=all.
    if events_mode == "all":
        aggregators: Dict[str, Tuple[Aggregator, Callable[[str], bool]]] = {
            "all_events": (Aggregator(), lambda _e: True),
            "sql_events": (Aggregator(), is_sql_event),
            "txn_events": (Aggregator(), is_txn_event),
        }
    else:
        predicate = make_event_predicate(events_mode, include_events)
        sheet_name = "custom_events" if events_mode == "custom" else f"{events_mode}_events"
        aggregators = {sheet_name: (Aggregator(), predicate)}

    for row in read_rows(input_csv):
        event = (row.get("event") or "").strip()
        for agg, predicate in aggregators.values():
            if predicate(event):
                agg.add_row(row)

    return {sheet: agg.to_rows() for sheet, (agg, _pred) in aggregators.items()}


def write_xlsx(
    output_xlsx: str,
    datasets: Dict[str, List[Dict[str, Any]]],
    column_overrides: Optional[Dict[str, List[str]]] = None,
) -> None:
    try:
        from openpyxl import Workbook
    except ImportError as exc:
        raise RuntimeError(
            "openpyxl is required for XLSX output. Install it with: pip install openpyxl"
        ) from exc

    wb = Workbook()
    # Remove default empty sheet.
    wb.remove(wb.active)

    for sheet_name, rows in datasets.items():
        ws = wb.create_sheet(title=sheet_name[:31])
        cols = (column_overrides or {}).get(sheet_name, OUTPUT_COLUMNS)
        ws.append(cols)
        for row in rows:
            ws.append([row.get(col, "") for col in cols])

    wb.save(output_xlsx)


def write_csv(output_csv: str, rows: List[Dict[str, Any]]) -> None:
    with open(output_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=OUTPUT_COLUMNS)
        writer.writeheader()
        writer.writerows(rows)


def load_stmt_anlz_avg_duration_ns(stmt_anlz_dir: str) -> Dict[str, float]:
    result: Dict[str, float] = {}
    if not os.path.isdir(stmt_anlz_dir):
        return result

    suffix = "_summary.csv"
    for name in os.listdir(stmt_anlz_dir):
        if not name.endswith(suffix):
            continue
        stmt_code = name[: -len(suffix)]
        csv_path = os.path.join(stmt_anlz_dir, name)
        avg_ns = parse_stmt_summary_avg_duration_ns(csv_path)
        if avg_ns is not None:
            result[stmt_code] = avg_ns
    return result


def parse_stmt_summary_avg_duration_ns(csv_path: str) -> Optional[float]:
    """Read *_summary.csv and return User-Client.Total-Run-Time avg converted by *1000."""
    try:
        with open(csv_path, "r", newline="", encoding="utf-8") as f:
            reader = csv.reader(f)
            rows = list(reader)
    except OSError:
        return None

    header_idx = -1
    tag_col = -1
    avg_col = -1

    for i, row in enumerate(rows):
        lowered = [c.strip().lower() for c in row]
        if "tag" in lowered and "avg" in lowered:
            header_idx = i
            tag_col = lowered.index("tag")
            avg_col = lowered.index("avg")
            break

    if header_idx < 0:
        return None

    for row in rows[header_idx + 1 :]:
        if tag_col >= len(row) or avg_col >= len(row):
            continue
        if row[tag_col].strip() != RDB_RUNTIME_TAG:
            continue
        raw_avg = row[avg_col].strip()
        if not raw_avg:
            return None
        try:
            # Requested conversion factor from stmt analyzer avg units.
            return float(raw_avg) * 1000.0
        except ValueError:
            return None

    return None


def compute_txn_time_by_type(input_csv: str) -> Dict[str, Tuple[float, int]]:
    """
    Second pass over the CSV to compute wall-clock transaction duration per txn_type.

    For each txn_id, elapsed time = timestamp_ns(TXN_COMMIT or TXN_ROLLBACK) −
    timestamp_ns(TXN_START).  Each retry gets its own txn_id, so rollback
    attempts are counted separately from commits.

    Returns {txn_type: (total_duration_ns, attempt_count)}.
    """
    txn_start_ns: Dict[str, int] = {}    # txn_id -> TXN_START timestamp_ns
    txn_type_by_id: Dict[str, str] = {}  # txn_id -> txn_type
    total_ns: Dict[str, float] = defaultdict(float)
    count: Dict[str, int] = defaultdict(int)

    for row in read_rows(input_csv):
        event = (row.get("event") or "").strip()
        txn_id = (row.get("txn_id") or "").strip()
        txn_type = (row.get("txn_type") or "").strip()
        if not txn_id:
            continue

        if event == "TXN_START":
            try:
                ts = int((row.get("timestamp_ns") or "0").strip())
            except ValueError:
                ts = 0
            txn_start_ns[txn_id] = ts
            txn_type_by_id[txn_id] = txn_type

        elif event in TXN_END_EVENTS:
            if txn_id not in txn_start_ns:
                continue
            try:
                ts = int((row.get("timestamp_ns") or "0").strip())
            except ValueError:
                continue
            elapsed = ts - txn_start_ns.pop(txn_id)
            t = txn_type_by_id.pop(txn_id, txn_type)
            if elapsed >= 0:
                total_ns[t] += elapsed
                count[t] += 1

    return {t: (total_ns[t], count[t]) for t in total_ns if count[t] > 0}


def add_txn_time_rows(
    datasets: Dict[str, List[Dict[str, Any]]],
    txn_time_by_type: Dict[str, Tuple[float, int]],
) -> None:
    """
    Inject TXN_TIME pseudo-event rows into sheets that carry transaction-level
    events (txn_events, all_events; not sql_events).

    Each row has statement_template="" and stmt_anlz_code="" because TXN_TIME
    is a transaction-level aggregate, not a per-statement one.
    combination_count reflects the number of transaction attempts (commits +
    rollbacks) seen for that txn_type.
    """
    TXN_SHEET_NAMES = {"all_events", "txn_events"}

    for sheet_name, rows in datasets.items():
        # Include named txn sheets; for custom/other sheets check for txn rows.
        if sheet_name not in TXN_SHEET_NAMES:
            has_txn = any(str(r.get("event", "")).strip() in TXN_EVENTS for r in rows)
            if not has_txn:
                continue

        for txn_type, (total, cnt) in sorted(txn_time_by_type.items()):
            avg = total / cnt if cnt else 0.0
            rows.append(
                {
                    "txn_type": txn_type,
                    "stmt_id": "",
                    "statement_template": "",
                    "example_statement": "",
                    "event": TXN_TIME_PSEUDO_EVENT,
                    "combination_count": cnt,
                    "total_duration_ns": round(total, 3),
                    "avg_duration_ns": round(avg, 3),
                    "txn_type_count": cnt,
                    "statement_occurrence_count": cnt,
                    "avg_statement_occurrences_per_txn": 1.0,
                }
            )


def build_driver_attribution_rows(
    datasets: Dict[str, List[Dict[str, Any]]]
) -> List[Dict[str, Any]]:
    """
    Build one row per stmt_anlz_code summarising how time is attributed across
    the driver and the Regatta server.

    Reads from the sql_events sheet (which already has RDB_STMT_TIME injected)
    or falls back to all_events.

    Column meanings
    ---------------
    QUERY_END_avg_ns     Avg duration of the execution-end event (QUERY_END /
                         UPDATE_END / BATCH_END) per call.  This is the total
                         round-trip time as seen by the JDBC layer: network +
                         server processing + driver overhead.
    RDB_STMT_TIME_avg_ns Avg server-side processing time from the stmt analyzer
                         (blank when --stmt_anlz_dir was not supplied).
    driver_overhead_ns   QUERY_END_avg_ns - RDB_STMT_TIME_avg_ns: network RTT +
                         driver serialisation/deserialisation within the call.
    rs_time_per_exec_ns  sum(RS_*.total_ns) / exec_count: result-set reading
                         time that falls outside QUERY_END (lazy row fetching,
                         cursor close, column getters).
    """
    source = datasets.get("sql_events") or datasets.get("all_events", [])
    if not source:
        return []

    exec_total: Dict[str, float] = defaultdict(float)
    exec_count_map: Dict[str, int] = defaultdict(int)
    exec_event_name: Dict[str, str] = {}
    rdb_total: Dict[str, float] = defaultdict(float)
    rdb_count_map: Dict[str, int] = defaultdict(int)
    rs_total: Dict[str, float] = defaultdict(float)
    rs_get_string_total: Dict[str, float] = defaultdict(float)
    seen_txn_types: Dict[str, Set[str]] = defaultdict(set)
    seen_templates: Dict[str, str] = {}

    for row in source:
        code = str(row.get("stmt_id") or "").strip()
        if not code:
            continue
        event = str(row.get("event") or "").strip()
        try:
            count = int(float(row.get("combination_count") or 0))
            total_ns = float(row.get("total_duration_ns") or 0)
        except (TypeError, ValueError):
            continue

        seen_txn_types[code].add(str(row.get("txn_type") or ""))
        if code not in seen_templates:
            seen_templates[code] = str(row.get("statement_template") or "")

        if event in RDB_SOURCE_EVENTS:
            exec_total[code] += total_ns
            exec_count_map[code] += count
            exec_event_name[code] = event
        elif event == RDB_PSEUDO_EVENT:
            rdb_total[code] += total_ns
            rdb_count_map[code] += count
        elif event.startswith("RS_"):
            rs_total[code] += total_ns
            if event == "RS_GET_STRING":
                rs_get_string_total[code] += total_ns

    rows: List[Dict[str, Any]] = []
    for code in sorted(exec_count_map.keys()):
        n = exec_count_map[code]
        exec_avg = exec_total[code] / n if n else 0.0

        rdb_n = rdb_count_map[code]
        rdb_avg: Optional[float] = rdb_total[code] / rdb_n if rdb_n else None
        driver_ns: Any = round(exec_avg - rdb_avg, 1) if rdb_avg is not None else ""
        rs_per_exec = rs_total.get(code, 0.0) / n if n else 0.0
        rs_get_string_per_exec = rs_get_string_total.get(code, 0.0) / n if n else 0.0

        txn_type_str = ", ".join(sorted(seen_txn_types[code] - {""})) or ""
        rows.append(
            {
                "stmt_id": code,
                "txn_type": txn_type_str,
                "statement_template": seen_templates.get(code, ""),
                "exec_event": exec_event_name.get(code, ""),
                "exec_count": n,
                "QUERY_END_avg_ns": round(exec_avg, 1),
                "RDB_STMT_TIME_avg_ns": round(rdb_avg, 1) if rdb_avg is not None else "",
                "driver_overhead_ns": driver_ns,
                "rs_time_per_exec_ns": round(rs_per_exec, 1),
                "rs_get_string_per_exec_ns": round(rs_get_string_per_exec, 1),
            }
        )
    return rows


def add_rdb_stmt_time_rows(
    datasets: Dict[str, List[Dict[str, Any]]], stmt_avg_duration_ns: Dict[str, float]
) -> Tuple[Set[str], Set[str]]:
    missing_events_by_code: Dict[str, Set[str]] = defaultdict(set)

    for sheet_name, rows in datasets.items():
        expanded: List[Dict[str, Any]] = []
        for row in rows:
            expanded.append(row)

            event = str(row.get("event", "")).strip()
            if event not in RDB_SOURCE_EVENTS:
                continue

            stmt_code = normalize_stmt_anlz_code(str(row.get("stmt_id", "")))
            if not stmt_code:
                continue

            avg_duration_ns = stmt_avg_duration_ns.get(stmt_code)
            if avg_duration_ns is None:
                missing_events_by_code[stmt_code].add(event)
                continue

            try:
                combination_count = float(row.get("combination_count", 0) or 0)
            except (TypeError, ValueError):
                combination_count = 0.0

            pseudo = dict(row)
            pseudo["event"] = RDB_PSEUDO_EVENT
            pseudo["avg_duration_ns"] = round(avg_duration_ns, 3)
            pseudo["total_duration_ns"] = round(avg_duration_ns * combination_count, 3)
            expanded.append(pseudo)

        datasets[sheet_name] = expanded

    report_missing: Set[str] = set()
    skipped_batch_only: Set[str] = set()
    for stmt_code, events in missing_events_by_code.items():
        # Current stmt_anlz capture does not generate analyzer artifacts for pure batch statements.
        if events and events.issubset({"BATCH_END"}):
            skipped_batch_only.add(stmt_code)
            continue
        report_missing.add(stmt_code)

    return report_missing, skipped_batch_only


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Classify BenchBase JDBC timing rows by txn_type, SQL template, and event. "
            "Writes XLSX by default and can optionally emit a CSV for the primary sheet."
        )
    )
    parser.add_argument("input_csv", help="Path to input timing CSV")
    parser.add_argument(
        "-o",
        "--output",
        dest="output_xlsx",
        default=None,
        help="Output XLSX path (default: <input>.classified.xlsx)",
    )
    parser.add_argument(
        "--csv-output",
        dest="output_csv",
        default=None,
        help=(
            "Optional CSV output path. If --events=all, this writes the all_events sheet as CSV."
        ),
    )
    parser.add_argument(
        "--events",
        choices=["all", "sql", "txn", "custom"],
        default="all",
        help="Event filter mode for aggregation (default: all)",
    )
    parser.add_argument(
        "--include-events",
        default=None,
        help="Comma-separated event names to include when --events=custom",
    )
    parser.add_argument(
        "--stmt_anlz_dir",
        default=None,
        help=(
            "Optional directory containing per-statement *_summary.csv files. "
            "When provided, injects RDB_STMT_TIME pseudo-events using "
            "User-Client.Total-Run-Time avg values."
        ),
    )
    return parser


def main() -> None:
    parser = build_arg_parser()
    args = parser.parse_args()

    output_xlsx = (
        args.output_xlsx
        if args.output_xlsx
        else (args.input_csv.rsplit(".", 1)[0] + ".classified.xlsx")
    )

    datasets = classify(args.input_csv, args.events, args.include_events)

    txn_times = compute_txn_time_by_type(args.input_csv)
    add_txn_time_rows(datasets, txn_times)

    if args.stmt_anlz_dir:
        stmt_avgs = load_stmt_anlz_avg_duration_ns(args.stmt_anlz_dir)
        missing_codes, skipped_batch_only = add_rdb_stmt_time_rows(datasets, stmt_avgs)
        for stmt_code in sorted(missing_codes):
            missing_path = os.path.join(args.stmt_anlz_dir, f"{stmt_code}_summary.csv")
            print(
                f"ERROR: missing summary CSV for stmt_anlz_code '{stmt_code}': {missing_path}",
                file=sys.stderr,
            )
        for stmt_code in sorted(skipped_batch_only):
            print(
                (
                    "INFO: no summary CSV for batch-only stmt_anlz_code "
                    f"'{stmt_code}' (BATCH_END has no stmt_anlz artifact)"
                ),
                file=sys.stderr,
            )

    # Build driver-attribution tab after all pseudo-events have been injected.
    attr_rows = build_driver_attribution_rows(datasets)
    if attr_rows:
        datasets["driver_attribution"] = attr_rows

    write_xlsx(
        output_xlsx,
        datasets,
        column_overrides={"driver_attribution": ATTRIBUTION_COLUMNS},
    )
    print(f"Wrote XLSX: {output_xlsx}")

    if args.output_csv:
        if "all_events" in datasets:
            primary_rows = datasets["all_events"]
        else:
            # Only one sheet exists for filtered modes.
            primary_rows = next(iter(datasets.values()), [])
        write_csv(args.output_csv, primary_rows)
        print(f"Wrote CSV: {args.output_csv}")


if __name__ == "__main__":
    main()
