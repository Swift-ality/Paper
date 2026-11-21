#!/bin/sh
# Swift - squish (cramming) deaths per tick in perf-bench logs: for each run-bench/bench-<scenario>-<mode>.log,
# deaths within the first N ticks after the mobs were summoned, using the run's measured TPS.
# usage: sh deaths-per-tick.sh <scenario> <mode> <tps> [<mode> <tps> ...]
cd "$(dirname "$0")/../../run-bench" || exit 1
scenario=$1; shift
while [ $# -ge 2 ]; do
  mode=$1; tps=$2; shift 2
  f=bench-$scenario-$mode.log
  start=$(grep -m1 "Test passed. Count" "$f" | grep -o "^\[[0-9:]*" | tr -d '[')
  grep "squished" "$f" | grep -o "^\[[0-9:]*" | tr -d '[' | awk -v s="$start" -v tps="$tps" -v m="$mode" '
    function sec(t) { split(t, a, ":"); return a[1] * 3600 + a[2] * 60 + a[3] }
    { ticks = (sec($1) - sec(s)) * tps; if (ticks <= 1200) c1++; if (ticks <= 2400) c2++ }
    END { printf "%-9s TPS %-5s squish deaths: %3d within 1200 ticks, %3d within 2400 ticks\n", m, tps, c1, c2 }'
done
