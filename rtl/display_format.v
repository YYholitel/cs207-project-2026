`timescale 1ns/1ps
module display_format(
    input wire [35:0] board, input wire [1:0] state,
    input wire [3:0] entered, input wire [7:0] valid_steps, invalid_steps,
    input wire error, stats, hint_view, demo, advanced, high_page, blink,
    input wire [2:0] demo_phase, input wire [7:0] hints,
    output reg [31:0] digits, output reg [15:0] leds
);
    integer i;
    always @* begin
        digits={8{4'hf}};
        for(i=0;i<8;i=i+1)
            if (board[i*4 +: 4]>=1 && board[i*4 +: 4]<=8)
                digits[i*4 +: 4]=board[i*4 +: 4];
        if (state==0 && error && entered<8) digits[entered*4 +: 4]=4'he;
        if (stats && state==2) begin
            digits={8{4'hf}};
            digits[3:0]=valid_steps/10;
            digits[7:4]=valid_steps%10;
            digits[27:24]=invalid_steps/10;
            digits[31:28]=invalid_steps%10;
        end
        leds=0;
        leds[15:12]=(board[35:32]<=8)?board[35:32]:4'b0;
        if (state==0 && error && entered==8) leds[15:12]=4'hf;
        if (hint_view) leds[7:0]=hints;
        else if (high_page) leds[7:0]={valid_steps[7:4],invalid_steps[7:4]};
        else leds[7:0]={valid_steps[3:0],invalid_steps[3:0]};
        leds[11]=advanced;  // K6
        leds[10]=!demo;     // L1
        leds[9]=demo;       // M1
        leds[8]=(state==2) || (error && blink); // K3
        // Demo's final reset scene is explicitly all dark (project section 3.6).
        if (demo && demo_phase==5) begin digits={8{4'hf}}; leds=0; end
    end
endmodule
