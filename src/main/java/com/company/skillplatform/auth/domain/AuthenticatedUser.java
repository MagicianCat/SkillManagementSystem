package com.company.skillplatform.auth.domain;

import java.util.List;

public record AuthenticatedUser(Long id, String username, String displayName, List<String> roles,
                                List<String> permissions) {
}
