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
package org.midonet.cluster.rest_api.conversion;

import java.net.URI;

import org.midonet.cluster.data.Chain.Property;
import org.midonet.cluster.rest_api.models.Chain;

public class ChainDataConverter {

    public static Chain fromData(org.midonet.cluster.data.Chain data,
                                 URI baseUri) throws IllegalAccessException {
        Chain c = new Chain();
        c.id = data.getId();
        c.tenantId = data.getProperty(Property.tenant_id);
        c.name = data.getName();
        c.setBaseUri(baseUri);
        return c;
    }

    public static org.midonet.cluster.data.Chain toData(Chain c) {
        return new org.midonet.cluster.data.Chain()
                .setId(c.id)
                .setName(c.name)
                .setProperty(Property.tenant_id, c.tenantId);
    }

}
