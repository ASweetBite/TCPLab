#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TCP Reno/NewReno 日志自动分析脚本。

用法：
    python tools/analyze_tcp_log.py tcp_newreno.log
    python tools/analyze_tcp_log.py tcp_reno.log --csv events.csv

输出：
    1. 事件计数
    2. cwnd / ssthresh 范围
    3. 超时、快重传、partial ACK、full ACK 等关键事件
    4. 平均每次丢包恢复所需事件数的粗略统计
"""

import argparse
import csv
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional


LOG_RE = re.compile(
    r"(?P<time>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3})\s+"
    r"EVENT:\s*(?P<event>\S+)\s+"
    r"base:\s*(?P<base>\d+)\s+"
    r"next:\s*(?P<next>\d+)\s+"
    r"cwnd:\s*(?P<cwnd>\d+)\s+"
    r"ssthresh:\s*(?P<ssthresh>\d+)\s+"
    r"state:\s*(?P<state>\S+)\s+"
    r"info:\s*(?P<info>.*)"
)


@dataclass
class Event:
    idx: int
    time: str
    event: str
    base: int
    next_seq: int
    cwnd: int
    ssthresh: int
    state: str
    info: str


def parse_log(path: Path) -> List[Event]:
    events: List[Event] = []
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        m = LOG_RE.search(line)
        if not m:
            continue
        events.append(Event(
            idx=len(events),
            time=m.group("time"),
            event=m.group("event"),
            base=int(m.group("base")),
            next_seq=int(m.group("next")),
            cwnd=int(m.group("cwnd")),
            ssthresh=int(m.group("ssthresh")),
            state=m.group("state"),
            info=m.group("info"),
        ))
    return events


def write_csv(events: List[Event], path: Path) -> None:
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "idx", "time", "event", "base", "next_seq", "cwnd", "ssthresh", "state", "info"
        ])
        writer.writeheader()
        for e in events:
            writer.writerow(e.__dict__)


def print_summary(events: List[Event]) -> None:
    if not events:
        print("No valid events found.")
        return

    counter = Counter(e.event for e in events)
    cwnds = [e.cwnd for e in events]
    ssthreshes = [e.ssthresh for e in events]

    print("========== TCP Log Summary ==========")
    print(f"Total events: {len(events)}")
    print(f"cwnd: min={min(cwnds)} max={max(cwnds)} final={cwnds[-1]}")
    print(f"ssthresh: min={min(ssthreshes)} max={max(ssthreshes)} final={ssthreshes[-1]}")
    print()

    print("---------- Event Counts ----------")
    for name, count in counter.most_common():
        print(f"{name:20s} {count}")
    print()

    print("---------- Key Recovery Events ----------")
    key_events = {
        "TIMEOUT",
        "FAST_RETRANS",
        "NEWRENO_RETRANS",
        "PARTIAL_ACK",
        "FULL_ACK",
        "ENTER_FR",
        "EXIT_FR",
        "RETRANSMIT",
    }

    for e in events:
        if e.event in key_events:
            print(f"#{e.idx:04d} {e.time} {e.event:18s} base={e.base:<6d} "
                  f"next={e.next_seq:<6d} cwnd={e.cwnd:<5d} ssthresh={e.ssthresh:<5d} {e.info}")
    print()

    print("---------- cwnd Drop Points ----------")
    last: Optional[Event] = None
    for e in events:
        if last and e.cwnd < last.cwnd:
            print(f"#{e.idx:04d} {e.time} {last.cwnd} -> {e.cwnd} by {e.event}, info={e.info}")
        last = e

    print()
    print("---------- NewReno Effect Check ----------")
    partial_count = counter.get("PARTIAL_ACK", 0)
    full_count = counter.get("FULL_ACK", 0)
    if partial_count:
        print(f"Detected {partial_count} PARTIAL_ACK event(s): NewReno multiple-loss recovery is active.")
    else:
        print("No PARTIAL_ACK detected. Try a scenario with multiple losses in one window.")
    if full_count:
        print(f"Detected {full_count} FULL_ACK event(s): fast recovery exits after recoverPoint is fully acknowledged.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("log", type=Path, help="tcp_reno.log or tcp_newreno.log")
    parser.add_argument("--csv", type=Path, help="optional path to export parsed events as CSV")
    args = parser.parse_args()

    events = parse_log(args.log)
    print_summary(events)

    if args.csv:
        write_csv(events, args.csv)
        print(f"\nCSV exported to: {args.csv}")


if __name__ == "__main__":
    main()
