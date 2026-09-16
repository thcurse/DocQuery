# DocQuery N5.3 Answer 评测报告

- 状态：`COMPLETED_FAILED_ACCEPTANCE`
- 生成时间：`2026-08-26T11:10:20.591091+00:00`
- 20 道冻结的 Answer 公开数据集原题；20 份不同文档；不传 `documentIds`
- HYBRID，topK=10；成功 20/20，降级 0，重试 0
- 精确 Token / 费用：`NOT_AVAILABLE_IN_CURRENT_ANSWER_CONTRACT`

| Answered | 自动参考答案匹配 | 正确文档引用 | 任意 Gold 页引用 | Gold 页覆盖率 | 全部 Gold 页引用 | 成功响应 P95 ms |
|---:|---:|---:|---:|---:|---:|---:|
| 0.7500 | 0.4000 | 0.7500 | 0.6500 | 0.6250 | 0.6000 | 53372.949 |

以上质量指标以全部 20 个逻辑请求为分母，请求失败计 0；自动答案匹配只用于初筛，最终答案正确性和引用语义支持度以人工复核产物为准。

## 人工复核与验收

- 按 PDF 原文纠错后的正确答案：12/20（0.6000）
- 无法由引用完整支持的回答：1 题
- 数据集问题：{"GOLD_PAGE_INCOMPLETE": 1, "GOLD_PAGE_INCORRECT": 2, "REFERENCE_ANSWER_INCORRECT": 2}
- 验收：`FAILED`
