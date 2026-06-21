package com.docqa.exception;

import org.springframework.http.HttpStatus;

public class UserAlreadyExistsException extends ApiException {

    public UserAlreadyExistsException(String email) {
        super(HttpStatus.CONFLICT, "User already exists with email: " + email);
    }
}
