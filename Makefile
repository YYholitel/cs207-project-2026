VIVADO_ROOT ?= /home/tfx/opt/amd/2025.2/Vivado
VIVADO := $(VIVADO_ROOT)/bin/vivado
export PATH := $(VIVADO_ROOT)/bin:$(PATH)
export VIVADO_ROOT
.PHONY: sim build program status verify project
sim:
	mkdir -p build
	bash scripts/sim.sh
build:
	mkdir -p build
	$(VIVADO) -mode batch -source scripts/build.tcl -log build/build.log -journal build/build.jou
program:
	$(VIVADO) -mode batch -source scripts/program.tcl -log build/program.log -journal build/program.jou
status:
	python tools/board_cli.py
verify:
	python tools/verify_board.py
project:
	mkdir -p build
	$(VIVADO) -mode batch -source scripts/create_project.tcl -log build/project.log -journal build/project.jou
