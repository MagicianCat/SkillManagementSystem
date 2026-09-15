package com.company.skillplatform.skill.interfaces;

import com.company.skillplatform.skill.application.SkillUpdateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SkillUpdateController {
    private final SkillUpdateService service;

    public SkillUpdateController(SkillUpdateService service) { this.service = service; }

    @PostMapping("/skill-updates:check")
    public SkillUpdateService.CheckView check(@Valid @RequestBody Request request) {
        return service.check(new SkillUpdateService.CheckCommand(request.platform(), request.osType(), request.skillKeys()));
    }

    public record Request(String platform, String osType, @NotEmpty @Size(max = 200) List<String> skillKeys) {}
}
