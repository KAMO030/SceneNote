package dev.scenenote.nmt

/** 读环境变量（真实模型文件的测试用 `SCENENOTE_NMT_MODELS` 指向下载目录，没设就跳过）。 */
expect fun testEnv(name: String): String?
