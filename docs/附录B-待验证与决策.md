# 附录 B · 待验证事项与决策点

> **这篇讲什么**：54 项"要真机实测才知道"的事和 33 项"要有人拍板"的事，每条都写了预设答案。分成四组：v1 保留、屏外实时、屏内、本次修订新增（含 Koog 与 UI 设计语言）。
> **谁该看**：全员；MVP 前两周建议先把和第 4 周 / 第 10 周关卡相关的实测做掉。
> **交叉引用说明**：正文里的 "§x.y" 是拆分前完整版的章节编号，对应关系：§0–§1 → 01 篇，§2 → 02 篇，§3 → 03 篇，§4–§6 → 04 篇，§7.1–7.4 → 05 篇，§7.5–7.9 → 06 篇，§7.10–7.14 → 07 篇，§8 → 08 篇，§9 → 09 篇，§10 → 10 篇，§11 → 11 篇，§12 → 12 篇，附录 A / B → 附录篇。完整版存于 `archive/`。

## 先看这个（大白话）

**最先要实测的**：基线机（iPhone 12 / 骁龙 7 系）上各模型的速度、内存、耗电（决定内存分级）；opus-mt 中 ↔ 英 int8 在基线机的单句延迟 / 内存 / 与 ASR + TTS 同跑的发热；ONNX Runtime 与 sherpa-onnx 共用运行时的链接冲突；蓝牙耳机的真实延迟和单耳下混行为；"三种姿态 × 三种噪声"下 M0 的判向准确率；待机 8 小时电量；Android 主流视频 App 允不允许抓声音；苹果 Background Assets 的体积上限；Koog 在 iOS 上的包体增量；compose-cupertino / haze 与 CMP 1.12 的兼容。

**最先要拍板的**：MVP 是 18 周 6 人还是砍范围；中 ↔ 英 NMT 语言对包（双向约 160 MB）随包内置还是按需下载（预设：按需，出门预热强制预下载）；TTS 是否也统一到 sherpa-onnx（预设：系统 TTS 默认、Matcha 兜底）；判向默认自动还是固定方向（预设：自动，灰度数据说话）；LLM 执行层用不用 Koog（预设：用，验证不过就回退自研）；标签栏 4 个还是 5 个（预设：4）。

---

## 附录 B：待验证事项与决策点（合并两份规格）

**待验证（事实 / 技术）——v1 保留**：
1. Compose Multiplatform 1.12.0 对 Kotlin 2.4.20 的官方兼容声明；AGP 8.13 / SKIE / SQLDelight 插件 / Koin 4.2.2 的适配状态。
2. 百炼 fun-asr-realtime / qwen3-asr-flash-realtime 对吴语、闽南语、西南官话的逐模型页支持与实际 CER（总览页列出，模型页未逐一列出）。
3. SenseVoice 2025-09-09 粤语微调版是否确实无标点输出；CT-Transformer int8 体积；sherpa-onnx whisper base.en int8 体积与 WER；Qwen3.5-2B GGUF 体积。
4. iPhone 12 / 骁龙 7 系上 paraformer-zh-small、SenseVoice、川渝 Paraformer 的实测 RTF、内存峰值、每小时耗电（MVP 周 4 验收）；端侧 NMT（opus-mt 中 ↔ 英 int8）在基线机的单句延迟（10 / 20 / 40 token 三档）。
5. （插件项，v1.1）iOS 26 SpeechTranscriber 对 zh_HK / yue_CN 的实际识别质量；iOS 27 正式版 Translation 框架是否开放粤语（`LanguageAvailability.status`）——仅在评估系统识别 / 系统翻译插件时验证，不影响 MVP。
6. OpenCC 规则表与拼音词典的 KMP 实现选型；KMP Argon2 / PBKDF2 与 libsodium 绑定选型。
7. ModelScope 直链在中国大陆的可达性与稳定性；`.sherpa-pack` 经微信文件传输的可用性；`.voxnote` 经微信发送的可用性。
8. Ktor CIO 服务端在 iosArm64 真机的 WebSocket 稳定性与 `NWListener` 权限弹窗行为（**提前到 v1.1 立项前 PoC**）。
9. 坚果云 / OneDrive 在 iOS File Provider 与 Android SAF 下的 ops 追加、变更通知、占位文件行为（v1.1 立项前）。
10. 火山引擎 BYOK 免费额度；各 provider 最短开通路径的步数 / 实名 / 绑卡（§7.6 表格逐一复核）。
11. Android 商店区域判定依据（Play 国家 vs 系统区域；无 GMS 设备）。
12. Live Activity 在 CarPlay 的显示行为；Android Activity Recognition 在国产 ROM 的可用性。
13. 蓝牙麦克风（AirPods / 国产 TWS / 车机）在 iOS HFP 与 Android SCO 下的实测矩阵（录音场景）。
14. Llamatik 1.7.0 + Qwen3.5-2B 在国产 ROM 与 iPhone 15 Pro / 骁龙 8 系的内存、速度、发热矩阵（v1.1）。
15. 首发三包的许可链（转换仓库无许可字段、FunASR MODEL_LICENSE 约束）法务复核结论。

**待验证——屏外实时（来自《矩阵规格》§13.3，合并）**：
16. 各端侧模型（流式 zipformer（greedy 与 modified_beam_search=4 分别）/ SenseVoice / 川渝 Paraformer / 3D-Speaker CAM++）在旗舰机与基线机的 RTF / 首包 / 内存；sherpa 官方 RTF 为桌面数据，手机 ×2–3 待实测；声纹 int8 体积；各档常驻内存峰值；发热时切换模型的尖峰；whisper-tiny LID 包（≈ 98 MB）是否值得（待决策 21）。
17. qwen-mt-lite / qwen-mt-flash / claude-haiku-4-5（关 thinking）/ Gemini Flash-Lite / 豆包的实测 TTFT 与每句延迟；跨境 RTT 下在线档是否落在 1.8–2.1 s；系统 TTS 首包按平台单列（iOS `AVSpeechSynthesizer.write` 首次合成；Android `onAudioAvailable` 支持度与无 Google TTS 的国产 ROM）；A2DP 回环补偿实测值。
18. 混合档 30 分钟温度与耗电（目标 1 小时 ≤ 15%，无来源）；待机档 8 小时电量与待机 → Live 重载时延（目标 1–2 s）；jetsam / 国产 ROM 下待机进程存活率；基线机离线档句尾 → 首音是否 ≤ 2.5 s。
19. OEM（三星 / 小米 / OPPO）在 `VOICE_COMMUNICATION` 源下自动拉 SCO 的行为；A2DP 下硬件 AEC 参考路径。
20. TWS 各品牌单耳放回充电盒后的下混行为（M5）；AirPods 4 开放式泄漏对手机麦的影响；`AVAudioSession.outputLatency` 对 AirPods 的可靠性；LE Audio 耳机作系统默认输入时 Android `setPreferredDevice(TYPE_BUILTIN_MIC)` 的实际路由；iOS `supportedPolarPatterns` 覆盖机型；拔耳机脚本下的实际泄漏窗口（目标 ≤ 100 ms）。
21. iOS 后台 / 锁屏下 Ktor CIO accept loop 存活；iOS 热点客户端上限（5？仅社区经验）与"无客户端 90 s 关闭"行为；MDM 禁热点比例；**安全评审**：sender-key 分发 / HKDF 方向密钥 / 帧头 ctrLow 重建 / v1 XChaCha20 → ChaCha20 变更。
22. Safari `speechSynthesis` 锁屏 / 切换后失效回归；BLE 音频通道 iOS ↔ Android 实际吞吐、丢包与协商 MTU（按最坏 185 设计；正文不再给具体 Mbps）（v2 前）；Opus 两套绑定互通与 code-3 多帧包解析一致性。
23. `TURN_END` 强制端点相对静音等待的实测收益（预期快 150–300 ms）。
24. WatchConnectivity / MessageClient 抬腕字幕延迟（100–500 ms？）。
25. Android 16 音频分享（Auracast 引导）是否包含第三方媒体流（观察项）。
26. 姿态检测误触发率（口袋内翻动）；竖直 + 屏幕朝外 + 静止 300 ms 三条件是否足够；背向 Face ID 时自动解锁失败率；**三姿态（胸前口袋 / 手持胸前 / 挂绳，裤兜作对照）× 三噪声环境（安静 / 街道 / 餐厅）的 SNR、判向准确率与声纹可用性**（周 10 验收）。
27. （插件项，v1.1）Apple Translation 常驻 SwiftUI `.translationTask` 宿主在长会话中的稳定性与内存；宿主重建对会话的影响——仅在用户手动启用系统翻译插件时相关，MVP 不验证。
28. 各 BYOK 厂商客户端直连的 CORS / 签名逐家验证（ElevenLabs / MiniMax / 豆包 / Gemini）；GPT-Realtime-Translate 与 Qwen3.5-LiveTranslate-Flash 的模型 ID、价格与直连可行性。
29. 每小时对话约 300 句、每句 ≈ 400 / 40 token、Eager 1.4–2 次调用的费用换算假设；Opus 5 / Sonnet 5 前缀缓存的实际命中率。

**待验证——屏内（来自《屏内规格》§10.3，合并）**：
30. Android：Chrome / 抖音 / B 站 / 腾讯视频 / 爱奇艺最新版可抓性（本地兼容表初值，Netflix / Spotify 已确认不可）；YouTube / Twitch / Prime / VLC / SoundCloud 社区报告的真机复核（标注核实日期）；Tile 透明 Activity 直进 PiP 在 MIUI / ColorOS / OriginOS 的可行性；PiP 被 YouTube 自身 PiP 顶掉后的行为；Android 14 单 App 投屏对音频 UID 范围的影响；播放抓取 + 麦克风同开在小米 / 华为；PiP 承载文字在各 OEM 的表现；ducking 在主流视频 App 的 duck vs 暂停行为；OEM 权限菜单名（OemGuide JSON）。
31. iOS 四项：① PiP 承载纯字幕（非视频内容）的 App Review 态度（TestFlight 提交一次探审核）；② Netflix / Apple TV+ `.audioApp` 是否静音；③ PiP + 扩展 30 分钟功耗与稳定性；④ 保活链路（PiP 关闭后主 App 挂起 → 扩展 5 s 无 ACK 结束广播）。扩展只转发，不再验证 SpeechAnalyzer 扩展内初始化。
32. iOS 27 Generated Subtitles（观察项：仅美 / 加英语、无检测 API；是否扩展地区与语言）；SenseVoice 在 iPhone 15 Pro 的批式实时倍数（桌面 10–30×，手机预期 5–15×；S4 全片 ≤ 1/4 时长据此定）；MTAudioProcessingTap 对直链 MP4 在 iOS 26 / 27 的稳定性；iOS HLS 分片自取（fMP4 单段 `AVAssetReader` / TS 自写解复用 → AudioConverter）的可行性（v1.1）。
33. ML Kit OCR 对动态视频字幕的识别率；30 分钟 S1 连续运行 Android 中端机 CPU / 电量实测。
34. Android 16 QPR2 开发者验证对侧载渠道（无 GMS 商店）的影响。

**待验证——本次修订新增**：
35. Apple-Hosted Background Assets 的体积上限、首装计入规则、审核行为与无网首启体验（替代已弃用的 ODR）。
36. iOS 首装包实测体积（onnxruntime 静态库、Skia / Compose 运行时、OpenCC 与拼音词典、声纹包）是否 ≤ 250 MB；Android 国内渠道各商店 APK 上限与拆分策略。
37. 发送侧 ME 声纹门控在同室多机下的误拒率（把我的话当成对方丢掉）与 host 同源去重阈值（`ptsMs` ± 200 ms + 能量）的实测。
38. 多语端侧 ASR 包（sherpa whisper small 多语 / SenseVoice 原版 zh / yue / en / ja / ko）在手机的体积与 RTF（v1.1 评估屏内日 / 韩视频原文轨）。
39. 待机档 `.mixWithOthers` 与用户音乐 / 播客共存的实际行为；`Live` 期被音乐中断后的恢复率。
40. StageHealth Probing 影子请求的费用与升回延迟；语言对包未下载时 3 s 超时 + 提示下载的体感。
41. iOS 27 Translation 框架是否开放粤语（已在 5 列出，此处补：若开放，粤语零 Key M0 的"普通话入耳"可否成立）。

**待验证——LLM 执行层（Koog，§7.13）**：
42. Koog 1.2.0 prompt executor 模块在 iosArm64 的包体增量、Ktor Darwin 引擎与项目 Ktor 3.5.2 的版本对齐；我们用到的 API 是否全在 stable 模块集内。
43. Koog DashScope 客户端是否透传 qwen-mt 的 `translation_options`（`terms` / `tm_list` / `domains`）；不透传则 qwen-mt 保留自研薄客户端。
44. Koog LiteRT 客户端的平台范围（Android 之外是否覆盖 iOS）与模型格式（LiteRT-LM）；与 Llamatik GGUF 路径二选一还是并存。
45. Koog 结构化输出在本地小模型（Qwen3.5-2B GGUF / Foundation Models）上的成功率与修复重试成本；Anthropic `output_config.format` 是否已由 Koog 覆盖。

**待验证——UI 设计语言（§7.14）**：
46. compose-cupertino / Calf 与 CMP 1.12.0、Kotlin 2.4.0 的兼容与维护状态；haze 在 iOS（Skia）上的模糊性能与"降低透明度"回退。
47. Android 端替代 SF Symbols 的符号集（Phosphor / Lucide）语义覆盖率与权重匹配；SF Pro / SF Symbols 许可边界的法务复核。
48. iOS 功能层原生化（UITabBarController + UINavigationController 外壳 + Compose 内容）在 CMP 1.12 下的滑动返回、sheet detent、键盘避让与 Compose 手势冲突；Dynamic Type 最大字号下双屏 / 气泡布局的截图矩阵。

**待验证——端侧 NMT（`core/nmt`，§7.4 / §7.7）**：
49. opus-mt zh↔en int8 在基线机（iPhone 12 / 骁龙 7 系）与旗舰机的单句延迟 P50 / P95（10 / 20 / 40 token）、常驻内存、冷启动加载时间；与 zipformer + 系统 TTS 同跑 10 分钟的发热与降频曲线（周 4 spike）。
50. opus-mt 模型许可（Helsinki-NLP 标 CC-BY-4.0，署名义务与商用条款待法务核实）；SentencePiece 模型随包分发的许可；int8 量化后与 fp32 的 BLEU / 人工可懂度差异。
51. ONNX Runtime 与 sherpa-onnx 共用同一份运行时的链接冲突（静态库符号重复、版本锁定、iOS xcframework 与 Android AAR 各自的单副本策略）；不可解时的隔离方案。
52. SentencePiece 分词的 KMP 实现选型（cinterop 官方 C++ 库 vs 纯 Kotlin 解码器）；占位符 token 在 SentencePiece 词表中的保留方式与对 NMT 输出的影响（漏译 / 错位率）。
53. Background Assets / PAD 对语言对包（每方向 ≈ 80 MB）的按需下载体验：出门预热强制预下载的完成率、无 GMS 手动导入 `.sherpa-pack` 的可用性。
54. 更多 opus-mt 语言对（en↔ja / en↔ko / 欧语）的可用模型与质量（v1.1 评估）。

**待决策（产品 / 工程）——v1 保留**：
1. （已决）系统识别引擎（SpeechTranscriber / SFSpeechRecognizer / Android SpeechRecognizer）移出 MVP，降为 v1.1 可选插件，MVP 端侧识别统一 sherpa-onnx。
2. 会议场景零 Key 时是否把 Foundation Models / ML Kit GenAI 的"部分风格"成稿作为默认，还是只用规则版时间轴要点（预设：系统模型可用即用，不可用用规则版）。
3. 是否在 v2 评估 zipformer transducer 作为端侧定稿引擎以吃热词（牺牲 Paraformer 的准确率换取术语回流；实时快路径已在用 zipformer，其热词收益可作先验）。
4. 本地消费闸门默认月度上限（预设 ¥30）与提额交互；实时场景达上限时降离线档而非停止会话（预设：降档）。
5. 方言家庭卡是否在 v1.1 火山接入后向上海 / 闽南家庭开放 CloudOnly（需 Key），还是等端侧大包。
6. 两端英文定稿统一用 sherpa whisper base.en int8（不再依赖系统听写）；是否值得换更大的英文包（体积 vs WER）。
7. `.scene` 深链与二维码是否值得在 v1.1 投入（微信内不可用的前提下，仅面对面场景；P2P 配对码同理）。
8. Opus 是否用于落盘（预设：否，仅 P2P 传输；AAC ≈14 MB/h 成投诉点后再议）。
9. 团队规模若少于 6 人，MVP 先砍什么（见 10）。

**待决策——旗舰新增**：
10. **4 人团队时的 MVP 范围**：预设 A = 屏内只交付 S4 / S8，Android S1 / S3 与 M4 移 v1.1，仍 18 周；备选 B = 维持全范围延至 24 周。
11. （已决）面对面对话零 Key 时两端统一走端侧 NMT 离线档（中 ↔ 英），不再有"同语字幕"模式；语言对包未下载时只在入口提示下载。
12. 判向默认策略：自动（声纹 + 输出脚本）还是固定方向 + 滑动翻转；H12 灰度数据（手动翻转率 > 25%）决定（预设：自动）。
13. M1 姿态触发是否默认开启（预设：开，可关；误触发率 > 5% 则改默认关）。
14. 对方音频是否提供"本会话落盘"开关（预设：不提供，仅存文本；采访场景除外）。
15. 在线档是否允许云 TTS 默认开启（预设：否，系统 TTS 默认；方言音色需显式选）。
16. iOS S1 实验层四项验证有任一不通过时：整体不做 vs 只做"纯转发 + 云 ASR"（预设：整体不做，主线 S4 / S8 不受影响）。
17. Android S1 的默认载体：PiP（预设）vs 首次即引导 Overlay；OEM 悬浮窗拒绝率 > 40% 时维持 PiP 默认。
18. 屏内三子卡（看视频 / 看课程 / 看剧）是否在 MVP 只保留一张"看视频字幕"（预设：是，子卡 v1.1）。
19. M11-web 网页字幕是否值得在 v2 投入（http 非安全上下文限制、说话方费用上升；预设：做，但限 D3 听者 ≤ 5）。
20. 围桌共录是否完全并入 M10（预设：是，v2 不再单独立项）。

**待决策——本次修订新增**：
21. whisper-tiny LID 包（≈ 98 MB，仅二分类判向，比 zipformer 大 3 倍）是否引入（预设：不引入；判向用声纹 + ASR 输出脚本，粤语路径用 SenseVoice 首 token）。
22. 讲座旁听卡放 v1.1（与课堂卡同期）还是 MVP 折叠在"更多场景"（预设：v1.1，MVP 用面对面对话卡的 M0 代替）。
23. 逐段授权档下 P2P 音频出站需会话级一次授权还是按"局域网不算出站"直接允许（预设：会话级一次授权，A.1 已按此书写）。
24. 慢路径是否允许用户切换强模型（Sonnet 5 / Opus 5）（预设：允许，默认当前 BYOK LLM；零 Key 走系统模型 / 本地 GGUF / 规则层阶梯）。
25. 周 10 若 P50 > 1.8 s，对外文案写"约 2 s"还是不写延迟数字（预设：不写数字，只写"句尾后很快入耳"）。
26. 屏内日 / 韩视频原文轨：引入多语端侧 ASR 包（v1.1 评估）还是只靠 Key（预设：MVP 先靠 Key，入口即提示；系统识别插件仅 v1.1 可选）。
27. 待机档是否默认随旅行 / 商务场景自动开启（预设：需用户在预热引导中按一次；8 小时电量实测 > 10% 则改为提示式）。
28. LLM 执行层采用 Koog 还是维持 v1 自研 Provider 抽象（预设：Koog，仅 prompt executor 模块；附录 B 42–45 任一不通过则回退自研，`LlmGateway` 接口不变）。
29. 标签栏 4 个还是 5 个（预设：4——场景 / 实时 / 资料库 / 设置，屏内入口放实时 Tab 的分段控件与场景卡；灰度期屏内会话占比 > 30% 则升为独立 Tab）。
30. Android 字体：系统默认 vs 内置 Inter + 思源黑体（预设：系统默认，只统一刻度；SF Pro 不得内置）。
31. TTS 是否也统一到 sherpa-onnx（Matcha + Vocos）以做到两端一致（预设：否——系统 TTS 默认、Matcha 只作无系统语音包时的兜底；v1.1 按各平台语音包覆盖率与音质复评）。
32. 中 ↔ 英 NMT 语言对包（双向约 160 MB）随包内置还是按需下载（预设：按需下载，出门预热强制预下载；若灰度期"首次面对面时模型未就绪"占比 > 20% 则改为随包内置并重议首装阈值）。
33. 系统识别 / 系统翻译插件在 v1.1 是否实现（预设：先实现 Android SpeechRecognizer 与 ML Kit Translation 的评估版，iOS 侧视 Swift 注入成本再定；一律默认关闭）。

**结语**：v2 把"场景"保留为唯一主语，把"实时翻译"立为旗舰——屏外面对面：说话的人只管说，听的人戴上耳机；屏内媒体：手机里播什么就翻什么，Android 走系统抓取，iOS 走文件与诚实的实验层；两者共用一条双速管线与同一份本地知识库，多设备之间默认只传说话方的音频流（三种显式例外可见、听者的云端识别对说话方可见）。方案 0 的三载体与留言翻译作为场景的输出层（新增对话卡片与 .srt），方案 1 的 Vault + 指标体系作为场景之下的数据层（新增对话纪要与观影词汇），方案 2 的账本、锁定与诚实分层作为所有场景共享的信任层（新增局域网类目、对方音频不落盘、屏内 tier 文案）。没有服务器、没有账号、没有内置 Key、没有域名依赖、没有自有分发端点、没有转播第三方音频、没有绕过版权；零 Key 能做什么、不能做什么写在摘要第二段；用户留下的原因是本地资产越用越像自己——每一场对话、每一集视频都让下一次翻译更准，而这些资产随时可以带走。
