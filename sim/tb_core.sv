`timescale 1ns/1ps
module tb_core;
    reg clk=0; always #5 clk=~clk;
    reg rst_n=0;
    reg [10:0] cmd=0;
    wire [35:0] board; wire [1:0] state; wire [3:0] entered;
    wire [7:0] good,bad,hints;
    wire error,stats,view,demo,undo_available; wire [2:0] phase;
    puzzle_core dut(clk,rst_n,cmd[10],cmd[9],cmd[8:5],cmd[4],cmd[3],cmd[2],cmd[1],
        board,state,entered,good,bad,error,stats,view,demo,phase,undo_available,hints);
    wire [73:0] actual={hints,undo_available,phase,demo,view,stats,error,bad,good,entered,state,board};
    integer fd,rc,n=0; reg [10:0] stimulus; reg [73:0] expected;
    initial begin
        repeat(3) @(negedge clk); rst_n=1;
        fd=$fopen("build/core_vectors.txt","r");
        if(!fd) $fatal(1,"Missing generated vectors");
        while(!$feof(fd)) begin
            rc=$fscanf(fd,"%h %h\n",stimulus,expected);
            if(rc==2) begin
                @(negedge clk); cmd=stimulus;
                @(posedge clk); #1; n=n+1;
                if(actual !== expected) $fatal(1,"vector=%0d cmd=%h expected=%h actual=%h",n,cmd,expected,actual);
            end else $fatal(1,"Malformed vector %0d",n);
        end
        $fclose(fd); $display("CORE_PASS vectors=%0d",n); $finish;
    end
    initial begin #10000000; $fatal(1,"core watchdog"); end
endmodule
