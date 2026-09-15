package com.company.skillplatform.user.interfaces;

import com.company.skillplatform.user.application.IdentityAdminService;
import com.company.skillplatform.common.interfaces.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.IamRoleEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.entity.IamPermissionEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('admin:identity')")
public class IdentityAdminController {
    private final IdentityAdminService service;
    public IdentityAdminController(IdentityAdminService service) { this.service = service; }
    @GetMapping("/users") PageResponse<UserView> users(Pageable pageable) {
        return PageResponse.from(service.users(pageable), UserView::from);
    }
    @PatchMapping("/users/{id}/status") UserView status(@PathVariable Long id, @Valid @RequestBody StatusRequest request,
                                                        Authentication auth, HttpServletRequest servletRequest) {
        return UserView.from(service.changeStatus(id, request.status(), request.versionNo(),
                (Long) auth.getPrincipal(), servletRequest.getRequestId()));
    }
    @GetMapping("/roles") List<RoleView> roles() { return service.roles().stream().map(RoleView::from).toList(); }
    @GetMapping("/permissions") List<PermissionView> permissions() { return service.permissions().stream().map(PermissionView::from).toList(); }
    @GetMapping("/users/{id}/roles") IdList userRoles(@PathVariable Long id) { return new IdList(service.userRoleIds(id)); }
    @GetMapping("/roles/{id}/permissions") IdList rolePermissions(@PathVariable Long id) { return new IdList(service.rolePermissionIds(id)); }
    @PostMapping("/roles") RoleView createRole(@Valid @RequestBody CreateRole request,
                                                Authentication auth, HttpServletRequest servletRequest) {
        return RoleView.from(service.createRole(request.roleKey(), request.roleName(), request.description(),
                (Long) auth.getPrincipal(), servletRequest.getRequestId()));
    }
    @PutMapping("/users/{id}/roles") void roles(@PathVariable Long id, @Valid @RequestBody IdsRequest request,
                                                Authentication auth, HttpServletRequest servletRequest) {
        service.replaceUserRoles(id, request.ids(), request.versionNo(), (Long) auth.getPrincipal(), servletRequest.getRequestId());
    }
    @PutMapping("/roles/{id}/permissions") void permissions(@PathVariable Long id, @Valid @RequestBody IdsRequest request,
                                                            Authentication auth, HttpServletRequest servletRequest) {
        service.replaceRolePermissions(id, request.ids(), request.versionNo(), (Long) auth.getPrincipal(), servletRequest.getRequestId());
    }
    public record StatusRequest(@NotNull UserStatus status, @NotNull Integer versionNo) {}
    public record CreateRole(@NotBlank String roleKey, @NotBlank String roleName, String description) {}
    public record IdsRequest(@NotNull List<@NotNull Long> ids, @NotNull Integer versionNo) {}
    public record UserView(Long id, String username, String displayName, String email, UserStatus status, int versionNo) {
        static UserView from(IamUserEntity e) { return new UserView(e.getId(), e.getUsername(), e.getDisplayName(), e.getEmail(), e.getStatus(), e.getVersionNo()); }
    }
    public record RoleView(Long id, String roleKey, String roleName, String description, int versionNo) {
        static RoleView from(IamRoleEntity e) { return new RoleView(e.getId(), e.getRoleKey(), e.getRoleName(), e.getDescription(), e.getVersionNo()); }
    }
    public record PermissionView(Long id, String permissionKey, String permissionName, String description) {
        static PermissionView from(IamPermissionEntity e) { return new PermissionView(e.getId(), e.getPermissionKey(), e.getPermissionName(), e.getDescription()); }
    }
    public record IdList(List<Long> ids) {}
}
