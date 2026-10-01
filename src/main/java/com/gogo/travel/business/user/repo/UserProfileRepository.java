package com.gogo.travel.business.user.repo;

import com.gogo.travel.business.user.entity.UserProfile;

import java.util.Optional;

public interface UserProfileRepository {

    Optional<UserProfile> findByUserId(String userId);

    UserProfile save(UserProfile profile);
}
