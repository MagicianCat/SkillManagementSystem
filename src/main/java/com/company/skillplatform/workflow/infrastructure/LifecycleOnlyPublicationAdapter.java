package com.company.skillplatform.workflow.infrastructure;
import com.company.skillplatform.adapter.application.AdapterRegistry;import com.company.skillplatform.workflow.domain.PublicationPort;import org.springframework.stereotype.Component;
/** Compatibility bridge for the lifecycle module; artifact orchestration consumes the extensible registry. */
@Component public class LifecycleOnlyPublicationAdapter implements PublicationPort {private final AdapterRegistry registry;public LifecycleOnlyPublicationAdapter(AdapterRegistry registry){this.registry=registry;}public void build(Long skillVersionId){if(registry.descriptors().isEmpty())throw new IllegalStateException("No skill adapters registered");/* source/overlay artifact persistence is supplied by the next artifact iteration. */}}
