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
package org.apache.camel.component.jms.issues;

import javax.jms.ConnectionFactory;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jms.CamelJmsTestHelper;
import org.apache.camel.test.junit5.CamelTestSupport;
import org.junit.jupiter.api.Test;

import static org.apache.camel.component.jms.JmsComponent.jmsComponentAutoAcknowledge;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class JmsInOutUseMessageIDasCorrelationIDTest extends CamelTestSupport {

    @Test
    public void testInOutWithMsgIdAsCorrId() {
        String reply = template.requestBody("activemq:queue:in?useMessageIDAsCorrelationID=true", "Hello World", String.class);
        assertEquals("Bye World", reply);
    }

    @Test
    public void testInOutFixedReplyToAndWithMsgIdAsCorrId() {
        String reply = template.requestBody("activemq:queue:in?replyTo=bar&useMessageIDAsCorrelationID=true", "Hello World",
                String.class);
        assertEquals("Bye World", reply);
    }

    @Override
    protected CamelContext createCamelContext() throws Exception {
        CamelContext camelContext = super.createCamelContext();
        ConnectionFactory connectionFactory = CamelJmsTestHelper.createConnectionFactory();
        camelContext.addComponent("activemq", jmsComponentAutoAcknowledge(connectionFactory));
        return camelContext;
    }

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            public void configure() {
                from("activemq:queue:in?useMessageIDAsCorrelationID=true").process(exchange -> {
                    String id = exchange.getIn().getHeader("JMSCorrelationID", String.class);
                    assertNull(id, "JMSCorrelationID should be null");

                    exchange.getMessage().setBody("Bye World");
                });
            }
        };
    }

}
