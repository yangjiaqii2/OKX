package com.example.quant.common;

public record Result<T>(boolean success, String message, T data) {
    public static <T> Result<T> ok(T data) {
        return new Result<>(true, "OK", data);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(false, message, null);
    }
}
