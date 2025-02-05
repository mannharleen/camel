/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl.console;

import java.util.Map;

import org.apache.camel.api.management.ManagedCamelContext;
import org.apache.camel.api.management.mbean.ManagedCamelContextMBean;
import org.apache.camel.spi.annotations.DevConsole;

@DevConsole("context")
public class ContextDevConsole extends AbstractDevConsole {

    public ContextDevConsole() {
        super("camel", "context", "CamelContext", "Overall information about the CamelContext");
    }

    @Override
    protected Object doCall(MediaType mediaType, Map<String, Object> options) {
        // only text is supported
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("Apache Camel %s (%s) uptime %s", getCamelContext().getVersion(), getCamelContext().getName(),
                getCamelContext().getUptime()));
        sb.append("\n");

        // TODO: number of messages should maybe be in another console
        ManagedCamelContext mcc = getCamelContext().getExtension(ManagedCamelContext.class);
        if (mcc != null) {
            ManagedCamelContextMBean mb = mcc.getManagedCamelContext();
            sb.append(String.format("\n    Total: %s", mb.getExchangesTotal()));
            sb.append(String.format("\n    Failed: %s", mb.getExchangesFailed()));
            sb.append(String.format("\n    Inflight: %s", mb.getExchangesInflight()));
            sb.append("\n");
        }

        return sb.toString();
    }
}
