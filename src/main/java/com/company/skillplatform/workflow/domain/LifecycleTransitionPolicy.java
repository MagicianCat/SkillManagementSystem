package com.company.skillplatform.workflow.domain;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.version.domain.LifecycleStatus;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LifecycleTransitionPolicy {
    public void require(LifecycleStatus from, LifecycleStatus to) {
        boolean allowed = switch (from) {
            case DRAFT -> to == LifecycleStatus.REVIEWING || to == LifecycleStatus.CANCELLED;
            case REVIEWING -> to == LifecycleStatus.DRAFT || to == LifecycleStatus.APPROVED;
            case APPROVED -> to == LifecycleStatus.DRAFT || to == LifecycleStatus.PUBLISHED;
            case PUBLISHED -> to == LifecycleStatus.DEPRECATED || to == LifecycleStatus.OFFLINE;
            case DEPRECATED -> to == LifecycleStatus.OFFLINE;
            case OFFLINE, CANCELLED -> false;
        };
        if (!allowed) throw new BusinessException("INVALID_LIFECYCLE_TRANSITION", "Lifecycle transition is not allowed", HttpStatus.CONFLICT);
    }
}
