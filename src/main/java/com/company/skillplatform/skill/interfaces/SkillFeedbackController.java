package com.company.skillplatform.skill.interfaces;
import com.company.skillplatform.skill.application.SkillFeedbackService;
import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import org.springframework.data.domain.Pageable;import org.springframework.http.HttpStatus;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/skills/{key}/feedback")
public class SkillFeedbackController{
 private final SkillFeedbackService service;public SkillFeedbackController(SkillFeedbackService s){service=s;}
 @GetMapping SkillFeedbackService.FeedbackPage list(@PathVariable String key,Pageable pageable,Authentication a){return service.list(key,(Long)a.getPrincipal(),pageable);}
 @PutMapping SkillFeedbackService.FeedbackView save(@PathVariable String key,@Valid@RequestBody Request r,Authentication a,HttpServletRequest h){return service.save(key,(Long)a.getPrincipal(),r.rating,r.comment,h.getRequestId());}
 @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@PathVariable String key,Authentication a,HttpServletRequest h){service.delete(key,(Long)a.getPrincipal(),h.getRequestId());}
 public record Request(@Min(1)@Max(5)int rating,@Size(max=2000)String comment){}
}
