# 闪剪 FlashCut

手机端的智能视频剪辑 App：导入多段素材，由大模型理解内容并生成可编辑的剪辑方案，在设备本地完成渲染导出。

![version](https://img.shields.io/badge/version-0.14.0-7B5CFF)
![platform](https://img.shields.io/badge/Android-8.0%2B-34D399)
![kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7B5CFF)

## 核心理念

**原片不出手机，只把镜头摘要发给大模型。**

```
本地: 抽帧 → 切镜 → 打分 → 得到镜头清单
云端: 镜头清单 + 用户意图 → 返回 JSON 剪辑脚本（EDL）
本地: 按脚本用 Media3 渲染成片
```

好处是隐私安全、省流量、响应快，且大模型不可用时能自动降级到本地规则剪法。

## 功能

| 模块 | 状态 |
|---|---|
| 多段素材导入 | ✅ 支持 1080P/4K、HEVC |
| 本地镜头切分 | ✅ 亮度突变检测 + 清晰度打分 |
| AI 剪辑脚本 | ✅ OpenAI 兼容接口，可配任意服务商 |
| 模板与特效 | ✅ 4 套风格，可调自动特效 |
| 配音与配乐 | ⚠️ 参数配置完成，TTS 待接入 |
| 字幕 | ✅ AI 旁白字幕，支持导出 SRT |
| 多轨精剪 | ✅ 画面轨 / 旁白轨 / 音乐轨 |
| 导出 | ✅ Media3 硬件编码，存入相册 |

## 快速开始

### 环境要求

- JDK 17
- Android SDK（compileSdk 35，build-tools 35.0.0）
- Gradle 8.13

### 构建

```bash
# 配置大模型（可选，也可安装后在 App 内填写）
export WORKBUDDY_API_KEY="your-api-key"

./gradlew :app:assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

### 配置大模型

App 内「我的 → 大模型 API」支持任意 OpenAI 兼容服务：

- DeepSeek 官方
- 通义千问
- Kimi
- 智谱 GLM
- 豆包
- 自建服务（填 Base URL 即可）

填入 Base URL、API Key 后点「拉取可用模型」，即可从列表中点选模型。内置「一键诊断连接」会分四步告诉你哪里不通。

## 项目结构

```
app/src/main/java/com/flashcut/app/
├── ai/          镜头分析与脚本生成
│   ├── ShotAnalyzer.kt     本地抽帧、切镜、打分
│   └── ScriptGenerator.kt  提示词构造与响应解析
├── llm/         OpenAI 兼容客户端
│   ├── LlmClient.kt        对话、模型列表、诊断
│   ├── LlmConfig.kt        多服务商配置与密钥存储
│   └── EndpointProbe.kt    端点自动探测
├── media/       媒体处理
│   ├── VideoExporter.kt    Media3 渲染
│   ├── MediaProbe.kt       元数据与缩略图
│   ├── MediaSaver.kt       存入相册
│   └── SrtWriter.kt        字幕导出
├── ui/          界面
│   ├── editor/             多轨时间线
│   └── llm/                大模型配置
├── dev/         调试工具
│   ├── SelfTest.kt         端到端自检
│   └── UiAuditor.kt        自动 UI 审计
└── data/        数据模型
```

## 技术栈

- Kotlin 2.0.21 + ViewBinding（不使用 Compose）
- Media3 Transformer 1.5.1 渲染
- 协程处理耗时任务
- 无第三方网络库，用 `HttpURLConnection`

## 文档

- [开源调研](docs/开源调研.md) — 现有开源方案对比
- [技术方案](docs/技术方案.md) — 架构设计与关键决策
- [大模型配置方案](docs/大模型配置方案.md) — 多服务商适配
- [原型说明](docs/原型说明.md) — 界面设计
- [界面还原对照](docs/界面还原对照.md) — 原型与实现的逐屏对照
- [环境搭建指南](docs/环境搭建指南.md) — 含手机端构建

## 原型

`prototype/index.html` 是可交互的 HTML 原型，15 个页面，浏览器直接打开即可。

## 已知限制

- 端侧语音识别（ASR）未接入，原声字幕不可用
- TTS 配音未接入，成片暂无 AI 配音
- 自动特效目前只有转场，调色与变速待实现
- API Key 存储在本地（当前为简单加密，生产环境应改为 Keystore）

## License

MIT
