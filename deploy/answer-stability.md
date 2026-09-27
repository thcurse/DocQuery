# 问答稳定性与检索收敛

## 最终结果与模型配置

普通和流式问答复用同一个最终校验器。合法 ANSWERED 必须有非空答案和至少一个被提供给最终模型的有效引用；部分未知引用过滤后保留有效项，全部无效则返回 ANSWER_OUTPUT_INVALID。合法 INSUFFICIENT_EVIDENCE 要求 answer=null、evidenceIds=[]。输出截断、字段类型错误和缺失字段都属于输出错误，不进入成功缓存。

`docquery.chat.profiles.<profile>.final-output-mode` 支持：

- `JSON_SCHEMA`：必填 status、answer、evidenceIds，约束枚举与类型，禁止额外字段；字段之间的关系仍由服务端校验。
- `JSON_OBJECT`：保持 JSON 模式，服务端执行相同校验。
- `PROMPT_ONLY`：使用文本模式和明确提示，服务端执行相同校验。

未配置时保留协议默认：Responses / Chat Completions 使用 JSON_OBJECT；Anthropic Messages 使用 PROMPT_ONLY。不按模型名称猜测能力，不在生产失败后自动降级或重新生成。工具选择阶段始终使用文本模式及函数调用，JSON 约束只用于最终作答。

使用锁定的 LangChain4j 1.16.2，接入依据：[官方结构化输出文档](https://docs.langchain4j.dev/tutorials/structured-outputs/)。三种协议均有本地实际 SDK 序列化测试；DeepSeek Flash、Grok 4.7 当前供应商的普通及流式合成探针均已通过，因此本地 secrets 为这两个 profile 显式启用了 JSON_SCHEMA。当前默认仍为 deepseek-flash，未改变推理配置和 300 秒预算。真实 Anthropic/Chat Completions 供应商能力未因此被认定已验证。

新增 `ANSWER_MODEL_REQUEST_INVALID` 表示模型配置不兼容，不建议原样重试；`ANSWER_OUTPUT_INVALID` 通过安全提示区分格式、引用和截断。流式校验失败必须清空预览，仅 done 确立最终答案和引用。主动停止/中断保留未校验文本。

## 缓存与当前检索行为

当前提示版本 `answer-agent-v26`，策略版本 `answer-policy-v27`，请求指纹版本 `answer-request-v5`。请求指纹包含模型配置标识、有效输出及目录预算；不包含 API Key，不清空 Redis。旧键配置冲突时沿用既有冲突响应。

2026-09-27 已移除未通过验收的旧收敛、目录分页、上下文压缩及目的提示实验。工具缓存继续按实际参数复用完整结果，Agent 自主决定搜索、阅读和提交证据。引用、最终输出校验、失败审计、取消和幂等机制保留。

## 验证与历史记录

当前保留普通与流式输出契约、三种协议 SDK 序列化、引用与租户隔离、取消/提交竞态、超时、容量、HTTP 语义及跨接口重放回归。测试采用本地替身，不自动调用真实模型。

原带收敛开关对照的 `AnswerStabilityLiveTest`、配套材料和分析脚本随实验代码归档至 `.local/archives/2026-09-27/retired-agent-experiments/`；2026-09-26 的原始结果位于 `.local/archives/2026-09-27/tmp/answer-stability-live/`。归档不参与当前测试编译。

历史稳定性报告和临时评测结果仅保留在本地，不随仓库发布，也不能当作当前版本重新测试的成绩。[实验退役说明](agent-efficiency.md)记录移除范围。

Windows 测试串行执行，Maven 与测试 JVM 分别限制堆为384 MiB，不同时启动完整评测或构建。端口仍为后端8080、前端5173，不修改系统网络、数据库或远程部署。
