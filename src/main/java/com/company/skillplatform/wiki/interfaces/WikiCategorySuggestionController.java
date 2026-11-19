package com.company.skillplatform.wiki.interfaces;

import com.company.skillplatform.wiki.application.WikiCategoryRecommendationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wiki")
public class WikiCategorySuggestionController {
    private final WikiCategoryRecommendationService recommendations;
    public WikiCategorySuggestionController(WikiCategoryRecommendationService recommendations) { this.recommendations = recommendations; }

    @PreAuthorize("hasAuthority('wiki:create') or hasAuthority('admin:identity')")
    @PostMapping("/knowledge-category:suggest")
    public WikiCategoryRecommendationService.Suggestion suggest(@Valid @RequestBody SuggestRequest request, Authentication authentication) {
        return recommendations.suggest(new WikiCategoryRecommendationService.SuggestCommand(request.title(), request.markdownContent(),
                request.teamId(), request.skillIds(), request.parentId()), (Long) authentication.getPrincipal());
    }

    public record SuggestRequest(@NotBlank @Size(max = 255) String title,
            @NotBlank @Size(max = 1_000_000) String markdownContent,
            Long teamId, List<@NotNull Long> skillIds, Long parentId) {}
}
