/**
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
package org.apache.camel.component.mail;

import java.util.LinkedList;
import java.util.Queue;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.FolderNotFoundException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Store;
import javax.mail.search.FlagTerm;

import org.apache.camel.BatchConsumer;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ShutdownRunningTask;
import org.apache.camel.impl.ScheduledPollConsumer;
import org.apache.camel.spi.ShutdownAware;
import org.apache.camel.util.CastUtils;
import org.apache.camel.util.ObjectHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * A {@link org.apache.camel.Consumer Consumer} which consumes messages from JavaMail using a
 * {@link javax.mail.Transport Transport} and dispatches them to the {@link Processor}
 *
 * @version $Revision$
 */
public class MailConsumer extends ScheduledPollConsumer implements BatchConsumer, ShutdownAware {
    public static final long DEFAULT_CONSUMER_DELAY = 60 * 1000L;
    private static final transient Log LOG = LogFactory.getLog(MailConsumer.class);

    private final MailEndpoint endpoint;
    private final JavaMailSenderImpl sender;
    private Folder folder;
    private Store store;
    private int maxMessagesPerPoll;
    private volatile ShutdownRunningTask shutdownRunningTask;
    private volatile int pendingExchanges;

    public MailConsumer(MailEndpoint endpoint, Processor processor, JavaMailSenderImpl sender) {
        super(endpoint, processor);
        this.endpoint = endpoint;
        this.sender = sender;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception {
        if (folder != null && folder.isOpen()) {
            folder.close(true);
        }
        if (store != null && store.isConnected()) {
            store.close();
        }

        super.doStop();
    }

    protected void poll() throws Exception {
        // must reset for each poll
        shutdownRunningTask = null;
        pendingExchanges = 0;

        ensureIsConnected();

        if (store == null || folder == null) {
            throw new IllegalStateException("MailConsumer did not connect properly to the MailStore: "
                    + endpoint.getConfiguration().getMailStoreLogInformation());
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Polling mailfolder: " + endpoint.getConfiguration().getMailStoreLogInformation());
        }

        if (endpoint.getConfiguration().getFetchSize() == 0) {
            LOG.warn("Fetch size is 0 meaning the configuration is set to poll no new messages at all. Camel will skip this poll.");
            return;
        }

        // ensure folder is open
        if (!folder.isOpen()) {
            folder.open(Folder.READ_WRITE);
        }

        try {
            int count = folder.getMessageCount();
            if (count > 0) {
                Message[] messages;

                // should we process all messages or only unseen messages
                if (endpoint.getConfiguration().isUnseen()) {
                    messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                } else {
                    messages = folder.getMessages();
                }

                processBatch(CastUtils.cast(createExchanges(messages)));
            } else if (count == -1) {
                throw new MessagingException("Folder: " + folder.getFullName() + " is closed");
            }
        } catch (Exception e) {
            handleException(e);
        } finally {
            // need to ensure we release resources
            try {
                if (folder.isOpen()) {
                    folder.close(true);
                }
            } catch (Exception e) {
                // some mail servers will lock the folder so we ignore in this case (CAMEL-1263)
                LOG.debug("Could not close mailbox folder: " + folder.getName(), e);
            }
        }
    }

    public void setMaxMessagesPerPoll(int maxMessagesPerPoll) {
        this.maxMessagesPerPoll = maxMessagesPerPoll;
    }

    public void processBatch(Queue<Object> exchanges) throws Exception {
        int total = exchanges.size();

        // limit if needed
        if (maxMessagesPerPoll > 0 && total > maxMessagesPerPoll) {
            LOG.debug("Limiting to maximum messages to poll " + maxMessagesPerPoll + " as there was " + total + " messages in this poll.");
            total = maxMessagesPerPoll;
        }

        for (int index = 0; index < total && isBatchAllowed(); index++) {
            // only loop if we are started (allowed to run)
            Exchange exchange = ObjectHelper.cast(Exchange.class, exchanges.poll());
            // add current index and total as properties
            exchange.setProperty(Exchange.BATCH_INDEX, index);
            exchange.setProperty(Exchange.BATCH_SIZE, total);
            exchange.setProperty(Exchange.BATCH_COMPLETE, index == total - 1);

            // update pending number of exchanges
            pendingExchanges = total - index - 1;

            // process the current exchange
            processExchange(exchange);
            if (!exchange.isFailed()) {
                processCommit(exchange);
            } else {
                processRollback(exchange);
            }
        }
    }

    public boolean deferShutdown(ShutdownRunningTask shutdownRunningTask) {
        // store a reference what to do in case when shutting down and we have pending messages
        this.shutdownRunningTask = shutdownRunningTask;
        // do not defer shutdown
        return false;
    }

    public int getPendingExchangesSize() {
        // only return the real pending size in case we are configured to complete all tasks
        if (ShutdownRunningTask.CompleteAllTasks == shutdownRunningTask) {
            return pendingExchanges;
        } else {
            return 0;
        }
    }

    public boolean isBatchAllowed() {
        // stop if we are not running
        boolean answer = isRunAllowed();
        if (!answer) {
            return false;
        }

        if (shutdownRunningTask == null) {
            // we are not shutting down so continue to run
            return true;
        }

        // we are shutting down so only continue if we are configured to complete all tasks
        return ShutdownRunningTask.CompleteAllTasks == shutdownRunningTask;
    }

    protected Queue<Exchange> createExchanges(Message[] messages) throws MessagingException {
        Queue<Exchange> answer = new LinkedList<Exchange>();

        int fetchSize = endpoint.getConfiguration().getFetchSize();
        int count = fetchSize == -1 ? messages.length : Math.min(fetchSize, messages.length);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Fetching " + count + " messages. Total " + messages.length + " messages.");
        }

        for (int i = 0; i < count; i++) {
            Message message = messages[i];
            if (!message.getFlags().contains(Flags.Flag.DELETED)) {
                Exchange exchange = endpoint.createExchange(message);
                answer.add(exchange);
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Skipping message as it was flagged as deleted: " + MailUtils.dumpMessage(message));
                }
            }
        }

        return answer;
    }

    /**
     * Strategy to process the mail message.
     */
    protected void processExchange(Exchange exchange) throws Exception {
        if (LOG.isDebugEnabled()) {
            MailMessage msg = (MailMessage) exchange.getIn();
            LOG.debug("Processing message: " + MailUtils.dumpMessage(msg.getMessage()));
        }
        getProcessor().process(exchange);
    }

    /**
     * Strategy to flag the message after being processed.
     */
    protected void processCommit(Exchange exchange) throws MessagingException {
        MailMessage msg = (MailMessage) exchange.getIn();
        // Use the "original" Message, in case a copy ended up being made
        Message message = msg.getOriginalMessage();

        if (endpoint.getConfiguration().isDelete()) {
            LOG.debug("Exchange processed, so flagging message as DELETED");
            message.setFlag(Flags.Flag.DELETED, true);
        } else {
            LOG.debug("Exchange processed, so flagging message as SEEN");
            message.setFlag(Flags.Flag.SEEN, true);
        }
    }

    /**
     * Strategy when processing the exchange failed.
     */
    protected void processRollback(Exchange exchange) throws MessagingException {
        LOG.warn("Exchange failed, so rolling back message status: " + exchange);
    }

    private void ensureIsConnected() throws MessagingException {
        MailConfiguration config = endpoint.getConfiguration();

        boolean connected = false;
        try {
            if (store != null && store.isConnected()) {
                connected = true;
            }
        } catch (Exception e) {
            LOG.debug("Exception while testing for is connected to MailStore: "
                    + endpoint.getConfiguration().getMailStoreLogInformation()
                    + ". Caused by: " + e.getMessage(), e);
        }

        if (!connected) {
            // ensure resources get recreated on reconnection
            store = null;
            folder = null;

            if (LOG.isDebugEnabled()) {
                LOG.debug("Connecting to MailStore: " + endpoint.getConfiguration().getMailStoreLogInformation());
            }
            store = sender.getSession().getStore(config.getProtocol());
            store.connect(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
        }

        if (folder == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Getting folder " + config.getFolderName());
            }
            folder = store.getFolder(config.getFolderName());
            if (folder == null || !folder.exists()) {
                throw new FolderNotFoundException(folder, "Folder not found or invalid: " + config.getFolderName());
            }
        }
    }

}
