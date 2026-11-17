#!/usr/bin/env bash
set -eo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
VIVADO_ROOT="${VIVADO_ROOT:-/home/tfx/opt/amd/2025.2/Vivado}"
source "$VIVADO_ROOT/settings64.sh"
mkdir -p build reports
python tools/generate_vectors.py
# XSim's incremental database can spin after interrupted or overlapping compiles.
# Use an isolated directory on every run; testbench vector path stays stable.
sim_dir="$(mktemp -d "$root/build/sim.XXXXXX")"
ln -s "$root/build" "$sim_dir/build"
cd "$sim_dir"
xvlog "$root"/rtl/*.v
for tb in tb_core tb_top; do
    xvlog -sv "$root/sim/$tb.sv"
    xelab "$tb" -s "$tb" --debug typical
    xsim "$tb" -runall | tee "$root/reports/$tb.txt"
    python - "$tb" "$root" <<'PY'
import pathlib, sys
name=sys.argv[1]
text=(pathlib.Path(sys.argv[2])/'reports'/f'{name}.txt').read_text()
expected={'tb_core':'CORE_PASS vectors=20106','tb_top':'TOP_PASS'}[name]
if expected not in text or 'Fatal:' in text or 'ERROR:' in text:
    raise SystemExit(f'{name} failed; inspect reports/{name}.txt')
PY
done
cd "$root"
python -m unittest discover -s sim -p 'test_*.py' -v 2>&1 | tee reports/host_protocol.txt
