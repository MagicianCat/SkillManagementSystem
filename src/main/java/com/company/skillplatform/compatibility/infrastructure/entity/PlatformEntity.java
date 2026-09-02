package com.company.skillplatform.compatibility.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import jakarta.persistence.*;
@Entity @Table(name="platform")public class PlatformEntity extends BaseJpaEntity{
 @Column(name="platform_key",nullable=false,length=64)private String platformKey;@Column(name="platform_name",nullable=false,length=128)private String platformName;
 @Column(nullable=false,length=32)private String status;@Column(name="user_install_path_template",length=512)private String userInstallPathTemplate;
 @Column(name="project_install_path_template",length=512)private String projectInstallPathTemplate;@Version@Column(name="version_no",nullable=false)private int versionNo;protected PlatformEntity(){}
 public String getPlatformKey(){return platformKey;}public String getPlatformName(){return platformName;}public String getStatus(){return status;}
}
