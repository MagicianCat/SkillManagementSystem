ALTER TABLE external_import_job
  ADD COLUMN knowledge_category VARCHAR(32) NULL
    COMMENT 'DOCUMENT 批量 Wiki 导入任务统一知识分类；SKILL 任务为 NULL';
