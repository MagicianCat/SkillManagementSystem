package com.company.skillplatform.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime controls for environment tagged Feishu card actions. */
@ConfigurationProperties(prefix = "skill-platform.feishu.card")
public class FeishuCardProperties {
    private String environment = "";
    private boolean actionEnabled;
    private boolean gatewayEnabled;
    private String legacyEnv = "reject";
    private Route routeLocal = new Route();
    private Route routeProd = new Route();
    private Forward forward = new Forward();
    private Security security = new Security();

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public boolean isActionEnabled() { return actionEnabled; }
    public void setActionEnabled(boolean actionEnabled) { this.actionEnabled = actionEnabled; }
    public boolean isGatewayEnabled() { return gatewayEnabled; }
    public void setGatewayEnabled(boolean gatewayEnabled) { this.gatewayEnabled = gatewayEnabled; }
    public String getLegacyEnv() { return legacyEnv; }
    public void setLegacyEnv(String legacyEnv) { this.legacyEnv = legacyEnv; }
    public Route getRouteLocal() { return routeLocal; }
    public void setRouteLocal(Route routeLocal) { this.routeLocal = routeLocal == null ? new Route() : routeLocal; }
    public Route getRouteProd() { return routeProd; }
    public void setRouteProd(Route routeProd) { this.routeProd = routeProd == null ? new Route() : routeProd; }
    public Forward getForward() { return forward; }
    public void setForward(Forward forward) { this.forward = forward == null ? new Forward() : forward; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security == null ? new Security() : security; }

    public void validate(boolean eventEnabled) {
        if (environment != null && !environment.isBlank() && !java.util.Set.of("local", "testing", "prod").contains(environment)) {
            throw new IllegalStateException("FEISHU_CARD_ENV must be local, testing, or prod");
        }
        if ((actionEnabled || gatewayEnabled) && (environment == null || environment.isBlank())) {
            throw new IllegalStateException("FEISHU_CARD_ENV must be explicitly set when card actions or the callback gateway are enabled");
        }
        if (!java.util.Set.of("reject", "testing").contains(legacyEnv)) {
            throw new IllegalStateException("FEISHU_CARD_LEGACY_ENV must be reject or testing");
        }
        if (gatewayEnabled && (!"testing".equals(environment))) {
            throw new IllegalStateException("Feishu card callback gateway can only run in testing");
        }
        if (gatewayEnabled && !eventEnabled) {
            throw new IllegalStateException("FEISHU_CARD_GATEWAY_ENABLED=true requires FEISHU_EVENT_ENABLED=true");
        }
        if (actionEnabled && !"testing".equals(environment) && eventEnabled) {
            throw new IllegalStateException("FEISHU_EVENT_ENABLED must be false on local/prod card action instances");
        }
        if (forward.connectTimeoutMs <= 0 || forward.totalTimeoutMs <= 0 || forward.connectTimeoutMs > forward.totalTimeoutMs) {
            throw new IllegalStateException("Feishu card forward timeouts must be positive and connect timeout <= total timeout");
        }
        if (forward.totalTimeoutMs > 1800) {
            throw new IllegalStateException("FEISHU_CARD_TOTAL_TIMEOUT_MS must not exceed 1800 to preserve the Feishu callback deadline");
        }
        validateRoute(routeLocal, security.localSecret, "local");
        validateRoute(routeProd, security.prodSecret, "prod");
        if ((routeLocal.enabled || routeProd.enabled) && !gatewayEnabled) {
            throw new IllegalStateException("Enabled Feishu card routes require FEISHU_CARD_GATEWAY_ENABLED=true");
        }
        if (security.localSecret != null && !security.localSecret.isBlank() && security.localSecret.equals(security.prodSecret)) {
            throw new IllegalStateException("FEISHU_CARD_HMAC_LOCAL_SECRET and FEISHU_CARD_HMAC_PROD_SECRET must differ");
        }
        if (routeProd.enabled && "testing".equals(legacyEnv)) {
            throw new IllegalStateException("FEISHU_CARD_LEGACY_ENV=testing cannot be used when prod routing is enabled");
        }
    }

    private static void validateRoute(Route route, String secret, String env) {
        if (!route.enabled) return;
        if (route.url == null || route.url.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException("Enabled Feishu card route " + env + " requires URL and HMAC secret");
        }
        try {
            java.net.URI uri = java.net.URI.create(route.url);
            if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || !"/internal/feishu/card-actions".equals(uri.getPath()) || uri.getUserInfo() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Feishu card route " + env + " URL must be an absolute HTTP(S) internal callback URL", invalid);
        }
    }

    public static class Route {
        private boolean enabled;
        private String url = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
    public static class Forward {
        private int connectTimeoutMs = 300;
        private int totalTimeoutMs = 1800;
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int value) { this.connectTimeoutMs = value; }
        public int getTotalTimeoutMs() { return totalTimeoutMs; }
        public void setTotalTimeoutMs(int value) { this.totalTimeoutMs = value; }
    }
    public static class Security {
        private String localSecret = "";
        private String prodSecret = "";
        public String getLocalSecret() { return localSecret; }
        public void setLocalSecret(String value) { this.localSecret = value; }
        public String getProdSecret() { return prodSecret; }
        public void setProdSecret(String value) { this.prodSecret = value; }
    }
}
