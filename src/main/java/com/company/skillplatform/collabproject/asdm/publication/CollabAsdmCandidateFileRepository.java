package com.company.skillplatform.collabproject.asdm.publication;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores a revision's classified file inventory separately from the candidate's trigger metadata. */
@Repository
public class CollabAsdmCandidateFileRepository {
    private final JdbcTemplate jdbc;
    public CollabAsdmCandidateFileRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional
    public void replaceRevision(long candidateId,int revision,List<CandidateFile> files) {
        jdbc.update("DELETE FROM collab_asdm_candidate_file WHERE candidate_id=? AND candidate_revision=?",candidateId,revision);
        for (CandidateFile f:files) {
            jdbc.update("INSERT INTO collab_asdm_candidate_file(candidate_id,candidate_revision,source_path_sha256,source_path,association,association_score,reason_json,source_blob_sha,size_bytes,media_type,delivery_mode,selected,mandatory_publish,source_git_url,time_created,time_updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(3),NOW(3))",
                    candidateId,revision,f.sourcePathSha256(),f.sourcePath(),f.association(),f.associationScore(),f.reasonJson(),f.sourceBlobSha(),
                    f.sizeBytes(),f.mediaType(),f.deliveryMode(),f.selected(),f.mandatoryPublish(),f.sourceGitUrl());
        }
    }

    public List<CandidateFile> list(long candidateId,int revision) {
        return jdbc.query("SELECT source_path_sha256,source_path,association,association_score,reason_json,source_blob_sha,size_bytes,media_type,delivery_mode,selected,mandatory_publish,source_git_url FROM collab_asdm_candidate_file WHERE candidate_id=? AND candidate_revision=? ORDER BY source_path",
                (rs,n)->new CandidateFile(rs.getString("source_path_sha256"),rs.getString("source_path"),rs.getString("association"),
                        nullableInt(rs,"association_score"),rs.getString("reason_json"),rs.getString("source_blob_sha"),rs.getLong("size_bytes"),
                        rs.getString("media_type"),rs.getString("delivery_mode"),rs.getBoolean("selected"),rs.getBoolean("mandatory_publish"),rs.getString("source_git_url")),candidateId,revision);
    }
    private static Integer nullableInt(java.sql.ResultSet rs,String key)throws java.sql.SQLException {int x=rs.getInt(key);return rs.wasNull()?null:x;}

    public record CandidateFile(String sourcePathSha256,String sourcePath,String association,Integer associationScore,String reasonJson,
            String sourceBlobSha,long sizeBytes,String mediaType,String deliveryMode,boolean selected,boolean mandatoryPublish,String sourceGitUrl) {}
}
