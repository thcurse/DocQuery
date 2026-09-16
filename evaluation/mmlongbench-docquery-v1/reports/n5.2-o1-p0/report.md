# N5.2-O1-P0 技术验证报告

状态：`ACCEPTED / CLOSED`

## 结论

完整超长章节可以直接进入 DeepSeek V4 Flash，但模型不能独自可靠承担最终分区数量和最大长度约束。可行架构是“模型提供语义边界候选，本地约束选择器形成最终连续范围”。

2026-08-21 按授权执行的第一版两次真实模型复验均因语义边界候选超过旧安全上限 32 而失败。V2 改用有界动态规划后，经用户单独授权再次对冻结的两份章节各调用一次且不重试：两份均形成允许数量内的完整连续分区，所有硬性校验通过。用户随后明确说“确认 N5.2-O1-P0 完成”，本阶段已验收关闭；该验收不授权接入正式链路或重跑 R2。

## 真实尝试摘要

| 尝试 | 3M OVERVIEW | Activision Overview | 结论 |
| --- | --- | --- | --- |
| 直接最终分区 | 3段、完整覆盖；末段 96,796 token，超限 | 分区数量不满足约束 | 模型不能独自保证硬边界 |
| 稠密候选 + 旧严格数量校验 | 候选数量提示未满足，拒绝 | 候选数量提示未满足，拒绝 | 候选数量不应是正确性契约 |
| 候选容错 + 本地约束选择 | 返回 76 个可用候选，超过安全上限 32，拒绝 | 返回 105 个可用候选，超过安全上限 32，拒绝 | 提示没有约束住候选密度，选择器未执行 |

## 最终两次复验

严格使用冻结的两份真实章节，各请求一次，无自动重试，共 `2` 次付费请求。

| 样本 | 状态 | 候选数 | 延迟 | prompt / completion / total token | cache hit / miss token |
| --- | --- | ---: | ---: | ---: | ---: |
| `3M_2018_10K.pdf` / `OVERVIEW` | `FAILED` | 76 | 16,619 ms | 159,473 / 2,758 / 162,231 | 159,360 / 113 |
| `ACTIVISIONBLIZZARD_2019_10K.pdf` / `Overview` | `FAILED` | 105 | 23,961 ms | 74,637 / 4,916 / 79,553 | 74,624 / 13 |

两次均正常结束（`finish_reason=stop`），但候选数超过原型为防止异常输出膨胀设置的 32 个上限，失败码均为 `O1_P0_VALIDATION_FAILED`。这是模型输出契约未收敛，不是 canonical 读取、供应商连通性或超长上下文失败。由于校验在最终范围选择之前终止，本轮没有最终分区，覆盖率、gap/overlap 和单段 64K 上限不能判为通过。

本轮合计 `prompt_tokens=234,110`、`completion_tokens=7,674`、`total_tokens=241,784`；其中 `prompt_cache_hit_tokens=233,984`、`prompt_cache_miss_tokens=126`。调用结束后已停止付费评测，没有追加重试。

## 零费用 V2 修正

- 提示词明确要求只返回 6—13 个最强主话题边界，禁止按段落、表格行、页码或轻微过渡逐项返回；
- 候选数量仍是输出质量提示，不是最终分区数量；响应安全上限从任意的 32 改为受 `max_tokens` 和本地校验共同约束的 256；
- 本地选择器改为 `O(最终分区数 × 候选数²)` 的有界动态规划，从模型给出的真实语义起点中选择最少 2—4 个最终分区；
- 本地程序不会按 token 窗口创造新边界，未入选候选不会持久化、向量化或进入检索结果；
- 192 个候选的压力用例能够稳定选择 3 个语义强边界；257 个候选会被安全拒绝；
- P0 测试 `9/9` 通过，两份冻结 canonical 为 `2/2 DRY_RUN_READY`，供应商请求数为 `0`。

## V2 两次真实复验

用户于 2026-08-21 明确授权“确认执行 O1-P0 V2 两次复验”。冻结的两份真实章节各请求一次，无自动重试，共 `2` 次付费请求。

| 样本 | 最终分区 | 模型候选 | 覆盖率 | gap / overlap / 超限 | 最大分区估算 token | 延迟 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 3M `OVERVIEW` | 3 | 115 | 1.0 | 0 / 0 / 0 | 44,582 | 29,584 ms |
| Activision `Overview` | 2 | 10 | 1.0 | 0 / 0 / 0 | 36,472 | 5,914 ms |

最终导航范围：

- 3M：`OVERVIEW` `[262,756)`、`Notes to Consolidated Financial Statements` `[756,1095)`、`NOTE 14. Derivatives` `[1095,1606)`；
- Activision：`Overview and Strategy` `[126,412)`、`Management's Discussion and Analysis` `[412,926)`。

两份均为 `VALID`、`finish_reason=stop`，canonical 标题树均未改变。本轮合计 `prompt_tokens=234,190`、`completion_tokens=4,996`、`total_tokens=239,186`；缓存命中为 0，全部 prompt token 计入 cache miss。脱敏结果完整保存于 `v2-real-replay.json`。

3M 的模型候选数 115，仍明显高于提示目标 9—13，但只有本地选出的 3 个最终分区会进入后续派生导航结构，另外 112 个不会持久化或向量化。因此它不违反本阶段冻结的最终分区正确性条件，但属于后续生产化时应治理的输出效率问题。

## 产物

- `attempt-1-direct-partitions.json`：第一轮脱敏结果；
- `attempt-2-strict-candidates.json`：第二轮脱敏失败结果；
- `final-prototype-dry-run.json`：最终原型零费用 dry-run；
- `final-prototype-v2-dry-run.json`：V2 动态规划原型的零费用 dry-run；
- `v2-real-replay.json`：V2 两次真实复验的脱敏通过结果；
- `final-real-replay.json`：最终两次复验的脱敏失败结果；
- `n5.2-o1-p0-selection.json`：两份冻结样本；
- `tools/evaluation/run_n5_o1_p0.py`：隔离原型；
- `tools/evaluation/test_n5_o1_p0.py`：离线测试。

未修改正式入库、投影或检索链路。
