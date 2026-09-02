package com.company.skillplatform.compatibility.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import jakarta.persistence.*;import java.util.Map;import org.hibernate.annotations.JdbcTypeCode;import org.hibernate.type.SqlTypes;
@Entity@Table(name="platform_adapter")public class PlatformAdapterEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="platform_id")private PlatformEntity platform;@Column(name="adapter_version",nullable=false,length=64)private String adapterVersion;
 @Column(name="implementation_key",nullable=false,length=128)private String implementationKey;@Column(nullable=false,length=32)private String status;
 @JdbcTypeCode(SqlTypes.JSON)@Column(name="configuration_json",nullable=false,columnDefinition="json")private Map<String,Object> configuration;
 @Version@Column(name="version_no",nullable=false)private int versionNo;protected PlatformAdapterEntity(){}public String getAdapterVersion(){return adapterVersion;}public String getImplementationKey(){return implementationKey;}
}
