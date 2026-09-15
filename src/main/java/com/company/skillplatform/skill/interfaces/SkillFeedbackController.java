package com.company.skillplatform.skill.interfaces;
import com.company.skillplatform.skill.application.SkillFeedbackService;
import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import org.springframework.data.domain.Pageable;import org.springframework.http.HttpStatus;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/skills/{key}/feedback")
public class SkillFeedbackController{
 private final SkillFeedbackService service;public SkillFeedbackController(SkillFeedbackService s){service=s;}
 @GetMapping SkillFeedbackService.FeedbackPage list(@PathVariable String key,Pageable pageable,Authentication a){return service.list(key,(Long)a.getPrincipal(),pageable);}
 @PutMapping("/rating") SkillFeedbackService.RatingView saveRating(@PathVariable String key,@Valid@RequestBody RatingRequest r,Authentication a,HttpServletRequest h){return service.saveRating(key,(Long)a.getPrincipal(),r.rating,h.getRequestId());}
 @PostMapping("/comments") SkillFeedbackService.CommentView addComment(@PathVariable String key,@Valid@RequestBody CommentRequest r,Authentication a,HttpServletRequest h){return service.addComment(key,(Long)a.getPrincipal(),r.comment,h.getRequestId());}
 @DeleteMapping("/comments/{commentId}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteComment(@PathVariable String key,@PathVariable Long commentId,Authentication a,HttpServletRequest h){service.deleteComment(key,(Long)a.getPrincipal(),commentId,h.getRequestId());}
 public record RatingRequest(@Min(1)@Max(5)int rating){} public record CommentRequest(@NotBlank@Size(max=2000)String comment){}
}
