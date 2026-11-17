set root [file normalize [file join [file dirname [info script]] ..]]
create_project puzzle [file join $root build gui] -part xc7a35tcsg324-1 -force
add_files [glob [file join $root rtl *.v]]
add_files -fileset constrs_1 [file join $root constraints ego1.xdc]
add_files -fileset sim_1 [glob [file join $root sim *.sv]]
set_property top puzzle_top [current_fileset]
set_property top tb_top [get_filesets sim_1]
update_compile_order -fileset sources_1
puts "PROJECT_READY: [file join $root build gui puzzle.xpr]"
close_project
