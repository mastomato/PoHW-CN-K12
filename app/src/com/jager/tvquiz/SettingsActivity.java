package com.jager.tvquiz;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 家长设置：密码门禁 + 单列卡片流（瀑布式纵向滚动）。
 * 参照电视端系统设置的信息层级：一个居中窄栏、分组卡片、每行「左标签 / 右取值」，
 * 底部固定操作条常驻，避免长表单把「保存并返回」挤到屏幕外。
 * 可聚焦控件按「行」显式串成上下链，学科复合行内部再串左右链；
 * TV 遥控器不再需要跨栏跳转，也不会出现三栏并排时的文字挤压与底部裁切。
 */
public class SettingsActivity extends Activity {

    private static final String NL = "\n";
    private static final String[] PRESETS = {"", "http://10.0.2.2:8000/", "http://127.0.0.1:8000/"};
    private static final int CARD_BG = 0xFF161E2B;
    /** 居中窄栏的左右留白：卡片与底部操作条共用，保证视觉对齐。 */
    private static final int GUTTER_DP = 88;
    /** 行标签宽度：普通行与学科行分别取不同值，避免右侧取值被压窄。 */
    private static final int LABEL_DP = 150;
    private static final int SUBJ_LABEL_DP = 100;
    /** 卡片内行、按钮、说明共用的上下留白（相邻两行间距 = 上下各一半相加）。 */
    private static final int ROW_GAP_DP = 12;

    private SettingsStore st;
    private PinGate gate;
    private int presetIdx = 0;
    private boolean inSettings = false;
    /** 用户手输过在线源地址后，refreshRows() 不得再用持久化值覆盖掉未保存的输入。 */
    private boolean urlDirty = false;
    /** 程序自身 setText 期间置位，避免 TextWatcher 把回显误判成用户编辑。 */
    private boolean urlProgrammatic = false;

    private View gateView, settingsView;
    private TextView gateTitle, gateMask, gateMsg, gateState, bankStatus, pinState;
    private Button firstGateBtn;
    private EditText urlEdit;
    private Button rowGrade, rowTerm, rowInterval, rowScope, rowSync;
    /** 学科设置复合行：左按钮切教材版本，右按钮切题目配比。 */
    private Button[] rowEd = new Button[Subjects.COUNT];
    private Button[] rowWt = new Button[Subjects.COUNT];
    private final java.util.List<Button[]> subjectPairs = new java.util.ArrayList<Button[]>();
    private Button rowBoot, rowAutoGuard, rowPause, rowResume;
    /** 单列焦点链：每行一个数组，纵向按加入顺序连接。 */
    private final java.util.List<View[]> focusRows = new java.util.ArrayList<View[]>();
    private Button btnChangePin, btnSave;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = new SettingsStore(this);
        BankRepository.installBuiltin(this, st);
        gate = new PinGate(st);
        gateView = buildGate();
        settingsView = buildSettings();
        enterGate(false);
    }

    @Override public void onBackPressed() {
        if (inSettings) save();
        else finish();
    }

    /** 遥控器/键盘实体数字键直接输入，避免 TV 上焦点吞掉点击导致密码输不进去。 */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (!inSettings && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int code = e.getKeyCode();
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                gate.key(String.valueOf(code - KeyEvent.KEYCODE_0));
                refreshGate();
                return true;
            }
            if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
                gate.key(String.valueOf(code - KeyEvent.KEYCODE_NUMPAD_0));
                refreshGate();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DEL) {
                gate.key("退格");
                refreshGate();
                return true;
            }
            // DPAD_CENTER / ENTER 交给按钮自身的 click，不在这里消费，避免双触发
        }
        return super.dispatchKeyEvent(e);
    }

    private static String ts(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }

    // ---------- 密码门禁 ----------

    private View buildGate() {
        LinearLayout l = Ui.vbox(this);
        l.setBackgroundColor(Ui.BG);
        l.setPadding(Ui.dp(this, 40), Ui.dp(this, 12), Ui.dp(this, 40), Ui.dp(this, 12));
        l.setGravity(Gravity.CENTER_HORIZONTAL);

        gateTitle = Ui.tv(this, "", 24, 0xFFFFFFFF);
        l.addView(gateTitle, Ui.fixed(this, 38));
        gateMask = Ui.tv(this, "", 30, Ui.AMBER);
        l.addView(gateMask, Ui.fixed(this, 44));
        gateMsg = Ui.tv(this, "", 15, Ui.AMBER);
        l.addView(gateMsg, Ui.fixed(this, 30));
        gateState = Ui.tv(this, "", 13, Ui.MUTED);
        l.addView(gateState, Ui.fixed(this, 28));

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "清空", "0", "确定"};
        for (int row = 0; row < 4; row++) {
            LinearLayout r = Ui.hbox(this);
            for (int col = 0; col < 3; col++) {
                final String k = keys[row * 3 + col];
                Button btn = Ui.button(this, k, 0xFF243044, 0xFFF5F7FA, 20);
                btn.setFocusableInTouchMode(false);
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { onKey(k); }
                });
                Ui.focus(btn, this, 0xFF243044, 0xFFF5F7FA);
                if (firstGateBtn == null) firstGateBtn = btn;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        Ui.dp(this, 120), Ui.dp(this, 46));
                p.setMargins(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
                r.addView(btn, p);
            }
            l.addView(r, Ui.fixed(this, 54));
        }
        l.addView(Ui.tv(this,
                "遥控器可直接按数字键输入；也可以点屏幕键盘，输完点「确定」。",
                13, Ui.MUTED), Ui.fixed(this, 26));
        l.addView(Ui.tv(this,
                "忘记密码请在电脑执行：adb shell pm clear com.jager.tvquiz（会清空全部设置与统计）",
                13, Ui.MUTED), Ui.fixed(this, 26));
        return l;
    }

    private void onKey(String k) {
        gate.key(k);
        refreshGate();
    }

    private void refreshGate() {
        gateTitle.setText(gate.title());
        gateMask.setText(gate.mask());
        gateMsg.setText(gate.messageText());
        gateState.setText(st.hasPin()
                ? "当前状态：已设置家长密码（修改密码需先验证旧密码）"
                : "当前状态：尚未设置密码，本次为首次设置");
        String t = gate.consumeToast();
        if (!t.isEmpty()) Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
        if (gate.consumeEnterSettings()) enterSettings();
    }

    private void enterGate(boolean reset) {
        if (reset) gate.backToGate();
        inSettings = false;
        setContentView(gateView);
        if (firstGateBtn != null) firstGateBtn.requestFocus();
        refreshGate();
    }

    private void enterSettings() {
        inSettings = true;
        setContentView(settingsView);
        refreshRows();
        if (rowGrade != null) rowGrade.requestFocus();
    }

    // ---------- 单列卡片流布局 ----------

    /** 分组卡片：顶部「色条 + 序号标题」，内部逐行堆叠，卡片之间留呼吸间距。 */
    private LinearLayout addCard(LinearLayout parent, String title) {
        LinearLayout card = Ui.vbox(this);
        card.setBackground(Ui.round(CARD_BG, 14, this));
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 12));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        parent.addView(card, cp);

        LinearLayout head = Ui.hbox(this);
        head.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackground(Ui.round(Ui.AMBER, 2, this));
        head.addView(bar, new LinearLayout.LayoutParams(Ui.dp(this, 4), Ui.dp(this, 18)));
        TextView tv = Ui.tv(this, title, 16, 0xFFF5F7FA, Gravity.LEFT);
        tv.setPadding(Ui.dp(this, 10), 0, 0, 0);
        head.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // 标题行随系统字体缩放自适应，不写死 28dp 高度。
        head.setMinimumHeight(Ui.dp(this, 28));
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void note(LinearLayout parent, String text) {
        TextView t = Ui.tv(this, text, 12, Ui.MUTED, Gravity.LEFT);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 2));
        parent.addView(t, rowLp());
    }

    /** 把本行可聚焦控件登记进纵向焦点链；说明文字与禁用占位不登记。 */
    private void track(View... views) {
        java.util.List<View> usable = new java.util.ArrayList<View>();
        for (View v : views) {
            if (v != null && v.isEnabled() && v.isFocusable()) usable.add(v);
        }
        if (!usable.isEmpty()) focusRows.add(usable.toArray(new View[0]));
    }

    /** 卡片内所有行、按钮、说明共用的上下留白，避免遥控器视角下按钮连成一片。 */
    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int half = Ui.dp(this, ROW_GAP_DP / 2);
        p.setMargins(0, half, 0, half);
        return p;
    }

    /** 统一行：左侧固定标签，右侧取值按钮按 weight 填满窄栏，不再写死 178dp。 */
    private Button addRow(LinearLayout parent, String label, View.OnClickListener l) {
        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.tv(this, label, 15, 0xFFC7D2E2, Gravity.LEFT),
                new LinearLayout.LayoutParams(Ui.dp(this, LABEL_DP),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        Button b = Ui.button(this, "", Ui.BTN_BG, 0xFFF5F7FA, 14);
        b.setSingleLine(false);
        b.setMaxLines(2);
        b.setEllipsize(null);
        b.setMinHeight(Ui.dp(this, 44));
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
        b.setOnClickListener(l);
        Ui.focus(b, this, Ui.BTN_BG, 0xFFF5F7FA);
        // 高度随系统字体缩放自适应，避免大字模式下两行文案被固定 44dp 裁切。
        row.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, rowLp());
        track(b);
        return b;
    }

    private void addButton(LinearLayout parent, String label, int bg, View.OnClickListener l) {
        Button b = Ui.button(this, label, bg, 0xFFFFFFFF, 15);
        b.setOnClickListener(l);
        Ui.focus(b, this, bg, 0xFFFFFFFF);
        b.setMinHeight(Ui.dp(this, 44));
        parent.addView(b, rowLp());
        track(b);
    }

    private View buildSettings() {
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 36), Ui.dp(this, 10), Ui.dp(this, 36), Ui.dp(this, 8));

        root.addView(Ui.tv(this, "家长设置", 24, 0xFFFFFFFF), Ui.fixed(this, 36));
        pinState = Ui.tv(this, "", 13, Ui.MUTED, Gravity.LEFT);
        pinState.setPadding(Ui.dp(this, GUTTER_DP), 0, 0, 0);
        root.addView(pinState, Ui.fixed(this, 22));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(true);
        LinearLayout shell = Ui.vbox(this);
        shell.setPadding(Ui.dp(this, GUTTER_DP), Ui.dp(this, 4),
                Ui.dp(this, GUTTER_DP), Ui.dp(this, 16));
        LinearLayout col = Ui.vbox(this);
        shell.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(shell, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 01 出题基础 ----
        LinearLayout quiz = addCard(col, "01  出题基础");
        rowGrade = addRow(quiz, "所在年级", new View.OnClickListener() {
            @Override public void onClick(View v) {
                st.grade(st.grade() % SettingsStore.MAX_GRADE + 1); refreshRows();
            }
        });
        rowTerm = addRow(quiz, "学期", new View.OnClickListener() {
            @Override public void onClick(View v) {
                st.termIndex((st.termIndex() + 1) % SettingsStore.TERMS.length);
                refreshRows();
            }
        });
        rowInterval = addRow(quiz, "出题频率", new View.OnClickListener() {
            @Override public void onClick(View v) {
                int i = (st.intervalIndex() + 1) % SettingsStore.INTERVALS.length;
                st.intervalSec(SettingsStore.INTERVALS[i]);
                refreshRows();
            }
        });
        rowScope = addRow(quiz, "出题范围", new View.OnClickListener() {
            @Override public void onClick(View v) { st.allSubjects(!st.allSubjects()); refreshRows(); }
        });
        rowSync = addRow(quiz, "自动更新", new View.OnClickListener() {
            @Override public void onClick(View v) {
                st.syncIndex((st.syncIndex() + 1) % SettingsStore.SYNC_HOURS.length);
                refreshRows();
            }
        });
        note(quiz, "点击右侧取值即可循环切换，离开设置页时统一保存。");
        note(quiz, "学期只影响内置题库的抽题范围：选「上册 / 下册」时优先出该学期与全学年通用的题；"
                + "题库未标注学期的题始终参与，该学期题量不足时自动放宽到同年级同学科。");

        // ---- 02 学科设置 ----
        LinearLayout subjects = addCard(col, "02  学科设置 · 教材版本 / 题目配比");
        for (int s = 0; s < Subjects.COUNT; s++) addSubjectRow(subjects, s);
        note(subjects, "配比按四科权重随机抽题，0 表示该科不出题；「其他学科」配比仅在「出题范围 = 全学科」且题库确有其他学科题时生效，"
                + "关闭或题库不可用时显示「未启用」/ 自动跳过，但仍可预先设置。内置离线题库按课标纲目组织，不含出版社版本，"
                + "教材版本用于在线题包筛选、题面标注与错题标记。");

        // ---- 03 开机与播放 ----
        LinearLayout boot = addCard(col, "03  开机与播放");
        rowBoot = addRow(boot, "开机自启", new View.OnClickListener() {
            @Override public void onClick(View v) { st.bootAutostart(!st.bootAutostart()); refreshRows(); }
        });
        rowAutoGuard = addRow(boot, "自动守护", new View.OnClickListener() {
            @Override public void onClick(View v) { st.autoGuardOnBoot(!st.autoGuardOnBoot()); refreshRows(); }
        });
        rowPause = addRow(boot, "暂停视频", new View.OnClickListener() {
            @Override public void onClick(View v) { st.pauseOnQuiz(!st.pauseOnQuiz()); refreshRows(); }
        });
        rowResume = addRow(boot, "恢复播放", new View.OnClickListener() {
            @Override public void onClick(View v) { st.resumePlayback(!st.resumePlayback()); refreshRows(); }
        });
        addButton(boot, "开启厂商自启动设置", 0xFF2C6E8F, new View.OnClickListener() {
            @Override public void onClick(View v) {
                BootSettings.open(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this,
                        "小米等 ROM 需允许「自启动」，否则收不到开机广播",
                        Toast.LENGTH_LONG).show();
            }
        });
        note(boot, "「启动即守护」默认开启：打开 APP 即自动进入守护；「开机自启」默认关闭，"
                + "需家长自行开启并允许厂商自启动权限后，才会随开机拉起。");
        note(boot, "暂停视频是 best-effort：靠音频焦点 + 媒体键请求播放器暂停，不保证所有视频 App 响应。");

        // ---- 04 错题库 ----
        LinearLayout wrong = addCard(col, "04  错题库");
        addButton(wrong, "查看错题库", 0xFFB06A2C, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, WrongBookActivity.class));
            }
        });
        note(wrong, "同一题答错 " + AnswerPolicy.WRONG_LIMIT + " 次自动换题并记录。");

        // ---- 05 在线题库源 ----
        LinearLayout online = addCard(col, "05  在线题库源（可选 · 需自行配置）");
        urlEdit = new EditText(this);
        urlEdit.setSingleLine(true);
        urlEdit.setHint("https://your-server/  (index.json 所在目录)");
        urlEdit.setTextSize(14);
        urlEdit.setTextColor(0xFFF5F7FA);
        urlEdit.setHintTextColor(0xFF7C8AA3);
        urlEdit.setBackground(Ui.round(0xFF1B2432, 8, this));
        urlEdit.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        urlEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                if (!urlProgrammatic) urlDirty = true;
            }
        });
        online.addView(urlEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42)));
        track(urlEdit);

        addButton(online, "填入示例地址", 0xFF243044, new View.OnClickListener() {
            @Override public void onClick(View v) {
                presetIdx = (presetIdx + 1) % PRESETS.length;
                setUrlText(PRESETS[presetIdx], true);
            }
        });

        bankStatus = Ui.tv(this, "", 13, Ui.MUTED, Gravity.LEFT);
        bankStatus.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        bankStatus.setMinHeight(Ui.dp(this, 72));
        bankStatus.setBackground(Ui.round(0xFF1B2432, 10, this));
        online.addView(bankStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addButton(online, "检查题库更新", 0xFF6C4F9E, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = urlEdit.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(SettingsActivity.this,
                            "当前使用内置离线题库；如需在线源请先填写地址",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                st.bankUrl(url);
                urlDirty = false;
                bankStatus.setText("正在同步题库…");
                BankRepository.sync(getApplicationContext(), st, url, true,
                        new BankRepository.Callback() {
                            @Override public void onDone(BankRepository.SyncResult r) {
                                updateBankStatus();
                                Toast.makeText(SettingsActivity.this, r.message, Toast.LENGTH_LONG).show();
                                sendReload();
                            }
                        });
            }
        });
        // 离线说明紧跟「检查题库更新」：它解释的是在线源开关本身。
        note(online, "默认完全离线：内置全量 K12 题库，不联网；只有填写在线源地址后才会按周期联网。");

        root.addView(sv, Ui.fill(1));

        // ---- 底部固定操作条：长表单滚动时仍可随时保存 ----
        LinearLayout footer = Ui.hbox(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(Ui.dp(this, GUTTER_DP), 0, Ui.dp(this, GUTTER_DP), 0);
        btnChangePin = Ui.button(this, "修改家长密码", 0xFFA32D2D, 0xFFFFFFFF, 15);
        btnChangePin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gate.startChange(); enterGate(false); }
        });
        Ui.focus(btnChangePin, this, 0xFFA32D2D, 0xFFFFFFFF);
        btnSave = Ui.button(this, "保存并返回", 0xFF1D9E75, 0xFFFFFFFF, 15);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        Ui.focus(btnSave, this, 0xFF1D9E75, 0xFFFFFFFF);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
        fp.setMargins(Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 4));
        footer.addView(btnChangePin, fp);
        footer.addView(btnSave, fp);
        root.addView(footer, Ui.fixed(this, 54));
        track(btnChangePin, btnSave);
        btnChangePin.setNextFocusRightId(ensureId(btnSave));
        btnSave.setNextFocusLeftId(ensureId(btnChangePin));

        wireVerticalFocus();
        wireSubjectRows();
        return root;
    }

    private String intervalLabel() {
        int sec = st.intervalSec();
        for (int i = 0; i < SettingsStore.INTERVALS.length; i++) {
            if (SettingsStore.INTERVALS[i] == sec) return SettingsStore.INTERVAL_LABELS[i];
        }
        return sec + " 秒";
    }

    /** 统一写入 URL 输入框：程序回显不算用户编辑，示例地址算用户编辑。 */
    private void setUrlText(String text, boolean userEdited) {
        if (urlEdit == null) return;
        urlProgrammatic = true;
        try {
            urlEdit.setText(text == null ? "" : text);
            urlEdit.setSelection(urlEdit.getText().length());
        } finally {
            urlProgrammatic = false;
        }
        if (userEdited) urlDirty = true;
    }

    /**
     * 单列纵向焦点链：把每一行的可聚焦控件连到上一行 / 下一行的首个可聚焦控件。
     * ScrollView 内跨卡片嵌套 ViewGroup 时，系统默认找不到邻居，显式连接才不会卡住。
     * 学科行有两个按钮，按「上」回到上一行首值、按「下」去下一行首值，符合遥控器直觉。
     */
    private void wireVerticalFocus() {
        for (int i = 0; i < focusRows.size(); i++) {
            View[] row = focusRows.get(i);
            View up = i > 0 ? firstUsable(focusRows.get(i - 1)) : null;
            View down = i < focusRows.size() - 1 ? firstUsable(focusRows.get(i + 1)) : null;
            for (View v : row) {
                if (v == null || !v.isEnabled() || !v.isFocusable()) continue;
                if (up != null) v.setNextFocusUpId(ensureId(up));
                if (down != null) v.setNextFocusDownId(ensureId(down));
                // 单列布局没有横向邻居：把未显式定义的左右目标指向自身，
                // 阻止系统按几何邻近把焦点甩到上一行 / 下一行。
                // EditText 例外：左右键留给光标移动（输入在线源 URL）。
                if (!(v instanceof EditText)) {
                    int self = ensureId(v);
                    if (v.getNextFocusLeftId() == View.NO_ID) v.setNextFocusLeftId(self);
                    if (v.getNextFocusRightId() == View.NO_ID) v.setNextFocusRightId(self);
                }
            }
        }
    }

    private static View firstUsable(View[] row) {
        for (View v : row) {
            if (v != null && v.isEnabled() && v.isFocusable()) return v;
        }
        return null;
    }

    /**
     * 学科设置复合行：学科名 + 教材版本 + 题目配比共占一行。
     * 教材与配比按 2:1 分配窄栏宽度；四科都可配权，第 4 槽在全学科关闭时标注「未启用」。
     */
    private void addSubjectRow(LinearLayout parent, final int subj) {
        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.tv(this, Subjects.name(subj), 14, 0xFFC7D2E2, Gravity.LEFT),
                new LinearLayout.LayoutParams(
                        Ui.dp(this, SUBJ_LABEL_DP), ViewGroup.LayoutParams.WRAP_CONTENT));

        Button ed = Ui.button(this, "", Ui.BTN_BG, 0xFFF5F7FA, 14);
        ed.setSingleLine(false);
        ed.setMaxLines(2);
        ed.setEllipsize(null);
        ed.setMinHeight(Ui.dp(this, 44));
        ed.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        ed.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                st.setEditionIndexFor(subj,
                        (st.editionIndexFor(subj) + 1) % SettingsStore.EDITIONS.length);
                refreshRows();
            }
        });
        Ui.focus(ed, this, Ui.BTN_BG, 0xFFF5F7FA);
        row.addView(ed, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2f));

        Button wt = Ui.button(this, "", Ui.BTN_BG, 0xFFF5F7FA, 14);
        wt.setSingleLine(false);
        wt.setMaxLines(2);
        wt.setEllipsize(null);
        wt.setMinHeight(Ui.dp(this, 44));
        wt.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        // 四科都可配权：全学科关闭时第 4 槽仍可预设，只是不参与抽题。
        wt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                st.weight(subj, (st.weight(subj) + 1) % (SubjectWeights.MAX_WEIGHT + 1));
                refreshRows();
            }
        });
        Ui.focus(wt, this, Ui.BTN_BG, 0xFFF5F7FA);
        row.addView(wt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        track(ed, wt);

        parent.addView(row, rowLp());
        rowEd[subj] = ed;
        rowWt[subj] = wt;
        subjectPairs.add(new Button[]{ed, wt});
    }

    /** 学科行内部左右链：教材版本 ↔ 题目配比；其余行的左右键不再跨栏乱跳。 */
    private void wireSubjectRows() {
        for (Button[] pair : subjectPairs) {
            Button ed = pair[0], wt = pair[1];
            if (ed == null || wt == null) continue;
            int edId = ensureId(ed);
            if (wt.isEnabled()) {
                int wtId = ensureId(wt);
                ed.setNextFocusRightId(wtId);
                wt.setNextFocusLeftId(edId);
            }
        }
    }

    private static int ensureId(View v) {
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        return v.getId();
    }

    private void refreshRows() {
        rowGrade.setText(st.gradeLabel());
        rowTerm.setText(st.termLabel());
        rowInterval.setText(intervalLabel());
        rowScope.setText(st.allSubjects() ? "全学科（含物理/历史等）" : "仅数 / 语 / 英");
        rowSync.setText(SettingsStore.SYNC_LABELS[st.syncIndex()]);
        for (int s = 0; s < Subjects.COUNT; s++) {
            rowEd[s].setText(st.editionFor(s));
            String w = "配比 " + st.weight(s);
            if (s == Subjects.OTHER && !st.allSubjects()) w += " · 未启用";
            rowWt[s].setText(w);
        }
        rowBoot.setText(st.bootAutostart() ? "开启（响应开机广播）" : "关闭（需家长手动开启）");
        rowAutoGuard.setText(st.autoGuardOnBoot() ? "开启（启动即守护）" : "关闭（仅恢复上次）");
        rowPause.setText(st.pauseOnQuiz() ? "开启（音频焦点+媒体键）" : "关闭");
        rowResume.setText(st.resumePlayback() ? "开启（尝试恢复）" : "关闭");
        if (!urlDirty) setUrlText(st.bankUrl(), false);
        pinState.setText(st.hasPin() ? "家长密码：已设置 ✓" : "家长密码：未设置 ✗");
        updateBankStatus();
    }

    private void updateBankStatus() {
        bankStatus.setText("题库来源：" + st.bankSource() + NL
                + "版本 " + st.bankVersion() + " · " + st.bankQuestions()
                + " 题 / " + st.bankPacks() + " 包" + NL
                + "资源更新 " + st.bankUpdated() + " · 上次检查 " + ts(st.bankChecked()) + NL
                + st.bankMessage());
    }

    private void sendReload() {
        if (!QuizOverlayService.isRunning()) return;
        Intent i = new Intent(this, QuizOverlayService.class);
        i.setAction(QuizOverlayService.ACTION_RELOAD);
        try { startService(i); } catch (Exception ignored) { }
    }

    private void save() {
        st.bankUrl(urlEdit.getText().toString().trim());
        urlDirty = false;
        sendReload();
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
