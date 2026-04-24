# WordbookGen / AI Wordbook Creator

WordbookGen is a Java Swing desktop application and headless CLI tool that turns word lists into structured dictionary entries with OpenAI-compatible LLM providers. It supports JSON/CSV export, multi-provider failover, checkpoint resume, script hooks, strict/compatible response parsing, optional truncated-output continuation, and cancellable HTTP requests.

WordbookGen 是一个 Java Swing 桌面工具，同时提供无界面 CLI 启动方式。它用于调用兼容 OpenAI Chat Completions 协议的大模型服务，将单词列表批量生成结构化词典数据。项目支持 JSON/CSV 导出、多 Provider 故障切换、断点续传、脚本 Hook、严格/兼容两种响应解析模式、可选的截断内容自动续写，以及可取消的异步 HTTP 请求。

## Features / 功能

- Multi-provider configuration and failover.
- 多 Provider 配置与故障切换。
- OpenAI Chat Completions compatible request format.
- 兼容 OpenAI Chat Completions 请求格式。
- Strict mode for standard response envelopes and strict JSON content.
- 严格模式：适合需要标准响应包络和严格 JSON 内容的场景。
- Compatibility mode for direct JSON, `output_text`, content arrays, and other non-standard provider shapes.
- 兼容模式：可处理直接返回 JSON、`output_text`、content 数组等非标准 Provider 返回。
- Optional automatic continuation when the provider reports `finish_reason=length` after a truncated response. It is disabled by default.
- 可选的截断自动续写：当 Provider 返回 `finish_reason=length` 时继续请求剩余 JSON 片段，默认关闭。
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
- Headless CLI mode for scripts, scheduled tasks, and server-side automation.
- 提供无界面 CLI 模式，便于脚本、定时任务和服务端自动化调用。

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

Headless CLI / 无界面命令行：

```bash
java -jar target/wordbook-gen-1.0.0-shaded.jar --cli \
  --config /path/to/config-folder \
  --input words.txt \
  --output wordbook.json \
  --format JSON
```

CLI without a saved UI config / 不依赖已保存 UI 配置的 CLI 示例：

```bash
java -jar target/wordbook-gen-1.0.0-shaded.jar --cli \
  --provider "ark|https://example.com/v1/chat/completions|YOUR_API_KEY|model-name|4|1000|300" \
  --input words.txt \
  --output wordbook.json \
  --source-lang English \
  --target-lang Chinese \
  --compatible-response true
```

## Basic Usage / 基本使用

1. Prepare a plain text word list, one word per line.
2. Add at least one Provider in the Provider panel.
3. Set input path, output path, output format, language, batch size, retries, and timeout.
4. Enable "compatible non-standard provider responses" for providers that return direct JSON or non-standard response shapes.
5. Disable that compatibility switch when a downstream workflow requires strict OpenAI-style response envelopes and strict JSON content.
6. Enable "auto continue truncated output" only when the provider often stops with `finish_reason=length`.
7. Click Start.

中文步骤：

1. 准备一个纯文本单词表，每行一个单词。
2. 在 Provider 区域至少配置一个服务商。
3. 设置输入路径、输出路径、输出格式、语种、批大小、重试次数和超时时间。
4. 如果服务商可能返回直接 JSON 或非标准结构，请开启“兼容非标准 Provider 返回”。
5. 如果下游流程对标准响应结构要求较高，请关闭该兼容开关。
6. 仅当 Provider 经常以 `finish_reason=length` 截断输出时，才开启“返回被截断时自动续写”。
7. 点击开始。

Useful CLI flags / 常用 CLI 参数：

- `--cli`: run without Swing UI / 无界面启动
- `--config <file-or-dir>`: load `ui-settings.json` from a file or folder / 从文件或目录加载配置
- `--provider "name|url|apiKey|model|concurrency|quota|windowMinutes"`: override providers; repeatable / 覆盖 Provider，可重复传入
- `--strict-response`: require standard Chat Completions response shape / 要求标准响应结构
- `--compatible-response true|false`: allow or reject non-standard response shapes / 开关非标准响应兼容
- `--auto-continue-truncated`: enable truncated-output continuation / 开启截断自动续写
- `--resume true|false`: resume from checkpoint / 是否从断点继续
- `--debug`: print raw request/response debug logs / 输出原始请求和响应调试日志

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
  UI and CLI entry points.
  图形界面与命令行入口。

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

The UI or CLI builds a `JobConfig` and starts `WordbookJobEngine`. The engine reads the word list, restores a compatible checkpoint, splits pending words into batches, and dispatches workers through `ProviderRouter`.

界面或 CLI 负责构造 `JobConfig` 并启动 `WordbookJobEngine`。任务引擎读取单词表、恢复兼容的断点、将待处理单词分批，并通过 `ProviderRouter` 调度 Provider。

`ProviderClient` builds OpenAI-compatible request bodies, sends HTTP requests with `HttpClient.sendAsync`, and polls the future so stop requests can cancel in-flight HTTP calls. It extracts model content, optionally requests append-only continuation for truncated outputs, parses JSON, validates required fields, optionally asks the model to repair malformed JSON, and then runs post-parse hooks.

`ProviderClient` 构造兼容 OpenAI 的请求体，使用 `HttpClient.sendAsync` 发送 HTTP 请求，并轮询 Future 以便停止任务时取消正在执行的请求。随后它会提取模型内容，在开启选项时对截断输出请求追加续写，解析 JSON、校验必需字段，在兼容模式下可请求模型修复畸形 JSON，最后执行解析后 Hook。

Checkpoint files are written atomically after each completed batch. A stopped job saves the latest completed state and exits as `STOPPED` instead of treating canceled HTTP requests as normal failures.

每完成一个批次都会原子写入断点文件。停止任务时会保存最新已完成状态，并以 `STOPPED` 收口，而不是把被取消的 HTTP 请求当作普通失败。

## Extension Points / 扩展修改点

- Prompt behavior / 提示词策略：`PromptBuilder`
- Output fields / 输出字段：`DictionaryFields`
- Provider response compatibility / Provider 响应兼容：`ProviderClient.extractModelContent` and `ProviderClient.parseAsArray`
- Truncated-output continuation / 截断续写：`ProviderClient.continueTruncatedModelOutput`
- Retry and failover / 重试与故障切换：`WordbookJobEngine.executeBatch` and `ProviderRouter`
- Checkpoint format / 断点格式：`WordbookCheckpoint` and `CheckpointStore`
- CLI options / 命令行参数：`WordbookGenCli`
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

Run the shaded jar in CLI mode / 以 CLI 模式运行 shaded jar：

```bash
java -jar target/wordbook-gen-1.0.0-shaded.jar --cli --help
```

Recommended release artifact / 推荐发布产物：

```text
target/wordbook-gen-1.0.0-shaded.jar
```

This project is released under the MIT License. See `LICENSE`.

本项目使用 MIT License 发布，详见 `LICENSE`。

## Known Risks and Limitations / 潜在问题与限制

- Provider output quality still depends on model behavior and prompt compliance.
- Provider 输出质量仍取决于模型能力和提示词遵循程度。
- Compatibility mode can recover many malformed JSON responses, but strict mode intentionally rejects non-standard response shapes.
- 兼容模式可以恢复很多畸形 JSON，但严格模式会主动拒绝非标准响应结构。
- Canceling an in-flight HTTP future requests cancellation promptly, but the exact network abort behavior depends on the JDK HTTP client and provider connection state.
- 取消正在执行的 HTTP Future 会尽快发出取消请求，但实际网络中断行为取决于 JDK HTTP Client 和 Provider 连接状态。
- Large batches may hit model context or output length limits; reduce batch size if truncation or missing words occur.
- 大批次可能触发上下文或输出长度限制；如出现截断或缺词，请降低批大小。
- Automatic continuation can help with length truncation, but a provider may still repeat content or fail to produce a clean suffix. Keep it off for strict downstream workflows unless needed.
- 截断自动续写可以缓解长度限制，但 Provider 仍可能重复已有内容或无法返回干净后缀；对严格下游流程，除非需要，否则建议保持关闭。
- UI settings may contain API keys; keep config folders outside the repository.
- UI 配置可能包含 API Key，请将配置目录放在仓库外。
- Hook scripts run as local processes with the user's permissions.
- Hook 脚本以当前用户权限在本地进程中执行。
- CLI mode is intended for scripts and scheduled runs, but it still uses the same local files, checkpoints, and provider quotas as the UI.
- CLI 模式适合脚本和定时执行，但仍与界面共用本地文件、断点和 Provider 配额规则。
