package com.company.skillplatform.wiki.interfaces;

import com.company.skillplatform.wiki.application.WikiCategoryBatchService;
import com.company.skillplatform.wiki.application.WikiCategoryBatchService.ReviewCommand;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/wiki-category")
@PreAuthorize("hasAuthority('admin:identity')")
public class WikiCategoryBatchController {
    private final WikiCategoryBatchService batches;
    public WikiCategoryBatchController(WikiCategoryBatchService batches){this.batches=batches;}
    private long actor(Authentication auth){return (Long)auth.getPrincipal();}
    @GetMapping("/preview") public Map<String,Object> preview(Authentication auth){return batches.preview(actor(auth));}
    @PostMapping("/batches") public ResponseEntity<Map<String,Object>> create(@RequestBody CreateRequest request,Authentication auth){return ResponseEntity.status(HttpStatus.ACCEPTED).body(batches.create(actor(auth),request.includeConfirmedOther()));}
    @GetMapping("/batches") public List<Map<String,Object>> list(Authentication auth){return batches.batches(actor(auth));}
    @GetMapping("/batches/{id}") public Map<String,Object> one(@PathVariable long id,Authentication auth){return batches.batch(actor(auth),id);}
    @GetMapping("/batches/{id}/items") public Map<String,Object> items(@PathVariable long id,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,Authentication auth){return batches.items(actor(auth),id,status,page,size);}
    @GetMapping("/batches/{id}/metrics") public Map<String,Object> metrics(@PathVariable long id,Authentication auth){return batches.metrics(actor(auth),id);}
    @PostMapping("/batches/{id}:retry-failed") public Map<String,Object> retry(@PathVariable long id,Authentication auth){return batches.retry(actor(auth),id);}
    @PostMapping("/batches/{id}:cancel") public void cancel(@PathVariable long id,Authentication auth){batches.cancel(actor(auth),id);}
    @PostMapping("/items/{itemId}:review") public Map<String,Object> review(@PathVariable long itemId,@RequestBody ReviewRequest request,Authentication auth){return batches.review(actor(auth),itemId,request.expectedWikiVersionNo(),request.inputFingerprint(),request.reviewAction(),request.finalCategory());}
    @PostMapping("/batches/{id}/reviews:batch") public List<Map<String,Object>> reviewMany(@PathVariable long id,@RequestBody List<ReviewCommand> requests,Authentication auth){batches.batch(actor(auth),id);return batches.reviewMany(actor(auth),id,requests);}
    public record CreateRequest(boolean includeConfirmedOther){}
    public record ReviewRequest(int expectedWikiVersionNo,String inputFingerprint,String reviewAction,String finalCategory){}
}
