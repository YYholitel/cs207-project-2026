open_hw_manager
connect_hw_server -url localhost:3121
puts "TARGETS=[get_hw_targets]"
open_hw_target
foreach device [get_hw_devices] {puts "DEVICE=$device PART=[get_property PART $device]"}
close_hw_target
disconnect_hw_server
close_hw_manager
