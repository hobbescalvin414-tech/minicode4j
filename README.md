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
  A lightweight, local-first, terminal-first coding agent. Java edition of MiniCode.
</p>

[简体中文](./README_ZH.md) | [Improvement ideas (Chinese)](./IMPROVEMENTS_ZH.md)

MiniCode4j is built for local development workflows: reading files, searching code, running commands, reviewing edits, keeping resumable sessions, and staying usable in long conversations through context compacting. It currently provides the minimum feature set of a coding agent. Many parts are still rough, but it runs.

## Features

- Anthropic-compatible provider path
- terminal-first coding agent workflow
- built-in tools: file reading, search, edit, write, command execution, `ask_user`, and `load_skill`
- permission review before sensitive actions
- append-only JSONL sessions with `list`, `rename`, `resume`, and `fork`
- manual `/compact` and full autoCompact
- Windows launcher and runnable fat jar

## Build

Requires:

- JDK 21
- Maven 3.9+
- PowerShell

If `java` and `mvn` are already available in `PATH`:

```powershell
cd <MiniCode4j source directory>

java -version
mvn -version

mvn test
mvn package
```

Outputs:

```text
target\minicode.jar
target\dist\minicode\
```

Where:

- `target\minicode.jar` is a runnable fat jar.
- `target\dist\minicode\` is the distribution directory containing `bin` and `lib`.

After building, check the version and help output:

```powershell
java -jar target\minicode.jar --version
java -jar target\minicode.jar --help
```

You can also test the Windows launcher in the distribution directory:

```powershell
target\dist\minicode\bin\minicode.cmd --version
target\dist\minicode\bin\minicode.cmd --help
```

If JDK 21 is not in `PATH`, set it for the current PowerShell session:

```powershell
cd <MiniCode4j source directory>

$env:JAVA_HOME="<your JDK 21 installation directory>"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

mvn test
mvn package
```

## Run

Add the distribution `bin` directory to `PATH`:

```powershell
$env:PATH="<MiniCode4j source directory>\target\dist\minicode\bin;$env:PATH"
```

Then start MiniCode from any project directory. Make sure `JAVA_HOME` is configured:

```powershell
cd <your project directory>
minicode
```

The default workspace is the current shell directory. You can also pass it explicitly:

```powershell
minicode --cwd <your project directory>
```

Without changing `PATH`, run the launcher by full path:

```powershell
cd <your project directory>
<MiniCode4j source directory>\target\dist\minicode\bin\minicode.cmd
```

Quick smoke test:

```powershell
New-Item -ItemType Directory -Force .\manual-test-workspace | Out-Null
cd .\manual-test-workspace
<MiniCode4j source directory>\target\dist\minicode\bin\minicode.cmd --version
<MiniCode4j source directory>\target\dist\minicode\bin\minicode.cmd session list
```

## Provider

Example Anthropic-compatible endpoint:

```powershell
$env:MINICODE_PROVIDER="anthropic-compatible"
$env:ANTHROPIC_BASE_URL="https://api.xiaomimimo.com/anthropic"
$env:ANTHROPIC_MODEL="mimo-v2.5-pro"
$env:ANTHROPIC_AUTH_TOKEN="<token>"
```

Do not commit or print real tokens.

## Commands

```powershell
minicode
minicode --cwd <path>
minicode --resume <id>
minicode --fork <id>
minicode session list
minicode session rename <id> <title>
minicode --max-steps <n>
minicode --version
minicode --help
```

Sessions are scoped by workspace cwd. To resume a session, run from the same project directory or pass the same `--cwd`.
