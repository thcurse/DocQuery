package com.doc.docquery.enums;

import lombok.Getter;

/** Outbox 事件从待发布到发送完成的状态。 */
@Getter
public enum OutboxEventStatus {

    PENDING("1"),
    PUBLISHING("2"),
    SENT("3");

    private final String code;

    OutboxEventStatus(String code) {
        this.code = code;
    }
}
