package com.company.skillplatform.external;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('admin:identity')")
public class ExternalResourceAdminController {
    private final ExternalResourceAdminService service;
    private final ExternalBulkImportService bulk;
    public ExternalResourceAdminController(ExternalResourceAdminService service, ExternalBulkImportService bulk) { this.service=service; this.bulk=bulk; }

    @GetMapping("/external-resources/roles") public List<Map<String,Object>> roles(){return service.roles();}
    @GetMapping("/external-resources") public List<Map<String,Object>> resources(@RequestParam(required=false)Long roleId,@RequestParam(required=false)String type,@RequestParam(required=false)String keyword,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size){return service.resources(roleId,type,keyword,page,size);}
    @GetMapping("/external-resources/{id}/versions") public List<Map<String,Object>> versions(@PathVariable Long id){return service.versions(id);}
    @GetMapping("/external-resource-versions/{id}/imports") public List<Map<String,Object>> imports(@PathVariable Long id){return service.imports(id);}
    @GetMapping("/external-resource-versions/{id}/download") public ResponseEntity<byte[]> download(@PathVariable Long id,Authentication auth,HttpServletRequest request){var raw=service.download(id);service.audit("EXTERNAL_RESOURCE_DOWNLOADED","EXTERNAL_RESOURCE_VERSION",id,(Long)auth.getPrincipal(),request.getRequestId());return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+URLEncoder.encode(raw.fileName(),StandardCharsets.UTF_8)).body(raw.bytes());}
    @PostMapping("/external-resource-versions/{id}/skill:prepare") public ExternalResourceAdminService.SkillPreparation prepare(@PathVariable Long id,@RequestParam(required=false)String skillKey,Authentication auth,HttpServletRequest request){var result=service.prepareSkill(id,skillKey);service.audit("EXTERNAL_SKILL_PREPARED","EXTERNAL_RESOURCE_VERSION",id,(Long)auth.getPrincipal(),request.getRequestId());return result;}
    @PostMapping("/external-resource-versions/{id}/skill:import-draft") public ExternalResourceAdminService.SkillImportResult importSkill(@PathVariable Long id,@RequestBody ExternalResourceAdminService.SkillImportRequest body,Authentication auth,HttpServletRequest request){var result=service.importSkill(id,body,(Long)auth.getPrincipal(),request.getRequestId());service.audit("EXTERNAL_SKILL_IMPORTED","EXTERNAL_RESOURCE_VERSION",id,(Long)auth.getPrincipal(),request.getRequestId());return result;}
    @GetMapping("/external-resource-versions/{id}/documents") public List<ExternalResourceAdminService.DocumentEntry> documents(@PathVariable Long id,Authentication auth,HttpServletRequest request){var result=service.documents(id);service.audit("EXTERNAL_DOCUMENTS_LISTED","EXTERNAL_RESOURCE_VERSION",id,(Long)auth.getPrincipal(),request.getRequestId());return result;}
    @GetMapping("/external-resource-versions/{id}/documents/content") public String content(@PathVariable Long id,@RequestParam String path,Authentication auth,HttpServletRequest request){var result=service.documentContent(id,path);service.audit("EXTERNAL_DOCUMENT_PREVIEWED","EXTERNAL_RESOURCE_VERSION",id,(Long)auth.getPrincipal(),request.getRequestId());return result;}
    @GetMapping("/external-resources/roles/{id}/import-readiness") public ExternalResourceAdminService.Readiness readiness(@PathVariable Long id){return service.readiness(id);}
    @PostMapping("/external-resources/roles/{id}/skills:import-all") public ResponseEntity<Map<String,Long>> importAllSkills(@PathVariable Long id, @RequestBody BulkSkillRequest body, Authentication auth) {
        return ResponseEntity.accepted().body(Map.of("jobId",bulk.enqueueSkills(id,body.resourceIds(),(Long)auth.getPrincipal())));
    }
    @PostMapping("/external-resources/roles/{id}/wikis:import-all") public ResponseEntity<Map<String,Long>> importAllWikis(@PathVariable Long id, @RequestBody BulkWikiRequest body, Authentication auth) {
        return ResponseEntity.accepted().body(Map.of("jobId",bulk.enqueueWikis(id,body.teamId(),body.parentWikiId(),body.linkRoleSkills(),body.selections(),(Long)auth.getPrincipal())));
    }
    @GetMapping("/external-import-jobs/{id}") public Map<String,Object> importJob(@PathVariable Long id) { return bulk.job(id); }
    public record BulkSkillRequest(List<Long> resourceIds) {}
    public record BulkWikiRequest(Long teamId,Long parentWikiId,boolean linkRoleSkills,List<ExternalBulkImportService.WikiSelection> selections) {}
}
