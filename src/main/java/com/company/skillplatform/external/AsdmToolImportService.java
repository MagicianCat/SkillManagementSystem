package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AsdmToolImportService {
    private static final String SOURCE_PATH = "ASDM-TOOL";
    private static final String BEGIN = "<!-- YANTU_ASDM_DOWNLOAD_BEGIN -->";
    private static final String END = "<!-- YANTU_ASDM_DOWNLOAD_END -->";
    private final JdbcTemplate db;
    private final ObjectStoragePort storage;
    private final WikiDocumentService wiki;
    private final TransactionTemplate transactions;
    private final String baseUrl;
    private final long maxInnerBytes;
    private final long maxOuterBytes;

    public AsdmToolImportService(JdbcTemplate db, ObjectStoragePort storage, WikiDocumentService wiki, TransactionTemplate transactions,
            @Value("${integration.external.asdm-tool.download-base-url:}") String baseUrl,
            @Value("${integration.external.asdm-tool.max-inner-zip-bytes:52428800}") long maxInnerBytes,
            @Value("${integration.external.max-file-bytes:52428800}") long maxOuterBytes) {
        this.db=db; this.storage=storage; this.wiki=wiki; this.transactions=transactions; this.baseUrl=baseUrl; this.maxInnerBytes=maxInnerBytes; this.maxOuterBytes=maxOuterBytes;
    }

    public record ImportCommand(boolean confirmUpdate, Integer expectedWikiVersionNo) {}
    public record ImportResult(String status, Long resourceVersionId, Long assetId, Long wikiDocumentId, String wikiTitle,
            String toolFilename, long toolSizeBytes, String downloadUrl, String wikiUrl) {}
    public record Status(boolean imported, Long resourceVersionId, Long assetId, Long wikiDocumentId, boolean wikiActive,
            String toolFilename, String downloadUrl, Instant importedAt, Integer wikiVersionNo) {}

    public Status status(long versionId) {
        Map<String,Object> source=source(versionId);
        List<Map<String,Object>> rows=db.queryForList("SELECT a.id assetId,a.wiki_document_id wikiId,a.filename,a.created_at,d.status wikiStatus,d.version_no wikiVersionNo FROM external_asdm_tool_asset a LEFT JOIN wiki_document d ON d.id=a.wiki_document_id WHERE a.resource_version_id=? AND a.status='READY'", versionId);
        if (rows.isEmpty()) {
            List<Map<String,Object>> existing=db.queryForList("SELECT id,status,version_no FROM wiki_document WHERE source_system=? AND source_resource_id=? AND source_entry_path=? AND team_id IS NULL ORDER BY id DESC LIMIT 1",source.get("source_system"),source.get("external_resource_id"),SOURCE_PATH);
            if (existing.isEmpty()) return new Status(false,versionId,null,null,false,null,null,null,null);
            var wiki=existing.get(0);
            return new Status(false,versionId,null,((Number)wiki.get("id")).longValue(),"ACTIVE".equals(wiki.get("status")),null,null,null,((Number)wiki.get("version_no")).intValue());
        }
        var row=rows.get(0); long id=((Number)row.get("assetId")).longValue();
        return new Status(true,versionId,id,((Number)row.get("wikiId")).longValue(),"ACTIVE".equals(row.get("wikiStatus")),(String)row.get("filename"),urlFor(id),((java.sql.Timestamp)row.get("created_at")).toInstant(),((Number)row.get("wikiVersionNo")).intValue());
    }

    public ImportResult importWiki(long versionId, ImportCommand command, long actor, String requestId) {
        Map<String,Object> source=source(versionId);
        Status prior=status(versionId);
        if (prior.imported() && prior.wikiActive()) return result("UNCHANGED",source,prior.assetId(),prior.wikiDocumentId(),prior.toolFilename());
        if (prior.imported()) throw error("ASDM_WIKI_ARCHIVED","原 Wiki 已归档，请先处理归档文档",HttpStatus.CONFLICT);
        if (prior.wikiDocumentId()!=null && !prior.wikiActive()) throw error("ASDM_WIKI_ARCHIVED","原 Wiki 已归档，请先处理归档文档",HttpStatus.CONFLICT);
        if (!versionIdEquals(source.get("current_version_id"),versionId)) throw error("ASDM_OLD_VERSION_NOT_CURRENT","只能导入当前外部资源版本",HttpStatus.CONFLICT);
        validatedBaseUrl();
        Path outer=null, inner=null;
        Long assetId=null; String objectKey=null;
        try {
            outer=Files.createTempFile("asdm-outer-", ".zip");
            try (InputStream in=storage.get((String)source.get("object_key")); var out=Files.newOutputStream(outer)) {
                byte[] buffer=new byte[8192]; long count=0; int n;
                while ((n=in.read(buffer))!=-1) { count+=n; if (count>maxOuterBytes) throw error("ASDM_ARCHIVE_INVALID","ASDM 外层 ZIP 超过大小限制",HttpStatus.UNPROCESSABLE_ENTITY); out.write(buffer,0,n); }
            }
            var entries=AsdmOuterArchive.inspect(outer,maxInnerBytes);
            String markdown=decode(AsdmOuterArchive.readMarkdown(outer,entries.markdownPath()));
            if (markdown.contains(BEGIN) || markdown.contains(END)) throw error("ASDM_MD_MARKER_CONFLICT","原始 Markdown 包含系统保留标记",HttpStatus.UNPROCESSABLE_ENTITY);
            inner=AsdmOuterArchive.extractTool(outer,entries.packagePath(),maxInnerBytes);
            String sha=sha256(inner); long size=Files.size(inner);
            String candidateKey="external-resources/asdm-tool-assets/pending/"+java.util.UUID.randomUUID()+".zip";
            GeneratedKeyHolder holder=new GeneratedKeyHolder();
            db.update(connection -> {
                var ps=connection.prepareStatement("INSERT INTO external_asdm_tool_asset(resource_id,resource_version_id,inner_entry_path,filename,object_key,sha256,size_bytes,status,created_by) VALUES(?,?,?,?,?,?,?,'STAGING',?)",Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1,((Number)source.get("resource_id")).longValue()); ps.setLong(2,versionId); ps.setString(3,entries.packagePath()); ps.setString(4,entries.packageFilename()); ps.setString(5,candidateKey); ps.setString(6,sha); ps.setLong(7,size); ps.setLong(8,actor); return ps;
            },holder);
            assetId=holder.getKey().longValue();
            objectKey=candidateKey;
            try (InputStream in=Files.newInputStream(inner)) { storage.put(objectKey,in,size,"application/zip"); }
            long finalAssetId=assetId;
            String content=markdown.stripTrailing()+"\n\n---\n\n"+BEGIN+"\n## ASDM 工具包下载\n\n[下载 ASDM 工具包（"+escape(entries.packageFilename())+"）]("+urlFor(assetId)+")\n\n> 下载需要登录研途助手；下载文件为 ASDM 完整工具压缩包。\n"+END+"\n";
            var saved=transactions.execute(tx -> {
                List<Long> existing=db.query("SELECT id FROM wiki_document WHERE source_system=? AND source_resource_id=? AND source_entry_path=? AND team_id IS NULL AND status='ACTIVE' FOR UPDATE",(rs,n)->rs.getLong(1),source.get("source_system"),source.get("external_resource_id"),SOURCE_PATH);
                WikiDocumentService.DocumentView document;
                if (existing.isEmpty()) {
                    document=wiki.create(new WikiDocumentService.CreateCommand((String)source.get("name"),"SKILL_GUIDE",null,List.of(),content,null),actor,requestId);
                    wiki.markExternalSource(document.id(),(String)source.get("source_system"),(String)source.get("external_resource_id"),SOURCE_PATH);
                } else {
                    if (command==null || !command.confirmUpdate()) throw error("ASDM_WIKI_UPDATE_CONFIRM_REQUIRED","更新平台 Wiki 前需确认覆盖",HttpStatus.CONFLICT);
                    var current=wiki.get(existing.get(0),actor);
                    if (command.expectedWikiVersionNo()==null || command.expectedWikiVersionNo()!=current.versionNo()) throw error("OPTIMISTIC_LOCK_CONFLICT","Wiki 版本已变化，请刷新后重试",HttpStatus.CONFLICT);
                    document=wiki.update(current.id(),new WikiDocumentService.UpdateCommand((String)source.get("name"),content,current.versionNo(),List.of(),null,false),actor,requestId);
                }
                db.update("UPDATE external_asdm_tool_asset SET wiki_document_id=?,status='READY' WHERE id=?",document.id(),finalAssetId);
                db.update("INSERT INTO external_import_record(resource_version_id,entry_path,target_type,target_id,action,result,operator_id) VALUES(?,?,'ASDM_TOOL',?,?,'SUCCESS',?)",versionId,SOURCE_PATH,finalAssetId,existing.isEmpty()?"IMPORT":"UPDATE",actor);
                return document;
            });
            db.update("INSERT INTO audit_log(time_created,time_updated,event_type,actor_user_id,target_type,target_id,request_id,result) VALUES(NOW(3),NOW(3),?,?,?,?,?,'SUCCESS')",prior.wikiDocumentId()!=null?"EXTERNAL_ASDM_WIKI_UPDATED":"EXTERNAL_ASDM_IMPORTED",actor,"WIKI_DOCUMENT",saved.id(),requestId);
            return result(prior.wikiDocumentId()!=null?"UPDATED":"CREATED",source,assetId,saved.id(),entries.packageFilename());
        } catch (DuplicateKeyException duplicate) {
            Status raced=status(versionId);
            if (raced.imported()) return result("UNCHANGED",source,raced.assetId(),raced.wikiDocumentId(),raced.toolFilename());
            throw error("ASDM_IMPORT_IN_PROGRESS","该版本正在导入，请稍后重试",HttpStatus.CONFLICT);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw error("ASDM_ASSET_STORAGE_ERROR","ASDM 工具包导入失败",HttpStatus.INTERNAL_SERVER_ERROR); }
        finally {
            if (assetId!=null) {
                Integer ready=db.queryForObject("SELECT COUNT(*) FROM external_asdm_tool_asset WHERE id=? AND status='READY'",Integer.class,assetId);
                if (ready==null || ready==0) { if (objectKey!=null) storage.delete(objectKey); db.update("DELETE FROM external_asdm_tool_asset WHERE id=? AND status='STAGING'",assetId); }
            }
            try { if (outer!=null) Files.deleteIfExists(outer); if (inner!=null) Files.deleteIfExists(inner); } catch (Exception ignored) {}
        }
    }

    public ImportResult refreshLink(long versionId, long actor, String requestId) {
        Map<String,Object> source=source(versionId);
        Status current=status(versionId);
        if (!current.imported() || !current.wikiActive()) throw error("ASDM_ASSET_NOT_FOUND","该版本没有可更新的 Wiki",HttpStatus.NOT_FOUND);
        var document=wiki.get(current.wikiDocumentId(),actor);
        String text=document.markdownContent();
        int begin=text.indexOf(BEGIN), end=text.indexOf(END);
        if (begin<0 || end<begin) throw error("ASDM_MD_MARKER_CONFLICT","Wiki 中缺少系统下载链接标记",HttpStatus.CONFLICT);
        String block=BEGIN+"\n## ASDM 工具包下载\n\n[下载 ASDM 工具包（"+escape(current.toolFilename())+"）]("+urlFor(current.assetId())+")\n\n> 下载需要登录研途助手；下载文件为 ASDM 完整工具压缩包。\n"+END;
        String replaced=text.substring(0,begin)+block+text.substring(end+END.length());
        if (!replaced.equals(text)) wiki.update(document.id(),new WikiDocumentService.UpdateCommand(document.title(),replaced,document.versionNo(),null,null,false),actor,requestId);
        return result(replaced.equals(text)?"UNCHANGED":"UPDATED",source,current.assetId(),document.id(),current.toolFilename());
    }

    public Map<String,Object> asset(long id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT a.id,a.filename,a.size_bytes sizeBytes,a.object_key objectKey,a.status,d.status wikiStatus,d.platform_visible platformVisible FROM external_asdm_tool_asset a JOIN wiki_document d ON d.id=a.wiki_document_id WHERE a.id=?",id);
        if (rows.isEmpty() || !"READY".equals(rows.get(0).get("status")) || !"ACTIVE".equals(rows.get(0).get("wikiStatus")) || !Boolean.TRUE.equals(rows.get(0).get("platformVisible")))
            throw error("ASDM_ASSET_NOT_FOUND","ASDM 工具包不可用",HttpStatus.NOT_FOUND);
        return rows.get(0);
    }
    public InputStream open(String key) { return storage.get(key); }
    @Scheduled(fixedDelayString="${integration.external.asdm-tool.orphan-sweep-ms:3600000}")
    public void removeStaleStagingAssets() {
        for (Map<String,Object> row:db.queryForList("SELECT id,object_key FROM external_asdm_tool_asset WHERE status='STAGING' AND created_at < DATE_SUB(NOW(3), INTERVAL 1 HOUR) LIMIT 100")) {
            storage.delete((String)row.get("object_key"));
            db.update("DELETE FROM external_asdm_tool_asset WHERE id=? AND status='STAGING'",row.get("id"));
        }
    }
    private Map<String,Object> source(long id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT v.id,v.resource_id,v.object_key,v.storage_status,r.current_version_id,r.source_system,r.external_resource_id,r.name,r.resource_type FROM external_resource_version v JOIN external_resource r ON r.id=v.resource_id WHERE v.id=?",id);
        if (rows.isEmpty()) throw error("EXTERNAL_VERSION_NOT_FOUND","外部资源版本不存在",HttpStatus.NOT_FOUND);
        if (!"AVAILABLE".equals(rows.get(0).get("storage_status"))) throw error("EXTERNAL_VERSION_NOT_FOUND","外部资源版本不可读",HttpStatus.NOT_FOUND);
        if (!"ASDM-TOOL".equals(rows.get(0).get("resource_type"))) throw error("ASDM_RESOURCE_TYPE_MISMATCH","资源不是 ASDM-TOOL",HttpStatus.BAD_REQUEST);
        return rows.get(0);
    }
    private ImportResult result(String status,Map<String,Object> source,long assetId,long wikiId,String filename) {
        long size=db.queryForObject("SELECT size_bytes FROM external_asdm_tool_asset WHERE id=?",Long.class,assetId);
        return new ImportResult(status,((Number)source.get("id")).longValue(),assetId,wikiId,(String)source.get("name"),filename,size,urlFor(assetId),"/wiki?documentId="+wikiId);
    }
    public String urlFor(long assetId) { return validatedBaseUrl()+"/asdm-tools/download/"+assetId; }
    private String validatedBaseUrl() {
        try {
            String value=baseUrl.strip(); URI uri=URI.create(value);
            if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || value.contains("\\") || value.indexOf("//",value.indexOf("://")+3)>=0) throw new IllegalArgumentException();
            return value.replaceAll("/+$", "");
        } catch (Exception ex) { throw error("ASDM_DOWNLOAD_BASE_URL_INVALID","ASDM_TOOL_DOWNLOAD_BASE_URL 未配置或无效",HttpStatus.INTERNAL_SERVER_ERROR); }
    }
    private static boolean versionIdEquals(Object value,long id) { return value instanceof Number number && number.longValue()==id; }
    private static String decode(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException ex) { throw error("ASDM_MD_INVALID","Markdown 不是有效 UTF-8",HttpStatus.UNPROCESSABLE_ENTITY); }
    }
    private static String sha256(Path path) throws Exception { MessageDigest digest=MessageDigest.getInstance("SHA-256"); try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest()); }
    private static String escape(String s) { return s.replace("\\","\\\\").replace("[","\\[").replace("]","\\]").replace("(","\\(").replace(")","\\)"); }
    private static BusinessException error(String code,String message,HttpStatus status) { return new BusinessException(code,message,status); }
}
