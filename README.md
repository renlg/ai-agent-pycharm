# 太微 (Taiwei) — IntelliJ IDEA AI 编程助手

一个集成在 IntelliJ IDEA 中的 AI 编程助手插件。在 IDE 右侧工具窗口中提供 AI 聊天交互，支持调用任意 OpenAI 兼容的 LLM API，内置代码读写、终端命令、浏览器、网络搜索、图片生成等工具，帮助开发者更高效地编写代码。

> Taiwei is an AI coding assistant plugin for IntelliJ IDEA, featuring a chat panel, tool calling, long-term memory and inline completion.

## ✨ 功能特性

- **AI 聊天助手** — 右侧工具窗口，基于 JCEF 的高性能聊天界面，SSE 流式输出、停止响应、Token 用量统计
- **多模型支持** — 兼容任意 OpenAI 格式 API（Base URL / API Key / 模型名均可配置），流式响应，失败自动回退
- **工具调用系统** — AI 可自主使用以下工具：
  - 📝 **代码工具**：读取文件、写入/替换代码（带路径穿越防护，经 IDE WriteCommandAction 安全修改）
  - 💻 **终端命令**：运行 shell 命令并获取输出（带超时与清理，防注入）
  - 🌐 **浏览器工具**：JCEF 嵌入浏览器，抓取页面内容、执行 JS、拦截网络请求
  - 🔍 **网络搜索**：默认 DuckDuckGo 免费搜索，可选 SerpApi / 阿里云 IQS
  - 🖼️ **图片生成**：文生图 + 图生图（自动取最近对话中的图片作为参考图），消息框内联显示，一键在浏览器打开
  - 🧠 **长期记忆**：`save_memory` / `search_memory` 工具，Embedding + SQLite FTS5 混合检索，自动压缩与遗忘
  - 🎯 **技能加载**：`load_skill` 按需加载技能文档，`Todo` 任务清单管理
- **内联代码补全** — 基于 Inline Completion 的智能补全（防抖、可取消）
- **代码选择弹出框** — 选中代码后一键添加到对话
- **Diff 代码审查** — 追踪 AI 生成的代码变更，编辑器内通知审查
- **@ 提及** — 在对话中引用文件与上下文
- **成本优化** — Prompt Caching 前缀缓存、LRU 结果缓存、jtokkit Token 计数
- **中英文双语** — 完整国际化支持

## 📥 安装

1. 从 [Releases](https://github.com/renlg/ai-agent/releases) 下载最新版 `ai-agent-<version>.zip`
2. 打开 IntelliJ IDEA：`Settings → Plugins → ⚙️ → Install Plugin from Disk...`
3. 选择下载的 zip 文件，重启 IDE

## ⚙️ 配置

`Settings → Tools → 太微`：

| 设置页 | 说明 |
|--------|------|
| 模型 | Base URL、API Key、模型名称、上下文窗口、最大 Token、超时 |
| 终端 | 命令执行行为、超时时间 |
| 网络搜索 | 搜索引擎选择（DuckDuckGo / SerpApi / IQS）与密钥 |
| 图片生成 | 生图模型与参数（提示词模板可编辑） |
| 工具管理 | 启用/禁用已加载的工具 |

## 🛠️ 技术栈

- **语言**：Java 17 + Kotlin
- **构建**：Gradle + `org.jetbrains.intellij` 插件
- **平台**：IntelliJ IDEA Community / Ultimate（sinceBuild `241`，兼容至 `261.*`）
- **依赖**：OkHttp（SSE 流式）、Gson、Velocity（提示词模板）、jtokkit（Token 计数）、SQLite FTS5

## 🔨 本地构建

```bash
./gradlew buildPlugin
# 产物：build/distributions/ai-agent-<version>.zip
```

调试运行：

```bash
./gradlew runIde
```

## 📁 目录结构

```
src/main/java/com/taiwei/aiagent/
├── window/     # 工具窗口（ChatWindowFactory）
├── settings/   # 配置服务与设置界面
├── diff/       # 代码变更追踪与审查
├── agent/      # Agent 上下文与会话管理
├── llm/        # LLM 客户端（OpenAI 兼容 / SSE 流式）
├── tool/       # 工具注册与实现
├── memory/     # 长期记忆系统
└── browser/    # JCEF 浏览器工具
```

## 📄 说明

本项目为个人项目，代码基于 `IntelliJ Platform SDK` 开发，仅用于学习交流。
