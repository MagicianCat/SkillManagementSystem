package com.company.skillplatform.skill.infrastructure.repository;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;import jakarta.persistence.LockModeType;import java.util.*;import org.springframework.data.jpa.repository.*;
public interface SkillRepository extends JpaRepository<SkillEntity,Long>,JpaSpecificationExecutor<SkillEntity>{
 Optional<SkillEntity> findBySkillKey(String key);List<SkillEntity> findBySkillKeyIn(Collection<String> keys);boolean existsBySkillKey(String key);
 @Lock(LockModeType.PESSIMISTIC_WRITE)@Query("select s from SkillEntity s where s.skillKey=:key")Optional<SkillEntity> findBySkillKeyForUpdate(String key);
}
