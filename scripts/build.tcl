set root [file normalize [file join [file dirname [info script]] ..]]
cd $root
file mkdir build output reports
set_param general.maxThreads 4
read_verilog [glob rtl/*.v]
read_xdc constraints/ego1.xdc
synth_design -top puzzle_top -part xc7a35tcsg324-1
write_checkpoint -force build/synth.dcp
report_utilization -file reports/utilization_synth.txt
opt_design
place_design
phys_opt_design
route_design
report_timing_summary -report_unconstrained -file reports/timing.txt
report_utilization -file reports/utilization.txt
report_drc -file reports/drc.txt
report_cdc -file reports/cdc.txt
report_methodology -file reports/methodology.txt
check_timing -verbose -file reports/check_timing.txt
set setup [get_timing_paths -delay_type max -max_paths 1]
set hold [get_timing_paths -delay_type min -max_paths 1]
if {[llength $setup]==0 || [get_property SLACK $setup]<0} {error "Setup timing failed"}
if {[llength $hold]==0 || [get_property SLACK $hold]<0} {error "Hold timing failed"}
set violations [get_drc_violations -quiet -filter {SEVERITY == Error}]
if {[llength $violations]>0} {error "DRC errors: $violations"}
write_checkpoint -force output/puzzle_routed.dcp
write_bitstream -force output/puzzle.bit
puts "BUILD_PASS WNS=[get_property SLACK $setup] WHS=[get_property SLACK $hold]"
