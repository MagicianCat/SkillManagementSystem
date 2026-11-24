package com.company.skillplatform.agent.application;

import com.company.skillplatform.agent.domain.AgentRun;
import com.company.skillplatform.knowledge.domain.ExternalRoleMetadata;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class RetrievalScopeGuard {
    private static final Set<String> GLOBAL=Set.of("user.context.read","retrieval.plan.read","retrieval.task.refine");
    private static final Set<String> WIKI=Set.of("knowledge.search","wiki.read","wiki.search","knowledge.catalog.list");
    private static final Set<String> SKILL=Set.of("skill.search","skill.detail","skill.file.read","recommendation.submit");
    private final RetrievalPlanStore plans;
    public RetrievalScopeGuard(RetrievalPlanStore plans){this.plans=plans;}

    public void authorize(AgentRun run,String capability) {
        var plan=plans.find(run.runRef());
        if(plan.isEmpty())return;
        if(run.projectKey()!=null||run.sessionKey()!=null||!"skill-advisor".equals(run.profileKey())
                ||plan.get().tasks().isEmpty()||!GLOBAL.contains(capability)
                &&plan.get().tasks().stream().noneMatch(task->allows(task,capability))) throw denied();
    }

    public RetrievalPlanner.Task task(AgentRun run,String capability){return task(run,capability,null);}

    public RetrievalPlanner.Task task(AgentRun run,String capability,String taskId) {
        var plan=plans.find(run.runRef());
        if(plan.isEmpty())return null;
        var tasks=plan.get().tasks();
        if(tasks.size()>1&&taskId==null)throw new IllegalArgumentException("RETRIEVAL_TASK_REQUIRED");
        return tasks.stream().filter(task->(taskId==null||task.taskId().equals(taskId))&&allows(task,capability))
                .findFirst().orElseThrow(RetrievalScopeGuard::denied);
    }

    private boolean allows(RetrievalPlanner.Task task,String capability){
        return switch(task.resourceKind()){
            case "CATEGORY_PREFER" -> Set.of("knowledge.search","wiki.read","feishu.search","feishu.read").contains(capability);
            case "CATEGORY" -> Set.of("knowledge.search","wiki.read").contains(capability);
            case "DOCUMENT" -> WIKI.contains(capability);
            case "RECOMMEND" -> SKILL.contains(capability);
            case "SKILL_READ" -> Set.of("skill.detail","skill.file.read").contains(capability);
            case "ASDM-TOOL" -> Set.of("knowledge.catalog.list","wiki.read").contains(capability);
            default -> "knowledge.catalog.list".equals(capability);
        };
    }

    public void requireWiki(RetrievalPlanner.Task task,ExternalRoleMetadata metadata,String category) {
        if(task==null)return;
        if((task.roleId()>0&&(!task.resourceKind().equals(metadata.resourceType())
                ||metadata.roles().stream().noneMatch(role->role.id()==task.roleId())))
                ||(task.category()!=null&&!"CATEGORY_PREFER".equals(task.resourceKind())&&!task.category().equals(category)))throw denied();
    }

    public static AccessDeniedException denied(){return new AccessDeniedException("RETRIEVAL_SCOPE_CONFLICT");}
}
