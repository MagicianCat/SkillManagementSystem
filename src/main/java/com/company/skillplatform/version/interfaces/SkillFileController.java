package com.company.skillplatform.version.interfaces;

import com.company.skillplatform.version.application.SkillFileService;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/skill-versions")
public class SkillFileController {
    private final SkillFileService files;
    public SkillFileController(SkillFileService files) { this.files = files; }
    @GetMapping("/{versionId}/files") public List<SkillFileService.FileView> files(@PathVariable Long versionId) { return files.files(versionId); }
    @GetMapping("/{versionId}/files/content") public String content(@PathVariable Long versionId,@RequestParam String path){return files.content(versionId,path);}
    @PutMapping("/{versionId}/files/content") public List<SkillFileService.FileView> save(@PathVariable Long versionId,@Valid @RequestBody ContentRequest request,Authentication auth){return files.saveContent(versionId,request.path(),request.content(),request.versionNo(),(Long)auth.getPrincipal());}
    @DeleteMapping("/{versionId}/files") public List<SkillFileService.FileView> delete(@PathVariable Long versionId,@RequestParam String path,@RequestParam int versionNo,Authentication auth){return files.deleteContent(versionId,path,versionNo,(Long)auth.getPrincipal());}
    @PostMapping(value="/{versionId}/files:upload",consumes="multipart/form-data") public List<SkillFileService.FileView> upload(@PathVariable Long versionId,@RequestParam String path,@RequestParam int versionNo,@RequestPart MultipartFile file,Authentication auth){return files.uploadResource(versionId,path,file,versionNo,(Long)auth.getPrincipal());}
    @PostMapping("/{versionId}/standard-config:reset") public List<SkillFileService.FileView> reset(@PathVariable Long versionId,@RequestParam int versionNo,Authentication auth){return files.resetStandardConfig(versionId,versionNo,(Long)auth.getPrincipal());}
    @PostMapping("/{versionId}:validate") public List<String> validate(@PathVariable Long versionId){return files.validate(versionId);}
    @GetMapping("/{versionId}/diff") public List<SkillFileService.FileDiffView> diff(@PathVariable Long versionId){return files.diff(versionId);}
    public record ContentRequest(@NotBlank String path,@NotBlank String content,int versionNo){}
}
