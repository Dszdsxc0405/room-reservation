package org.roomreservation.d;

import java.time.Instant;
import java.util.Objects;

/** A business module supplies the recipient, text and stable identity of one notice. */
public record NoticeEvent(
        String businessOperationId,
        String stepId,
        String eventType,
        String subjectType,
        String subjectId,
        String recipientId,
        String content,
        Instant triggeredAt) {

    public NoticeEvent {
        businessOperationId = required(businessOperationId, "businessOperationId");
        stepId = required(stepId, "stepId");
        eventType = required(eventType, "eventType");
        subjectType = required(subjectType, "subjectType");
        subjectId = required(subjectId, "subjectId");
        recipientId = required(recipientId, "recipientId");
        content = required(content, "content");
        Objects.requireNonNull(triggeredAt, "triggeredAt");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
