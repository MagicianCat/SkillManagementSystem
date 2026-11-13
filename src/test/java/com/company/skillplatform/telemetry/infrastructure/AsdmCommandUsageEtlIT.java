package com.company.skillplatform.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.skillplatform.telemetry.application.AsdmCommandCategoryResolver;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real MySQL coverage for the V142 schema, exact toolset lookup, OTHER, and frozen ETL stage. */
@EnabledIfSystemProperty(named = "it.asdm.command", matches = "true")
class AsdmCommandUsageEtlIT {
    @Test
    void synchronizesOtherAndPreservesTheOriginalEventStage() {
        {
            var mysql = new MysqlDataSource();
            mysql.setUrl("jdbc:mysql://127.0.0.1:3306/asdm_usage_it?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&createDatabaseIfNotExist=true");
            mysql.setUser(System.getProperty("it.asdm.mysql.user", "sms_app"));
            mysql.setPassword(System.getProperty("it.asdm.mysql.password", "SkillApp@2026"));
            var flyway = Flyway.configure().dataSource(mysql).cleanDisabled(false)
                    .locations("classpath:db/migration").load();
            flyway.clean();
            flyway.migrate();
            var jdbc = new JdbcTemplate(mysql);
            jdbc.update("""
                    INSERT INTO iam_user(time_created,time_updated,identity_provider,external_user_id,
                    username,display_name,email,status) VALUES(NOW(3),NOW(3),'TEST','asdm-it',
                    'asdm-it','ASDM IT','asdm-it@example.com','ACTIVE')
                    """);
            long user = jdbc.queryForObject("SELECT id FROM iam_user WHERE username='asdm-it'", Long.class);
            jdbc.update("""
                    INSERT INTO external_resource(source_system,external_resource_id,resource_type,name)
                    VALUES('IT_SOURCE','tool-1','ASDM-TOOL','tool')
                    """);
            long resource = jdbc.queryForObject("SELECT id FROM external_resource WHERE external_resource_id='tool-1'", Long.class);
            jdbc.update("""
                    INSERT INTO external_resource_version(resource_id,file_name,size_bytes,sha256,object_key,storage_status)
                    VALUES(?,'tool.zip',1,REPEAT('a',64),'it/tool.zip','READY')
                    """, resource);
            long version = jdbc.queryForObject("SELECT id FROM external_resource_version WHERE resource_id=?", Long.class, resource);
            jdbc.update("""
                    INSERT INTO external_asdm_tool_asset(resource_id,resource_version_id,inner_entry_path,
                    filename,object_key,sha256,size_bytes,status,created_by)
                    VALUES(?,?,'tool.zip','tool.zip','it/asset.zip',REPEAT('b',64),1,'READY',?)
                    """, resource, version, user);
            long asset = jdbc.queryForObject("SELECT id FROM external_asdm_tool_asset WHERE resource_version_id=?", Long.class, version);
            jdbc.update("""
                    INSERT INTO external_asdm_command_category(asset_id,resource_id,resource_version_id,
                    source_system,external_resource_id,tool_name,first_category_code,development_stage,
                    mapping_payload_sha256,mapping_status,imported_by)
                    VALUES(?,?,?,'IT_SOURCE','tool-1','代码评审工具集','other','OTHER',REPEAT('c',64),'ACTIVE',?)
                    """, asset, resource, version, user);
            var resolver = new AsdmCommandCategoryResolver(jdbc);
            var matched = resolver.resolve(asset, "代码评审工具集");
            assertThat(matched.matchStatus()).isEqualTo("MATCHED");
            assertThat(matched.developmentStage()).isEqualTo("OTHER");
            assertThat(resolver.resolve(asset, "代码评审工具集-X").matchStatus()).isEqualTo("TOOLSET_UNMAPPED");
            assertThat(resolver.resolve(null, "代码评审工具集").matchStatus()).isEqualTo("NO_ASSET");
            jdbc.update("""
                    INSERT INTO command_usage_event(time_created,time_updated,event_uuid,user_id,command_name,
                    toolset_name,trigger_type,evidence_type,client_session_id,generation_id,client,
                    invoked_at,received_at,asdm_asset_id,asdm_mapping_id,asdm_first_category_code,
                    asdm_development_stage,asdm_match_status)
                    VALUES(NOW(3),NOW(3),'11111111-2222-4333-8444-555555555555',?,
                    '代码评审工具集-code-review','代码评审工具集','TYPED','ACTION_FILE_READ',
                    'session-1','generation-1','CodeBuddyIDE',NOW(3),NOW(3),?,?,?,?,?)
                    """, user, matched.assetId(), matched.mappingId(), matched.firstCategoryCode(),
                    matched.developmentStage(), matched.matchStatus());
            jdbc.update("""
                    INSERT INTO command_usage_event(time_created,time_updated,event_uuid,user_id,command_name,
                    toolset_name,trigger_type,evidence_type,client_session_id,generation_id,client,
                    invoked_at,received_at,asdm_match_status)
                    VALUES(NOW(3),NOW(3),'22222222-3333-4444-8555-666666666666',?,
                    'local-command','local-toolset','MENU','STRUCTURED_COMMAND',
                    'session-2','generation-2','CodeBuddyIDE',NOW(3),NOW(3),'NO_ASSET')
                    """, user);
            var etl = new TelemetryEtlService(jdbc);
            etl.synchronize();
            assertThat(jdbc.queryForObject("SELECT stage_enum FROM dim_development_stage WHERE category_key='other'",
                    String.class)).isEqualTo("OTHER");
            assertThat(jdbc.queryForObject("""
                    SELECT asdm_development_stage FROM dwd_command_usage_detail
                    WHERE command_event_id='11111111-2222-4333-8444-555555555555'
                    """, String.class)).isEqualTo("OTHER");
            assertThat(jdbc.queryForObject("""
                    SELECT asdm_source_system FROM dwd_command_usage_detail
                    WHERE command_event_id='11111111-2222-4333-8444-555555555555'
                    """, String.class)).isEqualTo("IT_SOURCE");
            assertThat(jdbc.queryForMap("""
                    SELECT asdm_match_status,asdm_development_stage FROM dwd_command_usage_detail
                    WHERE command_event_id='22222222-3333-4444-8555-666666666666'
                    """)).containsEntry("asdm_match_status", "NO_ASSET")
                    .containsEntry("asdm_development_stage", null);
            jdbc.update("UPDATE external_asdm_command_category SET development_stage='TESTING' WHERE asset_id=?", asset);
            etl.synchronize();
            assertThat(jdbc.queryForObject("""
                    SELECT asdm_development_stage FROM dwd_command_usage_detail
                    WHERE command_event_id='11111111-2222-4333-8444-555555555555'
                    """, String.class)).isEqualTo("OTHER");
            jdbc.update("UPDATE external_asdm_command_category SET mapping_status='SUPERSEDED' WHERE asset_id=?", asset);
            assertThat(resolver.resolve(asset, "代码评审工具集").matchStatus()).isEqualTo("TOOLSET_UNMAPPED");
            etl.synchronize();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM dwd_command_usage_detail
                    WHERE command_event_id='11111111-2222-4333-8444-555555555555'
                    AND asdm_development_stage='OTHER'
                    """, Long.class)).isEqualTo(1L);
        }
    }
}
