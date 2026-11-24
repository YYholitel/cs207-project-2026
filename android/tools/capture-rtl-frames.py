"""Capture UART frames from the existing FPGA top-level test using Icarus Verilog."""
from pathlib import Path
import subprocess
import tempfile

android = Path(__file__).resolve().parents[1]
repo = android.parent
source = (repo / "sim/tb_top.sv").read_text(encoding="utf-8")
marker = 'if(sum!==packet[27]) $fatal(1,"bad packet checksum");'
assert source.count(marker) == 1
source = source.replace(marker, marker + '\n$write("ANDROID_FRAME "); for(i=0;i<28;i=i+1) $write("%02x",packet[i]); $display("");')
with tempfile.TemporaryDirectory(prefix="cs207-rtl-") as temporary:
    work = Path(temporary)
    test = work / "tb_top.sv"
    test.write_text(source, encoding="utf-8")
    executable = work / "top.vvp"
    subprocess.run(["iverilog", "-g2012", "-s", "tb_top", "-o", str(executable),
                    *map(str, sorted((repo / "rtl").glob("*.v"))), str(test)], check=True)
    output = subprocess.check_output(["vvp", str(executable)], text=True)
    assert "TOP_PASS" in output, output
    frames = [line for line in output.splitlines() if line.startswith("ANDROID_FRAME ")]
    assert len(frames) >= 20
(android / "tools/rtl-frames.txt").write_text("\n".join(frames) + "\n", encoding="ascii")
print(f"PASS: top-level RTL, captured {len(frames)} UART frames")
