# MiniCode Java v1 Smoke

## 1. 前置条件

- Node.js 20+ 已安装，并且 `node -v` 可用。
- JDK 21 已安装，并且 `java -version` 显示 21。
- 发布目录结构包含：

```text
minicode\
  bin\
    minicode.cmd
    minicode.ps1
  lib\
    minicode.jar
  ts-cli\
    package.json
    src\
      main.js
      ...
  README.md
  SMOKE_ZH.md
```

## 2. 配置 provider

真实 Mimo provider 的常用配置：

```powershell
$env:MINICODE_PROVIDER="anthropic-compatible"
$env:ANTHROPIC_BASE_URL="https://api.xiaomimimo.com/anthropic"
$env:ANTHROPIC_MODEL="mimo-v2.5-pro"
$env:ANTHROPIC_AUTH_TOKEN="<token>"
```

不要在日志、截图或 issue 中输出 token 明文。

如需离线检查 launcher，可先只跑：

```powershell
.\bin\minicode.cmd --version
.\bin\minicode.cmd --help
```

这两个命令不应启动 provider、不应启动 TS frontend、不应创建 session。

## 3. 加入 PATH

将发布目录的 `bin` 加入当前 PowerShell：

```powershell
$env:PATH="E:\path\to\minicode\bin;$env:PATH"
```

也可以直接运行完整路径：

```powershell
E:\path\to\minicode\bin\minicode.cmd --version
```

## 4. 任意目录启动

准备一个手测工作区：

```powershell
New-Item -ItemType Directory -Force E:\Minicode-Java\manual-test-workspace | Out-Null
cd E:\Minicode-Java\manual-test-workspace
minicode
```

预期：默认 `minicode` 启动 TS frontend，TS frontend 再自动启动同一发布目录下的 Java backend jar；MiniCode 默认工作区是当前 shell 目录 `E:\Minicode-Java\manual-test-workspace`。

可让模型执行一个最小写文件 smoke：

```text
请用工具在当前目录创建 hello.txt，内容为 ok。写完后简短回复。
```

审批通过后，文件应落在当前工作区：

```powershell
Get-Content -Raw -Encoding UTF8 E:\Minicode-Java\manual-test-workspace\hello.txt
```

预期内容：

```text
ok
```

## 5. --cwd 覆盖

从其他目录显式指定工作区：

```powershell
cd E:\Minicode-Java\MiniCode-Java
minicode --cwd E:\Minicode-Java\manual-test-workspace
```

预期：launcher、TS frontend、Java backend 整条链路都保持 `--cwd` 指定的目录作为 workspace；runtime config、system prompt cwd、session、permission、tools 都使用该目录。

## 6. --tty fallback

显式走旧 Java TTY：

```powershell
cd E:\Minicode-Java\manual-test-workspace
minicode --tty
```

预期：不经过 TS frontend，直接进入旧 Java TTY fallback；workspace 仍然是当前 shell 目录。

## 7. session

查看当前工作区 session：

```powershell
cd E:\Minicode-Java\manual-test-workspace
minicode session list
```

恢复当前工作区 session：

```powershell
minicode --resume <id>
```

如果 session 属于其他 cwd，MiniCode 应提示：

```text
Session <id> belongs to a different cwd: <path>
```

修正方式是在 session 所属目录运行，或显式带同一个 `--cwd`。

## 8. 打包产物 smoke

在源码目录执行：

```powershell
$env:JAVA_HOME=$env:JAVA_HOME21
$env:PATH="$env:JAVA_HOME\bin;E:\maven\apache-maven-3.9.11\bin;$env:PATH"
mvn package
```

检查：

```powershell
java -jar target\minicode.jar --version
java -jar target\minicode.jar --help
target\dist\minicode\bin\minicode.cmd --version
target\dist\minicode\bin\minicode.cmd --help
target\dist\minicode\bin\minicode.cmd
target\dist\minicode\bin\minicode.cmd --tty
```
