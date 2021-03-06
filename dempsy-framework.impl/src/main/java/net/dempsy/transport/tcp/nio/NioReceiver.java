package net.dempsy.transport.tcp.nio;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dempsy.DempsyException;
import net.dempsy.Infrastructure;
import net.dempsy.serialization.Serializer;
import net.dempsy.threading.ThreadingModel;
import net.dempsy.transport.DisruptableRecevier;
import net.dempsy.transport.Listener;
import net.dempsy.transport.MessageTransportException;
import net.dempsy.transport.NodeAddress;
import net.dempsy.transport.RoutedMessage;
import net.dempsy.transport.tcp.AbstractTcpReceiver;
import net.dempsy.transport.tcp.TcpUtils;
import net.dempsy.transport.tcp.nio.internal.NioUtils;
import net.dempsy.transport.tcp.nio.internal.NioUtils.ReturnableBufferOutput;
import net.dempsy.util.QuietCloseable;
import net.dempsy.util.io.MessageBufferInput;

public class NioReceiver<T> extends AbstractTcpReceiver<NioAddress, NioReceiver<T>> implements DisruptableRecevier {
    private static Logger LOGGER = LoggerFactory.getLogger(NioReceiver.class);

    public static final String CONFIG_KEY_RECEIVER_NETWORK_IF_NAME = "reciever_network_if";

    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    private NioAddress internal = null;
    private NioAddress address = null;
    private Binding binding = null;
    private Acceptor acceptor = null;

    @SuppressWarnings("unchecked") private Reader<T>[] readers = new Reader[2];

    public NioReceiver(final Serializer serializer, final int port) {
        super(serializer, port);
        resolver(new NioDefaultExternalAddressResolver());
    }

    public NioReceiver(final Serializer serializer) {
        this(serializer, -1);
    }

    @Override
    public void close() {
        isRunning.set(false);
        if(acceptor != null)
            acceptor.close();

        Arrays.stream(readers).filter(r -> r != null).forEach(r -> r.close());

        if(binding != null)
            binding.close();
    }

    @Override
    public synchronized NioAddress getAddress(final Infrastructure infra) {
        if(internal == null) {
            final String ifNameToGetAddrFrom = infra.getConfigValue(NioReceiver.class, CONFIG_KEY_RECEIVER_NETWORK_IF_NAME, null);

            if(useLocalHost) {
                if(ifNameToGetAddrFrom != null)
                    LOGGER.warn("Both \"useLocalHost\" as well as the property " + CONFIG_KEY_RECEIVER_NETWORK_IF_NAME + " for "
                        + NioReceiver.class.getPackage().getName() + ". The property will be ignored.");
                if(addrSupplier != null)
                    LOGGER.warn("Both IP address supplier (" + addrSupplier.getClass().getName()
                        + ") as well as \"useLocalHost\" was set. The address supplier will be ignored.");
            } else {
                if(addrSupplier != null && ifNameToGetAddrFrom != null)
                    LOGGER.warn("Both IP Address supplier (" + addrSupplier.getClass().getName() + ") as well as the property "
                        + CONFIG_KEY_RECEIVER_NETWORK_IF_NAME + " for " + NioReceiver.class.getPackage().getName()
                        + ". The property will be ignored.");
            }
            try {
                InetAddress bindAddr = useLocalHost ? Inet4Address.getLocalHost()
                    : (addrSupplier == null ?

                    // if someone set the variable for explicitly using a particular interface, then use it.
                        (ifNameToGetAddrFrom == null ? null : TcpUtils.getFirstNonLocalhostInetAddress(ifNameToGetAddrFrom))

                        : addrSupplier.get());
                binding = new Binding(bindAddr, internalPort);
                final InetSocketAddress inetSocketAddress = binding.bound;
                internalPort = inetSocketAddress.getPort();
                if(bindAddr == null)
                    bindAddr = binding.bound.getAddress(); // this will be the wildcard address.

                internal = new NioAddress(bindAddr, internalPort, serId, binding.recvBufferSize, this.maxMessageSize);

                address = resolver.getExternalAddresses(internal);
            } catch(final IOException e) {
                throw new DempsyException(e, false);
            }
        }
        return address;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void start(final Listener<?> listener, final Infrastructure infra) throws MessageTransportException {
        if(!isRunning.get())
            throw new IllegalStateException("Cannot restart an " + NioReceiver.class.getSimpleName());

        if(binding == null)
            getAddress(infra); // sets binding via side affect.

        // before starting the acceptor, make sure we have Readers created.
        try {
            for(int i = 0; i < readers.length; i++)
                readers[i] = new Reader<T>(isRunning, address, (Listener<T>)listener, serializer, maxMessageSize);
        } catch(final IOException ioe) {
            LOGGER.error(address.toString() + " failed to start up readers", ioe);
            throw new MessageTransportException(address.toString() + " failed to start up readers", ioe);
        }

        final ThreadingModel threadingModel = infra.getThreadingModel();
        // now start the readers.
        for(int i = 0; i < readers.length; i++)
            threadingModel.runDaemon(readers[i], "nio-reader-" + i + "-" + address);

        // start the acceptor
        threadingModel.runDaemon(acceptor = new Acceptor(binding, isRunning, readers, address), "nio-acceptor-" + address);
    }

    @Override
    @SuppressWarnings("unchecked")
    public NioReceiver<T> numHandlers(final int numHandlers) {
        Arrays.stream(readers).filter(r -> r != null).forEach(r -> r.close());
        readers = new Reader[numHandlers];
        return this;
    }

    // =============================================================================
    // These methods are to support spring dependency injection which (stupidly) requires
    // adherence to a 15 year old JavaBeans spec.
    // =============================================================================
    public void setNumHandlers(final int numHandlers) {
        numHandlers(numHandlers);
    }

    public int getNumHandlers() {
        return readers == null ? 0 : readers.length;
    }

    // =============================================================================
    // These methods are to support testing
    // =============================================================================
    @Override
    public boolean disrupt(final NodeAddress nodeAddress) {
        return Arrays.stream(readers)
            .filter(r -> r.disrupt(nodeAddress))
            .findFirst().orElse(null) != null;
    }

    // =============================================================================
    // These classes manages accepting external connections.
    // =============================================================================
    private static class Binding implements QuietCloseable {
        public final Selector selector;
        public final ServerSocketChannel serverChannel;
        public final InetSocketAddress bound;
        public final int recvBufferSize;

        public Binding(final InetAddress addr, final int port) throws IOException {
            final int lport = port < 0 ? 0 : port;
            selector = Selector.open();

            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);

            final InetSocketAddress tobind = addr == null ? new InetSocketAddress(lport) : new InetSocketAddress(addr, lport);
            final ServerSocket sock = serverChannel.socket();
            sock.bind(tobind);
            bound = (InetSocketAddress)sock.getLocalSocketAddress();
            recvBufferSize = sock.getReceiveBufferSize();
        }

        @Override
        public void close() {
            NioUtils.closeQuietly(serverChannel, LOGGER, "Failed to close serverChannel.");
            NioUtils.closeQuietly(serverChannel.socket(), LOGGER, "Failed to close serverChannel.");
            NioUtils.closeQuietly(selector, LOGGER, "Failed to close selector.");
        }
    }

    private static class Acceptor implements Runnable {
        final Binding binding;
        final AtomicBoolean isRunning;
        final Reader<?>[] readers;
        final long numReaders;
        final AtomicLong messageNum = new AtomicLong(0);
        final AtomicBoolean done = new AtomicBoolean(false);
        final NioAddress thisNode;

        private Acceptor(final Binding binding, final AtomicBoolean isRunning, final Reader<?>[] readers, final NioAddress thisNode) {
            this.binding = binding;
            this.isRunning = isRunning;
            this.readers = readers;
            this.numReaders = readers.length;
            this.thisNode = thisNode;
        }

        @Override
        public void run() {
            final Selector selector = binding.selector;
            final ServerSocketChannel serverChannel = binding.serverChannel;

            try {
                while(isRunning.get()) {
                    try {
                        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

                        while(isRunning.get()) {
                            final int numSelected = selector.select();

                            if(numSelected == 0)
                                continue;

                            final Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                            while(keys.hasNext()) {
                                final SelectionKey key = keys.next();

                                keys.remove();

                                if(!key.isValid())
                                    continue;

                                if(key.isAcceptable()) {
                                    accept(key);
                                }
                            }
                        }
                    } catch(final IOException ioe) {
                        LOGGER.error("Failed during accept loop.", ioe);
                    }
                }
            } finally {
                NioUtils.closeQuietly(serverChannel, LOGGER, "Failed to close the server Channel from the Acceptor");
                done.set(true);
            }
        }

        private void accept(final SelectionKey key) throws IOException {
            final Reader<?> reader = readers[(int)(messageNum.getAndIncrement() % numReaders)];

            final ServerSocketChannel serverChannel = (ServerSocketChannel)key.channel();
            final SocketChannel channel = serverChannel.accept();

            LOGGER.trace(thisNode + " is accepting a connection from " + channel.getRemoteAddress());

            reader.newClient(channel);
        }

        // assumes isRunning is already set to false
        private void close() {
            while(!done.get()) {
                binding.selector.wakeup();
                Thread.yield();
            }
        }
    }
    // =============================================================================

    // =============================================================================
    // A Client instance is attached to each socket in the selector's register
    // =============================================================================
    private static class Client<T> {
        ReturnableBufferOutput partialRead = null;
        private final NioAddress thisNode;
        private final Listener<T> typedListener;
        private final Serializer serializer;
        private final int maxMessageSize;

        private Client(final NioAddress thisNode, final Listener<T> listener, final Serializer serializer, final int maxMessageSize) {
            this.thisNode = thisNode;
            this.typedListener = listener;
            this.serializer = serializer;
            this.maxMessageSize = maxMessageSize;
        }

        /**
         * Read the size
         *
         * @return -1 if there aren't enough bytes read in to figure out the size. -2 if the socket channel reached it's eof. Otherwise, the size actually read.
         */
        private final int readSize(final SocketChannel channel, final ByteBuffer bb) throws IOException {
            final int size;

            if(bb.position() < 2) {
                // read a Short
                bb.limit(2);
                if(channel.read(bb) == -1)
                    return -2;
            }

            if(bb.position() >= 2) { // we read the full short in
                final short ssize = bb.getShort(0); // read the short.

                if(ssize == -1) { // we need to read the int ... indication that an int size is there.
                    if(bb.position() < 6) {
                        bb.limit(6); // set the limit to read the int.
                        if(channel.read(bb) == -1) // read 4 more bytes.
                            return -2;
                    }

                    if(bb.position() >= 6) // we have an int based size
                        size = bb.getInt(2); // read an int starting after the short.
                    else
                        // we need an int based size but don't have it all yet.
                        size = -1; // we're going to need to try again.

                } else { // the ssize contains the full size.
                    size = ssize;
                }
            } else {
                // we already tried to read the short but didn't get enought bytes.
                size = -1; // try again.
            }

            return size;
        }

        private void closeup(final SocketChannel channel, final SelectionKey key) {
            final Socket socket = channel.socket();
            final SocketAddress remoteAddr = socket.getRemoteSocketAddress();
            LOGGER.debug(thisNode + " had a connection closed by client: " + remoteAddr);
            try {
                channel.close();
            } catch(final IOException ioe) {
                LOGGER.error(thisNode + " failed to close the receiver channel receiving data from " + remoteAddr + ". Ingoring", ioe);
            }
            key.cancel();
        }

        public void read(final SelectionKey key) throws IOException {
            final SocketChannel channel = (SocketChannel)key.channel();
            final ReturnableBufferOutput buf;
            if(partialRead == null) {
                buf = NioUtils.get();
                buf.getBb().limit(2); // set it to read the short for size initially
                partialRead = buf; // set the partialRead. We'll unset this when we pass it on
            } else
                buf = partialRead;
            ByteBuffer bb = buf.getBb();

            if(bb.limit() <= 6) { // we haven't read the size yet.
                final int size = readSize(channel, bb);
                if(size == -2) { // indication we hit an eof
                    closeup(channel, key);
                    return; // we're done
                }
                if(size == -1) { // we didn't read the size yet so just go back.
                    return;
                }
                // if the results are less than zero or WAY to big, we need to assume a corrupt channel.
                if(size <= 0 || size > maxMessageSize) {
                    // assume the channel is corrupted and close us out.
                    LOGGER.warn(thisNode + " received what appears to be a corrupt message because it's size is " + size + " which is greater than the max ("
                        + maxMessageSize + ")");
                    closeup(channel, key);
                    return;
                }

                final int limit = bb.limit();
                if(bb.capacity() < limit + size) {
                    // we need to grow the underlying buffer.
                    buf.grow(limit + size);
                    bb = buf.getBb();
                }

                buf.messageStart = bb.position();
                bb.limit(limit + size); // set the limit to read the entire message.
            }

            if(bb.position() < bb.limit()) {
                // continue reading
                if(channel.read(bb) == -1) {
                    closeup(channel, key);
                    return;
                }
            }

            if(bb.position() < bb.limit())
                return; // we need to wait for more data.

            // otherwise we have a message ready to go.
            final ReturnableBufferOutput toGo = partialRead;
            partialRead = null;
            typedListener.onMessage(() -> {
                try (final ReturnableBufferOutput mbo = toGo;
                    final MessageBufferInput mbi = new MessageBufferInput(mbo.getBuffer(), mbo.messageStart, mbo.getBb().position());) {
                    @SuppressWarnings("unchecked")
                    final T rm = (T)serializer.deserialize(mbi, RoutedMessage.class);
                    return rm;
                } catch(final IOException ioe) {
                    LOGGER.error(thisNode + " failed on deserialization", ioe);
                    throw new DempsyException(ioe, false);
                }
            });
        }
    }

    public static class Reader<T> implements Runnable {
        private final AtomicReference<SocketChannel> landing = new AtomicReference<SocketChannel>(null);
        private final Selector selector;
        private final AtomicBoolean isRunning;
        private final NioAddress thisNode;
        private final Listener<T> typedListener;
        private final Serializer serializer;
        private final int maxMessageSize;
        private final AtomicBoolean done = new AtomicBoolean(false);
        private final AtomicReference<CloseCommand> clientToClose = new AtomicReference<CloseCommand>(null);

        public Reader(final AtomicBoolean isRunning, final NioAddress thisNode, final Listener<T> typedListener, final Serializer serializer,
            final int maxMessageSize) throws IOException {
            selector = Selector.open();
            this.isRunning = isRunning;
            this.thisNode = thisNode;
            this.typedListener = typedListener;
            this.serializer = serializer;
            this.maxMessageSize = maxMessageSize;
        }

        @Override
        public void run() {
            try {
                while(isRunning.get()) {
                    try {
                        final int numKeysSelected = selector.select();

                        if(numKeysSelected > 0) {
                            final Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                            while(keys.hasNext()) {
                                final SelectionKey key = keys.next();

                                keys.remove();

                                if(!key.isValid())
                                    continue;

                                if(key.isReadable()) {
                                    ((Client<?>)key.attachment()).read(key);
                                } else // this shouldn't be possible
                                    LOGGER.info(thisNode + " reciever got an unexpexted selection key " + key);
                            }
                        } else if(isRunning.get() && !done.get()) {
                            // if we processed no keys then maybe we have a new client passed over to us.
                            final SocketChannel newClient = landing.getAndSet(null); // mark it as retrieved.
                            if(newClient != null) {
                                // we have a new client
                                newClient.configureBlocking(false);
                                final Socket socket = newClient.socket();
                                final SocketAddress remote = socket.getRemoteSocketAddress();
                                LOGGER.debug(thisNode + " received connection from " + remote);
                                newClient.register(selector, SelectionKey.OP_READ,
                                    new Client<T>(thisNode, typedListener, serializer, maxMessageSize));
                            } else if(clientToClose.get() != null) {
                                final NioAddress addr = clientToClose.get().addrToClose;
                                final Object[] toClose = selector.keys().stream()
                                    .map(k -> new Object[] {k,(Client<?>)k.attachment()})
                                    .filter(c -> ((Client<?>)c[1]).thisNode.equals(addr))
                                    .findFirst()
                                    .orElse(null);

                                if(toClose != null) {
                                    final SelectionKey key = (SelectionKey)toClose[0];
                                    final Client<?> client = ((Client<?>)toClose[1]);
                                    try {
                                        client.closeup((SocketChannel)key.channel(), key);
                                    } finally {
                                        clientToClose.get().set(client);
                                    }
                                } else
                                    clientToClose.set(null);
                            }
                        }
                    } catch(final IOException ioe) {
                        LOGGER.error("Failed during reader loop.", ioe);
                    }
                }
            } finally {
                if(selector != null)
                    NioUtils.closeQuietly(selector, LOGGER, "Failed to close selector on reader thread.");
                done.set(true);
            }
        }

        private static class CloseCommand {
            public final NioAddress addrToClose;
            public volatile boolean done;
            public Client<?> clientClosed;

            CloseCommand(final NioAddress addrToClose) {
                this.addrToClose = addrToClose;
                done = false;
            }

            public void set(final Client<?> clientClosed) {
                this.clientClosed = clientClosed;
                done = true;
            }
        }

        boolean disrupt(final NodeAddress addr) {
            final CloseCommand cmd = new CloseCommand((NioAddress)addr);

            // wait for the command landing pad to be clear and claim it once available
            while(!clientToClose.compareAndSet(null, cmd) && isRunning.get())
                Thread.yield();

            // now wait for the reader thread to pick up the command and respond
            if(isRunning.get()) {
                do {
                    selector.wakeup();
                    Thread.yield();
                } while(!clientToClose.get().done && isRunning.get()); // double volatile read
            }

            clientToClose.set(null); // clear the command

            return cmd.clientClosed != null;
        }

        // assumes isRunning is already set to false
        private void close() {
            while(!done.get()) {
                selector.wakeup();
                Thread.yield();
            }
        }

        public synchronized void newClient(final SocketChannel newClient) throws IOException {
            // attempt to set the landing as long as it's null
            while(landing.compareAndSet(null, newClient))
                Thread.yield();

            // wait until the Reader runnable takes it.
            while(landing.get() != null && isRunning.get() && !done.get()) {
                selector.wakeup();
                Thread.yield();
            }
        }
    }

}
