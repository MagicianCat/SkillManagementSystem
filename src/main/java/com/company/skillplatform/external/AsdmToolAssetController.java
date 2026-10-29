package com.company.skillplatform.external;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/asdm-tool-assets")
@PreAuthorize("hasAuthority('skill:browse')")
public class AsdmToolAssetController {
    private final AsdmToolImportService service;
    private final JdbcTemplate db;
    public AsdmToolAssetController(AsdmToolImportService service, JdbcTemplate db) { this.service=service; this.db=db; }

    @GetMapping("/{id}")
    public Map<String,Object> info(@PathVariable long id) {
        var asset=service.asset(id);
        return Map.of("id",id,"filename",asset.get("filename"),"sizeBytes",asset.get("sizeBytes"));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable long id, Authentication auth, HttpServletRequest request) {
        Map<String,Object> asset;
        try { asset=service.asset(id); }
        catch (com.company.skillplatform.common.application.BusinessException ex) {
            db.update("INSERT INTO audit_log(time_created,time_updated,event_type,actor_user_id,target_type,target_id,request_id,result) VALUES(NOW(3),NOW(3),'ASDM_TOOL_DOWNLOAD_DENIED',?,?,?,?,'FAILED')",(Long)auth.getPrincipal(),"ASDM_TOOL_ASSET",id,request.getRequestId());
            throw ex;
        }
        String key=(String)asset.get("objectKey");
        String filename=(String)asset.get("filename");
        long size=((Number)asset.get("sizeBytes")).longValue();
        db.update("INSERT INTO audit_log(time_created,time_updated,event_type,actor_user_id,target_type,target_id,request_id,result) VALUES(NOW(3),NOW(3),'ASDM_TOOL_DOWNLOADED',?,?,? ,?,'SUCCESS')",(Long)auth.getPrincipal(),"ASDM_TOOL_ASSET",id,request.getRequestId());
        StreamingResponseBody body=output -> { try (var input=service.open(key)) { input.transferTo(output); } };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(size).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+URLEncoder.encode(filename,StandardCharsets.UTF_8))
                .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(body);
    }
}
