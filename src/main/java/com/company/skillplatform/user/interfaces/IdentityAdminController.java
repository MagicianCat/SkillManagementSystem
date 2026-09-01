package com.company.skillplatform.user.interfaces;

import com.company.skillplatform.user.application.IdentityAdminService;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.IamRoleEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.data.domain.Page;
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
    @GetMapping("/users") Page<UserView> users(Pageable pageable) { return service.users(pageable).map(UserView::from); }
    @PostMapping("/users") UserView createUser(@Valid @RequestBody CreateUser request) {
        return UserView.from(service.createUser(request.username(), request.password(), request.displayName(), request.email()));
    }
    @PatchMapping("/users/{id}/status") UserView status(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return UserView.from(service.changeStatus(id, request.status(), request.versionNo()));
    }
    @GetMapping("/roles") List<RoleView> roles() { return service.roles().stream().map(RoleView::from).toList(); }
    @PostMapping("/roles") RoleView createRole(@Valid @RequestBody CreateRole request) {
        return RoleView.from(service.createRole(request.roleKey(), request.roleName(), request.description()));
    }
    @PutMapping("/users/{id}/roles") void roles(@PathVariable Long id, @Valid @RequestBody IdsRequest request, Authentication auth) {
        service.replaceUserRoles(id, request.ids(), (Long) auth.getPrincipal());
    }
    @PutMapping("/roles/{id}/permissions") void permissions(@PathVariable Long id, @Valid @RequestBody IdsRequest request, Authentication auth) {
        service.replaceRolePermissions(id, request.ids(), (Long) auth.getPrincipal());
    }
    public record CreateUser(@NotBlank String username, @NotBlank String password, @NotBlank String displayName, @Email String email) {}
    public record StatusRequest(@NotNull UserStatus status, int versionNo) {}
    public record CreateRole(@NotBlank String roleKey, @NotBlank String roleName, String description) {}
    public record IdsRequest(@NotEmpty List<Long> ids) {}
    public record UserView(Long id, String username, String displayName, String email, UserStatus status, int versionNo) {
        static UserView from(IamUserEntity e) { return new UserView(e.getId(), e.getUsername(), e.getDisplayName(), e.getEmail(), e.getStatus(), e.getVersionNo()); }
    }
    public record RoleView(Long id, String roleKey, String roleName, String description, int versionNo) {
        static RoleView from(IamRoleEntity e) { return new RoleView(e.getId(), e.getRoleKey(), e.getRoleName(), e.getDescription(), e.getVersionNo()); }
    }
}
