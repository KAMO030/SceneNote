# -*- coding: utf-8 -*-
"""
记记酱（JIJI）logo 的造型真源。

一份 path 数据，生成四端资产，改造型只改这个文件：
  python3 scripts/brand/jiji_logo.py            # 全量重新生成
  python3 scripts/brand/jiji_logo.py --preview  # 只出 docs/品牌/ 下的 SVG 与预览 PNG

产物：
  docs/品牌/记记酱.svg / 应用图标-浅色.svg / 应用图标-深色.svg / 应用图标-单色.svg
  androidApp/.../drawable/ic_launcher_{background,foreground,monochrome}.xml
  iosApp/.../AppIcon.appiconset/*.png（需要 npx @resvg/resvg-js-cli 光栅化）
  shared/.../ui/brand/JijiArt.kt（开屏动效用的同一份 path）

坐标系：viewBox 512×512，角色朝正面，bbox 约 x[80,432] y[14,500]。
"""
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ---------------------------------------------------------------- 配色
C = {
    "ink":      "#96628A",   # 角色描边：参考设定图是淡粉紫的细线，不是深色轮廓
    "inkSoft":  "#D79FAC",   # 脸 / 皮肤更淡一档
    "inkGlass": "#17171C",   # 镜框是黑的
    "lilac":    "#C9B0DE",   # 发色暗部 / 内层
    "blue":     "#AFD6EE",   # 蓝色挑染
    "earIn":    "#FFF4EC",   # 内耳绒毛
    "earMid":   "#F6D9E4",
    "blush":    "#F79FB4",
    "mouth":    "#C9607A",
    "nose":     "#E0A898",
    "lash":     "#3A2B3F",   # 眼线
    "lashSoft": "#7A5C72",   # 下睫毛
    "white":    "#FFFFFF",
    "clip":     "#EDE8F6",   # 发夹 / 星形装饰
    "clipInk":  "#A090C0",
    "paw":      "#F58CAE",   # 左镜片上的猫爪肉球
    "pawInk":   "#B05878",
    "ahoge":    "#F2AEC4",
    "pupilL":   "#5A2A08",
    "pupilR":   "#241A5C",
}
# 渐变都用绝对坐标（userSpaceOnUse），SVG / Android / Compose 三端同一套数值
GRAD = {
    "hairBack":  (256,  90, 256, 470, [(0.0, "#EDA2BC"), (0.60, "#E08FAE"), (1.0, "#C098D6")]),
    "hairFront": (256,  90, 256, 320, [(0.0, "#FDD4E0"), (0.55, "#F6BACE"), (1.0, "#E3B4D6")]),
    "skin":      (256, 196, 256, 442, [(0.0, "#FFF9F5"), (1.0, "#FDEADF")]),
    "irisL":     (256, 298, 256, 344, [(0.0, "#7A3A0C"), (0.45, "#F5A623"), (1.0, "#FFE9A8")]),
    "irisR":     (256, 298, 256, 344, [(0.0, "#332477"), (0.45, "#7B6BD9"), (1.0, "#CFC8F5")]),
    "skyLight":  (120,   0, 392, 512, [(0.0, "#FFE7F3"), (0.5, "#FBB3DA"), (1.0, "#C3A4F0")]),
    "skyDark":   (120,   0, 392, 512, [(0.0, "#4A2D63"), (0.55, "#33204C"), (1.0, "#1E1436")]),
}


def S(part, d, fill=None, stroke=None, sw=0.0, op=1.0):
    """一条形状。part 用于开屏动效分组（同名的一起做变换）。"""
    return dict(part=part, d=d, fill=fill, stroke=stroke, sw=sw, op=op)


INK = C["ink"]
# ---------------------------------------------------------------- 造型
SHAPES = [
    # 后发：蓬松短波波头，最宽处在耳下，发梢收到下巴以下
    S("hairBack", "M52 340 C44 194 134 80 256 80 C378 80 468 194 460 340 "
                  "C464 398 454 442 438 468 C426 488 396 484 390 462 "
                  "C382 412 384 374 374 346 L138 346 "
                  "C128 374 130 412 122 462 C116 484 86 488 74 468 "
                  "C58 442 48 398 52 340 Z", "grad:hairBack", INK, 6),
    # 猫耳：外耳 → 内耳 → 绒毛，三层，底边藏在头发里
    S("earL", "M104 218 C76 136 88 60 118 34 C176 66 224 130 244 208 Z", "grad:hairFront", INK, 6),
    S("earL", "M124 196 C100 130 108 76 130 56 C174 86 208 134 222 190 Z", C["earMid"], C["ink"], 3),
    S("earL", "M142 176 C124 126 130 92 146 76 C178 102 200 138 208 170 Z", C["earIn"]),
    S("earR", "M408 218 C436 136 424 60 394 34 C336 66 288 130 268 208 Z", "grad:hairFront", INK, 6),
    S("earR", "M388 196 C412 130 404 76 382 56 C338 86 304 134 290 190 Z", C["earMid"], C["ink"], 3),
    S("earR", "M370 176 C388 126 382 92 366 76 C334 102 312 138 304 170 Z", C["earIn"]),
    # 脸：圆润，下巴小而圆
    S("face", "M256 196 C320 196 348 238 350 302 C352 344 346 378 330 404 "
              "C316 426 288 442 256 442 C224 442 196 426 182 404 "
              "C166 378 160 344 162 302 C164 238 192 196 256 196 Z", "grad:skin", C["inkSoft"], 5),
    # 腮红
    S("blush", "M186 358 m-25 0 a25 11 0 1 0 50 0 a25 11 0 1 0 -50 0 Z", C["blush"], op=.45),
    S("blush", "M326 358 m-25 0 a25 11 0 1 0 50 0 a25 11 0 1 0 -50 0 Z", C["blush"], op=.45),
    # 眼（左·橙金）：杏仁形，虹膜几乎填满，上眼线粗、外眼角挑出去
    S("eyeL", "M153 318 C158 300 172 292 194 294 C216 296 230 304 235 320 "
              "C230 338 214 346 194 345 C172 344 156 332 153 318 Z", C["white"]),
    S("eyeL", "M195 320 m-26 0 a26 24 0 1 0 52 0 a26 24 0 1 0 -52 0 Z", "grad:irisL"),
    S("eyeL", "M195 322 m-10.5 0 a10.5 11.5 0 1 0 21 0 a10.5 11.5 0 1 0 -21 0 Z", C["pupilL"]),
    S("eyeL", "M195 333 m-14 0 a14 6.5 0 1 0 28 0 a14 6.5 0 1 0 -28 0 Z", C["white"], op=.45),
    S("eyeL", "M184 308 m-7 0 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0 Z", C["white"]),
    S("eyeL", "M206 332 m-3.5 0 a3.5 3.5 0 1 0 7 0 a3.5 3.5 0 1 0 -7 0 Z", C["white"]),
    S("lashL", "M149 314 C154 294 172 284 196 286 C220 288 232 298 238 316", None, C["lash"], 10),
    S("lashL", "M149 314 L135 302", None, C["lash"], 8),
    S("lashL", "M168 336 C182 345 208 346 224 336", None, C["lashSoft"], 3),
    # 眼（右·蓝紫）
    S("eyeR", "M359 318 C354 300 340 292 318 294 C296 296 282 304 277 320 "
              "C282 338 298 346 318 345 C340 344 356 332 359 318 Z", C["white"]),
    S("eyeR", "M317 320 m-26 0 a26 24 0 1 0 52 0 a26 24 0 1 0 -52 0 Z", "grad:irisR"),
    S("eyeR", "M317 322 m-10.5 0 a10.5 11.5 0 1 0 21 0 a10.5 11.5 0 1 0 -21 0 Z", C["pupilR"]),
    S("eyeR", "M317 333 m-14 0 a14 6.5 0 1 0 28 0 a14 6.5 0 1 0 -28 0 Z", C["white"], op=.45),
    S("eyeR", "M328 308 m-7 0 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0 Z", C["white"]),
    S("eyeR", "M306 332 m-3.5 0 a3.5 3.5 0 1 0 7 0 a3.5 3.5 0 1 0 -7 0 Z", C["white"]),
    S("lashR", "M363 314 C358 294 340 284 316 286 C292 288 280 298 274 316", None, C["lash"], 10),
    S("lashR", "M363 314 L377 302", None, C["lash"], 8),
    S("lashR", "M344 336 C330 345 304 346 288 336", None, C["lashSoft"], 3),
    # 鼻 / 嘴：参考图里几乎只是两笔
    S("mouth", "M252 378 l5 4", None, C["nose"], 4),
    S("mouth", "M245 400 C251 408 261 408 267 400", None, C["mouth"], 5),
    # 侧发（前片）：先画，让刘海压住它的上端，过渡才连得上
    S("sideL", "M56 318 C44 368 46 418 58 454 C64 470 82 470 90 454 C98 412 102 364 122 324 Z",
      "grad:hairBack", INK, 6),
    S("sideR", "M456 318 C468 368 466 418 454 454 C448 470 430 470 422 454 C414 412 410 364 390 324 Z",
      "grad:hairBack", INK, 6),
    # 刘海：下缘是平缓的波浪（层次靠里面的发丝线，不靠外缘锯齿），分缝在中间偏右
    S("bangs", "M56 348 C48 188 134 76 256 76 C378 76 464 188 456 348 "
               "C448 330 438 316 430 308 C422 302 414 296 404 292 "
               "C396 284 386 278 378 276 C370 280 358 288 350 296 "
               "C340 288 326 278 318 272 C310 278 302 284 296 290 "
               "C288 282 280 268 272 262 C264 270 254 282 246 288 "
               "C238 282 224 274 216 270 C206 278 194 288 186 296 "
               "C176 290 162 280 152 276 C142 284 132 294 124 300 "
               "C112 304 100 310 92 314 C78 324 66 336 56 348 Z",
      "grad:hairFront", INK, 6),
    # 发丝：刘海里的分缕线，头发的层次感全靠这几笔
    S("bangs", "M150 124 C130 184 124 250 134 302", None, C["lilac"], 4, .65),
    S("bangs", "M202 100 C188 172 184 240 194 298", None, C["lilac"], 4, .6),
    S("bangs", "M258 90 C252 158 252 218 262 254", None, C["lilac"], 4, .55),
    S("bangs", "M312 98 C322 168 324 236 314 292", None, C["lilac"], 4, .6),
    S("bangs", "M362 122 C378 182 382 248 372 300", None, C["lilac"], 4, .65),
    # 发丝暗部（贴着两侧，压出体积）
    S("bangs", "M96 150 C80 196 74 248 82 308 L104 296 C96 244 102 192 116 148 Z", C["lilac"], op=.4),
    S("bangs", "M416 150 C432 196 438 248 430 308 L408 296 C416 244 410 192 396 148 Z", C["lilac"], op=.4),
    # 蓝色挑染：跟着发丝走向的三缕细带
    S("bangs", "M186 104 C174 164 172 226 184 292", None, C["blue"], 11, .85),
    S("bangs", "M296 106 C308 166 310 224 300 284", None, C["blue"], 9, .7),
    S("bangs", "M136 140 C124 190 122 240 130 288", None, C["blue"], 7, .55),
    # 侧发上的高光线
    S("sideL", "M70 352 C62 388 62 422 70 448", None, C["lilac"], 7),
    S("sideR", "M442 352 C450 388 450 422 442 448", None, C["lilac"], 7),
    # 发饰：左边两根细发夹，右边一颗星
    S("clip", "M118 252 L166 240", None, C["clip"], 9),
    S("clip", "M118 252 L166 240", None, C["clipInk"], 2),
    S("clip", "M122 270 L168 258", None, C["clip"], 9),
    S("clip", "M122 270 L168 258", None, C["clipInk"], 2),
    S("clip", "M374 244 C378 252 382 256 390 260 C382 264 378 268 374 276 "
              "C370 268 366 264 358 260 C366 256 370 252 374 244 Z", C["clip"], C["clipInk"], 2),
    # 圆框眼镜：比脸略宽，压在刘海下沿
    S("glass", "M194 321 m-56 0 a56 56 0 1 0 112 0 a56 56 0 1 0 -112 0 Z", None, C["inkGlass"], 9),
    S("glass", "M318 321 m-56 0 a56 56 0 1 0 112 0 a56 56 0 1 0 -112 0 Z", None, C["inkGlass"], 9),
    S("glass", "M250 312 C253 306 259 306 262 312", None, C["inkGlass"], 7),
    S("glass", "M138 314 L114 306", None, C["inkGlass"], 7),
    S("glass", "M374 314 L398 306", None, C["inkGlass"], 7),
    S("glass", "M162 292 C168 280 178 273 189 271", None, C["white"], 6, .55),
    S("glass", "M286 292 C292 280 302 273 313 271", None, C["white"], 6, .55),
    # 猫爪肉球：贴在左镜片上，参考设定图里的记号
    S("paw", "M166 304 m-9 0 a9 7.5 0 1 0 18 0 a9 7.5 0 1 0 -18 0 Z", C["paw"], C["pawInk"], 2),
    S("paw", "M155 291 m-3.6 0 a3.6 3.6 0 1 0 7.2 0 a3.6 3.6 0 1 0 -7.2 0 Z", C["paw"], C["pawInk"], 1.6),
    S("paw", "M164 286 m-3.6 0 a3.6 3.6 0 1 0 7.2 0 a3.6 3.6 0 1 0 -7.2 0 Z", C["paw"], C["pawInk"], 1.6),
    S("paw", "M173 288 m-3.6 0 a3.6 3.6 0 1 0 7.2 0 a3.6 3.6 0 1 0 -7.2 0 Z", C["paw"], C["pawInk"], 1.6),
    S("paw", "M181 295 m-3.2 0 a3.2 3.2 0 1 0 6.4 0 a3.2 3.2 0 1 0 -6.4 0 Z", C["paw"], C["pawInk"], 1.6),
    # 呆毛
    S("ahoge", "M262 80 C256 42 278 22 298 34 C286 42 280 60 284 76", None, INK, 13),
    S("ahoge", "M262 80 C256 42 278 22 298 34 C286 42 280 60 284 76", None, C["ahoge"], 7),
]

# 贴纸白描边：这些部件在最底层用白色粗描边再画一遍，角色就能从任何底色上跳出来
OUTLINE_PARTS = ("hairBack", "earL", "earR", "face", "sideL", "sideR", "ahoge")
OUTLINE_W = 26.0

# 闭合填充形（描白边时要连 fill 一起，避免中间露出底色）；其余是线条，只加粗描边
def _is_closed(s):
    return s["fill"] is not None


def outline_shapes():
    out = []
    for s in SHAPES:
        if s["part"] not in OUTLINE_PARTS:
            continue
        if _is_closed(s):
            out.append(S(s["part"], s["d"], C["white"], C["white"], (s["sw"] or 0) + OUTLINE_W))
        elif s["stroke"] and s["sw"] >= 10:      # 呆毛这类粗线条
            out.append(S(s["part"], s["d"], None, C["white"], s["sw"] + OUTLINE_W))
    return out


# 图标背景上的点缀（只在不裁切的 iOS 图标里用；Android 外圈会被遮罩吃掉）
def _star(cx, cy, r):
    k = r * 0.28
    return (f"M{cx} {cy - r} C{cx + k} {cy - k} {cx + k} {cy - k} {cx + r} {cy} "
            f"C{cx + k} {cy + k} {cx + k} {cy + k} {cx} {cy + r} "
            f"C{cx - k} {cy + k} {cx - k} {cy + k} {cx - r} {cy} "
            f"C{cx - k} {cy - k} {cx - k} {cy - k} {cx} {cy - r} Z")


def _petal(cx, cy, s, rot):
    import math
    pts = [(0, 0), (-8, -14), (-4, -26), (0, -30), (4, -26), (8, -14), (0, 0)]
    a = math.radians(rot)
    out = []
    for x, y in pts:
        out.append((cx + (x * math.cos(a) - y * math.sin(a)) * s, cy + (x * math.sin(a) + y * math.cos(a)) * s))
    return (f"M{out[0][0]:.1f} {out[0][1]:.1f} C{out[1][0]:.1f} {out[1][1]:.1f} {out[2][0]:.1f} {out[2][1]:.1f} "
            f"{out[3][0]:.1f} {out[3][1]:.1f} C{out[4][0]:.1f} {out[4][1]:.1f} {out[5][0]:.1f} {out[5][1]:.1f} "
            f"{out[6][0]:.1f} {out[6][1]:.1f} Z")


DECOR = [
    S("decor", _star(74, 116, 23), C["white"], op=.75),
    S("decor", _star(444, 152, 16), C["white"], op=.6),
    S("decor", _star(86, 418, 14), C["white"], op=.55),
    S("decor", _star(438, 386, 20), C["white"], op=.65),
    S("decor", _petal(452, 66, 1.0, 24), "#FFFFFF", op=.5),
    S("decor", _petal(62, 250, 0.85, -160), "#FFFFFF", op=.42),
]

# ---------------------------------------------------------------- SVG
def _fill_attr(f):
    if not f:
        return 'fill="none"'
    return f'fill="url(#{f[5:]})"' if f.startswith("grad:") else f'fill="{f}"'


def svg_defs(ids=None):
    rows = ["  <defs>"]
    for gid, (x1, y1, x2, y2, stops) in GRAD.items():
        if ids and gid not in ids:
            continue
        st = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
        rows.append(f'    <linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
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


def svg_mark(size=512):
    """透明底的角色 mark。"""
    body = svg_paths(outline_shapes() + SHAPES)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">\n'
            f'{svg_defs()}\n{body}\n</svg>\n')


def svg_icon(kind="light", size=1024, decor=True, char_scale=0.88):
    """应用图标：底 + 光晕 + 点缀 + 角色。kind=light|dark|transparent。"""
    bg = {"light": "skyLight", "dark": "skyDark"}.get(kind)
    rows = []
    if bg:
        rows.append(f'  <rect width="512" height="512" fill="url(#{bg})"/>')
        rows.append('  <rect width="512" height="512" fill="url(#glow)"/>')
    if decor:
        rows.append(svg_paths(DECOR))
    rows.append(f'  <g transform="translate(256 256) scale({char_scale}) translate(-256 -256)">')
    rows.append(svg_paths(outline_shapes() + SHAPES, indent="    "))
    rows.append("  </g>")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">\n'
            f'{svg_defs()}\n' + "\n".join(rows) + "\n</svg>\n")


# ---------------------------------------------------------------- 单色（Android 主题图标 / iOS tinted）
# 系统只看形状、颜色由系统给，所以用剪影：一条闭合轮廓（猫耳直接长在头上，不自相重叠），
# 两个镜片圆靠 evenOdd 挖成洞，瞳孔再实心补回去。
MONO_SILHOUETTE = (
    "M104 216 C78 136 90 60 120 34 C172 66 212 118 238 172 "           # 左耳
    "C246 150 250 134 256 124 C262 134 266 150 274 172 "               # 头顶
    "C300 118 340 66 392 34 C422 60 434 136 408 216 "                  # 右耳
    "C446 252 462 292 460 342 C464 400 452 446 436 470 "               # 右侧 + 发梢
    "C424 488 396 484 388 464 "
    "C360 478 310 470 256 470 C202 470 152 478 124 464 "               # 底部连成整块：
    "C116 484 88 488 76 470 "                                          #  留缺口会被 evenOdd 翻出尖角
    "C60 446 48 400 52 342 C50 292 66 252 104 216 Z "
    "M146 172 C132 132 138 104 150 90 C174 112 190 140 198 166 Z "     # 左内耳（挖空）
    "M366 172 C380 132 374 104 362 90 C338 112 322 140 314 166 Z "     # 右内耳（挖空）
    "M140 321 a54 54 0 1 0 108 0 a54 54 0 1 0 -108 0 Z "               # 左镜片（挖空）
    "M264 321 a54 54 0 1 0 108 0 a54 54 0 1 0 -108 0 Z"                # 右镜片（挖空）
)
MONO_SOLID = [
    "M178 322 a16 19 0 1 0 32 0 a16 19 0 1 0 -32 0 Z",                 # 左瞳
    "M302 322 a16 19 0 1 0 32 0 a16 19 0 1 0 -32 0 Z",                 # 右瞳
]
MONO_STROKE = []   # 呆毛在剪影里会被看成第三只耳朵，单色版不画

# 剪影没有那圈白描边，bbox 比彩色版小，放大一点才和它一样显眼
MONO_SCALE = 0.62


def svg_mono(size=1024, color="#FFFFFF", scale=0.88):
    g = f'  <g transform="translate(256 256) scale({scale}) translate(-256 -256)">'
    rows = [g, f'    <path d="{MONO_SILHOUETTE}" fill="{color}" fill-rule="evenodd"/>']
    for d in MONO_SOLID:
        rows.append(f'    <path d="{d}" fill="{color}"/>')
    for d, w in MONO_STROKE:
        rows.append(f'    <path d="{d}" fill="none" stroke="{color}" stroke-width="{w}" '
                    f'stroke-linecap="round" stroke-linejoin="round"/>')
    rows.append("  </g>")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">\n'
            + "\n".join(rows) + "\n</svg>\n")


# ---------------------------------------------------------------- Android vector
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
        a.append(f'{indent}            <item android:offset="{o}" android:color="{c}"/>')
    a.append(f'{indent}        </gradient>')
    a.append(f'{indent}    </aapt:attr>')
    a.append(f'{indent}</path>')
    return "\n".join(a)


def android_vector(shapes, scale=1.0, header_comment=""):
    has_grad = any(s["fill"] and s["fill"].startswith("grad:") for s in shapes)
    ns = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          + ('    xmlns:aapt="http://schemas.android.com/aapt"\n' if has_grad else '')
          + '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="512" android:viewportHeight="512">')
    body = []
    if scale != 1.0:
        body.append(f'    <group android:pivotX="256" android:pivotY="256" '
                    f'android:scaleX="{scale}" android:scaleY="{scale}">')
        body.append("\n".join(_android_path(s, indent="        ") for s in shapes))
        body.append("    </group>")
    else:
        body.append("\n".join(_android_path(s) for s in shapes))
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
    rows = [f'    <group android:pivotX="256" android:pivotY="256" android:scaleX="{scale}" android:scaleY="{scale}">',
            f'        <path android:fillColor="#FFFFFF" android:fillType="evenOdd"\n'
            f'            android:pathData="{MONO_SILHOUETTE}"/>']
    for d in MONO_SOLID:
        rows.append(f'        <path android:fillColor="#FFFFFF"\n            android:pathData="{d}"/>')
    for d, w in MONO_STROKE:
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

    def col(hex_):
        return f"0xFF{hex_[1:].upper()}"

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
    grads = []
    for gid, (x1, y1, x2, y2, stops) in GRAD.items():
        if gid.startswith("sky"):
            continue
        st = ", ".join(f"{o}f to Color({col(c)})" for o, c in stops)
        grads.append(f'        {lit(gid)} to JijiGradient({x1}f, {y1}f, {x2}f, {y2}f, listOf({st})),')
    sky = []
    for gid in ("skyLight", "skyDark"):
        x1, y1, x2, y2, stops = GRAD[gid]
        st = ", ".join(f"{o}f to Color({col(c)})" for o, c in stops)
        sky.append(f'        {lit(gid)} to JijiGradient({x1}f, {y1}f, {x2}f, {y2}f, listOf({st})),')
    return f'''package dev.scenenote.ui.brand

import androidx.compose.ui.graphics.Color

// 由 scripts/brand/jiji_logo.py 生成，勿手改。改造型请改那个脚本再重跑。
// 与 docs/品牌/ 下的 SVG、两端桌面图标同源，坐标系 512×512。

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
{chr(10).join(grads)}
    )

    /** 图标底色（开屏页背景同源）。 */
    val sky: Map<String, JijiGradient> = mapOf(
{chr(10).join(sky)}
    )

    /** 画序即图层序：贴纸白边在最底，眼镜和呆毛在最上。 */
    val shapes: List<JijiShape> = listOf(
{chr(10).join(rows)}
    )
}}
'''


# ---------------------------------------------------------------- 落盘
def write(rel, text):
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(text)
    print("  ✓", rel)


def rasterize(svg_rel, png_rel, px):
    src = os.path.join(ROOT, svg_rel)
    dst = os.path.join(ROOT, png_rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    r = subprocess.run(["npx", "-y", "@resvg/resvg-js-cli", "--fit-width", str(px), src, dst],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("  ✗ 光栅化失败", png_rel, r.stderr[-400:])
        return False
    print("  ✓", png_rel, f"({px}px)")
    return True


AI = "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"

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
    dst = os.path.join(ROOT, "docs/品牌/预览-Android遮罩.png")
    out.save(dst)
    print("  ✓ docs/品牌/预览-Android遮罩.png")


def main():
    preview_only = "--preview" in sys.argv
    print("品牌资产 · docs/品牌/")
    write("docs/品牌/记记酱.svg", svg_mark())
    write("docs/品牌/应用图标-浅色.svg", svg_icon("light"))
    write("docs/品牌/应用图标-深色.svg", svg_icon("dark"))
    write("docs/品牌/应用图标-单色.svg", svg_mono(color="#2B2440"))
    if preview_only:
        rasterize("docs/品牌/应用图标-浅色.svg", "docs/品牌/预览-图标.png", 512)
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
    write(f"{tmp}/icon-tinted.svg", svg_mono(color="#FFFFFF"))
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
    # 开屏底色：iOS 的 UILaunchScreen 只认 Assets 里的颜色，和 Android 的 brand_splash 同一支
    def _c(hex_):
        return {"color-space": "srgb", "components": {
            "alpha": "1.000", "red": f"0x{hex_[1:3]}", "green": f"0x{hex_[3:5]}", "blue": f"0x{hex_[5:7]}"}}
    light = GRAD["skyLight"][4][1][1]
    dark = GRAD["skyDark"][4][1][1]
    write("iosApp/iosApp/Assets.xcassets/LaunchBackground.colorset/Contents.json", json.dumps({
        "colors": [
            {"color": _c(light), "idiom": "universal"},
            {"appearances": [{"appearance": "luminosity", "value": "dark"}], "color": _c(dark), "idiom": "universal"},
        ],
        "info": {"author": "xcode", "version": 1},
    }, indent=2) + "\n")

    print("Compose · 开屏动效")
    write("shared/src/commonMain/kotlin/dev/scenenote/ui/brand/JijiArt.kt", kotlin_art())

    print("预览图")
    rasterize("docs/品牌/应用图标-浅色.svg", "docs/品牌/预览-图标.png", 512)
    write(f"{tmp}/android.svg", svg_android_preview())
    if rasterize(f"{tmp}/android.svg", "docs/品牌/.tmp/android.png", 432):
        android_mask_preview("docs/品牌/.tmp/android.png")
    import shutil
    shutil.rmtree(os.path.join(ROOT, tmp), ignore_errors=True)
    print("完成" if ok else "完成（有光栅化失败，见上）")


if __name__ == "__main__":
    main()
