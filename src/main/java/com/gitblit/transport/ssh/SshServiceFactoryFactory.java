/**
 * 
 */
package com.gitblit.transport.ssh;

import java.nio.channels.AsynchronousChannel;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.io.IoServiceFactory;
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory;

/**
 * @author dariusz.bywalec
 *
 */
public class SshServiceFactoryFactory extends Nio2ServiceFactoryFactory {

    public SshServiceFactoryFactory() {
        this(null, true);
    }

    /**
     * @param executors      The {@link ExecutorService} to use for spawning threads.
     *                       If {@code null} then an internal service is allocated - in which case it
     *                       is automatically shutdown regardless of the value of the <tt>shutdownOnExit</tt>
     *                       parameter value
     * @param shutdownOnExit If {@code true} then the {@link ExecutorService#shutdownNow()}
     *                       will be called (unless it is an internally allocated service which is always
     *                       closed)
     */
    public SshServiceFactoryFactory(ExecutorService executors, boolean shutdownOnExit) {
        super(executors, shutdownOnExit);
        // Make sure NIO2 is available
        Objects.requireNonNull(AsynchronousChannel.class, "Missing NIO2-Gitblit class");
    }

    @Override
    public IoServiceFactory create(FactoryManager manager) {
        return new SshServiceFactory(manager, getExecutorService(), isShutdownOnExit());
    }
}
