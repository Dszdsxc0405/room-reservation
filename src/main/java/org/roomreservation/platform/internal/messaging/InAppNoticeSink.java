package org.roomreservation.platform.internal.messaging;

/** D's post-commit destination. A failed call must roll back its own writes before retry. */
interface InAppNoticeSink {
    void publish(long noticeId, String recipientId, String content);
}
