package com.myhockeystats.repository;

import com.myhockeystats.model.OAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, UUID> {

    Optional<OAuthAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    List<OAuthAccount> findByUserId(Long userId);

    Optional<OAuthAccount> findByUserIdAndProvider(Long userId, String provider);
}
