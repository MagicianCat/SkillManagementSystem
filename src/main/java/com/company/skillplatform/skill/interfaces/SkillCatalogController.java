package com.company.skillplatform.skill.interfaces;

import com.company.skillplatform.skill.application.SkillService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SkillCatalogController {
    private final SkillService service;
    public SkillCatalogController(SkillService service) { this.service = service; }
    @GetMapping("/categories") List<SkillService.CategoryView> categories() { return service.categories(); }
    @GetMapping("/tags") List<SkillService.TagView> tags(@RequestParam(required = false) String keyword) { return service.tags(keyword); }
}
