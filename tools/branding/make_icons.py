#!/usr/bin/env python3
"""Generate the launcher icon, TV banner and notification icon with Pillow.

The open book connects this app to 知行答题 TV. A shield and check identify
the guard mode. All assets are deterministic and require no external artwork.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "res"
PREVIEW = ROOT / ".branding_preview.png"
S = 1024
PURPLE_TOP = (112, 88, 222)
PURPLE_BOTTOM = (77, 52, 174)
PURPLE = (112, 88, 222)
PURPLE_DARK = (88, 64, 193)
INK = (34, 35, 57)
MUTED = (105, 108, 133)
GREEN = (42, 163, 119)
WHITE = (255, 255, 255)
LAVENDER = (235, 230, 255)
FONT = "/System/Library/Fonts/STHeiti Medium.ttc"


def font(size):
    return ImageFont.truetype(FONT, size) if Path(FONT).exists() else ImageFont.load_default()


def gradient(w, h, top, bottom):
    image = Image.new("RGB", (w, h), top)
    draw = ImageDraw.Draw(image)
    for y in range(h):
        t = y / max(h - 1, 1)
        color = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        draw.line((0, y, w, y), fill=color)
    return image


def icon_master():
    image = gradient(S, S, PURPLE_TOP, PURPLE_BOTTOM).convert("RGBA")
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((30, 30, S - 30, S - 30), radius=224, fill=255)
    image.putalpha(mask)
    d = ImageDraw.Draw(image)

    # Open book: the same silhouette and palette used by 知行答题 TV.
    d.polygon(((230, 284), (480, 323), (480, 719), (230, 681)), fill=WHITE)
    d.polygon(((544, 323), (794, 284), (794, 681), (544, 719)), fill=LAVENDER)
    d.polygon(((488, 339), (512, 358), (536, 339), (536, 715), (512, 739), (488, 715)),
              fill=(215, 206, 250))

    # Guard shield overlays a corner of the book without hiding its spine.
    d.ellipse((603, 580, 880, 858), fill=WHITE)
    d.polygon(((741, 610), (836, 645), (824, 747), (741, 816), (658, 747), (646, 645)),
              fill=PURPLE_DARK)
    d.line(((688, 711), (727, 750), (795, 679)), fill=WHITE, width=24, joint="curve")
    for x, y in ((688, 711), (795, 679)):
        d.ellipse((x - 12, y - 12, x + 12, y + 12), fill=WHITE)
    return image


def banner_master(icon):
    w, h = 1280, 720
    image = gradient(w, h, (248, 248, 253), (235, 230, 255))
    d = ImageDraw.Draw(image)
    d.rectangle((0, 0, 22, h), fill=PURPLE)
    small = icon.resize((390, 390), Image.Resampling.LANCZOS)
    image.paste(small, (66, 165), small)
    d.text((500, 194), "知行答题守护", font=font(86), fill=INK)
    d.rounded_rectangle((500, 325, 716, 381), radius=28, fill=PURPLE)
    d.text((530, 332), "电视答题", font=font(30), fill=WHITE)
    d.text((500, 427), "连续练习 · 家长设置每轮题数", font=font(42), fill=MUTED)
    return image


def notification_master():
    image = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    d.polygon(((150, 250), (475, 310), (475, 760), (150, 700)), fill=WHITE)
    d.polygon(((549, 310), (874, 250), (874, 700), (549, 760)), fill=WHITE)
    d.polygon(((487, 327), (512, 346), (537, 327), (537, 758), (512, 782), (487, 758)),
              fill=WHITE)
    return image


def save(image, folder, name, size):
    path = RES / folder / name
    path.parent.mkdir(parents=True, exist_ok=True)
    image.resize(size, Image.Resampling.LANCZOS).save(path, "PNG", optimize=True)
    print(path.relative_to(ROOT), size)


def main():
    icon = icon_master()
    for folder, size in (("drawable-mdpi", 48), ("drawable-hdpi", 96),
                         ("drawable-xhdpi", 144), ("drawable-xxhdpi", 192),
                         ("drawable-xxxhdpi", 288)):
        save(icon, folder, "ic_launcher.png", (size, size))
    banner = banner_master(icon)
    save(banner, "drawable-xhdpi", "tv_banner.png", (640, 360))
    stat = notification_master()
    save(stat, "drawable-hdpi", "ic_stat_quiz.png", (24, 24))
    save(stat, "drawable-xhdpi", "ic_stat_quiz.png", (32, 32))
    preview = Image.new("RGB", (1120, 420), (247, 248, 252))
    preview.paste(icon.resize((360, 360), Image.Resampling.LANCZOS), (24, 30),
                  icon.resize((360, 360), Image.Resampling.LANCZOS))
    preview.paste(banner.resize((640, 360), Image.Resampling.LANCZOS), (440, 30))
    preview.save(PREVIEW)
    print("BRANDING_OK", PREVIEW)


if __name__ == "__main__":
    main()
