# MiniCode4j

<p align="center">
  <img src="./docs/logo.svg" alt="MiniCode Logo" width="180" />
</p>

<h2 align="center">MiniCode4j</h2>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-D97757?style=for-the-badge" alt="Java 21" />
  <img src="https://img.shields.io/badge/MiniCode-Java%20Edition-B85C3F?style=for-the-badge" alt="MiniCode Java Edition" />
  <img src="https://img.shields.io/badge/terminal--first-agent-F0EBE1?style=for-the-badge&labelColor=8B8B8B" alt="terminal-first agent" />
</p>

---

<p align="center">
  一个轻量级、本地优先、terminal-first 的 coding agent。MiniCode 的 Java 版。
</p>

[English](./README.md) | [功能对齐概览](./FEATURE_ALIGNMENT_ZH.md) | [可优化点](./IMPROVEMENTS_ZH.md)

MiniCode4j 面向本地开发工作流：读文件、搜代码、执行命令、审阅修改、保存可恢复会话，并在长上下文中通过上下文压缩保持可用。目前只支持作为一个coding agent的最小功能，很多地方都不太完善，但是能跑:)。

## 特性

- Anthropic-compatible provider 路径
- terminal-first coding agent 工作流
- 默认 `minicode` 启动 TS terminal UI frontend
- `minicode --tty` 保留旧 Java TTY fallback
- 内置工具：文件读取、搜索、编辑、写入、命令执行、`ask_user`、`load_skill`
- 敏感动作执行前进行权限审查
- append-only JSONL session，支持 `list`、`rename`、`resume`、`fork`
- manual `/compact` 与 full autoCompact
- Windows launcher、TS UI 运行时与可运行 fat jar

## 构建

需要：

- Node.js 20+
- JDK 21
- Maven 3.9+
- PowerShell

如果 `java` 和 `mvn` 已经在 `PATH` 中，可以直接使用：

```powershell
cd <MiniCode4j 源码目录>

java -version
mvn -version

mvn test
mvn package
```

产物：

```text
target\minicode.jar
target\dist\minicode\
```

其中：

- `target\minicode.jar` 是可直接运行的 fat jar。
- `target\dist\minicode\` 是发布目录，里面包含 `bin`、`lib` 和 `ts-cli` 运行时文件。

构建完成后可以先检查版本和帮助：

```powershell
java -jar target\minicode.jar --version
java -jar target\minicode.jar --help
```

也可以直接测试发布目录里的 Windows launcher：

```powershell
target\dist\minicode\bin\minicode.cmd --version
target\dist\minicode\bin\minicode.cmd --help
```

如果 JDK 21 没有加入 `PATH`，可以临时指定：

```powershell
cd <MiniCode4j 源码目录>

$env:JAVA_HOME="<你的 JDK 21 安装目录>"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

mvn test
mvn package
```

## 运行

把发布目录的 `bin` 加入 `PATH`：

```powershell
$env:PATH="<MiniCode4j 源码目录>\target\dist\minicode\bin;$env:PATH"
```

然后在任意项目目录启动(记得配JAVA_HOME)：

```powershell
cd <你的项目目录>
minicode
```

默认 `minicode` 会先启动 TS frontend，再由 TS frontend 自动启动同发布目录下的 Java backend jar，并传入 `--ui-stdio-run`。

如果你想显式走旧 Java TTY 路径：

```powershell
minicode --tty
```

默认 workspace 就是当前 shell 目录。也可以显式指定：

```powershell
minicode --cwd <你的项目目录>
```

不想改 `PATH` 时，也可以用完整路径运行：

```powershell
cd <你的项目目录>
<MiniCode4j 源码目录>\target\dist\minicode\bin\minicode.cmd
```

快速 smoke：

```powershell
New-Item -ItemType Directory -Force .\manual-test-workspace | Out-Null
cd .\manual-test-workspace
<MiniCode4j 源码目录>\target\dist\minicode\bin\minicode.cmd --version
<MiniCode4j 源码目录>\target\dist\minicode\bin\minicode.cmd --help
<MiniCode4j 源码目录>\target\dist\minicode\bin\minicode.cmd
<MiniCode4j 源码目录>\target\dist\minicode\bin\minicode.cmd --tty
```

## Provider

Anthropic-compatible endpoint 示例(mimo是孩子测试的时候用的)：

```powershell
$env:MINICODE_PROVIDER="anthropic-compatible"
$env:ANTHROPIC_BASE_URL="https://api.xiaomimimo.com/anthropic"
$env:ANTHROPIC_MODEL="mimo-v2.5-pro"
$env:ANTHROPIC_AUTH_TOKEN="<token>"
```

不要提交或打印真实 token。

## 常用命令

```powershell
minicode
minicode --tty
minicode --cwd <path>
minicode --resume <id>
minicode --fork <id>
minicode session list
minicode session rename <id> <title>
minicode --max-steps <n>
minicode --version
minicode --help
```

session 按 workspace cwd 隔离。恢复 session 时，请在同一个项目目录运行，或传入相同的 `--cwd`。

当前发布目录不会 bundled Node.js，也不会包含 `node_modules`；默认 TS UI 启动依赖本机可用的 `node` 命令。JDK 21 仍然是必须项，因为 TS frontend 会启动同目录下的 Java backend。
