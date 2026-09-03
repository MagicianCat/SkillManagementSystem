package com.company.skillplatform.notification.application;

import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import java.util.Collection;

/** Extension point for recommending a newly published version to users who downloaded an older version. */
public interface VersionUpdateRecipientProvider {
    Collection<IamUserEntity> recipientsFor(SkillVersionEntity publishedVersion);
}

