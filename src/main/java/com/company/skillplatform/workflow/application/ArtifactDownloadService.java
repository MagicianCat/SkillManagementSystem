package com.company.skillplatform.workflow.infrastructure;

import com.company.skillplatform.artifact.infrastructure.entity.SkillArtifactEntity;
import com.company.skillplatform.artifact.infrastructure.repository.SkillArtifactRepository;
import com.company.skillplatform.audit.application.DownloadAuditService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.logging.LogContext;
import com.company.skillplatform.skill.application.SkillService;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import com.company.skillplatform.version.domain.LifecycleStatus;
import java.io.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service public class ArtifactDownloadService {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ArtifactDownloadService.class);
 private final SkillService skills;private final SkillArtifactRepository artifacts;private final ObjectStoragePort storage;private final DownloadAuditService downloadAudit;
 public ArtifactDownloadService(SkillService s,SkillArtifactRepository a,ObjectStoragePort o,DownloadAuditService da){skills=s;artifacts=a;storage=o;downloadAudit=da;}
 @PreAuthorize("hasAuthority('skill:download')") @Transactional(readOnly=true)
 public ResponseEntity<ByteArrayResource> download(Long versionId,String platform,String os,Long userId){
  var v=skills.version(versionId);skills.assertVisibleVersion(v,userId);
  if(v.getLifecycleStatus()!=LifecycleStatus.PUBLISHED&&v.getLifecycleStatus()!=LifecycleStatus.DEPRECATED)throw error("VERSION_NOT_DOWNLOADABLE");
  SkillArtifactEntity a=artifacts.findFirstByVersionIdAndPlatformPlatformKeyAndOsTypeAndStatus(versionId,platform.toUpperCase(),os.toUpperCase(),"AVAILABLE").or(()->artifacts.findFirstByVersionIdAndPlatformPlatformKeyAndOsTypeAndStatus(versionId,platform.toUpperCase(),"ANY","AVAILABLE")).orElseThrow(()->error("ARTIFACT_NOT_FOUND"));
  try(InputStream in=storage.get(a.getObjectKey())){byte[] bytes=in.readAllBytes();downloadAudit.record(userId,v.getSkill().getId(),a.getId(),null,a.getObjectKey());return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(bytes.length).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+a.getFileName()).body(new ByteArrayResource(bytes));}
  catch(IOException e){log.error("event=artifact.download.failed requestId={} actorId={} versionId={} artifactId={} errorCode={}",LogContext.requestId(),LogContext.actorId(),versionId,a.getId(),"ARTIFACT_READ_FAILED",e);throw error("ARTIFACT_READ_FAILED");}
 }
 private BusinessException error(String c){return new BusinessException(c,c,HttpStatus.NOT_FOUND);}
}
