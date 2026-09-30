package com.company.skillplatform.git.infrastructure;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.git.domain.GitRemotePort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class JGitRemoteAdapter implements GitRemotePort {
    private final GitProperties properties;
    public JGitRemoteAdapter(GitProperties properties){this.properties=properties;}
    @Override public RepositoryResolution resolve(String locator){String url=normalized(locator);List<RemoteBranch> refs=branches(url);String branch=defaultBranch(url,refs);String sha=refs.stream().filter(x->x.name().equals(branch)).map(RemoteBranch::commitSha).findFirst().orElse(null);return new RepositoryResolution(repositoryPath(url),url,branch,refs.stream().map(RemoteBranch::name).toList(),sha);}
    @Override public List<RemoteBranch> branches(String locator){String url=normalized(locator);try{var command=Git.lsRemoteRepository().setRemote(url).setHeads(true).setTags(false).setTimeout(timeoutSeconds());CredentialsProvider credentials=credentials();if(credentials!=null)command.setCredentialsProvider(credentials);return command.call().stream().filter(r->r.getName().startsWith("refs/heads/")).map(r->new RemoteBranch(r.getName().substring("refs/heads/".length()),r.getObjectId().name())).sorted(Comparator.comparing(RemoteBranch::name)).limit(properties.getMaxBranchesPerRepository()).toList();}catch(Exception failure){throw error("GIT_REMOTE_UNREACHABLE","Unable to read Git repository: "+safeMessage(failure),HttpStatus.UNPROCESSABLE_ENTITY);}}
    @Override public BranchHead head(String locator,String branch){return branches(locator).stream().filter(x->x.name().equals(branch)).findFirst().map(x->new BranchHead(branch,x.commitSha())).orElseThrow(()->error("GIT_BRANCH_NOT_FOUND","Git branch was not found",HttpStatus.NOT_FOUND));}
    @Override public synchronized List<CommitMetadata> commits(String locator,String branch,String oldSha,String newSha){String url=normalized(locator);Path directory=properties.getCacheRoot().toAbsolutePath().normalize().resolve(hash(url));try{Files.createDirectories(directory.getParent());Git git;if(Files.exists(directory.resolve("HEAD"))){git=new Git(new FileRepositoryBuilder().setGitDir(directory.toFile()).setBare().build());var fetch=git.fetch().setRemote(url).setRefSpecs(new RefSpec("+refs/heads/"+branch+":refs/heads/"+branch)).setTimeout(timeoutSeconds());CredentialsProvider c=credentials();if(c!=null)fetch.setCredentialsProvider(c);fetch.call();}else{var clone=Git.cloneRepository().setURI(url).setBare(true).setDirectory(directory.toFile()).setBranchesToClone(List.of("refs/heads/"+branch)).setTimeout(timeoutSeconds());CredentialsProvider c=credentials();if(c!=null)clone.setCredentialsProvider(c);git=clone.call();}try(git;RevWalk walk=new RevWalk(git.getRepository())){RevCommit current=walk.parseCommit(git.getRepository().resolve(newSha));String stop=oldSha==null?"":oldSha;List<CommitMetadata> result=new ArrayList<>();walk.markStart(current);for(RevCommit commit:walk){if(commit.name().equals(stop))break;String parent=commit.getParentCount()==0?null:commit.getParent(0).name();result.add(new CommitMetadata(commit.name(),parent,commit.getAuthorIdent().getName(),commit.getAuthorIdent().getEmailAddress(),Instant.ofEpochSecond(commit.getCommitTime()),commit.getShortMessage()));if(result.size()>=200)break;}return result;}}catch(Exception failure){throw error("GIT_METADATA_FETCH_FAILED","Unable to fetch Git commit metadata: "+safeMessage(failure),HttpStatus.BAD_GATEWAY);}}
    public String normalized(String locator){if(!properties.isEnabled())throw error("GIT_DISABLED","Git integration is disabled",HttpStatus.SERVICE_UNAVAILABLE);String raw=locator==null?"":locator.trim();if(raw.isBlank())throw error("GIT_URL_REQUIRED","Repository URL is required",HttpStatus.BAD_REQUEST);if("BASE_RELATIVE".equalsIgnoreCase(properties.getUrlMode())&&!raw.contains("://"))raw=properties.getBaseUrl().replaceAll("/$","")+"/"+raw.replaceAll("^/","");URI uri;try{uri=URI.create(raw);}catch(Exception e){throw error("GIT_URL_INVALID","Repository URL is invalid",HttpStatus.BAD_REQUEST);}if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw error("GIT_URL_FORBIDDEN","Only credential-free HTTPS repository URLs are allowed",HttpStatus.BAD_REQUEST);String host=uri.getHost().toLowerCase(Locale.ROOT);if(host.equals("localhost")||host.endsWith(".localhost")||host.matches("[0-9.]+")||host.contains(":"))throw error("GIT_HOST_FORBIDDEN","Local and IP repository hosts are not allowed",HttpStatus.BAD_REQUEST);if(properties.allowedHostSet().isEmpty()||!properties.allowedHostSet().contains(host))throw error("GIT_HOST_FORBIDDEN","Repository host is not allowed",HttpStatus.BAD_REQUEST);String path=uri.getPath()==null?"":uri.getPath().replaceAll("/{2,}","/");if(path.length()<2)throw error("GIT_URL_INVALID","Repository path is required",HttpStatus.BAD_REQUEST);return "https://"+host+(uri.getPort()>0?":"+uri.getPort():"")+path.replaceAll("/$","");}
    private String defaultBranch(String url,List<RemoteBranch> branches){try{var command=Git.lsRemoteRepository().setRemote(url).setHeads(false).setTags(false).setTimeout(timeoutSeconds());CredentialsProvider c=credentials();if(c!=null)command.setCredentialsProvider(c);Map<String,Ref> refs=command.callAsMap();Ref head=refs.get("HEAD");if(head!=null&&head.isSymbolic())return head.getTarget().getName().replace("refs/heads/","");}catch(Exception ignored){}for(String fallback:List.of("main","master"))if(branches.stream().anyMatch(x->x.name().equals(fallback)))return fallback;return branches.size()==1?branches.get(0).name():null;}
    private CredentialsProvider credentials(){return "HTTP_TOKEN".equalsIgnoreCase(properties.getAuthMode())?new UsernamePasswordCredentialsProvider(properties.getUsername(),properties.getToken()):null;}
    private int timeoutSeconds(){return Math.max(1,(int)properties.getReadTimeout().toSeconds());} private String repositoryPath(String url){return URI.create(url).getPath().replaceAll("^/","");}
    private String hash(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));return java.util.HexFormat.of().formatHex(bytes);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private String safeMessage(Exception failure){String m=failure.getMessage();return m==null?failure.getClass().getSimpleName():m.replaceAll("https://[^@/]+@","https://");} private BusinessException error(String code,String message,HttpStatus status){return new BusinessException(code,message,status);}
}
