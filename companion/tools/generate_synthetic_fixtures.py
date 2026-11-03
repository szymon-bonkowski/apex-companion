#!/usr/bin/env python3
"""Original synthetic typography only. Requires ImageMagick; never downloads game assets.
No tuning/training dataset or real/held-out Apex compatibility claim is made.
"""
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1] / "fixtures"
ROOT.mkdir(exist_ok=True)
FONT = subprocess.check_output(["fc-match", "-f", "%{file}", "sans"], text=True).strip()
samples = [
    ("results-1920", 1920, 1080, "MATCH SUMMARY", ["KILLS 3", "ASSISTS 2", "DAMAGE DEALT 1234", "SQUAD PLACED 4", "RP DELTA +123"], "RESULTS", dict(kills=3, assists=2, damage=1234, placement=4, rpDelta=123)),
    ("results-1280", 1280, 720, "MATCH SUMMARY", ["KILLS 0", "ASSISTS 1", "DAMAGE DEALT 426", "SQUAD PLACED 12", "RP DELTA -45"], "RESULTS", dict(kills=0, assists=1, damage=426, placement=12, rpDelta=-45)),
    ("results-2560", 2560, 1440, "MATCH SUMMARY", ["KILLS 12", "ASSISTS 7", "DAMAGE DEALT 3865", "SQUAD PLACED 1", "RP DELTA +285"], "RESULTS", dict(kills=12, assists=7, damage=3865, placement=1, rpDelta=285)),
    ("missing-fields", 1920, 1080, "MATCH SUMMARY", ["DAMAGE DEALT 100", "SQUAD PLACED 8"], "RESULTS", dict(damage=100, placement=8)),
    ("menu", 1920, 1080, "PLAY APEX LOBBY", ["READY"], "MENU", {}),
    ("loading", 1280, 720, "LOADING", ["CONNECTING"], "LOADING", {}),
    ("spectator", 1920, 1080, "SPECTATING", ["KILLS 3", "DAMAGE DEALT 500"], "SPECTATOR", {}),
    ("session-summary", 1920, 1080, "SESSION SUMMARY", ["DAMAGE DEALT 1500", "KILLS 12"], "SESSION_SUMMARY", {}),
    ("changed-ui", 1920, 1080, "UNRECOGNIZED RESULT VIEW", ["SCORE 1700"], "UNKNOWN", {}),
    ("gameplay", 1920, 1080, "SQUADS LEFT 12", ["KILLS 2"], "IN_MATCH", {}),
]
manifest = dict(schemaVersion=1, dataset="original-synthetic-v1", rights="Original synthetic text and geometric backgrounds; no game artwork or player data.", samples=[])
for id_, width, height, title, stats, screen, fields in samples:
    # Test normalized regions at the same capped processing resolution as Windows.
    scaled_width = min(width, 1920)
    scaled_height = round(height * scaled_width / width)
    font = max(20, round(scaled_width / 1920 * 44))
    for suffix, text, crop_width, crop_height in [
        ("header", [title], round(scaled_width*.9), round(scaled_height*.25)),
        ("stats", stats, round(scaled_width*.9), round(scaled_height*.7)),
    ]:
        args = ["magick", "-size", f"{crop_width}x{crop_height}", "xc:#161b21", "-fill", "white", "-font", FONT, "-pointsize", str(font)]
        for i, line in enumerate(text):
            args += ["-annotate", f"+{font}+{font*2 + i*round(font*1.7)}", line]
        args += [str(ROOT / f"{id_}-{suffix}.png")]
        subprocess.run(args, check=True)
    manifest["samples"].append(dict(id=id_, width=width, height=height, headerImage=f"{id_}-header.png", statisticsImage=f"{id_}-stats.png", screen=screen, fields=fields))
(ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
