package com.myhockeystats.service.integration;

import com.myhockeystats.model.integration.ImportDomainEntities.LinkState;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class MatchLinkLifecycleService {

    public LinkTransition confirmSelection() {
        return new LinkTransition(LinkState.CONFIRMED, Instant.now(), null);
    }

    public LinkTransition requireReverify(String reason) {
        return new LinkTransition(LinkState.REVERIFY_REQUIRED, Instant.now(), reason);
    }

    public LinkTransition unlink(String reason) {
        return new LinkTransition(LinkState.UNLINKED, Instant.now(), reason);
    }

    public boolean canAutoReuse(LinkState state, boolean identitySignalsConsistent) {
        return state == LinkState.CONFIRMED && identitySignalsConsistent;
    }

    public record LinkTransition(LinkState nextState, Instant transitionedAt, String reason) {
    }
}
