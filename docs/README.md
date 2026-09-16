# 场记 SceneNote 方案文档

从 [00-导读与术语表.md](00-导读与术语表.md) 开始读。拆分前的完整版在 `archive/`。

## 变更记录

- **2026-09-17 · 端侧能力两端统一，翻译三档可切换**
  - 识别：sherpa-onnx 成为唯一端侧 ASR / VAD / 声纹引擎；iOS SpeechTranscriber / SFSpeechRecognizer、Android SpeechRecognizer 降为 `AsrEngine` 下的可选插件，移出 MVP（v1.1 评估），删除对应 Swift 注入点。
  - 翻译：新增 `core/nmt` 端侧 NMT（ONNX Runtime 与 sherpa-onnx 共用 + opus-mt 中 ↔ 英 int8，每方向约 80 MB，按语言对按需下载）作为两端零 Key 默认离线档；云端 BYOK（qwen-mt / Claude Haiku 4.5 / Gemini Flash-Lite）保持有 Key 时首选，快路径 1.5 s 超时降级到端侧 NMT；Apple Translation / ML Kit Translation 降为 `FastTranslator` 下的可选插件（v1.1，设置里手动启用）。删除所有"iOS 17 / 无 GMS 退化为同语字幕"表述——只有语言对模型未下载时才提示下载。
  - TTS：系统 TTS 默认，sherpa-onnx Matcha 作无系统语音包时兜底；是否统一到 sherpa-onnx 列入待决策 31。
  - 术语回流：端侧 NMT 由我们控制解码，术语表通过 S0 前置替换 + 占位符回填在离线档生效，删除"离线档不吃术语表 / 仅 BYOK 用户"的限定。
  - 连带：架构图与模块目录加 `core/nmt`、首装包体积口径（NMT 包不计入首装）、选型表新增端侧 NMT 行、路线图周 3–6 与人 × 周表改写、周 4 spike 加 NMT 实测、指标新增 H17、风险表替换 Apple Translation 宿主风险为端侧 NMT 三条风险、附录 A 新增 `OnnxNmtTranslator : FastTranslator`、附录 B 待验证 49–54 与待决策 31–33、去向账本计入端侧 NMT 模型下载；两份规格、用户场景流程图、13-执行计划同步。红线不变：无后端、无内置 Key。
