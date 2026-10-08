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
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
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
    @Override public synchronized AncestorDistance ancestorDistance(String locator, String ancestorSha, String targetSha) {
        String url = normalized(locator);
        Path directory = properties.getCacheRoot().toAbsolutePath().normalize().resolve(hash(url));
        try {
            if (!Files.exists(directory.resolve("HEAD"))) {
                var cloneCommand = Git.cloneRepository().setURI(url).setBare(true).setDirectory(directory.toFile()).setTimeout(timeoutSeconds());
                CredentialsProvider credentials = credentials(); if (credentials != null) cloneCommand.setCredentialsProvider(credentials);
                var clone = cloneCommand.call();
                clone.close();
            } else {
                try (Git git = new Git(new FileRepositoryBuilder().setGitDir(directory.toFile()).setBare().build())) {
                    var fetch = git.fetch().setRemote(url).setRefSpecs(new RefSpec("+refs/heads/*:refs/heads/*")).setTimeout(timeoutSeconds());
                    CredentialsProvider c = credentials(); if (c != null) fetch.setCredentialsProvider(c); fetch.call();
                }
            }
            try (var repository = new FileRepositoryBuilder().setGitDir(directory.toFile()).setBare().build(); RevWalk walk = new RevWalk(repository)) {
                RevCommit base = walk.parseCommit(repository.resolve(ancestorSha));
                RevCommit target = walk.parseCommit(repository.resolve(targetSha));
                if (!walk.isMergedInto(base, target)) return new AncestorDistance(false, -1);
                walk.reset(); walk.markStart(target); walk.markUninteresting(base);
                int distance = 0; for (RevCommit ignored : walk) distance++;
                return new AncestorDistance(true, distance);
            }
        } catch (Exception failure) {
            throw error("GIT_ANCESTOR_CHECK_FAILED", "Unable to inspect Git commit ancestry: " + safeMessage(failure), HttpStatus.BAD_GATEWAY);
        }
    }
    @Override public synchronized FrozenRepository freeze(String locator, String branch) {
        String url = normalized(locator);
        Path directory = properties.getCacheRoot().toAbsolutePath().normalize().resolve(hash(url));
        try {
            Files.createDirectories(directory.getParent());
            Git git;
            if (Files.exists(directory.resolve("HEAD"))) {
                git = new Git(new FileRepositoryBuilder().setGitDir(directory.toFile()).setBare().build());
                var fetch = git.fetch().setRemote(url).setRefSpecs(new RefSpec("+refs/heads/" + branch + ":refs/heads/" + branch)).setTimeout(timeoutSeconds());
                CredentialsProvider c = credentials(); if (c != null) fetch.setCredentialsProvider(c); fetch.call();
            } else {
                var clone = Git.cloneRepository().setURI(url).setBare(true).setDirectory(directory.toFile())
                        .setBranchesToClone(List.of("refs/heads/" + branch)).setTimeout(timeoutSeconds());
                CredentialsProvider c = credentials(); if (c != null) clone.setCredentialsProvider(c); git = clone.call();
            }
            try (git; RevWalk walk = new RevWalk(git.getRepository())) {
                RevCommit commit = walk.parseCommit(git.getRepository().resolve("refs/heads/" + branch));
                String commitSha = commit.name();
                String treeSha = commit.getTree().getId().name();
                Path root = properties.getSourcePackageRoot().toAbsolutePath().normalize().resolve(hash(url));
                Files.createDirectories(root);
                Path target = root.resolve(commitSha + ".tar");
                if (!Files.exists(target)) writeArchive(directory, branch, target);
                return new FrozenRepository(repositoryPath(url), url, branch, commitSha, treeSha,
                        logicalKey(url), workerUri(target), sha256(target));
            }
        } catch (BusinessException failure) { throw failure;
        } catch (Exception failure) {
            throw error("GIT_SOURCE_PACKAGE_FAILED", "Unable to freeze Git source package: " + safeMessage(failure), HttpStatus.BAD_GATEWAY);
        }
    }

    private void writeArchive(Path bareRepository, String branch, Path target) throws Exception {
        Path temp = Files.createTempFile(target.getParent(), "source-", ".tmp");
        Path checkout = Files.createTempDirectory(target.getParent(), "checkout-");
        long total = 0L;
        try (Git ignored = Git.cloneRepository().setURI(bareRepository.toUri().toString()).setBranch(branch)
                .setDirectory(checkout.toFile()).setCloneAllBranches(false).call();
             var out = Files.newOutputStream(temp); var tar = new TarArchiveOutputStream(out);
             var paths = Files.walk(checkout)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            // Never follow repository-controlled symlinks while producing a server-side archive.
            for (Path path : paths.filter(candidate -> Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = checkout.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                if (name.isBlank() || name.startsWith("../") || name.contains("/../")) throw new IllegalStateException("Unsafe Git path");
                long size = Files.size(path);
                total = Math.addExact(total, size);
                if (total > properties.getMaxSourcePackageBytes()) throw new IllegalStateException("Git source package exceeds configured limit");
                TarArchiveEntry entry = new TarArchiveEntry(name); entry.setSize(size);
                tar.putArchiveEntry(entry); Files.copy(path, tar); tar.closeArchiveEntry();
            }
            tar.finish();
        } catch (Exception failure) { Files.deleteIfExists(temp); throw failure; }
        finally { deleteDirectory(checkout); }
        try { Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    }

    private String workerUri(Path target) {
        String configured = properties.getSourcePackageWorkerUriRoot();
        if (configured == null || configured.isBlank()) return target.toUri().toString();
        String prefix = configured.replaceAll("/+$", "");
        Path packageRoot = properties.getSourcePackageRoot().toAbsolutePath().normalize();
        String relative = packageRoot.relativize(target).toString().replace(java.io.File.separatorChar, '/');
        return prefix + "/" + relative;
    }
    private void deleteDirectory(Path directory) {
        if (directory == null || !directory.normalize().startsWith(properties.getSourcePackageRoot().toAbsolutePath().normalize())) return;
        try (var paths = Files.walk(directory)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); }
        catch (Exception ignored) {}
    }

    private String sha256(Path path) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (var in = Files.newInputStream(path)) { byte[] buffer = new byte[8192]; for (int n; (n = in.read(buffer)) >= 0;) if (n > 0) digest.update(buffer, 0, n); } return java.util.HexFormat.of().formatHex(digest.digest()); }
    private String logicalKey(String url) { return url.replaceAll("\\.git$", "").toLowerCase(Locale.ROOT); }
    public String normalized(String locator){if(!properties.isEnabled())throw error("GIT_DISABLED","Git integration is disabled",HttpStatus.SERVICE_UNAVAILABLE);String raw=locator==null?"":locator.trim();if(raw.isBlank())throw error("GIT_URL_REQUIRED","Repository URL is required",HttpStatus.BAD_REQUEST);if("BASE_RELATIVE".equalsIgnoreCase(properties.getUrlMode())&&!raw.contains("://"))raw=properties.getBaseUrl().replaceAll("/$","")+"/"+raw.replaceAll("^/","");URI uri;try{uri=URI.create(raw);}catch(Exception e){throw error("GIT_URL_INVALID","Repository URL is invalid",HttpStatus.BAD_REQUEST);}if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw error("GIT_URL_FORBIDDEN","Only credential-free HTTPS repository URLs are allowed",HttpStatus.BAD_REQUEST);String host=uri.getHost().toLowerCase(Locale.ROOT);if(host.equals("localhost")||host.endsWith(".localhost")||host.matches("[0-9.]+")||host.contains(":"))throw error("GIT_HOST_FORBIDDEN","Local and IP repository hosts are not allowed",HttpStatus.BAD_REQUEST);if(properties.allowedHostSet().isEmpty()||!properties.allowedHostSet().contains(host))throw error("GIT_HOST_FORBIDDEN","Repository host is not allowed",HttpStatus.BAD_REQUEST);String path=uri.getPath()==null?"":uri.getPath().replaceAll("/{2,}","/");if(path.length()<2)throw error("GIT_URL_INVALID","Repository path is required",HttpStatus.BAD_REQUEST);return "https://"+host+(uri.getPort()>0?":"+uri.getPort():"")+path.replaceAll("/$","");}
    private String defaultBranch(String url,List<RemoteBranch> branches){try{var command=Git.lsRemoteRepository().setRemote(url).setHeads(false).setTags(false).setTimeout(timeoutSeconds());CredentialsProvider c=credentials();if(c!=null)command.setCredentialsProvider(c);Map<String,Ref> refs=command.callAsMap();Ref head=refs.get("HEAD");if(head!=null&&head.isSymbolic())return head.getTarget().getName().replace("refs/heads/","");}catch(Exception ignored){}for(String fallback:List.of("main","master"))if(branches.stream().anyMatch(x->x.name().equals(fallback)))return fallback;return branches.size()==1?branches.get(0).name():null;}
    private CredentialsProvider credentials(){return "HTTP_TOKEN".equalsIgnoreCase(properties.getAuthMode())?new UsernamePasswordCredentialsProvider(properties.getUsername(),properties.getToken()):null;}
    private int timeoutSeconds(){return Math.max(1,(int)properties.getReadTimeout().toSeconds());} private String repositoryPath(String url){return URI.create(url).getPath().replaceAll("^/","");}
    private String hash(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));return java.util.HexFormat.of().formatHex(bytes);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private String safeMessage(Exception failure){String m=failure.getMessage();return m==null?failure.getClass().getSimpleName():m.replaceAll("https://[^@/]+@","https://");} private BusinessException error(String code,String message,HttpStatus status){return new BusinessException(code,message,status);}
}
