package com.company.skillplatform.common.application;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ErrorClassifierTest {
 @Test void nestedCauseIsPreservedAndClassified(){var root=new ConnectException("secret endpoint");var wrapped=new BusinessException("PUBLIC_ERROR","Unavailable",org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,root);assertSame(root,wrapped.getCause());var result=ErrorClassifier.classify(wrapped,"FALLBACK");assertEquals("CONNECTION_REFUSED",result.code());assertEquals("ConnectException",result.rootCauseType());}
}
