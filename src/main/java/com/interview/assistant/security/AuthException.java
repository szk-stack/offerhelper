package com.interview.assistant.security;

/**
 * 鉴权失败：交给 GlobalExceptionHandler 转成 401。
 */
public class AuthException extends RuntimeException {

    public AuthException(String message) {
        super(message);
    }
}
