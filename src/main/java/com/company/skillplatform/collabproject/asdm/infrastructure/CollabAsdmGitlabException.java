package com.company.skillplatform.collabproject.asdm.infrastructure;

public class CollabAsdmGitlabException extends RuntimeException {
    private final String errorCode;
    private final int httpStatus;

    public CollabAsdmGitlabException(String errorCode, int httpStatus) {
        this(errorCode,httpStatus,null);
    }

    public CollabAsdmGitlabException(String errorCode,int httpStatus,Throwable cause){super(errorCode,cause);this.errorCode=errorCode;this.httpStatus=httpStatus;}
    public String errorCode() { return errorCode; }
    public int httpStatus() { return httpStatus; }
}
