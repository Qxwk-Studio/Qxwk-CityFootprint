"""把 Ma Shan Zheng 子集化到「城市表 + ASCII + 常用中文标点」，产物放到 res/font。

分享页的标题只可能是城市名（来自 cities.json）与英文名，用不到 2 万汉字，
整包里塞 5.6MB 字体不值。城市表里出现的字全部保留，另加 ASCII 与常见标点。
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CITIES = os.path.join(ROOT, "app", "src", "main", "assets", "cities.json")
SOURCE = os.path.join(os.environ.get("TEMP", "/tmp"), "MaShanZheng-Regular.ttf")
TARGET_DIR = os.path.join(ROOT, "app", "src", "main", "res", "font")
TARGET = os.path.join(TARGET_DIR, "mashanzheng.ttf")
CHARS_FILE = os.path.join(os.environ.get("TEMP", "."), "mashanzheng-chars.txt")

chars = set()
with open(CITIES, encoding="utf-8") as handle:
    data = json.load(handle)
for city in data.get("cities", []):
    for key in ("name", "nameEn", "province", "provinceEn", "country"):
        chars.update(str(city.get(key) or ""))

# ASCII 可打印字符 + 常见中英文标点（英文名里会出现句点、连字符等）
chars.update(chr(c) for c in range(0x20, 0x7F))
chars.update("，。、；：？！“”‘’（）《》【】—…·「」『』～￥")

with open(CHARS_FILE, "w", encoding="utf-8") as handle:
    handle.write("".join(sorted(chars)))

os.makedirs(TARGET_DIR, exist_ok=True)
os.system(
    f'python -m fontTools.subset "{SOURCE}" --text-file="{CHARS_FILE}" '
    f'--output-file="{TARGET}" --layout-features=* --no-hinting --name-IDs=* --drop-tables+=DSIG'
)
print("chars:", len(chars))
print("output:", TARGET, os.path.getsize(TARGET) if os.path.exists(TARGET) else "MISSING")
