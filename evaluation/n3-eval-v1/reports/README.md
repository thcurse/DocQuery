# N3.4 基线报告边界

- `deterministic-metric-baseline.json` 证明数据集机械完整性和 Recall/MRR/nDCG/拒答判定公式，不代表检索质量。
- `deterministic-http-performance.json` 是 Fake Gateway、单活跃文档夹具的正式 HTTP 服务开销快照；它经过 Credential、Grant、activeVersion、Elasticsearch、canonical 对象读取、Redis 和 MySQL 审计，但排除了真实供应商延迟，不能作为 SLO。
- `real-provider-status.json` 明确记录真实供应商评测尚未执行。用户已决定将正式质量评测延期到完整功能落地后的发布前最终门禁，并优先适配成熟公开数据集。

所有后续报告必须新建目录或文件，不得覆盖本冒烟夹具、这三份基线状态或已有原始结果。
