package com.company.skillplatform.telemetry.domain;

/** Token 数据质量。只有 EXACT / PARTIAL 参与 Token 相关统计。 */
public enum TokenQuality {
    EXACT,
    PARTIAL,
    ESTIMATED,
    UNAVAILABLE;

    /** 是否参与 Token 统计聚合。 */
    public boolean isCountable() {
        return this == EXACT || this == PARTIAL;
    }
}
