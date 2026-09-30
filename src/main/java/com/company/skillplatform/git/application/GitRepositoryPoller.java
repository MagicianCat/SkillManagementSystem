package com.company.skillplatform.git.application;

import com.company.skillplatform.git.infrastructure.GitProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GitRepositoryPoller {
    private final JdbcTemplate jdbc; private final GitProperties properties; private final GitStageWatchService watches; private final String owner=UUID.randomUUID().toString();
    public GitRepositoryPoller(JdbcTemplate jdbc,GitProperties properties,GitStageWatchService watches){this.jdbc=jdbc;this.properties=properties;this.watches=watches;}
    @Scheduled(fixedDelayString="${skill-platform.git.poll-interval:PT15S}") public void poll(){if(!properties.isEnabled())return;List<Long> ids=jdbc.query("select id from stage_git_watch where status in ('WATCHING','CHANGED','ERROR','BRANCH_MISSING') and (next_poll_at is null or next_poll_at<=now(3)) and (lease_until is null or lease_until<now(3)) order by coalesce(next_poll_at,time_created),id limit ?",(rs,n)->rs.getLong(1),properties.getPollBatchSize());for(Long id:ids)if(jdbc.update("update stage_git_watch set lease_owner=?,lease_until=date_add(now(3),interval 30 second),time_updated=now(3) where id=? and (lease_until is null or lease_until<now(3))",owner,id)>0)watches.poll(id);}
}
