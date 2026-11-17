`timescale 1ns/1ps
module tb_top;
    reg clk=0; always #5 clk=~clk;
    localparam DIV=16, BIT_NS=DIV*10;
    reg reset_n=1, rx=1; reg [4:0] buttons=0, switches=0;
    wire tx; wire [15:0] led; wire [7:0] en,sl,sr;
    puzzle_top #(.BUTTON_CYCLES(5),.SWITCH_CYCLES(3),.SCAN_CYCLES(8),
        .BLINK_CYCLES(30),.UART_DIV(DIV)) dut(clk,reset_n,buttons,switches,rx,tx,led,en,sl,sr);
    reg [7:0] packet[0:27];
    reg [7:0] last_en=0,last_sl=0,last_sr=0;
    integer n;
    // Actual driven segment/select signals must blank before every data change.
    always @(posedge clk) begin
        #2;
        if(dut.rst_n) begin
            if(en!=0 && !(en==8'h11 || en==8'h22 || en==8'h44 || en==8'h88))
                $fatal(1,"invalid digit enables %h",en);
            if(en!=0 && last_en!=0 && (sl!==last_sl || sr!==last_sr))
                $fatal(1,"segments changed without blank interval");
        end
        last_en=en; last_sl=sl; last_sr=sr;
    end
    task send;
        input [7:0] b;
        integer i;
        begin
            @(negedge clk); rx=0; #(BIT_NS);
            for(i=0;i<8;i=i+1) begin rx=b[i]; #(BIT_NS); end
            rx=1; #(BIT_NS*2);
        end
    endtask
    task receive;
        output [7:0] b;
        integer i;
        begin
            @(negedge tx); #(BIT_NS+BIT_NS/2);
            for(i=0;i<8;i=i+1) begin b[i]=tx; #(BIT_NS); end
            if(tx!==1) $fatal(1,"bad UART stop bit");
        end
    endtask
    task query;
        integer i; reg [7:0] sum;
        begin
            fork
                send("?");
                begin for(i=0;i<28;i=i+1) receive(packet[i]); end
            join
            if(packet[0]!==8'ha5 || packet[1]!==8'h5a || packet[2]!==1)
                $fatal(1,"bad telemetry header %h %h",packet[0],packet[1]);
            sum=0; for(i=2;i<27;i=i+1) sum=sum^packet[i];
            if(sum!==packet[27]) $fatal(1,"bad packet checksum");
            #(BIT_NS*2);
        end
    endtask
    task press_button;
        input integer idx;
        begin
            @(negedge clk); buttons[idx]=1;
            repeat(40) @(negedge clk); buttons[idx]=0;
            repeat(12) @(negedge clk);
        end
    endtask
    task check;
        input condition;
        input [511:0] message;
        begin if(condition!==1) $fatal(1,"%0s",message); end
    endtask
    initial begin
        repeat(20) @(negedge clk);
        query(); check(packet[4]==0 && packet[5]==0 && led==16'h0400,"powerup/reset outputs");
        send("1"); query(); check(packet[5]==0,"disabled UART changed game");
        send("a");
        send("1"); send("2"); send("3"); send("4"); send("5");
        send("6"); send("7"); send("0"); send("8");
        query(); check(packet[4]==1 && packet[5]==9 && packet[8]==8'h0b,"initial board/hints");
        check(led==16'h8c00,"cell 9 binary/mode pin ordering");
        send("v"); query(); check(packet[23]==8'h0b,"hint LED mapping");
        send("1"); query(); check(packet[7]==1 && packet[3][1],"invalid move");
        send("u"); query(); check(packet[7]==0 && !packet[3][1],"invalid undo");
        send("8"); query(); check(packet[4]==2 && packet[6]==1 && packet[8]==5,"win move");
        check(led[15:12]==0 && led[8],"win LEDs");
        send("c"); query();
        check({packet[22],packet[21],packet[20],packet[19]}==32'h00ffff10,"decimal stats positions");
        send("u"); query(); check(packet[4]==1 && packet[6]==0 && !packet[3][2],"winning move undo");
        send("r"); query(); check(packet[5]==0,"remote reset");
        switches=9; repeat(12) @(negedge clk); press_button(0);
        query(); check(packet[5]==0 && packet[19][3:0]==14,"physical invalid initialization E");
        switches=1; repeat(12) @(negedge clk);
        buttons[0]=1; repeat(2) @(negedge clk); buttons[0]=0;
        repeat(12) @(negedge clk); check(dut.entered==0,"button bounce accepted");
        press_button(0); query(); check(packet[5]==1 && packet[10]==1,"held button repeated");
        reset_n=0; repeat(4) @(negedge clk); reset_n=1;
        repeat(20) @(negedge clk); query(); check(packet[5]==0 && !packet[3][5],"physical global reset");
        press_button(4); press_button(3); query();
        check(packet[9]==1 && packet[4]==1 && led[9] && !led[10],"physical demo init");
        press_button(3); query(); check(packet[7]==1 && packet[3][1],"demo illegal");
        press_button(3); query(); check(packet[6]==1 && packet[7]==1 && packet[4]==2,"demo solved");
        press_button(3); query(); check(packet[3][2],"demo stats");
        press_button(3); query(); check(packet[9]==5 && led==0,"demo reset not dark");
        press_button(3); query(); check(packet[9]==1,"demo replay");
        // Test both binary pages and two-digit decimal count (>15).
        send("a"); send("r");
        send("0"); send("1"); send("2"); send("3"); send("4");
        send("5"); send("6"); send("7"); send("8");
        for(n=0;n<16;n=n+1) send("1");
        for(n=0;n<17;n=n+1) send("0");
        query(); check(packet[6]==16 && packet[7]==17 && packet[23]==1,"low binary page");
        send("h"); query(); check(packet[23]==8'h11 && packet[3][6],"high binary page");
        send("h"); switches[4]=1; repeat(15) @(negedge clk);
        query(); check(packet[23]==8'h11,"physical high binary page");
        switches=0; send("r");
        send("1"); send("2"); send("3"); send("4"); send("5");
        send("6"); send("7"); send("0"); send("8");
        for(n=0;n<16;n=n+1) send("7");
        for(n=0;n<18;n=n+1) send("0");
        send("8"); send("c"); query();
        check(packet[6]==17 && packet[7]==18 && packet[23]==8'h12,"multi-digit completed count");
        check({packet[22],packet[21],packet[20],packet[19]}==32'h81ffff71,"decimal 17 / 18");
        switches=0; send("z"); query(); check(!packet[3][5] && !packet[3][6],"UART disable");
        // False start and bad stop must be rejected even with UART writes enabled.
        send("a");
        rx=0; #(BIT_NS/4); rx=1; #(BIT_NS*2);
        query(); check(packet[6]==17 && packet[7]==18,"false start corruption");
        @(negedge clk); rx=0; #(BIT_NS);
        for(n=0;n<8;n=n+1) begin rx=(8'h72 >> n)&1; #(BIT_NS); end // r
        rx=0; #(BIT_NS); rx=1; #(BIT_NS*3);
        query(); check(packet[6]==17 && packet[7]==18,"bad stop frame reset game");
        send("z");
        $display("TOP_PASS UART packets, buttons, reset, display, LEDs, demo, pages"); $finish;
    end
    initial begin #10000000; $fatal(1,"top watchdog"); end
endmodule
