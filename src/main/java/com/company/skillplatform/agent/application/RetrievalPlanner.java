package com.company.skillplatform.agent.application;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RetrievalPlanner {
    private static final Pattern CONTEXT=Pattern.compile("上下文|项目背景|项目资料");
    private static final Pattern SKILLS=Pattern.compile("(全部|所有|完整).{0,10}skill|skill.{0,6}(清单|列表)");
    private static final Pattern ASDM=Pattern.compile("asdm|工具包");
    private static final Pattern COMPLEX=Pattern.compile("不要|排除|比较|对比|区别|分别|总结|分析一下|做分析|推荐|然后|并且|再帮|再找|以及|如果|例如");
    private static final Pattern REFERENCE=Pattern.compile("刚才那个角色|刚才的角色|这个角色|该角色|上一个角色");
    private final QueryEntityResolver resolver;
    private final RetrievalPlanStore plans;
    private final boolean enabled;

    public RetrievalPlanner(QueryEntityResolver resolver,RetrievalPlanStore plans,@Value("${agent.retrieval.v2-enabled:false}") boolean enabled) {
        this.resolver=resolver;this.plans=plans;this.enabled=enabled;
    }

    public Optional<Plan> plan(String runKey,long user,String scope,String query) {
        if(!enabled)return Optional.empty();
        String text=QueryEntityResolver.normalize(query);
        String scopedText=text.replaceFirst("[，,；;。]\\s*(?:不要|别|请勿|无需|不用|不需要)推荐(?:任何)?\\s*(?:skill|技能)[。.!！]*$","");
        if(!scopedText.equals(text))text=scopedText;
        var switchRole=Pattern.compile("^(?:不要|排除|不用看).+?[，,；;]\\s*(?:改为|换成|改查|找|查|看)(.+)$").matcher(text);
        if(switchRole.matches())text=switchRole.group(1).strip();
        String scopedQuery=text;
        var skillReference=java.util.regex.Pattern.compile("第([一二三四五六七八九十0-9]+)个\\s*skill").matcher(text);
        if(skillReference.find()&&text.matches(".*(?:怎么用|如何用|用法|怎么安装|如何安装).*")){
            String number=skillReference.group(1);
            int ordinal=number.matches("[0-9]{1,2}")?Integer.parseInt(number):"一二三四五六七八九十".indexOf(number)+1;
            var prior=plans.priorCatalog(runKey).filter(value->value.tasks().size()==1&&"SKILL".equals(value.task().resourceKind()));
            var selected=plans.priorSkill(runKey,ordinal);
            if(prior.isEmpty()||selected.isEmpty())return Optional.of(new Plan("CLARIFY",null,List.of()));
            var ref=selected.get();
            return Optional.of(new Plan("SEMANTIC",new Task("t1",prior.get().task().roleId(),"SKILL_READ",
                    null,null,ref.id(),ref.versionId(),ref.skillKey()),prior.get().roles()));
        }
        if(text.contains("skill")&&text.matches(".*(?:怎么用|用法|如何使用|怎么安装|如何安装).*")
                &&(!text.contains("推荐")||text.matches(".*(?:不要|别|不需要|无需|不用)推荐.*"))
                &&!text.matches(".*(?:并且|以及|然后).*")){
            var named=plans.namedSkills(text);
            if(named.size()>1)return Optional.of(new Plan("CLARIFY",null,List.of()));
            if(named.size()==1&&resolver.resolve(user,scope,scopedQuery).isEmpty()){
                var ref=named.get(0);
                return Optional.of(new Plan("SEMANTIC",new Task("t1",0,"SKILL_READ",null,null,
                        ref.id(),ref.versionId(),ref.skillKey()),List.of()));
            }
        }
        if(text.matches(".*(?:它|这个技能|这个skill).*(?:怎么用|如何用|用法|怎么安装|如何安装).*")){
            var prior=plans.priorCatalog(runKey).filter(value->value.tasks().size()==1&&"SKILL".equals(value.task().resourceKind()));
            if(prior.isPresent()){
                var ref=plans.priorSkill(runKey,1);
                if(ref.isEmpty()||plans.priorSkill(runKey,2).isPresent())return Optional.of(new Plan("CLARIFY",null,List.of()));
                var selected=ref.get();
                return Optional.of(new Plan("SEMANTIC",new Task("t1",prior.get().task().roleId(),"SKILL_READ",
                        null,null,selected.id(),selected.versionId(),selected.skillKey()),prior.get().roles()));
            }
        }
        var mixed=mixedRecommendation(user,scope,text);
        if(mixed.isPresent())return mixed;
        var combined=combineCatalogs(user,scope,text);
        if(combined.isPresent())return combined;
        var mentions=resolver.resolve(user,scope,scopedQuery);
        String intentText=withoutRoleNames(text,mentions);
        boolean context=CONTEXT.matcher(intentText).find();
        boolean skills=SKILLS.matcher(intentText).find();
        boolean asdm=ASDM.matcher(intentText).find();
        if(mentions.isEmpty()){
            if(!REFERENCE.matcher(text).find()||text.contains("不限定角色")||text.contains("不用限定角色")){
                if((context||skills||asdm)&&text.matches(".*(?:项目组|外部角色|指定角色).*"))
                    return Optional.of(new Plan("CLARIFY",null,List.of()));
                return standalone(text);
            }
            var previous=plans.priorCatalog(runKey).filter(value->value.tasks().size()==1&&value.task()!=null&&"DOCUMENT".equals(value.task().resourceKind()));
            if(previous.isEmpty())return Optional.of(new Plan("CLARIFY",null,List.of()));
            var old=previous.get();
            return Optional.of(new Plan("SEMANTIC",new Task("t1",old.task().roleId(),
                    asdm?"ASDM-TOOL":"DOCUMENT",asdm?null:old.task().category()),old.roles()));
        }
        var roles=mentions.stream().distinct().collect(java.util.stream.Collectors.toMap(
                QueryEntityResolver.Mention::roleId, item->item,(a,b)->a,LinkedHashMap::new));
        var analysisRequest=Pattern.compile("^\\s*(?:请|帮我)?\\s*(?:分析一下|分析|总结一下|总结)\\s*").matcher(intentText);
        if(roles.size()==1&&context&&!skills&&!asdm&&analysisRequest.find()
                &&!COMPLEX.matcher(intentText.substring(analysisRequest.end())).find()){
            return Optional.of(new Plan("SEMANTIC",new Task("t1",roles.keySet().iterator().next(),
                    "DOCUMENT","PROJECT_CONTEXT"),List.copyOf(roles.values())));
        }
        if(roles.size()!=1 || COMPLEX.matcher(intentText).find() || (context?1:0)+(skills?1:0)+(asdm?1:0)>1)
            return Optional.of(new Plan("CLARIFY",null,List.copyOf(roles.values())));
        if(!context&&!skills&&!asdm)return Optional.of(new Plan("SEMANTIC",
                new Task("t1",roles.keySet().iterator().next(),"DOCUMENT",null),List.copyOf(roles.values())));
        String kind=context?"DOCUMENT":skills?"SKILL":"ASDM-TOOL";
        String strategy=asdm&&text.matches(".*(?:怎么用|如何安装|如何使用|安装说明|使用方法|用法).*")?"SEMANTIC":"CATALOG";
        return Optional.of(new Plan(strategy,new Task("t1",roles.keySet().iterator().next(),kind,
                context?"PROJECT_CONTEXT":null),List.copyOf(roles.values())));
    }

    private Optional<Plan> standalone(String text) {
        if(text.matches(".*(?:只查|仅查|仅限|只看).*(?:fde|内部知识).*")
                ||text.matches(".*(?<![a-z0-9])fde(?![a-z0-9]).*(?:专区|知识库|规范|流程|经验|资料).*")){
            String category=text.contains("fde")?"FDE_WIKI":"INTERNAL_KNOWLEDGE";
            return Optional.of(new Plan("SEMANTIC",new Task("t1",0,"CATEGORY",category),List.of()));
        }
        if(text.matches("^(?:请问|帮我解释一下)?(?:什么是|解释一下|是什么意思).*(?:上下文窗口|二分查找|哈希表|版本控制)[?？。]*$"))
            return Optional.of(new Plan("GENERAL",null,List.of()));
        if(text.matches(".*(?:公司|企业|内部).*(?:规章|制度|报销|审批流程|管理规定).*"))
            return Optional.of(new Plan("SEMANTIC",new Task("t1",0,"CATEGORY_PREFER","INTERNAL_KNOWLEDGE"),List.of()));
        if(explicitRecommendation(text)&&text.matches(".*(?:后端开发|后端编码|后端工程|backend).*"))
            return Optional.of(new Plan("SEMANTIC",new Task("t1",0,"RECOMMEND",null,"BACKEND_CODING"),List.of()));
        return Optional.empty();
    }

    private boolean explicitRecommendation(String text) {
        return text.matches(".*(?:推荐|有什么适合).*(?:skill|技能).*")
                &&!text.matches(".*(?:不要|别推荐|不推荐|排除|不用|不需要|禁止|请勿|无需|不想|不要求|不必|如果|例如|是否).*");
    }

    private Optional<Plan> mixedRecommendation(long user,String scope,String text) {
        if(!text.contains("推荐")||text.matches(".*(?:不要|别推荐|不推荐|排除|不用推荐|不需要推荐|禁止|请勿|无需推荐|不想要|不要求|不必推荐|无需|如果|例如|是否).*"))return Optional.empty();
        String[] clauses=text.split("[，,；;。]|并且|然后|以及");
        if(clauses.length!=2||!clauses[1].contains("推荐"))return Optional.empty();
        var mentions=resolver.resolve(user,scope,clauses[0]);
        if(!CONTEXT.matcher(clauses[0]).find()||mentions.stream().map(QueryEntityResolver.Mention::roleId).distinct().count()!=1
                ||!clauses[1].contains("skill")||!clauses[1].matches(".*(?:后端开发|后端编码|后端工程|backend).*"))return Optional.empty();
        long role=mentions.get(0).roleId();
        var explicit=resolver.resolve(user,scope,clauses[1]);
        if(explicit.stream().anyMatch(item->item.roleId()!=role))return Optional.empty();
        long skillRole=!explicit.isEmpty()||REFERENCE.matcher(clauses[1]).find()?role:0;
        var docs=new Task("t1",role,"DOCUMENT","PROJECT_CONTEXT");
        var skills=new Task("t2",skillRole,"RECOMMEND",null,"BACKEND_CODING");
        return Optional.of(new Plan("MIXED",docs,mentions,List.of(docs,skills)));
    }

    private Optional<Plan> combineCatalogs(long user,String scope,String text) {
        if(!text.matches(".*(?:[，,；;。]|并且|然后|以及|比较|对比|分别).*")
                ||text.matches(".*(?:不要|排除|推荐|总结|分析一下|做分析|区别|如果|例如).*"))return Optional.empty();
        String[] clauses=text.split("[，,；;。]|并且|然后|以及");
        List<Task> tasks=new ArrayList<>();
        List<QueryEntityResolver.Mention> roles=new ArrayList<>();
        for(String clause:clauses){
            if(clause.isBlank())continue;
            var entries=resolver.resolve(user,scope,clause);
            var distinct=entries.stream().collect(java.util.stream.Collectors.toMap(
                    QueryEntityResolver.Mention::roleId,entry->entry,(a,b)->a,LinkedHashMap::new));
            boolean comparison=clause.matches(".*(?:比较|对比|分别).*$");
            boolean ambiguous=entries.stream().anyMatch(a->entries.stream().anyMatch(b->a.roleId()!=b.roleId()
                    &&a.start()==b.start()&&a.end()==b.end()));
            if(ambiguous||distinct.isEmpty()||(!comparison&&distinct.size()!=1))return Optional.empty();
            String intent=withoutRoleNames(QueryEntityResolver.normalize(clause),entries);
            boolean wiki=CONTEXT.matcher(intent).find(), skills=SKILLS.matcher(intent).find(), asdm=ASDM.matcher(intent).find();
            if((wiki?1:0)+(skills?1:0)+(asdm?1:0)!=1)return Optional.empty();
            for(var role:distinct.values().stream().sorted(Comparator.comparingInt(QueryEntityResolver.Mention::start)).toList()){
                roles.add(role);
                tasks.add(new Task("t"+(tasks.size()+1),role.roleId(),wiki?"DOCUMENT":skills?"SKILL":"ASDM-TOOL",wiki?"PROJECT_CONTEXT":null));
            }
        }
        return tasks.size()>1&&tasks.size()<=4?Optional.of(new Plan("CATALOG",tasks.get(0),roles,tasks)):Optional.empty();
    }

    private String withoutRoleNames(String text,List<QueryEntityResolver.Mention> mentions) {
        char[] characters=text.toCharArray();
        for(var mention:mentions){
            if(mention.start()>=0&&mention.end()>=mention.start()&&mention.end()<=characters.length)
                Arrays.fill(characters,mention.start(),mention.end(),' ');
        }
        return new String(characters);
    }

    public record Plan(String strategy,Task task,List<QueryEntityResolver.Mention> roles,List<Task> tasks) {
        public Plan(String strategy,Task task,List<QueryEntityResolver.Mention> roles) {
            this(strategy,task,roles,task==null?List.of():List.of(task));
        }
        public Plan {
            if(tasks==null)tasks=task==null?List.of():List.of(task);
            tasks=List.copyOf(tasks);
        }
        public String roleName(Task target){
            return roles.stream().filter(role->role.roleId()==target.roleId()).findFirst().orElseThrow().name();
        }
    }
    public record Task(String taskId,long roleId,String resourceKind,String category,String stage,Long objectId,Long versionId,String skillKey) {
        public Task(String id,long role,String kind,String category,String stage){this(id,role,kind,category,stage,null,null,null);}
        public Task(String taskId,long roleId,String resourceKind,String category){this(taskId,roleId,resourceKind,category,null);}
    }
}
