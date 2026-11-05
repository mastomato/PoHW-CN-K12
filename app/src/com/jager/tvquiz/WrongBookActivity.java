package com.jager.tvquiz;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 错题库浏览页：TV 遥控器友好的单题翻页卡片。
 * 数据一律后台加载，主线程不做 open()/list()；标题用真实 COUNT(*)，
 * 列表只展示最近 WrongBook.LIST_LIMIT 条，超过时明确提示被截断。
 */
public class WrongBookActivity extends Activity {

    private static final String NL = String.valueOf((char) 10);

    private final List<WrongBook.Entry> items = new ArrayList<WrongBook.Entry>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int pos = 0;
    private int totalCount = 0;
    private boolean clearArmed = false;
    private boolean alive = false;
    private boolean loaded = false;

    private TextView title, detail;
    private Button btnPrev, btnNext, btnDelete, btnClear, btnBack;

    /** 清空二次确认 5 秒后自动复位，避免误触后按钮长期停在危险态。 */
    private final Runnable disarmClear = new Runnable() {
        @Override public void run() {
            clearArmed = false;
            if (alive && btnClear != null) btnClear.setText("清空错题库");
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = true;
        setContentView(buildView());
        // 加载期间导航按钮是 disabled，初始焦点落在始终可用的「返回控制台」。
        if (btnBack != null) btnBack.requestFocus();
        render();
        loadAsync();
    }

    @Override protected void onResume() {
        super.onResume();
        if (loaded) loadAsync();
    }

    @Override protected void onDestroy() {
        alive = false;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 冷启动、返回页面、删除/清空之后统一走后台加载。 */
    private void loadAsync() {
        WrongBook.refreshAsync(getApplicationContext(), new WrongBook.RefreshCallback() {
            @Override public void onLoaded(List<WrongBook.Entry> snapshot, int total) {
                if (!alive) return;
                items.clear();
                if (snapshot != null) items.addAll(snapshot);
                totalCount = total;
                if (pos >= items.size()) pos = 0;
                ui.removeCallbacks(disarmClear);
                clearArmed = false;
                if (btnClear != null) btnClear.setText("清空错题库");
                loaded = true;
                render();
            }
        });
    }

    private Button addButton(LinearLayout row, String label, int bg,
                             View.OnClickListener l) {
        Button b = Ui.button(this, label, bg, 0xFFFFFFFF, 16);
        b.setOnClickListener(l);
        Ui.focus(b, this, bg, Ui.BLUE, 0xFFFFFFFF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                Ui.dp(this, 158), Ui.dp(this, 48));
        p.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
        row.addView(b, p);
        return b;
    }

    private View buildView() {
        LinearLayout l = Ui.vbox(this);
        l.setBackgroundColor(Ui.BG);
        l.setPadding(Ui.dp(this, 40), Ui.dp(this, 14), Ui.dp(this, 40), Ui.dp(this, 10));

        l.addView(Ui.tv(this, "错题库  ·  Proof of Homework", 26, 0xFFFFFFFF),
                Ui.fixed(this, 40));
        title = Ui.tv(this, "", 15, Ui.AMBER, Gravity.LEFT);
        l.addView(title, Ui.fixed(this, 26));

        detail = Ui.tv(this, "", 17, 0xFFE6EDF7, Gravity.LEFT);
        detail.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        detail.setBackground(Ui.round(0xFF1B2432, 12, this));
        detail.setLineSpacing(0, 1.22f);
        ScrollView detailScroll = new ScrollView(this);
        detailScroll.setFillViewport(true);
        detailScroll.addView(detail);
        l.addView(detailScroll, Ui.fill(1));

        LinearLayout row = Ui.hbox(this);
        btnPrev = addButton(row, "上一题", 0xFF243044, new View.OnClickListener() {
            @Override public void onClick(View v) { prev(); }
        });
        btnNext = addButton(row, "下一题", 0xFF243044, new View.OnClickListener() {
            @Override public void onClick(View v) { next(); }
        });
        btnDelete = addButton(row, "删除本题", 0xFFA32D2D, new View.OnClickListener() {
            @Override public void onClick(View v) { deleteCurrent(); }
        });
        btnClear = addButton(row, "清空错题库", 0xFF6C4F9E, new View.OnClickListener() {
            @Override public void onClick(View v) { clearAll(); }
        });
        btnBack = addButton(row, "返回控制台", 0xFF1D9E75, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        l.addView(row, Ui.fixed(this, 56));

        l.addView(Ui.tv(this,
                "遥控器方向键选中按钮后按确定；同一题答错 " + AnswerPolicy.WRONG_LIMIT
                        + " 次会自动换题并记录到这里。",
                13, Ui.MUTED), Ui.fixed(this, 24));
        return l;
    }

    private void prev() {
        if (items.isEmpty()) return;
        pos = (pos - 1 + items.size()) % items.size();
        render();
    }

    private void next() {
        if (items.isEmpty()) return;
        pos = (pos + 1) % items.size();
        render();
    }

    private void deleteCurrent() {
        if (items.isEmpty()) return;
        final long id = items.get(pos).id;
        WrongBook.deleteAsync(getApplicationContext(), id, new Runnable() {
            @Override public void run() {
                if (!alive) return;
                loadAsync();
                Toast.makeText(WrongBookActivity.this, "已删除这道错题",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void clearAll() {
        if (items.isEmpty()) return;
        if (!clearArmed) {
            clearArmed = true;
            btnClear.setText("再按一次确认清空");
            ui.removeCallbacks(disarmClear);
            ui.postDelayed(disarmClear, 5000L);
            Toast.makeText(this, "5 秒内再按一次「清空错题库」确认",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ui.removeCallbacks(disarmClear);
        WrongBook.clearAsync(getApplicationContext(), new Runnable() {
            @Override public void run() {
                if (!alive) return;
                pos = 0;
                loadAsync();
                Toast.makeText(WrongBookActivity.this, "错题库已清空",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String ts(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }

    private void setNavEnabled(boolean p, boolean n, boolean d, boolean c) {
        if (btnPrev != null) btnPrev.setEnabled(p);
        if (btnNext != null) btnNext.setEnabled(n);
        if (btnDelete != null) btnDelete.setEnabled(d);
        if (btnClear != null) btnClear.setEnabled(c);
    }

    private void render() {
        if (!alive || title == null) return;
        if (!loaded) {
            title.setText("正在加载错题库…");
            detail.setText("正在读取本地错题库，请稍候。");
            setNavEnabled(false, false, false, false);
            return;
        }
        if (items.isEmpty()) {
            title.setText("共 " + Math.max(totalCount, 0) + " 题 · 错题库为空");
            detail.setText("还没有错题。" + NL + NL
                    + "答题时同一题累计答错 " + AnswerPolicy.WRONG_LIMIT
                    + " 次会自动换一道新题，并把原题记入错题库。" + NL
                    + "这里可以随时查看正确答案、解析和你当时选错的选项。");
            setNavEnabled(false, false, false, false);
            return;
        }

        setNavEnabled(items.size() > 1, items.size() > 1, true, true);
        WrongBook.Entry e = items.get(pos);
        int total = Math.max(totalCount, items.size());
        String count = total > items.size()
                ? "共 " + total + " 题 · 只展示最近 " + items.size() + " 条"
                : "共 " + total + " 题";
        title.setText("错题库 · " + count + " · 当前第 " + (pos + 1) + " 题");

        StringBuilder s = new StringBuilder();
        s.append("答错 ").append(e.wrongCount).append(" 次   ·   最近 ")
                .append(ts(e.lastWrongAt)).append(NL);
        s.append(e.gradeLabel).append(" · ").append(e.subject).append(" · ")
                .append(e.edition).append(" · ").append(e.topic).append(NL).append(NL);
        s.append("题目：").append(e.text).append(NL).append(NL);
        for (int i = 0; i < 4; i++) {
            String mark = i == e.answerIndex ? "✓ 正确"
                    : (e.lastPick.equals(e.options[i]) ? "✗ 你选的" : "      ");
            s.append(mark).append("  ").append((char) ('A' + i)).append(". ")
                    .append(e.options[i]).append(NL);
        }
        s.append(NL).append("解析：").append(e.explain.isEmpty() ? "（暂无）" : e.explain);
        detail.setText(s.toString());
    }
}
