package dev.scenenote.ui.brand

import androidx.compose.ui.graphics.Color

// 由 scripts/brand/jiji_logo.py 生成，勿手改。改造型请改那个脚本再重跑。
// 与 docs/品牌/ 下的 SVG、两端桌面图标同源，坐标系 512×512。

/** 一块颜色：实色，或引用 [JijiArt.gradients] 里的渐变。 */
sealed interface JijiPaint {
    data class Solid(val argb: Long) : JijiPaint
    data class Grad(val id: String) : JijiPaint
}

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

object JijiArt {
    const val VIEWPORT = 512f

    val gradients: Map<String, JijiGradient> = mapOf(
        "hairBack" to JijiGradient(256f, 90f, 256f, 470f, listOf(0.0f to Color(0xFFEDA2BC), 0.6f to Color(0xFFE08FAE), 1.0f to Color(0xFFC098D6))),
        "hairFront" to JijiGradient(256f, 90f, 256f, 320f, listOf(0.0f to Color(0xFFFDD4E0), 0.55f to Color(0xFFF6BACE), 1.0f to Color(0xFFE3B4D6))),
        "skin" to JijiGradient(256f, 196f, 256f, 442f, listOf(0.0f to Color(0xFFFFF9F5), 1.0f to Color(0xFFFDEADF))),
        "irisL" to JijiGradient(256f, 298f, 256f, 344f, listOf(0.0f to Color(0xFF7A3A0C), 0.45f to Color(0xFFF5A623), 1.0f to Color(0xFFFFE9A8))),
        "irisR" to JijiGradient(256f, 298f, 256f, 344f, listOf(0.0f to Color(0xFF332477), 0.45f to Color(0xFF7B6BD9), 1.0f to Color(0xFFCFC8F5))),
    )

    /** 图标底色（开屏页背景同源）。 */
    val sky: Map<String, JijiGradient> = mapOf(
        "skyLight" to JijiGradient(120f, 0f, 392f, 512f, listOf(0.0f to Color(0xFFFFE7F3), 0.5f to Color(0xFFFBB3DA), 1.0f to Color(0xFFC3A4F0))),
        "skyDark" to JijiGradient(120f, 0f, 392f, 512f, listOf(0.0f to Color(0xFF4A2D63), 0.55f to Color(0xFF33204C), 1.0f to Color(0xFF1E1436))),
    )

    /** 画序即图层序：贴纸白边在最底，眼镜和呆毛在最上。 */
    val shapes: List<JijiShape> = listOf(
        JijiShape("hairBack", "M52 340 C44 194 134 80 256 80 C378 80 468 194 460 340 C464 398 454 442 438 468 C426 488 396 484 390 462 C382 412 384 374 374 346 L138 346 C128 374 130 412 122 462 C116 484 86 488 74 468 C58 442 48 398 52 340 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 32.0f, 1.0f),
        JijiShape("earL", "M104 218 C76 136 88 60 118 34 C176 66 224 130 244 208 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 32.0f, 1.0f),
        JijiShape("earL", "M124 196 C100 130 108 76 130 56 C174 86 208 134 222 190 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 29.0f, 1.0f),
        JijiShape("earL", "M142 176 C124 126 130 92 146 76 C178 102 200 138 208 170 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 26.0f, 1.0f),
        JijiShape("earR", "M408 218 C436 136 424 60 394 34 C336 66 288 130 268 208 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 32.0f, 1.0f),
        JijiShape("earR", "M388 196 C412 130 404 76 382 56 C338 86 304 134 290 190 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 29.0f, 1.0f),
        JijiShape("earR", "M370 176 C388 126 382 92 366 76 C334 102 312 138 304 170 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 26.0f, 1.0f),
        JijiShape("face", "M256 196 C320 196 348 238 350 302 C352 344 346 378 330 404 C316 426 288 442 256 442 C224 442 196 426 182 404 C166 378 160 344 162 302 C164 238 192 196 256 196 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 31.0f, 1.0f),
        JijiShape("sideL", "M56 318 C44 368 46 418 58 454 C64 470 82 470 90 454 C98 412 102 364 122 324 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 32.0f, 1.0f),
        JijiShape("sideR", "M456 318 C468 368 466 418 454 454 C448 470 430 470 422 454 C414 412 410 364 390 324 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 32.0f, 1.0f),
        JijiShape("ahoge", "M262 80 C256 42 278 22 298 34 C286 42 280 60 284 76", null, JijiPaint.Solid(0xFFFFFFFF), 39.0f, 1.0f),
        JijiShape("hairBack", "M52 340 C44 194 134 80 256 80 C378 80 468 194 460 340 C464 398 454 442 438 468 C426 488 396 484 390 462 C382 412 384 374 374 346 L138 346 C128 374 130 412 122 462 C116 484 86 488 74 468 C58 442 48 398 52 340 Z", JijiPaint.Grad("hairBack"), JijiPaint.Solid(0xFF96628A), 6f, 1.0f),
        JijiShape("earL", "M104 218 C76 136 88 60 118 34 C176 66 224 130 244 208 Z", JijiPaint.Grad("hairFront"), JijiPaint.Solid(0xFF96628A), 6f, 1.0f),
        JijiShape("earL", "M124 196 C100 130 108 76 130 56 C174 86 208 134 222 190 Z", JijiPaint.Solid(0xFFF6D9E4), JijiPaint.Solid(0xFF96628A), 3f, 1.0f),
        JijiShape("earL", "M142 176 C124 126 130 92 146 76 C178 102 200 138 208 170 Z", JijiPaint.Solid(0xFFFFF4EC), null, 0.0f, 1.0f),
        JijiShape("earR", "M408 218 C436 136 424 60 394 34 C336 66 288 130 268 208 Z", JijiPaint.Grad("hairFront"), JijiPaint.Solid(0xFF96628A), 6f, 1.0f),
        JijiShape("earR", "M388 196 C412 130 404 76 382 56 C338 86 304 134 290 190 Z", JijiPaint.Solid(0xFFF6D9E4), JijiPaint.Solid(0xFF96628A), 3f, 1.0f),
        JijiShape("earR", "M370 176 C388 126 382 92 366 76 C334 102 312 138 304 170 Z", JijiPaint.Solid(0xFFFFF4EC), null, 0.0f, 1.0f),
        JijiShape("face", "M256 196 C320 196 348 238 350 302 C352 344 346 378 330 404 C316 426 288 442 256 442 C224 442 196 426 182 404 C166 378 160 344 162 302 C164 238 192 196 256 196 Z", JijiPaint.Grad("skin"), JijiPaint.Solid(0xFFD79FAC), 5f, 1.0f),
        JijiShape("blush", "M186 358 m-25 0 a25 11 0 1 0 50 0 a25 11 0 1 0 -50 0 Z", JijiPaint.Solid(0xFFF79FB4), null, 0.0f, 0.45f),
        JijiShape("blush", "M326 358 m-25 0 a25 11 0 1 0 50 0 a25 11 0 1 0 -50 0 Z", JijiPaint.Solid(0xFFF79FB4), null, 0.0f, 0.45f),
        JijiShape("eyeL", "M153 318 C158 300 172 292 194 294 C216 296 230 304 235 320 C230 338 214 346 194 345 C172 344 156 332 153 318 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("eyeL", "M195 320 m-26 0 a26 24 0 1 0 52 0 a26 24 0 1 0 -52 0 Z", JijiPaint.Grad("irisL"), null, 0.0f, 1.0f),
        JijiShape("eyeL", "M195 322 m-10.5 0 a10.5 11.5 0 1 0 21 0 a10.5 11.5 0 1 0 -21 0 Z", JijiPaint.Solid(0xFF5A2A08), null, 0.0f, 1.0f),
        JijiShape("eyeL", "M195 333 m-14 0 a14 6.5 0 1 0 28 0 a14 6.5 0 1 0 -28 0 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 0.45f),
        JijiShape("eyeL", "M184 308 m-7 0 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("eyeL", "M206 332 m-3.5 0 a3.5 3.5 0 1 0 7 0 a3.5 3.5 0 1 0 -7 0 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("lashL", "M149 314 C154 294 172 284 196 286 C220 288 232 298 238 316", null, JijiPaint.Solid(0xFF3A2B3F), 10f, 1.0f),
        JijiShape("lashL", "M149 314 L135 302", null, JijiPaint.Solid(0xFF3A2B3F), 8f, 1.0f),
        JijiShape("lashL", "M168 336 C182 345 208 346 224 336", null, JijiPaint.Solid(0xFF7A5C72), 3f, 1.0f),
        JijiShape("eyeR", "M359 318 C354 300 340 292 318 294 C296 296 282 304 277 320 C282 338 298 346 318 345 C340 344 356 332 359 318 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("eyeR", "M317 320 m-26 0 a26 24 0 1 0 52 0 a26 24 0 1 0 -52 0 Z", JijiPaint.Grad("irisR"), null, 0.0f, 1.0f),
        JijiShape("eyeR", "M317 322 m-10.5 0 a10.5 11.5 0 1 0 21 0 a10.5 11.5 0 1 0 -21 0 Z", JijiPaint.Solid(0xFF241A5C), null, 0.0f, 1.0f),
        JijiShape("eyeR", "M317 333 m-14 0 a14 6.5 0 1 0 28 0 a14 6.5 0 1 0 -28 0 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 0.45f),
        JijiShape("eyeR", "M328 308 m-7 0 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("eyeR", "M306 332 m-3.5 0 a3.5 3.5 0 1 0 7 0 a3.5 3.5 0 1 0 -7 0 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("lashR", "M363 314 C358 294 340 284 316 286 C292 288 280 298 274 316", null, JijiPaint.Solid(0xFF3A2B3F), 10f, 1.0f),
        JijiShape("lashR", "M363 314 L377 302", null, JijiPaint.Solid(0xFF3A2B3F), 8f, 1.0f),
        JijiShape("lashR", "M344 336 C330 345 304 346 288 336", null, JijiPaint.Solid(0xFF7A5C72), 3f, 1.0f),
        JijiShape("mouth", "M252 378 l5 4", null, JijiPaint.Solid(0xFFE0A898), 4f, 1.0f),
        JijiShape("mouth", "M245 400 C251 408 261 408 267 400", null, JijiPaint.Solid(0xFFC9607A), 5f, 1.0f),
        JijiShape("sideL", "M56 318 C44 368 46 418 58 454 C64 470 82 470 90 454 C98 412 102 364 122 324 Z", JijiPaint.Grad("hairBack"), JijiPaint.Solid(0xFF96628A), 6f, 1.0f),
        JijiShape("sideR", "M456 318 C468 368 466 418 454 454 C448 470 430 470 422 454 C414 412 410 364 390 324 Z", JijiPaint.Grad("hairBack"), JijiPaint.Solid(0xFF96628A), 6f, 1.0f),
        JijiShape("bangs", "M56 348 C48 188 134 76 256 76 C378 76 464 188 456 348 C448 330 438 316 430 308 C422 302 414 296 404 292 C396 284 386 278 378 276 C370 280 358 288 350 296 C340 288 326 278 318 272 C310 278 302 284 296 290 C288 282 280 268 272 262 C264 270 254 282 246 288 C238 282 224 274 216 270 C206 278 194 288 186 296 C176 290 162 280 152 276 C142 284 132 294 124 300 C112 304 100 310 92 314 C78 324 66 336 56 348 Z", JijiPaint.Grad("hairFront"), JijiPaint.Solid(0xFF96628A), 6f, 1.0f),
        JijiShape("bangs", "M150 124 C130 184 124 250 134 302", null, JijiPaint.Solid(0xFFC9B0DE), 4f, 0.65f),
        JijiShape("bangs", "M202 100 C188 172 184 240 194 298", null, JijiPaint.Solid(0xFFC9B0DE), 4f, 0.6f),
        JijiShape("bangs", "M258 90 C252 158 252 218 262 254", null, JijiPaint.Solid(0xFFC9B0DE), 4f, 0.55f),
        JijiShape("bangs", "M312 98 C322 168 324 236 314 292", null, JijiPaint.Solid(0xFFC9B0DE), 4f, 0.6f),
        JijiShape("bangs", "M362 122 C378 182 382 248 372 300", null, JijiPaint.Solid(0xFFC9B0DE), 4f, 0.65f),
        JijiShape("bangs", "M96 150 C80 196 74 248 82 308 L104 296 C96 244 102 192 116 148 Z", JijiPaint.Solid(0xFFC9B0DE), null, 0.0f, 0.4f),
        JijiShape("bangs", "M416 150 C432 196 438 248 430 308 L408 296 C416 244 410 192 396 148 Z", JijiPaint.Solid(0xFFC9B0DE), null, 0.0f, 0.4f),
        JijiShape("bangs", "M186 104 C174 164 172 226 184 292", null, JijiPaint.Solid(0xFFAFD6EE), 11f, 0.85f),
        JijiShape("bangs", "M296 106 C308 166 310 224 300 284", null, JijiPaint.Solid(0xFFAFD6EE), 9f, 0.7f),
        JijiShape("bangs", "M136 140 C124 190 122 240 130 288", null, JijiPaint.Solid(0xFFAFD6EE), 7f, 0.55f),
        JijiShape("sideL", "M70 352 C62 388 62 422 70 448", null, JijiPaint.Solid(0xFFC9B0DE), 7f, 1.0f),
        JijiShape("sideR", "M442 352 C450 388 450 422 442 448", null, JijiPaint.Solid(0xFFC9B0DE), 7f, 1.0f),
        JijiShape("clip", "M118 252 L166 240", null, JijiPaint.Solid(0xFFEDE8F6), 9f, 1.0f),
        JijiShape("clip", "M118 252 L166 240", null, JijiPaint.Solid(0xFFA090C0), 2f, 1.0f),
        JijiShape("clip", "M122 270 L168 258", null, JijiPaint.Solid(0xFFEDE8F6), 9f, 1.0f),
        JijiShape("clip", "M122 270 L168 258", null, JijiPaint.Solid(0xFFA090C0), 2f, 1.0f),
        JijiShape("clip", "M374 244 C378 252 382 256 390 260 C382 264 378 268 374 276 C370 268 366 264 358 260 C366 256 370 252 374 244 Z", JijiPaint.Solid(0xFFEDE8F6), JijiPaint.Solid(0xFFA090C0), 2f, 1.0f),
        JijiShape("glass", "M194 321 m-56 0 a56 56 0 1 0 112 0 a56 56 0 1 0 -112 0 Z", null, JijiPaint.Solid(0xFF17171C), 9f, 1.0f),
        JijiShape("glass", "M318 321 m-56 0 a56 56 0 1 0 112 0 a56 56 0 1 0 -112 0 Z", null, JijiPaint.Solid(0xFF17171C), 9f, 1.0f),
        JijiShape("glass", "M250 312 C253 306 259 306 262 312", null, JijiPaint.Solid(0xFF17171C), 7f, 1.0f),
        JijiShape("glass", "M138 314 L114 306", null, JijiPaint.Solid(0xFF17171C), 7f, 1.0f),
        JijiShape("glass", "M374 314 L398 306", null, JijiPaint.Solid(0xFF17171C), 7f, 1.0f),
        JijiShape("glass", "M162 292 C168 280 178 273 189 271", null, JijiPaint.Solid(0xFFFFFFFF), 6f, 0.55f),
        JijiShape("glass", "M286 292 C292 280 302 273 313 271", null, JijiPaint.Solid(0xFFFFFFFF), 6f, 0.55f),
        JijiShape("paw", "M166 304 m-9 0 a9 7.5 0 1 0 18 0 a9 7.5 0 1 0 -18 0 Z", JijiPaint.Solid(0xFFF58CAE), JijiPaint.Solid(0xFFB05878), 2f, 1.0f),
        JijiShape("paw", "M155 291 m-3.6 0 a3.6 3.6 0 1 0 7.2 0 a3.6 3.6 0 1 0 -7.2 0 Z", JijiPaint.Solid(0xFFF58CAE), JijiPaint.Solid(0xFFB05878), 1.6f, 1.0f),
        JijiShape("paw", "M164 286 m-3.6 0 a3.6 3.6 0 1 0 7.2 0 a3.6 3.6 0 1 0 -7.2 0 Z", JijiPaint.Solid(0xFFF58CAE), JijiPaint.Solid(0xFFB05878), 1.6f, 1.0f),
        JijiShape("paw", "M173 288 m-3.6 0 a3.6 3.6 0 1 0 7.2 0 a3.6 3.6 0 1 0 -7.2 0 Z", JijiPaint.Solid(0xFFF58CAE), JijiPaint.Solid(0xFFB05878), 1.6f, 1.0f),
        JijiShape("paw", "M181 295 m-3.2 0 a3.2 3.2 0 1 0 6.4 0 a3.2 3.2 0 1 0 -6.4 0 Z", JijiPaint.Solid(0xFFF58CAE), JijiPaint.Solid(0xFFB05878), 1.6f, 1.0f),
        JijiShape("ahoge", "M262 80 C256 42 278 22 298 34 C286 42 280 60 284 76", null, JijiPaint.Solid(0xFF96628A), 13f, 1.0f),
        JijiShape("ahoge", "M262 80 C256 42 278 22 298 34 C286 42 280 60 284 76", null, JijiPaint.Solid(0xFFF2AEC4), 7f, 1.0f),
    )
}
