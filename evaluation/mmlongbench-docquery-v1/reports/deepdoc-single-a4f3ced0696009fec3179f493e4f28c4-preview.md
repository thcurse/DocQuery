# DeepDoc 单文档解析预览

## 输入

- 文件：`a4f3ced0696009fec3179f493e4f28c4.pdf`
- 类型：MMLongBench-Doc `CASE_SOURCE`
- SHA-256：`702351312f3241cc51d140064ba02ac63ec50581c3a8a664c9249908d8c0c30b`
- PDF 页数：17
- 文件大小：100,113 字节
- 官方问题：`WHAT IS USCA CASE NUMBER?`
- 官方答案：`21-13199`
- 官方证据页：第 1 页

## DeepDoc HTTP 结果

- HTTP：`200`
- Schema：`docquery-deepdoc-http-v1`
- Request ID：`e84ea35c-8bba-4bf6-9f93-c02c7c57bf10`
- 客户端总耗时：约 13.88 秒
- 服务报告解析耗时：14,940 ms
- 页数：17
- 块数：80
- 块类型：1 个 `TITLE`、6 个 `HEADING`、73 个 `PARAGRAPH`
- 页覆盖：17/17 页均有块
- 警告：`DEEPDOC_HEADING_LEVEL_FLATTENED`，标题块使用扁平的已验证标题层级

各页块数：

```text
1:7, 2:8, 3:5, 4:2, 5:3, 6:5, 7:5, 8:4, 9:5,
10:3, 11:7, 12:7, 13:5, 14:2, 15:6, 16:3, 17:3
```

## 第 1 页解析块

| 页内序号 | 类型 | 文本 |
| ---: | --- | --- |
| 0 | PARAGRAPH | `[PUBLISH]` |
| 1 | TITLE | `Jn the` |
| 2 | HEADING | `Hnited States Cmurt nf Appeals Jur the Fleuenth Cirruit` |
| 3 | PARAGRAPH | `No. 21-13199` |
| 4 | PARAGRAPH | `MARTIN COWEN, an individual, ALLEN BUCKLEY, an individual, AARON GILMER, an individual, JOHN MONDS, an individual, LIBERTARIAN PARTY OF GEORGIA, INC., a Georgia nonprofit corporation,` |
| 5 | PARAGRAPH | `Plaintiffs-Appellees- Cross Appellants,` |
| 6 | PARAGRAPH | `versus` |

## 结论

- 官方证据页第 1 页已被解析，答案 `21-13199` 精确出现在第 1 页第 3 个块中。
- 17 页全部得到带页码和页内顺序的文本块。
- 装饰性哥特字体标题存在明显 OCR 字符错误，例如 `In the` 被识别为 `Jn the`，`United States Court of Appeals for the Eleventh Circuit` 被识别为 `Hnited States Cmurt nf Appeals Jur the Fleuenth Cirruit`。
- 本次只验证 DeepDoc HTTP 输出，没有进入租户、RabbitMQ、检索卡、Embedding 或索引链路。
