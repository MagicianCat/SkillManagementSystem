package com.company.skillplatform.telemetry.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import jakarta.persistence.*;
import java.time.Instant;

/** Generation 与 Skill 的多对多关联；记录某 Skill 在该 Generation 中被调用的次数。 */
@Entity
@Table(name = "ai_generation_skill")
public class AiGenerationSkillEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "generation_id", nullable = false)
    private AiGenerationEntity generation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "skill_id", nullable = false)
    private SkillEntity skill;

    @Column(name = "skill_version_id") private Long skillVersionId;
    @Column(name = "first_invoked_at") private Instant firstInvokedAt;
    @Column(name = "invocation_count", nullable = false) private int invocationCount;

    protected AiGenerationSkillEntity() {}

    public AiGenerationSkillEntity(AiGenerationEntity generation, SkillEntity skill, Long skillVersionId,
            Instant firstInvokedAt, int invocationCount) {
        this.generation = generation; this.skill = skill; this.skillVersionId = skillVersionId;
        this.firstInvokedAt = firstInvokedAt; this.invocationCount = invocationCount;
    }

    public AiGenerationEntity getGeneration() { return generation; }
    public SkillEntity getSkill() { return skill; }
    public int getInvocationCount() { return invocationCount; }
}
