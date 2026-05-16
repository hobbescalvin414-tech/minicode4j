# MiniCode Java

MiniCode Java is a lightweight, local-first, terminal-first coding agent.

Run it from a project directory:

```powershell
cd E:\path\to\your-project
minicode
```

The default workspace is the current shell directory. Use `--cwd <path>` only when you want to override it.

## Requirements

- JDK 21 on `PATH`
- Provider configuration in environment variables or settings

## Provider

Anthropic-compatible Mimo example:

```powershell
$env:MINICODE_PROVIDER="anthropic-compatible"
$env:ANTHROPIC_BASE_URL="https://api.xiaomimimo.com/anthropic"
$env:ANTHROPIC_MODEL="mimo-v2.5-pro"
$env:ANTHROPIC_AUTH_TOKEN="<token>"
```

Do not paste real tokens into logs or issue reports.

## Commands

```powershell
minicode
minicode --cwd E:\path\to\project
minicode --resume <id>
minicode --fork <id>
minicode session list
minicode session rename <id> <title>
minicode --max-steps <n>
minicode --version
minicode --help
```

## PATH

Add this release's `bin` directory to `PATH`, or run the launcher by full path:

```powershell
E:\path\to\minicode\bin\minicode.cmd --version
```
