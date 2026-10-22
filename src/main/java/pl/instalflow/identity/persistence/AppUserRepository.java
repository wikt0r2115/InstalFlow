package pl.instalflow.identity.persistence;

import pl.instalflow.identity.domain.AppUser;
import pl.instalflow.identity.domain.RoleEnum;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmailNormalized(String emailNormalized);

    boolean existsByEmailNormalized(String emailNormalized);

    Page<AppUser> findByRoleAndActiveTrue(RoleEnum role, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.role = :role and u.active = true order by u.id")
    List<AppUser> lockActiveOwners(@Param("role") RoleEnum role);

    long countByRoleAndActiveTrue(RoleEnum role);
}
