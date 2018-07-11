package co.nyzo.verifier;

import co.nyzo.verifier.messages.*;
import co.nyzo.verifier.util.IpUtil;
import co.nyzo.verifier.util.PrintUtil;
import co.nyzo.verifier.util.SignatureUtil;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

public class Message {

    private long timestamp;  // millisecond precision -- when the message is first generated
    private MessageType type;
    private MessageObject content;
    private byte[] sourceNodeIdentifier;  // the identifier of the node that created this message
    private byte[] sourceNodeSignature;   // the signature of all preceding parts
    private boolean valid;       // not serialized
    private byte[] sourceIpAddress;   // not serialized

    // This is the constructor for a new message originating from this system.
    public Message(MessageType type, MessageObject content) {
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.content = content;
        this.sourceNodeIdentifier = Verifier.getIdentifier();
        this.sourceNodeSignature = Verifier.sign(getBytesForSigning());
        this.valid = true;
    }

    // This is the constructor for a message from another system.
    public Message(long timestamp, MessageType type, MessageObject content, byte[] sourceNodeIdentifier,
                   byte[] sourceNodeSignature, byte[] sourceIpAddress) {

        this.timestamp = timestamp;
        this.type = type;
        this.content = content;
        this.sourceNodeIdentifier = sourceNodeIdentifier;
        this.sourceNodeSignature = sourceNodeSignature;
        this.sourceIpAddress = sourceIpAddress;

        // Verify the source signature.
        this.valid = SignatureUtil.signatureIsValid(sourceNodeSignature, getBytesForSigning(),
                sourceNodeIdentifier);
        if (!this.valid) {
            System.out.println("message from " + PrintUtil.compactPrintByteArray(sourceNodeIdentifier) + " of type " +
                    this.type + " is not valid, content is " + content);
            System.out.println("signature is " + ByteUtil.arrayAsStringWithDashes(sourceNodeSignature));
        }
    }

    public long getTimestamp() {
        return timestamp;
    }

    public MessageType getType() {
        return type;
    }

    public MessageObject getContent() {
        return content;
    }

    public byte[] getSourceNodeIdentifier() {
        return sourceNodeIdentifier;
    }

    public byte[] getSourceNodeSignature() {
        return sourceNodeSignature;
    }

    public boolean isValid() {
        return valid;
    }

    public byte[] getSourceIpAddress() {
        return sourceIpAddress;
    }

    public void sign(byte[] privateSeed) {
        this.sourceNodeIdentifier = KeyUtil.identifierForSeed(privateSeed);
        this.sourceNodeSignature = SignatureUtil.signBytes(getBytesForSigning(), privateSeed);
    }

    public static void broadcast(Message message) {

        // Send the message to all nodes in the mesh.
        List<Node> mesh = NodeManager.getMesh();
        for (Node node : mesh) {
            if (node.isActive() && !ByteUtil.arraysAreEqual(node.getIdentifier(), Verifier.getIdentifier())) {
                String ipAddress = IpUtil.addressAsString(node.getIpAddress());
                fetch(ipAddress, node.getPort(), message, null);
            }
        }
    }

    public static void fetch(Message message, MessageCallback messageCallback) {

        Node node = null;
        List<Node> mesh = NodeManager.getMesh();
        Random random = new Random();
        while (node == null && !mesh.isEmpty()) {
            Node testNode = mesh.remove(random.nextInt(mesh.size()));
            if (!ByteUtil.arraysAreEqual(testNode.getIdentifier(), Verifier.getIdentifier())) {
                node = testNode;
            }
        }

        if (node != null) {
            fetch(IpUtil.addressAsString(node.getIpAddress()), node.getPort(), message, messageCallback);
        }
    }

    public static void fetch(String hostNameOrIp, int port, Message message,
                             MessageCallback messageCallback) {

        byte[] identifier = NodeManager.identifierForIpAddress(hostNameOrIp);
        if (!ByteUtil.arraysAreEqual(identifier, Verifier.getIdentifier())) {

            new Thread(new Runnable() {
                @Override
                public void run() {
                    Socket socket = null;
                    for (int i = 0; i < 3 && socket == null; i++) {
                        socket = new Socket();
                        try {
                            socket.connect(new InetSocketAddress(hostNameOrIp, port), 3000);
                        } catch (Exception ignored) {
                            socket = null;
                        }

                        if (socket == null) {
                            try {
                                Thread.sleep(100L + (long) (Math.random() * 100));
                            } catch (Exception ignored) { }
                        }
                    }

                    Message response = null;
                    if (socket == null) {

                        NodeManager.markFailedConnection(hostNameOrIp);

                    } else {

                        NodeManager.markSuccessfulConnection(hostNameOrIp);

                        try {
                            OutputStream outputStream = socket.getOutputStream();
                            outputStream.write(message.getBytesForTransmission());

                            response = readFromStream(socket.getInputStream(), socket.getInetAddress().getAddress(),
                                    message.getType());
                        } catch (Exception reportOnly) {
                            System.err.println("Exception sending message " + message.getType() + " to " +
                                    hostNameOrIp + ":" + port + ": " + PrintUtil.printException(reportOnly));
                        }

                        try {
                            socket.close();
                        } catch (Exception ignored) {
                            System.out.println("unable to close socket to " + hostNameOrIp + ":" + port);
                        }
                    }

                    if (messageCallback != null) {
                        if (response == null || !response.isValid()) {
                            MessageQueue.add(messageCallback, null);
                        } else {
                            MessageQueue.add(messageCallback, response);
                        }
                    }
                }
            }, "Message-fetch-" + message).start();
        }
    }

    public static Message readFromStream(InputStream inputStream, byte[] sourceIpAddress, MessageType sourceType) {

        byte[] response = getResponse(inputStream);
        Message message;
        if (response.length == 0) {
            System.out.println("empty response from " + IpUtil.addressAsString(sourceIpAddress) + " for message of " +
                    "type " + sourceType);
            message = null;
        } else {
            message = fromBytes(response, sourceIpAddress);
        }

        return message;
    }

    private static byte[] getResponse(InputStream inputStream) {

        byte[] result = new byte[0];
        try {
            BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
            byte[] lengthBytes = new byte[4];
            bufferedInputStream.read(lengthBytes);
            int messageLength = ByteBuffer.wrap(lengthBytes).getInt();

            result = new byte[messageLength - 4];
            int totalBytesRead = 0;
            boolean readFailure = false;
            int waitCycles = 0;
            while (totalBytesRead < result.length && !readFailure && waitCycles < 10) {
                int numberOfBytesRead = bufferedInputStream.read(result, totalBytesRead,
                        result.length - totalBytesRead);
                if (numberOfBytesRead < 0) {
                    readFailure = true;
                } else {
                    if (numberOfBytesRead == 0) {
                        waitCycles++;
                    }
                    totalBytesRead += numberOfBytesRead;
                }

                try {
                    Thread.sleep(10);
                } catch (Exception ignore) { }
            }

            if (totalBytesRead < result.length) {
                System.err.println("only read " + totalBytesRead + " of " + result.length);
            }

        } catch (Exception ignore) { }

        return result;
    }

    public byte[] getBytesForSigning() {

        // Determine the size (timestamp, type, source-node identifier, content if present).
        int sizeBytes = FieldByteSize.timestamp + FieldByteSize.messageType + FieldByteSize.identifier;
        if (content != null) {
            sizeBytes += content.getByteSize();
        }

        // Make the buffer.
        byte[] result = new byte[sizeBytes];
        ByteBuffer buffer = ByteBuffer.wrap(result);

        // Add the data.
        buffer.putLong(timestamp);
        buffer.putShort((short) type.getValue());
        if (content != null) {
            buffer.put(content.getBytes());
        }
        buffer.put(sourceNodeIdentifier);

        return result;
    }

    public byte[] getBytesForTransmission() {

        // Determine the size (timestamp, type, source-node identifier, source-node signature, content if present).
        int sizeBytes = FieldByteSize.messageLength + FieldByteSize.timestamp + FieldByteSize.messageType +
                FieldByteSize.identifier + FieldByteSize.signature;
        if (content != null) {
            sizeBytes += content.getByteSize();
        }

        // Make the buffer.
        byte[] result = new byte[sizeBytes];
        ByteBuffer buffer = ByteBuffer.wrap(result);

        // Add the size.
        buffer.putInt(sizeBytes);

        // Add the data.
        buffer.putLong(timestamp);
        buffer.putShort((short) type.getValue());
        if (content != null) {
            buffer.put(content.getBytes());
        }
        buffer.put(sourceNodeIdentifier);
        buffer.put(sourceNodeSignature);

        return result;
    }

    public static Message fromBytes(byte[] bytes, byte[] sourceIpAddress) {

        Message message = null;
        int typeValue = 0;
        MessageType type = null;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            // The size is discarded before this method, so it is not read here.

            long timestamp = buffer.getLong();
            typeValue = buffer.getShort() & 0xffff;
            type = MessageType.forValue(typeValue);
            MessageObject content = processContent(type, buffer);

            byte[] sourceNodeIdentifier = new byte[FieldByteSize.identifier];
            buffer.get(sourceNodeIdentifier);
            byte[] sourceNodeSignature = new byte[FieldByteSize.signature];
            buffer.get(sourceNodeSignature);

            message = new Message(timestamp, type, content, sourceNodeIdentifier, sourceNodeSignature, sourceIpAddress);
        } catch (Exception reportOnly) {
            System.err.println("problem getting message from bytes, message type is " + typeValue + ", " +
                    type + ", " + PrintUtil.printException(reportOnly));
        }

        return message;
    }

    private static MessageObject processContent(MessageType type, ByteBuffer buffer) {

        MessageObject content = null;
        if (type == MessageType.BootstrapRequest1) {
            content = BootstrapRequest.fromByteBuffer(buffer);
        } else if (type == MessageType.BootstrapResponse2) {
            content = BootstrapResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.NodeJoin3) {
            content = NodeJoinMessage.fromByteBuffer(buffer);
        } else if (type == MessageType.NodeJoinResponse4) {
            content = NodeJoinResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.Transaction5) {
            content = Transaction.fromByteBuffer(buffer);
        } else if (type == MessageType.TransactionResponse6) {
            content = TransactionResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.PreviousHashResponse8) {
            content = PreviousHashResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.NewBlock9) {
            content = Block.fromByteBuffer(buffer);
        } else if (type == MessageType.BlockRequest11) {
            content = BlockRequest.fromByteBuffer(buffer);
        }  else if (type == MessageType.BlockResponse12) {
            content = BlockResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.TransactionPoolResponse14) {
            content = TransactionPoolResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.MeshResponse16) {
            content = MeshResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.StatusResponse18) {
            content = StatusResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.BlockVote19) {
            content = BlockVote.fromByteBuffer(buffer);
        } else if (type == MessageType.NewVerifierVote21) {
            content = NewVerifierVote.fromByteBuffer(buffer);
        } else if (type == MessageType.MissingBlockVoteRequest23) {
            content = MissingBlockVoteRequest.fromByteBuffer(buffer);
        } else if (type == MessageType.MissingBlockVoteResponse24) {
            content = BlockVote.fromByteBuffer(buffer);
        } else if (type == MessageType.PingResponse201) {
            content = PingResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.UpdateResponse301) {
            content = UpdateResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.UnfrozenBlockPoolPurgeResponse405) {
            content = UnfrozenBlockPoolPurgeResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.UnfrozenBlockPoolResponse407) {
            content = UnfrozenBlockPoolResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.ResetResponse501) {
            content = BooleanMessageResponse.fromByteBuffer(buffer);
        } else if (type == MessageType.Error65534) {
            content = ErrorMessage.fromByteBuffer(buffer);
        }

        return content;
    }

    @Override
    public String toString() {
        return "[Message: " + type + " (" + getContent() + ")]";
    }
}
