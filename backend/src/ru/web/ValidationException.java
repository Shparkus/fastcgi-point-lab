package ru.web;

class ValidationException extends Exception {
    public ValidationException(String message) {
        super(message);
    }
}