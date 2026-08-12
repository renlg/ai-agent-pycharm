# AGENTS.md

## 项目概述

**太微**（Taiwei）是一款 IntelliJ IDEA 插件，作为 AI 编程助手，帮助开发者更高效地编写代码。插件在 IDE 右侧工具窗口中提供聊天交互界面，支持调用 LLM API 进行代码生成、解释、重构等任务；内置模板引擎用于管理提示词；提供 Diff 模块用于审查 AI 生成的代码变更；支持通过终端插件集成命令行操作，并内置网络搜索功能（默认 DuckDuckGo 免费搜索，可选阿里云 IQS 搜索）。

核心功能：
- AI 聊天助手（右侧工具窗口）
- 可配置的 AI 模型、终端行为、网络搜索（设置页面）
- 基于 Diff 的代码变更审查与通知
- 国际化支持（中英文消息文件）

## 技术栈

- **语言**：Java 17
- **构建工具**：Gradle（IntelliJ Plugin 插件 `org.jetbrains.intellij` 1.17.4）
- **IDE 平台**：IntelliJ IDEA Community Edition 2024.1（支持 `241` 至 `261.*`）
- **主要依赖**：
  - `okhttp3:okhttp:4.12.0` + `okhttp-sse:4.12.0` — HTTP 客户端，用于调用 LLM API
  - `gson:2.10.1` — JSON 处理
  - `velocity-engine-core:2.3` — 模板引擎，用于渲染提示词
  - `jtokkit:1.1.0` — OpenAI tiktoken 的 Java 实现，用于 Token 计数
  - `iqs20241111:1.7.2` — 阿里云 IQS SDK（可选网络搜索引擎）
- **测试**：JUnit 5

## 目录结构说明

```
ai-agent/
├── gradle/
│   └── wrapper/            # Gradle Wrapper 脚本和 jar
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/taiwei/ # 插件主代码（所有 Java 源文件）
│   │   │       ├── aiagent/
│   │   │       │   ├── window/       # 工具窗口（ChatWindowFactory）
│   │   │       │   ├── settings/     # 配置服务与设置界面（AiAgentSettings, IqsSettings, 多个 Configurable）
│   │   │       │   └── diff/         # 代码变更追踪与审查（DiffReviewService, DiffEditorNotificationProvider）
│   │   │       └── ...               # 可能存在其他包
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── plugin.xml        # 插件描述符，声明扩展点、依赖、组件
│   │       ├── html/
│   │       │   ├── css/              # 聊天界面样式
│   │       │   ├── js/               # 聊天界面逻辑
│   │       │   └── chat.html         # 聊天界面主页面（嵌入 JCEF）
│   │       ├── icons/                # 插件图标（SVG）
│   │       ├── templates/
│   │       │   ├── init_prompt.vm    # 初始化提示词模板（Velocity）
│   │       │   └── system_prompt.vm  # 系统提示词模板
│   │       ├── messages.properties           # 默认英文消息
│   │       └── messages_zh_CN.properties     # 简体中文消息
│   └── test/
│       ├── java/                     # 单元测试代码
│       └── resources/                # 测试资源
├── build.gradle                      # 构建配置
├── settings.gradle                   # 项目名称 (ai-agent)
├── gradlew / gradlew.bat             # Gradle Wrapper 执行脚本
└── .gitignore                        # Git 忽略规则
```

> 注意：`src/main/java/com/taiwei/` 下实际目录结构根据 `plugin.xml` 中的包名推断，可能包含 `aiagent` 包（或其它子包），具体需查看实际源码。但已注册的服务和扩展点位于 `com.taiwei.aiagent` 包下（如 `window`, `settings`, `diff`）。

## 关键文件说明

| 文件 | 作用 |
|------|------|
| `build.gradle` | 插件构建配置，声明插件 ID、版本、IntelliJ 平台版本、依赖项、运行/签名/发布配置 |
| `settings.gradle` | 设置项目名称 `ai-agent` |
| `src/main/resources/META-INF/plugin.xml` | **插件描述符**，定义插件 ID (`com.taiwei.ai-agent`)、名称（太微）、依赖（`com.intellij.modules.platform` 和 `org.jetbrains.plugins.terminal`）、注册的扩展点（工具窗口 `ChatWindowFactory`、配置服务、设置页面、Diff 服务等） |
| `src/main/resources/html/chat.html` | **聊天界面**的 HTML 入口，嵌入在工具窗口中（通过 JCEF 或类似机制加载） |
| `src/main/resources/templates/init_prompt.vm` | **初始化提示词模板**，当 AI 会话开始时使用 Velocity 渲染 |
| `src/main/resources/templates/system_prompt.vm` | **系统提示词模板**，AI 系统消息模板 |
| `src/main/resources/messages.properties` | 英文国际化资源文件 |
| `src/main/resources/messages_zh_CN.properties` | 简体中文国际化资源文件 |
| `src/main/java/com/taiwei/aiagent/window/ChatWindowFactory.java` | 注册为 `toolWindow` 工厂类，负责创建“太微”工具窗口 |
| `src/main/java/com/taiwei/aiagent/settings/AiAgentSettings.java` | 应用级别服务，持久化 AI 相关设置（如模型选择、API 密钥等） |
| `src/main/java/com/taiwei/aiagent/settings/IqsSettings.java` | 应用级别服务，持久化阿里云 IQS 搜索设置 |
| `src/main/java/com/taiwei/aiagent/settings/TaiweiParentConfigurable.java` | 设置页面的父级配置（Tools → 太微） |
| `src/main/java/com/taiwei/aiagent/settings/ModelConfigurable.java` | 模型设置页面 |
| `src/main/java/com/taiwei/aiagent/settings/TerminalConfigurable.java` | 终端设置页面 |
| `src/main/java/com/taiwei/aiagent/settings/IqsConfigurable.java` | 网络搜索（IQS）设置页面 |
| `src/main/java/com/taiwei/aiagent/diff/DiffReviewService.java` | 项目级别服务，管理 AI 生成的代码变更审查 |
| `src/main/java/com/taiwei/aiagent/diff/DiffEditorNotificationProvider.java` | 编辑器通知提供者，提示用户审查 AI 变更 |

> 具体实现类可能位于 `com.taiwei.aiagent` 包下，也可能在 `com.taiwei` 下。`plugin.xml` 中声明的类全限定名均以 `com.taiwei.aiagent` 开头。

## 开发约定

- **代码风格**：遵循标准 Java 命名规范（驼峰命名，类名首字母大写，方法/变量首字母小写）。无特定风格强制要求。
- **包结构**：业务模块按功能分包（`window`, `settings`, `diff`），服务类通常放在 `settings` 包或其他功能包内。
- **扩展点注册**：所有 IntelliJ 扩展点（工具窗口、服务、配置界面、通知提供者）均在 `plugin.xml` 中声明，使用 `com.intellij` 扩展命名空间。
- **国际化**：使用 Java 属性文件（`messages.properties` + 语言后缀），通过标准 ResourceBundle 加载。文本内容应尽量抽取到消息文件中。
- **模板引擎**：提示词使用 Apache Velocity 模板（`.vm` 文件），位于 `resources/templates/` 下。修改模板后需确保 Velocity 语法正确。
- **构建与运行**：
  - 运行 `./gradlew runIde` 会启动一个使用固定沙箱目录（`~/.taiwei-ide-sandbox/`）的独立 IDE 实例，并自动打开当前项目。
  - 沙箱配置路径通过系统属性硬编码在 `build.gradle` 中，避免每次 clean 后丢失配置。
  - 禁用 `instrumentCode` 任务，避免 JDK 路径问题。
- **依赖管理**：主要依赖通过 Maven Central（使用阿里云镜像加速）获取；禁止引入过多外部库，保持插件轻量。
- **JDK 版本**：必须使用 Java 17 编译与运行。
- **IDE 目标版本**：`sinceBuild = 241`（IntelliJ 2024.1），`untilBuild = 261.*`（兼容至 2026.1 系列）。
- **测试**：使用 JUnit 5，测试类放在 `src/test/java` 下，资源文件放在 `src/test/resources`。