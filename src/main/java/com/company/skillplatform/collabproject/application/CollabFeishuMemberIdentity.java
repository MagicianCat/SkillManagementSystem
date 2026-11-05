package com.company.skillplatform.collabproject.application;

/** Resolves an app-scoped Feishu open ID from a user created by directory sync or login. */
final class CollabFeishuMemberIdentity {
    private CollabFeishuMemberIdentity() {}

    static String openId(String provider, String openId, String externalUserId, String username) {
        if (openId != null && !openId.isBlank()) return openId;
        if (!"FEISHU".equals(provider)) return null;
        if (valid(externalUserId)) return externalUserId;
        if (username != null && username.startsWith("feishu_") && valid(username.substring(7)))
            return username.substring(7);
        return null;
    }

    private static boolean valid(String value) {
        return value != null && value.matches("ou_[A-Za-z0-9_-]{8,128}");
    }
}
