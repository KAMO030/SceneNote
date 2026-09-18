# 场记 SceneNote 产品介绍页

面向用户的萌系介绍页（实机截图 + 吉祥物记记酱 + 全页动画）。线上版本：https://claude.ai/artifact/LXmLsfFDNFtefK3sV4CbHB

```
docs/介绍页/
  index.html        页面本体，双击即可打开（字体从 Google Fonts 加载，离线时回退系统字体）
  i18n.js           英 / 日 / 韩译文（中文底稿写在 index.html 里）；页面按浏览器语言自动选，右上角可切换，也可用 ?lang=en|ja|ko|zh 指定
  img/*.png         13 张截图（除 live.png 是 vivo 外均为 iPhone 17 Pro 模拟器，高 1200）
  video/demo.mp4    演示视频（106 s，588×1278，H.264；面对面 / 速译 / 视频字幕 / 会议纪要 / 主题色 / 界面语言，录制方法见 演示脚本.md）
  video/poster.jpg  演示视频封面
```

## 改文案

- 中文直接改 `index.html` 里带 `data-i18n="键名"` 的元素；其它语言改 `i18n.js` 里同名的键（图片 alt 用 `alt:` 前缀，aria-label 用 `aria:` 前缀，记记酱台词在 `lines`）。
- 新增一段文字时，给元素加 `data-i18n` 并在 `i18n.js` 三种语言里都补上；漏掉的键会回退显示中文。
- 记记酱是 `<symbol id="jiji">`（猫耳 + 圆框眼镜 + 异色瞳 + 天蓝开衫），表情用 CSS 变量切换：`--eo/--eh/--ew` 睁眼 / 眯眼笑 / 眨眼，`--mw/--mo` ω 嘴 / 张嘴，`--note` 音符。小头像用 `viewBox="56 40 208 208"` 裁到脸部。

## 更换演示视频

1. 把视频放到 `docs/介绍页/video/demo.mp4`（H.264 + AAC 的 mp4，横屏竖屏都行，页面会按视频比例自适应）。
2. 可选：封面图放 `docs/介绍页/video/poster.jpg`，没有就用视频第一帧。
3. 打开 `index.html`，「演示视频」章节会自动从占位卡切换成播放器，不用改页面。

线上 artifact 要另行发布视频文件（单个文件 ≤ 15 MB）：让 Claude 用 Artifact `files` 参数把 `video/demo.mp4` 发布到同一个 URL；超过 15 MB 就只放在仓库镜像里。

## 本地预览

`.claude/launch.json` 里有 `intro-page`（python http.server 8765 服务本目录）；或直接双击 `index.html`。
