package com.company.skillplatform.telemetry.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds shared telemetry marts from the authoritative telemetry and catalog tables. */
@Service
public class TelemetryEtlService {
    private final JdbcTemplate jdbc;

    public TelemetryEtlService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void synchronize() {
        synchronizeDevelopmentStages();
        synchronizeGenerations();
        synchronizeSkillUsage();
    }

    private void synchronizeDevelopmentStages() {
        jdbc.update("""
            INSERT INTO dim_development_stage (
                category_id, category_key, category_name, parent_category_id,
                stage_key, stage_name, stage_enum, category_level, sort_order, status
            )
            SELECT c.id, c.category_key, c.category_name, c.parent_id,
                   COALESCE(p.category_key, c.category_key), COALESCE(p.category_name, c.category_name),
                   CASE COALESCE(p.category_key, c.category_key)
                     WHEN 'requirement' THEN 'REQUIREMENT'
                     WHEN 'product' THEN 'PRODUCT'
                     WHEN 'architecture' THEN 'ARCHITECTURE_DESIGN'
                     WHEN 'ui' THEN 'UI_DESIGN'
                     WHEN 'backend' THEN 'BACKEND_CODING'
                     WHEN 'frontend' THEN 'FRONTEND_CODING'
                     WHEN 'security' THEN 'SECURITY_REVIEW'
                     WHEN 'testing' THEN 'TESTING'
                     WHEN 'deployment' THEN 'DEPLOYMENT'
                     ELSE 'OTHER'
                   END,
                   CASE WHEN c.parent_id IS NULL THEN 1 ELSE 2 END, c.sort_order, c.status
            FROM skill_category c
            LEFT JOIN skill_category p ON p.id = c.parent_id
            ON DUPLICATE KEY UPDATE
              category_key=VALUES(category_key), category_name=VALUES(category_name),
              parent_category_id=VALUES(parent_category_id), stage_key=VALUES(stage_key),
              stage_name=VALUES(stage_name), stage_enum=VALUES(stage_enum),
              category_level=VALUES(category_level), sort_order=VALUES(sort_order), status=VALUES(status)
            """);
        jdbc.update("DELETE d FROM dim_development_stage d LEFT JOIN skill_category c ON c.id=d.category_id WHERE c.id IS NULL");
    }

    private void synchronizeGenerations() {
        jdbc.update("""
            INSERT INTO dwd_ai_generation_wide (
              generation_id,user_oa,project_key,project_name,primary_stage,started_at,ended_at,duration_ms,
              generation_status,model_id,model_name,model_call_count,input_tokens,output_tokens,total_tokens,
              cache_read_tokens,cache_write_tokens,cache_miss_tokens,thinking_tokens,lines_added,lines_deleted,
              files_created,files_modified,file_metrics_json,tool_call_count,tool_failure_count,token_quality,
              capture_quality,observed_log,observed_structured,token_mismatch,etl_updated_at
            )
            SELECT CONCAT('u',g.user_id,':',g.hook_generation_id),
              CASE WHEN u.email REGEXP '^[A-Za-z0-9][A-Za-z0-9._-]*@[^@[:space:]]+$'
                     AND CHAR_LENGTH(SUBSTRING_INDEX(u.email,'@',1)) <= 128
                   THEN SUBSTRING_INDEX(u.email,'@',1) ELSE NULL END,
              g.project_key,g.project_name,g.primary_stage,g.started_at,g.ended_at,g.duration_ms,g.status,
              g.model_id,g.model_name,g.model_call_count,g.input_tokens,g.output_tokens,g.total_tokens,
              g.cache_read_tokens,g.cache_write_tokens,g.cache_miss_tokens,g.thinking_tokens,
              g.lines_added,g.lines_deleted,g.files_created,g.files_modified,
              (SELECT JSON_ARRAYAGG(JSON_OBJECT('type',fm.file_category,'extension',fm.file_extension,
                    'linesAdded',fm.lines_added,'linesDeleted',fm.lines_deleted,
                    'filesCreated',fm.files_created,'filesModified',fm.files_modified))
               FROM ai_generation_file_metric fm WHERE fm.generation_id=g.id),
              g.tool_call_count,g.tool_failure_count,g.token_quality,g.capture_quality,
              g.observed_log,g.observed_structured,g.token_mismatch,NOW(3)
            FROM ai_generation_event g JOIN iam_user u ON u.id=g.user_id
            ON DUPLICATE KEY UPDATE
              user_oa=VALUES(user_oa),project_key=VALUES(project_key),project_name=VALUES(project_name),
              primary_stage=VALUES(primary_stage),started_at=VALUES(started_at),ended_at=VALUES(ended_at),
              duration_ms=VALUES(duration_ms),generation_status=VALUES(generation_status),
              model_id=VALUES(model_id),model_name=VALUES(model_name),model_call_count=VALUES(model_call_count),
              input_tokens=VALUES(input_tokens),output_tokens=VALUES(output_tokens),total_tokens=VALUES(total_tokens),
              cache_read_tokens=VALUES(cache_read_tokens),cache_write_tokens=VALUES(cache_write_tokens),
              cache_miss_tokens=VALUES(cache_miss_tokens),thinking_tokens=VALUES(thinking_tokens),
              lines_added=VALUES(lines_added),lines_deleted=VALUES(lines_deleted),files_created=VALUES(files_created),
              files_modified=VALUES(files_modified),file_metrics_json=VALUES(file_metrics_json),
              tool_call_count=VALUES(tool_call_count),tool_failure_count=VALUES(tool_failure_count),
              token_quality=VALUES(token_quality),capture_quality=VALUES(capture_quality),
              observed_log=VALUES(observed_log),observed_structured=VALUES(observed_structured),
              token_mismatch=VALUES(token_mismatch),etl_updated_at=VALUES(etl_updated_at)
            """);
        jdbc.update("""
            DELETE d FROM dwd_ai_generation_wide d
            LEFT JOIN ai_generation_event g ON CONCAT('u',g.user_id,':',g.hook_generation_id)=d.generation_id
            WHERE g.id IS NULL
            """);
    }

    private void synchronizeSkillUsage() {
        jdbc.update("""
            INSERT INTO dwd_skill_usage_detail (
              detail_key,generation_id,record_type,user_oa,skill_id,skill_key,skill_name,skill_version_id,
              category_id,invocation_count,observed_event_count,first_invoked_at,last_invoked_at,
              client_types_json,etl_updated_at
            )
            SELECT SHA2(CONCAT('GENERATION:',g.user_id,':',g.hook_generation_id,':',s.id),256),
              CONCAT('u',g.user_id,':',g.hook_generation_id),'GENERATION_SKILL',
              CASE WHEN u.email REGEXP '^[A-Za-z0-9][A-Za-z0-9._-]*@[^@[:space:]]+$'
                     AND CHAR_LENGTH(SUBSTRING_INDEX(u.email,'@',1)) <= 128
                   THEN SUBSTRING_INDEX(u.email,'@',1) ELSE NULL END,
              s.id,s.skill_key,s.display_name,ags.skill_version_id,s.category_id,ags.invocation_count,
              (SELECT COUNT(*) FROM skill_usage_event e WHERE e.ai_generation_id=g.id AND e.skill_id=s.id),
              ags.first_invoked_at,
              (SELECT MAX(e.invoked_at) FROM skill_usage_event e WHERE e.ai_generation_id=g.id AND e.skill_id=s.id),
              (SELECT CAST(CONCAT('[',GROUP_CONCAT(DISTINCT JSON_QUOTE(e.client)),']') AS JSON)
               FROM skill_usage_event e WHERE e.ai_generation_id=g.id AND e.skill_id=s.id),NOW(3)
            FROM ai_generation_skill ags
            JOIN ai_generation_event g ON g.id=ags.generation_id
            JOIN iam_user u ON u.id=g.user_id
            JOIN skill s ON s.id=ags.skill_id
            ON DUPLICATE KEY UPDATE
              generation_id=VALUES(generation_id),record_type=VALUES(record_type),user_oa=VALUES(user_oa),
              skill_key=VALUES(skill_key),skill_name=VALUES(skill_name),skill_version_id=VALUES(skill_version_id),
              category_id=VALUES(category_id),invocation_count=VALUES(invocation_count),
              observed_event_count=VALUES(observed_event_count),first_invoked_at=VALUES(first_invoked_at),
              last_invoked_at=VALUES(last_invoked_at),client_types_json=VALUES(client_types_json),etl_updated_at=VALUES(etl_updated_at)
            """);
        jdbc.update("""
            INSERT INTO dwd_skill_usage_detail (
              detail_key,generation_id,record_type,user_oa,skill_id,skill_key,skill_name,skill_version_id,
              category_id,invocation_count,observed_event_count,first_invoked_at,last_invoked_at,
              client_types_json,etl_updated_at
            )
            SELECT SHA2(CONCAT('ORPHAN_EVENT:',e.event_uuid),256),NULL,'UNLINKED_EVENT',
              CASE WHEN u.email REGEXP '^[A-Za-z0-9][A-Za-z0-9._-]*@[^@[:space:]]+$'
                     AND CHAR_LENGTH(SUBSTRING_INDEX(u.email,'@',1)) <= 128
                   THEN SUBSTRING_INDEX(u.email,'@',1) ELSE NULL END,
              s.id,s.skill_key,s.display_name,e.skill_version_id,s.category_id,1,1,e.invoked_at,e.invoked_at,
              JSON_ARRAY(e.client),NOW(3)
            FROM skill_usage_event e
            JOIN iam_user u ON u.id=e.user_id
            JOIN skill s ON s.id=e.skill_id
            LEFT JOIN ai_generation_event g ON g.id=e.ai_generation_id
            WHERE g.id IS NULL
            ON DUPLICATE KEY UPDATE
              user_oa=VALUES(user_oa),skill_key=VALUES(skill_key),skill_name=VALUES(skill_name),
              skill_version_id=VALUES(skill_version_id),category_id=VALUES(category_id),
              first_invoked_at=VALUES(first_invoked_at),last_invoked_at=VALUES(last_invoked_at),
              client_types_json=VALUES(client_types_json),etl_updated_at=VALUES(etl_updated_at)
            """);
        jdbc.update("""
            DELETE d FROM dwd_skill_usage_detail d
            LEFT JOIN ai_generation_event g ON CONCAT('u',g.user_id,':',g.hook_generation_id)=d.generation_id
            WHERE d.generation_id IS NOT NULL AND g.id IS NULL
            """);
        jdbc.update("""
            DELETE d FROM dwd_skill_usage_detail d
            LEFT JOIN ai_generation_skill ags ON d.record_type='GENERATION_SKILL'
              AND ags.generation_id=(SELECT g.id FROM ai_generation_event g WHERE CONCAT('u',g.user_id,':',g.hook_generation_id)=d.generation_id LIMIT 1)
              AND ags.skill_id=d.skill_id
            WHERE d.record_type='GENERATION_SKILL' AND ags.id IS NULL
            """);
        jdbc.update("""
            DELETE d FROM dwd_skill_usage_detail d
            LEFT JOIN skill_usage_event e ON d.record_type='UNLINKED_EVENT'
              AND d.detail_key=SHA2(CONCAT('ORPHAN_EVENT:',e.event_uuid),256)
            LEFT JOIN ai_generation_event g ON g.id=e.ai_generation_id
            WHERE d.record_type='UNLINKED_EVENT' AND (e.id IS NULL OR g.id IS NOT NULL)
            """);
    }
}
