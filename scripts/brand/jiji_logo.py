# -*- coding: utf-8 -*-
"""
记记酱（JIJI）的造型真源。

一份 path 数据，生成各端资产，改造型只改这个文件：
  python3 scripts/brand/jiji_logo.py            # 全量重新生成
  python3 scripts/brand/jiji_logo.py --preview  # 只出 docs/品牌/ 下的 SVG 与预览 PNG
  python3 scripts/brand/jiji_logo.py --draft D  # 只往目录 D 里出草稿 PNG，画的时候用

产物：
  docs/品牌/记记酱.svg / 记记酱-全身.svg / 应用图标-浅色.svg / -深色.svg / -单色.svg
  androidApp/.../drawable/ic_launcher_{background,foreground,monochrome}.xml
  iosApp/.../AppIcon.appiconset/*.png（需要 npx @resvg/resvg-js-cli 光栅化）
  shared/.../ui/brand/JijiArt.kt（开屏动效用的同一份 path）
  docs/介绍页/index.html 里 JIJI:BEGIN / JIJI:END 之间的 <symbol>（全身版）

坐标系：头像 512×512，正面；全身 640×800，头像平移 (64, 0) 后压在身体上。
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# ---------------------------------------------------------------- path 小工具
def _n(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


_TOK = re.compile(r"[MLCQAZ]|-?\d*\.?\d+")
_ARGC = {"M": 2, "L": 2, "C": 6, "Q": 4, "A": 7}


def _segments(d):
    """拆成 [(cmd, [数值...])]。只认绝对坐标的 M L C Q A Z——造型数据全部按这个子集写。"""
    toks = _TOK.findall(d)
    out, i, cmd = [], 0, None
    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
            if cmd == "Z":
                out.append(("Z", []))
            continue
        n = _ARGC[cmd]
        out.append((cmd, [float(x) for x in toks[i:i + n]]))
        i += n
        if cmd == "M":
            cmd = "L"
    return out


def _join(segs):
    return " ".join(c + " ".join(_n(v) for v in a) if a else c for c, a in segs)


def mirror(d, axis=256.0):
    """左右镜像（绕 x=axis）。弧线要翻 sweep。"""
    out = []
    for c, a in _segments(d):
        if c == "A":
            out.append((c, [a[0], a[1], -a[2], a[3], 1 - a[4], 2 * axis - a[5], a[6]]))
        else:
            out.append((c, [2 * axis - v if i % 2 == 0 else v for i, v in enumerate(a)]))
    return _join(out)


def shift(d, dx, dy):
    out = []
    for c, a in _segments(d):
        if c == "A":
            out.append((c, a[:5] + [a[5] + dx, a[6] + dy]))
        else:
            out.append((c, [v + (dx if i % 2 == 0 else dy) for i, v in enumerate(a)]))
    return _join(out)


def _reverse(d):
    """把一条开放的 M/L/C 路径倒过来走（不带开头的 M）。"""
    segs = _segments(d)
    pts = [segs[0][1]]
    for c, a in segs[1:]:
        pts.append(a[-2:])
    out = []
    for k in range(len(segs) - 1, 0, -1):
        c, a = segs[k]
        prev = pts[k - 1]
        if c == "L":
            out.append(("L", prev))
        elif c == "C":
            out.append(("C", [a[2], a[3], a[0], a[1]] + prev))
        else:
            raise ValueError("sym() 只支持 L / C")
    return _join(out)


def sym(half, axis=256.0):
    """左半条（从顶部中线走到底部中线）→ 左右对称的闭合形。"""
    return half + " " + _reverse(mirror(half, axis)) + " Z"


def ell(cx, cy, rx, ry=None):
    ry = rx if ry is None else ry
    return (f"M{_n(cx - rx)} {_n(cy)} A{_n(rx)} {_n(ry)} 0 1 0 {_n(cx + rx)} {_n(cy)} "
            f"A{_n(rx)} {_n(ry)} 0 1 0 {_n(cx - rx)} {_n(cy)} Z")


def lens(cx, cy, rx, ry, k=0.70):
    """镜片：介于圆和圆角方之间（k=0.5523 是正圆，越大越方）。"""
    a, b = rx * k, ry * k
    return (f"M{_n(cx - rx)} {_n(cy)} C{_n(cx - rx)} {_n(cy - b)} {_n(cx - a)} {_n(cy - ry)} {_n(cx)} {_n(cy - ry)} "
            f"C{_n(cx + a)} {_n(cy - ry)} {_n(cx + rx)} {_n(cy - b)} {_n(cx + rx)} {_n(cy)} "
            f"C{_n(cx + rx)} {_n(cy + b)} {_n(cx + a)} {_n(cy + ry)} {_n(cx)} {_n(cy + ry)} "
            f"C{_n(cx - a)} {_n(cy + ry)} {_n(cx - rx)} {_n(cy + b)} {_n(cx - rx)} {_n(cy)} Z")


# ---------------------------------------------------------------- 配色
C = {
    "ink":      "#7E4A74",   # 头发 / 耳朵 / 衣服的线稿：梅子色，不用纯黑
    "inkSoft":  "#DE98A6",   # 皮肤的线更淡一档
    "inkBlue":  "#6F8FCB",   # 蓝色衣物、缎带的线
    "inkGlass": "#1D1924",   # 镜框
    "lash":     "#3A2232",   # 眼线
    "lashSoft": "#7A5468",   # 下睫毛
    "lidShade": "#DCCFEA",   # 上眼睑落在眼白上的影子
    "brow":     "#BE6E8C",
    "strand":   "#EC93B1",   # 发丝线
    "shine":    "#FFE9F0",   # 头顶那圈高光
    "streak":   "#BCE0FB",   # 蓝色挑染
    "plum":     "#C2557F",   # 头顶分缝里那缕深色
    "earIn":    "#FFDCE6",
    "earInk":   "#EBA3BE",
    "fluff":    "#FFFAF5",
    "fluffInk": "#F0C6D4",
    "skinShade": "#F6C6C2",
    "blush":    "#FF9DB6",
    "blushInk": "#F2789A",
    "nose":     "#E8A0A0",
    "mouth":    "#B4506C",
    "mouthIn":  "#DC5673",
    "tongue":   "#FF9DB2",
    "white":    "#FFFFFF",
    "ribbon":   "#CFE5FB",   # 淡蓝缎带
    "ribbonDk": "#A9CBF2",
    "clip":     "#F3E9FF",   # 左边两根细发夹
    "clipInk":  "#9A84CC",
    "pin":      "#FF9CBD",   # 交叉的小发卡
    "pinInk":   "#C4557F",
    "bat":      "#D8EDFF",   # 右边的蝙蝠翅膀发夹
    "paw":      "#FFB29C",   # 镜框左上角的肉球
    "pawInk":   "#5A2E3A",
    "ahoge":    "#FAB9CC",
    "pupilL":   "#43190A",
    "pupilR":   "#1D164E",
    "ringL":    "#6A2A0C",
    "ringR":    "#241A5C",
    "glowL":    "#FFF2B8",
    "glowR":    "#DCE8FF",
}
# 渐变都用绝对坐标（userSpaceOnUse），SVG / Android / Compose 三端同一套数值
GRAD = {
    # 头发：粉 → 发梢淡紫蓝。后发、鬓发、小辫共用，所以发梢自然就是蓝紫的
    "hair":      (256,  88, 256, 476, [(0.0, "#FCCBD8"), (0.42, "#F7ABC3"), (0.68, "#F0A4C6"),
                                        (0.85, "#C9ACE7"), (1.0, "#A8B7F5")]),
    "hairIn":    (256, 384, 256, 470, [(0.0, "#8A66C400"), (1.0, "#8A66C470")]),
    "tuft":      (256, 150, 256, 326, [(0.0, "#F9B4CA"), (0.5, "#F2A6C8"), (1.0, "#B5B2F2")]),
    "earL":      ( 96, 160, 184, 104, [(0.0, "#A9B8F7"), (0.35, "#C6B4EF"), (0.75, "#F3B0CB"), (1.0, "#F8B2C7")]),
    "earR":      (416, 160, 328, 104, [(0.0, "#A9B8F7"), (0.35, "#C6B4EF"), (0.75, "#F3B0CB"), (1.0, "#F8B2C7")]),
    "skin":      (256, 200, 256, 430, [(0.0, "#FFF7F1"), (1.0, "#FFE5D9")]),
    "irisL":     (256, 278, 256, 342, [(0.0, "#7A2E0E"), (0.40, "#EC7F1C"), (0.75, "#FFBB3A"), (1.0, "#FFE68E")]),
    "irisR":     (256, 278, 256, 342, [(0.0, "#2A2270"), (0.40, "#5D5FD8"), (0.75, "#8FA6F6"), (1.0, "#D2E2FF")]),
    "skyLight":  (120,   0, 392, 512, [(0.0, "#FFE7F3"), (0.5, "#FBB3DA"), (1.0, "#C3A4F0")]),
    "skyDark":   (120,   0, 392, 512, [(0.0, "#4A2D63"), (0.55, "#33204C"), (1.0, "#1E1436")]),
}


def S(part, d, fill=None, stroke=None, sw=0.0, op=1.0, var=None, sub=None):
    """一条形状。part 是动效分组名；var 是表情变体（None=常驻，eo/eh/ew 眼，mw/mo 嘴）；sub 是变体里的小组。"""
    return dict(part=part, d=d, fill=fill, stroke=stroke, sw=sw, op=op, var=var, sub=sub)


def both(part_l, part_r, d, **kw):
    """左右对称的一对。"""
    return [S(part_l, d, **kw), S(part_r, mirror(d), **kw)]


INK = C["ink"]

# ---------------------------------------------------------------- 头：后层（全身版里压在身体后面）
TUFT_L = ("M106 296 C70 302 40 332 30 376 C26 394 28 410 34 426 C40 408 50 398 62 392 "
          "C62 410 66 424 76 438 C82 416 94 402 108 394 Z")
BOW_UP_L = "M84 284 C64 256 38 246 30 264 C24 280 48 294 84 288 Z"
BOW_DN_L = "M84 288 C60 292 36 306 42 324 C48 340 72 320 86 294 Z"
STREAM_L = "M72 292 C58 330 60 380 48 436 L58 430 L64 444 C76 392 74 338 86 296 Z"

HAIR_BACK = sym("M256 100 C164 100 78 176 74 290 C72 346 82 396 104 430 C116 448 132 458 152 462 "
                "C154 452 160 445 170 440 C178 452 192 462 210 468 C214 459 222 452 232 448 "
                "C238 455 246 460 256 462")

HEAD_BACK = (
    both("tuftL", "tuftR", TUFT_L, fill="grad:hair", stroke=INK, sw=5)
    + both("bowL", "bowR", STREAM_L, fill=C["ribbon"], stroke=C["inkBlue"], sw=3.5)
    + both("bowL", "bowR", BOW_UP_L, fill=C["ribbon"], stroke=C["inkBlue"], sw=3.5)
    + both("bowL", "bowR", BOW_DN_L, fill=C["ribbonDk"], stroke=C["inkBlue"], sw=3.5)
    + [S("hairBack", HAIR_BACK, "grad:hair", INK, 5),
       S("hairBack", HAIR_BACK, "grad:hairIn")]          # 波波头的里层：下巴底下那一段压暗
)

# ---------------------------------------------------------------- 头：前层
EAR_L = "M108 204 C90 146 94 76 120 32 C124 25 133 24 139 29 C186 62 224 106 240 152 Z"
EAR_IN_L = "M126 186 C112 140 114 92 128 56 C162 82 196 116 212 150 Z"
EAR_FLUFF_L = ("M126 182 C122 160 124 140 130 120 C134 132 138 142 144 150 C142 130 144 112 150 94 "
               "C156 110 162 122 170 132 C170 122 172 114 176 106 C182 120 190 132 198 142 "
               "C202 146 206 150 210 152 Z")

FACE = sym("M256 168 C186 168 136 210 132 268 C128 322 142 364 180 396 C206 416 232 428 256 428")

CAP = ("M92 304 C78 190 148 90 256 88 C364 90 434 190 420 304 C408 290 402 274 400 256 "
       "C398 274 388 288 372 298 C370 278 362 260 352 242 "
       "C350 250 346 254 340 257 C334 252 328 246 324 238 "
       "C320 248 312 255 302 258 C286 256 266 240 258 214 "
       "C250 240 230 256 212 258 C202 255 194 248 190 238 "
       "C186 246 180 252 174 257 C168 254 164 250 162 242 "
       "C158 262 152 282 140 298 C128 286 118 272 112 256 C110 274 104 290 92 304 Z")

TUFT_MID = "M238 190 C232 240 240 290 260 324 C270 292 282 240 282 190 Z"

SIDE_L = ("M100 240 C84 286 76 350 88 400 C92 418 100 432 112 442 C112 430 114 420 120 412 "
          "C126 430 136 444 152 450 C142 434 138 418 140 398 C144 358 146 300 134 244 Z")

LENS_L = lens(194, 309, 55, 56)
BRIDGE = "M249 300 C252 293 260 293 263 300"
AHOGE = "M256 96 C244 72 244 44 264 34 C276 28 290 32 294 42 C284 37 273 40 268 50 C261 64 265 82 271 96 Z"


def _shine():
    """头顶那圈高光：上缘顺着头顶的弧，下缘是一排小尖。"""
    def yu(x):
        return 136 + ((x - 256) / 106.0) ** 2 * 44
    xs = list(range(166, 347, 12))
    top = [(x, yu(x)) for x in xs]
    d = f"M{_n(top[0][0])} {_n(top[0][1])} " + " ".join(f"L{_n(x)} {_n(y)}" for x, y in top[1:])
    low = []
    for i, x in enumerate(reversed(xs)):
        low.append((x, yu(x) + (17 if i % 2 else 8)))
    d += " " + " ".join(f"L{_n(x)} {_n(y)}" for x, y in low) + " Z"
    return d


EYE_DX = 118   # 两只眼的高光相距这么多（高光不镜像，整体平移）
EYE_DY = 3     # 整只眼往下挪一点，给镜框上沿让位


def _eye(side):
    """一只睁开的眼。side='L' 是画面左边那只（橙），'R' 是右边那只（蓝紫）。"""
    m0 = (lambda d: d) if side == "L" else mirror
    m = lambda d: shift(m0(d), 0, EYE_DY)
    sh = lambda d: shift(d, 0 if side == "L" else EYE_DX, EYE_DY)      # 高光不镜像：两只眼的光从同一边来
    eye, lash = "eye" + side, "lash" + side
    iris = ell(197, 310, 27.5, 33)
    white = [
        S(eye, m("M162 308 C163 286 180 271 200 271 C217 271 229 283 231 300 C233 322 222 342 198 345 "
                 "C176 346 162 330 162 308 Z"), C["white"], var="eo"),
        # 上眼睑压在眼白上的一条淡影
        S(eye, m("M164 300 C168 284 182 274 200 274 C216 274 227 284 230 298 C220 288 210 284 198 284 "
                 "C184 284 172 290 164 300 Z"), C["lidShade"], op=.75, var="eo"),
    ]
    ball = [
        S(eye, m(iris), "grad:iris" + side, var="eo"),
        S(eye, m(iris), None, C["ring" + side], 3, .55, var="eo"),
        S(eye, m("M170.54 301 A27.5 33 0 0 1 223.46 301 C208 309 186 309 170.54 301 Z"), C["pupil" + side], op=.38, var="eo"),
        S(eye, m(ell(197, 311, 11, 15.5)), C["pupil" + side], var="eo"),
        S(eye, m(ell(197, 332, 16, 8)), C["glow" + side], op=.75, var="eo"),
        S(eye, sh(ell(185, 293, 9.5)), C["white"], var="eo"),
        S(eye, sh(ell(211, 328, 4.4)), C["white"], var="eo"),
        S(eye, sh(ell(179, 310, 3)), C["white"], op=.85, var="eo"),
    ]
    ball = [dict(b, sub="iris") for b in ball]
    lashes = [
        S(lash, m("M146 290 C158 270 178 259 200 259 C216 259 230 268 238 286 C230 276 216 271 200 271 "
                  "C184 271 170 280 164 298 C162 303 160 308 160 312 C154 304 150 298 146 290 Z"), C["lash"], var="eo"),
        S(lash, m("M153 301 C148 300 143 301 139 304 C145 305 150 308 154 312 Z"), C["lash"], var="eo"),
        S(lash, m("M178 347 C190 352 208 351 220 344"), None, C["lashSoft"], 3, .8, var="eo"),
    ]
    return white, ball, lashes


_WL, _BL, _LL = _eye("L")
_WR, _BR, _LR = _eye("R")
EYES_OPEN = _WL + _WR + _BL + _BR + _LL + _LR   # 白 → 眼珠 → 睫毛：介绍页要把眼珠单独包一层跟指针

HAPPY_L = "M160 318 C172 290 220 290 234 318"
CLOSED_R = mirror("M160 306 C176 326 218 326 234 306")

HEAD_FRONT = (
    # 猫耳：外耳 → 内耳 → 绒毛，底边藏在头发里
    [S("earL", EAR_L, "grad:earL", INK, 5),
     S("earL", EAR_IN_L, C["earIn"], C["earInk"], 3),
     S("earL", EAR_FLUFF_L, C["fluff"], C["fluffInk"], 2.5),
     S("earR", mirror(EAR_L), "grad:earR", INK, 5),
     S("earR", mirror(EAR_IN_L), C["earIn"], C["earInk"], 3),
     S("earR", mirror(EAR_FLUFF_L), C["fluff"], C["fluffInk"], 2.5)]
    # 脸
    + [S("face", FACE, "grad:skin", C["inkSoft"], 4),
       S("face", shift(CAP, 0, 10), C["skinShade"], op=.5)]
    + both("blush", "blush", ell(156, 378, 24, 11), fill=C["blush"], op=.5)
    + both("blush", "blush", "M144 373 L140 383", stroke=C["blushInk"], sw=2.6, op=.7)
    + both("blush", "blush", "M155 373 L151 383", stroke=C["blushInk"], sw=2.6, op=.7)
    + both("blush", "blush", "M166 373 L162 383", stroke=C["blushInk"], sw=2.6, op=.7)
    + EYES_OPEN
    # 眯眼笑 / 单眼眨（介绍页的表情，图标和开屏不用）
    + [S("eyeL", HAPPY_L, None, C["lash"], 7, var="eh"),
       S("eyeR", mirror(HAPPY_L), None, C["lash"], 7, var="eh")]
    + [dict(s, var="ew", sub=None) for s in _WL + _BL + _LL]
    + [S("eyeR", CLOSED_R, None, C["lash"], 7, var="ew")]
    # 鼻 / 嘴
    + [S("mouth", "M254 374 C255 377 257 378 259 378", None, C["nose"], 3.4),
       S("mouth", "M242 394 C245 402 253 402 256 395 C259 402 267 402 270 394", None, C["mouth"], 4, var="mw"),
       S("mouth", "M240 390 C248 394 264 394 272 390 C270 412 242 412 240 390 Z", C["mouthIn"], C["mouth"], 3, var="mo"),
       S("mouth", ell(256, 405, 9, 4.5), C["tongue"], var="mo"),
       S("mouth", "M245 392 L249 399 L253 393 Z", C["white"], var="mo")]
    # 镜腿先画，让鬓发压住
    + both("glass", "glass", "M140 300 L104 292", stroke=C["inkGlass"], sw=8)
    # 鬓发
    + [S("sideL", SIDE_L, "grad:hair", INK, 5),
       S("sideL", "M112 270 C102 320 102 380 114 430", None, C["strand"], 3, .7),
       S("sideR", mirror(SIDE_L), "grad:hair", INK, 5),
       S("sideR", mirror("M112 270 C102 320 102 380 114 430"), None, C["strand"], 3, .7)]
    # 中间那缕：先画，从刘海的拱里垂到鼻梁，梢上是淡紫蓝
    + [S("bangs", TUFT_MID, "grad:tuft", INK, 4)]
    # 刘海
    + [S("bangs", CAP, "grad:hair", INK, 5),
       S("bangs", "M168 196 C184 170 208 152 238 145", None, C["shine"], 9, .8),
       S("bangs", "M292 147 C316 154 336 168 350 188", None, C["shine"], 9, .8),
       # 分缝里那缕深色
       S("bangs", "M212 102 C238 90 280 88 304 100 C280 96 240 98 212 106 Z", C["plum"], op=.9),
       # 发丝：从各个缺口往分缝收
       S("bangs", "M112 256 C114 222 128 186 150 158", None, C["strand"], 2.8, .8),
       S("bangs", "M162 242 C164 214 176 184 194 160", None, C["strand"], 2.8, .8),
       S("bangs", "M190 238 C192 212 204 186 220 162", None, C["strand"], 2.8, .8),
       S("bangs", "M324 238 C322 212 310 186 294 162", None, C["strand"], 2.8, .8),
       S("bangs", "M352 242 C350 214 338 184 320 160", None, C["strand"], 2.8, .8),
       S("bangs", "M400 258 C398 222 384 186 362 158", None, C["strand"], 2.8, .8),
       # 蓝色挑染：顺着发缕走
       S("bangs", "M250 106 C224 142 210 196 211 254 C222 202 238 150 262 110 Z", C["streak"], op=.92),
       S("bangs", "M278 106 C320 138 354 192 368 252 C340 198 308 148 270 112 Z", C["streak"], op=.92)]
    # 眉：压在刘海上，淡一点
    + both("brow", "brow", "M168 236 C180 229 200 228 216 233", stroke=C["brow"], sw=3.4, op=.8)
    # 发饰：左边两根细发夹 + 交叉小发卡，右边蝙蝠翅膀
    + [S("clip", "M122 224 L164 214", None, C["clipInk"], 11),
       S("clip", "M122 224 L164 214", None, C["clip"], 6.5),
       S("clip", "M126 242 L168 232", None, C["clipInk"], 11),
       S("clip", "M126 242 L168 232", None, C["clip"], 6.5),
       S("clip", "M346 226 C354 213 368 209 392 211 C388 217 388 222 390 228 C382 226 378 228 376 234 "
                 "C370 230 364 230 360 236 C356 230 352 228 346 226 Z", C["bat"], C["inkBlue"], 3)]
    # 圆框眼镜
    + both("glass", "glass", LENS_L, fill=C["white"], op=.10)
    + both("glass", "glass", LENS_L, stroke=C["inkGlass"], sw=10)
    + [S("glass", BRIDGE, None, C["inkGlass"], 8)]
    # 肉球：挂在左镜框的左上角
    + [S("paw", ell(147, 273, 11.5, 9.5), C["paw"], C["pawInk"], 3),
       S("paw", ell(132, 263, 4.6), C["paw"], C["pawInk"], 2.4),
       S("paw", ell(140, 254, 4.6), C["paw"], C["pawInk"], 2.4),
       S("paw", ell(152, 252, 4.6), C["paw"], C["pawInk"], 2.4),
       S("paw", ell(162, 259, 4.6), C["paw"], C["pawInk"], 2.4)]
    # 呆毛
    + [S("ahoge", AHOGE, C["ahoge"], INK, 5)]
)

DEFAULT_VARS = (None, "eo", "mw")
ALL_VARS = (None, "eo", "eh", "ew", "mw", "mo")
IOS_SCALE = 0.95               # iOS 图标里角色的缩放：四周留白刚好躲开 22.37% 的圆角
ICON_DY = 10                   # 头像 bbox（含白描边）约 x[14,498] y[9,483]，放进图标时下移这么多才居中

# 开屏动效的支点（512 坐标系）：耳根、两只眼心、呆毛根
PIVOTS = {"earL": (172, 196), "earR": (340, 196), "eyeL": (197, 313), "eyeR": (315, 313), "ahoge": (263, 96)}


def head_shapes(variants=DEFAULT_VARS):
    return [s for s in HEAD_BACK + HEAD_FRONT if s["var"] in variants]


SHAPES = head_shapes()

# ---------------------------------------------------------------- 全身（介绍页用）：640×800，头像平移 HEAD_AT 后压在身体上
BODY_W, BODY_H = 640, 800
HEAD_AT = (64, 0)
HEAD_SCALE = 0.92              # 全身版里头稍微收一点，衣服才看得清；绕下巴缩，脖子位置不动
HEAD_PIVOT = (256, 432)


def head_transform():
    k, (px, py) = HEAD_SCALE, HEAD_PIVOT
    return f"translate({_n(HEAD_AT[0] + px * (1 - k))} {_n(HEAD_AT[1] + py * (1 - k))}) scale({k})"

AX = 320.0                     # 身体的对称轴

CB = {
    "cardi":    "#BCD8F7",     # 开衫
    "cardiDk":  "#A3C5EF",
    "band":     "#D6E8FC",     # 罗纹边
    "skirt":    "#B5D3F5",
    "skirtDk":  "#8DB1E6",
    "collar":   "#B9D8F8",
    "cloth":    "#FFFFFF",
    "clothInk": "#B4B0D4",
    "bow":      "#FFB3C9",
    "bowDk":    "#F58FB0",
    "bowInk":   "#D46A8C",
    "gold":     "#F6D06A",
    "goldInk":  "#C2953A",
    "badge":    "#4B3442",
    "shoe":     "#2C2632",
    "shoeInk":  "#15121A",
    "shoeHi":   "#625A70",
    "tail":     "#F7A9C2",
    "tailHi":   "#FFD3E0",
    "aid":      "#FFD0DE",
    "aidInk":   "#E58CAB",
    "button":   "#93ACDD",
}
GRAD["skinB"] = (320, 640, 320, 730, [(0.0, "#FFF1E8"), (1.0, "#FFDCCD")])
GRAD["skirtG"] = (320, 540, 320, 664, [(0.0, "#C3DCF8"), (1.0, "#A6C8F2")])
GRAD["cardiG"] = (320, 470, 320, 680, [(0.0, "#C9E0F9"), (1.0, "#AFCDF2")])


def bboth(part, d, **kw):
    return [S(part, d, **kw), S(part, mirror(d, AX), **kw)]


def _ruffle(x0, x1, y, sag, n, depth):
    """裙摆下的白色荷叶边：沿着一条下垂的弧排 n 个小扇。"""
    def hem(x):
        t = (x - x0) / (x1 - x0)
        return y + sag * 4 * t * (1 - t)
    w = (x1 - x0) / n
    d = f"M{_n(x0)} {_n(hem(x0) - 10)} L{_n(x0)} {_n(hem(x0))}"
    for i in range(n):
        a, b = x0 + i * w, x0 + (i + 1) * w
        d += f" Q{_n((a + b) / 2)} {_n(hem((a + b) / 2) + depth * 2)} {_n(b)} {_n(hem(b))}"
    d += f" L{_n(x1)} {_n(hem(x1) - 10)} Z"
    return d


TAIL = "M380 636 C430 716 560 724 580 636 C586 602 562 578 540 594"

LEG_L = "M266 640 C263 672 268 700 275 728 L311 728 C314 700 315 672 313 640 Z"
SOCK_L = "M272 716 L313 716 C313 738 312 754 311 772 L277 772 C276 754 274 738 272 716 Z"
FRILL_L = ("M268 713 C271 703 281 703 284 711 C287 702 298 702 301 711 C304 703 317 705 317 714 "
           "C317 723 305 725 301 719 C298 727 287 727 284 719 C281 726 271 725 268 719 Z")
SHOE_L = "M260 776 C259 755 319 755 319 777 C321 793 312 799 290 799 C268 799 258 793 260 776 Z"

SKIRT = sym("M320 510 L266 510 L262 550 C238 580 216 606 202 638 C232 656 278 664 320 664", AX)
BLOUSE = sym("M320 474 L304 448 C292 446 274 450 258 459 C250 484 246 506 244 528 C268 537 296 540 320 540", AX)
SLEEVE_W_L = "M262 458 C240 464 224 484 218 510 L254 520 C256 496 260 476 268 463 Z"
COLLAR = ("M298 440 C286 446 274 452 264 460 C280 484 300 497 320 506 C340 497 360 484 376 460 "
          "C366 452 354 446 342 440 L320 486 Z")

PANEL_L = ("M252 480 C232 520 212 590 204 682 C212 690 228 693 240 690 C240 630 248 570 258 524 "
           "C260 506 260 492 258 480 Z")
BAND_L = "M256 484 C258 530 242 608 234 686"
ARM_L = ("M258 476 C246 472 232 476 222 486 C188 510 146 560 130 612 C126 626 128 638 134 646 "
         "L198 664 C214 636 236 592 248 550 C256 522 260 498 258 476 Z")
CUFF_L = "M135 642 L201 661 C201 670 198 681 193 688 C172 688 144 679 129 669 C129 659 131 649 135 642 Z"
HAND_L = "M143 672 C139 687 148 697 158 692 C162 700 174 700 177 692 C184 696 192 690 189 681 L187 674 Z"
FOLD_L = "M212 504 C222 483 240 471 262 475"


def _lace(cx, cy, r=13):
    return [f"M{_n(cx - r)} {_n(cy - r * .8)} L{_n(cx + r)} {_n(cy + r * .8)}",
            f"M{_n(cx + r)} {_n(cy - r * .8)} L{_n(cx - r)} {_n(cy + r * .8)}"]


def _body_shapes():
    ib, ik = C["inkBlue"], CB["clothInk"]
    out = []
    # 猫尾：最底层
    out += [S("tail", TAIL, None, INK, 30), S("tail", TAIL, None, CB["tail"], 21),
            S("tail", "M470 692 C520 700 566 676 572 634", None, CB["tailHi"], 5, .9)]
    back = list(out)
    out = []
    # 腿 / 袜 / 鞋
    out += bboth("leg", LEG_L, fill="grad:skinB", stroke=C["inkSoft"], sw=3.5)
    out += bboth("leg", SOCK_L, fill=CB["cloth"], stroke=ik, sw=3)
    out += bboth("leg", FRILL_L, fill=CB["cloth"], stroke=ik, sw=2.6)
    out += bboth("leg", SHOE_L, fill=CB["shoe"], stroke=CB["shoeInk"], sw=3)
    out += bboth("leg", "M272 779 C282 768 300 768 310 779", stroke=CB["shoeHi"], sw=3.4)
    # 创可贴（右腿上交叉的一对）+ 左膝的爱心贴
    for d in ("M340 664 L360 682", "M360 664 L340 682"):
        out += [S("leg", d, None, CB["aidInk"], 10.5), S("leg", d, None, CB["aid"], 7)]
    out += [S("leg", ell(350, 673, 2.8), CB["cloth"]),
            S("leg", "M290 702 C282 695 280 688 284 685 C287 683 290 685 290 688 C290 685 293 683 296 685 "
                     "C300 688 298 695 290 702 Z", CB["aid"], CB["aidInk"], 2)]
    # 荷叶边 → 裙子
    out += [S("skirt", _ruffle(202, 438, 640, 24, 10, 7), CB["cloth"], ik, 3),
            S("skirt", SKIRT, "grad:skirtG", ib, 4),
            S("skirt", "M262 550 C292 557 348 557 378 550", None, ib, 3),
            S("skirt", "M292 556 C284 590 272 624 258 654", None, CB["skirtDk"], 2.6, .7),
            S("skirt", "M348 556 C356 590 368 624 382 654", None, CB["skirtDk"], 2.6, .7),
            S("skirt", "M320 557 L320 660", None, CB["skirtDk"], 2.6, .5),
            S("skirt", "M214 622 C254 642 386 642 426 622", None, CB["cloth"], 5)]
    for y in (532, 574, 610):
        for x in (304, 336):
            out.append(S("skirt", ell(x, y, 4.2), CB["gold"], CB["goldInk"], 1.6))
    # 脖子 + 领口的 V
    out += [S("neck", "M304 414 L300 440 L320 490 L340 440 L336 414 Z", "#FFEDE3", C["inkSoft"], 3),
            S("neck", "M304 428 C312 440 328 440 336 428 L336 418 L304 418 Z", C["skinShade"], op=.55)]
    # 白上衣（短款）+ 露在开衫外的白袖
    out += bboth("top", SLEEVE_W_L, fill=CB["cloth"], stroke=ik, sw=3.5)
    out += [S("top", BLOUSE, CB["cloth"], ik, 3.5),
            S("top", "M264 520 C284 528 300 530 320 530 C340 530 356 528 376 520", None, "#E6E3F3", 4, .9)]
    # 水手领 + 白杠
    out += [S("top", COLLAR, CB["collar"], ib, 3.5),
            S("top", "M273 464 C287 482 303 493 320 499 C337 493 353 482 367 464", None, CB["cloth"], 3.5)]
    # 粉蝴蝶结
    tail_l = "M316 510 C311 524 305 538 297 549 L311 548 C315 536 318 524 320 514 Z"
    loop_l = "M318 504 C304 489 283 489 281 502 C279 516 302 521 318 510 Z"
    out += bboth("top", tail_l, fill=CB["bowDk"], stroke=CB["bowInk"], sw=2.6)
    out += bboth("top", loop_l, fill=CB["bow"], stroke=CB["bowInk"], sw=2.6)
    out += [S("top", ell(320, 507, 6.5, 7.5), CB["bow"], CB["bowInk"], 2.6)]
    # 徽章
    out += [S("top", "M354 499 L370 499 L370 512 C370 518 366 522 362 524 C358 522 354 518 354 512 Z",
              CB["badge"], CB["shoeInk"], 2),
            S("top", ell(362, 509, 3.4), CB["gold"])]
    # 开衫：前片 → 手 → 袖 → 袖口 → 落肩的翻边
    out += bboth("cardi", PANEL_L, fill="grad:cardiG", stroke=ib, sw=4)
    out += bboth("cardi", BAND_L, stroke=ib, sw=15)
    out += bboth("cardi", BAND_L, stroke=CB["band"], sw=9)
    out += bboth("hand", HAND_L, fill="#FFEDE3", stroke=C["inkSoft"], sw=3)
    out += bboth("cardi", ARM_L, fill="grad:cardiG", stroke=ib, sw=4)
    out += bboth("cardi", "M232 506 C204 536 172 580 156 622", stroke=CB["cardiDk"], sw=4, op=.55)
    out += bboth("cardi", CUFF_L, fill=CB["band"], stroke=ib, sw=3.5)
    for t in (0.2, 0.4, 0.6, 0.8):
        x, y = 135 + 66 * t, 642 + 19 * t
        out += bboth("cardi", f"M{_n(x)} {_n(y + 3)} L{_n(x - 5)} {_n(y + 24)}", stroke=CB["cardiDk"], sw=2.4, op=.8)
    for cx, cy in ((168, 592), (158, 624)):
        for d in _lace(cx, cy):
            out += bboth("cardi", d, stroke=CB["skirtDk"], sw=9)
        for d in _lace(cx, cy):
            out += bboth("cardi", d, stroke=CB["cloth"], sw=5.5)
    out += bboth("cardi", FOLD_L, stroke=ib, sw=17)
    out += bboth("cardi", FOLD_L, stroke=CB["band"], sw=11)
    # 右前片的三颗扣子
    for x, y in ((386, 548), (394, 604), (403, 660)):
        out.append(S("cardi", ell(x, y, 6.5), CB["button"], ib, 2.4))
    return back, out


BODY_BACK, BODY_FRONT = _body_shapes()
BODY_OUTLINE_PARTS = ("tail", "leg", "skirt", "cardi", "hand", "top")


# 贴纸白描边：这些部件在最底层用白色粗描边再画一遍，角色就能从任何底色上跳出来
OUTLINE_PARTS = ("tuftL", "tuftR", "bowL", "bowR", "hairBack", "earL", "earR", "sideL", "sideR", "bangs", "ahoge")
OUTLINE_W = 26.0


def outline_shapes(shapes=None, parts=OUTLINE_PARTS, width=OUTLINE_W):
    out = []
    for s in (SHAPES if shapes is None else shapes):
        if s["part"] not in parts or s["fill"] is None or not s["stroke"]:
            continue                                   # 只描有线稿的实心形：剪影就是它们撑起来的
        out.append(S(s["part"], s["d"], C["white"], C["white"], s["sw"] + width))
    return out


# 图标背景上的点缀（只在不裁切的 iOS 图标里用；Android 外圈会被遮罩吃掉）
def _star(cx, cy, r):
    k = r * 0.28
    return (f"M{cx} {cy - r} C{cx + k} {cy - k} {cx + k} {cy - k} {cx + r} {cy} "
            f"C{cx + k} {cy + k} {cx + k} {cy + k} {cx} {cy + r} "
            f"C{cx - k} {cy + k} {cx - k} {cy + k} {cx - r} {cy} "
            f"C{cx - k} {cy - k} {cx - k} {cy - k} {cx} {cy - r} Z")


DECOR = [
    S("decor", _star(50, 78, 20), C["white"], op=.8),
    S("decor", _star(470, 52, 13), C["white"], op=.6),
    S("decor", _star(474, 474, 15), C["white"], op=.65),
    S("decor", _star(40, 470, 11), C["white"], op=.5),
]


# ---------------------------------------------------------------- SVG
def _fill_attr(f):
    if not f:
        return 'fill="none"'
    return f'fill="url(#{f[5:]})"' if f.startswith("grad:") else f'fill="{f}"'


def _svg_stop(o, c):
    """渐变色可以写成 #RRGGBBAA（带透明度）。"""
    if len(c) == 9:
        return f'<stop offset="{o}" stop-color="{c[:7]}" stop-opacity="{_n(int(c[7:], 16) / 255)}"/>'
    return f'<stop offset="{o}" stop-color="{c}"/>'


def svg_defs(ids=None, prefix=""):
    rows = ["  <defs>"]
    for gid, (x1, y1, x2, y2, stops) in GRAD.items():
        if ids and gid not in ids:
            continue
        st = "".join(_svg_stop(o, c) for o, c in stops)
        rows.append(f'    <linearGradient id="{prefix}{gid}" gradientUnits="userSpaceOnUse" '
                    f'x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{st}</linearGradient>')
    rows.append('    <radialGradient id="glow" gradientUnits="userSpaceOnUse" cx="256" cy="180" r="300">'
                '<stop offset="0" stop-color="#FFFFFF" stop-opacity=".55"/>'
                '<stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient>')
    rows.append("  </defs>")
    return "\n".join(rows)


def svg_paths(shapes, indent="  "):
    rows = []
    for s in shapes:
        a = [f'd="{s["d"]}"', _fill_attr(s["fill"])]
        if s["stroke"]:
            a.append(f'stroke="{s["stroke"]}" stroke-width="{s["sw"]}" stroke-linecap="round" stroke-linejoin="round"')
        if s["op"] != 1.0:
            a.append(f'opacity="{s["op"]}"')
        rows.append(f'{indent}<path ' + " ".join(a) + "/>")
    return "\n".join(rows)


def svg_mark(size=512, bg=None):
    """透明底的角色 mark。"""
    body = svg_paths(outline_shapes() + SHAPES)
    back = f'  <rect width="512" height="512" fill="{bg}"/>\n' if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">\n'
            f'{svg_defs()}\n{back}{body}\n</svg>\n')


def svg_icon(kind="light", size=1024, decor=True, char_scale=IOS_SCALE):
    """应用图标：底 + 光晕 + 点缀 + 角色。kind=light|dark|transparent。"""
    bg = {"light": "skyLight", "dark": "skyDark"}.get(kind)
    rows = []
    if bg:
        rows.append(f'  <rect width="512" height="512" fill="url(#{bg})"/>')
        rows.append('  <rect width="512" height="512" fill="url(#glow)"/>')
    if decor:
        rows.append(svg_paths(DECOR))
    rows.append(f'  <g transform="translate(256 {256 + ICON_DY * char_scale}) scale({char_scale}) translate(-256 -256)">')
    rows.append(svg_paths(outline_shapes() + SHAPES, indent="    "))
    rows.append("  </g>")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">\n'
            f'{svg_defs()}\n' + "\n".join(rows) + "\n</svg>\n")


def _g(shapes, indent, transform=None):
    rows = svg_paths(shapes, indent=indent + ("  " if transform else ""))
    if not transform:
        return rows
    return f'{indent}<g transform="{transform}">\n{rows}\n{indent}</g>'


def fullbody_layers(variants=DEFAULT_VARS, sticker=True):
    """全身的图层：[(shapes, 是否头像坐标系)]，从下到上。"""
    hb = [x for x in HEAD_BACK if x["var"] in variants]
    hf = [x for x in HEAD_FRONT if x["var"] in variants]
    layers = []
    if sticker:
        body_ol = outline_shapes(BODY_BACK + BODY_FRONT, BODY_OUTLINE_PARTS)
        # 线条形的尾巴也要描白边
        body_ol = [S("tail", TAIL, None, C["white"], 30 + OUTLINE_W)] + body_ol
        layers += [(body_ol, False), (outline_shapes(hb + hf), True)]
    layers += [(BODY_BACK, False), (hb, True), (BODY_FRONT, False), (hf, True)]
    return layers


def svg_fullbody(size=640, bg=None, sticker=True):
    t = head_transform()
    rows = [_g(sh, "  ", t if is_head else None) for sh, is_head in fullbody_layers(sticker=sticker)]
    back = f'  <rect width="{BODY_W}" height="{BODY_H}" fill="{bg}"/>\n' if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {BODY_W} {BODY_H}" '
            f'width="{size}" height="{int(size * BODY_H / BODY_W)}">\n'
            f'{svg_defs()}\n{back}' + "\n".join(rows) + "\n</svg>\n")


# ---------------------------------------------------------------- 灰度（iOS 着色图标）
def _gray_hex(c):
    """iOS 着色态只看亮度：把颜色换成等亮度的灰，整体再提亮一点，着色后才不发闷。"""
    r, g, b = int(c[1:3], 16), int(c[3:5], 16), int(c[5:7], 16)
    y = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
    y = min(1.0, y ** 0.8)
    v = f"{int(round(y * 255)):02X}"
    return f"#{v}{v}{v}" + c[7:]


def svg_tinted(size=1024, char_scale=IOS_SCALE):
    global GRAD
    keep = GRAD
    GRAD = {k: (x1, y1, x2, y2, [(o, _gray_hex(c)) for o, c in st]) for k, (x1, y1, x2, y2, st) in keep.items()}
    shapes = []
    for sh in outline_shapes() + SHAPES:
        t = dict(sh)
        for key in ("fill", "stroke"):
            if t[key] and not t[key].startswith("grad:"):
                t[key] = _gray_hex(t[key])
        shapes.append(t)
    body = svg_paths(shapes, indent="    ")
    out = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">\n'
           f'{svg_defs()}\n'
           f'  <g transform="translate(256 {256 + ICON_DY * char_scale}) scale({char_scale}) translate(-256 -256)">\n'
           f'{body}\n  </g>\n</svg>\n')
    GRAD = keep
    return out


# ---------------------------------------------------------------- 单色（Android 13+ 主题图标）
# 系统只看形状、颜色由系统给。做法：头发 / 耳朵 / 小辫 / 缎带全部实心叠在一起就是剪影；
# 后发那一片用 evenOdd 把整张脸挖掉，刘海和鬓发再实心盖回去，剩下的洞正好是露出来的脸；
# 洞里再画镜框（描边）、两只眼（带一颗高光的洞）和嘴。
MONO_SCALE = 0.62              # 剪影没有那圈白描边，放大一点才和彩色版一样显眼
_MONO_EYE = ell(197, 315, 22, 27) + " " + ell(187, 301, 7.5)
MONO_FILLS = (
    [(HAIR_BACK + " " + FACE, True)]
    + [(d, False) for d in (TUFT_L, mirror(TUFT_L), BOW_UP_L, mirror(BOW_UP_L), BOW_DN_L, mirror(BOW_DN_L),
                            STREAM_L, mirror(STREAM_L))]
    + [(EAR_L + " " + EAR_IN_L, True), (mirror(EAR_L) + " " + mirror(EAR_IN_L), True)]
    + [(d, False) for d in (EAR_FLUFF_L, mirror(EAR_FLUFF_L), SIDE_L, mirror(SIDE_L), TUFT_MID, CAP, AHOGE)]
    + [(_MONO_EYE, True), (shift(_MONO_EYE, EYE_DX, 0), True)]
)
MONO_STROKES = [
    (LENS_L, 11), (mirror(LENS_L), 11),
    (BRIDGE, 9),
    ("M242 394 C245 402 253 402 256 395 C259 402 267 402 270 394", 5),
]


def svg_mono(size=1024, color="#FFFFFF", scale=0.88):
    rows = [f'  <g transform="translate(256 {256 + ICON_DY * scale}) scale({scale}) translate(-256 -256)">']
    for d, eo in MONO_FILLS:
        rows.append(f'    <path d="{d}" fill="{color}"' + (' fill-rule="evenodd"' if eo else "") + "/>")
    for d, w in MONO_STROKES:
        rows.append(f'    <path d="{d}" fill="none" stroke="{color}" stroke-width="{w}" '
                    f'stroke-linecap="round" stroke-linejoin="round"/>')
    rows.append("  </g>")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">\n'
            + "\n".join(rows) + "\n</svg>\n")


# ---------------------------------------------------------------- Android vector
def _argb(c):
    """#RRGGBB[AA] → Android 的 #AARRGGBB。"""
    return c if len(c) == 7 else "#" + c[7:] + c[1:7]


def _android_path(s, indent="    "):
    a = [f'{indent}<path']
    f = s["fill"]
    grad = f[5:] if (f and f.startswith("grad:")) else None
    if f and not grad:
        a.append(f'{indent}    android:fillColor="{f}"')
    if s["op"] != 1.0:
        if f:
            a.append(f'{indent}    android:fillAlpha="{s["op"]}"')
        if s["stroke"]:
            a.append(f'{indent}    android:strokeAlpha="{s["op"]}"')
    if s["stroke"]:
        a.append(f'{indent}    android:strokeColor="{s["stroke"]}"')
        a.append(f'{indent}    android:strokeWidth="{s["sw"]}"')
        a.append(f'{indent}    android:strokeLineCap="round"')
        a.append(f'{indent}    android:strokeLineJoin="round"')
    a.append(f'{indent}    android:pathData="{s["d"]}"')
    if not grad:
        a.append(f'{indent}    />')
        return "\n".join(a)
    a.append(f'{indent}    >')
    x1, y1, x2, y2, stops = GRAD[grad]
    a.append(f'{indent}    <aapt:attr name="android:fillColor">')
    a.append(f'{indent}        <gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
             f'android:endX="{x2}" android:endY="{y2}">')
    for o, c in stops:
        a.append(f'{indent}            <item android:offset="{o}" android:color="{_argb(c)}"/>')
    a.append(f'{indent}        </gradient>')
    a.append(f'{indent}    </aapt:attr>')
    a.append(f'{indent}</path>')
    return "\n".join(a)


def _android_group(scale):
    return (f'    <group android:pivotX="256" android:pivotY="256" android:scaleX="{scale}" android:scaleY="{scale}"\n'
            f'        android:translateY="{_n(ICON_DY * scale)}">')


def android_vector(shapes, scale=1.0, header_comment=""):
    ns = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    xmlns:aapt="http://schemas.android.com/aapt"\n'
          '    android:width="108dp" android:height="108dp"\n'
          '    android:viewportWidth="512" android:viewportHeight="512">')
    body = [_android_group(scale), "\n".join(_android_path(s, indent="        ") for s in shapes), "    </group>"]
    cm = f"<!-- {header_comment} -->\n" if header_comment else ""
    return '<?xml version="1.0" encoding="utf-8"?>\n' + cm + ns + "\n" + "\n".join(body) + "\n</vector>\n"


def android_background():
    """底层：只有渐变 + 光晕，任何遮罩形状下都成立（外圈 18dp 会被吃掉）。"""
    x1, y1, x2, y2, stops = GRAD["skyLight"]
    items = "\n".join(f'                <item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/brand/jiji_logo.py 生成，勿手改 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="512" android:viewportHeight="512">
    <path android:pathData="M0,0h512v512h-512z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}">
{items}
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h512v512h-512z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="256" android:centerY="180" android:gradientRadius="300">
                <item android:offset="0" android:color="#8CFFFFFF"/>
                <item android:offset="1" android:color="#00FFFFFF"/>
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''


def android_monochrome(scale=MONO_SCALE):
    rows = [_android_group(scale)]
    for d, eo in MONO_FILLS:
        rows.append('        <path android:fillColor="#FFFFFF"' + (' android:fillType="evenOdd"' if eo else "")
                    + f'\n            android:pathData="{d}"/>')
    for d, w in MONO_STROKES:
        rows.append(f'        <path android:strokeColor="#FFFFFF" android:strokeWidth="{w}" '
                    f'android:strokeLineCap="round" android:strokeLineJoin="round"\n'
                    f'            android:pathData="{d}"/>')
    rows.append('    </group>')
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- 由 scripts/brand/jiji_logo.py 生成，勿手改。Android 13+ 主题图标：系统只看形状，颜色会被替换 -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="512" android:viewportHeight="512">\n'
            + "\n".join(rows) + "\n</vector>\n")


# ---------------------------------------------------------------- Compose
def kotlin_art():
    def lit(s):
        return json.dumps(s, ensure_ascii=False)

    def col(c):
        return f"0x{c[7:].upper() if len(c) == 9 else 'FF'}{c[1:7].upper()}"

    rows = []
    for s in outline_shapes() + SHAPES:
        f = s["fill"]
        if f is None:
            fill = "null"
        elif f.startswith("grad:"):
            fill = f'JijiPaint.Grad({lit(f[5:])})'
        else:
            fill = f'JijiPaint.Solid({col(f)})'
        st = f'JijiPaint.Solid({col(s["stroke"])})' if s["stroke"] else "null"
        rows.append(f'        JijiShape({lit(s["part"])}, {lit(s["d"])}, {fill}, {st}, {s["sw"]}f, {s["op"]}f),')
    used = {s["fill"][5:] for s in SHAPES if s["fill"] and s["fill"].startswith("grad:")}
    grads = []
    for gid, (x1, y1, x2, y2, stops) in GRAD.items():
        if gid in used:
            st = ", ".join(f"{o}f to Color({col(c)})" for o, c in stops)
            grads.append(f'        {lit(gid)} to JijiGradient({x1}f, {y1}f, {x2}f, {y2}f, listOf({st})),')
    pivots = "\n".join(f'        {lit(k)} to Offset({x}f, {y}f),' for k, (x, y) in PIVOTS.items())
    nl = chr(10)
    return f'''package dev.scenenote.ui.brand

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

// 由 scripts/brand/jiji_logo.py 生成，勿手改。改造型请改那个脚本再重跑。
// 与 docs/品牌/ 下的 SVG、两端桌面图标、介绍页里的记记酱同源，坐标系 512×512。

/** 一块颜色：实色，或引用 [JijiArt.gradients] 里的渐变。 */
sealed interface JijiPaint {{
    data class Solid(val argb: Long) : JijiPaint
    data class Grad(val id: String) : JijiPaint
}}

/** 线性渐变，坐标与形状同在 512×512 里。 */
data class JijiGradient(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val stops: List<Pair<Float, Color>>)

/**
 * 一条形状。[part] 是动效分组名：开屏页按它挑出猫耳 / 眼睛 / 呆毛单独做变换。
 * [strokeWidth] 为 0 表示只填充。
 */
data class JijiShape(
    val part: String,
    val pathData: String,
    val fill: JijiPaint?,
    val stroke: JijiPaint?,
    val strokeWidth: Float,
    val alpha: Float,
)

object JijiArt {{
    const val VIEWPORT = 512f

    val gradients: Map<String, JijiGradient> = mapOf(
{nl.join(grads)}
    )

    /** 动效支点：耳根、两只眼心、呆毛根。 */
    val pivots: Map<String, Offset> = mapOf(
{pivots}
    )

    /** 画序即图层序：贴纸白边在最底，眼镜和呆毛在最上。 */
    val shapes: List<JijiShape> = listOf(
{nl.join(rows)}
    )
}}
'''


# ---------------------------------------------------------------- 介绍页的 <symbol>
HTML_BEGIN = "<!-- JIJI:BEGIN（由 scripts/brand/jiji_logo.py 生成，勿手改） -->"
HTML_END = "<!-- JIJI:END -->"
_HTML_VAR = {"eo": ('class="eyes" style="display:var(--eo,inline)"'), "eh": 'style="display:var(--eh,none)"',
             "ew": 'style="display:var(--ew,none)"', "mw": 'style="display:var(--mw,inline)"',
             "mo": 'style="display:var(--mo,none)"'}
_HTML_PART = {"earL": 'class="ear l"', "earR": 'class="ear r"', "tail": 'class="tail"'}


def _html_paths(shapes, indent, prefix):
    rows = []
    for s in shapes:
        f = s["fill"]
        if not f:
            fa = 'fill="none"'
        elif f.startswith("grad:"):
            fa = f'fill="url(#{prefix}{f[5:]})"'
        else:
            fa = f'fill="{f}"'
        a = [f'd="{s["d"]}"', fa]
        if s["stroke"]:
            a.append(f'stroke="{s["stroke"]}" stroke-width="{s["sw"]}" stroke-linecap="round" stroke-linejoin="round"')
        if s["op"] != 1.0:
            a.append(f'opacity="{s["op"]}"')
        rows.append(f"{indent}<path " + " ".join(a) + "/>")
    return rows


def _html_layer(shapes, indent, prefix):
    """同一变体 / 同一动效部件的相邻形状包成一组，CSS 才抓得到。"""
    rows, i = [], 0
    while i < len(shapes):
        s = shapes[i]
        key = ("var", s["var"]) if s["var"] else (("part", s["part"]) if s["part"] in _HTML_PART else None)
        j = i
        while j < len(shapes):
            t = shapes[j]
            k2 = ("var", t["var"]) if t["var"] else (("part", t["part"]) if t["part"] in _HTML_PART else None)
            if k2 != key:
                break
            j += 1
        run = shapes[i:j]
        if key is None:
            rows += _html_paths(run, indent, prefix)
        else:
            attr = _HTML_VAR[key[1]] if key[0] == "var" else _HTML_PART[key[1]]
            rows.append(f"{indent}<g {attr}>")
            k = 0
            while k < len(run):                       # 眼珠单独一层：介绍页让它跟着指针走
                if run[k]["sub"] == "iris":
                    m = k
                    while m < len(run) and run[m]["sub"] == "iris":
                        m += 1
                    rows.append(f'{indent}  <g class="iris">')
                    rows += _html_paths(run[k:m], indent + "    ", prefix)
                    rows.append(f"{indent}  </g>")
                    k = m
                else:
                    rows += _html_paths([run[k]], indent + "  ", prefix)
                    k += 1
            rows.append(f"{indent}</g>")
        i = j
    return rows


def html_symbol(prefix="jj-", indent="    "):
    used = set()
    layers = fullbody_layers(variants=ALL_VARS, sticker=True)
    for sh, _ in layers:
        for x in sh:
            if x["fill"] and x["fill"].startswith("grad:"):
                used.add(x["fill"][5:])
    rows = [indent + HTML_BEGIN]
    for gid, (x1, y1, x2, y2, stops) in GRAD.items():
        if gid in used:
            st = "".join(_svg_stop(o, c) for o, c in stops)
            rows.append(f'{indent}<linearGradient id="{prefix}{gid}" gradientUnits="userSpaceOnUse" '
                        f'x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{st}</linearGradient>')
    rows.append(f'{indent}<symbol id="jiji" viewBox="0 0 {BODY_W} {BODY_H}">')
    for sh, is_head in layers:
        if is_head:
            rows.append(f'{indent}  <g transform="{head_transform()}">')
            rows += _html_layer(sh, indent + "    ", prefix)
            rows.append(f"{indent}  </g>")
        else:
            rows += _html_layer(sh, indent + "  ", prefix)
    rows.append(f'{indent}  <text x="548" y="232" font-size="56" fill="#E8467F" font-family="sans-serif" '
                f'style="display:var(--note,inline)">♪</text>')
    rows.append(f"{indent}</symbol>")
    rows.append(indent + HTML_END)
    return "\n".join(rows)


def inject_html(rel="docs/介绍页/index.html"):
    p = os.path.join(ROOT, rel)
    with open(p, encoding="utf-8") as f:
        page = f.read()
    a, b = page.find(HTML_BEGIN), page.find(HTML_END)
    if a < 0 or b < 0:
        print("  ✗", rel, "里没有 JIJI:BEGIN / JIJI:END 标记，跳过")
        return False
    a = page.rfind("\n", 0, a) + 1
    page = page[:a] + html_symbol() + page[b + len(HTML_END):]
    with open(p, "w", encoding="utf-8") as f:
        f.write(page)
    print("  ✓", rel, "（<symbol id=\"jiji\">）")
    return True


# ---------------------------------------------------------------- 落盘
def write(rel, text):
    p = rel if os.path.isabs(rel) else os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(text)
    print("  ✓", rel)


def rasterize(svg_rel, png_rel, px):
    src = svg_rel if os.path.isabs(svg_rel) else os.path.join(ROOT, svg_rel)
    dst = png_rel if os.path.isabs(png_rel) else os.path.join(ROOT, png_rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    r = subprocess.run(["npx", "-y", "@resvg/resvg-js-cli", "--fit-width", str(px), src, dst],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("  ✗ 光栅化失败", png_rel, r.stderr[-400:])
        return False
    print("  ✓", png_rel, f"({px}px)")
    return True


AI = "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"

# 两端系统开屏的纯色底 = App 的页面底色（core/designsystem/Palette.kt 的 Light / DarkGroupedBackground）。
# 开屏不另起一套品牌底色：系统开屏 → Compose 开屏 → 首页，三段同一支色，中间没有换底的那一下。
# 粉紫渐变只留给桌面图标。Android 那份在 res/values{,-night}/colors.xml 里手写，下面会核对是否一致。
SPLASH_BG = {"light": "#F2F2F7", "dark": "#000000"}


def check_android_splash_color():
    for mode, rel in (("light", "androidApp/src/main/res/values/colors.xml"),
                      ("dark", "androidApp/src/main/res/values-night/colors.xml")):
        try:
            with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
                m = re.search(r'name="brand_splash">\s*(#[0-9A-Fa-f]{6,8})', f.read())
        except OSError:
            m = None
        got = m.group(1).upper() if m else None
        print("  ✓" if got == SPLASH_BG[mode] else "  ✗", rel, f"brand_splash = {got}（应为 {SPLASH_BG[mode]}）")

# Android adaptive icon 的前景缩放：把角色收进 72dp 安全区（外圈 18dp 归各家遮罩）
ANDROID_SCALE = 0.57


def svg_android_preview():
    """把 background + foreground 两层按系统的叠法拼出来，用来核对安全区。"""
    return svg_icon("light", size=432, decor=False, char_scale=ANDROID_SCALE)


def android_mask_preview(png_rel):
    """按各家遮罩裁一遍：圆 / squircle / 圆角方 / 方，看角色有没有被切到。"""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("  · 跳过遮罩预览（没有 Pillow）")
        return
    src = Image.open(os.path.join(ROOT, png_rel)).convert("RGBA")
    n = src.width
    keep = int(n * 72 / 108)                       # 遮罩只露出中心 72dp
    off = (n - keep) // 2
    core = src.crop((off, off, off + keep, off + keep))
    shapes = {"圆": keep // 2, "squircle": int(keep * 0.28), "圆角方": int(keep * 0.18), "方": 0}
    out = Image.new("RGB", (keep * len(shapes) + 16 * (len(shapes) + 1), keep + 32), "#E8E3F2")
    for i, r in enumerate(shapes.values()):
        m = Image.new("L", (keep, keep), 0)
        d = ImageDraw.Draw(m)
        if r <= 0:
            d.rectangle((0, 0, keep - 1, keep - 1), fill=255)
        else:
            d.rounded_rectangle((0, 0, keep - 1, keep - 1), radius=r, fill=255)
        tile = Image.new("RGB", (keep, keep), "#E8E3F2")
        tile.paste(core.convert("RGB"), (0, 0), m)
        out.paste(tile, (16 + i * (keep + 16), 16))
    out.save(os.path.join(ROOT, "docs/品牌/预览-Android遮罩.png"))
    print("  ✓ docs/品牌/预览-Android遮罩.png")


def draft(out_dir):
    out_dir = os.path.abspath(out_dir)
    j = lambda n: os.path.join(out_dir, n)
    write(j("mark.svg"), svg_mark(bg="#F3EAF7"))
    rasterize(j("mark.svg"), j("mark.png"), 900)
    write(j("icon.svg"), svg_icon("light"))
    rasterize(j("icon.svg"), j("icon.png"), 512)
    write(j("full.svg"), svg_fullbody(bg="#F3EAF7"))
    rasterize(j("full.svg"), j("full.png"), 800)
    write(j("mono.svg"), svg_mono(color="#2B2440"))
    rasterize(j("mono.svg"), j("mono.png"), 512)
    write(j("tinted.svg"), svg_tinted())
    rasterize(j("tinted.svg"), j("tinted.png"), 512)
    write(j("dark.svg"), svg_icon("dark"))
    rasterize(j("dark.svg"), j("dark.png"), 512)


def main():
    if "--draft" in sys.argv:
        draft(sys.argv[sys.argv.index("--draft") + 1])
        return
    preview_only = "--preview" in sys.argv
    print("品牌资产 · docs/品牌/")
    write("docs/品牌/记记酱.svg", svg_mark())
    write("docs/品牌/记记酱-全身.svg", svg_fullbody())
    write("docs/品牌/应用图标-浅色.svg", svg_icon("light"))
    write("docs/品牌/应用图标-深色.svg", svg_icon("dark"))
    write("docs/品牌/应用图标-单色.svg", svg_mono(color="#2B2440"))
    if preview_only:
        rasterize("docs/品牌/应用图标-浅色.svg", "docs/品牌/预览-图标.png", 512)
        rasterize("docs/品牌/记记酱-全身.svg", "docs/品牌/预览-全身.png", 640)
        return

    print("Android · adaptive icon")
    write("androidApp/src/main/res/drawable/ic_launcher_background.xml", android_background())
    write("androidApp/src/main/res/drawable/ic_launcher_foreground.xml",
          android_vector(outline_shapes() + SHAPES, scale=ANDROID_SCALE,
                         header_comment=f"由 scripts/brand/jiji_logo.py 生成，勿手改。{ANDROID_SCALE} 是把角色收进 72dp 安全区"))
    write("androidApp/src/main/res/drawable/ic_launcher_monochrome.xml", android_monochrome())

    print("iOS · AppIcon")
    tmp = "docs/品牌/.tmp"
    write(f"{tmp}/icon-light.svg", svg_icon("light"))
    write(f"{tmp}/icon-dark.svg", svg_icon("dark", decor=True))
    write(f"{tmp}/icon-tinted.svg", svg_tinted())
    ok = True
    ok &= rasterize(f"{tmp}/icon-light.svg", f"{AI}/AppIcon-Light.png", 1024)
    ok &= rasterize(f"{tmp}/icon-dark.svg", f"{AI}/AppIcon-Dark.png", 1024)
    ok &= rasterize(f"{tmp}/icon-tinted.svg", f"{AI}/AppIcon-Tinted.png", 1024)
    write(f"{AI}/Contents.json", json.dumps({
        "images": [
            {"filename": "AppIcon-Light.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
            {"appearances": [{"appearance": "luminosity", "value": "dark"}],
             "filename": "AppIcon-Dark.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
            {"appearances": [{"appearance": "luminosity", "value": "tinted"}],
             "filename": "AppIcon-Tinted.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
        ],
        "info": {"author": "xcode", "version": 1},
    }, indent=2, ensure_ascii=False) + "\n")
    # 开屏底色：iOS 的 UILaunchScreen 只认 Assets 里的颜色，和 Android 的 brand_splash 同一支（都等于 App 的页面底色）
    def _c(hex_):
        return {"color-space": "srgb", "components": {
            "alpha": "1.000", "red": f"0x{hex_[1:3]}", "green": f"0x{hex_[3:5]}", "blue": f"0x{hex_[5:7]}"}}
    light, dark = SPLASH_BG["light"], SPLASH_BG["dark"]
    write("iosApp/iosApp/Assets.xcassets/LaunchBackground.colorset/Contents.json", json.dumps({
        "colors": [
            {"color": _c(light), "idiom": "universal"},
            {"appearances": [{"appearance": "luminosity", "value": "dark"}], "color": _c(dark), "idiom": "universal"},
        ],
        "info": {"author": "xcode", "version": 1},
    }, indent=2) + "\n")

    check_android_splash_color()

    print("Compose · 开屏动效")
    write("shared/src/commonMain/kotlin/dev/scenenote/ui/brand/JijiArt.kt", kotlin_art())

    print("介绍页 · 全身版")
    inject_html()

    print("预览图")
    rasterize("docs/品牌/应用图标-浅色.svg", "docs/品牌/预览-图标.png", 512)
    rasterize("docs/品牌/记记酱-全身.svg", "docs/品牌/预览-全身.png", 640)
    write(f"{tmp}/android.svg", svg_android_preview())
    if rasterize(f"{tmp}/android.svg", "docs/品牌/.tmp/android.png", 432):
        android_mask_preview("docs/品牌/.tmp/android.png")
    import shutil
    shutil.rmtree(os.path.join(ROOT, tmp), ignore_errors=True)
    print("完成" if ok else "完成（有光栅化失败，见上）")


if __name__ == "__main__":
    main()
