package com.company.skillplatform.version.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import jakarta.persistence.*;
@Entity @Table(name="skill_file_index") public class SkillFileIndexEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="skill_version_id")private SkillVersionEntity version;
 @Column(name="source_revision",nullable=false)private int sourceRevision;@Column(name="relative_path",nullable=false,length=1024)private String relativePath;
 @Column(name="path_hash",nullable=false,columnDefinition="char(64)")private String pathHash;@Column(name="file_type",nullable=false,length=32)private String fileType;
 @Column(name="media_type",length=128)private String mediaType;@Column(name="size_bytes",nullable=false)private long sizeBytes;@Column(columnDefinition="char(64)")private String sha256;
 @Column(nullable=false)private boolean editable;@Column(name="file_source",nullable=false,length=32)private String fileSource;protected SkillFileIndexEntity(){}
 public SkillFileIndexEntity(SkillVersionEntity v,int revision,String path,String type,String media,long size,boolean canEdit,String source){version=v;sourceRevision=revision;relativePath=path;pathHash=hash(path);fileType=type;mediaType=media;sizeBytes=size;sha256="";editable=canEdit;fileSource=source;}
 private String hash(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public String getRelativePath(){return relativePath;}public int getSourceRevision(){return sourceRevision;}public String getFileSource(){return fileSource;}public boolean isEditable(){return editable;}public long getSizeBytes(){return sizeBytes;}
}
