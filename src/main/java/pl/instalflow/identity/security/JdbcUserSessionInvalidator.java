package pl.instalflow.identity.security;

import pl.instalflow.identity.application.UserSessionInvalidator;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
class JdbcUserSessionInvalidator implements UserSessionInvalidator {
    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions;

    JdbcUserSessionInvalidator(ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions) {
        this.sessions = sessions;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidate(UUID userId) {
        FindByIndexNameSessionRepository<? extends Session> repository = sessions.getIfAvailable();
        if (repository != null) {
            repository.findByPrincipalName(userId.toString()).keySet().forEach(repository::deleteById);
        }
    }
}
