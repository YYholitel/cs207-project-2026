set root [file normalize [file join [file dirname [info script]] ..]]
set bitfile [file join $root output puzzle.bit]
if {![file isfile $bitfile]} {error "Build first: $bitfile missing"}
open_hw_manager
connect_hw_server -url localhost:3121
set targets [get_hw_targets]
if {[llength $targets]!=1} {error "Expected exactly one cable, got $targets"}
open_hw_target [lindex $targets 0]
set devices [get_hw_devices]
if {[llength $devices]!=1} {error "Expected exactly one FPGA, got $devices"}
set device [lindex $devices 0]
if {[get_property PART $device] ne "xc7a35t"} {error "Wrong FPGA part"}
set_property PROGRAM.FILE $bitfile $device
program_hw_devices $device
refresh_hw_device $device
set done [get_property REGISTER.CONFIG_STATUS.BIT14_DONE_PIN $device]
set crc [get_property REGISTER.CONFIG_STATUS.BIT00_CRC_ERROR $device]
puts "PROGRAM_STATUS PART=[get_property PART $device] DONE=$done CRC_ERROR=$crc"
if {$done ne "1" || $crc ne "0"} {error "Configuration verification failed"}
close_hw_target
disconnect_hw_server
close_hw_manager
