package com.doc.docquery.stream;

import com.doc.docquery.vo.AnswerResponseVO;

/** Prepared authority and idempotency ownership, used once by its worker. */
public interface PreparedAnswer {
    AnswerResponseVO execute(AnswerExecution execution);
    boolean replayed();
    void renew();
    void abandon();
    default void reject(RuntimeException cause) { abandon(); }
}
