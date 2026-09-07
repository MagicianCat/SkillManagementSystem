package com.company.skillplatform.common.interfaces;
import static org.assertj.core.api.Assertions.*;
import com.company.skillplatform.common.application.BusinessException;
import org.junit.jupiter.api.Test;import org.springframework.http.HttpStatus;import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;import org.springframework.security.authorization.AuthorizationDecision;import org.springframework.security.authorization.AuthorizationDeniedException;

/** 验证异常处理器到标准错误响应的映射。 */
class ApiExceptionHandlerAccessDeniedTest{
    private final ApiExceptionHandler handler=new ApiExceptionHandler();

    @Test void authorizationDeniedMapsTo403(){
        MockHttpServletRequest req=new MockHttpServletRequest("PATCH","/api/v1/skills/x");
        var resp=handler.handleAccessDenied(new AuthorizationDeniedException("Access Denied",new AuthorizationDecision(false)),req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().code()).isEqualTo("ACCESS_DENIED");
    }

    @Test void accessDeniedMapsTo403(){
        MockHttpServletRequest req=new MockHttpServletRequest("PATCH","/api/v1/skills/x");
        var resp=handler.handleAccessDenied(new AccessDeniedException("Access Denied"),req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().code()).isEqualTo("ACCESS_DENIED");
    }

    @Test void businessExceptionMapsToItsStatusAndCode(){
        MockHttpServletRequest req=new MockHttpServletRequest("GET","/api/v1/skills/x");
        var resp=handler.handleBusiness(new BusinessException("SKILL_NOT_FOUND","not here",HttpStatus.NOT_FOUND),req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().code()).isEqualTo("SKILL_NOT_FOUND");
        assertThat(resp.getBody().message()).isEqualTo("not here");
    }

    @Test void optimisticLockMapsTo409(){
        MockHttpServletRequest req=new MockHttpServletRequest("PATCH","/api/v1/skills/x");
        var resp=handler.handleOptimisticLock(new ObjectOptimisticLockingFailureException("Skill",1L),req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().code()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
    }

    @Test void validationFailureMapsTo400WithFieldDetails(){
        MockHttpServletRequest req=new MockHttpServletRequest("POST","/api/v1/skills");
        org.springframework.validation.MapBindingResult binding=new org.springframework.validation.MapBindingResult(new java.util.LinkedHashMap<>(),"request");
        binding.addError(new org.springframework.validation.FieldError("request","skillKey","must not be blank"));
        var resp=handler.handleValidation(new org.springframework.web.bind.MethodArgumentNotValidException(null,binding),req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(resp.getBody().details()).containsEntry("skillKey","must not be blank");
    }

    @Test void unexpectedMapsTo500(){
        MockHttpServletRequest req=new MockHttpServletRequest("GET","/api/v1/skills/x");
        var resp=handler.handleUnexpected(new IllegalStateException("boom"),req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
