package org.roomreservation.platform;

/** The same event identity was reused for a different notice. */
public class NoticeEventConflictException extends RuntimeException {
    public NoticeEventConflictException(String message) {
        super(message);
    }
}
