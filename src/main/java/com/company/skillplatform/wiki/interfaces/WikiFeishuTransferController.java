package com.company.skillplatform.wiki.interfaces;

import com.company.skillplatform.wiki.feishu.WikiFeishuTransferService;
import com.company.skillplatform.wiki.feishu.FeishuDriveGateway;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wiki")
public class WikiFeishuTransferController {
    private final WikiFeishuTransferService service;
    public WikiFeishuTransferController(WikiFeishuTransferService service) { this.service = service; }
    private static long actor(Authentication authentication) { return (Long) authentication.getPrincipal(); }

    @GetMapping("/feishu/access") public FeishuDriveGateway.Access access(Authentication a) { return service.access(actor(a)); }
    @GetMapping("/feishu/drive/root") public Map<String, String> root(Authentication a) { return Map.of("folderToken", service.root(actor(a))); }
    @GetMapping("/feishu/drive/files") public FeishuDriveGateway.DrivePage files(@RequestParam(required = false) String folderToken,
            @RequestParam(required = false) String pageToken, Authentication a) { return service.files(actor(a), folderToken, pageToken); }
    @GetMapping("/feishu/drive/search") public com.fasterxml.jackson.databind.JsonNode search(@RequestParam String keyword, Authentication a) {
        return service.search(actor(a), keyword);
    }
    public record UrlRequest(String url) {}
    @PostMapping("/feishu/documents:resolve") public FeishuDriveGateway.Resolved resolve(@RequestBody UrlRequest request, Authentication a) {
        return service.resolveUrl(actor(a), request.url());
    }
    @PostMapping("/feishu/documents:preview") public WikiFeishuTransferService.Preview preview(@RequestBody WikiFeishuTransferService.Source source, Authentication a) {
        return service.preview(actor(a), source);
    }
    @PostMapping("/feishu/documents:content") public WikiFeishuTransferService.EditableContent content(@RequestBody WikiFeishuTransferService.Source source, Authentication a) {
        return service.editableContent(actor(a), source);
    }
    @PostMapping("/feishu/import-jobs") @ResponseStatus(HttpStatus.ACCEPTED) public WikiFeishuTransferService.JobAccepted importJob(@RequestBody WikiFeishuTransferService.ImportRequest request, Authentication a) {
        return service.startImport(actor(a), request);
    }
    @PostMapping("/documents/{id}/feishu-export-jobs") @ResponseStatus(HttpStatus.ACCEPTED) public WikiFeishuTransferService.JobAccepted exportJob(@PathVariable long id,
            @RequestBody WikiFeishuTransferService.ExportRequest request, Authentication a) { return service.startExport(actor(a), id, request); }
    @GetMapping("/feishu/jobs/{id}") public Map<String, Object> job(@PathVariable long id, Authentication a) { return service.job(actor(a), id); }
    @PostMapping("/feishu/jobs/{id}:retry") public WikiFeishuTransferService.JobAccepted retry(@PathVariable long id, Authentication a) {
        return service.retry(actor(a), id);
    }
}
