/**
 * 
 */
package com.gitblit.transport.ssh;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Map;

import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.io.IoHandler;
import org.apache.sshd.common.io.nio2.Nio2Acceptor;
import org.apache.sshd.common.io.nio2.Nio2CompletionHandler;
import org.apache.sshd.common.io.nio2.Nio2Session;
import org.apache.sshd.common.util.ValidateUtils;

/**
 * @author dariusz.bywalec
 *
 */
public class SshAcceptor extends Nio2Acceptor {

    public SshAcceptor(FactoryManager manager, IoHandler handler, AsynchronousChannelGroup group) {
        super(manager, handler, group);
    }

    protected CompletionHandler<AsynchronousSocketChannel, ? super SocketAddress> createSocketCompletionHandler(
            Map<SocketAddress, AsynchronousServerSocketChannel> channelsMap, AsynchronousServerSocketChannel socket) throws IOException {
        return new SshAcceptCompletionHandler(socket);
    }
    
    protected class SshAcceptCompletionHandler extends Nio2CompletionHandler<AsynchronousSocketChannel, SocketAddress> {
        protected final AsynchronousServerSocketChannel socket;

        SshAcceptCompletionHandler(AsynchronousServerSocketChannel socket) {
            this.socket = socket;
        }

        @Override
        @SuppressWarnings("synthetic-access")
        protected void onCompleted(AsynchronousSocketChannel result, SocketAddress address) {
            // Verify that the address has not been unbound
            if (!channels.containsKey(address)) {
                return;
            }

            Nio2Session session = null;
            try {
                // Create a session
                IoHandler handler = getIoHandler();
                setSocketOptions(result);
                session = Objects.requireNonNull(createSession(SshAcceptor.this, address, result, handler), "No SSH session created");
                handler.sessionCreated(session);
                sessions.put(session.getId(), session);
                session.startReading();
            } catch (Throwable exc) {
                failed(exc, address);

                // fail fast the accepted connection
                if (session != null) {
                    try {
                        session.close();
                    } catch (Throwable t) {
                        log.warn("Failed (" + t.getClass().getSimpleName() + ")"
                               + " to close accepted connection from " + address
                               + ": " + t.getMessage(),
                                 t);
                    }
                }
            }

            try {
                // Accept new connections
                socket.accept(address, this);
            } catch (Throwable exc) {
                failed(exc, address);
            }
        }

        @SuppressWarnings("synthetic-access")
        protected Nio2Session createSession(Nio2Acceptor acceptor, SocketAddress address, AsynchronousSocketChannel channel, IoHandler handler) throws Throwable {
            if (log.isTraceEnabled()) {
                log.trace("createSshSession({}) address={}", acceptor, address);
            }
            return new Nio2Session(acceptor, getFactoryManager(), handler, channel);
        }

        @Override
        @SuppressWarnings("synthetic-access")
        protected void onFailed(final Throwable exc, final SocketAddress address) {
            if (channels.containsKey(address) && !disposing.get()) {
                log.warn("Caught " + exc.getClass().getSimpleName()
                       + " while accepting incoming connection from " + address
                       + ": " + exc.getMessage(),
                        exc);
            }
        }
    }
}
