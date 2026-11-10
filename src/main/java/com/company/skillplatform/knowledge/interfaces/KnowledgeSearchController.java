package com.company.skillplatform.knowledge.interfaces;

import com.company.skillplatform.knowledge.application.KnowledgeSearchService;
import com.company.skillplatform.knowledge.application.KnowledgeSearchResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only user-scoped knowledge search for local clients. */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeSearchController {
    private final KnowledgeSearchService search;

    public KnowledgeSearchController(KnowledgeSearchService search) { this.search = search; }

    @GetMapping("/search")
    public KnowledgeSearchResult search(@RequestParam String query, @RequestParam(defaultValue = "5") int limit,
                                        Authentication authentication) {
        return search.search((Long) authentication.getPrincipal(), "USER_VISIBLE", query, null, limit);
    }
}
