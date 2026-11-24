package com.company.skillplatform.agent.application;

import com.company.skillplatform.knowledge.application.ExternalRoleDirectoryService;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class QueryEntityResolver {
    private final ExternalRoleDirectoryService directory;

    public QueryEntityResolver(ExternalRoleDirectoryService directory) { this.directory=directory; }

    public List<Mention> resolve(long user,String scope,String query) {
        String text=normalize(query);
        List<Mention> candidates=new ArrayList<>();
        for(var role:directory.mentions(user,scope,text)) {
            String term=normalize(role.term());
            String boundary=term.matches("[a-z0-9_.-]+")?"(?<![a-z0-9_.-])":"";
            String end=boundary.isEmpty()?"":"(?![a-z0-9_.-])";
            var matcher=Pattern.compile(boundary+Pattern.quote(term)+end).matcher(text);
            while(matcher.find())candidates.add(new Mention(role.id(),role.name(),role.source(),matcher.start(),matcher.end()));
        }
        return candidates.stream().distinct().filter(item->candidates.stream().noneMatch(other->
                other.start()<=item.start()&&other.end()>=item.end()&&other.end()-other.start()>item.end()-item.start())).toList();
    }

    static String normalize(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+"," ");
    }

    public record Mention(long roleId,String name,String source,int start,int end) {}
}
