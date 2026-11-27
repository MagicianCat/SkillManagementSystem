package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.knowledge.application.RoleNameMatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/external-resources/roles/{roleId}/aliases")
@PreAuthorize("hasAuthority('admin:identity')")
public class ExternalRoleAliasController {
    private final JdbcTemplate db;
    private final ExternalResourceAdminService audit;

    public ExternalRoleAliasController(JdbcTemplate db,ExternalResourceAdminService audit) {
        this.db=db;
        this.audit=audit;
    }

    @GetMapping
    public List<Alias> list(@PathVariable long roleId) {
        requireRole(roleId);
        return db.query("""
                SELECT id,alias,status FROM external_role_alias WHERE role_id=? ORDER BY id
                """,(rs,row)->new Alias(rs.getLong(1),rs.getString(2),rs.getString(3)),roleId);
    }

    @PostMapping
    @Transactional
    public Alias save(@PathVariable long roleId,@RequestBody AliasRequest body,Authentication auth,HttpServletRequest request) {
        requireRole(roleId);
        String name=body.alias()==null?"":body.alias().strip();
        String normalized=RoleNameMatcher.normalize(name);
        if(name.length()>255||normalized.length()>255||normalized.codePointCount(0,normalized.length())<2||
                name.codePoints().anyMatch(Character::isISOControl))
            throw new BusinessException("EXTERNAL_ROLE_ALIAS_INVALID","角色别名应为 2 至 255 个字符",HttpStatus.BAD_REQUEST);
        db.update("""
                INSERT INTO external_role_alias(role_id,alias,normalized_alias,status) VALUES (?,?,?,'ACTIVE')
                ON DUPLICATE KEY UPDATE alias=VALUES(alias),status='ACTIVE'
                """,roleId,name,normalized);
        var alias=db.queryForObject("""
                SELECT id,alias,status FROM external_role_alias WHERE role_id=? AND normalized_alias=?
                """,(rs,row)->new Alias(rs.getLong(1),rs.getString(2),rs.getString(3)),roleId,normalized);
        audit.audit("EXTERNAL_ROLE_ALIAS_SAVED","EXTERNAL_ROLE_ALIAS",alias.id(),(Long)auth.getPrincipal(),request.getRequestId());
        return alias;
    }

    @DeleteMapping("/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revoke(@PathVariable long roleId,@PathVariable long aliasId,Authentication auth,HttpServletRequest request) {
        int updated=db.update("UPDATE external_role_alias SET status='REVOKED' WHERE id=? AND role_id=? AND status='ACTIVE'",aliasId,roleId);
        if(updated>0)audit.audit("EXTERNAL_ROLE_ALIAS_REVOKED","EXTERNAL_ROLE_ALIAS",aliasId,(Long)auth.getPrincipal(),request.getRequestId());
    }

    private void requireRole(long roleId) {
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM external_role WHERE id=?)",Boolean.class,roleId)))
            throw new BusinessException("EXTERNAL_ROLE_NOT_FOUND","角色不存在",HttpStatus.NOT_FOUND);
    }

    public record Alias(long id,String alias,String status){}
    public record AliasRequest(String alias){}
}
