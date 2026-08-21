# DeepDoc HTTP 服务

该目录把 N5.1-P0 已验证的 DeepDoc GPU 镜像包装为 DocQuery 内部 HTTP 服务。服务统一处理 PDF、DOCX、TXT 和 Markdown；RabbitMQ、`ProcessingJob`、重试和完整入库状态仍由 Java 应用负责。

生产 DeepDoc 已是根 Compose 的默认服务。启动前先按 `../document-parser-p0/README.md` 构建固定 GPU 基础镜像，然后执行：

```powershell
docker compose up -d --build deepdoc
```

健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:18080/health/ready
```

Java 应用默认启用 DeepDoc，只需保证服务地址可达：

```text
DOCQUERY_DEEPDOC_ENABLED=true
DOCQUERY_DEEPDOC_BASE_URL=http://127.0.0.1:18080
```

只有需要显式回退到原本地四格式 Adapter 时才设置 `DOCQUERY_DEEPDOC_ENABLED=false`。

解析请求必须同时携带原文件摘要 `X-Source-SHA256` 和显式格式 `X-Source-Format`；格式值只允许 `PDF`、`DOCX`、`TXT`、`MARKDOWN`。服务返回同一中立块结构，并按格式返回 PDF 页位置、DOCX 正文元素位置或 TXT/Markdown 行位置。

服务内部 `PARSER_MAX_CONCURRENCY` 在 P1 固定为 `1`。并发 HTTP 请求会等待唯一解析槽位，不为正常忙碌返回 `429`。

`PARSER_MAX_REQUEST_BYTES` 限制完整 multipart 请求，必须略大于 DocQuery 的原文件上限；默认与 `DOCQUERY_UPLOAD_MAX_REQUEST_BYTES=53477376` 对齐。

容器使用固定镜像已有的 Uvicorn WSGI Server，固定单 Worker，确保只初始化一份 DeepDoc 模型；HTTP 请求线程可以等待同一个进程内的唯一解析槽位。
