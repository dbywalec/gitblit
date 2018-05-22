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
import java.util.Objects;
import java.util.logging.Level;

import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.io.IoHandler;
import org.apache.sshd.common.io.nio2.Nio2Acceptor;
import org.apache.sshd.common.io.nio2.Nio2CompletionHandler;
import org.apache.sshd.common.io.nio2.Nio2Session;

/**
 * @author dariusz.bywalec
 *
 */
public class SshAcceptor extends Nio2Acceptor {

	public SshAcceptor(FactoryManager manager, IoHandler handler, AsynchronousChannelGroup group) {
		super(manager, handler, group);
	}

	protected CompletionHandler<AsynchronousSocketChannel, ? super SocketAddress> createSocketCompletionHandler(
			Map<SocketAddress, AsynchronousServerSocketChannel> channelsMap, AsynchronousServerSocketChannel socket)
					throws IOException {
		return new SshAcceptCompletionHandler(socket);
	}

	@SuppressWarnings("synthetic-access")
	protected class SshAcceptCompletionHandler extends Nio2CompletionHandler<AsynchronousSocketChannel, SocketAddress> {
		protected final AsynchronousServerSocketChannel socket;

		SshAcceptCompletionHandler(AsynchronousServerSocketChannel socket) {
			this.socket = socket;
		}

		@Override
		protected void onCompleted(AsynchronousSocketChannel result, SocketAddress address) {
			// Verify that the address has not been unbound
			if (!channels.containsKey(address)) {
				if (log.isDebugEnabled()) {
					log.debug("onCompleted({}) unbound address", address);
				}
				return;
			}

			Nio2Session session = null;
			Long sessionId = null;
			boolean keepAccepting;
			try {
				// Create a session
				IoHandler handler = getIoHandler();
				setSocketOptions(result);
				session = Objects.requireNonNull(createSession(SshAcceptor.this, address, result, handler),
						"No SSH session created");
				sessionId = session.getId();
				handler.sessionCreated(session);
				sessions.put(sessionId, session);
				if (session.isClosing()) {
					try {
						handler.sessionClosed(session);
					} finally {
						unmapSession(sessionId);
					}
				} else {
					session.startReading();
				}

				keepAccepting = true;
			} catch (Throwable exc) {
				keepAccepting = okToReaccept(exc, address);

				// fail fast the accepted connection
				if (session != null) {
					try {
						session.close();
					} catch (Throwable t) {
						log.warn("onCompleted(" + address + ") Failed (" + t.getClass().getSimpleName() + ")"
								+ " to close accepted connection from " + address + ": " + t.getMessage(), t);
					}
				}

				unmapSession(sessionId);
			}

			if (keepAccepting) {
				try {
					// Accept new connections
					socket.accept(address, this);
				} catch (Throwable exc) {
					failed(exc, address);
				}
			} else {
				log.error("=====> onCompleted({}) no longer accepting incoming connections <====", address);
			}
		}

		protected Nio2Session createSession(Nio2Acceptor acceptor, SocketAddress address,
				AsynchronousSocketChannel channel, IoHandler handler) throws Throwable {
			if (log.isTraceEnabled()) {
				log.trace("createSshSession({}) address={}", acceptor, address);
			}
			return new Nio2Session(acceptor, getFactoryManager(), handler, channel);
		}

		@Override
		protected void onFailed(Throwable exc, SocketAddress address) {
			if (okToReaccept(exc, address)) {
				try {
					// Accept new connections
					socket.accept(address, this);
				} catch (Throwable t) {
					// Do not call failed(t, address) to avoid infinite
					// recursion
					log.error("Failed (" + t.getClass().getSimpleName() + " to re-accept new connections on " + address
							+ ": " + t.getMessage(), t);
				}
			}
		}

		protected boolean okToReaccept(Throwable exc, SocketAddress address) {
			AsynchronousServerSocketChannel channel = channels.get(address);
			if (channel == null) {
				if (log.isDebugEnabled()) {
					log.debug("Caught {} for untracked channel of {}: {}", exc.getClass().getSimpleName(), address,
							exc.getMessage());
				}
				return false;
			}

			if (disposing.get()) {
				if (log.isDebugEnabled()) {
					log.debug("Caught {} for tracked channel of {} while disposing: {}", exc.getClass().getSimpleName(),
							address, exc.getMessage());
				}
				return false;
			}

			log.warn("Caught {} while accepting incoming connection from {}: {}", exc.getClass().getSimpleName(),
					address, exc.getMessage());
			SshLoggingUtils.logExceptionStackTrace(log, Level.WARNING, exc);
			return true;
		}
	}
}
