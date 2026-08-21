package com.doc.docquery.service;

import com.doc.docquery.retrieval.DocumentProfileSemantic;
import com.doc.docquery.retrieval.RetrievalNodeSemantic;

import java.util.List;

/** 隔离 DeepSeek/LangChain4j 的语义卡片生成端口，单测可零成本替换。 */
public interface RetrievalCardChatGateway {

    List<GeneratedNode> generateNodes(List<NodeInput> inputs, String correctionHint);

    DocumentProfileSemantic generateProfile(ProfileInput input, String correctionHint);

    record NodeInput(String requestId, String titlePath, String sourceText) {
    }

    record GeneratedNode(String requestId, RetrievalNodeSemantic semantic) {
    }

    record ProfileInput(String requestId, String documentTitle, String sourceText) {
    }
}
