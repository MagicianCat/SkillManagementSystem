alter table document_agent_job
    add column turn_mode varchar(32) not null default 'CREATE_ARTIFACT' after instruction,
    add column target_document_id bigint null after turn_mode,
    add column target_title varchar(255) null after target_document_id,
    add constraint fk_document_agent_job_target_document foreign key (target_document_id) references project_document(id);

create table project_stage_artifact (
    id bigint not null auto_increment,
    time_created datetime(3) not null,
    time_updated datetime(3) not null,
    stage_id bigint not null,
    document_id bigint not null,
    source_job_id bigint null,
    added_by bigint not null,
    primary key (id),
    constraint uk_project_stage_artifact unique (stage_id, document_id),
    constraint fk_stage_artifact_stage foreign key (stage_id) references project_stage(id),
    constraint fk_stage_artifact_document foreign key (document_id) references project_document(id),
    constraint fk_stage_artifact_job foreign key (source_job_id) references document_agent_job(id),
    constraint fk_stage_artifact_user foreign key (added_by) references iam_user(id)
);
create index idx_stage_artifact_stage_time on project_stage_artifact(stage_id, time_created);

insert ignore into project_stage_artifact(time_created,time_updated,stage_id,document_id,source_job_id,added_by)
select j.time_created,j.time_updated,s.stage_id,j.artifact_id,j.id,coalesce(j.requested_by,s.owner_user_id)
from document_agent_job j join document_agent_session s on s.id=j.session_id
where s.stage_id is not null and j.artifact_id is not null;
