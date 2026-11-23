#!/usr/bin/env python3
"""Independent recomputation of benchmark summaries from a raw export (spec §6.3, §12.3).

Usage:
    python3 tools/analysis/recompute_metrics.py <prefix>.records.csv [<prefix>.session.json]

Reads only the per-operation CSV, recomputes per (run, protocol) statistics with the same
documented definitions, and — if the session JSON is given — compares them with the app's
summaries. Exit code 1 on any mismatch. Standard library only.

Definitions (docs/EXPORT_SCHEMA.md):
  * measured ops = is_warmup == false
  * latency stats over successful measured ops only (failures are never zero-latency samples)
  * percentile: nearest rank, rank = ceil(p/100 * n), value = sorted[rank-1]
  * throughput_busy = successes / sum(t_end_ns - t_start_ns) over measured ops
  * throughput_window = successes / (max t_end_ns - min t_start_ns) over measured ops
"""
import csv
import json
import math
import statistics
import sys
from collections import defaultdict

SUPPORTED_SCHEMA = "1.0.0"


def nearest_rank(sorted_vals, p):
    if not sorted_vals:
        return None
    rank = max(1, min(len(sorted_vals), math.ceil(p / 100.0 * len(sorted_vals))))
    return sorted_vals[rank - 1]


def recompute(csv_path):
    groups = defaultdict(list)
    with open(csv_path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["schema_version"] != SUPPORTED_SCHEMA:
                raise SystemExit(f"Unsupported schema {row['schema_version']} (expected {SUPPORTED_SCHEMA})")
            groups[(row["run_id"], row["protocol"])].append(row)
    out = {}
    for key, rows in sorted(groups.items()):
        measured = [r for r in rows if r["is_warmup"] == "false"]
        ok = [r for r in measured if r["success"] == "true"]
        rtts = sorted(int(r["rtt_ns"]) for r in ok)
        ms = [v / 1e6 for v in rtts]
        busy = sum(int(r["t_end_ns"]) - int(r["t_start_ns"]) for r in measured)
        window = (max(int(r["t_end_ns"]) for r in measured) - min(int(r["t_start_ns"]) for r in measured)) if measured else 0
        modes = sorted({r["execution_mode"] for r in rows})
        out[key] = {
            "attempts": len(measured),
            "successes": len(ok),
            "failures": len(measured) - len(ok),
            "timeouts": sum(1 for r in measured if r["error_category"] == "TIMEOUT"),
            "success_rate": (len(ok) / len(measured)) if measured else 0.0,
            "latency_ms": {
                "n": len(ms),
                "min": ms[0] if ms else None,
                "mean": statistics.fmean(ms) if ms else None,
                "p50": nearest_rank(rtts, 50) / 1e6 if rtts else None,
                "p90": nearest_rank(rtts, 90) / 1e6 if rtts else None,
                "p95": nearest_rank(rtts, 95) / 1e6 if rtts else None,
                "p99": nearest_rank(rtts, 99) / 1e6 if rtts else None,
                "max": ms[-1] if ms else None,
            },
            "throughput_busy_ops_per_s": (len(ok) / (busy / 1e9)) if busy > 0 else None,
            "throughput_window_ops_per_s": (len(ok) / (window / 1e9)) if window > 0 else None,
            "execution_modes": modes,
            "warmup_excluded": len(rows) - len(measured),
        }
    return out


def close(a, b, tol=1e-6):
    if a is None or b is None:
        return a is None and b is None
    return abs(float(a) - float(b)) <= tol * max(1.0, abs(float(a)))


def compare(recomputed, json_path):
    with open(json_path, encoding="utf-8") as f:
        doc = json.load(f)
    if doc.get("schema_version") != SUPPORTED_SCHEMA:
        raise SystemExit(f"JSON schema {doc.get('schema_version')} != {SUPPORTED_SCHEMA}")
    problems = []
    for run in doc["runs"]:
        for s in run["summary"]:
            key = (run["run_id"], s["protocol"])
            r = recomputed.get(key)
            if r is None:
                problems.append(f"{key}: in JSON but not in CSV")
                continue
            for k in ("attempts", "successes", "failures", "timeouts", "warmup_excluded"):
                if r[k] != s[k]:
                    problems.append(f"{key} {k}: csv={r[k]} json={s[k]}")
            for k in ("success_rate", "throughput_busy_ops_per_s", "throughput_window_ops_per_s"):
                if not close(r[k], s[k]):
                    problems.append(f"{key} {k}: csv={r[k]} json={s[k]}")
            for k, v in r["latency_ms"].items():
                if k == "n":
                    if v != s["latency_ms"]["n"]:
                        problems.append(f"{key} latency n mismatch")
                elif not close(v, s["latency_ms"][k]):
                    problems.append(f"{key} latency {k}: csv={v} json={s['latency_ms'][k]}")
    return problems


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    rec = recompute(argv[1])
    for (run, proto), s in rec.items():
        l = s["latency_ms"]
        fmt = lambda v: "-" if v is None else f"{v:.3f}"
        print(f"{run[:8]} {proto:8s} modes={','.join(s['execution_modes'])} n={s['attempts']} ok={s['successes']} "
              f"timeouts={s['timeouts']} p50={fmt(l['p50'])} p95={fmt(l['p95'])} p99={fmt(l['p99'])} ms")
    if len(argv) > 2:
        problems = compare(rec, argv[2])
        if problems:
            print("MISMATCH:\n  " + "\n  ".join(problems))
            return 1
        print("OK: every summary in the JSON export was reproduced independently from the CSV.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
