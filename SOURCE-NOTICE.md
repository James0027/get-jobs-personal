# 来源与许可声明（SOURCE NOTICE）

本仓库是上游开源项目 **get_jobs** 的个人分支快照，不是上游官方仓库。

## 上游来源
- 仓库：https://github.com/loks666/get_jobs
- 基准提交：`8f5c48538f68ffebbcfe1c9fb57a12a0bc699b50`
  （提交说明：`✨ customize random follow-up messages for boss chats.`）
- 快照日期：2026-10-29
- 应用版本：`2.0.1`（`spring.info.app.version`）

## 许可
- 许可证：**PolyForm Noncommercial License 1.0.0**
- 原始文本保存在仓库根 `LICENSE`，未做任何修改
- Required Notice：`Copyright (c) 2024-2026 GET JOBS (https://github.com/loks666/get_jobs)`
- 使用限制：仅限非商业用途；再分发必须保留 `LICENSE` 与 Required Notice

## 本快照相对上游 8f5c485 的改动
本仓库的 `main` 是**单一根提交**，内容等于作者本机 2026-10-29 的可用工作副本
（上游 31 个文件被修改、新增 59 个文件），主要涉及：

- BOSS 直聘：四类资料（基本信息/求职意向/工作经历/项目经历）JSON 兼容与 CRUD
- BOSS 直聘：聊天记录只读、AI 决策与自动回复硬门闸、跟进原子 claim（同轮次只发一次）
- BOSS 直聘：简历文本草稿/显式批准/PDF 本地校验存储与批准（**不含 PDF 真实发送**）
- BOSS 直聘：投递执行护栏 `BossExecutionGuard`、`delivery/` 投递任务与运行
- 官方行业目录 `boss/industry-catalog.json`（15 大类 / 134 细分）
- 前端：共享外壳（Sidebar/PageHeader/ContentArea）与 BOSS 聊天工作区组件
- 其他三平台（猎聘 / 51job / 智联）：**仅 UI 样式统一**，字段、接口、序列化与后端逻辑未改
- 配置：`server.port` 由 9527 改为 8888（本机 9527 被代理软件占用）

## 与上游的关系
- 本仓库的 `origin` 指向本仓库自身，**未改写上游仓库**，也未向上游推送
- 上游历史没有复制进本仓库（本仓库刻意保持干净的单一根提交）

## 本快照的脱敏改动（仅发布副本，不影响作者本机工作副本）
1. `启动.cmd`：移除硬编码的个人 JDK 绝对路径，改为 `JAVA_HOME` → `%USERPROFILE%\.jdks\jdk-21*`
   自动探测；`PLAYWRIGHT_NODEJS_PATH` 改为从 PATH 取 `node.exe`
2. `src/test/java/com/getjobs/application/B7UiEchoTest.java`：移除硬编码的浏览器绝对路径，
   改为 `BOSS_TEST_CHROME` 环境变量优先 + 本机 `%LOCALAPPDATA%\ms-playwright` 自动探测
3. `.gitignore`：追加 `.pnpm-store/`
