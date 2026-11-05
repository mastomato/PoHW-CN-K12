package com.jager.tvquiz;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 打赏作者：全屏展示收款码，遥控器 ← / → 直接切换微信支付 / 支付宝。
 *
 * 这里没有任何支付 SDK、订单或网络请求：只是把两张静态收款码作为 APK 资源显示出来，
 * 扫码与付款完全在家长手机上完成，APP 不接触金额与账号，也不需要 INTERNET 权限。
 * 收款码图片由 tools/branding 之外的静态资源提供，见 res/drawable-nodpi/。
 */
public class DonateActivity extends Activity {

    private static final int CH_WECHAT = 0;
    private static final int CH_ALIPAY = 1;

    private static final int WECHAT_ON = 0xFF1DAD74;
    private static final int WECHAT_OFF = 0xFF1C3A33;
    private static final int ALIPAY_ON = 0xFF2A8BF0;
    private static final int ALIPAY_OFF = 0xFF1B2F49;
    private static final int BACK_BG = 0xFF243044;

    private int channel = CH_WECHAT;
    private Button tabWechat, tabAlipay, btnBack;
    private ImageView qr;
    private TextView hint;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildView());
        setChannel(CH_WECHAT);
    }

    /** 左右键在任何焦点位置都能切渠道，符合 TV 遥控器习惯；返回键直接退出本页。 */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { setChannel(CH_WECHAT); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { setChannel(CH_ALIPAY); return true; }
            if (code == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    private Button tabButton(String label) {
        Button b = Ui.button(this, label, WECHAT_OFF, 0xFFFFFFFF, 17);
        b.setMinHeight(Ui.dp(this, 48));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setChannel(v == tabAlipay ? CH_ALIPAY : CH_WECHAT);
            }
        });
        return b;
    }

    private View buildView() {
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 56), Ui.dp(this, 22), Ui.dp(this, 56), Ui.dp(this, 16));

        root.addView(Ui.tv(this, "请作者喝一杯咖啡", 26, 0xFFFFFFFF, Gravity.LEFT),
                Ui.fixed(this, 42));
        TextView intro = Ui.tv(this,
                "如果 PoHW 帮你把电视时间管起来了，可以请作者喝一杯咖啡。完全自愿，"
                        + "不影响任何功能；本页只显示静态收款码，不发起支付、不联网。",
                15, Ui.MUTED, Gravity.LEFT);
        intro.setLineSpacing(0, 1.15f);
        root.addView(intro, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout tabs = Ui.hbox(this);
        tabs.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 12));
        tabWechat = tabButton("微信支付");
        tabAlipay = tabButton("支付宝");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMargins(0, 0, Ui.dp(this, 12), 0);
        tabs.addView(tabWechat, tp);
        tabs.addView(tabAlipay, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 收款码按可用高度等比缩放，保持原始像素比例，避免二维码被拉伸后扫不出。
        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.round(0xFF141D2B, 14, this));
        qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        box.addView(qr, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(box, Ui.fill(1));

        hint = Ui.tv(this, "", 14, Ui.MUTED, Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        btnBack = Ui.button(this, "返回家长设置", BACK_BG, 0xFFFFFFFF, 15);
        btnBack.setMinHeight(Ui.dp(this, 44));
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        Ui.focus(btnBack, this, BACK_BG, 0xFFFFFFFF);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, Ui.dp(this, 10), 0, 0);
        root.addView(btnBack, bp);

        // 显式 TV 焦点链：两个 tab 向下统一落到「返回」，返回向上回到当前渠道 tab。
        tabWechat.setId(1);
        tabAlipay.setId(2);
        btnBack.setId(3);
        tabWechat.setNextFocusDownId(3);
        tabAlipay.setNextFocusDownId(3);
        btnBack.setNextFocusUpId(1);
        return root;
    }

    private void paintTab(Button b, boolean on, int active, int idle) {
        int bg = on ? active : idle;
        int fg = on ? 0xFFFFFFFF : 0xFF9FB0C8;
        b.setBackground(Ui.round(bg, 10, this));
        b.setTextColor(fg);
        Ui.focus(b, this, bg, fg);
    }

    private void setChannel(int c) {
        channel = c;
        paintTab(tabWechat, channel == CH_WECHAT, WECHAT_ON, WECHAT_OFF);
        paintTab(tabAlipay, channel == CH_ALIPAY, ALIPAY_ON, ALIPAY_OFF);
        qr.setImageResource(c == CH_WECHAT
                ? R.drawable.donate_wechat : R.drawable.donate_alipay);
        hint.setText(c == CH_WECHAT
                ? "用手机微信「扫一扫」扫码  ·  遥控器 ← → 切换渠道，返回键退出"
                : "用手机支付宝「扫一扫」扫码  ·  遥控器 ← → 切换渠道，返回键退出");
        // onCreate 里窗口可能还没拿到焦点，post 到下一帧再抢，TV 遥控器才不会落在空处。
        final Button target = c == CH_WECHAT ? tabWechat : tabAlipay;
        target.post(new Runnable() {
            @Override public void run() { target.requestFocus(); }
        });
        btnBack.setNextFocusUpId(c == CH_WECHAT ? 1 : 2);
    }
}
