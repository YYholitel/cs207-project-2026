`timescale 1ns/1ps
module input_filter #(parameter integer WIDTH=1, STABLE_CYCLES=1000000)(
    input wire clk, rst_n, input wire [WIDTH-1:0] async_in,
    output reg [WIDTH-1:0] level,
    output reg [WIDTH-1:0] rise
);
    localparam CW=(STABLE_CYCLES<=1)?1:$clog2(STABLE_CYCLES);
    (* ASYNC_REG="TRUE" *) reg [WIDTH-1:0] meta, synced;
    reg [CW-1:0] count[0:WIDTH-1];
    integer i;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin meta<=0; synced<=0; end
        else begin meta<=async_in; synced<=meta; end
    end
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            level<=0; rise<=0;
            for(i=0;i<WIDTH;i=i+1) count[i]<=0;
        end else begin
            rise<=0;
            for(i=0;i<WIDTH;i=i+1) begin
                if (synced[i]==level[i]) count[i]<=0;
                else if (count[i]==STABLE_CYCLES-1) begin
                    level[i]<=synced[i]; rise[i]<=synced[i]; count[i]<=0;
                end else count[i]<=count[i]+1'b1;
            end
        end
    end
endmodule
