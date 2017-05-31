/**
 * 
 */
package com.gitblit.transport.ssh;

import java.io.IOException;
import java.nio.channels.AsynchronousChannelGroup;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.RuntimeSshException;
import org.apache.sshd.common.util.threads.ThreadUtils;
import org.apache.sshd.common.io.AbstractIoServiceFactory;
import org.apache.sshd.common.io.IoAcceptor;
import org.apache.sshd.common.io.IoConnector;
import org.apache.sshd.common.io.IoHandler;
import org.apache.sshd.common.io.nio2.Nio2Connector;

/**
 * @author dariusz.bywalec
 *
 */
public class SshServiceFactory extends AbstractIoServiceFactory {

    private final AsynchronousChannelGroup group;

    public SshServiceFactory(FactoryManager factoryManager, ExecutorService service, boolean shutdownOnExit) {
        super(factoryManager,
                service == null ? ThreadUtils.newFixedThreadPool(factoryManager.toString() + "-gitblit", getNioWorkers(factoryManager)) : service,
                service == null || shutdownOnExit);
        try {
            group = AsynchronousChannelGroup.withThreadPool(ThreadUtils.protectExecutorServiceShutdown(getExecutorService(), isShutdownOnExit()));
        } catch (IOException e) {
            log.warn("Failed (" + e.getClass().getSimpleName() + " to start async. channel group: " + e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("Start async. channel group failure details", e);
            }
            throw new RuntimeSshException(e);
        }
    }

    @Override
    public IoConnector createConnector(IoHandler handler) {
        return new Nio2Connector(getFactoryManager(), handler, group);
    }

    @Override
    public IoAcceptor createAcceptor(IoHandler handler) {
        return new SshAcceptor(getFactoryManager(), handler, group);
    }

    @Override
    protected void doCloseImmediately() {
        try {
            if (!group.isShutdown()) {
                log.debug("Shutdown group");
                group.shutdownNow();

                // if we protect the executor then the await will fail since we didn't really shut it down...
                if (isShutdownOnExit()) {
                    if (group.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.debug("Group successfully shut down");
                    } else {
                        log.debug("Not all group tasks terminated");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Exception caught while closing channel group", e);
        } finally {
            super.doCloseImmediately();
        }
    }
}
