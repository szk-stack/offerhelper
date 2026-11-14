package com.interview.assistant.exception;

/**
 * 资源不存在（含「存在但不属于当前租户」，不泄露他人数据是否存在）。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
