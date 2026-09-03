package com.company.skillplatform.notification.interfaces;

import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.notification.application.NotificationService;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping
    PageResponse<NotificationService.NotificationView> inbox(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                              Pageable pageable, Authentication auth) {
        return PageResponse.from(service.inbox((Long) auth.getPrincipal(), unreadOnly, pageable), item -> item);
    }

    @GetMapping("/unread-count")
    UnreadCount unreadCount(Authentication auth) { return new UnreadCount(service.unreadCount((Long) auth.getPrincipal())); }

    @PatchMapping("/{id}/read")
    NotificationService.NotificationView markRead(@PathVariable Long id, Authentication auth) {
        return service.markRead(id, (Long) auth.getPrincipal());
    }

    public record UnreadCount(long count) {}
}

