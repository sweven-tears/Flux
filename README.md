# Flux

> 原生 Android LLM 聚合路由客户端：把手机上配置的多家模型厂商聚合成一个 OpenAI 兼容网关，也能在本机直接对话。

<p>
<img alt="platform" src="https://img.shields.io/badge/platform-Android%2026%2B-3DDC84">
<img alt="kotlin" src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF">
<img alt="compose" src="https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.12-4285F4">
<img alt="version" src="https://img.shields.io/badge/version-0.26.0-blue">
<img alt="license" src="https://img.shields.io/badge/license-AGPL--3.0-orange">
</p>

**English (short)** — Flux is a native Android client that aggregates the LLM providers you configure on your
phone into a single OpenAI-compatible gateway served over the LAN (Ktor + mDNS), while also working as a local
chat app. It ships smart routing (aggregated `flux-auto` model with failure-classified fallback and health-based
cooldowns), route groups, tool calling with a QuickJS sandbox, image/video generation, request logs and a global
network log. Built with Kotlin + Jetpack Compose, single `:app` module, no DI framework, AGPL-3.0 licensed.

---

## 目录

- [它解决什么问题](#它解决什么问题)
- [功能概览](#功能概览)
- [快速开始](#快速开始)
- [局域网网关](#局域网网关)
- [智能路由](#智能路由)
- [工具系统](#工具系统)
- [支持的厂商](#支持的厂商)
- [项目结构](#项目结构)
- [数据与隐私](#数据与隐私)
- [测试与质量](#测试与质量)
- [参与贡献](#参与贡献)
- [许可证](#许可证)

## 它解决什么问题

同一台手机上，你可能同时握有 OpenAI、DeepSeek、智谱、通义、SiliconFlow、OpenRouter、Gemini、Grok、本机 Ollama
等多家的 Key，额度、速率、可用性各不相同。把它们分散配置到每个客户端里既麻烦，也浪费了「谁快用谁」的可能。

Flux 的做法是：**厂商只在手机上配一次，出口只有一个**。

- 对局域网内的其他设备（电脑、平板、另一台手机、任何支持自定义 OpenAI Base URL 的客户端），手机就是一个
  `http://<手机IP>:8787/v1` 的 OpenAI 兼容服务。
- 对本机用户，它是一个完整的聊天客户端，界面、会话、工具调用、图像/视频生成都在手机上进行。
- 两条链路共用同一套路由与协议代码，因此在任意一侧调优，另一侧同时受益。

## 功能概览

| 能力 | 说明 |
| --- | --- |
| 多厂商聚合 | 一个厂商可存多把 API Key，按 `activeKeyId` 切换；模型带 6 个模态开关与上下文长度 |
| 局域网网关 | Ktor（Netty）监听 `0.0.0.0`，mDNS 广播 `flux.local`，端点覆盖 chat completions 与视频生成 |
| 智能路由 | `flux-auto` 聚合全部合格模型，按健康度三桶排序，首字超时逐轮回退 |
| 路由分组 | 同族模型自动归为 `flux-<基名>` 虚拟模型，组内成员按优先级跨厂商切换 |
| 工具调用 | 6 个内置工具 + 自定义 HTTP/JS 工具，带人工审批与出站地址策略 |
| 图像与视频 | 支持图片生成与 6 类视频接口形态（Sora / Agnes / Kling / MiniMax / Runway / Veo） |
| 可观测性 | 请求日志、模型异常记录、全局网络日志、单请求错误详情与一键复制 |
| 设备鉴权 | 网关访问需在 App 内逐台批准，一次性令牌，数据库只存 SHA-256 摘要 |
| 配置备份 | 全量 JSON 快照导入导出，默认不含 API Key，导入自动降权 |

## 快速开始

### 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK Platform | 36（`compileSdk 36`，由 quickjs-kt 的 `minCompileSdk` 决定） |
| Build Tools | 随 SDK 安装即可 |
| Gradle | 8.13（仓库自带 wrapper，无需预装） |
| 最低运行系统 | Android 8.0（API 26） |
| 目标系统 | Android 15（API 35） |

### 构建

```bash
# Windows 用 gradlew.bat 替代 ./gradlew
./gradlew assembleDebug      # 输出 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 需要 keystore.properties，见下
./gradlew test               # JVM 单元测试
./gradlew lint               # Android Lint
```

Release 签名从仓库根目录的 `keystore.properties` 读取（该文件已被 gitignore）：

```properties
storeFile=flux-release.jks
storePassword=******
keyAlias=flux
keyPassword=******
```

文件不存在时产出的 release 包未签名。签名、发版与 Room 迁移纪律的细节见
[项目 Wiki](http://192.168.1.27:3000/sweven/flux/wiki)。

### 首次使用

1. 打开 App 进入「厂商」页，选择预设厂商或新增自定义厂商，填入 Base URL 与 API Key。
2. 拉取模型列表（或手工添加），为模型打开需要的模态开关。
3. 回到「聊天」页选择模型，即可对话。
4. 需要给局域网其他设备用时，进入「局域网」页开启服务。

## 局域网网关

开启后，手机在 `http://<手机IP>:8787/v1` 提供 OpenAI 兼容接口；mDNS 可用时还会广播 `flux.local`，
同一局域网内可直接用 `http://flux.local:8787/v1`。

### 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/` | 服务信息（名称、mDNS 主机名、模型 ID 格式、鉴权说明） |
| `GET` | `/v1/models` | 聚合后的模型列表：各厂商模型 + `flux-auto` + 各路由分组 |
| `POST` | `/v1/chat/completions` | 聊天补全，支持 `stream: true` 的 SSE 与 `tool_calls` |
| `POST` | `/v1/videos` | 提交视频生成任务（Sora 兼容形态） |
| `GET` | `/v1/videos/{id}` | 查询任务状态 |
| `GET` | `/v1/videos/{id}/content` | 下载生成的 mp4 |

### 模型 ID 约定

- 真实模型：`厂商别名/模型名`，例如 `deepseek/deepseek-chat`。
- `flux-auto`：把所有已启用且合格的模型当作一个池子，按智能路由策略挑选（需在「局域网」页单独开启）。
- `flux-<基名>`：路由分组。同族模型会按「规范化 ID 基名」自动归组（小写、去厂商命名空间、
  去尾部版本/日期后缀，如 `deepseek-v4-flash`），组内按 `sortOrder` 优先级跨厂商切换。

### 设备配对

网关不是开放代理，每个访问方都要在 App 内被批准一次：

1. 每个请求都必须携带**稳定的** `X-Device-Id`（缺失会直接 `400`）；建议用设备名或随机 UUID 并在客户端持久化。
2. 首次出现时服务端登记为 `PENDING`，手机收到通知；App 内批准后弹出一次性 256-bit 令牌。
3. 之后的请求带上 `X-Device-Token: <令牌>` 或标准的 `Authorization: Bearer <令牌>`。

令牌可随时撤销；数据库中只保存令牌的 SHA-256 摘要。同一来源 IP 每分钟鉴权失败达到 10 次返回 `429`，
被禁止的设备返回 `403`。

### 调用示例

```bash
curl http://192.168.1.20:8787/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <你的设备令牌>' \
  -d '{
        "model": "flux-auto",
        "messages": [{"role": "user", "content": "用一句话解释什么是向量数据库"}],
        "stream": true
      }'
```

任何支持「自定义 OpenAI Base URL」的客户端都可以直接接入：Base URL 填 `http://<手机IP>:8787/v1`，
API Key 填设备令牌，模型名按 `/v1/models` 返回的 ID 填。

## 智能路由

`flux-auto` 不是简单的随机负载均衡，而是一条带失败分类的回退链：

1. 取全部候选模型 → 提取模态/工具/推理/上下文等特征。
2. 按请求需求做资格过滤（要图片就排除纯文本模型，要工具就排除不支持 tool_calls 的模型）。
3. 依据健康度分三桶排序：健康 → 降权 → 冷却。
4. 首轮只取最多 3 个最符合「极速」档的候选者，避免一上来就把慢模型排前面。
5. 逐轮执行，按失败类型决定「重试同一模型 / 换下一个 / 直接终态」。
6. 记录首字时间（EWMA，权重 7:3）与失败次数，供下一轮排序使用。

失败分类的处理差异是这里最值得看的部分：

| 失败类型 | 典型状态码 | 计入连续失败 | 处理 |
| --- | --- | --- | --- |
| `PARAM` | 400 / 422 | 否 | 参数问题，立即终态，换模型也没用 |
| `AUTH` | 401 / 403 | 否 | 密钥失效，全轮耗尽后才终态 |
| `MODEL` | 404 | 否 | 模型不存在，换下一个 |
| `RATE_LIMIT` | 429 | 否 | 直接冷却该模型，尊重 `Retry-After` |
| `UPSTREAM` | 408 / 5xx | 是 | 上游故障，换下一个 |
| `NETWORK` | 连接失败 | 是 | 网络问题，换下一个 |
| `TIMEOUT` | 首字超时 | 是 | 按档位超时判定，换下一个 |
| `EXHAUSTED` | — | — | 所有候选耗尽，返回最后一次错误 |

可调参数（「智能路由」页）：

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `smartFastFirstTokenMs` | 2000 | 「极速」档首字预算（毫秒） |
| `smartFirstTokenTimeoutSeconds` | 30 | 「聊天」档首字超时 |
| `smartLeisureFirstTokenSeconds` | 120 | 「随缘」档首字超时 |
| `smartRouteMaxRounds` | 3 | 默认轮巡轮数 |
| `smartCooldownThreshold` | 2 | 连续失败多少次进入冷却 |
| `smartCooldownSeconds` | 60 | 冷却时长 |

健康度是进程级状态，不持久化——重启后重新观察，避免把一次网络抖动永久记在账上。

## 工具系统

模型可以调用工具，但每个工具的去留由你决定。

**内置工具**（都在「工具中心」里单独开关）：

| 工具 | 默认 | 作用 |
| --- | --- | --- |
| `get_time_info` | 开 | 当前时间、时区、日期换算 |
| `web_search` | 开 | 免密钥多源搜索：Bing → DuckDuckGo → 可选 SearXNG 回退 |
| `clipboard` | 关 | 读写剪贴板 |
| `eval_javascript` | 关 | QuickJS 沙箱执行 JS（ES2020，10 秒超时 / 64MB 内存 / 256KB 栈） |
| `fetch_url` | 关 | 抓取网页并转为纯文本 |
| `script_tool_dev` | 关 | 让模型自己创建/修改自定义工具的草稿工具 |

**自定义工具**：存于 `custom_tools` 表，支持 HTTP 模板与 JS 脚本两种执行器，默认 `needsApproval = true`。

**审批与出站策略**：

- 需要审批的调用会排队（FIFO ticket），等待超过 5 分钟按拒绝处理；工具调用轮数上限默认不限，
  可在设置里收紧（最大 50）。
- 出站默认 `PUBLIC_ONLY`：禁止访问内网地址、本机回环与云元数据端点，避免工具被诱导去打内网服务。
- 唯一允许访问局域网地址的入口是用户手动确认过的 SearXNG 端点。

## 支持的厂商

预设直接选，也可以新增自定义厂商（任何 OpenAI 兼容端点都能接）：

| 厂商 | Base URL | 协议 |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | OpenAI |
| DeepSeek | `https://api.deepseek.com/v1` | OpenAI |
| Kimi / Moonshot | `https://api.moonshot.cn/v1` | OpenAI |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | OpenAI |
| 通义千问 Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenAI |
| SiliconFlow 硅基流动 | `https://api.siliconflow.cn/v1` | OpenAI |
| OpenRouter | `https://openrouter.ai/api/v1` | OpenAI |
| Anthropic | `https://api.anthropic.com/v1` | Anthropic |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | OpenAI（官方兼容层） |
| xAI Grok | `https://api.x.ai/v1` | OpenAI |
| Ollama | `http://127.0.0.1:11434/v1` | OpenAI |

上游协议实现了 `openai` / `gemini` / `anthropic` 三种，厂商详情页可切换，并支持自定义请求头。

## 项目结构

单模块 `:app`，包按功能分层，不使用 DI 框架（全局单例在 `App.kt` 里手工装配）。

```text
app/src/main/java/cn/luoluna/sweven/ai/
├── App.kt / MainActivity.kt      应用入口、全局单例装配
├── data/
│   ├── AppSettings.kt            SharedPreferences 包装的全局设置
│   ├── BackupManager.kt          配置快照导入导出
│   ├── db/                       Room：16 实体 / 16 DAO，v23
│   ├── preset/                   预设厂商表
│   ├── repo/                     智能路由：ModelRouter / SmartRouteExecutor / SmartHealth / FailurePolicy
│   └── security/                 Keystore 密钥库与字段加密
├── net/                          ChatProtocol（OpenAI/Gemini/Anthropic）、ProviderClient、
│                                 NetworkLog、OutboundPolicy、TokenBudget、video/
├── server/                       局域网网关：LanServer（Ktor 路由）、LanService、
│                                 MdnsAdvertiser、DeviceAuthManager、VideoJobService
├── tools/                        FluxTool 抽象、内置工具、自定义工具执行器、搜索链
└── ui/                           Compose 界面：chat / provider / lan / settings / tools / navigation
```

- `app/src` 下约 150 个 Kotlin 文件（main 120 / test 29 / androidTest 1），20 条导航路由，
  底部三个 Tab：聊天 / 厂商 / 局域网。
- 本机聊天与局域网网关共用 `ModelRouter` + `SmartRouteExecutor` + `ChatProtocol`，改一处两条链路同时生效。
- 结构性的细节文档维护在项目 Wiki（架构、协议、数据层、工具系统、UI 分层、智能路由等），
  代码仓库本身不含 `wiki/` 目录；改动前建议先读对应页面。

## 数据与隐私

- **API Key 加密**：使用 Android Keystore 的 AES-256-GCM 加密后落库，加密失败即失败关闭，不回退明文。
- **网关令牌**：只保存 SHA-256 摘要，明文令牌仅在批准时展示一次。
- **本地存储**：Room 数据库（v23，无 destructive fallback，缺迁移会显式失败而不是静默清库），
  会话与附件留在应用私有目录。
- **网络日志**：可全局关闭；日志中的敏感字段（令牌、Key、Authorization 头）在写入前脱敏。
- **备份**：导出默认不含 API Key，勾选包含时会二次确认；导入默认降权，避免不信任来源的配置直接生效。
- **明文 HTTP**：为兼容自建/局域网端点，应用允许 cleartext 流量（`usesCleartextTraffic=true`），
  公网厂商请使用 `https://`。
- **权限**：仅申请网络、前台服务（数据同步）、通知、Wi-Fi 组播（mDNS）、电池优化豁免与
  旧系统写外部存储（≤API 28）。没有后台定位、通讯录、相册扫描之类的权限。

## 测试与质量

```bash
./gradlew test                                # 29 个测试类 / 181 个用例
./gradlew :app:testDebugUnitTest --tests "<全限定类名>"   # 跑单个类
./gradlew :app:connectedDebugAndroidTest      # 仪器测试（需连接设备）
```

- JVM 单元测试覆盖：消息与流式工具调用组装、QuickJS 沙箱、搜索解析与回退链、加密与脱敏、
  设备令牌竞态、出站策略、审批队列、聊天皮肤与样式编解码、执行轨迹派生等。
- 仪器测试 5 例，覆盖真实 Room 迁移（v22→v23）、加密落库、导入事务回滚等只能在设备上验证的部分。
- 当前没有 CI；Compose 界面仍需 `assembleDebug` 后真机手测。无 ktlint/detekt，风格跟随现有文件。
- 代码约定：注释与 UI 文案为中文，决策理由写在源码 KDoc 里（改代码前先读注释）。

## 参与贡献

1. 从 `master` 开分支，改动尽量收敛在单一主题内。
2. 提交前跑 `./gradlew test`，涉及 UI 的再跑 `assembleDebug` 手测。
3. 改动影响到结构（模块、类、常量、路由、表结构）时，同步更新项目 Wiki 对应页面。
4. 加表/加列必须配套 Room 迁移与旧库升级回归，不允许改版本号后靠清库解决。
5. 提交信息沿用现有风格：`type(scope): 中文描述`，例如 `fix(lan): 修复令牌撤销竞态`。

问题与建议请提到 [Gitea 仓库](http://192.168.1.27:3000/sweven/flux) 的 Issue。

## 许可证

本项目以 **GNU Affero General Public License v3.0（AGPL-3.0）** 发布，全文见 [LICENSE](LICENSE)。

```text
Flux - 原生 Android LLM 聚合路由客户端
Copyright (C) 2026 sweven <sweventears@163.com>

本程序是自由软件：你可以依据自由软件基金会发布的 GNU Affero 通用公共许可证
（第 3 版）条款重新分发和/或修改它。本程序按"原样"提供，不附带任何担保，
包括但不限于适销性或特定用途适用性的默示担保。
```

简单说：

- 你可以自由使用、修改、分发，包括商用。
- 分发修改版时必须以同一许可证开放源代码，并保留版权声明。
- **AGPL 的特别之处**：如果你的修改版通过网络向他人提供服务（本项目本身就是网络服务端），
  使用者也必须能够获得该版本的源代码。
- 若不希望受 AGPL 约束（例如闭源分发或嵌入商业产品），需要另行获得商业授权，请通过上方邮箱联系作者。

### 第三方与致谢

- 本项目部分实现参考并移植自 [RikkaHub](https://github.com/rikkahub/rikkahub)（AGPL-3.0），
  包括 JS 沙箱工具的接入方式、搜索服务的多源回退结构等；相关源文件的 KDoc 中已注明来源。
  按 AGPL-3.0 的要求，本项目的完整源代码同样以 AGPL-3.0 提供。
- 依赖：Jetpack Compose、Room、OkHttp、Ktor、kotlinx.serialization、JmDNS、quickjs-kt 等，
  各自遵循其原有开源许可证，版本集中在 `gradle/libs.versions.toml`。
- 界面风格受 Material Design 3 与现代 LLM 客户端启发，图标来自 Material Icons。
