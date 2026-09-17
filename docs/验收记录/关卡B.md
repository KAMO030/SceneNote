# 关卡 B · 验收脚本（I4）

> 口径：本机（不含蓝牙链路）句尾 → 首帧写入 P50 ≤ 1300 ms、P95 ≤ 2000 ms；含 A2DP 后 ≈ 1.5 / 2.2 s。按用户 2026-09-17 决策：拔耳机脚本、8 h 待机电量、三姿态 × 三噪声矩阵延后到灰度期。

## 数据来源

- App 内：设置 → 诊断 → 录音自检 →「句尾 → 首音（关卡 B）」卡（`LatencyProbe`：VAD_END → MT_FIRST → TTS_FIRST → SINK_WRITE；无 TTS 的句只计翻译，标 `no_tts` / `screen`）。
- 文件：`files/bench/latency.jsonl`（每句一行，含 profile 与各阶段时间戳），`adb exec-out run-as dev.scenenote.app cat files/bench/latency.jsonl` 可拉取。

## 脚本

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| B1 | 填百炼 Key → 首页「开始仅听」→ 对方（英语）连续说 20 句（每句 5–12 词，停顿 ≥ 0.7 s） | 20 句全部出译文 + 出声；卡片 P50 ≤ 1300 / P95 ≤ 2000 ms | ⏳ 待用户 |
| B2 | 同 B1，喂 WAV 复现：`scenenote://scene/listen?autostart=1&feed=test0.wav`（同语恒等，不含云翻译） | 首音 P50 ≤ 400 ms（vivo 系统 TTS） | vivo：4 句 176–361 ms（I3 实测） |
| B3 | 面对面对话 M1：两人中英各说 10 句，混说 | 判向错误 ≤ 2 句；错判可翻转 | ⏳ 待用户 |
| B4 | M0 播译文时对方插话 > 1.5 s | 译文压低后停止、屏上标「未播完」 | 单测覆盖（FastPathTest）；真机 ⏳ |
| B5 | M1 竖起手机 1.5 s / 放平 1 s | 自动进 M1 / 回 M0（设置里可关） | ⏳ 待真机 |
| B6 | 30 分钟连续 M0 | 无崩溃 / 无卡死；热状态阶梯有日志 | ⏳ 待用户 |
