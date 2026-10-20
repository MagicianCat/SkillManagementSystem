package com.company.skillplatform.collabproject.asdm.infrastructure;

public class CollabAsdmGitlabException extends RuntimeException {
    private final String errorCode;
    private final int httpStatus;

    public CollabAsdmGitlabException(String errorCode, int httpStatus) {
        super(errorCode);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String errorCode() { return errorCode; }
    public int httpStatus() { return httpStatus; }
}
