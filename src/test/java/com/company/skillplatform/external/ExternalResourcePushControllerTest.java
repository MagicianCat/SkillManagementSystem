package com.company.skillplatform.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;

class ExternalResourcePushControllerTest {
    private final ExternalResourceService service=mock(ExternalResourceService.class);
    private final ExternalResourcePushController controller;

    ExternalResourcePushControllerTest() throws Exception {
        controller=new ExternalResourcePushController(service,new ObjectMapper(),"{\"platform-a\":\"secret\"}",60);
    }

    @Test void rejectsWrongSourceOrKeyBeforeCallingService() {
        var files=new LinkedMultiValueMap<String,MultipartFile>();
        var request=mock(HttpServletRequest.class);
        String metadata="{\"sourceSystem\":\"platform-b\",\"role\":{\"roleId\":\"r\",\"roleName\":\"R\"},\"resources\":[{}]}";
        assertEquals(HttpStatus.UNAUTHORIZED,controller.push("secret",metadata,files,request).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,controller.push("wrong",metadata.replace("platform-b","platform-a"),files,request).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test void returnsPartialFailureWithoutRollingBackSuccessfulItems() {
        var files=new LinkedMultiValueMap<String,MultipartFile>();
        var request=mock(HttpServletRequest.class);
        when(request.getRequestId()).thenReturn("req-1");
        when(service.push(any(),eq(files),eq("req-1"))).thenReturn(new ExternalResourceService.PushResult(2,1,0,0,1,
                List.of(new ExternalResourceService.Failure("b","file2","FILE_MISSING","missing")),
                List.of(new ExternalResourceService.Success("a",9L,"CREATED")),"req-1"));
        String metadata="{\"sourceSystem\":\"platform-a\",\"role\":{\"roleId\":\"r\",\"roleName\":\"R\"},\"resources\":[{},{}]}";
        var response=controller.push("secret",metadata,files,request);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertEquals(1001,response.getBody().code());
        assertEquals(1,response.getBody().data().failed());
    }

    @Test void acceptsStateOnlyRequestAndReturnsZeroResourceCounts() {
        var files=new LinkedMultiValueMap<String,MultipartFile>();
        var request=mock(HttpServletRequest.class);
        when(request.getRequestId()).thenReturn("req-state");
        when(service.push(any(),eq(files),eq("req-state"))).thenReturn(new ExternalResourceService.PushResult(0,0,0,0,0,List.of(),List.of(),"req-state"));
        String metadata="{\"sourceSystem\":\"platform-a\",\"role\":{\"roleId\":\"r\",\"roleName\":\"R\",\"status\":\"INACTIVE\"},\"resources\":[]}";
        var response=controller.push("secret",metadata,files,request);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertEquals(0,response.getBody().code());
        assertEquals(0,response.getBody().data().total());
        ArgumentCaptor<ExternalResourceService.PushRequest> captured=ArgumentCaptor.forClass(ExternalResourceService.PushRequest.class);
        verify(service).push(captured.capture(),eq(files),eq("req-state"));
        assertEquals("INACTIVE",captured.getValue().role().status());
    }

    @Test void rejectsInvalidRoleStatusBeforeCallingService() {
        var files=new LinkedMultiValueMap<String,MultipartFile>();
        var request=mock(HttpServletRequest.class);
        String metadata="{\"sourceSystem\":\"platform-a\",\"role\":{\"roleId\":\"r\",\"roleName\":\"R\",\"status\":\"DISABLED\"},\"resources\":[]}";
        var response=controller.push("secret",metadata,files,request);
        assertEquals(HttpStatus.BAD_REQUEST,response.getStatusCode());
        assertEquals(4001,response.getBody().code());
        verifyNoInteractions(service);
    }

    @Test void rejectsEmptyRoleStatusRatherThanDefaultingIt() {
        var files=new LinkedMultiValueMap<String,MultipartFile>();
        var request=mock(HttpServletRequest.class);
        String metadata="{\"sourceSystem\":\"platform-a\",\"role\":{\"roleId\":\"r\",\"roleName\":\"R\",\"status\":\"\"},\"resources\":[]}";
        var response=controller.push("secret",metadata,files,request);
        assertEquals(HttpStatus.BAD_REQUEST,response.getStatusCode());
        assertEquals(4001,response.getBody().code());
        verifyNoInteractions(service);
    }
}
