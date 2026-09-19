package com.company.skillplatform.telemetry.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.telemetry.domain.FileCategory;
import jakarta.persistence.*;

/** Generation 按文件类型聚合的代码量统计；只保存统计值，不保存文件正文。 */
@Entity
@Table(name = "ai_generation_file_metric")
public class AiGenerationFileMetricEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "generation_id", nullable = false)
    private AiGenerationEntity generation;

    @Column(name = "file_extension", length = 32) private String fileExtension;

    @Enumerated(EnumType.STRING) @Column(name = "file_category", nullable = false, length = 32)
    private FileCategory fileCategory;

    @Column(name = "lines_added", nullable = false) private long linesAdded;
    @Column(name = "lines_deleted", nullable = false) private long linesDeleted;
    @Column(name = "files_created", nullable = false) private int filesCreated;
    @Column(name = "files_modified", nullable = false) private int filesModified;

    protected AiGenerationFileMetricEntity() {}

    public AiGenerationFileMetricEntity(AiGenerationEntity generation, String fileExtension, FileCategory fileCategory,
            long linesAdded, long linesDeleted, int filesCreated, int filesModified) {
        this.generation = generation; this.fileExtension = fileExtension; this.fileCategory = fileCategory;
        this.linesAdded = linesAdded; this.linesDeleted = linesDeleted;
        this.filesCreated = filesCreated; this.filesModified = filesModified;
    }

    public AiGenerationEntity getGeneration() { return generation; }
    public FileCategory getFileCategory() { return fileCategory; }
}
