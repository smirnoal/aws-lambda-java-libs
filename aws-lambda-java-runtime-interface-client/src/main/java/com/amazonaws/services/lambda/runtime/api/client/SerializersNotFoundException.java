/* Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved. */
 
package com.amazonaws.services.lambda.runtime.api.client;
 
public class SerializersNotFoundException extends IllegalArgumentException {
    public SerializersNotFoundException() {
 
    }
 
    public SerializersNotFoundException(String errorMessage) {
        super(errorMessage);
    }
 
    public SerializersNotFoundException(Throwable cause) {
        super(cause);
    }
 
    public SerializersNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}