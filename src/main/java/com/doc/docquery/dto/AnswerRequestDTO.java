package com.doc.docquery.dto;

import lombok.Getter;
import lombok.Setter;

/** 传统业务后端对一个明确 KnowledgeBase 发起的受控单轮回答请求。 */
@Getter
@Setter
public class AnswerRequestDTO {

    private String query;
    private String mode;
    private Integer topK;
}
