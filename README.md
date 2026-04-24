# WordbookGen / AI Wordbook Creator

WordbookGen is a Java Swing desktop application that turns word lists into structured dictionary entries with OpenAI-compatible LLM providers. It supports JSON/CSV export, multi-provider failover, checkpoint resume, script hooks, strict/compatible response parsing, and cancellable HTTP requests.

WordbookGen 是一个 Java Swing 桌面工具，用于调用兼容 OpenAI Chat Completions 协议的大模型服务，将单词列表批量生成结构化词典数据。项目支持 JSON/CSV 导出、多 Provider 故障切换、断点续传、脚本 Hook、严格/兼容两种响应解析模式，以及可取消的异步 HTTP 请求。

## Features / 功能

- Multi-provider configuration and failover.
- 多 Provider 配置与故障切换。
- OpenAI Chat Completions compatible request format.
- 兼容 OpenAI Chat Completions 请求格式。
- Strict mode for standard response envelopes and strict JSON content.
- 严格模式：适合需要标准响应包络和严格 JSON 内容的场景。
- Compatibility mode for direct JSON, `output_text`, content arrays, and other non-standard provider shapes.
- 兼容模式：可处理直接返回 JSON、`output_text`、content 数组等非标准 Provider 返回。
- Automatic JSON repair request when compatible mode detects malformed model output.
- 兼容模式下，当模型输出 JSON 异常时可自动发起修复请求。
- Batch processing with retry, backoff, quota limiting, rate-limit handling, and adaptive batch splitting.
- 批量处理，支持重试、退避、配额限制、限流处理和自适应拆批。
- Checkpoint resume after interruption.
- 断点续传。
- Pause, resume, and stop controls; in-flight HTTP requests are cancellable async futures.
- 暂停、继续、停止控制；正在执行的 HTTP 请求可通过异步 Future 取消。
- JSON and CSV output.
- JSON 和 CSV 输出。
- External script hooks before request, after raw response, and after parsed result.
- 支持请求前、原始响应后、解析结果后的外部脚本 Hook。
- Config save/load from the default folder or a user-selected config folder.
- 支持从默认配置目录或用户指定配置目录加载/保存配置。

## Requirements / 环境要求

- JDK 17+
- Maven 3.8+

## Quick Start / 快速开始

Run from an IDE / IDE 启动主类：

```text
com.wordbookgen.app.WordbookGenApplication
```

Run with Maven / 使用 Maven 运行：

```bash
mvn -q -DskipTests compile
mvn exec:java
```

Build and run the shaded jar / 打包并运行可执行 jar：

```bash
mvn -q -DskipTests package
java -jar target/wordbook-gen-1.0.0-shaded.jar
```

## Basic Usage / 基本使用

1. Prepare a plain text word list, one word per line.
2. Add at least one Provider in the Provider panel.
3. Set input path, output path, output format, language, batch size, retries, and timeout.
4. Enable "compatible non-standard provider responses" for providers that return direct JSON or non-standard response shapes.
5. Disable that compatibility switch when a downstream workflow requires strict OpenAI-style response envelopes and strict JSON content.
6. Click Start.

中文步骤：

1. 准备一个纯文本单词表，每行一个单词。
2. 在 Provider 区域至少配置一个服务商。
3. 设置输入路径、输出路径、输出格式、语种、批大小、重试次数和超时时间。
4. 如果服务商可能返回直接 JSON 或非标准结构，请开启“兼容非标准 Provider 返回”。
5. 如果下游流程对标准响应结构要求较高，请关闭该兼容开关。
6. 点击开始。

Default UI settings path / 默认 UI 配置路径：

```text
~/.wordbookgen/ui-settings.json
```

The UI can also load/save `ui-settings.json` from a selected config folder. This file may contain API keys and should stay outside the repository.

界面也支持从用户选择的配置文件夹读取和保存 `ui-settings.json`。该文件可能包含 API Key，请不要放入仓库。

## Output Schema / 输出结构

Each dictionary item must include these fields / 每个词典条目必须包含以下字段：

- `单词`
- `音标`
- `词性`
- `核心含义`
- `词形变形`
- `常用词组`
- `经典例句`
- `词缀解析`
- `辅助记忆小故事`

Nested bilingual fields use / 双语子字段使用：

- `英文`
- `中文`

## Project Structure / 项目结构

```text
src/main/java/com/wordbookgen/app
  Application entry point.
  应用入口。

src/main/java/com/wordbookgen/core
  Job orchestration, batching, pause/resume/stop, checkpoints, prompt building.
  任务调度、分批、暂停/继续/停止、断点、提示词构建。

src/main/java/com/wordbookgen/core/provider
  Provider client, model discovery, retry classification, quota limiting.
  Provider 客户端、模型发现、重试分类、配额限制。

src/main/java/com/wordbookgen/core/io
  Word input reader and JSON/CSV output writer.
  单词输入读取和 JSON/CSV 输出。

src/main/java/com/wordbookgen/core/model
  Job config, provider config, checkpoint data, output schema constants.
  任务配置、Provider 配置、断点数据、输出字段常量。

src/main/java/com/wordbookgen/core/script
  External script hook executor.
  外部脚本 Hook 执行器。

src/main/java/com/wordbookgen/ui
  Swing UI, config persistence, provider cards, connectivity test.
  Swing 界面、配置持久化、Provider 卡片、连通性测试。

examples/hooks
  Hook templates and common post-processing scripts.
  Hook 模板和常用后处理脚本。
```

## Design Notes / 设计说明

The UI builds a `JobConfig` and starts `WordbookJobEngine`. The engine reads the word list, restores a compatible checkpoint, splits pending words into batches, and dispatches workers through `ProviderRouter`.

界面负责构造 `JobConfig` 并启动 `WordbookJobEngine`。任务引擎读取单词表、恢复兼容的断点、将待处理单词分批，并通过 `ProviderRouter` 调度 Provider。

`ProviderClient` builds OpenAI-compatible request bodies, sends HTTP requests with `HttpClient.sendAsync`, and polls the future so stop requests can cancel in-flight HTTP calls. It extracts model content, parses JSON, validates required fields, optionally asks the model to repair malformed JSON, and then runs post-parse hooks.

`ProviderClient` 构造兼容 OpenAI 的请求体，使用 `HttpClient.sendAsync` 发送 HTTP 请求，并轮询 Future 以便停止任务时取消正在执行的请求。随后它会提取模型内容、解析 JSON、校验必需字段，在兼容模式下可请求模型修复畸形 JSON，最后执行解析后 Hook。

Checkpoint files are written atomically after each completed batch. A stopped job saves the latest completed state and exits as `STOPPED` instead of treating canceled HTTP requests as normal failures.

每完成一个批次都会原子写入断点文件。停止任务时会保存最新已完成状态，并以 `STOPPED` 收口，而不是把被取消的 HTTP 请求当作普通失败。

## Extension Points / 扩展修改点

- Prompt behavior / 提示词策略：`PromptBuilder`
- Output fields / 输出字段：`DictionaryFields`
- Provider response compatibility / Provider 响应兼容：`ProviderClient.extractModelContent` and `ProviderClient.parseAsArray`
- Retry and failover / 重试与故障切换：`WordbookJobEngine.executeBatch` and `ProviderRouter`
- Checkpoint format / 断点格式：`WordbookCheckpoint` and `CheckpointStore`
- UI fields and persistence / UI 字段与配置持久化：`WordbookGenFrame`, `UiSettings`, `UiConfigStore`
- Script hooks / 脚本 Hook：`examples/hooks` and `ScriptHookExecutor`

## Script Hooks / 脚本 Hook

Hooks communicate through JSON on stdin/stdout.

Hook 通过 stdin/stdout 传递 JSON。

Supported stages / 支持阶段：

- `PRE_REQUEST`: rewrite `systemPrompt` and `userPrompt`.
- `PRE_REQUEST`：修改系统提示词和用户提示词。
- `POST_RESPONSE`: rewrite raw provider response text.
- `POST_RESPONSE`：修改 Provider 原始响应文本。
- `POST_PARSED`: rewrite parsed `entries`.
- `POST_PARSED`：修改解析后的 `entries`。

Supported languages / 支持语言：

- Python
- JavaScript
- Lua
- Java

Hook failures are treated as non-retryable configuration errors.

Hook 失败会被视为不可重试的配置错误。

## Git and Secret Hygiene / Git 与敏感信息管理

The repository includes `.gitignore` and `.gitattributes` for open-source publishing.

仓库包含面向开源发布的 `.gitignore` 和 `.gitattributes`。

Do not commit / 请勿提交：

- API keys or provider credentials / API Key 或 Provider 凭据
- `ui-settings.json`
- `.env` files / `.env` 文件
- checkpoint files / 断点文件
- generated wordbook outputs / 生成的词书输出
- `target/` build artifacts / `target/` 构建产物
- IDE metadata / IDE 元数据

Before publishing, scan staged changes / 发布前检查暂存区：

```bash
git status --short
git diff --cached
```

If a real API key was ever committed, rotate it before publishing.

如果真实 API Key 曾经被提交过，请在公开发布前立即轮换。

## Packaging and Release / 打包发布

Compile / 编译：

```bash
mvn -q -DskipTests compile
```

Package / 打包：

```bash
mvn -q -DskipTests package
```

Run the shaded jar / 运行 shaded jar：

```bash
java -jar target/wordbook-gen-1.0.0-shaded.jar
```

Recommended release artifact / 推荐发布产物：

```text
target/wordbook-gen-1.0.0-shaded.jar
```

Before a public release, choose and add a license such as MIT or Apache-2.0.

公开发布前，请选择并添加许可证，例如 MIT 或 Apache-2.0。

## Known Risks and Limitations / 潜在问题与限制

- Provider output quality still depends on model behavior and prompt compliance.
- Provider 输出质量仍取决于模型能力和提示词遵循程度。
- Compatibility mode can recover many malformed JSON responses, but strict mode intentionally rejects non-standard response shapes.
- 兼容模式可以恢复很多畸形 JSON，但严格模式会主动拒绝非标准响应结构。
- Canceling an in-flight HTTP future requests cancellation promptly, but the exact network abort behavior depends on the JDK HTTP client and provider connection state.
- 取消正在执行的 HTTP Future 会尽快发出取消请求，但实际网络中断行为取决于 JDK HTTP Client 和 Provider 连接状态。
- Large batches may hit model context or output length limits; reduce batch size if truncation or missing words occur.
- 大批次可能触发上下文或输出长度限制；如出现截断或缺词，请降低批大小。
- UI settings may contain API keys; keep config folders outside the repository.
- UI 配置可能包含 API Key，请将配置目录放在仓库外。
- Hook scripts run as local processes with the user's permissions.
- Hook 脚本以当前用户权限在本地进程中执行。
- The current UI is Swing-based and not optimized for headless/server automation.
- 当前界面基于 Swing，不适合直接作为无头服务端自动化程序。
