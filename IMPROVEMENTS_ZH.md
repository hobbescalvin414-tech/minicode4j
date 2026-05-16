# 可优化点

这份文档记录 MiniCode4j 当前但暂时没有做完的优化方向。

## TUI 体验

- 继续优化终端界面的视觉层级和布局。
- 增加更自然的 loading / running 状态动画。
- 支持完整的多行输入。
- 支持 session picker。
- 支持工具结果的展开、折叠和滚动查看。
- 优化 diff 展示和权限审批界面。
- 继续处理 Windows Terminal 下的滚轮、光标、中文宽度等细节。

## 权限系统

- 支持完全访问权限功能
- 支持不同权限模式的切换

## 工具能力

- 继续优化 `read_file`、`grep_files` 的源码阅读体验。
- 支持更好的“按函数/符号附近读取上下文”。
- 优化大工具输出的预览、持久化和恢复查看体验。

## Skills

- 增加 skills 列表命令。
- 增加 skills 安装和移除命令。
- 支持全局 / 项目级 skills 管理。
- 处理同名 skill 冲突和版本管理。

## MCP

（这块孩子不太懂，所以目前做的很少）

- 支持 MCP resources：
  - `list_mcp_resources`
  - `read_mcp_resource`
  - resources 分页 / 大输出预算
  - resources 权限策略
  - resources 与 session / context 的展示关系
- 支持 MCP prompts：
  - `list_mcp_prompts`
  - `get_mcp_prompt`
  - prompt 参数校验
  - prompt 渲染结果预览
- 支持更多 MCP transport：
  - HTTP / SSE MCP
  - 连接超时和重连策略
  - server health check
- 增加 MCP server 管理：
  - server list
  - enable / disable
  - add / remove
  - 配置校验
  - 启动失败诊断
- 优化 MCP tool 权限策略：
  - 按 server 授权
  - 按 tool 授权
  - 只读工具与敏感工具区分
  - 权限审批文案更清楚
- 优化 MCP tool 体验：
  - tool name 展示更友好
  - `mcp__server__tool` 与原始 MCP tool name 的映射展示
  - tool input / output 摘要
  - tool error 归一
- 增加 MCP server 状态展示：
  - 已连接 / 启动失败 / 已关闭
  - tools 数量
  - resources/prompts 支持情况
  - 最近错误
- 评估 MCP sampling 是否需要支持。
- 评估 MCP marketplace / preset server 配置。

## Web 工具

- 增加 `web_fetch`。
- 增加 `web_search`。
- 处理网络失败、权限确认和大输出预算。

## Session

- 增加 session 删除或归档。
- 增加 session 搜索。
- 增加 session 导出。
- 优化 session list 的展示和过滤。
- 展示 fork / compact boundary 等会话关系。

## 安装与发布

- 增加更完整的 Windows 安装脚本。
- 增加版本检查和升级策略。

## Provider

- 增加更多 provider 适配。
- 增加 provider 配置向导或诊断命令。
- 优化 provider 错误提示、重试和限流处理。

## Context / Compact

- 继续调优 autoCompact 阈值和失败恢复策略。
- 优化 compact summary 质量。
- 增强 context 使用量展示。
- 增加 tool-results 清理策略。
