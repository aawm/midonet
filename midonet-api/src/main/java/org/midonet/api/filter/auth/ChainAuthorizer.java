/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter.auth;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.Authorizer;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Chain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for Chain
 */
public class ChainAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(ChainAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public ChainAuthorizer(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException {
        log.debug("authorize entered: id=" + id + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        Chain chain = dataClient.chainsGet(id);
        if (chain == null) {
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        } else {
            return isOwner(context, chain.getProperty(
                    Chain.Property.tenant_id));
        }
    }
}