package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.common.application.BusinessException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Validates GitLab namespace roots and ensures repository evidence stays under the requirement root. */
@Component
public class CollabGitlabRootValidator {
    private static final Set<String> TRUSTED_HOSTS = Set.of("gitlab.it.taikang.com");

    public String normalizeRoot(String value) {
        URI uri = parse(value);
        String host = host(uri);
        if (!TRUSTED_HOSTS.contains(host) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw invalidRoot();
        String path = normalizePath(uri.getPath());
        if (path.isBlank() || "/".equals(path) || path.toLowerCase(Locale.ROOT).endsWith(".git")) throw invalidRoot();
        return "https://" + host + path;
    }

    public void validateEvidence(String rootValue, String repositoryUrl, String commitOrPullRequestUrl) {
        String root = normalizeRoot(rootValue);
        URI rootUri = URI.create(root);
        URI repository = evidenceUri(repositoryUrl);
        URI commit = evidenceUri(commitOrPullRequestUrl);
        String rootPath = normalizePath(rootUri.getPath());
        String repoPath = normalizePath(repository.getPath());
        if (!sameOrigin(rootUri, repository) || !underPath(repoPath, rootPath)) throw outOfRoot();
        if (!sameOrigin(repository, commit)) throw outOfRoot();
        String repoBase = repoPath.endsWith(".git") ? repoPath.substring(0, repoPath.length() - 4) : repoPath;
        String commitPath = normalizePath(commit.getPath());
        if (!underPath(commitPath, repoBase) || commitPath.equals(repoBase)) throw outOfRoot();
    }

    private URI evidenceUri(String value) {
        URI uri = parse(value);
        if (!TRUSTED_HOSTS.contains(host(uri)) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw outOfRoot();
        String path = normalizePath(uri.getPath());
        if (path.isBlank() || "/".equals(path)) throw outOfRoot();
        return uri;
    }

    private URI parse(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException ex) {
            throw invalidRoot();
        }
    }

    private String host(URI uri) { return uri.getHost().toLowerCase(Locale.ROOT); }
    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "";
        String clean = path.replaceAll("/{2,}", "/");
        while (clean.length() > 1 && clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return clean;
    }
    private boolean underPath(String candidate, String root) { return candidate.equals(root) || candidate.startsWith(root + "/"); }
    private boolean sameOrigin(URI a, URI b) { return "https".equalsIgnoreCase(a.getScheme()) && host(a).equals(host(b)) && effectivePort(a) == effectivePort(b); }
    private int effectivePort(URI uri) { return uri.getPort() < 0 ? 443 : uri.getPort(); }
    private BusinessException invalidRoot() { return new BusinessException("COLLAB_GITLAB_ROOT_INVALID", "GitLab root must be an HTTPS namespace URL on an approved GitLab host", HttpStatus.UNPROCESSABLE_ENTITY); }
    private BusinessException outOfRoot() { return new BusinessException("COLLAB_GIT_EVIDENCE_OUT_OF_ROOT", "Git evidence must belong to the requirement GitLab root namespace", HttpStatus.UNPROCESSABLE_ENTITY); }
}
