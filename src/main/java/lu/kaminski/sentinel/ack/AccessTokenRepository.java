package lu.kaminski.sentinel.ack;

import lu.kaminski.sentinel.domain.AccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface AccessTokenRepository extends JpaRepository<AccessToken, Long> {

    Optional<AccessToken> findByTokenHash(String tokenHash);

    /** Dernier digest non acquitté. */
    @Query("SELECT t FROM AccessToken t WHERE t.type = 'DIGEST' AND t.ackedAt IS NULL ORDER BY t.createdAt DESC LIMIT 1")
    Optional<AccessToken> findLatestUnackedDigest();

    /** Tous les digests non acquittés plus anciens que `beforeCreatedAt`. */
    @Modifying
    @Transactional
    @Query("UPDATE AccessToken t SET t.ackedAt = :now WHERE t.type = 'DIGEST' AND t.ackedAt IS NULL AND t.createdAt < :beforeCreatedAt")
    void acknowledgeOlderDigests(String beforeCreatedAt, String now);

    @Modifying
    @Transactional
    @Query("DELETE FROM AccessToken t WHERE t.expiresAt < :before")
    int deleteExpiredBefore(String before);

    @Query("SELECT t FROM AccessToken t WHERE t.type = 'DIGEST' AND t.ackedAt IS NULL AND t.createdAt < :before")
    List<AccessToken> findUnackedDigestsBefore(String before);
}
