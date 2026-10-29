# 发布快照说明（2026-10-29）

本仓库保存的是 get_jobs 在 2026-10-29 的**可用版本基线**，来源与本许可见 `SOURCE-NOTICE.md`。

## 分支与标签
| 引用 | 内容 |
|---|---|
| `main` | 单一根提交，等于基线源码 + 测试 + `LICENSE` 来源 + 可运行脱敏配置 |
| `baseline-2026-10-29` | 指向 `main` 的基线标签 |
| `feature/boss-live-integration` | 从 `main` 分出，只含尚未完成项的任务说明，**无功能实现** |

## 快照内容
- 文件数：285（不含 `.git`）
- 含：`src/`（Java 主代码与测试）、`front/`（Next.js 源码与脚本）、`doc/`+`docs/`（上游文档与配图）、
  Gradle wrapper、`LICENSE`、`README.md`、`CLA.md`、`start-local.ps1`、`启动.cmd`、`停止.cmd`
- 不含（构建/运行时产物与个人数据）：见下节

## 排除清单（生成快照时显式剔除）
目录：`work/`（日志/备份/jar/证据/快照）、`.pnpm-store/`、`db/`、`data/`、`browser-data/`、
`target/`、`build/`、`.gradle/`、`node_modules/`、`front/node_modules/`、`front/.next/`、`front/out/`、
`docs/node_modules/`、`docs/.vitepress/{cache,dist}/`、`cookies/`

文件：`*.log`、`*.db`、`*.db-wal`、`*.db-shm`、`cookie.json`、`*.jar`（**保留** Gradle wrapper
`gradle/wrapper/gradle-wrapper.jar`，它是构建引导文件，不是本项目的打包产物）、
个人简历（`src/main/resources/resume*`、`data/boss/resume/`）、浏览器用户数据

核验结果：快照中不存在上述任何目录；`*.db` / `*.log` 命中 0；`*.jar` 命中 1（仅 Gradle wrapper）。

## 敏感信息扫描
- 密钥模式（`sk-`、`ghp_`、`github_pat_`、`AKIA`、`xox?-`、`-----BEGIN ... PRIVATE KEY-----`、
  `eyJhbGciOi` 形式的 JWT、`Bearer <token>`）：命中 2 处，**均为占位符**
  （`docs/guide/ai-and-push.md` 与 `front/app/env-config/page.tsx` 的 `sk-xxx...xxxx` 输入提示），无真实密钥
- 个人绝对路径（`C:\Users\...`、`C:/Users/...`）：发布前命中 2 处（`启动.cmd`、`B7UiEchoTest.java`），
  **已脱敏**，复扫 0 命中
- Cookie / 登录态 / 数据库 / 简历：未进入快照（目录级排除 + `.gitignore` 原有规则）
- Git 历史：本仓库为单一根提交，未复制作者本机的历史提交，因此历史中也不存在上述数据

## 运行方式（本机验证过的路径）
前置：JDK 21；Node.js（前端构建用）。数据库文件 `db/getjobs.db` 不在仓库内（含个人数据），
需按 `README.md` / `start-local.ps1` 说明放一份库文件模板后再启动。

1. 后端：`./gradlew bootRun`（或先 `./gradlew bootJar` 后 `java -jar build/libs/get_jobs-2.0.1.jar`）
2. 前端：`cd front && npm i && npm run build`（产物 `front/out`，也可由 `next build` 内嵌进 jar 的 `dist`）
3. 端口：后端 API `8888`、前端页面 `6866`（`src/main/resources/application.yaml`、
   `front/server.config.js`）
4. Windows 便捷入口：`启动.cmd` / `停止.cmd`

## 已知边界（未完成项，详见 `feature/boss-live-integration` 分支的 `PENDING-BOSS-LIVE.md`）
- PDF 简历**真实发送**未实现：决策为 PDF 时返回 `blocked/PDF_SEND_NOT_IMPLEMENTED`，不降级发图
- 真实聊天 DOM 未验收：仅本地/Mock DOM 用例（`BOSS_CHAT_DOM_TEST=true`）
- BOSS 官网实习届别取值未经官方证据验证，代码中不写入猜测值
- 自动回复、跟进默认关闭；真实会话身份未绑定时 fail-closed（`IDENTITY_NOT_BOUND`）
