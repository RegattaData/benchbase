# DEVELOPMENT BY MH

## Header
- Document: DEVELOPMENT_BY_MH.md
- Attribution: GitHub Copilot (GPT-5.3-Codex), guided by Michael Hirsch (mh)
- Start date: 2026-06-24
- Summary date: 2026-06-28
- Scope: Changes since origin/main

## Branch Overview
- Best performance branch: origin/mh
- Purpose of origin/mh:
  - Core TPC-C performance work (NewOrder batching, Payment update-returning and rowid patterns, Delivery batching).
  - This branch is the optimized baseline for throughput/latency runs.
- Purpose of origin/mh-debug:
  - Adds runtime JDBC query-event logging and analysis helpers on top of origin/mh.
  - Introduces query log generation and classification tooling.
- Purpose of origin/mh-debug-stmt-anlz:
  - Adds statement-analyzer capture pipeline on top of origin/mh-debug.
  - Produces per-statement analyzer artifacts and integrates analyzer metrics into classifier outputs.

## Commit-by-Commit Summary Since origin/main

The following is the linear commit chain on origin/mh-debug-stmt-anlz (which contains all work from origin/mh and origin/mh-debug plus stmt analyzer additions):

1. a0d9ea4d - NewOrder: batched price query statement
   - Introduced batched price-query pattern in NewOrder.
2. 8cae6651 - NewOrder: batched stock query
   - Added batched stock read in NewOrder.
3. fafbbffd - NewOrder: batched stock update
   - Added batched stock update write path.
4. ea59630b - Payment: update-returning customer by ID
   - Shifted Payment customer update flow to update-returning by ID.
5. 7fd8303f - Payment: update-returning warehouse and district
   - Applied update-returning for warehouse/district updates.
6. 6e9a895c - Payment: customer-by-name uses update-returning
   - Extended update-returning approach to by-name customer flow.
7. 46cf2bca - Delivery: batching all but the initial select/delete
   - Added batching to Delivery for most operations.
8. 876962f2 - Payment: optimize retrieval from customer, actually reverting part of an earlier change
   - Adjusted/reverted parts of a prior Payment retrieval change to improve behavior.
9. cb6a8e42 - Payment: update-returning customer by ID revisited
   - Follow-up tuning of customer-by-ID update-returning path.
10. 3571d717 - Payment: by name select returns rowid, update by rowid
    - Refined by-name flow to select rowid then update via rowid.
11. 852bdbaa - Payment: optimize bad-credit flow functions.
    - Performance tuning for bad-credit update path.
12. b336664d - Add JDBC query event logging instrumentation
    - Added query event logging hooks for JDBC execution flow.
13. 1418fe99 - Add query log timing classifier utility
    - Added scripts/classify_query_log_timings.py.
14. a10333e3 - Instrument plain Statement execution with logging wrapper
    - Added Statement wrapper instrumentation so plain Statement paths are covered.
15. 6b766366 - Added statement analyzer: separate summary.csv file for each statement type
    - Added Regatta statement analyzer capture.
    - Added deterministic stmt_anlz_code propagation and per-statement analyzer outputs.
    - Added log4j suppression for noisy non-fatal Regatta ExceptionHelper category.
16. 8b50d4d5 - classify_query_log_timings.py: add --stmt_anlz_dir support and inject as RDB_STMT_TIME
    - Added optional classifier fusion with statement analyzer summary files.
    - Injects pseudo-event RDB_STMT_TIME from User-Client.Total-Run-Time avg values.

## Side Branches and Frozen Experiments

These side branches preserve experiment snapshots (work-in-progress spikes):

- mh-delete-seperate-selects
  - Frozen experiment: Delivery phase-1 variant using separate selects followed by batch delete.
  - Key branch tip: f71cffaf.
- mh-order-status-parallel-selects
  - Frozen experiment: force/attempt parallel execution of two OrderStatus selects.
  - Key branch tip: 37201ccb.
- mh-payment-by-name-full-rowid
  - Frozen experiment: by-name Payment flow that retrieves rowids, chooses median row, updates via rowid.
  - Key branch tip: ec229375 (with precursor cdf89d41).
- mh-stocklevel-single-statement
  - Frozen experiment: fused single-statement Regatta path for TPCC StockLevel.
  - Key branch tip: 48306b9f.

## How To Run: Debug Log Only

Prerequisites:
- Build with Regatta profile:
  - ./mvnw clean package -P regatta
- Extract distribution:
  - cd target
  - tar xvzf benchbase-regatta.tgz
  - cd benchbase-regatta

Run benchmark with query debug log enabled:
- java -Dbenchbase.querylog=$PWD/query_log.csv -jar benchbase.jar -b tpcc -c config/regatta/sample_tpcc_config.xml --create=true --load=true --execute=true

First-run note:
- Use --create=true --load=true when initializing a new/empty benchmark database.
- For subsequent runs against an already-populated database, skip create/load to save time, for example:
  - java -Dbenchbase.querylog=$PWD/query_log.csv -jar benchbase.jar -b tpcc -c config/regatta/sample_tpcc_config.xml --execute=true

Where files are created:
- Query log CSV: exactly the path passed to -Dbenchbase.querylog (example: target/benchbase-regatta/query_log.csv).

## How To Run: Debug Log + Statement Analyzer Files

Use the same run command above (statement analyzer capture is tied to the logging instrumentation path):
- java -Dbenchbase.querylog=$PWD/query_log.csv -jar benchbase.jar -b tpcc -c config/regatta/sample_tpcc_config.xml --create=true --load=true --execute=true

First-run note:
- Use --create=true --load=true only when the target benchmark schema/data is not already present.
- For repeated measurement runs on the same populated dataset, run execute only to avoid reload overhead:
  - java -Dbenchbase.querylog=$PWD/query_log.csv -jar benchbase.jar -b tpcc -c config/regatta/sample_tpcc_config.xml --execute=true

Where statement analyzer files are created:
- In the runtime working directory, under:
  - benchbase_stmt_anlz_YYYYMMDD_HHMMSS
- Typical location when launched from extracted distribution root:
  - target/benchbase-regatta/benchbase_stmt_anlz_YYYYMMDD_HHMMSS

Contents:
- One JSON per statement code:
  - <stmt_anlz_code>.json
- One summary CSV per statement code:
  - <stmt_anlz_code>_summary.csv

## Classifier Usage

### Case A: Query Log Only
- python3 scripts/classify_query_log_timings.py /path/to/query_log.csv -o /path/to/query_log.classified.xlsx --csv-output /path/to/query_log.classified.csv

### Case B: Query Log + Statement Analyzer Fusion
- python3 scripts/classify_query_log_timings.py /path/to/query_log.csv -o /path/to/query_log.classified.xlsx --csv-output /path/to/query_log.classified.csv --stmt_anlz_dir /path/to/benchbase_stmt_anlz_YYYYMMDD_HHMMSS

Behavior in Case B:
- For QUERY_END, UPDATE_END, and BATCH_END rows, classifier injects duplicate pseudo-event rows with event = RDB_STMT_TIME.
- avg_duration_ns is populated from each statement summary row with tag = User-Client.Total-Run-Time.
- total_duration_ns is computed as avg_duration_ns * combination_count.
- If a matching <stmt_anlz_code>_summary.csv is missing, classifier prints an error line to stderr for that code.

## Quick Reference: Branch Intent
- origin/mh: best-performance branch for core benchmark procedure optimizations.
- origin/mh-debug: observability/debug branch (query event logs + classifier).
- origin/mh-debug-stmt-anlz: deep observability branch (statement analyzer artifact generation + classifier mash-up).
