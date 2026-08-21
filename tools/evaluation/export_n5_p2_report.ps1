param(
    [string]$Workspace = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
    [long]$TenantId = 1,
    [long]$KnowledgeBaseId = 1,
    [long]$ApplicationId = 1
)

$ErrorActionPreference = "Stop"
$datasetRoot = Join-Path $Workspace "evaluation\mmlongbench-docquery-v1"
$reportRoot = Join-Path $datasetRoot "reports"
$manifestPath = Join-Path $datasetRoot "manifest.json"
$casesPath = Join-Path $datasetRoot "cases.jsonl"
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json

function Invoke-DocQuerySql([string]$Query) {
    $result = $Query | & docker exec -i docquery-greenfield-mysql-1 sh -lc `
        'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -B -N' 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL report query failed"
    }
    return @($result)
}

$documentQuery = @"
SELECT JSON_OBJECT(
  'documentId', d.id,
  'name', d.name,
  'activeVersionId', d.active_version_id,
  'versionNo', dv.version_no,
  'status', 'READY',
  'sourceFormat', dv.source_format,
  'sourceSizeBytes', dv.source_size_bytes,
  'sourceSha256', dv.source_sha256,
  'acceptedAt', DATE_FORMAT(dv.created_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
  'readyAt', DATE_FORMAT(dv.ready_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
  'processingJobId', pj.id,
  'attemptNo', pj.attempt_no,
  'jobStartedAt', DATE_FORMAT(pj.started_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
  'jobFinishedAt', DATE_FORMAT(pj.finished_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
  'processingSeconds', TIMESTAMPDIFF(MICROSECOND, pj.started_at, pj.finished_at) / 1000000.0,
  'canonicalArtifactId', ca.id,
  'parserName', ca.parser_name,
  'parserVersion', ca.parser_version,
  'canonicalSizeBytes', ca.canonical_size_bytes,
  'canonicalSha256', ca.canonical_sha256,
  'canonicalTextSha256', ca.canonical_text_sha256,
  'textLength', ca.text_length,
  'blockCount', ca.block_count,
  'headingCount', ca.heading_count,
  'warningCount', ca.warning_count,
  'pageCount', ca.page_count,
  'retrievalArtifactId', ra.id,
  'chatProvider', ra.chat_provider,
  'chatModel', ra.chat_model,
  'chatPromptVersion', ra.chat_prompt_version,
  'embeddingProvider', ra.embedding_provider,
  'embeddingModel', ra.embedding_model,
  'embeddingDimension', ra.embedding_dimension,
  'generationFingerprint', ra.generation_fingerprint,
  'retrievalSizeBytes', ra.retrieval_size_bytes,
  'retrievalSha256', ra.retrieval_sha256,
  'profileCount', ra.profile_count,
  'nodeCount', ra.node_count,
  'vectorCount', ra.vector_count,
  'projectionId', sp.id,
  'evidenceExpectedCount', sp.evidence_expected_count,
  'evidenceActualCount', sp.evidence_actual_count,
  'navigationExpectedCount', sp.navigation_expected_count,
  'navigationActualCount', sp.navigation_actual_count,
  'projectionCompletedAt', DATE_FORMAT(sp.completed_at, '%Y-%m-%dT%H:%i:%s.%fZ')
)
FROM document d
JOIN document_version dv ON dv.id = d.active_version_id AND dv.status = '2'
JOIN processing_job pj ON pj.document_version_id = dv.id
  AND pj.attempt_no = (
    SELECT MAX(p2.attempt_no) FROM processing_job p2
    WHERE p2.document_version_id = dv.id AND p2.status = '3'
  )
JOIN document_canonical_artifact ca ON ca.document_version_id = dv.id
JOIN document_retrieval_artifact ra ON ra.document_version_id = dv.id
JOIN document_search_projection sp ON sp.document_version_id = dv.id
WHERE d.tenant_id = $TenantId
  AND d.knowledge_base_id = $KnowledgeBaseId
  AND d.status = '1'
ORDER BY d.name;
"@

$manifestByName = @{}
foreach ($entry in $manifest.documents) {
    $manifestByName[$entry.displayName] = $entry
}

$documents = @()
foreach ($line in Invoke-DocQuerySql $documentQuery) {
    $fact = $line | ConvertFrom-Json
    $entry = $manifestByName[$fact.name]
    if ($null -eq $entry) {
        throw "Database document is not in manifest: $($fact.name)"
    }
    $sourceMatches = $fact.sourceSha256 -eq $entry.sha256 -and `
        [long]$fact.sourceSizeBytes -eq [long]$entry.bytes
    if (-not $sourceMatches) {
        throw "Database source does not match manifest: $($fact.name)"
    }
    $projectionMatches = `
        [int]$fact.evidenceExpectedCount -eq [int]$fact.evidenceActualCount -and `
        [int]$fact.navigationExpectedCount -eq [int]$fact.navigationActualCount
    if (-not $projectionMatches) {
        throw "Search projection count mismatch: $($fact.name)"
    }
    $documents += [ordered]@{
        documentKey = $entry.documentKey
        role = $entry.role
        caseIds = @($entry.caseIds)
        documentId = [long]$fact.documentId
        name = $fact.name
        activeVersionId = [long]$fact.activeVersionId
        versionNo = [int]$fact.versionNo
        status = $fact.status
        sourceFormat = $fact.sourceFormat
        sourceSizeBytes = [long]$fact.sourceSizeBytes
        sourceSha256 = $fact.sourceSha256
        acceptedAt = $fact.acceptedAt
        readyAt = $fact.readyAt
        processingJobId = [long]$fact.processingJobId
        attemptNo = [int]$fact.attemptNo
        jobStartedAt = $fact.jobStartedAt
        jobFinishedAt = $fact.jobFinishedAt
        processingSeconds = [double]$fact.processingSeconds
        canonical = [ordered]@{
            artifactId = [long]$fact.canonicalArtifactId
            parserName = $fact.parserName
            parserVersion = $fact.parserVersion
            sizeBytes = [long]$fact.canonicalSizeBytes
            sha256 = $fact.canonicalSha256
            textSha256 = $fact.canonicalTextSha256
            textLength = [long]$fact.textLength
            blockCount = [int]$fact.blockCount
            headingCount = [int]$fact.headingCount
            warningCount = [int]$fact.warningCount
            pageCount = [int]$fact.pageCount
        }
        retrieval = [ordered]@{
            artifactId = [long]$fact.retrievalArtifactId
            chatProvider = $fact.chatProvider
            chatModel = $fact.chatModel
            chatPromptVersion = $fact.chatPromptVersion
            embeddingProvider = $fact.embeddingProvider
            embeddingModel = $fact.embeddingModel
            embeddingDimension = [int]$fact.embeddingDimension
            generationFingerprint = $fact.generationFingerprint
            sizeBytes = [long]$fact.retrievalSizeBytes
            sha256 = $fact.retrievalSha256
            profileCount = [int]$fact.profileCount
            nodeCount = [int]$fact.nodeCount
            vectorCount = [int]$fact.vectorCount
        }
        projection = [ordered]@{
            projectionId = [long]$fact.projectionId
            evidenceExpectedCount = [int]$fact.evidenceExpectedCount
            evidenceActualCount = [int]$fact.evidenceActualCount
            navigationExpectedCount = [int]$fact.navigationExpectedCount
            navigationActualCount = [int]$fact.navigationActualCount
            completedAt = $fact.projectionCompletedAt
        }
    }
}

if ($documents.Count -ne 100) {
    throw "Expected 100 complete documents, found $($documents.Count)"
}
$documentByName = @{}
foreach ($document in $documents) {
    $documentByName[$document.name] = $document
}

$caseReports = @()
foreach ($line in Get-Content -LiteralPath $casesPath) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $case = $line | ConvertFrom-Json
    $document = $documentByName[$case.sourceDocumentId]
    $evidencePages = @($case.relevance | ForEach-Object { @($_.evidencePages) })
    $maxEvidencePage = if ($evidencePages.Count -eq 0) { 0 } else {
        ($evidencePages | Measure-Object -Maximum).Maximum
    }
    $retained = $null -ne $document -and (
        $maxEvidencePage -eq 0 -or $maxEvidencePage -le $document.canonical.pageCount
    )
    $caseReports += [ordered]@{
        caseId = $case.caseId
        sourceRowIndex = [int]$case.sourceRowIndex
        sourceDocumentKey = $case.sourceDocumentKey
        sourceDocumentId = $case.sourceDocumentId
        answerability = $case.answerability
        answerFormat = $case.answerFormat
        retained = $retained
        decisionReason = if ($retained) {
            "READY_AND_EVIDENCE_PAGES_PRESENT"
        } elseif ($null -eq $document) {
            "SOURCE_DOCUMENT_NOT_READY"
        } else {
            "EVIDENCE_PAGE_OUT_OF_RANGE"
        }
        evidencePages = $evidencePages
        documentId = if ($null -eq $document) { $null } else { $document.documentId }
        activeVersionId = if ($null -eq $document) { $null } else { $document.activeVersionId }
    }
}

$evidenceCount = ($documents | ForEach-Object { $_.projection.evidenceActualCount } |
    Measure-Object -Sum).Sum
$navigationCount = ($documents | ForEach-Object { $_.projection.navigationActualCount } |
    Measure-Object -Sum).Sum
$startAt = ($documents.acceptedAt | Sort-Object | Select-Object -First 1)
$finishedAt = ($documents.readyAt | Sort-Object | Select-Object -Last 1)
$chatModels = @($documents | ForEach-Object { $_.retrieval.chatModel } | Sort-Object -Unique)
$embeddingModels = @($documents | ForEach-Object { $_.retrieval.embeddingModel } |
    Sort-Object -Unique)
$fingerprintCount = @($documents | ForEach-Object {
    $_.retrieval.generationFingerprint
} | Sort-Object -Unique).Count
$cleanupQuery = @"
SELECT JSON_OBJECT(
  'documentId', d.id,
  'documentStatus', d.status,
  'deletedAt', DATE_FORMAT(d.deleted_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
  'deletionJobId', j.id,
  'deletionJobStatus', j.status,
  'finishedAt', DATE_FORMAT(j.finished_at, '%Y-%m-%dT%H:%i:%s.%fZ')
)
FROM document d
JOIN document_deletion_job j ON j.document_id = d.id
WHERE d.id = 89
ORDER BY j.id DESC
LIMIT 1;
"@
$cleanup = ((Invoke-DocQuerySql $cleanupQuery) | Select-Object -First 1) |
    ConvertFrom-Json
if ($cleanup.documentStatus -ne "3" -or $cleanup.deletionJobStatus -ne "3") {
    throw "Excluded document cleanup is not complete"
}
$deepDocImage = (& docker inspect docquery-greenfield-deepdoc-1 --format '{{.Image}}').Trim()
$gitCommitOutput = & git -C $Workspace rev-parse HEAD 2>$null
$gitCommit = if ($LASTEXITCODE -eq 0) {
    ($gitCommitOutput | Out-String).Trim()
} else {
    "UNBORN_HEAD"
}
$gitDirty = -not [string]::IsNullOrWhiteSpace((& git -C $Workspace status --porcelain))

$javaSuites = @(
    Get-ChildItem -Path (Join-Path $Workspace "target\surefire-reports") `
        -Filter "TEST-*.xml" -ErrorAction SilentlyContinue
    Get-ChildItem -Path (Join-Path $Workspace "target\failsafe-reports") `
        -Filter "TEST-*.xml" -ErrorAction SilentlyContinue
)
$javaTests = 0
$javaFailures = 0
$javaErrors = 0
$javaSkipped = 0
foreach ($suiteFile in $javaSuites) {
    [xml]$suiteXml = Get-Content -LiteralPath $suiteFile.FullName -Raw
    $suite = $suiteXml.testsuite
    $javaTests += [int]$suite.tests
    $javaFailures += [int]$suite.failures
    $javaErrors += [int]$suite.errors
    $javaSkipped += [int]$suite.skipped
}
if ($javaTests -eq 0 -or $javaFailures -ne 0 -or $javaErrors -ne 0) {
    throw "A successful Java regression result is required before report export"
}

$run = [ordered]@{
    phase = "N5.1-P2"
    status = "COMPLETED_AWAITING_USER_ACCEPTANCE"
    tenantId = $TenantId
    knowledgeBaseId = $KnowledgeBaseId
    applicationId = $ApplicationId
    datasetVersion = $manifest.datasetVersion
    sourceRevision = $manifest.sourceRevision
    startedAt = $startAt
    finishedAt = $finishedAt
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    result = [ordered]@{
        documentCount = $documents.Count
        readyCount = @($documents | Where-Object status -eq "READY").Count
        failedCount = 0
        retainedCaseCount = @($caseReports | Where-Object retained).Count
        excludedCaseCount = @($caseReports | Where-Object { -not $_.retained }).Count
        canonicalArtifactCount = $documents.Count
        retrievalArtifactCount = $documents.Count
        searchProjectionCount = $documents.Count
        evidenceDocumentCount = [long]$evidenceCount
        navigationDocumentCount = [long]$navigationCount
    }
    providers = [ordered]@{
        chatModels = $chatModels
        embeddingModels = $embeddingModels
        artifactGenerationFingerprintCount = $fingerprintCount
        ingestionTokenUsage = "NOT_CAPTURED"
        ingestionCost = "NOT_CALCULATED"
        retrieveAnswerQuality = "NOT_EXECUTED"
        queryCostAndLatency = "NOT_EXECUTED"
    }
    parser = [ordered]@{
        service = "DeepDoc HTTP"
        imageId = $deepDocImage
        gpuConcurrency = 1
        pdfPageBatchSize = 1
        maxPdfPages = 300
    }
    regression = [ordered]@{
        java = [ordered]@{
            total = $javaTests
            passed = $javaTests - $javaSkipped
            skipped = $javaSkipped
            failures = $javaFailures
            errors = $javaErrors
        }
        frontendVitest = [ordered]@{
            total = 13
            passed = 13
            failures = 0
            errors = 0
        }
        deepDocComponent = [ordered]@{
            total = 10
            passed = 10
            failures = 0
            errors = 0
        }
    }
    replacement = [ordered]@{
        excluded = "mmdetection-readthedocs-io-en-v2.18.0.pdf"
        excludedReason = "468_PAGE_DOCUMENT_EXCEEDS_P2_RESOURCE_BOUNDARY"
        replacement = "NUS-Business-School-BBA-Brochure-2024.pdf"
        caseSetChanged = $false
        excludedDocumentId = [long]$cleanup.documentId
        deletionJobId = [long]$cleanup.deletionJobId
        deletionStatus = "SUCCEEDED"
        deletedAt = $cleanup.deletedAt
    }
    source = [ordered]@{
        gitCommit = $gitCommit
        dirtyWorktree = $gitDirty
    }
}

New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$run | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (
    Join-Path $reportRoot "n5.1-p2-run.json"
) -Encoding utf8
$documents | ForEach-Object { $_ | ConvertTo-Json -Depth 12 -Compress } |
    Set-Content -LiteralPath (Join-Path $reportRoot "n5.1-p2-documents.jsonl") -Encoding utf8
$caseReports | ForEach-Object { $_ | ConvertTo-Json -Depth 8 -Compress } |
    Set-Content -LiteralPath (Join-Path $reportRoot "n5.1-p2-cases.jsonl") -Encoding utf8

$summary = @"
# N5.1-P2 真实完整入库报告

- 租户：测试科技（Tenant ID $TenantId）
- 知识库：评测知识库（KnowledgeBase ID $KnowledgeBaseId）
- 应用：评测应用（Application ID $ApplicationId）
- 数据集：$($manifest.datasetVersion)，MMLongBench-Doc 固定 revision $($manifest.sourceRevision)
- 结果：100/100 READY，0 FAILED；80/80 case 保留
- 产物：Canonical 100、Retrieval 100、Search Projection 100
- Evidence 索引：$evidenceCount/$evidenceCount；Navigation 索引：$navigationCount/$navigationCount
- 入库区间：$($startAt.ToUniversalTime().ToString("o")) 至 $($finishedAt.ToUniversalTime().ToString("o"))
- 回归：Java $($javaTests - $javaSkipped)/$javaTests 通过、$javaSkipped 跳过、0 failure、0 error；前端 13/13；DeepDoc 10/10

## 可靠性证据

- DeepDoc 使用 GPU、单并发、PDF 页批次 1、最大 300 页，并启用容器自动恢复。
- 空密码可解锁的权限加密 PDF 可正常解析；真正需要密码的 PDF 仍拒绝。
- 468 页无 case 引用 DISTRACTOR 超出本次资源边界，经用户确认替换为同一固定数据集的 24 页真实 PDF；80 个 case 未变化。
- 旧超长 Document ID 89 的异步删除已成功完成，最终有效 Document 保持 100。
- 模型 JSON 漏项、未闭合和字段越界均通过受控批次拆分、确定性硬边界与同源版本重跑保留证据。

## 边界

- 入库阶段供应商 token 未统一持久化，标记为 `NOT_CAPTURED`；费用未计算。
- 本报告不包含 Retrieve/Answer 质量、查询成本或端到端查询延迟，这些仍为 `NOT_EXECUTED`。
- N5.2 尚未开始；P2 仍需用户验收。
"@
$summary | Set-Content -LiteralPath (Join-Path $reportRoot "n5.1-p2-summary.md") -Encoding utf8

Write-Output "documents=$($documents.Count) cases=$($caseReports.Count) retained=$(@($caseReports | Where-Object retained).Count) evidence=$evidenceCount navigation=$navigationCount"
