package com.company.skillplatform.collabproject.asdm.publication;

import com.company.skillplatform.auth.application.FeishuUserTokenService;
import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Feishu Wiki/Drive API adapter for stage folders and raw non-Markdown artifacts. */
@Component
public class CollabAsdmFeishuAttachmentGateway {
    private static final long MAX_UPLOAD_BYTES=20L*1024*1024;
    private final FeishuUserTokenService tokens;
    private final ObjectMapper json;
    private final String base;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CollabAsdmFeishuAttachmentGateway(FeishuUserTokenService tokens,FeishuProperties config,ObjectMapper json) {
        this.tokens=tokens; this.json=json; this.base=config.apiBaseUrl().replaceAll("/$","");
    }

    public WikiFolder createWikiFolder(long actorId,String spaceId,String parentNodeToken,String title) {
        try {
            var body=json.createObjectNode().put("obj_type","docx").put("parent_node_token",parentNodeToken)
                    .put("node_type","origin").put("title",title);
            JsonNode node=request(actorId,"POST","/wiki/v2/spaces/"+segment(spaceId)+"/nodes",json.writeValueAsBytes(body),"application/json")
                    .path("data").path("node");
            String nodeToken=text(node,"node_token","nodeToken","token");
            String objectToken=text(node,"obj_token","objToken");
            if(nodeToken.isBlank()||objectToken.isBlank()) throw new IllegalStateException("ASDM_FEISHU_FOLDER_RESPONSE_INCOMPLETE");
            return new WikiFolder(nodeToken,objectToken);
        } catch(Exception ex) { throw normalize("ASDM_FEISHU_FOLDER_FAILED",ex); }
    }

    public PublishedAttachment uploadAndMount(long actorId,String driveFolderToken,String spaceId,String wikiFolderNodeToken,
            String fileName,String mediaType,byte[] bytes) {
        String fileToken=uploadToDrive(actorId,driveFolderToken,fileName,mediaType,bytes);
        return mountDriveFile(actorId,spaceId,wikiFolderNodeToken,fileToken,fileName);
    }

    public String uploadToDrive(long actorId,String driveFolderToken,String fileName,String mediaType,byte[] bytes) {
        if(bytes==null||bytes.length==0) throw new IllegalArgumentException("ASDM_FILE_EMPTY");
        if(bytes.length>MAX_UPLOAD_BYTES) throw new IllegalArgumentException("ASDM_FEISHU_UPLOAD_LIMIT");
        return upload(actorId,driveFolderToken,fileName,mediaType,bytes);
    }

    public PublishedAttachment mountDriveFile(long actorId,String spaceId,String wikiFolderNodeToken,String fileToken,String fileName) {
        try {
            var body=json.createObjectNode().put("obj_type","file").put("obj_token",fileToken)
                    .put("parent_wiki_token",wikiFolderNodeToken);
            JsonNode data=request(actorId,"POST","/wiki/v2/spaces/"+segment(spaceId)+"/nodes/move_docs_to_wiki",
                    json.writeValueAsBytes(body),"application/json").path("data");
            String nodeToken=text(data,"wiki_token","node_token");
            if(nodeToken.isBlank()) {
                String taskId=text(data,"task_id");
                if(!taskId.isBlank()) nodeToken=awaitMoveTask(actorId,taskId);
            }
            if(nodeToken.isBlank()) throw new IllegalStateException("ASDM_FEISHU_FILE_MOUNT_RESPONSE_INCOMPLETE");
            return new PublishedAttachment(fileToken,nodeToken,"https://tkhome.feishu.cn/wiki/"+nodeToken);
        } catch(Exception ex) { throw normalize("ASDM_FEISHU_FILE_MOUNT_FAILED",ex); }
    }

    public String rootDriveFolderToken(long actorId) {
        try {
            JsonNode data=request(actorId,"GET","/drive/explorer/v2/root_folder/meta",new byte[0],"application/json")
                    .path("data");
            String token=text(data,"token");
            if(token.isBlank()) throw new IllegalStateException("ASDM_FEISHU_DRIVE_ROOT_RESPONSE_INCOMPLETE");
            return token;
        } catch(Exception ex) { throw normalize("ASDM_FEISHU_DRIVE_ROOT_FAILED",ex); }
    }

    private String awaitMoveTask(long actorId,String taskId) throws Exception {
        for(int attempt=0;attempt<20;attempt++) {
            JsonNode data=request(actorId,"GET","/wiki/v2/tasks/"+segment(taskId)+"?task_type=move",
                    new byte[0],"application/json").path("data");
            String nodeToken=text(data,"wiki_token","node_token");
            if(nodeToken.isBlank()) {
                JsonNode results=data.path("task").path("move_result");
                if(!results.isArray()) results=data.path("move_result");
                if(results.isArray()) for(JsonNode result:results) {
                    nodeToken=text(result.path("node"),"node_token");
                    if(!nodeToken.isBlank()) break;
                }
            }
            if(!nodeToken.isBlank()) return nodeToken;
            Thread.sleep(1000);
        }
        throw new IllegalStateException("ASDM_FEISHU_MOVE_TASK_PENDING");
    }

    private String upload(long actorId,String folderToken,String fileName,String mediaType,byte[] bytes) {
        String boundary="----YanShuAsdm"+UUID.randomUUID().toString().replace("-","");
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream(bytes.length+1024);
            field(out,boundary,"file_name",fileName); field(out,boundary,"parent_type","explorer");
            field(out,boundary,"parent_node",folderToken); field(out,boundary,"size",String.valueOf(bytes.length));
            out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+safeFilename(fileName)+"\"\r\nContent-Type: "+safeMediaType(mediaType)+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes); out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            JsonNode response=request(actorId,"POST","/drive/v1/files/upload_all",out.toByteArray(),"multipart/form-data; boundary="+boundary);
            String token=text(response.path("data"),"file_token","fileToken");
            if(token.isBlank()) throw new IllegalStateException("ASDM_FEISHU_UPLOAD_RESPONSE_INCOMPLETE");
            return token;
        } catch(Exception ex) { throw normalize("ASDM_FEISHU_UPLOAD_FAILED",ex); }
    }

    private JsonNode request(long actorId,String method,String path,byte[] body,String contentType) throws Exception {
        String access=tokens.accessTokenFor(actorId);
        if(access==null||access.isBlank()) throw new IllegalStateException("FEISHU_AUTH_REQUIRED");
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(60))
                .header("Authorization","Bearer "+access);
        HttpRequest request=("GET".equals(method)?builder.GET():builder.header("Content-Type",contentType)
                .method(method,HttpRequest.BodyPublishers.ofByteArray(body))).build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode result=json.readTree(response.body());
        if(response.statusCode()/100!=2||result.path("code").asInt(-1)!=0)
            throw new IllegalStateException("Feishu API rejected request: code="+result.path("code").asInt(-1)+",msg="+result.path("msg").asText("").replaceAll("[\\r\\n]", " ")+",path="+path);
        return result;
    }

    private static void field(ByteArrayOutputStream out,String boundary,String name,String value)throws Exception {
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private static String text(JsonNode node,String...names){for(String name:names){String v=node.path(name).asText("");if(!v.isBlank())return v;}return "";}
    private static String segment(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private static String safeFilename(String name){if(name==null||name.isBlank()||name.contains("\r")||name.contains("\n")||name.contains("\"")||name.contains("/"))throw new IllegalArgumentException("ASDM_FILENAME_INVALID");return name;}
    private static String safeMediaType(String type){return type!=null&&type.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")?type:"application/octet-stream";}
    private static RuntimeException normalize(String code,Exception ex){return ex instanceof RuntimeException r?r:new IllegalStateException(code,ex);}
    public record WikiFolder(String nodeToken,String objectToken) {}
    public record PublishedAttachment(String fileToken,String nodeToken,String url) {}
}
