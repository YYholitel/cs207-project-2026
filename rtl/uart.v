`timescale 1ns/1ps
// 8N1 UART, rounded integer divider (100 MHz / 115200 -> 868).
module uart_rx #(parameter integer CLKS_PER_BIT=868)(
    input wire clk, rst_n, rx,
    output reg [7:0] data, output reg valid
);
    (* ASYNC_REG="TRUE" *) reg rx_meta, rx_sync;
    localparam CW=$clog2(CLKS_PER_BIT+1);
    reg [CW-1:0] count;
    reg [1:0] state;
    reg [2:0] bit_index;
    reg [7:0] shift;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin rx_meta<=1; rx_sync<=1; end
        else begin rx_meta<=rx; rx_sync<=rx_meta; end
    end
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            state<=0; count<=0; bit_index<=0; shift<=0; data<=0; valid<=0;
        end else begin
            valid<=0;
            case(state)
            0: if(!rx_sync) begin state<=1; count<=0; end
            1: if(count==(CLKS_PER_BIT/2)-1) begin
                count<=0;
                if(!rx_sync) begin state<=2; bit_index<=0; end
                else state<=0;
            end else count<=count+1'b1;
            2: if(count==CLKS_PER_BIT-1) begin
                count<=0; shift[bit_index]<=rx_sync;
                if(bit_index==7) state<=3;
                else bit_index<=bit_index+1'b1;
            end else count<=count+1'b1;
            3: if(count==CLKS_PER_BIT-1) begin
                count<=0; state<=0;
                if(rx_sync) begin data<=shift; valid<=1; end
            end else count<=count+1'b1;
            endcase
        end
    end
endmodule

module uart_tx #(parameter integer CLKS_PER_BIT=868)(
    input wire clk, rst_n, start, input wire [7:0] data,
    output wire tx, output reg busy
);
    localparam CW=$clog2(CLKS_PER_BIT+1);
    reg [CW-1:0] count;
    reg [3:0] bit_index;
    reg [9:0] shift;
    assign tx=busy?shift[0]:1'b1;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin count<=0; bit_index<=0; shift<=10'h3ff; busy<=0; end
        else if(!busy) begin
            if(start) begin
                shift<={1'b1,data,1'b0}; busy<=1; count<=0; bit_index<=0;
            end
        end else if(count==CLKS_PER_BIT-1) begin
            count<=0; shift<={1'b1,shift[9:1]};
            if(bit_index==9) busy<=0;
            else bit_index<=bit_index+1'b1;
        end else count<=count+1'b1;
    end
endmodule
