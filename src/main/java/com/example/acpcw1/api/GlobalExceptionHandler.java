package com.example.acpcw1.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({
            NotFoundException.class,
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            Exception.class
    })
    public ResponseEntity<Void> handleAnyFailure(Exception ignored) {
        return ResponseEntity.status(404).build();
    }
}
