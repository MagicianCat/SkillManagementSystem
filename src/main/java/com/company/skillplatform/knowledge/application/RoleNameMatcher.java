package com.company.skillplatform.knowledge.application;

import java.text.Normalizer;
import java.util.*;

public final class RoleNameMatcher {
    private RoleNameMatcher() {}

    public static String normalize(String value){
        return Normalizer.normalize(value,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
    }

    public static Result search(List<ExternalRoleDirectoryService.Name> names,String input,int limit,boolean exactOnly){
        String query=normalize(input);
        if(query.length()<2||query.length()>255)throw new IllegalArgumentException("Role search requires 2 to 255 characters");
        boolean partial=names.size()>5000;
        Map<Long,Candidate> ranked=new HashMap<>();
        for(var item:names.stream().limit(5000).toList()){
            String term=normalize(item.term());
            boolean external=term.equals(normalize(item.externalId()))&&!term.equals(normalize(item.name()));
            boolean exact=external?input.strip().equals(item.externalId()):query.equals(term);
            if(!exact&&(exactOnly||external))continue;
            String left=query.replace(" ",""),right=term.replace(" ","");
            int max=Math.max(left.codePointCount(0,left.length()),right.codePointCount(0,right.length()));
            if(max>255)continue;
            boolean contains=left.contains(right)||right.contains(left);
            if(!exact&&!contains&&Math.abs(left.length()-right.length())>2)continue;
            int distance=exact?0:distance(left,right);
            if(!exact&&!contains&&distance>(max>=6?2:1))continue;
            double score=exact?1:Math.max(contains?0.56:0,1.0-(double)distance/max);
            String kind=exact?(external?"EXACT_ID":term.equals(normalize(item.name()))?"CANONICAL_EXACT":"ALIAS_EXACT"):
                    contains&&distance>1?"SUBSTRING":"TYPO";
            var candidate=new Candidate(item.id(),item.name(),item.source(),item.externalId(),kind,item.term(),distance,score,false);
            ranked.merge(item.id(),candidate,(a,b)->a.score()>=b.score()?a:b);
        }
        var ordered=ranked.values().stream().sorted(Comparator.comparingDouble(Candidate::score).reversed().thenComparingLong(Candidate::id)).toList();
        if(ordered.isEmpty())return new Result(partial?"SEARCH_PARTIAL":"NOT_FOUND_OR_NOT_VISIBLE",List.of(),partial);
        var best=ordered.get(0);
        double margin=best.score()-(ordered.size()==1?0:ordered.get(1).score());
        boolean exact=!best.matchKind().equals("TYPO")&&!best.matchKind().equals("SUBSTRING");
        boolean auto=!partial&&(exact?margin>0:query.codePointCount(0,query.length())>=4&&
                !query.matches("[a-z0-9_. -]+")&&best.editDistance()<=1&&best.score()>=0.70&&margin>=0.15);
        var top=new ArrayList<>(ordered.stream().limit(Math.max(1,Math.min(10,limit))).toList());
        if(auto)top.set(0,new Candidate(best.id(),best.name(),best.sourceSystem(),best.externalRoleId(),best.matchKind(),
                best.matchedTerm(),best.editDistance(),best.score(),true));
        return new Result(partial?"SEARCH_PARTIAL":auto?(exact?"EXACT":"SUGGESTED_HIGH"):
                ordered.size()>1?"AMBIGUOUS":"SUGGESTED",top,partial);
    }

    static int distance(String left,String right){
        int[] a=left.codePoints().toArray(),b=right.codePoints().toArray();
        int[] older=new int[b.length+1], previous=new int[b.length+1], current=new int[b.length+1];
        for(int j=0;j<=b.length;j++)previous[j]=j;
        for(int i=1;i<=a.length;i++){
            current[0]=i;
            for(int j=1;j<=b.length;j++){
                current[j]=Math.min(Math.min(previous[j]+1,current[j-1]+1),previous[j-1]+(a[i-1]==b[j-1]?0:1));
                if(i>1&&j>1&&a[i-1]==b[j-2]&&a[i-2]==b[j-1])current[j]=Math.min(current[j],older[j-2]+1);
            }
            int[] swap=older;older=previous;previous=current;current=swap;
        }
        return previous[b.length];
    }

    public record Candidate(long id,String name,String sourceSystem,String externalRoleId,String matchKind,
                            String matchedTerm,int editDistance,double score,boolean canAutoConfirm){}
    public record Result(String matchStatus,List<Candidate> candidates,boolean truncated){}
}
