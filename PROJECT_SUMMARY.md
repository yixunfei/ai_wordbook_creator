# Project Summary / 项目概要

WordbookGen is a Java 17 Swing desktop application for generating structured wordbook data from word lists through OpenAI-compatible LLM providers.

WordbookGen 是一个 Java 17 Swing 桌面应用，用于通过兼容 OpenAI 协议的大模型 Provider，把单词列表生成结构化词书数据。

## Core Capabilities / 核心能力

- Multi-provider configuration and failover.
- 多 Provider 配置与故障切换。
- Batched word processing with retry, quota limiting, checkpoint resume, pause/resume/stop.
- 批量处理单词，支持重试、配额限制、断点续传、暂停、继续和停止。
- Cancellable async HTTP requests on stop.
- 停止任务时可取消正在执行的异步 HTTP 请求。
- Strict or compatible provider response parsing.
- 支持严格或兼容两种 Provider 响应解析模式。
- JSON/CSV export using fixed dictionary fields.
- 使用固定词典字段导出 JSON/CSV。
- Script hooks for request, response, and parsed-result customization.
- 支持请求、响应和解析后结果的脚本 Hook 扩展。
- Config save/load from a default or selected folder.
- 支持从默认目录或指定目录加载/保存配置。

## Entry Point / 入口

```text
com.wordbookgen.app.WordbookGenApplication
```

## Build / 构建

```bash
mvn -q -DskipTests package
```

## Release Artifact / 发布产物

```text
target/wordbook-gen-1.0.0-shaded.jar
```

## Open-Source Readiness / 开源准备

- `.gitignore` excludes secrets, local config, checkpoints, outputs, IDE metadata, and build artifacts.
- `.gitignore` 已排除密钥、本地配置、断点、输出文件、IDE 元数据和构建产物。
- `ui-settings.json` can contain API keys and must not be committed.
- `ui-settings.json` 可能包含 API Key，不能提交。
- The project includes an MIT License.
- 项目已包含 MIT License。
