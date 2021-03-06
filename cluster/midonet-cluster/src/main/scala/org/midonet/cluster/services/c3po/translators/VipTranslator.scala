/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.services.c3po.translators

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronVIP
import org.midonet.cluster.models.Topology.{Pool, VIP}
import org.midonet.cluster.services.c3po.midonet.Create
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for VIP. */
class VipTranslator(protected val storage: ReadOnlyStorage)
        extends NeutronTranslator[NeutronVIP]{

    override protected def translateCreate(nVip: NeutronVIP)
    : MidoOpList = {
        val mVipBldr = VIP.newBuilder
                      .setId(nVip.getId)
                      .setAdminStateUp(nVip.getAdminStateUp)
        if (nVip.hasAddress) mVipBldr.setAddress(nVip.getAddress)
        if (nVip.hasProtocolPort) mVipBldr.setProtocolPort(nVip.getProtocolPort)
        if (nVip.hasSessionPersistence &&
            nVip.getSessionPersistence.getType ==
                NeutronVIP.SessionPersistence.Type.SOURCE_IP) {
            mVipBldr.setSessionPersistence(VIP.SessionPersistence.SOURCE_IP)
        }
        if (nVip.hasPoolId) {
            val pool = storage.get(classOf[Pool], nVip.getPoolId).await()
            mVipBldr.setPoolId(pool.getId)
            mVipBldr.setLoadBalancerId(pool.getLoadBalancerId)
        }
        List(Create(mVipBldr.build()))
    }

    override protected def translateDelete(id: UUID)
    : MidoOpList = throw new NotImplementedError()

    override protected def translateUpdate(nm: NeutronVIP)
    : MidoOpList = throw new NotImplementedError()
}