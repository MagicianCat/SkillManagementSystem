package com.company.skillplatform.adapter.application;

import com.company.skillplatform.adapter.domain.SkillAdapter;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AdapterRegistry {
    private final Map<String,SkillAdapter> adapters;
    public AdapterRegistry(List<SkillAdapter> discovered){Map<String,SkillAdapter> map=new LinkedHashMap<>();for(SkillAdapter adapter:discovered){if(map.putIfAbsent(adapter.platformKey().toUpperCase(Locale.ROOT),adapter)!=null)throw new IllegalStateException("Duplicate adapter platform: "+adapter.platformKey());}adapters=Map.copyOf(map);}
    public SkillAdapter forPlatform(String platform){SkillAdapter adapter=adapters.get(platform.toUpperCase(Locale.ROOT));if(adapter==null)throw new IllegalArgumentException("No adapter registered for platform: "+platform);return adapter;}
    public List<AdapterDescriptor> descriptors(){return adapters.values().stream().map(a->new AdapterDescriptor(a.platformKey(),a.implementationKey(),a.adapterVersion())).toList();}
    public record AdapterDescriptor(String platformKey,String implementationKey,String adapterVersion){}
}
