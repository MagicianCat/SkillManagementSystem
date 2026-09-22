package com.company.skillplatform.knowledge.application;

import java.util.List;

public record KnowledgeSearchResult(String query,boolean retrieverUnavailable,boolean rerankApplied,List<DocumentResult> results,boolean hasMore){
    public record DocumentResult(Long documentId,String title,String documentType,String teamScope,double vectorScore,Double rerankScore,List<Snippet> snippets,List<LinkedSkill> linkedSkills){}
    public record Snippet(String heading,String content,double vectorScore,Double rerankScore){}
    public record LinkedSkill(String skillKey,String displayName,String description,String developmentStage,String version,String detailPath){}
}
