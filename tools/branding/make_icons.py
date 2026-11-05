#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PoHW 品牌资源生成器（零第三方设计工具，PIL 即可，结果完全可复现）。

设计语言「极客几何」：
  - 深蓝渐变圆角方底，呼应控制台深色主题；
  - 琥珀色计时弧 = 「租约到期、续租 30 分钟」；
  - 屏幕里一枚青绿对勾 = 「答对一题」；
  - 无任何文字，保证 TV 远景与小尺寸下仍可辨。

用法：python3 tools/branding/make_icons.py
产物：app/res/drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png
      app/res/drawable-xhdpi/tv_banner.png
      app/res/drawable-{h,xh}dpi/ic_stat_quiz.png（纯白透明状态栏小图标）
      .branding_preview.png（人工核对用拼图，不入库）
"""
import math
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "res")
PREVIEW = os.path.join(ROOT, ".branding_preview.png")

NAVY_TOP = (16, 25, 42)
NAVY_BOTTOM = (30, 46, 76)
SCREEN_BG = (26, 40, 66)
AMBER = (255, 209, 102)
MINT = (74, 222, 160)
WHITE = (255, 255, 255)
MUTED = (158, 178, 208)

LATIN_BOLD = "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"
CJK_BOLD = "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Black.ttc"
CJK_INDEX = 2  # NotoSansCJK-Black.ttc: 0=JP 1=KR 2=SC 3=TC 4=HK

S = 1024  # 主画布，所有位图先在此分辨率绘制再 LANCZOS 降采样


def gradient(size, top, bottom):
    im = Image.new("RGB", (size, size), top)
    d = ImageDraw.Draw(im)
    for y in range(size):
        t = float(y) / (size - 1)
        d.line([(0, y), (size, y)], fill=tuple(
            int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return im


def thick_line(d, pts, fill, width):
    d.line(pts, fill=fill, width=width, joint="curve")
    r = width / 2.0
    for x, y in (pts[0], pts[-1]):
        d.ellipse([x - r, y - r, x + r, y + r], fill=fill)


def round_arc(d, box, start, end, fill, width):
    """带圆头的圆弧：PIL 的 arc 没有 round cap，两端补圆点。"""
    d.arc(box, start=start, end=end, fill=fill, width=width)
    cx = (box[0] + box[2]) / 2.0
    cy = (box[1] + box[3]) / 2.0
    rx = (box[2] - box[0]) / 2.0
    ry = (box[3] - box[1]) / 2.0
    r = width / 2.0
    for deg in (start, end):
        a = math.radians(deg)
        x, y = cx + rx * math.cos(a), cy + ry * math.sin(a)
        d.ellipse([x - r, y - r, x + r, y + r], fill=fill)


def master_icon():
    im = gradient(S, NAVY_TOP, NAVY_BOTTOM).convert("RGBA")
    # 圆角方底，四角透明（TV launcher 直接显示原始位图）
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([24, 24, S - 24, S - 24], radius=228, fill=255)
    im.putalpha(mask)
    d = ImageDraw.Draw(im)

    # 外圈计时弧：从正上方顺时针 292 度，缺口在右上，暗示「租约倒计时」
    round_arc(d, [126, 126, S - 126, S - 126], -90, 202, AMBER, 34)
    # 起点小圆点，强化「计时起点」
    d.ellipse([S / 2 - 22, 104, S / 2 + 22, 148], fill=AMBER)

    # 电视/屏幕：圆角描边 + 内填充
    d.rounded_rectangle([268, 326, 756, 666], radius=46, fill=SCREEN_BG, outline=AMBER, width=28)
    # 一枚对勾：答对一题
    thick_line(d, [(344, 494), (452, 588), (686, 414)], MINT, 46)
    # 底座
    thick_line(d, [(512, 666), (512, 716)], AMBER, 20)
    thick_line(d, [(420, 716), (604, 716)], AMBER, 20)
    return im


def font(path, size, index=0):
    if os.path.isfile(path):
        return ImageFont.truetype(path, size, index=index)
    return ImageFont.load_default()


def master_banner(icon):
    W, H = 1280, 720
    im = gradient_sized(W, H, NAVY_TOP, NAVY_BOTTOM).convert("RGB")
    d = ImageDraw.Draw(im)
    # 左侧图标 + 琥珀分隔线
    small = icon.resize((440, 440), Image.LANCZOS)
    im.paste(small, (72, 140), small)
    d.rounded_rectangle([566, 176, 574, 544], radius=4, fill=AMBER)
    # 右侧字标
    d.text((640, 196), "PoHW", font=font(LATIN_BOLD, 132), fill=WHITE)
    d.text((646, 356), "Proof of Homework", font=font(LATIN_BOLD, 44), fill=AMBER)
    d.text((646, 444), "答对一题，续租 30 分钟",
           font=font(CJK_BOLD, 52, CJK_INDEX), fill=MUTED)
    return im


def gradient_sized(w, h, top, bottom):
    im = Image.new("RGB", (w, h), top)
    d = ImageDraw.Draw(im)
    for y in range(h):
        t = float(y) / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(
            int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return im


def master_stat():
    """状态栏小图标：纯白透明剪影，由系统在通知栏着色。"""
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([90, 130, S - 90, S - 230], radius=90, outline=WHITE, width=70)
    thick_line(d, [(270, 510), (440, 660), (760, 330)], WHITE, 90)
    thick_line(d, [(S / 2, S - 230), (S / 2, S - 130)], WHITE, 62)
    thick_line(d, [(330, S - 130), (694, S - 130)], WHITE, 62)
    return im


def save_scaled(master, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    master.resize((size, size) if isinstance(size, int) else size, Image.LANCZOS).save(
        path, "PNG", optimize=True)
    print("  %-58s %s" % (os.path.relpath(path, ROOT), size))


def main():
    icon = master_icon()
    print("launcher icons:")
    for folder, size in (("drawable-mdpi", 48), ("drawable-hdpi", 96),
                         ("drawable-xhdpi", 144), ("drawable-xxhdpi", 192),
                         ("drawable-xxxhdpi", 288)):
        save_scaled(icon, os.path.join(RES, folder, "ic_launcher.png"), size)
    print("tv banner / stat icons:")
    banner = master_banner(icon)
    save_scaled(banner, os.path.join(RES, "drawable-xhdpi", "tv_banner.png"), (640, 360))
    stat = master_stat()
    for folder, size in (("drawable-hdpi", 24), ("drawable-xhdpi", 32)):
        save_scaled(stat, os.path.join(RES, folder, "ic_stat_quiz.png"), size)

    # 人工核对拼图： launcher / banner / stat
    prev = Image.new("RGB", (1120, 420), (11, 17, 28))
    ic = icon.resize((360, 360), Image.LANCZOS)
    prev.paste(ic, (24, 30), ic)
    bn = banner.resize((640, 360), Image.LANCZOS)
    prev.paste(bn, (440, 30))
    st = stat.resize((64, 64), Image.LANCZOS)
    prev.paste(st, (370, 320), st)
    prev.save(PREVIEW)
    print("BRANDING_OK preview=%s" % PREVIEW)


if __name__ == "__main__":
    main()
