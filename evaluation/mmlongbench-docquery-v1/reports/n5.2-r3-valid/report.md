# DocQuery N5.2-R3 检索有效集评测报告

- 状态：`COMPLETED`
- 生成时间：`2026-08-22T17:11:50.090701+00:00`
- 80 道首次执行的公开数据集原题；全库检索，不传 `documentIds`
- HYBRID，topK=10；成功 80/80，降级 0，重试 0
- Answer / DeepSeek：未调用

| Doc Hit@1 | Doc Hit@3 | Doc Hit@5 | Doc Hit@10 | Any Page@10 | Page Coverage@10 | All Pages@10 | MRR@10 | P95 ms |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.8000 | 0.8875 | 0.9250 | 0.9625 | 0.7375 | 0.6554 | 0.5875 | 0.8585 | 436.145 |

## 分文档类型

| 类型 | 题数 | Doc Hit@1 | Doc Hit@5 | Doc Hit@10 | Page Coverage@10 |
|---|---:|---:|---:|---:|---:|
| Academic paper | 9 | 0.7778 | 1.0000 | 1.0000 | 0.7778 |
| Administration/Industry file | 8 | 0.7500 | 0.8750 | 0.8750 | 0.6875 |
| Brochure | 7 | 0.8571 | 1.0000 | 1.0000 | 0.6429 |
| Financial report | 12 | 0.5833 | 1.0000 | 1.0000 | 0.5833 |
| Guidebook | 14 | 0.9286 | 0.9286 | 1.0000 | 0.7245 |
| Research report / Introduction | 24 | 0.8750 | 0.8750 | 0.9583 | 0.5536 |
| Tutorial/Workshop | 6 | 0.6667 | 0.8333 | 0.8333 | 0.8333 |

失败样本共 `33` 题，其中 Top10 未找到 Gold 文档 `3` 题，已找到文档但 Gold 页未完全覆盖 `30` 题。

## 边界

R3 只评估当前测试科技租户、评测应用、100 份 READY PDF 和当前索引快照上的顺序 HYBRID 检索。问题来自公开集且在选题时未执行过；不评估 Answer、不可回答题、并发容量、OCR 或复杂视觉语义。
