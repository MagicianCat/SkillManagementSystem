package com.company.skillplatform.knowledge.application;

import java.util.List;

public record KnowledgeSearchResult(String query,boolean retrieverUnavailable,boolean rerankApplied,List<DocumentResult> results,boolean hasMore,
        String roleMode,List<ExternalRoleLookupService.Role> resolvedRoles,boolean roleFallbackUsed,boolean roleIndexPending){
    public KnowledgeSearchResult(String query,boolean unavailable,boolean reranked,List<DocumentResult> results,boolean more) {
        this(query,unavailable,reranked,results,more,null,List.of(),false,false);
    }
    public record DocumentResult(Long documentId,String title,String teamScope,double vectorScore,Double rerankScore,List<Snippet> snippets,List<LinkedSkill> linkedSkills,String knowledgeCategory,
            String sourceType,String sourceSystem,List<Long> externalRoleIds,List<String> externalRoleNames,
            List<com.company.skillplatform.knowledge.domain.ExternalRoleMetadata.Role> externalRoles,
            String roleMatch,List<Long> matchedExternalRoleIds){
        public DocumentResult(Long id,String title,String scope,double score,Double rerank,List<Snippet> snippets,List<LinkedSkill> skills,String category) {
            this(id,title,scope,score,rerank,snippets,skills,category,null,null,List.of(),List.of(),List.of(),"NOT_REQUESTED",List.of());
        }
    }
    public record Snippet(String heading,String content,double vectorScore,Double rerankScore){}
    public record LinkedSkill(String skillKey,String displayName,String description,String developmentStage,String version,String detailPath){}
}
