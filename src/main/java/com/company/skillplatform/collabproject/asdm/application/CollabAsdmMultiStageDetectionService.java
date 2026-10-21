package com.company.skillplatform.collabproject.asdm.application;

import com.company.skillplatform.collabproject.application.CollabFeishuOutbox;
import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService;
import com.company.skillplatform.collabproject.asdm.application.CollabAsdmConfigurationService;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient.FileContent;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient.TreeEntry;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabException;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmProperties;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmCandidateFileRepository;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmCandidateFileRepository.CandidateFile;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmPackagePreparationService;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scans immutable branch snapshots for stage-specific ASDM artifacts. */
@Service
public class CollabAsdmMultiStageDetectionService {
    private static final Set<String> ACTIVE = Set.of("IN_PROGRESS", "REWORK");
    private static final Set<String> TERMINAL = Set.of("CLAIMED", "CLOSED_BY_SKILL", "INVALIDATED", "SUPERSEDED");
    private static final Set<String> READABLE_SUFFIXES = Set.of(".md", ".xlsx", ".sql", ".txt", ".json", ".html", ".png", ".jpg", ".jpeg", ".svg", ".csv", ".py");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s)\\]}>\\\"']+", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final CollabAsdmProperties properties;
    private final CollabAsdmConfigurationService configuration;
    private final CollabAsdmGitlabClient gitlab;
    private final CollabAsdmStageRules rules;
    private final CollabAsdmCandidateFileRepository candidateFiles;
    private final CollabProjectAuthorizationService authorization;
    private final CollabFeishuOutbox outbox;
    private final ObjectMapper mapper;

    public CollabAsdmMultiStageDetectionService(JdbcTemplate jdbc, CollabAsdmProperties properties,
            CollabAsdmConfigurationService configuration, CollabAsdmGitlabClient gitlab,
            CollabAsdmStageRules rules, CollabAsdmCandidateFileRepository candidateFiles,
            CollabProjectAuthorizationService authorization, CollabFeishuOutbox outbox, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.configuration = configuration;
        this.gitlab = gitlab;
        this.rules = rules;
        this.candidateFiles = candidateFiles;
        this.authorization = authorization;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    public void scanRequirement(long requirementId) {
        if (!properties.multiStageEnabled()) return;
        Requirement req = requirement(requirementId);
        if (req == null || req.branch() == null || req.path() == null || !"ACTIVE".equals(req.projectStatus())
                || !"PUBLISHED".equals(req.groupStatus())) return;
        CollabAsdmConfigurationService.RepositoryAccess access = configuration.requireAccess(req.groupId());
        try {
            String head = gitlab.getBranch(access.projectId(), req.branch(), access.token(), access.repositoryBase()).headSha();
            List<TreeEntry> tree = gitlab.listTreeRecursive(access.projectId(), head, "workspace", access.token(),
                    properties.maxDiscoveryEntries(), access.repositoryBase());
            Map<String, String> baseline = req.baselineHead() == null ? Map.of() : treeMap(gitlab.listTreeRecursive(
                    access.projectId(), req.baselineHead(), "workspace", access.token(), properties.maxDiscoveryEntries(), access.repositoryBase()));
            List<Stage> active = jdbc.query("select id,stage_key,status,attempt_no,actors_frozen_at from collab_stage where project_id=? and status in ('IN_PROGRESS','REWORK') and actors_frozen_at is not null order by stage_order",
                    (rs,n)->stage(rs), requirementId);
            Map<String,Long> matchCounts=new LinkedHashMap<>();
            for(Stage stage:active)if(rules.enabled(stage.key()))matchCounts.put(stage.key(),tree.stream().filter(e->"blob".equals(e.type())&&readable(e.path())&&rules.mayMatchStagePath(stage.key(),req.path(),e.path())).count());
            Set<String> overLimit=matchCounts.entrySet().stream().filter(e->e.getValue()>properties.maxCandidateFiles()).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
            List<TreeEntry> relevant = tree.stream().filter(e -> "blob".equals(e.type()) && readable(e.path())
                    && active.stream().anyMatch(s->!overLimit.contains(s.key())&&rules.mayMatchStagePath(s.key(),req.path(),e.path()))).toList();
            List<CollabAsdmStageRules.InputFile> files = new ArrayList<>(relevant.size());
            Map<String, FileContent> rawByPath = new LinkedHashMap<>();
            for (TreeEntry entry : relevant) {
                try {
                    FileContent raw = gitlab.getFileBytes(access.projectId(), entry.path(), head, access.token(), access.repositoryBase(), properties.maxFileBytes());
                    rawByPath.put(entry.path(), raw);
                    String text = entry.path().toLowerCase(Locale.ROOT).endsWith(".md") ? decodeUtf8(raw.content()) : null;
                    files.add(new CollabAsdmStageRules.InputFile(entry.path(), raw.blobSha(), raw.size(), text,
                            !Objects.equals(baseline.get(entry.path()), raw.blobSha())));
                } catch (CollabAsdmGitlabException ex) {
                    if (ex.httpStatus() == 413) {
                        files.add(new CollabAsdmStageRules.InputFile(entry.path(), entry.id(), -1, null,
                                !Objects.equals(baseline.get(entry.path()), entry.id())));
                    } else throw ex;
                }
            }
            for (Stage stage : active) {
                if (!rules.enabled(stage.key()) || !hasActiveOwner(req.groupId(), stage.id())) continue;
                Integer legacy=jdbc.queryForObject("select count(*) from collab_asdm_candidate where stage_id=? and attempt_no=? and stage_key is null",Integer.class,stage.id(),stage.attempt());
                if(legacy!=null&&legacy>0)continue;
                if (submissionClaimed(stage.id(), stage.attempt())) { closeBySubmission(stage); continue; }
                if(overLimit.contains(stage.key())){markStageScanError(stage,"ASDM_CANDIDATE_LIMIT_EXCEEDED");continue;}
                Map<String,String> overrides=associationOverrides(requirementId, stage.key());
                CollabAsdmStageRules.Evaluation trigger = rules.evaluate(stage.key(), req.path(), req.name(), files, properties.matchLikelyAutoinclude());
                CollabAsdmStageRules.Evaluation evaluated = rules.evaluate(stage.key(), req.path(), req.name(), files,
                        properties.matchLikelyAutoinclude(), overrides);
                persistCandidate(req, access, stage, head, trigger, evaluated, rawByPath);
            }
            jdbc.update("update collab_project set asdm_last_scanned_head_sha=?,asdm_last_scan_at=now(3),time_updated=now(3) where id=?", head, requirementId);
        } catch (CollabAsdmGitlabException ex) {
            if (ex.httpStatus() == 401 || ex.httpStatus() == 403) pauseCredential(req.groupId(), ex.errorCode());
            markScanError(requirementId, ex.errorCode());
        }
    }

    private void persistCandidate(Requirement req, CollabAsdmConfigurationService.RepositoryAccess access, Stage stage,
            String head, CollabAsdmStageRules.Evaluation trigger, CollabAsdmStageRules.Evaluation evaluation, Map<String, FileContent> rawByPath) {
        List<CollabAsdmStageRules.ClassifiedFile> classified = evaluation.files().stream()
                .map(f -> rawByPath.containsKey(f.path()) ? f : new CollabAsdmStageRules.ClassifiedFile(f.path(), f.blobSha(), f.size(),
                        f.content(), "UNCERTAIN", f.score(), "ASDM_FILE_UNREADABLE_OR_OVERSIZE", f.deliveryMode(), false))
                .sorted(Comparator.comparing(CollabAsdmStageRules.ClassifiedFile::path)).toList();
        boolean markerReady = trigger.triggerReady();
        if (!markerReady) { invalidateCandidate(stage, trigger.diagnostic()); return; }
        List<CollabAsdmStageRules.ClassifiedFile> selected = classified.stream().filter(CollabAsdmStageRules.ClassifiedFile::selected).toList();
        if (selected.isEmpty()) { invalidateCandidate(stage, "ASDM_MARKER_NOT_READY"); return; }
        String markerPath = trigger.markerEvidence().isEmpty() ? selected.get(0).path() : trigger.markerEvidence().get(0);
        FileContent marker = rawByPath.get(markerPath);
        if (marker == null) { invalidateCandidate(stage, "ASDM_MARKER_UNREADABLE"); return; }
        if (trigger.markerEvidence().stream().anyMatch(path -> !rawByPath.containsKey(path))) {
            invalidateCandidate(stage, "ASDM_MARKER_UNREADABLE");
            return;
        }
        List<CandidateFile> externalFiles=externalReferences(classified,rawByPath,req);
        List<CollabAsdmPackagePreparationService.PreparedFile> manifestFiles = classified.stream()
                .filter(CollabAsdmStageRules.ClassifiedFile::selected).map(f -> {
                    FileContent raw = rawByPath.get(f.path());
                    CandidateFile file = new CandidateFile(sha256(f.path().getBytes(StandardCharsets.UTF_8)), f.path(), f.association(), f.score(),
                            json(Map.of("reason", f.reason())), raw.blobSha(), raw.size(), mimeType(f.path()), f.deliveryMode(), true, true,
                            gitlabUrl(req.repositoryUrl(), head, f.path()));
                    return new CollabAsdmPackagePreparationService.PreparedFile(file, null, null, 0);
                }).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        externalFiles.stream().map(f->new CollabAsdmPackagePreparationService.PreparedFile(f,null,null,0)).forEach(manifestFiles::add);
        String manifestHash = CollabAsdmPackagePreparationService.hashManifest(stage.key(), stage.attempt(), access.projectId(), head, manifestFiles);
        Candidate current = candidate(stage.id(), stage.attempt());
        if (current != null && TERMINAL.contains(current.status())) return;
        if (current != null && Objects.equals(current.manifestHash(), manifestHash)) return;
        int revision = current == null ? 1 : current.revision() + 1;
        Instant now = Instant.now();
        String markerSha = sha256(marker.content());
        String markerMarkdown = markerPath.toLowerCase(Locale.ROOT).endsWith(".md") ? decodeUtf8(marker.content()) : null;
        String markerEvidenceJson = json(trigger.markerEvidence());
        int uncertain = (int) classified.stream().filter(f -> "UNCERTAIN".equals(f.association())).count();
        int external = externalFiles.size();
        if (current == null) {
            jdbc.update("insert into collab_asdm_candidate(requirement_id,stage_id,attempt_no,source_path,source_commit_sha,source_blob_sha,content_sha256,markdown_snapshot,snapshot_cached_at,detected_revision,status,first_detected_at,last_changed_at,notify_after,version_no,time_updated,stage_key,manifest_hash,marker_evidence_json,uncertain_count,external_ref_count,scan_scope_version) values(?,?,?,?,?,?,?,?,?,?,'DEBOUNCING',?,?,?,?,?, ?,?,?,?, ?,1)",
                    req.id(), stage.id(), stage.attempt(), markerPath, head, marker.blobSha(), markerSha, markerMarkdown, now, revision, now, now,
                    now.plus(properties.debounce()), 0, now, stage.key(), manifestHash, markerEvidenceJson, uncertain, external);
        } else {
            jdbc.update("update collab_asdm_candidate set source_path=?,source_commit_sha=?,source_blob_sha=?,content_sha256=?,markdown_snapshot=?,snapshot_cached_at=?,detected_revision=?,status='DEBOUNCING',last_changed_at=?,notify_after=?,notified_revision=NULL,notified_at=NULL,confirmed_by=NULL,confirmed_at=NULL,submission_id=NULL,last_error_code=NULL,version_no=version_no+1,time_updated=?,stage_key=?,manifest_hash=?,marker_evidence_json=?,uncertain_count=?,external_ref_count=?,scan_scope_version=1 where id=? and status not in ('CLAIMED','CLOSED_BY_SKILL','INVALIDATED','SUPERSEDED')",
                    markerPath, head, marker.blobSha(), markerSha, markerMarkdown, now, revision, now, now.plus(properties.debounce()), now,
                    stage.key(), manifestHash, markerEvidenceJson, uncertain, external, current.id());
        }
        List<CandidateFile> rows = new ArrayList<>();
        for (var f : classified) {
            FileContent raw = rawByPath.get(f.path());
            if (raw == null) continue;
            String confidence = f.association();
            String sourceUrl = gitlabUrl(req.repositoryUrl(), head, f.path());
            String mime = mimeType(f.path());
            rows.add(new CandidateFile(sha256(f.path().getBytes(StandardCharsets.UTF_8)), f.path(), confidence, f.score(),
                    json(Map.of("reason", f.reason())), raw.blobSha(), raw.size(), mime, f.deliveryMode(), f.selected(),
                    f.selected(), sourceUrl));
        }
        rows.addAll(externalFiles);
        Candidate persisted = candidate(stage.id(), stage.attempt());
        if (persisted != null) candidateFiles.replaceRevision(persisted.id(), revision, rows);
    }

    @Transactional
    public void releaseDueCandidates() {
        if(!properties.multiStageEnabled()||!properties.multiFilePublishEnabled())return;
        List<Map<String,Object>> due = jdbc.queryForList("select c.id,c.requirement_id,c.stage_id,c.detected_revision,p.status project_status,s.status stage_status,s.attempt_no from collab_asdm_candidate c join collab_project p on p.id=c.requirement_id join collab_stage s on s.id=c.stage_id where c.stage_key is not null and c.status='DEBOUNCING' and c.notify_after<=now(3) and c.manifest_hash is not null and p.status='ACTIVE' and s.status in ('IN_PROGRESS','REWORK') and s.attempt_no=c.attempt_no and s.actors_frozen_at is not null and not exists(select 1 from collab_submission sub where sub.stage_id=s.id and sub.attempt_no=s.attempt_no) and (?=1 or not exists(select 1 from collab_asdm_candidate_file f where f.candidate_id=c.id and f.candidate_revision=c.detected_revision and f.selected=1 and f.delivery_mode<>'DOCX')) order by c.notify_after limit ?", properties.multiFilePublishEnabled()?1:0, properties.pollBatchSize());
        for (Map<String,Object> c : due) {
            long id = ((Number)c.get("id")).longValue();
            int changed = jdbc.update("update collab_asdm_candidate set status='NOTIFIED',notified_revision=detected_revision,notified_at=now(3),version_no=version_no+1,time_updated=now(3) where id=? and status='DEBOUNCING' and notify_after<=now(3)", id);
            if (changed == 1) outbox.enqueueAsdmPublishCandidate(((Number)c.get("requirement_id")).longValue(), id,
                    ((Number)c.get("detected_revision")).intValue());
        }
    }

    public StageList stages(String key, long actorId) {
        var project = authorization.requireMemberOrThrow(key, actorId);
        Requirement req = requirement(project.projectId());
        List<StageSummary> items = jdbc.query("select s.stage_key,s.status,s.attempt_no,s.started_at,s.completed_at,c.id candidate_id,c.detected_revision,c.status candidate_status,c.source_commit_sha,c.notify_after,c.last_error_code,c.uncertain_count,c.external_ref_count from collab_stage s left join collab_asdm_candidate c on c.stage_id=s.id and c.attempt_no=s.attempt_no where s.project_id=? order by s.stage_order",
                (rs,n)->new StageSummary(rs.getString("stage_key"),rs.getString("status"),rs.getInt("attempt_no"),rs.getObject("candidate_id")==null?null:rs.getLong("candidate_id"),nullableInt(rs,"detected_revision"),rs.getString("candidate_status"),rs.getString("source_commit_sha"),instant(rs,"notify_after"),rs.getString("last_error_code"),nullableInt(rs,"uncertain_count"),nullableInt(rs,"external_ref_count")),project.projectId());
        return new StageList(properties.multiStageEnabled(),req.branch(), req.path(), req.lastHead(), CollabAsdmStageRules.VERSION, items);
    }

    public StageDetail stage(String key, String stageKey, long actorId) {
        var project = authorization.requireMemberOrThrow(key, actorId);
        StageSummary summary = stages(key, actorId).stages().stream().filter(s->s.stageKey().equals(stageKey)).findFirst()
                .orElseThrow(()->error("ASDM_STAGE_NOT_FOUND", HttpStatus.NOT_FOUND));
        Map<String,Object> marker = jdbc.query("select marker_evidence_json,manifest_hash from collab_asdm_candidate where requirement_id=? and stage_key=? order by id desc limit 1", rs->rs.next()?Map.of("markers",Objects.toString(rs.getString(1),"[]"),"manifest",Objects.toString(rs.getString(2),"")):Map.of(),project.projectId(),stageKey);
        Long packageId=jdbc.query("select id from collab_asdm_package where requirement_id=? and stage_id=(select id from collab_stage where project_id=? and stage_key=?) order by id desc limit 1",rs->rs.next()?rs.getLong(1):null,project.projectId(),project.projectId(),stageKey);
        return new StageDetail(summary, parseJsonList(String.valueOf(marker.get("markers"))), String.valueOf(marker.get("manifest")),packageId);
    }

    public CandidateDetail candidate(String key, String stageKey, long actorId) {
        var project = authorization.requireMemberOrThrow(key, actorId);
        Map<String,Object> row = jdbc.query("select id,stage_id,attempt_no,detected_revision,version_no,status,source_commit_sha,notify_after,manifest_hash,marker_evidence_json,uncertain_count,external_ref_count,last_error_code from collab_asdm_candidate where requirement_id=? and stage_key=? order by id desc limit 1",
                rs->rs.next()?row(rs):null, project.projectId(),stageKey);
        if(row==null) throw error("ASDM_CANDIDATE_NOT_FOUND",HttpStatus.NOT_FOUND);
        long id=((Number)row.get("id")).longValue(); int revision=((Number)row.get("detected_revision")).intValue();
        List<CandidateFile> files=candidateFiles.list(id,revision);
        String markerJson=Objects.toString(row.get("marker_evidence_json"),"[]");
        return new CandidateDetail(stageKey,((Number)row.get("attempt_no")).intValue(),id,revision,((Number)row.get("version_no")).intValue(),String.valueOf(row.get("status")),
                String.valueOf(row.get("source_commit_sha")),instant(row.get("notify_after")),!parseJsonList(markerJson).isEmpty(),
                nullableString(row.get("manifest_hash")),parseJsonList(markerJson),
                files.stream().filter(f->f.selected()&&"CONFIRMED".equals(f.association())).count(),
                files.stream().filter(f->"LIKELY".equals(f.association())).count(),
                files.stream().filter(f->"UNCERTAIN".equals(f.association())).count(),
                files.stream().filter(f->"EXTERNAL_REF".equals(f.association())).count(),files.stream().map(this::fileView).toList(),
                nullableString(row.get("last_error_code")));
    }

    @Transactional
    public ScanQueued scanNow(String key, String stageKey, long actorId) {
        var project=authorization.requireRequirementManager(key,actorId);
        if(!properties.multiStageEnabled())throw error("ASDM_MULTI_STAGE_DISABLED",HttpStatus.CONFLICT);
        if(!rules.enabled(stageKey)) throw error("ASDM_STAGE_DISABLED",HttpStatus.CONFLICT);
        Stage stage=stage(project.projectId(),stageKey);
        if(stage==null) throw error("ASDM_STAGE_NOT_FOUND",HttpStatus.NOT_FOUND);
        Instant now=Instant.now();
        Integer recent=jdbc.queryForObject("select count(*) from collab_asdm_scan_task where requirement_id=? and task_type='RESCAN' and time_created>date_sub(now(3),interval 60 second)",Integer.class,project.projectId());
        if(recent!=null&&recent>0)throw error("ASDM_SCAN_RATE_LIMITED",HttpStatus.TOO_MANY_REQUESTS);
        jdbc.update("insert into collab_asdm_scan_task(group_id,requirement_id,task_type,dedupe_key,status,available_at,time_created,time_updated) values(?,?,'RESCAN',?,'PENDING',?,?,?)",
                stage.groupId(),project.projectId(),"asdm:stage-rescan:"+project.projectId()+":"+stageKey+":"+now.toEpochMilli(),now,now,now);
        return new ScanQueued(true,now);
    }

    @Transactional
    public CandidateDetail updateAssociations(String key,String stageKey,AssociationUpdate request,long actorId) {
        var project=authorization.requireMemberOrThrow(key,actorId);
        Integer owner=jdbc.queryForObject("select count(*) from collab_stage s join collab_requirement_stage_actor a on a.stage_id=s.id and a.duty='OWNER' and a.user_id=? and a.frozen_at is not null join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where s.project_id=? and s.stage_key=? and s.status in ('IN_PROGRESS','REWORK')",Integer.class,actorId,project.groupId(),project.projectId(),stageKey);
        if(actorId!=project.groupOwnerUserId()&&actorId!=project.createdBy()&&(owner==null||owner==0))
            throw error("COLLAB_REQUIREMENT_MANAGER_REQUIRED",HttpStatus.FORBIDDEN);
        Map<String,Object> row=jdbc.query("select id,detected_revision,version_no,stage_id,attempt_no from collab_asdm_candidate where requirement_id=? and stage_key=? and status in ('DEBOUNCING','NOTIFIED') order by id desc limit 1",rs->rs.next()?row(rs):null,project.projectId(),stageKey);
        if(row==null)throw error("ASDM_CANDIDATE_NOT_FOUND",HttpStatus.NOT_FOUND);
        long candidateId=((Number)row.get("id")).longValue();int revision=((Number)row.get("detected_revision")).intValue();
        if(revision!=request.expectedRevision())throw error("ASDM_CANDIDATE_STALE",HttpStatus.CONFLICT);
        if(((Number)row.get("version_no")).intValue()!=request.expectedVersion())throw error("ASDM_CANDIDATE_STALE",HttpStatus.CONFLICT);
        List<CandidateFile> currentFiles=candidateFiles.list(candidateId,revision);
        List<String> markers=parseJsonList(Objects.toString(jdbc.queryForObject("select marker_evidence_json from collab_asdm_candidate where id=?",String.class,candidateId),"[]"));
        for(AssociationChange change:request.changes()){
            if(!Set.of("CONFIRMED","EXCLUDED").contains(change.association()))throw error("ASDM_ASSOCIATION_INVALID",HttpStatus.BAD_REQUEST);
            CandidateFile target=currentFiles.stream().filter(f->f.sourcePath().equals(change.path())).findFirst()
                    .orElseThrow(()->error("ASDM_ASSOCIATION_INVALID",HttpStatus.BAD_REQUEST));
            if(!Set.of("UNCERTAIN","LIKELY").contains(target.association())||markers.contains(change.path()))
                throw error("ASDM_ASSOCIATION_INVALID",HttpStatus.BAD_REQUEST);
            int changed=jdbc.update("update collab_asdm_file_association set association_status=?,review_actor_user_id=?,reason=?,time_updated=now(3) where requirement_id=? and stage_key=? and source_path_sha256=?",
                    change.association(),actorId,"MANUAL_REVIEW",project.projectId(),stageKey,sha256(change.path().getBytes(StandardCharsets.UTF_8)));
            if(changed==0)jdbc.update("insert into collab_asdm_file_association(requirement_id,stage_key,source_path_sha256,source_path,association_status,review_actor_user_id,reason,time_updated) values(?,?,?,?,?,?,?,now(3))",
                    project.projectId(),stageKey,sha256(change.path().getBytes(StandardCharsets.UTF_8)),change.path(),change.association(),actorId,"MANUAL_REVIEW");
        }
        scanRequirement(project.projectId());
        return candidate(key,stageKey,actorId);
    }

    public List<CandidateFile> candidateFiles(long candidateId,int revision){return candidateFiles.list(candidateId,revision);}

    private List<String> parseJsonList(String json) { try { return mapper.readValue(json,mapper.getTypeFactory().constructCollectionType(List.class,String.class)); } catch(Exception ex) { return List.of(); } }
    private List<CandidateFile> externalReferences(List<CollabAsdmStageRules.ClassifiedFile> files,Map<String,FileContent> rawByPath,Requirement req){
        Map<String,CandidateFile> refs=new LinkedHashMap<>();
        for(var file:files){
            if(!file.selected()||!"CONFIRMED".equals(file.association())||!file.path().toLowerCase(Locale.ROOT).endsWith(".md")||file.content()==null||!rawByPath.containsKey(file.path()))continue;
            Matcher matcher=URL_PATTERN.matcher(file.content());
            while(matcher.find()){
                String url=matcher.group().replaceAll("[.,;:]+$","");
                try{
                    URI uri=URI.create(url);
                    String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
                    String path=uri.getPath()==null?"":uri.getPath();
                    boolean gitlabHost=host.equals("gitlab.com")||host.endsWith(".gitlab.com")||host.equals("jihulab.com")
                            ||host.endsWith(".jihulab.com")||(host.startsWith("gitlab.")&&host.endsWith(".taikang.com"));
                    boolean commitOrMr=path.matches(".*/-/commits?/[A-Za-z0-9._-]+/?")||path.matches(".*/-/merge_requests/[0-9]+/?");
                    if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                            ||!gitlabHost||!commitOrMr)continue;
                    String urlHash=sha256(url.getBytes(StandardCharsets.UTF_8));
                    String sourcePath="external-ref/"+urlHash;
                    refs.putIfAbsent(url,new CandidateFile(sha256(sourcePath.getBytes(StandardCharsets.UTF_8)),sourcePath,"EXTERNAL_REF",null,
                            json(Map.of("reason","EXPLICIT_GITLAB_REFERENCE","sourceMarkdown",file.path())),sha1(url),0,
                            "text/uri-list","LINK_ONLY",true,true,url));
                }catch(IllegalArgumentException ignored){/* malformed or unsafe external link */}
            }
        }
        return List.copyOf(refs.values());
    }
    private CandidateFileView fileView(CandidateFile f){return new CandidateFileView(f.sourcePath(),f.association(),f.deliveryMode(),f.selected(),reason(f.reasonJson()),f.sizeBytes(),f.sourceGitUrl(),f.sourceBlobSha(),f.mandatoryPublish());}
    private String reason(String json){try{return mapper.readTree(json).path("reason").asText(json);}catch(Exception ex){return json;}}
    private Map<String,Object> row(ResultSet rs)throws SQLException{Map<String,Object>m=new LinkedHashMap<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)m.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));return m;}
    private Map<String,String> associationOverrides(long req,String stage){Map<String,String>m=new LinkedHashMap<>();jdbc.query("select source_path,association_status from collab_asdm_file_association where requirement_id=? and stage_key=?",(org.springframework.jdbc.core.RowCallbackHandler)rs->m.put(rs.getString(1),rs.getString(2)),req,stage);return m;}
    private Map<String,String> treeMap(List<TreeEntry> entries){Map<String,String>m=new LinkedHashMap<>();for(TreeEntry e:entries)if("blob".equals(e.type()))m.put(e.path(),e.id());return m;}
    private boolean readable(String path){String l=path.toLowerCase(Locale.ROOT);return l.startsWith("workspace/")&&READABLE_SUFFIXES.stream().anyMatch(l::endsWith)&&path.length()<=1600&&!path.contains("..")&&!path.contains("\\");}
    private String decodeUtf8(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(Exception ex){return null;}}
    private String gitlabUrl(String repo,String branch,String path){return repo+"/-/blob/"+encode(branch)+"/"+encodePath(path);}
    private String encodePath(String path){return java.util.Arrays.stream(path.split("/")).map(this::encode).collect(java.util.stream.Collectors.joining("/"));}
    private String mimeType(String path){String l=path.toLowerCase(Locale.ROOT);if(l.endsWith(".md")||l.endsWith(".txt"))return "text/plain";if(l.endsWith(".xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";if(l.endsWith(".sql"))return "application/sql";if(l.endsWith(".json"))return "application/json";if(l.endsWith(".html"))return "text/html";if(l.endsWith(".png"))return "image/png";if(l.endsWith(".jpg")||l.endsWith(".jpeg"))return "image/jpeg";return "application/octet-stream";}
    private String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private String sha1(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private boolean hasActiveOwner(long groupId,long stageId){Integer n=jdbc.queryForObject("select count(*) from collab_requirement_stage_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? and a.duty='OWNER' and a.frozen_at is not null",Integer.class,groupId,stageId);return n!=null&&n>0;}
    private boolean submissionClaimed(long stage,int attempt){Integer n=jdbc.queryForObject("select count(*) from collab_submission where stage_id=? and attempt_no=?",Integer.class,stage,attempt);return n!=null&&n>0;}
    private void closeBySubmission(Stage stage){jdbc.update("update collab_asdm_candidate set status='CLOSED_BY_SKILL',last_error_code='SUBMISSION_ALREADY_CLAIMED',version_no=version_no+1,time_updated=now(3) where stage_id=? and attempt_no=? and stage_key is not null and status not in ('CLAIMED','CLOSED_BY_SKILL','INVALIDATED','SUPERSEDED')",stage.id(),stage.attempt());}
    private void invalidateCandidate(Stage stage,String code){jdbc.update("update collab_asdm_candidate set status='INVALIDATED',last_error_code=?,version_no=version_no+1,time_updated=now(3) where stage_id=? and attempt_no=? and stage_key is not null and status in ('DEBOUNCING','NOTIFIED')",code,stage.id(),stage.attempt());}
    private void markScanError(long id,String code){jdbc.update("update collab_project set asdm_binding_status=case when asdm_binding_status='AUTH_PAUSED' then asdm_binding_status else 'ERROR' end,time_updated=now(3) where id=?",id);jdbc.update("update collab_asdm_candidate set last_error_code=?,time_updated=now(3) where requirement_id=? and stage_key is not null and status in ('DEBOUNCING','NOTIFIED')",code,id);}
    private void markStageScanError(Stage stage,String code){jdbc.update("update collab_asdm_candidate set last_error_code=?,time_updated=now(3) where stage_id=? and attempt_no=? and stage_key is not null and status in ('DEBOUNCING','NOTIFIED')",code,stage.id(),stage.attempt());}
    private void pauseCredential(long groupId,String code){jdbc.update("update collab_asdm_credential set status=case when ?='GITLAB_INVALID_TOKEN' then 'INVALID_TOKEN' else 'PERMISSION_DENIED' end,last_error_code=?,time_updated=now(3) where group_id=?",code,code,groupId);jdbc.update("update collab_project set asdm_binding_status='AUTH_PAUSED',time_updated=now(3) where group_id=? and asdm_branch is not null",groupId);}
    private Candidate candidate(long stage,int attempt){return jdbc.query("select id,detected_revision,status,manifest_hash from collab_asdm_candidate where stage_id=? and attempt_no=?",rs->rs.next()?new Candidate(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getString(4)):null,stage,attempt);}
    private Stage stage(long projectId,String key){return jdbc.query("select s.id,s.project_id,s.stage_key,s.status,s.attempt_no,p.group_id from collab_stage s join collab_project p on p.id=s.project_id where s.project_id=? and s.stage_key=?",rs->rs.next()?new Stage(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getLong(6)):null,projectId,key);}
    private Stage stage(ResultSet rs)throws SQLException{return new Stage(rs.getLong("id"),0,rs.getString("stage_key"),rs.getString("status"),rs.getInt("attempt_no"),0);}
    private Requirement requirement(long id){return jdbc.query("select p.id,p.name,p.group_id,p.status project_status,p.asdm_branch,p.asdm_requirement_path,p.asdm_baseline_head_sha,p.asdm_last_scanned_head_sha,g.status group_status,g.asdm_repository_url from collab_project p join collab_group g on g.id=p.group_id where p.id=?",rs->rs.next()?new Requirement(rs.getLong("id"),rs.getString("name"),rs.getLong("group_id"),rs.getString("project_status"),rs.getString("asdm_branch"),rs.getString("asdm_requirement_path"),rs.getString("asdm_baseline_head_sha"),rs.getString("asdm_last_scanned_head_sha"),rs.getString("group_status"),rs.getString("asdm_repository_url")):null,id);}
    private String json(Object o){try{return mapper.writeValueAsString(o);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private String sha256(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private Integer nullableInt(ResultSet rs,String k)throws SQLException{int x=rs.getInt(k);return rs.wasNull()?null:x;}
    private Instant instant(ResultSet rs,String key)throws SQLException{var t=rs.getTimestamp(key);return t==null?null:t.toInstant();}
    private Instant instant(Object value){if(value instanceof java.sql.Timestamp t)return t.toInstant();return null;}
    private String nullableString(Object value){return value==null?null:String.valueOf(value);}
    private BusinessException error(String code,HttpStatus status){return new BusinessException(code,code,status);}

    public record StageList(boolean enabled,String branch,String requirementPath,String lastHead,String ruleVersion,List<StageSummary> stages){}
    public record StageSummary(String stageKey,String status,int attemptNo,Long candidateId,Integer revision,String candidateStatus,String sourceCommitSha,Instant notifyAfter,String lastErrorCode,Integer uncertainCount,Integer externalRefCount){}
    public record StageDetail(StageSummary stage,List<String> markerEvidence,String manifestHash,Long packageId){}
    public record CandidateDetail(String stageKey,int attemptNo,long candidateId,int revision,int versionNo,String status,String sourceCommitSha,Instant notifyAfter,boolean markerReady,String manifestHash,List<String> markerEvidence,long confirmedCount,long likelyCount,long uncertainCount,long externalRefCount,List<CandidateFileView> files,String lastErrorCode){}
    public record CandidateFileView(String path,String association,String deliverAs,boolean selected,String reason,long size,String gitlabUrl,String blobSha,boolean mandatoryPublish){}
    public record ScanQueued(boolean accepted,Instant queuedAt){}
    public record AssociationUpdate(int expectedRevision,int expectedVersion,List<AssociationChange> changes){}
    public record AssociationChange(String path,String association){}
    private record Requirement(long id,String name,long groupId,String projectStatus,String branch,String path,String baselineHead,String lastHead,String groupStatus,String repositoryUrl){}
    private record Stage(long id,long projectId,String key,String status,int attempt,long groupId){}
    private record Candidate(long id,int revision,String status,String manifestHash){}
}
