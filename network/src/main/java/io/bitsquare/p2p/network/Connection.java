package io.bitsquare.p2p.network;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import io.bitsquare.app.Log;
import io.bitsquare.app.Version;
import io.bitsquare.common.ByteArrayUtils;
import io.bitsquare.common.UserThread;
import io.bitsquare.crypto.PrefixedSealedAndSignedMessage;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.NodeAddress;
import io.bitsquare.p2p.Utils;
import io.bitsquare.p2p.network.messages.CloseConnectionMessage;
import io.bitsquare.p2p.network.messages.SendersNodeAddressMessage;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Connection is created by the server thread or by sendMessage from NetworkNode.
 * All handlers are called on User thread.
 */
public class Connection implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(Connection.class);
    private static final int MAX_MSG_SIZE = 100 * 1024;         // 100 kb of compressed data
    private static final int MSG_THROTTLE_PER_SEC = 10;              // With MAX_MSG_SIZE of 100kb results in bandwidth of 10 mbit/sec 
    private static final int MSG_THROTTLE_PER_10SEC = 50;           // With MAX_MSG_SIZE of 100kb results in bandwidth of 5 mbit/sec for 10 sec 
    //timeout on blocking Socket operations like ServerSocket.accept() or SocketInputStream.read()
    private static final int SOCKET_TIMEOUT = (int) TimeUnit.MINUTES.toMillis(30);

    public static int getMaxMsgSize() {
        return MAX_MSG_SIZE;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Enums
    ///////////////////////////////////////////////////////////////////////////////////////////

    public enum PeerType {
        SEED_NODE,
        PEER,
        DIRECT_MSG_PEER
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final Socket socket;
    private final MessageListener messageListener;
    private final ConnectionListener connectionListener;
    private final String portInfo;
    private final String uid = UUID.randomUUID().toString();
    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
    // holder of state shared between InputHandler and Connection
    private final SharedModel sharedModel;

    // set in init
    private InputHandler inputHandler;
    private ObjectOutputStream objectOutputStream;

    // mutable data, set from other threads but not changed internally.
    private Optional<NodeAddress> peersNodeAddressOptional = Optional.empty();
    private volatile boolean stopped;

    //TODO got java.util.zip.DataFormatException: invalid distance too far back
    // java.util.zip.DataFormatException: invalid literal/lengths set
    // use GZIPInputStream but problems with blocking
    private final boolean useCompression = false;
    private PeerType peerType;
    private final ObjectProperty<NodeAddress> nodeAddressProperty = new SimpleObjectProperty<>();
    private List<Long> messageTimeStamps = new ArrayList<>();

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    Connection(Socket socket, MessageListener messageListener, ConnectionListener connectionListener,
               @Nullable NodeAddress peersNodeAddress) {
        this.socket = socket;
        this.messageListener = messageListener;
        this.connectionListener = connectionListener;

        sharedModel = new SharedModel(this, socket);

        if (socket.getLocalPort() == 0)
            portInfo = "port=" + socket.getPort();
        else
            portInfo = "localPort=" + socket.getLocalPort() + "/port=" + socket.getPort();

        init(peersNodeAddress);
    }

    private void init(@Nullable NodeAddress peersNodeAddress) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT);
            // Need to access first the ObjectOutputStream otherwise the ObjectInputStream would block
            // See: https://stackoverflow.com/questions/5658089/java-creating-a-new-objectinputstream-blocks/5658109#5658109
            // When you construct an ObjectInputStream, in the constructor the class attempts to read a header that 
            // the associated ObjectOutputStream on the other end of the connection has written.
            // It will not return until that header has been read. 
            objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream objectInputStream = new ObjectInputStream(socket.getInputStream());


            // We create a thread for handling inputStream data
            inputHandler = new InputHandler(sharedModel, objectInputStream, portInfo, this, useCompression);
            singleThreadExecutor.submit(inputHandler);
        } catch (IOException e) {
            sharedModel.handleConnectionException(e);
        }

        sharedModel.updateLastActivityDate();

        // Use Peer as default, in case of other types they will set it as soon as possible.
        peerType = PeerType.PEER;

        if (peersNodeAddress != null)
            setPeersNodeAddress(peersNodeAddress);

        log.trace("New connection created: " + this.toString());

        UserThread.execute(() -> connectionListener.onConnection(this));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////


    // Called form various threads
    public void sendMessage(Message message) {
        if (!stopped) {
            try {
                String peersNodeAddress = peersNodeAddressOptional.isPresent() ? peersNodeAddressOptional.get().toString() : "null";
                if (message instanceof PrefixedSealedAndSignedMessage && peersNodeAddressOptional.isPresent()) {
                    setPeerType(Connection.PeerType.DIRECT_MSG_PEER);

                    log.info("\n\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n" +
                                    "Sending direct message to peer" +
                                    "Write object to outputStream to peer: {} (uid={})\ntruncated message={}" +
                                    "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n",
                            peersNodeAddress, uid, StringUtils.abbreviate(message.toString(), 100));
                } else {
                    log.info("\n\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n" +
                                    "Write object to outputStream to peer: {} (uid={})\ntruncated message={}" +
                                    "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n",
                            peersNodeAddress, uid, StringUtils.abbreviate(message.toString(), 100));
                }

                Object objectToWrite;
                //noinspection ConstantConditions
                if (useCompression) {
                    byte[] messageAsBytes = ByteArrayUtils.objectToByteArray(message);
                    // log.trace("Write object uncompressed data size: " + messageAsBytes.length);
                    @SuppressWarnings("UnnecessaryLocalVariable") byte[] compressed = Utils.compress(message);
                    //log.trace("Write object compressed data size: " + compressed.length);
                    objectToWrite = compressed;
                } else {
                    // log.trace("Write object data size: " + ByteArrayUtils.objectToByteArray(message).length);
                    objectToWrite = message;
                }
                if (!stopped) {
                    synchronized (objectOutputStream) {
                        objectOutputStream.writeObject(objectToWrite);
                        objectOutputStream.flush();
                    }
                    sharedModel.updateLastActivityDate();
                }
            } catch (IOException e) {
                // an exception lead to a shutdown
                sharedModel.handleConnectionException(e);
            }
        } else {
            log.debug("called sendMessage but was already stopped");
        }
    }

    @SuppressWarnings("unused")
    public void reportIllegalRequest(CorruptRequest corruptRequest) {
        sharedModel.reportInvalidRequest(corruptRequest);
    }

    public boolean violatesThrottleLimit() {
        long now = System.currentTimeMillis();
        boolean violated = false;
        if (messageTimeStamps.size() >= MSG_THROTTLE_PER_SEC) {
            // check if we got more than 10 (MSG_THROTTLE_PER_SEC) msg per sec.
            long compareValue = messageTimeStamps.get(messageTimeStamps.size() - MSG_THROTTLE_PER_SEC);
            // if duration < 1 sec we received too much messages
            violated = now - compareValue < TimeUnit.SECONDS.toMillis(1);
        }

        if (messageTimeStamps.size() >= MSG_THROTTLE_PER_10SEC) {
            if (!violated) {
                // check if we got more than 50 msg per 10 sec.
                long compareValue = messageTimeStamps.get(messageTimeStamps.size() - MSG_THROTTLE_PER_10SEC);
                // if duration < 10 sec we received too much messages
                violated = now - compareValue < TimeUnit.SECONDS.toMillis(10);
            }
            // we limit to max 50 (MSG_THROTTLE_PER_10SEC) entries
            messageTimeStamps.remove(0);
        }

        messageTimeStamps.add(now);
        return violated;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Only get non - CloseConnectionMessage messages
    @Override
    public void onMessage(Message message, Connection connection) {
        // connection is null as we get called from InputHandler, which does not hold a reference to Connection
        UserThread.execute(() -> messageListener.onMessage(message, this));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setPeerType(PeerType peerType) {
        Log.traceCall(peerType.toString());
        this.peerType = peerType;
    }

    private synchronized void setPeersNodeAddress(NodeAddress peerNodeAddress) {
        checkNotNull(peerNodeAddress, "peerAddress must not be null");
        peersNodeAddressOptional = Optional.of(peerNodeAddress);

        String peersNodeAddress = getPeersNodeAddressOptional().isPresent() ? getPeersNodeAddressOptional().get().getFullAddress() : "";
        if (this instanceof InboundConnection) {
            log.info("\n\n############################################################\n" +
                    "We got the peers node address set.\n" +
                    "peersNodeAddress= " + peersNodeAddress +
                    "\nconnection.uid=" + getUid() +
                    "\n############################################################\n");
        }

        nodeAddressProperty.set(peerNodeAddress);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public synchronized Optional<NodeAddress> getPeersNodeAddressOptional() {
        return peersNodeAddressOptional;
    }

    public Date getLastActivityDate() {
        return sharedModel.getLastActivityDate();
    }

    public String getUid() {
        return uid;
    }

    public boolean hasPeersNodeAddress() {
        return peersNodeAddressOptional.isPresent();
    }

    public boolean isStopped() {
        return stopped;
    }

    public PeerType getPeerType() {
        return peerType;
    }

    public ReadOnlyObjectProperty<NodeAddress> getNodeAddressProperty() {
        return nodeAddressProperty;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ShutDown
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void shutDown(Runnable completeHandler) {
        shutDown(true, completeHandler);
    }

    public void shutDown() {
        shutDown(true, null);
    }

    public void shutDown(boolean sendCloseConnectionMessage) {
        shutDown(sendCloseConnectionMessage, null);
    }

    private void shutDown(boolean sendCloseConnectionMessage, @Nullable Runnable shutDownCompleteHandler) {
        Log.traceCall(this.toString());
        if (!stopped) {
            String peersNodeAddress = peersNodeAddressOptional.isPresent() ? peersNodeAddressOptional.get().toString() : "null";
            log.info("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                    "ShutDown connection:"
                    + "\npeersNodeAddress=" + peersNodeAddress
                    + "\nuid=" + uid
                    + "\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n");

            if (sendCloseConnectionMessage) {
                new Thread(() -> {
                    Thread.currentThread().setName("Connection:SendCloseConnectionMessage-" + this.uid);
                    Log.traceCall("sendCloseConnectionMessage");
                    try {
                        sendMessage(new CloseConnectionMessage());
                        setStopFlags();

                        Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
                    } catch (Throwable t) {
                        log.error(t.getMessage());
                        t.printStackTrace();
                    } finally {
                        UserThread.execute(() -> doShutDown(shutDownCompleteHandler));
                    }
                }).start();
            } else {
                setStopFlags();
                doShutDown(shutDownCompleteHandler);
            }
        }
    }

    private void setStopFlags() {
        stopped = true;
        sharedModel.stop();
        if (inputHandler != null)
            inputHandler.stop();
    }

    private void doShutDown(@Nullable Runnable shutDownCompleteHandler) {
        ConnectionListener.Reason shutDownReason = sharedModel.getShutDownReason();
        if (shutDownReason == null)
            shutDownReason = ConnectionListener.Reason.SHUT_DOWN;
        final ConnectionListener.Reason finalShutDownReason = shutDownReason;
        // keep UserThread.execute as its not clear if that is called from a non-UserThread
        UserThread.execute(() -> connectionListener.onDisconnect(finalShutDownReason, this));

        try {
            sharedModel.getSocket().close();
        } catch (SocketException e) {
            log.trace("SocketException at shutdown might be expected " + e.getMessage());
        } catch (IOException e) {
            log.error("Exception at shutdown. " + e.getMessage());
            e.printStackTrace();
        } finally {
            MoreExecutors.shutdownAndAwaitTermination(singleThreadExecutor, 500, TimeUnit.MILLISECONDS);

            log.debug("Connection shutdown complete " + this.toString());
            // Use UserThread.execute as its not clear if that is called from a non-UserThread
            if (shutDownCompleteHandler != null)
                UserThread.execute(shutDownCompleteHandler);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Connection)) return false;

        Connection that = (Connection) o;

        if (portInfo != null ? !portInfo.equals(that.portInfo) : that.portInfo != null) return false;
        //noinspection SimplifiableIfStatement
        if (uid != null ? !uid.equals(that.uid) : that.uid != null) return false;
        return peersNodeAddressOptional != null ? peersNodeAddressOptional.equals(that.peersNodeAddressOptional) : that.peersNodeAddressOptional == null;

    }

    @Override
    public int hashCode() {
        int result = portInfo != null ? portInfo.hashCode() : 0;
        result = 31 * result + (uid != null ? uid.hashCode() : 0);
        result = 31 * result + (peersNodeAddressOptional != null ? peersNodeAddressOptional.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Connection{" +
                "peerAddress=" + peersNodeAddressOptional +
                ", peerType=" + peerType +
                ", uid='" + uid + '\'' +
                ", lastActivityDate=" + getLastActivityDate() +
                '}';
    }

    @SuppressWarnings("unused")
    public String printDetails() {
        return "Connection{" +
                "peerAddress=" + peersNodeAddressOptional +
                ", peerType=" + peerType +
                ", portInfo=" + portInfo +
                ", uid='" + uid + '\'' +
                ", sharedSpace=" + sharedModel.toString() +
                ", stopped=" + stopped +
                ", useCompression=" + useCompression +
                '}';
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // SharedSpace
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Holds all shared data between Connection and InputHandler
     * Runs in same thread as Connection
     */
    private static class SharedModel {
        private static final Logger log = LoggerFactory.getLogger(SharedModel.class);

        private final Connection connection;
        private final Socket socket;
        private final ConcurrentHashMap<CorruptRequest, Integer> corruptRequests = new ConcurrentHashMap<>();

        // mutable
        private Date lastActivityDate;
        private volatile boolean stopped;
        private ConnectionListener.Reason shutDownReason;

        public SharedModel(Connection connection, Socket socket) {
            this.connection = connection;
            this.socket = socket;
        }

        public synchronized void updateLastActivityDate() {
            lastActivityDate = new Date();
        }

        public synchronized Date getLastActivityDate() {
            return lastActivityDate;
        }

        public void reportInvalidRequest(CorruptRequest corruptRequest) {
            log.warn("We got reported an corrupt request " + corruptRequest + "\n\tconnection=" + this);
            int numCorruptRequests;
            if (corruptRequests.contains(corruptRequest))
                numCorruptRequests = corruptRequests.get(corruptRequest);
            else
                numCorruptRequests = 0;

            numCorruptRequests++;
            corruptRequests.put(corruptRequest, numCorruptRequests);

            if (numCorruptRequests >= corruptRequest.maxTolerance) {
                log.warn("We close connection as we received too many corrupt requests.\n" +
                        "numCorruptRequests={}\n\t" +
                        "corruptRequest={}\n\t" +
                        "corruptRequests={}\n\t" +
                        "connection={}", numCorruptRequests, corruptRequest, corruptRequests.toString(), connection);
                shutDown();
            } else {
                corruptRequests.put(corruptRequest, ++numCorruptRequests);
            }
        }

        public void handleConnectionException(Throwable e) {
            Log.traceCall(e.toString());
            if (e instanceof SocketException) {
                if (socket.isClosed())
                    shutDownReason = ConnectionListener.Reason.SOCKET_CLOSED;
                else
                    shutDownReason = ConnectionListener.Reason.RESET;
            } else if (e instanceof SocketTimeoutException || e instanceof TimeoutException) {
                shutDownReason = ConnectionListener.Reason.TIMEOUT;
                log.debug("TimeoutException at socket " + socket.toString() + " on connection={}" + this);
            } else if (e instanceof EOFException) {
                shutDownReason = ConnectionListener.Reason.PEER_DISCONNECTED;
            } else if (e instanceof NoClassDefFoundError || e instanceof ClassNotFoundException) {
                shutDownReason = ConnectionListener.Reason.INCOMPATIBLE_DATA;
            } else {
                shutDownReason = ConnectionListener.Reason.UNKNOWN;
                log.warn("Unknown reason for exception at socket {} on connection={}\n\tException=",
                        socket.toString(), this, e.getMessage());
                e.printStackTrace();
            }

            shutDown();
        }

        public void shutDown() {
            if (!stopped) {
                stopped = true;
                connection.shutDown(false);
            }
        }

        public synchronized Socket getSocket() {
            return socket;
        }

        public void stop() {
            this.stopped = true;
        }

        public synchronized ConnectionListener.Reason getShutDownReason() {
            return shutDownReason;
        }

        @Override
        public String toString() {
            return "SharedSpace{" +
                    ", socket=" + socket +
                    ", illegalRequests=" + corruptRequests +
                    ", lastActivityDate=" + lastActivityDate +
                    '}';
        }
    }


///////////////////////////////////////////////////////////////////////////////////////////
// InputHandler
///////////////////////////////////////////////////////////////////////////////////////////

    // Runs in same thread as Connection
    private static class InputHandler implements Runnable {
        private static final Logger log = LoggerFactory.getLogger(InputHandler.class);

        private final SharedModel sharedModel;
        private final ObjectInputStream objectInputStream;
        private final String portInfo;
        private final MessageListener messageListener;
        private final boolean useCompression;

        private volatile boolean stopped;

        public InputHandler(SharedModel sharedModel, ObjectInputStream objectInputStream, String portInfo, MessageListener messageListener, boolean useCompression) {
            this.useCompression = useCompression;
            this.sharedModel = sharedModel;
            this.objectInputStream = objectInputStream;
            this.portInfo = portInfo;
            this.messageListener = messageListener;
        }

        public void stop() {
            stopped = true;
            try {
                objectInputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            try {
                Thread.currentThread().setName("InputHandler-" + portInfo);
                while (!stopped && !Thread.currentThread().isInterrupted()) {
                    try {
                        log.trace("InputHandler waiting for incoming messages.\n\tConnection=" + sharedModel.connection);
                        Object rawInputObject = objectInputStream.readObject();

                        log.info("\n\n<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<\n" +
                                        "New data arrived at inputHandler of connection {}.\n" +
                                        "Received object (truncated)={}"
                                        + "\n<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<\n",
                                sharedModel.connection,
                                StringUtils.abbreviate(rawInputObject.toString(), 100));

                        int size = ByteArrayUtils.objectToByteArray(rawInputObject).length;
                        if (size > getMaxMsgSize()) {
                            sharedModel.reportInvalidRequest(CorruptRequest.MaxSizeExceeded);
                            return;
                        }

                        Serializable serializable = null;
                        if (useCompression) {
                            if (rawInputObject instanceof byte[]) {
                                byte[] compressedObjectAsBytes = (byte[]) rawInputObject;
                                size = compressedObjectAsBytes.length;
                                //log.trace("Read object compressed data size: " + size);
                                serializable = Utils.decompress(compressedObjectAsBytes);
                            } else {
                                sharedModel.reportInvalidRequest(CorruptRequest.InvalidDataType);
                            }
                        } else {
                            if (rawInputObject instanceof Serializable) {
                                serializable = (Serializable) rawInputObject;
                            } else {
                                sharedModel.reportInvalidRequest(CorruptRequest.InvalidDataType);
                            }
                        }
                        //log.trace("Read object decompressed data size: " + ByteArrayUtils.objectToByteArray(serializable).length);

                        // compressed size might be bigger theoretically so we check again after decompression
                        if (size > getMaxMsgSize()) {
                            sharedModel.reportInvalidRequest(CorruptRequest.MaxSizeExceeded);
                            return;
                        }

                        if (sharedModel.connection.violatesThrottleLimit()) {
                            sharedModel.reportInvalidRequest(CorruptRequest.ViolatedThrottleLimit);
                            return;
                        }

                        if (!(serializable instanceof Message)) {
                            sharedModel.reportInvalidRequest(CorruptRequest.InvalidDataType);
                            return;
                        }

                        Message message = (Message) serializable;
                        if (message.networkId() != Version.getNetworkId()) {
                            sharedModel.reportInvalidRequest(CorruptRequest.WrongNetworkId);
                            return;
                        }

                        Connection connection = sharedModel.connection;
                        sharedModel.updateLastActivityDate();
                        if (message instanceof CloseConnectionMessage) {
                            log.info("CloseConnectionMessage received on connection {}", connection);
                            stopped = true;
                            sharedModel.shutDown();
                        } else if (!stopped) {
                            // First a seed node gets a message form a peer (PreliminaryDataRequest using 
                            // AnonymousMessage interface) which does not has its hidden service 
                            // published, so does not know its address. As the IncomingConnection does not has the 
                            // peersNodeAddress set that connection cannot be used for outgoing messages until we 
                            // get the address set.
                            // At the data update message (DataRequest using SendersNodeAddressMessage interface) 
                            // after the HS is published we get the peers address set.

                            // There are only those messages used for new connections to a peer:
                            // 1. PreliminaryDataRequest
                            // 2. DataRequest (implements SendersNodeAddressMessage)
                            // 3. GetPeersRequest (implements SendersNodeAddressMessage)
                            // 4. DirectMessage (implements SendersNodeAddressMessage)
                            if (message instanceof SendersNodeAddressMessage) {
                                NodeAddress senderNodeAddress = ((SendersNodeAddressMessage) message).getSenderNodeAddress();
                                Optional<NodeAddress> peersNodeAddressOptional = connection.getPeersNodeAddressOptional();
                                if (peersNodeAddressOptional.isPresent())
                                    checkArgument(peersNodeAddressOptional.get().equals(senderNodeAddress),
                                            "senderNodeAddress not matching connections peer address");
                                else
                                    connection.setPeersNodeAddress(senderNodeAddress);
                            }
                            if (message instanceof PrefixedSealedAndSignedMessage)
                                connection.setPeerType(Connection.PeerType.DIRECT_MSG_PEER);

                            messageListener.onMessage(message, connection);
                        }
                    } catch (IOException | ClassNotFoundException | NoClassDefFoundError e) {
                        stopped = true;
                        sharedModel.handleConnectionException(e);
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
                stopped = true;
                sharedModel.handleConnectionException(new Exception(t));
            }
        }

        @Override
        public String toString() {
            return "InputHandler{" +
                    "sharedSpace=" + sharedModel +
                    ", port=" + portInfo +
                    ", stopped=" + stopped +
                    '}';
        }
    }
}