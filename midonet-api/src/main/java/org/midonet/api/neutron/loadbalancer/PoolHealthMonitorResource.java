/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.neutron.loadbalancer;

import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.data.neutron.LoadBalancerApi;

public class PoolHealthMonitorResource extends AbstractResource {

    private final LoadBalancerApi api;

    @Inject
    public PoolHealthMonitorResource(RestApiConfig config, UriInfo uriInfo,
                                     SecurityContext context,
                                     LoadBalancerApi api) {
        super(config, uriInfo, context, null, null);
        this.api = api;
    }
}
