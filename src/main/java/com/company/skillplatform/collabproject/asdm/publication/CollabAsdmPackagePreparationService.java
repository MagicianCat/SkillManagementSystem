package com.company.skillplatform.collabproject.asdm.publication;

import com.company.skillplatform.collabproject.asdm.application.CollabAsdmConfigurationService;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmProperties;
import com.company.skillplatform.common.application.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Downloads and verifies one candidate revision outside the short submission claim transaction. */
@Service
public class CollabAsdmPackagePreparationService {
    private final JdbcTemplate jdbc;
    private final CollabAsdmCandidateFileRepository candidateFiles;
    private final CollabAsdmConfigurationService configuration;
    private final CollabAsdmGitlabClient gitlab;
    private final CollabAsdmSnapshotStore snapshots;
    private final CollabAsdmProperties properties;

    public CollabAsdmPackagePreparationService(JdbcTemplate jdbc, CollabAsdmCandidateFileRepository candidateFiles,
            CollabAsdmConfigurationService configuration, CollabAsdmGitlabClient gitlab,
            CollabAsdmSnapshotStore snapshots, CollabAsdmProperties properties) {
        this.jdbc=jdbc; this.candidateFiles=candidateFiles; this.configuration=configuration;
        this.gitlab=gitlab; this.snapshots=snapshots; this.properties=properties;
    }

    public PreparedPackage prepare(long candidateId,int revision) {
        Map<String,Object> candidate=jdbc.queryForMap("select c.id,c.requirement_id,c.stage_id,c.attempt_no,c.stage_key,c.status,c.detected_revision,c.source_commit_sha,c.manifest_hash,c.confirmed_by,p.project_key,p.asdm_branch,p.group_id,g.asdm_gitlab_project_id "
                + "from collab_asdm_candidate c join collab_project p on p.id=c.requirement_id join collab_group g on g.id=p.group_id where c.id=?",candidateId);
        if(!"CONFIRM_REQUESTED".equals(candidate.get("status"))||((Number)candidate.get("detected_revision")).intValue()!=revision)
            throw conflict("ASDM_CANDIDATE_STALE","Candidate revision changed");
        String stageKey=(String)candidate.get("stage_key");
        if(stageKey==null)throw conflict("ASDM_CANDIDATE_STALE","Legacy candidate uses the single-file publisher");
        long requirementId=((Number)candidate.get("requirement_id")).longValue();
        long groupId=((Number)candidate.get("group_id")).longValue();
        String commit=(String)candidate.get("source_commit_sha");
        var access=configuration.requireAccess(groupId);
        List<CollabAsdmCandidateFileRepository.CandidateFile> selected=candidateFiles.list(candidateId,revision).stream()
                .filter(CollabAsdmCandidateFileRepository.CandidateFile::selected)
                .sorted(Comparator.comparing(CollabAsdmCandidateFileRepository.CandidateFile::sourcePath)).toList();
        if(selected.isEmpty())throw conflict("ASDM_MARKER_NOT_READY","Candidate has no selected files");
        if(selected.size()>properties.maxCandidateFiles())throw conflict("ASDM_FILE_OVERSIZE","Too many selected files");
        String head=gitlab.getBranch(access.projectId(),String.valueOf(candidate.get("asdm_branch")),access.token(),access.repositoryBase()).headSha();
        List<CollabAsdmGitlabClient.TreeEntry> current=gitlab.listTreeRecursive(access.projectId(),head,"workspace",access.token(),properties.maxDiscoveryEntries(),access.repositoryBase());
        Map<String,String> currentBlobs=current.stream().filter(e->"blob".equals(e.type()))
                .collect(java.util.stream.Collectors.toMap(CollabAsdmGitlabClient.TreeEntry::path,
                        CollabAsdmGitlabClient.TreeEntry::id,(first,second)->first));
        List<PreparedFile> files=new ArrayList<>();
        long total=0;
        for(var file:selected){
            if("LINK_ONLY".equals(file.deliveryMode())){
                files.add(new PreparedFile(file,null,null,0));
                continue;
            }
            if(!Objects.equals(currentBlobs.get(file.sourcePath()),file.sourceBlobSha()))
                throw conflict("ASDM_CANDIDATE_STALE","A selected file changed since the candidate was prepared");
            if(file.sizeBytes()>properties.maxFileBytes())throw conflict("ASDM_FILE_OVERSIZE","Selected file exceeds the size limit");
            var raw=gitlab.getFileBytes(access.projectId(),file.sourcePath(),commit,access.token(),access.repositoryBase(),properties.maxFileBytes());
            if(!Objects.equals(raw.blobSha(),file.sourceBlobSha())||raw.size()!=file.sizeBytes())
                throw conflict("ASDM_CANDIDATE_STALE","Pinned file metadata does not match the candidate");
            total+=raw.size();
            if(total>properties.maxPackageBytes())throw conflict("ASDM_FILE_OVERSIZE","Selected package exceeds the size limit");
            var snapshot=snapshots.store(candidateId,file.sourcePath(),raw.content(),file.mediaType());
            java.util.Arrays.fill(raw.content(),(byte)0);
            files.add(new PreparedFile(file,snapshot.storageKey(),snapshot.sha256(),snapshot.sizeBytes()));
        }
        String manifestHash=hashManifest(stageKey,((Number)candidate.get("attempt_no")).intValue(),access.projectId(),commit,files);
        if(candidate.get("manifest_hash")!=null && !Objects.equals(candidate.get("manifest_hash"),manifestHash))
            throw conflict("ASDM_CANDIDATE_STALE","Candidate manifest changed");
        return new PreparedPackage(requirementId,((Number)candidate.get("stage_id")).longValue(),
                ((Number)candidate.get("attempt_no")).intValue(),candidateId,revision,stageKey,
                (String)candidate.get("project_key"),((Number)candidate.get("confirmed_by")).longValue(),
                commit,manifestHash,List.copyOf(files));
    }

    public static String hashManifest(String stageKey,int attempt,long gitlabProjectId,String commit,List<PreparedFile> files) {
        StringBuilder canonical=new StringBuilder(stageKey).append('\n').append(attempt).append('\n')
                .append(gitlabProjectId).append('\n').append(commit).append('\n');
        files.stream().sorted(Comparator.comparing(f->f.file().sourcePath())).forEach(f->canonical
                .append(f.file().sourcePath()).append('\t').append(f.file().sourceBlobSha()).append('\t')
                .append(f.file().sizeBytes()).append('\t').append(f.file().association()).append('\t')
                .append(f.file().deliveryMode()).append('\n'));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }

    private BusinessException conflict(String code,String message){return new BusinessException(code,message,HttpStatus.CONFLICT);}

    public record PreparedFile(CollabAsdmCandidateFileRepository.CandidateFile file,String storageKey,
            String sha256,long sizeBytes) {}
    public record PreparedPackage(long requirementId,long stageId,int attemptNo,long candidateId,int revision,
            String stageKey,String requirementKey,long confirmedBy,String commitSha,String manifestHash,
            List<PreparedFile> files) {}
}
