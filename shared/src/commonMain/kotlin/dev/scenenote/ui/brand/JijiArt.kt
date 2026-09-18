package dev.scenenote.ui.brand

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

// 由 scripts/brand/jiji_logo.py 生成，勿手改。改造型请改那个脚本再重跑。
// 与 docs/品牌/ 下的 SVG、两端桌面图标、介绍页里的记记酱同源，坐标系 512×512。

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
        "hair" to JijiGradient(256f, 88f, 256f, 476f, listOf(0.0f to Color(0xFFFCCBD8), 0.42f to Color(0xFFF7ABC3), 0.68f to Color(0xFFF0A4C6), 0.85f to Color(0xFFC9ACE7), 1.0f to Color(0xFFA8B7F5))),
        "hairIn" to JijiGradient(256f, 384f, 256f, 470f, listOf(0.0f to Color(0x008A66C4), 1.0f to Color(0x708A66C4))),
        "tuft" to JijiGradient(256f, 150f, 256f, 326f, listOf(0.0f to Color(0xFFF9B4CA), 0.5f to Color(0xFFF2A6C8), 1.0f to Color(0xFFB5B2F2))),
        "earL" to JijiGradient(96f, 160f, 184f, 104f, listOf(0.0f to Color(0xFFA9B8F7), 0.35f to Color(0xFFC6B4EF), 0.75f to Color(0xFFF3B0CB), 1.0f to Color(0xFFF8B2C7))),
        "earR" to JijiGradient(416f, 160f, 328f, 104f, listOf(0.0f to Color(0xFFA9B8F7), 0.35f to Color(0xFFC6B4EF), 0.75f to Color(0xFFF3B0CB), 1.0f to Color(0xFFF8B2C7))),
        "skin" to JijiGradient(256f, 200f, 256f, 430f, listOf(0.0f to Color(0xFFFFF7F1), 1.0f to Color(0xFFFFE5D9))),
        "irisL" to JijiGradient(256f, 278f, 256f, 342f, listOf(0.0f to Color(0xFF7A2E0E), 0.4f to Color(0xFFEC7F1C), 0.75f to Color(0xFFFFBB3A), 1.0f to Color(0xFFFFE68E))),
        "irisR" to JijiGradient(256f, 278f, 256f, 342f, listOf(0.0f to Color(0xFF2A2270), 0.4f to Color(0xFF5D5FD8), 0.75f to Color(0xFF8FA6F6), 1.0f to Color(0xFFD2E2FF))),
    )

    /** 动效支点：耳根、两只眼心、呆毛根。 */
    val pivots: Map<String, Offset> = mapOf(
        "earL" to Offset(172f, 196f),
        "earR" to Offset(340f, 196f),
        "eyeL" to Offset(197f, 313f),
        "eyeR" to Offset(315f, 313f),
        "ahoge" to Offset(263f, 96f),
    )

    /** 画序即图层序：贴纸白边在最底，眼镜和呆毛在最上。 */
    val shapes: List<JijiShape> = listOf(
        JijiShape("tuftL", "M106 296 C70 302 40 332 30 376 C26 394 28 410 34 426 C40 408 50 398 62 392 C62 410 66 424 76 438 C82 416 94 402 108 394 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 31.0f, 1.0f),
        JijiShape("tuftR", "M406 296 C442 302 472 332 482 376 C486 394 484 410 478 426 C472 408 462 398 450 392 C450 410 446 424 436 438 C430 416 418 402 404 394 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 31.0f, 1.0f),
        JijiShape("bowL", "M72 292 C58 330 60 380 48 436 L58 430 L64 444 C76 392 74 338 86 296 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 29.5f, 1.0f),
        JijiShape("bowR", "M440 292 C454 330 452 380 464 436 L454 430 L448 444 C436 392 438 338 426 296 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 29.5f, 1.0f),
        JijiShape("bowL", "M84 284 C64 256 38 246 30 264 C24 280 48 294 84 288 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 29.5f, 1.0f),
        JijiShape("bowR", "M428 284 C448 256 474 246 482 264 C488 280 464 294 428 288 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 29.5f, 1.0f),
        JijiShape("bowL", "M84 288 C60 292 36 306 42 324 C48 340 72 320 86 294 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 29.5f, 1.0f),
        JijiShape("bowR", "M428 288 C452 292 476 306 470 324 C464 340 440 320 426 294 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 29.5f, 1.0f),
        JijiShape("hairBack", "M256 100 C164 100 78 176 74 290 C72 346 82 396 104 430 C116 448 132 458 152 462 C154 452 160 445 170 440 C178 452 192 462 210 468 C214 459 222 452 232 448 C238 455 246 460 256 462 C266 460 274 455 280 448 C290 452 298 459 302 468 C320 462 334 452 342 440 C352 445 358 452 360 462 C380 458 396 448 408 430 C430 396 440 346 438 290 C434 176 348 100 256 100 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 31.0f, 1.0f),
        JijiShape("earL", "M108 204 C90 146 94 76 120 32 C124 25 133 24 139 29 C186 62 224 106 240 152 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 31.0f, 1.0f),
        JijiShape("earL", "M126 186 C112 140 114 92 128 56 C162 82 196 116 212 150 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 29.0f, 1.0f),
        JijiShape("earL", "M126 182 C122 160 124 140 130 120 C134 132 138 142 144 150 C142 130 144 112 150 94 C156 110 162 122 170 132 C170 122 172 114 176 106 C182 120 190 132 198 142 C202 146 206 150 210 152 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 28.5f, 1.0f),
        JijiShape("earR", "M404 204 C422 146 418 76 392 32 C388 25 379 24 373 29 C326 62 288 106 272 152 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 31.0f, 1.0f),
        JijiShape("earR", "M386 186 C400 140 398 92 384 56 C350 82 316 116 300 150 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 29.0f, 1.0f),
        JijiShape("earR", "M386 182 C390 160 388 140 382 120 C378 132 374 142 368 150 C370 130 368 112 362 94 C356 110 350 122 342 132 C342 122 340 114 336 106 C330 120 322 132 314 142 C310 146 306 150 302 152 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 28.5f, 1.0f),
        JijiShape("sideL", "M100 240 C84 286 76 350 88 400 C92 418 100 432 112 442 C112 430 114 420 120 412 C126 430 136 444 152 450 C142 434 138 418 140 398 C144 358 146 300 134 244 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 31.0f, 1.0f),
        JijiShape("sideR", "M412 240 C428 286 436 350 424 400 C420 418 412 432 400 442 C400 430 398 420 392 412 C386 430 376 444 360 450 C370 434 374 418 372 398 C368 358 366 300 378 244 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 31.0f, 1.0f),
        JijiShape("bangs", "M238 190 C232 240 240 290 260 324 C270 292 282 240 282 190 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 30.0f, 1.0f),
        JijiShape("bangs", "M92 304 C78 190 148 90 256 88 C364 90 434 190 420 304 C408 290 402 274 400 256 C398 274 388 288 372 298 C370 278 362 260 352 242 C350 250 346 254 340 257 C334 252 328 246 324 238 C320 248 312 255 302 258 C286 256 266 240 258 214 C250 240 230 256 212 258 C202 255 194 248 190 238 C186 246 180 252 174 257 C168 254 164 250 162 242 C158 262 152 282 140 298 C128 286 118 272 112 256 C110 274 104 290 92 304 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 31.0f, 1.0f),
        JijiShape("ahoge", "M256 96 C244 72 244 44 264 34 C276 28 290 32 294 42 C284 37 273 40 268 50 C261 64 265 82 271 96 Z", JijiPaint.Solid(0xFFFFFFFF), JijiPaint.Solid(0xFFFFFFFF), 31.0f, 1.0f),
        JijiShape("tuftL", "M106 296 C70 302 40 332 30 376 C26 394 28 410 34 426 C40 408 50 398 62 392 C62 410 66 424 76 438 C82 416 94 402 108 394 Z", JijiPaint.Grad("hair"), JijiPaint.Solid(0xFF7E4A74), 5f, 1.0f),
        JijiShape("tuftR", "M406 296 C442 302 472 332 482 376 C486 394 484 410 478 426 C472 408 462 398 450 392 C450 410 446 424 436 438 C430 416 418 402 404 394 Z", JijiPaint.Grad("hair"), JijiPaint.Solid(0xFF7E4A74), 5f, 1.0f),
        JijiShape("bowL", "M72 292 C58 330 60 380 48 436 L58 430 L64 444 C76 392 74 338 86 296 Z", JijiPaint.Solid(0xFFCFE5FB), JijiPaint.Solid(0xFF6F8FCB), 3.5f, 1.0f),
        JijiShape("bowR", "M440 292 C454 330 452 380 464 436 L454 430 L448 444 C436 392 438 338 426 296 Z", JijiPaint.Solid(0xFFCFE5FB), JijiPaint.Solid(0xFF6F8FCB), 3.5f, 1.0f),
        JijiShape("bowL", "M84 284 C64 256 38 246 30 264 C24 280 48 294 84 288 Z", JijiPaint.Solid(0xFFCFE5FB), JijiPaint.Solid(0xFF6F8FCB), 3.5f, 1.0f),
        JijiShape("bowR", "M428 284 C448 256 474 246 482 264 C488 280 464 294 428 288 Z", JijiPaint.Solid(0xFFCFE5FB), JijiPaint.Solid(0xFF6F8FCB), 3.5f, 1.0f),
        JijiShape("bowL", "M84 288 C60 292 36 306 42 324 C48 340 72 320 86 294 Z", JijiPaint.Solid(0xFFA9CBF2), JijiPaint.Solid(0xFF6F8FCB), 3.5f, 1.0f),
        JijiShape("bowR", "M428 288 C452 292 476 306 470 324 C464 340 440 320 426 294 Z", JijiPaint.Solid(0xFFA9CBF2), JijiPaint.Solid(0xFF6F8FCB), 3.5f, 1.0f),
        JijiShape("hairBack", "M256 100 C164 100 78 176 74 290 C72 346 82 396 104 430 C116 448 132 458 152 462 C154 452 160 445 170 440 C178 452 192 462 210 468 C214 459 222 452 232 448 C238 455 246 460 256 462 C266 460 274 455 280 448 C290 452 298 459 302 468 C320 462 334 452 342 440 C352 445 358 452 360 462 C380 458 396 448 408 430 C430 396 440 346 438 290 C434 176 348 100 256 100 Z", JijiPaint.Grad("hair"), JijiPaint.Solid(0xFF7E4A74), 5f, 1.0f),
        JijiShape("hairBack", "M256 100 C164 100 78 176 74 290 C72 346 82 396 104 430 C116 448 132 458 152 462 C154 452 160 445 170 440 C178 452 192 462 210 468 C214 459 222 452 232 448 C238 455 246 460 256 462 C266 460 274 455 280 448 C290 452 298 459 302 468 C320 462 334 452 342 440 C352 445 358 452 360 462 C380 458 396 448 408 430 C430 396 440 346 438 290 C434 176 348 100 256 100 Z", JijiPaint.Grad("hairIn"), null, 0.0f, 1.0f),
        JijiShape("earL", "M108 204 C90 146 94 76 120 32 C124 25 133 24 139 29 C186 62 224 106 240 152 Z", JijiPaint.Grad("earL"), JijiPaint.Solid(0xFF7E4A74), 5f, 1.0f),
        JijiShape("earL", "M126 186 C112 140 114 92 128 56 C162 82 196 116 212 150 Z", JijiPaint.Solid(0xFFFFDCE6), JijiPaint.Solid(0xFFEBA3BE), 3f, 1.0f),
        JijiShape("earL", "M126 182 C122 160 124 140 130 120 C134 132 138 142 144 150 C142 130 144 112 150 94 C156 110 162 122 170 132 C170 122 172 114 176 106 C182 120 190 132 198 142 C202 146 206 150 210 152 Z", JijiPaint.Solid(0xFFFFFAF5), JijiPaint.Solid(0xFFF0C6D4), 2.5f, 1.0f),
        JijiShape("earR", "M404 204 C422 146 418 76 392 32 C388 25 379 24 373 29 C326 62 288 106 272 152 Z", JijiPaint.Grad("earR"), JijiPaint.Solid(0xFF7E4A74), 5f, 1.0f),
        JijiShape("earR", "M386 186 C400 140 398 92 384 56 C350 82 316 116 300 150 Z", JijiPaint.Solid(0xFFFFDCE6), JijiPaint.Solid(0xFFEBA3BE), 3f, 1.0f),
        JijiShape("earR", "M386 182 C390 160 388 140 382 120 C378 132 374 142 368 150 C370 130 368 112 362 94 C356 110 350 122 342 132 C342 122 340 114 336 106 C330 120 322 132 314 142 C310 146 306 150 302 152 Z", JijiPaint.Solid(0xFFFFFAF5), JijiPaint.Solid(0xFFF0C6D4), 2.5f, 1.0f),
        JijiShape("face", "M256 168 C186 168 136 210 132 268 C128 322 142 364 180 396 C206 416 232 428 256 428 C280 428 306 416 332 396 C370 364 384 322 380 268 C376 210 326 168 256 168 Z", JijiPaint.Grad("skin"), JijiPaint.Solid(0xFFDE98A6), 4f, 1.0f),
        JijiShape("face", "M92 314 C78 200 148 100 256 98 C364 100 434 200 420 314 C408 300 402 284 400 266 C398 284 388 298 372 308 C370 288 362 270 352 252 C350 260 346 264 340 267 C334 262 328 256 324 248 C320 258 312 265 302 268 C286 266 266 250 258 224 C250 250 230 266 212 268 C202 265 194 258 190 248 C186 256 180 262 174 267 C168 264 164 260 162 252 C158 272 152 292 140 308 C128 296 118 282 112 266 C110 284 104 300 92 314 Z", JijiPaint.Solid(0xFFF6C6C2), null, 0.0f, 0.5f),
        JijiShape("blush", "M132 378 A24 11 0 1 0 180 378 A24 11 0 1 0 132 378 Z", JijiPaint.Solid(0xFFFF9DB6), null, 0.0f, 0.5f),
        JijiShape("blush", "M380 378 A24 11 0 1 1 332 378 A24 11 0 1 1 380 378 Z", JijiPaint.Solid(0xFFFF9DB6), null, 0.0f, 0.5f),
        JijiShape("blush", "M144 373 L140 383", null, JijiPaint.Solid(0xFFF2789A), 2.6f, 0.7f),
        JijiShape("blush", "M368 373 L372 383", null, JijiPaint.Solid(0xFFF2789A), 2.6f, 0.7f),
        JijiShape("blush", "M155 373 L151 383", null, JijiPaint.Solid(0xFFF2789A), 2.6f, 0.7f),
        JijiShape("blush", "M357 373 L361 383", null, JijiPaint.Solid(0xFFF2789A), 2.6f, 0.7f),
        JijiShape("blush", "M166 373 L162 383", null, JijiPaint.Solid(0xFFF2789A), 2.6f, 0.7f),
        JijiShape("blush", "M346 373 L350 383", null, JijiPaint.Solid(0xFFF2789A), 2.6f, 0.7f),
        JijiShape("eyeL", "M162 311 C163 289 180 274 200 274 C217 274 229 286 231 303 C233 325 222 345 198 348 C176 349 162 333 162 311 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("eyeL", "M164 303 C168 287 182 277 200 277 C216 277 227 287 230 301 C220 291 210 287 198 287 C184 287 172 293 164 303 Z", JijiPaint.Solid(0xFFDCCFEA), null, 0.0f, 0.75f),
        JijiShape("eyeR", "M350 311 C349 289 332 274 312 274 C295 274 283 286 281 303 C279 325 290 345 314 348 C336 349 350 333 350 311 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("eyeR", "M348 303 C344 287 330 277 312 277 C296 277 285 287 282 301 C292 291 302 287 314 287 C328 287 340 293 348 303 Z", JijiPaint.Solid(0xFFDCCFEA), null, 0.0f, 0.75f),
        JijiShape("eyeL", "M169.5 313 A27.5 33 0 1 0 224.5 313 A27.5 33 0 1 0 169.5 313 Z", JijiPaint.Grad("irisL"), null, 0.0f, 1.0f),
        JijiShape("eyeL", "M169.5 313 A27.5 33 0 1 0 224.5 313 A27.5 33 0 1 0 169.5 313 Z", null, JijiPaint.Solid(0xFF6A2A0C), 3f, 0.55f),
        JijiShape("eyeL", "M170.54 304 A27.5 33 0 0 1 223.46 304 C208 312 186 312 170.54 304 Z", JijiPaint.Solid(0xFF43190A), null, 0.0f, 0.38f),
        JijiShape("eyeL", "M186 314 A11 15.5 0 1 0 208 314 A11 15.5 0 1 0 186 314 Z", JijiPaint.Solid(0xFF43190A), null, 0.0f, 1.0f),
        JijiShape("eyeL", "M181 335 A16 8 0 1 0 213 335 A16 8 0 1 0 181 335 Z", JijiPaint.Solid(0xFFFFF2B8), null, 0.0f, 0.75f),
        JijiShape("eyeL", "M175.5 296 A9.5 9.5 0 1 0 194.5 296 A9.5 9.5 0 1 0 175.5 296 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("eyeL", "M206.6 331 A4.4 4.4 0 1 0 215.4 331 A4.4 4.4 0 1 0 206.6 331 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("eyeL", "M176 313 A3 3 0 1 0 182 313 A3 3 0 1 0 176 313 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 0.85f),
        JijiShape("eyeR", "M342.5 313 A27.5 33 0 1 1 287.5 313 A27.5 33 0 1 1 342.5 313 Z", JijiPaint.Grad("irisR"), null, 0.0f, 1.0f),
        JijiShape("eyeR", "M342.5 313 A27.5 33 0 1 1 287.5 313 A27.5 33 0 1 1 342.5 313 Z", null, JijiPaint.Solid(0xFF241A5C), 3f, 0.55f),
        JijiShape("eyeR", "M341.46 304 A27.5 33 0 0 0 288.54 304 C304 312 326 312 341.46 304 Z", JijiPaint.Solid(0xFF1D164E), null, 0.0f, 0.38f),
        JijiShape("eyeR", "M326 314 A11 15.5 0 1 1 304 314 A11 15.5 0 1 1 326 314 Z", JijiPaint.Solid(0xFF1D164E), null, 0.0f, 1.0f),
        JijiShape("eyeR", "M331 335 A16 8 0 1 1 299 335 A16 8 0 1 1 331 335 Z", JijiPaint.Solid(0xFFDCE8FF), null, 0.0f, 0.75f),
        JijiShape("eyeR", "M293.5 296 A9.5 9.5 0 1 0 312.5 296 A9.5 9.5 0 1 0 293.5 296 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("eyeR", "M324.6 331 A4.4 4.4 0 1 0 333.4 331 A4.4 4.4 0 1 0 324.6 331 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 1.0f),
        JijiShape("eyeR", "M294 313 A3 3 0 1 0 300 313 A3 3 0 1 0 294 313 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 0.85f),
        JijiShape("lashL", "M146 293 C158 273 178 262 200 262 C216 262 230 271 238 289 C230 279 216 274 200 274 C184 274 170 283 164 301 C162 306 160 311 160 315 C154 307 150 301 146 293 Z", JijiPaint.Solid(0xFF3A2232), null, 0.0f, 1.0f),
        JijiShape("lashL", "M153 304 C148 303 143 304 139 307 C145 308 150 311 154 315 Z", JijiPaint.Solid(0xFF3A2232), null, 0.0f, 1.0f),
        JijiShape("lashL", "M178 350 C190 355 208 354 220 347", null, JijiPaint.Solid(0xFF7A5468), 3f, 0.8f),
        JijiShape("lashR", "M366 293 C354 273 334 262 312 262 C296 262 282 271 274 289 C282 279 296 274 312 274 C328 274 342 283 348 301 C350 306 352 311 352 315 C358 307 362 301 366 293 Z", JijiPaint.Solid(0xFF3A2232), null, 0.0f, 1.0f),
        JijiShape("lashR", "M359 304 C364 303 369 304 373 307 C367 308 362 311 358 315 Z", JijiPaint.Solid(0xFF3A2232), null, 0.0f, 1.0f),
        JijiShape("lashR", "M334 350 C322 355 304 354 292 347", null, JijiPaint.Solid(0xFF7A5468), 3f, 0.8f),
        JijiShape("mouth", "M254 374 C255 377 257 378 259 378", null, JijiPaint.Solid(0xFFE8A0A0), 3.4f, 1.0f),
        JijiShape("mouth", "M242 394 C245 402 253 402 256 395 C259 402 267 402 270 394", null, JijiPaint.Solid(0xFFB4506C), 4f, 1.0f),
        JijiShape("glass", "M140 300 L104 292", null, JijiPaint.Solid(0xFF1D1924), 8f, 1.0f),
        JijiShape("glass", "M372 300 L408 292", null, JijiPaint.Solid(0xFF1D1924), 8f, 1.0f),
        JijiShape("sideL", "M100 240 C84 286 76 350 88 400 C92 418 100 432 112 442 C112 430 114 420 120 412 C126 430 136 444 152 450 C142 434 138 418 140 398 C144 358 146 300 134 244 Z", JijiPaint.Grad("hair"), JijiPaint.Solid(0xFF7E4A74), 5f, 1.0f),
        JijiShape("sideL", "M112 270 C102 320 102 380 114 430", null, JijiPaint.Solid(0xFFEC93B1), 3f, 0.7f),
        JijiShape("sideR", "M412 240 C428 286 436 350 424 400 C420 418 412 432 400 442 C400 430 398 420 392 412 C386 430 376 444 360 450 C370 434 374 418 372 398 C368 358 366 300 378 244 Z", JijiPaint.Grad("hair"), JijiPaint.Solid(0xFF7E4A74), 5f, 1.0f),
        JijiShape("sideR", "M400 270 C410 320 410 380 398 430", null, JijiPaint.Solid(0xFFEC93B1), 3f, 0.7f),
        JijiShape("bangs", "M238 190 C232 240 240 290 260 324 C270 292 282 240 282 190 Z", JijiPaint.Grad("tuft"), JijiPaint.Solid(0xFF7E4A74), 4f, 1.0f),
        JijiShape("bangs", "M92 304 C78 190 148 90 256 88 C364 90 434 190 420 304 C408 290 402 274 400 256 C398 274 388 288 372 298 C370 278 362 260 352 242 C350 250 346 254 340 257 C334 252 328 246 324 238 C320 248 312 255 302 258 C286 256 266 240 258 214 C250 240 230 256 212 258 C202 255 194 248 190 238 C186 246 180 252 174 257 C168 254 164 250 162 242 C158 262 152 282 140 298 C128 286 118 272 112 256 C110 274 104 290 92 304 Z", JijiPaint.Grad("hair"), JijiPaint.Solid(0xFF7E4A74), 5f, 1.0f),
        JijiShape("bangs", "M168 196 C184 170 208 152 238 145", null, JijiPaint.Solid(0xFFFFE9F0), 9f, 0.8f),
        JijiShape("bangs", "M292 147 C316 154 336 168 350 188", null, JijiPaint.Solid(0xFFFFE9F0), 9f, 0.8f),
        JijiShape("bangs", "M212 102 C238 90 280 88 304 100 C280 96 240 98 212 106 Z", JijiPaint.Solid(0xFFC2557F), null, 0.0f, 0.9f),
        JijiShape("bangs", "M112 256 C114 222 128 186 150 158", null, JijiPaint.Solid(0xFFEC93B1), 2.8f, 0.8f),
        JijiShape("bangs", "M162 242 C164 214 176 184 194 160", null, JijiPaint.Solid(0xFFEC93B1), 2.8f, 0.8f),
        JijiShape("bangs", "M190 238 C192 212 204 186 220 162", null, JijiPaint.Solid(0xFFEC93B1), 2.8f, 0.8f),
        JijiShape("bangs", "M324 238 C322 212 310 186 294 162", null, JijiPaint.Solid(0xFFEC93B1), 2.8f, 0.8f),
        JijiShape("bangs", "M352 242 C350 214 338 184 320 160", null, JijiPaint.Solid(0xFFEC93B1), 2.8f, 0.8f),
        JijiShape("bangs", "M400 258 C398 222 384 186 362 158", null, JijiPaint.Solid(0xFFEC93B1), 2.8f, 0.8f),
        JijiShape("bangs", "M250 106 C224 142 210 196 211 254 C222 202 238 150 262 110 Z", JijiPaint.Solid(0xFFBCE0FB), null, 0.0f, 0.92f),
        JijiShape("bangs", "M278 106 C320 138 354 192 368 252 C340 198 308 148 270 112 Z", JijiPaint.Solid(0xFFBCE0FB), null, 0.0f, 0.92f),
        JijiShape("brow", "M168 236 C180 229 200 228 216 233", null, JijiPaint.Solid(0xFFBE6E8C), 3.4f, 0.8f),
        JijiShape("brow", "M344 236 C332 229 312 228 296 233", null, JijiPaint.Solid(0xFFBE6E8C), 3.4f, 0.8f),
        JijiShape("clip", "M122 224 L164 214", null, JijiPaint.Solid(0xFF9A84CC), 11f, 1.0f),
        JijiShape("clip", "M122 224 L164 214", null, JijiPaint.Solid(0xFFF3E9FF), 6.5f, 1.0f),
        JijiShape("clip", "M126 242 L168 232", null, JijiPaint.Solid(0xFF9A84CC), 11f, 1.0f),
        JijiShape("clip", "M126 242 L168 232", null, JijiPaint.Solid(0xFFF3E9FF), 6.5f, 1.0f),
        JijiShape("clip", "M346 226 C354 213 368 209 392 211 C388 217 388 222 390 228 C382 226 378 228 376 234 C370 230 364 230 360 236 C356 230 352 228 346 226 Z", JijiPaint.Solid(0xFFD8EDFF), JijiPaint.Solid(0xFF6F8FCB), 3f, 1.0f),
        JijiShape("glass", "M139 309 C139 269.8 155.5 253 194 253 C232.5 253 249 269.8 249 309 C249 348.2 232.5 365 194 365 C155.5 365 139 348.2 139 309 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 0.1f),
        JijiShape("glass", "M373 309 C373 269.8 356.5 253 318 253 C279.5 253 263 269.8 263 309 C263 348.2 279.5 365 318 365 C356.5 365 373 348.2 373 309 Z", JijiPaint.Solid(0xFFFFFFFF), null, 0.0f, 0.1f),
        JijiShape("glass", "M139 309 C139 269.8 155.5 253 194 253 C232.5 253 249 269.8 249 309 C249 348.2 232.5 365 194 365 C155.5 365 139 348.2 139 309 Z", null, JijiPaint.Solid(0xFF1D1924), 10f, 1.0f),
        JijiShape("glass", "M373 309 C373 269.8 356.5 253 318 253 C279.5 253 263 269.8 263 309 C263 348.2 279.5 365 318 365 C356.5 365 373 348.2 373 309 Z", null, JijiPaint.Solid(0xFF1D1924), 10f, 1.0f),
        JijiShape("glass", "M249 300 C252 293 260 293 263 300", null, JijiPaint.Solid(0xFF1D1924), 8f, 1.0f),
        JijiShape("paw", "M135.5 273 A11.5 9.5 0 1 0 158.5 273 A11.5 9.5 0 1 0 135.5 273 Z", JijiPaint.Solid(0xFFFFB29C), JijiPaint.Solid(0xFF5A2E3A), 3f, 1.0f),
        JijiShape("paw", "M127.4 263 A4.6 4.6 0 1 0 136.6 263 A4.6 4.6 0 1 0 127.4 263 Z", JijiPaint.Solid(0xFFFFB29C), JijiPaint.Solid(0xFF5A2E3A), 2.4f, 1.0f),
        JijiShape("paw", "M135.4 254 A4.6 4.6 0 1 0 144.6 254 A4.6 4.6 0 1 0 135.4 254 Z", JijiPaint.Solid(0xFFFFB29C), JijiPaint.Solid(0xFF5A2E3A), 2.4f, 1.0f),
        JijiShape("paw", "M147.4 252 A4.6 4.6 0 1 0 156.6 252 A4.6 4.6 0 1 0 147.4 252 Z", JijiPaint.Solid(0xFFFFB29C), JijiPaint.Solid(0xFF5A2E3A), 2.4f, 1.0f),
        JijiShape("paw", "M157.4 259 A4.6 4.6 0 1 0 166.6 259 A4.6 4.6 0 1 0 157.4 259 Z", JijiPaint.Solid(0xFFFFB29C), JijiPaint.Solid(0xFF5A2E3A), 2.4f, 1.0f),
        JijiShape("ahoge", "M256 96 C244 72 244 44 264 34 C276 28 290 32 294 42 C284 37 273 40 268 50 C261 64 265 82 271 96 Z", JijiPaint.Solid(0xFFFAB9CC), JijiPaint.Solid(0xFF7E4A74), 5f, 1.0f),
    )
}
