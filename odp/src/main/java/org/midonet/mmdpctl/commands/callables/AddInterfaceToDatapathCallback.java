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

package org.midonet.mmdpctl.commands.callables;

import org.midonet.mmdpctl.commands.results.AddInterfaceToDatapathResult;
import org.midonet.odp.Datapath;
import org.midonet.odp.DpPort;
import org.midonet.odp.protos.OvsDatapathConnection;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

public class AddInterfaceToDatapathCallback
        implements Callable<AddInterfaceToDatapathResult> {
    OvsDatapathConnection connection;
    String interfaceName;
    String datapathName;

    public AddInterfaceToDatapathCallback(OvsDatapathConnection connection,
                                          String interfaceName,
                                          String datapathName) {
        this.connection = connection;
        this.interfaceName = interfaceName;
        this.datapathName = datapathName;
    }

    @Override
    public AddInterfaceToDatapathResult call()
            throws InterruptedException, ExecutionException {
        Datapath datapath = connection.futures.datapathsGet(datapathName).get();
        DpPort createdDpPort = connection.futures.addInterface(
                datapath, interfaceName).get();
        boolean isSucceeded = (createdDpPort != null);
        return new AddInterfaceToDatapathResult(datapath, isSucceeded);
    }
}
