package com.doc.docquery.parser;

/** 根节点或文件中真实存在的标题节点，不包含任何模型生成字段。 */
public record HeadingNode(
        String nodeId,
        String parentNodeId,
        int declaredLevel,
        int depth,
        String title,
        String sourceHeadingBlockId,
        String detectionSource,
        int sectionStartBlockOrdinal,
        int sectionEndBlockOrdinalExclusive
) {
}
