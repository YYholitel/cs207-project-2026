`timescale 1ns/1ps
// Pure synchronous game rules. Cell 0 is board[3:0]; F means not entered.
module puzzle_core (
    input wire clk, input wire rst_n, input wire clear,
    input wire confirm, input wire [3:0] value,
    input wire undo, input wire toggle_view,
    input wire toggle_demo, input wire demo_next,
    output reg [35:0] board,
    output reg [1:0] state,
    output reg [3:0] entered,
    output reg [7:0] valid_steps, invalid_steps,
    output reg error, stats, hint_view, demo,
    output reg [2:0] demo_phase,
    output reg undo_available,
    output reg [7:0] hints
);
    localparam INIT=2'd0, PLAY=2'd1, WON=2'd2;
    localparam [35:0] GOAL=36'h087654321, DEMO_BOARD=36'h807654321;
    reg [8:0] used;
    reg [35:0] previous_board;
    reg [7:0] previous_valid, previous_invalid;
    reg previous_error;
    reg [1:0] previous_state;
    integer i;
    reg [3:0] blank_pos, selected_pos;
    reg [35:0] moved_board;
    reg legal;

    function adjacent;
        input [3:0] a,b;
        begin
            // Explicit grid neighbors avoid division hardware and row wrap.
            case(a)
            0: adjacent=(b==1 || b==3);
            1: adjacent=(b==0 || b==2 || b==4);
            2: adjacent=(b==1 || b==5);
            3: adjacent=(b==0 || b==4 || b==6);
            4: adjacent=(b==1 || b==3 || b==5 || b==7);
            5: adjacent=(b==2 || b==4 || b==8);
            6: adjacent=(b==3 || b==7);
            7: adjacent=(b==4 || b==6 || b==8);
            8: adjacent=(b==5 || b==7);
            default: adjacent=0;
            endcase
        end
    endfunction
    always @* begin
        blank_pos=15; selected_pos=15;
        for (i=0;i<9;i=i+1) begin
            if (board[i*4 +: 4]==0) blank_pos=i;
            if (board[i*4 +: 4]==value) selected_pos=i;
        end
        legal=state==PLAY && value>=1 && value<=8 && blank_pos!=15 &&
              selected_pos!=15 && adjacent(blank_pos,selected_pos);
        moved_board=board;
        if (legal) begin
            moved_board[blank_pos*4 +: 4]=value;
            moved_board[selected_pos*4 +: 4]=0;
        end
        hints=0;
        if (state!=INIT && blank_pos!=15)
            for (i=0;i<9;i=i+1)
                if (board[i*4 +: 4]>=1 && board[i*4 +: 4]<=8 && adjacent(blank_pos,i))
                    hints[8-board[i*4 +: 4]]=1'b1;
    end

    task reset_game;
        begin
            board<={9{4'hf}}; state<=INIT; entered<=0; used<=0;
            valid_steps<=0; invalid_steps<=0; error<=0; stats<=0;
            hint_view<=0; undo_available<=0;
            previous_board<={9{4'hf}}; previous_valid<=0; previous_invalid<=0;
            previous_error<=0; previous_state<=INIT;
        end
    endtask
    // Counters saturate at 99. A selectable upper-nibble LED page preserves
    // the complete binary count above 15, while decimal readout is always 00..99.
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            reset_game(); demo<=0; demo_phase<=0;
        end else if (clear) begin
            reset_game(); demo<=0; demo_phase<=0;
        end else if (toggle_demo) begin
            reset_game(); demo<=!demo; demo_phase<=0;
        end else begin
            if (toggle_view) hint_view<=!hint_view;
            if (demo) begin
                if (demo_next) begin
                    case (demo_phase)
                    0,5: begin
                        board<=DEMO_BOARD; entered<=9; used<=9'h1ff;
                        state<=PLAY; valid_steps<=0; invalid_steps<=0;
                        error<=0; stats<=0; undo_available<=0; demo_phase<=1;
                    end
                    1: begin invalid_steps<=1; error<=1; demo_phase<=2; end
                    2: begin
                        board<=GOAL; state<=WON; valid_steps<=1;
                        error<=0; demo_phase<=3;
                    end
                    3: begin stats<=1; demo_phase<=4; end
                    4: begin reset_game(); demo_phase<=5; end
                    default: demo_phase<=0;
                    endcase
                end
            end else if (undo && undo_available) begin
                board<=previous_board; valid_steps<=previous_valid;
                invalid_steps<=previous_invalid; error<=previous_error;
                state<=previous_state; stats<=0; undo_available<=0;
            end else if (confirm) begin
                case (state)
                INIT: begin
                    if (value>8 || used[value]) error<=1;
                    else begin
                        board[entered*4 +: 4]<=value;
                        used[value]<=1; entered<=entered+1'b1; error<=0;
                        if (entered==8) begin
                            if ({value,board[31:0]}==GOAL) state<=WON;
                            else state<=PLAY;
                        end
                    end
                end
                PLAY: begin
                    previous_board<=board; previous_valid<=valid_steps;
                    previous_invalid<=invalid_steps; previous_error<=error;
                    previous_state<=state; undo_available<=1;
                    if (legal) begin
                        board<=moved_board; error<=0;
                        if (valid_steps<99) valid_steps<=valid_steps+1'b1;
                        if (moved_board==GOAL) state<=WON;
                    end else begin
                        error<=1;
                        if (invalid_steps<99) invalid_steps<=invalid_steps+1'b1;
                    end
                end
                WON: stats<=!stats;
                default: reset_game();
                endcase
            end
        end
    end
endmodule
