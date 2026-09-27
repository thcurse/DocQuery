# 前后端独立发布

后端仍位于仓库根目录；前端位于 `frontend/`，分别发布 JAR 和 `dist`。浏览器通过同一 Nginx 地址访问管理页面与 API。

## 开发

1. 按根 README 启动依赖、初始化管理员和 Spring Boot（8080）。
2. 安装 Node.js >=22.12（建议 Node.js 24 LTS）与 pnpm 12.6.0。
3. `cd frontend`，执行 `pnpm install --frozen-lockfile`、`pnpm dev`。
4. 打开 `http://127.0.0.1:5173/admin/`。需要连接其他后端时，在启动前设置 `DOCQUERY_API_TARGET`。

Windows 开发入口使用系统自带 PowerShell 设置进程内存保护，不需要管理员权限。保护初始化失败会中止启动；超限后查看终端提示和 `frontend/node_modules/.cache/docquery-memory/`，排查后手动再次执行 `pnpm dev`。不要用直接运行 Vite 绕过保护。可执行 `pnpm test:dev-guard` 检查限制和进程清理。生产静态部署不运行这些开发脚本。

## 生产

1. 后端执行 `./mvnw clean verify`，发布生成的 JAR。Windows 使用 `./mvnw.cmd`。此流程不需要 Node.js，也不执行前端命令。迁移首次构建必须 clean，防止 target 中旧静态资源进入 JAR。
2. 前端执行 `pnpm install --frozen-lockfile`、`pnpm lint`、`pnpm test`、`pnpm build`，归档 `dist`。
3. 将 `dist` 内容部署到 Nginx 的 `/usr/share/nginx/html/admin/`，将 `nginx.conf` 安装到 Nginx 的 server 配置目录。按实际后端位置修改 `proxy_pass`；容器内的 127.0.0.1 指向该容器自身。
4. 执行 `nginx -t` 后 reload，通过 `/admin/` 访问。生产使用静态资源服务器，不启动 Vite。
5. 实际公网入口配置 HTTPS；Session Cookie 和 CSRF 随同源 API 正常传递，不需要 CORS 或 JWT 改造。

默认上传请求体上限 64 MiB，覆盖后端当前 50 MiB 文件 / 51 MiB 请求上限。API 超时 360 秒，覆盖当前 300 秒问答预算。后端修改上传大小或总超时时，应同步调整 Nginx。代理不重写 API 路径、不缓存 API；带哈希资源长期缓存，HTML 和下载文档每次校验。

流式问答路径单独关闭缓冲、代理缓存和压缩。后端发送禁止缓冲的响应头，业务总预算仍为 300 秒，心跳不延长预算。配置、协议与验证记录见 [流式回答说明](streaming.md)。输出错误分类、模型输出模式和缓存规则见 [问答稳定性说明](answer-stability.md)。未通过验收的检索实验已从主代码移除，历史记录见 [Agent 检索效率实验退役说明](agent-efficiency.md)。

## 验收与回滚

- 验证 `/admin` 重定向、`/admin/#/knowledge-bases` 刷新、登录退出、写请求 CSRF、文件上传和长耗时问答。
- 验证 OpenAPI / Postman 下载及 `/admin/assets/` 缓存头，API 错误不得返回 SPA HTML。
- `pnpm test:e2e` 默认由 Playwright 启动 Vite，后端默认 8080；设置 `DOCQUERY_E2E_BASE_URL` 可直接验证已经部署的 Nginx 地址，不启动 Vite。`DOCQUERY_E2E_BROWSER_CHANNEL=msedge` 可使用本机 Edge。
- `tools/n4.3/run-admin-e2e.ps1` 验证临时 MySQL 核心链路；`tools/n4.4/run-showcase-e2e.ps1` 验证临时依赖和 Fake Gateway 文档链路。脚本不调用真实付费模型。
- `pnpm exec playwright test e2e/ui-smoke.spec.ts` 只使用模拟 API，验证界面、响应式和交互，不能作为真实后端验收证据。
- 上线保留上一版 JAR 与前端产物。独立前端版本回退只替换静态资源；首次分离改造整体回退需同时恢复旧 JAR 和旧入口路由。
- 迁移前 Git 基线：`a197d4841c1adb3b9c953fb40ed925d761017317`。没有数据库结构迁移。
