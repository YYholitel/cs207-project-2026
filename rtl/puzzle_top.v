`timescale 1ns/1ps
module puzzle_top #(
    parameter integer BUTTON_CYCLES=1000000, SWITCH_CYCLES=100000,
    parameter integer SCAN_CYCLES=25000, BLINK_CYCLES=25000000,
    parameter integer UART_DIV=868
)(
    input wire clk, reset_n,
    input wire [4:0] buttons,
    input wire [4:0] switches,
    input wire uart_rxd,
    output wire uart_txd,
    output wire [15:0] led,
    output wire [7:0] digit_en, seg_left, seg_right
);
    // Asynchronous assertion and synchronous deassertion; defined power-up reset.
    (* ASYNC_REG="TRUE" *) reg [2:0] reset_pipe=0;
    always @(posedge clk or negedge reset_n)
        if(!reset_n) reset_pipe<=0;
        else reset_pipe<={reset_pipe[1:0],1'b1};
    wire rst_n=reset_pipe[2];
    wire [4:0] button_level, button_pulse, sw_level, sw_pulse;
    input_filter #(.WIDTH(5),.STABLE_CYCLES(BUTTON_CYCLES)) u_buttons
        (clk,rst_n,buttons,button_level,button_pulse);
    input_filter #(.WIDTH(5),.STABLE_CYCLES(SWITCH_CYCLES)) u_switches
        (clk,rst_n,switches,sw_level,sw_pulse);
    wire [7:0] rx_data;
    wire rx_valid;
    uart_rx #(.CLKS_PER_BIT(UART_DIV)) u_rx(clk,rst_n,uart_rxd,rx_data,rx_valid);
    reg advanced, remote_high;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin advanced<=0; remote_high<=0; end
        else if(rx_valid) begin
            if(rx_data=="a") advanced<=1;
            if(rx_data=="z") begin advanced<=0; remote_high<=0; end
            if(advanced && rx_data=="h") remote_high<=!remote_high;
        end
    end
    wire remote=rx_valid && advanced;
    wire numeric=(rx_data>="0" && rx_data<="9") || (rx_data>="A" && rx_data<="F");
    wire [3:0] remote_value=(rx_data<="9")?(rx_data-8'h30):(rx_data-8'h37);
    wire confirm=button_pulse[0] || (remote && (numeric || rx_data=="c"));
    wire [3:0] value=button_pulse[0]?sw_level[3:0]:
                     (numeric?remote_value:sw_level[3:0]);
    wire undo=button_pulse[2] || (remote && rx_data=="u");
    wire toggle_view=button_pulse[1] || (remote && rx_data=="v");
    wire toggle_demo=button_pulse[4] || (remote && rx_data=="d");
    wire demo_next=button_pulse[3] || (remote && rx_data=="n");
    wire clear=remote && rx_data=="r";
    // Register the complete command atomically. This separates UART ASCII /
    // physical-input arbitration from the board's move/goal logic at 100 MHz.
    reg [3:0] command_value;
    reg command_confirm, command_undo, command_view, command_demo, command_next, command_clear;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            command_value<=0; command_confirm<=0; command_undo<=0;
            command_view<=0; command_demo<=0; command_next<=0; command_clear<=0;
        end else begin
            command_value<=value; command_confirm<=confirm; command_undo<=undo;
            command_view<=toggle_view; command_demo<=toggle_demo;
            command_next<=demo_next; command_clear<=clear;
        end
    end
    wire [35:0] board;
    wire [1:0] state;
    wire [3:0] entered;
    wire [7:0] valid_steps, invalid_steps, hints;
    wire error, stats, hint_view, demo, undo_available;
    wire [2:0] demo_phase;
    puzzle_core u_core(clk,rst_n,command_clear,command_confirm,command_value,command_undo,command_view,
        command_demo,command_next,board,state,entered,valid_steps,invalid_steps,
        error,stats,hint_view,demo,demo_phase,undo_available,hints);
    localparam BW=(BLINK_CYCLES<=1)?1:$clog2(BLINK_CYCLES);
    reg [BW-1:0] blink_count;
    reg blink;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin blink_count<=0; blink<=1; end
        else if(!error) begin blink_count<=0; blink<=1; end
        else if(blink_count==BLINK_CYCLES-1) begin blink_count<=0; blink<=!blink; end
        else blink_count<=blink_count+1'b1;
    end
    wire high_page=sw_level[4] || (advanced && remote_high);
    wire [31:0] digits;
    display_format u_format(board,state,entered,valid_steps,invalid_steps,
        error,stats,hint_view,demo,advanced,high_page,blink,demo_phase,hints,digits,led);
    seven_segment #(.SCAN_CYCLES(SCAN_CYCLES)) u_display
        (clk,rst_n,digits,digit_en,seg_left,seg_right);

    // Atomic read-only telemetry. '?' snapshots exactly 28 bytes; XOR checksum.
    // No asynchronous status stream: hosts can match each request unambiguously.
    reg [223:0] packet;
    reg [4:0] packet_index;
    reg sending, tx_start, tx_wait;
    reg [7:0] tx_data;
    wire tx_busy;
    integer j;
    reg [7:0] checksum;
    uart_tx #(.CLKS_PER_BIT(UART_DIV)) u_tx(clk,rst_n,tx_start,tx_data,uart_txd,tx_busy);
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            packet<=0; packet_index<=0; sending<=0; tx_start<=0;
            tx_wait<=0; tx_data<=0;
        end else begin
            tx_start<=0;
            if(rx_valid && rx_data=="?" && !sending && !tx_busy) begin
                packet[7:0]<=8'ha5; packet[15:8]<=8'h5a; packet[23:16]<=1;
                packet[31:24]<={1'b0,high_page,advanced,undo_available,hint_view,stats,error,demo};
                packet[39:32]<={6'b0,state}; packet[47:40]<={4'b0,entered};
                packet[55:48]<=valid_steps; packet[63:56]<=invalid_steps;
                packet[71:64]<=hints; packet[79:72]<={5'b0,demo_phase};
                for(j=0;j<9;j=j+1) packet[(10+j)*8 +: 8]<={4'b0,board[j*4 +: 4]};
                packet[183:152]<=digits;
                packet[191:184]<=led[7:0]; packet[199:192]<=led[15:8];
                packet[215:200]<=0;
                // Compute on the following cycle, after all snapshot NBAs settle.
                packet_index<=0; sending<=1; tx_wait<=0;
            end else if(sending && !tx_busy && !tx_start && !tx_wait) begin
                if(packet_index==0) begin
                    checksum=0;
                    for(j=2;j<27;j=j+1) checksum=checksum ^ packet[j*8 +: 8];
                    packet[223:216]<=checksum;
                end
                tx_data<=packet[packet_index*8 +: 8]; tx_start<=1; tx_wait<=1;
            end else if(tx_wait && tx_busy) begin
                tx_wait<=0;
                if(packet_index==27) sending<=0;
                else packet_index<=packet_index+1'b1;
            end
        end
    end
endmodule
