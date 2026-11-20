package org.roomreservation.platform;

/** Join the caller's business transaction. The returned ID is durable only after that transaction commits. */
public interface InAppNoticePort {
    long enqueue(NoticeEvent event);
}
