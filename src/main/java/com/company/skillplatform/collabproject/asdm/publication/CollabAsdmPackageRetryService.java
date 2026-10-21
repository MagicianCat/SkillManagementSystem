package com.company.skillplatform.collabproject.asdm.publication;

import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService;
import com.company.skillplatform.common.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class CollabAsdmPackageRetryService {
    private final CollabAsdmPackageRepository packages;
    private final CollabProjectAuthorizationService authorization;
    public CollabAsdmPackageRetryService(CollabAsdmPackageRepository packages,CollabProjectAuthorizationService authorization){this.packages=packages;this.authorization=authorization;}

    public RetryResult retry(String requirementKey,long packageId,long actorId) {
        var requirement=authorization.requireRequirementManager(requirementKey,actorId);
        var manifest=packages.findPackage(packageId).orElseThrow(()->new BusinessException("ASDM_PACKAGE_NOT_FOUND","ASDM package was not found",HttpStatus.NOT_FOUND));
        if(manifest.requirementId()!=requirement.projectId()) throw new BusinessException("ASDM_PACKAGE_NOT_FOUND","ASDM package was not found",HttpStatus.NOT_FOUND);
        if(!"PARTIAL_FAILED".equals(manifest.packageStatus())) throw new BusinessException("ASDM_PACKAGE_NOT_RETRYABLE","Only packages with failed files can be retried",HttpStatus.CONFLICT);
        packages.resetFailedFiles(packageId);
        return new RetryResult(packageId,"PUBLISHING_FILES");
    }

    public PackageStatus status(String requirementKey, String stageKey, long packageId, long actorId) {
        var requirement=authorization.requireMemberOrThrow(requirementKey,actorId);
        var manifest=packages.findPackage(packageId).filter(p -> p.requirementId()==requirement.projectId())
                .filter(p -> packages.belongsToStage(packageId, requirement.projectId(), stageKey))
                .orElseThrow(()->new BusinessException("ASDM_PACKAGE_NOT_FOUND","ASDM package was not found",HttpStatus.NOT_FOUND));
        var packageFiles=packages.findFiles(packageId);
        List<FileStatus> files=packageFiles.stream()
                .map(f->new FileStatus(f.sourcePath(),f.publishStatus(),f.feishuUrl(),f.lastErrorCode())).toList();
        long docxTotal=packageFiles.stream().filter(f->"DOCX".equals(f.deliveryMode())).count();
        long docxPublished=packageFiles.stream().filter(f->"DOCX".equals(f.deliveryMode())&&"PUBLISHED".equals(f.publishStatus())).count();
        long attachmentTotal=packageFiles.stream().filter(f->"DRIVE_FILE".equals(f.deliveryMode())).count();
        long attachmentPublished=packageFiles.stream().filter(f->"DRIVE_FILE".equals(f.deliveryMode())&&"PUBLISHED".equals(f.publishStatus())).count();
        var index=packages.findIndexPublication(manifest.sourceIndexArtifactVersionId()).orElse(null);
        return new PackageStatus(packageId,manifest.packageStatus(),files,docxPublished,docxTotal,attachmentPublished,attachmentTotal,index==null?null:index.url());
    }

    public record RetryResult(long packageId,String status){}
    public record FileStatus(String path,String publishStatus,String feishuUrl,String lastErrorCode){}
    public record PackageStatus(long packageId,String status,List<FileStatus> files,long docxPublished,long docxTotal,
            long attachmentPublished,long attachmentTotal,String indexUrl){}
}
