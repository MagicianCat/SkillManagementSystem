package com.company.skillplatform.workflow.infrastructure;
import com.company.skillplatform.workflow.domain.PublicationPort;import org.springframework.stereotype.Component;
@Component public class LifecycleOnlyPublicationAdapter implements PublicationPort {public void build(Long skillVersionId){/* File/artifact build is implemented in the next iteration. */}}
