`timescale 1ns/1ps
// Two independent 4-digit groups; all selects and segments are ACTIVE HIGH.
// digits[3:0] is physical BIT1 (leftmost); segments[7:0]={a,b,c,d,e,f,g,dp}.
// Each step first blanks, then changes data, then enables: no ghost digits.
module seven_segment #(parameter integer SCAN_CYCLES=25000)(
    input wire clk, rst_n, input wire [31:0] digits,
    output reg [7:0] an, seg_left, seg_right
);
    localparam CW=(SCAN_CYCLES<=3)?2:$clog2(SCAN_CYCLES);
    reg [CW-1:0] count;
    reg [1:0] index;
    function [7:0] encode;
        input [3:0] x;
        begin
            case(x)
            0:encode=8'hfc; 1:encode=8'h60; 2:encode=8'hda;
            3:encode=8'hf2; 4:encode=8'h66; 5:encode=8'hb6;
            6:encode=8'hbe; 7:encode=8'he0; 8:encode=8'hfe;
            9:encode=8'hf6; 14:encode=8'h9e;
            default:encode=0;
            endcase
        end
    endfunction
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin count<=0; index<=0; an<=0; seg_left<=0; seg_right<=0; end
        else begin
            if(count==SCAN_CYCLES-1) begin
                count<=0; index<=index+1'b1; an<=0;
            end else begin
                count<=count+1'b1;
                if(count==0) begin
                    seg_left<=encode(digits[index*4 +: 4]);
                    seg_right<=encode(digits[(index+4)*4 +: 4]);
                end
                if(count==1) an<=(8'h11 << index);
            end
        end
    end
endmodule
