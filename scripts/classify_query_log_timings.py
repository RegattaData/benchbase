#!/usr/bin/env python3
"""
Classify BenchBase JDBC query log rows by transaction type, SQL template, and event.

Expected input CSV columns:
- txn_id
- txn_type
- sql
- event
- duration_ns

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
import re
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

OUTPUT_COLUMNS = [
    "txn_type",
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


@dataclass
class Aggregator:
    combo_counts: Dict[Tuple[str, str, str], int] = field(default_factory=lambda: defaultdict(int))
    combo_duration_ns: Dict[Tuple[str, str, str], int] = field(default_factory=lambda: defaultdict(int))
    stmt_example_sql: Dict[Tuple[str, str], str] = field(default_factory=dict)
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

        combo_key = (txn_type, template, event)
        stmt_key = (txn_type, template)

        self.combo_counts[combo_key] += 1
        self.combo_duration_ns[combo_key] += duration_ns
        self.stmt_occurrences_by_txn_type[stmt_key] += 1

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


def read_rows(path: str) -> Iterable[Dict[str, str]]:
    csv.field_size_limit(1024 * 1024 * 1024)
    required = {"txn_id", "txn_type", "sql", "event", "duration_ns"}

    with open(path, "r", newline="", encoding="utf-8") as f:
        header_line = f.readline()
        if not header_line:
            raise ValueError("Input file has no header row.")

        header = next(csv.reader([header_line]))
        if not header:
            raise ValueError("Input file has no header row.")

        missing = sorted(required - set(header))
        if missing:
            raise ValueError("Input is missing required columns: " + ", ".join(missing))

        cols = len(header)

        for line in f:
            if not line.strip():
                continue
            parts = _split_csv_line_keep_tail(line, cols)
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


def write_xlsx(output_xlsx: str, datasets: Dict[str, List[Dict[str, Any]]]) -> None:
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
        ws.append(OUTPUT_COLUMNS)
        for row in rows:
            ws.append([row.get(col, "") for col in OUTPUT_COLUMNS])

    wb.save(output_xlsx)


def write_csv(output_csv: str, rows: List[Dict[str, Any]]) -> None:
    with open(output_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=OUTPUT_COLUMNS)
        writer.writeheader()
        writer.writerows(rows)


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
    write_xlsx(output_xlsx, datasets)
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
