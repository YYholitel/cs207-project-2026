package edu.sustech.cs207.egopuzzle;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static edu.sustech.cs207.egopuzzle.BoardLink.Action;
import static edu.sustech.cs207.egopuzzle.BoardLink.Phase;

public final class MainActivity extends Activity {
    private static final int PAPER = 0xFFF7F4EB;
    private static final int INK = 0xFF182B26;
    private static final int MUTED = 0xFF4C655D;
    private static final int PANEL = 0xFFE6EDE6;
    private static final int TILE = 0xFFFFFBF0;
    private static final int MINT = 0xFFB7E9CC;
    private static final int ACCENT = 0xFF175A43;
    private static final int ERROR = 0xFF992D1C;

    private final Button[] cells = new Button[9];
    private BoardLink board;
    private TextView validValue;
    private TextView invalidValue;
    private TextView status;
    private TextView tip;
    private LinearLayout keypad;
    private Button undo;
    private Button stats;
    private Button demo;
    private TextView source;
    private TextView connectionState;
    private Button view;
    private Button page;
    private LinearLayout gameContent;
    private BoardConnections connections;
    private boolean remoteSession;
    private boolean sessionReady = true;
    private int sessionGeneration;
    private BoardLink.Snapshot confirmedState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        board = restoreBoard(savedInstanceState);
        createScreen();
        connections = new BoardConnections(this, this::useRemote, this::showConnectionMessage);
        if (savedInstanceState != null && savedInstanceState.getBoolean("remote_session")) {
            remoteSession = true;
            board = null;
            sessionReady = false;
            restoreRemoteDisplay(savedInstanceState);
            source.setText("EGO1 · 已断开");
            showConnectionMessage("请重新连接开发板，同步当前棋局");
            setGameEnabled(false);
        } else send(Action.SYNC, 0);
    }

    @SuppressWarnings("deprecation")
    private BoardLink restoreBoard(Bundle state) {
        if (state != null && state.getSerializable("simulated_board") instanceof SimulatedBoard saved) {
            return saved;
        }
        return new SimulatedBoard();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (board instanceof SimulatedBoard simulated) outState.putSerializable("simulated_board", simulated);
        outState.putBoolean("remote_session", remoteSession);
        if (remoteSession && confirmedState != null) outState.putSerializable("remote_state", confirmedState);
    }

    @SuppressWarnings("deprecation")
    private void restoreRemoteDisplay(Bundle state) {
        if (state.getSerializable("remote_state") instanceof BoardLink.Snapshot snapshot) render(snapshot);
    }

    private void createScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            applyInsets(view, insets);
            return insets;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        source = label("EGO1  ·  模拟板", 13, ACCENT, true);
        content.addView(source);
        LinearLayout links = row();
        addWeighted(links, button("USB 连接", 14, PANEL, INK, v -> {
            disconnectRemote();
            connections.usb();
        }), 48, 0);
        addWeighted(links, button("蓝牙连接", 14, PANEL, INK, v -> {
            disconnectRemote();
            connections.bluetooth();
        }), 48, 5);
        addWeighted(links, button("模拟板", 14, PANEL, INK, v -> useSimulator()), 48, 5);
        add(content, links, 6);
        connectionState = label("可直接练习，也可以连接开发板", 13, MUTED, false);
        add(content, connectionState, 5);
        gameContent = new LinearLayout(this);
        gameContent.setOrientation(LinearLayout.VERTICAL);
        add(content, gameContent, 0);
        content = gameContent;
        TextView title = label("数字华容道", 33, INK, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        add(content, title, 2);

        LinearLayout counters = row();
        validValue = addCounter(counters, "有效步数", 0);
        invalidValue = addCounter(counters, "无效步数", 8);
        add(content, counters, 12);
        status = label("", 15, MUTED, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, status, 12);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        grid.setBackground(shape(INK, 20));
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        add(content, grid, 8);
        int cellHeight = Math.max(62, Math.min(102, (getResources().getConfiguration().screenWidthDp - 74) / 3));
        for (int r = 0; r < 3; r++) {
            LinearLayout line = row();
            add(grid, line, r == 0 ? 0 : 6);
            for (int c = 0; c < 3; c++) {
                int index = r * 3 + c;
                Button cell = button("", 31, TILE, INK, null);
                cells[index] = cell;
                addWeighted(line, cell, cellHeight, c == 0 ? 0 : 6);
            }
        }

        tip = label("浅绿色棋子可以移动", 13, MUTED, false);
        add(content, tip, 7);

        keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        add(content, keypad, 8);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = row();
            add(keypad, line, r == 0 ? 0 : 5);
            for (int i = r * 5; i < Math.min(r * 5 + 5, 9); i++) {
                int tile = i;
                Button key = button(String.valueOf(tile), 18, PANEL, INK, v -> send(Action.PLACE, tile));
                if (tile == 0) key.setContentDescription("0，空格");
                addWeighted(line, key, 48, tile % 5 == 0 ? 0 : 5);
            }
            if (r == 1) addWeighted(line, new View(this), 48, 5);
        }

        LinearLayout controls = row();
        addWeighted(controls, button("练习关", 15, ACCENT, 0xFFFFFFFF, v -> send(Action.PRACTICE, 0)), 48, 0);
        undo = button("撤回", 15, PANEL, INK, v -> send(Action.UNDO, 0));
        addWeighted(controls, undo, 48, 6);
        addWeighted(controls, button("复位", 15, PANEL, INK, v -> confirmReset()), 48, 6);
        add(content, controls, 10);

        LinearLayout footer = row();
        demo = button("开始演示", 15, PANEL, ACCENT, v -> send(Action.DEMO_NEXT, 0));
        stats = button("查看统计", 15, ACCENT, 0xFFFFFFFF, v -> send(Action.SHOW_STATS, 0));
        addWeighted(footer, demo, 48, 0);
        addWeighted(footer, stats, 48, 6);
        add(content, footer, 6);
        LinearLayout boardControls = row();
        view = button("板上提示 / 统计", 14, PANEL, INK, v -> send(Action.VIEW, 0));
        page = button("计数高 / 低位", 14, PANEL, INK, v -> send(Action.HIGH_PAGE, 0));
        addWeighted(boardControls, view, 48, 0);
        addWeighted(boardControls, page, 48, 6);
        add(content, boardControls, 6);
        setContentView(scroll);
    }

    @SuppressWarnings("deprecation")
    private void applyInsets(View view, WindowInsets insets) {
        view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
    }

    private void send(Action action, int tile) {
        if (board == null || !sessionReady) return;
        int generation = sessionGeneration;
        if (board instanceof RemoteBoard) setGameEnabled(false);
        board.send(action, tile, confirmed -> runOnUiThread(() -> {
            if (generation != sessionGeneration) return;
            sessionReady = true;
            setGameEnabled(true);
            render(confirmed);
        }));
    }

    private void render(BoardLink.Snapshot state) {
        confirmedState = state;
        boolean setup = state.phase == Phase.SETUP;
        boolean interactive = state.phase == Phase.PLAYING && !state.demoMode;
        boolean badInput = state.feedback == BoardLink.Feedback.BAD_INPUT;
        boolean invalidMove = state.feedback == BoardLink.Feedback.INVALID_MOVE;
        for (int i = 0; i < cells.length; i++) {
            Button cell = cells[i];
            int tile = state.tileAt(i);
            boolean rejected = badInput && i == state.setupIndex;
            boolean movable = state.movable(tile);
            cell.setText(rejected ? "E" : tile > 0 ? String.valueOf(tile) : "");
            cell.setTextColor(rejected ? ERROR : INK);
            cell.setBackground(buttonBackground(rejected ? 0xFFFFDDD3 : movable ? MINT : tile > 0 ? TILE : 0xFF30443C));
            cell.setContentDescription(rejected ? "第 " + (i + 1) + " 格，输入错误"
                    : tile < 0 ? "第 " + (i + 1) + " 格，未设置"
                    : tile == 0 ? "空格" : "棋子 " + tile + (movable ? "，可移动" : ""));
            cell.setOnClickListener(interactive && tile > 0 ? view -> send(Action.MOVE, tile) : null);
            cell.setClickable(interactive && tile > 0);
        }
        validValue.setText(String.valueOf(state.validMoves));
        validValue.setContentDescription("有效步数 " + state.validMoves);
        invalidValue.setText(String.valueOf(state.invalidMoves));
        invalidValue.setContentDescription("无效步数 " + state.invalidMoves);
        keypad.setVisibility(setup && !state.demoMode ? View.VISIBLE : View.GONE);
        tip.setVisibility(setup ? View.GONE : View.VISIBLE);
        undo.setEnabled(state.canUndo && !state.demoMode);
        undo.setAlpha(undo.isEnabled() ? 1f : 0.55f);
        stats.setVisibility((state.phase == Phase.WON || state.phase == Phase.STATS) && !state.demoMode ? View.VISIBLE : View.GONE);
        stats.setText(state.phase == Phase.STATS ? "返回棋盘" : "查看统计");
        demo.setText(!state.demoMode ? "开始演示" : "演示下一步 · " + state.demoStep + "/5");
        view.setText(state.hintView ? "板上显示 · 提示" : "板上显示 · 统计");
        page.setText(state.highPage ? "计数页 · 高四位" : "计数页 · 低四位");
        status.setTextColor(badInput || invalidMove ? ERROR : ACCENT);
        status.setText(switch (state.phase) {
            case SETUP -> state.demoMode ? "演示已复位 · 点击下一步重播" : badInput ? "输入无效，请重新输入第 " + (state.setupIndex + 1) + " 格"
                    : "设置第 " + (state.setupIndex + 1) + " / 9 格 · 0 为空格";
            case PLAYING -> invalidMove ? "无法移动 · 棋子必须紧邻空格"
                    : state.feedback == BoardLink.Feedback.UNDONE ? "已撤回上一步" : "点击与空格相邻的棋子";
            case WON -> "已通关";
            case STATS -> "通关统计 · 有效 " + state.validMoves + " 步，无效 " + state.invalidMoves + " 步";
        });
    }


    private void confirmReset() {
        if (!remoteSession) {
            send(Action.RESET, 0);
            return;
        }
        new android.app.AlertDialog.Builder(this).setTitle("复位开发板棋局？")
                .setMessage("将清空棋盘和步数。")
                .setPositiveButton("复位", (dialog, which) -> send(Action.RESET, 0))
                .setNegativeButton("取消", null).show();
    }

    private void useRemote(SerialTransport transport, String label) {
        disconnectRemote();
        remoteSession = true;
        sessionReady = false;
        setGameEnabled(false);
        source.setText("EGO1 · " + label);
        connectionState.setText("正在读取开发板当前棋局…");
        int generation = sessionGeneration;
        RemoteBoard remote = new RemoteBoard(transport, state -> runOnUiThread(() -> {
            if (generation != sessionGeneration) return;
            sessionReady = true;
            connectionState.setText("已连接 · 棋盘由开发板确认");
            setGameEnabled(true);
            render(state);
        }), error -> runOnUiThread(() -> {
            if (generation != sessionGeneration) return;
            disconnectRemote();
            showConnectionMessage("连接已中断 · " + error + "。请点连接按钮重试");
        }));
        board = remote;
        remote.start();
    }

    private void disconnectRemote() {
        sessionGeneration++;
        if (board instanceof RemoteBoard remote) remote.close();
        if (remoteSession) {
            board = null;
            sessionReady = false;
            source.setText("EGO1 · 已断开");
            setGameEnabled(false);
        }
    }

    private void useSimulator() {
        connections.cancel();
        disconnectRemote();
        remoteSession = false;
        sessionReady = true;
        board = new SimulatedBoard();
        source.setText("EGO1 · 模拟板");
        connectionState.setText("模拟板 · 可切换 USB 或蓝牙连接");
        setGameEnabled(true);
        send(Action.SYNC, 0);
    }

    private void showConnectionMessage(String message) {
        connectionState.setText(message);
    }

    private void setGameEnabled(boolean enabled) {
        enableChildren(gameContent, enabled);
        gameContent.setAlpha(enabled ? 1f : 0.55f);
    }

    private void enableChildren(View target, boolean enabled) {
        target.setEnabled(enabled);
        if (target instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) enableChildren(group.getChildAt(i), enabled);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 207) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) connections.bluetooth();
            else showConnectionMessage("蓝牙权限未授予，可改用 USB 或模拟板");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        connections.cancel();
        if (remoteSession) {
            disconnectRemote();
            showConnectionMessage("已断开 · 回到前台后点连接按钮同步当前棋局");
        }
    }

    @Override
    protected void onDestroy() {
        connections.destroy();
        disconnectRemote();
        super.onDestroy();
    }

    private TextView addCounter(LinearLayout row, String name, int gap) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(7), dp(12), dp(7));
        panel.setBackground(shape(PANEL, 13));
        panel.addView(label(name, 13, MUTED, false));
        TextView value = label("0", 23, INK, true);
        panel.addView(value);
        addWeighted(row, panel, -2, gap);
        return value;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private Button button(String text, int size, int background, int foreground, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(size);
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setBackground(buttonBackground(background));
        if (click != null) button.setOnClickListener(click);
        return button;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private RippleDrawable buttonBackground(int color) {
        return new RippleDrawable(ColorStateList.valueOf(0x33175A43), shape(color, 12), null);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        parent.addView(child, params);
    }

    private void addWeighted(LinearLayout parent, View child, int height, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height < 0 ? height : dp(height), 1f);
        params.setMarginStart(dp(gap));
        parent.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
