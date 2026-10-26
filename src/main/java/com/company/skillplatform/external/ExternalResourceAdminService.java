package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.application.SkillService;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import com.company.skillplatform.version.application.SkillFileService;
import com.company.skillplatform.version.application.ZipSecurityValidator;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExternalResourceAdminService {
    private final JdbcTemplate db;
    private final ObjectStoragePort storage;
    private final ZipSecurityValidator skillZip;
    private final SkillService skills;
    private final SkillFileService files;
    private final SkillRepository skillRepository;
    private final WikiDocumentService wiki;
    private final TransactionTemplate transactions;

    public ExternalResourceAdminService(JdbcTemplate db, ObjectStoragePort storage, ZipSecurityValidator skillZip,
            SkillService skills, SkillFileService files, SkillRepository skillRepository,
            WikiDocumentService wiki, TransactionTemplate transactions) {
        this.db=db; this.storage=storage; this.skillZip=skillZip; this.skills=skills;
        this.files=files; this.skillRepository=skillRepository; this.wiki=wiki; this.transactions=transactions;
    }

    public void audit(String event, String targetType, Long targetId, Long actorId, String requestId) {
        db.update("INSERT INTO audit_log(time_created,time_updated,event_type,actor_user_id,target_type,target_id,request_id,result) VALUES(NOW(3),NOW(3),?,?,?,?,?,'SUCCESS')",
                event,actorId,targetType,targetId,requestId);
    }

    public List<Map<String,Object>> roles() {
        return db.queryForList("SELECT id,source_system AS sourceSystem,external_role_id AS externalRoleId,role_name AS roleName,status,updated_at AS updatedAt FROM external_role ORDER BY updated_at DESC");
    }

    public List<Map<String,Object>> resources(Long roleId, String type, String keyword, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT r.id,r.source_system AS sourceSystem,r.external_resource_id AS externalResourceId,r.resource_type AS resourceType,r.name,r.description,r.development_stage AS developmentStage,r.sub_stage_key AS subStageKey,r.current_version_id AS currentVersionId,v.external_version AS externalVersion,v.size_bytes AS sizeBytes,v.received_at AS receivedAt,rr.role_id AS roleId FROM external_resource r JOIN external_role_resource rr ON rr.resource_id=r.id LEFT JOIN external_resource_version v ON v.id=r.current_version_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (roleId != null) { sql.append(" AND rr.role_id=?"); args.add(roleId); }
        if (type != null && !type.isBlank()) { sql.append(" AND r.resource_type=?"); args.add(type); }
        if (keyword != null && !keyword.isBlank()) { sql.append(" AND (r.name LIKE ? OR r.external_resource_id LIKE ?)"); args.add("%"+keyword+"%"); args.add("%"+keyword+"%"); }
        sql.append(" ORDER BY r.updated_at DESC LIMIT ? OFFSET ?");
        int boundedSize=Math.min(Math.max(size,1),100);
        args.add(boundedSize); args.add(Math.max(page,0)*boundedSize);
        return db.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String,Object>> versions(Long resourceId) {
        return db.queryForList("SELECT v.id,v.external_version AS externalVersion,v.file_name AS fileName,v.size_bytes AS sizeBytes,v.sha256,v.storage_status AS storageStatus,v.development_stage AS developmentStage,v.sub_stage_key AS subStageKey,v.received_at AS receivedAt FROM external_resource_version v WHERE v.resource_id=? ORDER BY v.id DESC",resourceId);
    }

    public List<Map<String,Object>> imports(Long versionId) {
        return db.queryForList("SELECT id,external_role_id AS externalRoleId,entry_path AS entryPath,target_type AS targetType,target_id AS targetId,draft_version_id AS draftVersionId,action,result,error_code AS errorCode,operator_id AS operatorId,created_at AS createdAt FROM external_import_record WHERE resource_version_id=? ORDER BY id DESC",versionId);
    }

    public Download download(Long versionId) {
        Map<String,Object> v = version(versionId);
        try (InputStream in = storage.get((String)v.get("object_key"))) {
            byte[] bytes=in.readNBytes(50*1024*1024+1);
            if (bytes.length > 50*1024*1024) throw bad("FILE_TOO_LARGE","文件过大");
            return new Download((String)v.get("file_name"), bytes);
        } catch (IOException ex) { throw bad("STORAGE_ERROR","读取原始 ZIP 失败"); }
    }

    public SkillPreparation prepareSkill(Long versionId) {
        return prepareSkill(versionId,null);
    }

    public SkillPreparation prepareSkill(Long versionId,String requestedKey) {
        Map<String,Object> v = versionOfType(versionId,"SKILL");
        try (InputStream in=storage.get((String)v.get("object_key"))) {
            var archive=skillZip.validate(in);
            String markdown=new String(archive.files().get("SKILL.md"),StandardCharsets.UTF_8);
            String key=null;
            for (String line:markdown.split("\\R",40)) {
                if (line.startsWith("name:")) { key=line.substring(5).trim().replaceAll("^[\"']|[\"']$", ""); break; }
            }
            if (key!=null && !key.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) key=null;
            String checkedKey=requestedKey==null||requestedKey.isBlank()?key:requestedKey;
            var existing=checkedKey==null?null:skillRepository.findBySkillKey(checkedKey).orElse(null);
            return new SkillPreparation(key,archive.files().size(),existing!=null,existing==null?null:existing.getId(),existing==null||existing.getActiveDraftVersion()==null?null:existing.getActiveDraftVersion().getId());
        } catch (IOException ex) { throw bad("STORAGE_ERROR","读取 ZIP 失败"); }
    }

    public SkillImportResult importSkill(Long versionId, SkillImportRequest request, Long actorId, String requestId) {
        versionOfType(versionId,"SKILL");
        if (request==null || request.skillKey()==null || !request.skillKey().matches("^[a-z0-9]+(-[a-z0-9]+)*$")) throw bad("INVALID_SKILL_KEY","请填写合法的 Skill Key");
        var preparation=prepareSkill(versionId);
        if (preparation.skillKey()!=null && !preparation.skillKey().equals(request.skillKey())) throw bad("SKILL_KEY_MISMATCH","Skill Key 与 SKILL.md 不一致");
        Download raw=download(versionId);
        return transactions.execute(status -> {
            List<Map<String,Object>> prior=db.queryForList("SELECT target_id FROM external_import_record WHERE resource_version_id=? AND target_type='SKILL' AND result='SUCCESS' ORDER BY id DESC LIMIT 1",versionId);
            if (!prior.isEmpty() && !request.reimport()) throw bad("ALREADY_IMPORTED","该版本已导入；重新导入请明确确认");
            var existing=skillRepository.findBySkillKey(request.skillKey()).orElse(null);
            if (existing!=null && !request.confirmExisting()) throw bad("SKILL_KEY_EXISTS","同 Key 已存在，请明确确认");
            if (existing!=null && existing.getActiveDraftVersion()!=null) throw bad("DRAFT_CONFLICT","已存在草稿，请先处理现有草稿");
            if (existing==null) {
                if (request.categoryId()==null || request.displayName()==null || request.displayName().isBlank() || request.description()==null || request.description().isBlank()) throw bad("METADATA_REQUIRED","请补齐 Skill 元数据");
                skills.create(new SkillService.CreateCommand(request.skillKey(),request.displayName(),request.description(),request.categoryId(),List.of(actorId),List.of(),request.teamId(),null),actorId,requestId);
            }
            var uploaded=files.upload(request.skillKey(),new ByteMultipart(raw.fileName(),raw.bytes()),"从外部资源导入",actorId,requestId);
            Long skillId=skillRepository.findBySkillKey(request.skillKey()).orElseThrow().getId();
            db.update("INSERT INTO external_import_record(resource_version_id,target_type,target_id,draft_version_id,action,result,operator_id) VALUES(?,'SKILL',?,?,'IMPORT_DRAFT','SUCCESS',?)",versionId,skillId,uploaded.versionId(),actorId);
            return new SkillImportResult(request.skillKey(),skillId,uploaded.versionId());
        });
    }

    public List<DocumentEntry> documents(Long versionId) {
        versionOfType(versionId,"DOCUMENT");
        return parseDocuments(versionId).entrySet().stream().map(e->new DocumentEntry(e.getKey(),e.getValue().length)).toList();
    }

    public String documentContent(Long versionId,String path) {
        versionOfType(versionId,"DOCUMENT");
        byte[] content=parseDocuments(versionId).get(path);
        if (content==null) throw bad("DOCUMENT_NOT_FOUND","ZIP 中没有该文档");
        return new String(content,StandardCharsets.UTF_8);
    }

    public Readiness readiness(Long roleId) {
        List<Map<String,Object>> pending = new ArrayList<>(); Set<Long> ids = new LinkedHashSet<>();
        List<Map<String,Object>> resources=db.queryForList("SELECT r.id,r.name,r.external_resource_id AS externalResourceId,r.current_version_id AS versionId FROM external_role_resource rr JOIN external_resource r ON r.id=rr.resource_id WHERE rr.role_id=? AND r.resource_type='SKILL'",roleId);
        for (Map<String,Object> resource:resources) {
            Long versionId=((Number)resource.get("versionId")).longValue();
            List<Long> imported=db.query("SELECT target_id FROM external_import_record WHERE resource_version_id=? AND target_type='SKILL' AND result='SUCCESS' ORDER BY id DESC LIMIT 1",(rs,n)->rs.getLong(1),versionId);
            if (imported.isEmpty() || !skillRepository.existsById(imported.get(0))) pending.add(resource);
            else ids.add(imported.get(0));
        }
        return new Readiness(!resources.isEmpty() && pending.isEmpty(),pending,List.copyOf(ids));
    }

    public List<DocumentImportResult> importDocuments(Long versionId,DocumentImportRequest request,Long actorId,String requestId) {
        Map<String,Object> v=versionOfType(versionId,"DOCUMENT");
        if (request==null || request.externalRoleId()==null || request.paths()==null || request.paths().isEmpty() || request.paths().size()>50) throw bad("INVALID_REQUEST","请选择角色和文档");
        Integer linked=db.queryForObject("SELECT COUNT(*) FROM external_role_resource WHERE role_id=? AND resource_id=?",Integer.class,request.externalRoleId(),v.get("resource_id"));
        if (linked==null || linked==0) throw bad("ROLE_RESOURCE_MISMATCH","该角色未关联此资源");
        Readiness ready=readiness(request.externalRoleId());
        if (!ready.ready()) throw bad("ROLE_SKILLS_NOT_READY","该角色的全部当前 Skill 须先导入草稿："+ready.pendingSkills());
        if (request.skillIds()!=null) throw bad("SKILL_IDS_FORBIDDEN","Skill 关联只能由服务端确定");
        Map<String,byte[]> docs=parseDocuments(versionId);
        List<DocumentImportResult> results=new ArrayList<>();
        for (String path:new LinkedHashSet<>(request.paths())) {
            try {
                byte[] data=docs.get(path);
                if (data==null) throw bad("DOCUMENT_NOT_FOUND","ZIP 中没有该文档");
                List<Long> prior=db.query("SELECT target_id FROM external_import_record WHERE resource_version_id=? AND external_role_id=? AND entry_path=? AND target_type='WIKI' AND result='SUCCESS' ORDER BY id DESC LIMIT 1",(rs,n)->rs.getLong(1),versionId,request.externalRoleId(),path);
                if (!prior.isEmpty()) { results.add(new DocumentImportResult(path,"UNCHANGED",prior.get(0),null,ready.skillIds())); continue; }
                Long createdId=transactions.execute(status -> {
                    Readiness latest=readiness(request.externalRoleId());
                    if (!latest.ready()) throw bad("ROLE_SKILLS_NOT_READY","Skill 导入状态已变化，请刷新");
                    var created=wiki.create(new WikiDocumentService.CreateCommand(path.substring(path.lastIndexOf('/')+1).replaceFirst("(?i)\\.md$",""),request.documentType()==null?"SKILL_GUIDE":request.documentType(),request.teamId(),latest.skillIds(),new String(data,StandardCharsets.UTF_8)),actorId,requestId);
                    db.update("INSERT INTO external_import_record(resource_version_id,external_role_id,entry_path,target_type,target_id,action,result,operator_id) VALUES(?,?,?,'WIKI',?,'IMPORT','SUCCESS',?)",versionId,request.externalRoleId(),path,created.id(),actorId);
                    return created.id();
                });
                results.add(new DocumentImportResult(path,"CREATED",createdId,null,ready.skillIds()));
            } catch (RuntimeException ex) { results.add(new DocumentImportResult(path,"FAILED",null,ex instanceof BusinessException be?be.getCode():"IMPORT_ERROR",ready.skillIds())); }
        }
        return results;
    }

    private Map<String,byte[]> parseDocuments(Long versionId) {
        Map<String,Object> v=versionOfType(versionId,"DOCUMENT");
        Map<String,byte[]> result=new LinkedHashMap<>(); Set<String> seen=new LinkedHashSet<>(); long total=0; long started=System.nanoTime();
        Path archive=null;
        try {
            archive=Files.createTempFile("external-document-preview-",".zip");
            try (InputStream source=storage.get((String)v.get("object_key")); var sink=Files.newOutputStream(archive)) {
                byte[] chunk=new byte[8192]; long compressed=0; int n;
                while ((n=source.read(chunk))!=-1) {
                    compressed+=n;
                    if (compressed>50L*1024*1024) throw bad("ZIP_COMPRESSED_SIZE_LIMIT","ZIP 文件过大");
                    sink.write(chunk,0,n);
                }
            }
            try (ZipFile zip=ZipFile.builder().setPath(archive).setCharset(StandardCharsets.UTF_8).get()) {
            var entries=zip.getEntries(); byte[] buffer=new byte[8192]; int count=0;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry=entries.nextElement();
                if (System.nanoTime()-started>15_000_000_000L) throw bad("ZIP_PARSE_TIMEOUT","ZIP 解析超时");
                if (++count>2000) throw bad("ZIP_FILE_COUNT_LIMIT","ZIP 文件数过多");
                String path=entry.getName();
                if (entry.isUnixSymlink() || path.length()>512 || path.startsWith("/") || path.contains("..") || path.contains("\\") || path.indexOf('\0')>=0 || !seen.add(path)) throw bad("ZIP_PATH_INVALID","ZIP 路径不安全");
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out=new ByteArrayOutputStream(); int n;
                try (InputStream content=zip.getInputStream(entry)) { while ((n=content.read(buffer))!=-1) {
                    total+=n;
                    if (total>20*1024*1024 || (path.toLowerCase(java.util.Locale.ROOT).endsWith(".md") && out.size()+n>1024*1024)) throw bad("ZIP_EXPANDED_SIZE_LIMIT","ZIP 解压内容过大");
                    if (System.nanoTime()-started>15_000_000_000L) throw bad("ZIP_PARSE_TIMEOUT","ZIP 解析超时");
                    if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) out.write(buffer,0,n);
                } }
                if (entry.getCompressedSize()>0 && entry.getSize()>0 && entry.getSize()/entry.getCompressedSize()>200) throw bad("ZIP_COMPRESSION_RATIO_LIMIT","ZIP 压缩比过高");
                if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) result.put(path,out.toByteArray());
            }
            }
        } catch (BusinessException ex) { throw ex; } catch (IOException ex) { throw bad("ZIP_INVALID","ZIP 内容无法解析"); }
        finally { if (archive!=null) try { Files.deleteIfExists(archive); } catch (IOException ignored) { } }
        return result;
    }

    private Map<String,Object> version(Long versionId) {
        List<Map<String,Object>> rows=db.queryForList("SELECT v.*,r.resource_type FROM external_resource_version v JOIN external_resource r ON r.id=v.resource_id WHERE v.id=? AND v.storage_status='AVAILABLE'",versionId);
        if (rows.isEmpty()) throw bad("VERSION_NOT_FOUND","资源版本不存在");
        return rows.get(0);
    }
    private Map<String,Object> versionOfType(Long id,String type) {
        Map<String,Object> row=version(id);
        if (!type.equals(row.get("resource_type"))) throw bad("RESOURCE_TYPE_MISMATCH","资源类型不匹配");
        return row;
    }
    private BusinessException bad(String code,String message) { return new BusinessException(code,message,HttpStatus.BAD_REQUEST); }
    public record Download(String fileName,byte[] bytes) {}
    public record SkillPreparation(String skillKey,int fileCount,boolean exists,Long skillId,Long activeDraftVersionId) {}
    public record SkillImportRequest(String skillKey,String displayName,String description,Long categoryId,Long teamId,boolean confirmExisting,boolean reimport) {}
    public record SkillImportResult(String skillKey,Long skillId,Long draftVersionId) {}
    public record DocumentEntry(String path,long sizeBytes) {}
    public record Readiness(boolean ready,List<Map<String,Object>> pendingSkills,List<Long> skillIds) {}
    public record DocumentImportRequest(Long externalRoleId,List<String> paths,Long teamId,String documentType,List<Long> skillIds) {}
    public record DocumentImportResult(String path,String status,Long wikiDocumentId,String errorCode,List<Long> linkedSkillIds) {}
    private record ByteMultipart(String filename,byte[] bytes) implements MultipartFile {
        public String getName(){return "file";} public String getOriginalFilename(){return filename;}
        public String getContentType(){return "application/zip";} public boolean isEmpty(){return bytes.length==0;}
        public long getSize(){return bytes.length;} public byte[] getBytes(){return bytes;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public void transferTo(java.io.File dest)throws IOException{java.nio.file.Files.write(dest.toPath(),bytes);}
    }
}
