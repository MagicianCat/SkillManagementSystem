package com.company.skillplatform.knowledge.domain;

public class EmbeddingProviderException extends RuntimeException {
    private final int statusCode;
    private final String providerCode;

    public EmbeddingProviderException(int statusCode, String providerCode, Throwable cause) {
        super("Embedding provider request failed: " + providerCode, cause);
        this.statusCode = statusCode;
        this.providerCode = providerCode;
    }

    public int statusCode() { return statusCode; }
    public String providerCode() { return providerCode; }
    public boolean isQuotaExhausted() { return "provider_quota_exceeded".equalsIgnoreCase(providerCode); }
}
