package io.agentbridge.orders;

class NotFoundException extends RuntimeException {
    private final String code;

    NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    String getCode() {
        return code;
    }
}

class ConflictException extends RuntimeException {
    private final String code;

    ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    String getCode() {
        return code;
    }
}
