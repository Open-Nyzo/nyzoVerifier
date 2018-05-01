package co.nyzo.verifier.messages;

import co.nyzo.verifier.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class BootstrapResponse implements MessageObject {

    private List<Node> mesh;
    private long firstHashHeight;
    private List<byte[]> frozenBlockHashes;
    private List<Transaction> transactionPool;
    private List<Block> unfrozenBlockPool;

    public BootstrapResponse() {

        this.mesh = NodeManager.getMesh();

        List<byte[]> frozenBlockHashes = new ArrayList<>();
        long height = BlockManager.highestBlockFrozen();
        Block block = BlockManager.frozenBlockForHeight(height);
        long firstHashHeight = -1;
        while (frozenBlockHashes.size() < 5 && block != null) {
            frozenBlockHashes.add(block.getHash());
            firstHashHeight = block.getBlockHeight();
            height--;
            block = BlockManager.frozenBlockForHeight(height);
        }
        this.firstHashHeight = firstHashHeight;
        this.frozenBlockHashes = frozenBlockHashes;

        this.transactionPool = TransactionPool.allTransactions();
        this.unfrozenBlockPool = ChainOptionManager.allUnfrozenBlocks();
    }

    public BootstrapResponse(List<Node> mesh, long firstHashHeight, List<byte[]> frozenBlockHashes,
                             List<Transaction> transactionPool, List<Block> unfrozenBlockPool) {

        this.mesh = mesh;
        this.firstHashHeight = firstHashHeight;
        this.frozenBlockHashes = frozenBlockHashes;
        this.transactionPool = transactionPool;
        this.unfrozenBlockPool = unfrozenBlockPool;
    }

    public List<Node> getMesh() {
        return mesh;
    }

    public long getFirstHashHeight() {
        return firstHashHeight;
    }

    public List<byte[]> getFrozenBlockHashes() {
        return frozenBlockHashes;
    }

    public List<Transaction> getTransactionPool() {
        return transactionPool;
    }

    public List<Block> getUnfrozenBlockPool() {
        return unfrozenBlockPool;
    }

    @Override
    public int getByteSize() {

        // mesh
        int byteSize = FieldByteSize.nodeListLength + mesh.size() *
                (FieldByteSize.identifier + FieldByteSize.ipAddress + FieldByteSize.port + FieldByteSize.booleanField);

        // first hash height
        byteSize += FieldByteSize.blockHeight;

        // frozen block hashes
        byteSize += FieldByteSize.hashListLength + frozenBlockHashes.size() * FieldByteSize.hash;

        // transaction pool
        byteSize += FieldByteSize.transactionPoolLength;
        for (Transaction transaction : transactionPool) {
            byteSize += transaction.getByteSize();
        }

        // unfrozen block pool
        byteSize += FieldByteSize.unfrozenBlockPoolLength;
        for (Block block : unfrozenBlockPool) {
            byteSize += block.getByteSize();
        }

        return byteSize;
    }

    @Override
    public byte[] getBytes() {

        int size = getByteSize();
        System.out.println("byte size of node list response: " + size);
        byte[] result = new byte[size];
        ByteBuffer buffer = ByteBuffer.wrap(result);

        // mesh
        buffer.putInt(mesh.size());
        for (Node node : mesh) {
            buffer.put(node.getIdentifier());
            buffer.put(node.getIpAddress());
            buffer.putInt(node.getPort());
            buffer.put(node.isFullNode() ? (byte) 1 : (byte) 0);
        }

        // first hash height
        buffer.putLong(firstHashHeight);

        // frozen block hashes
        buffer.put((byte) frozenBlockHashes.size());
        for (byte[] hash : frozenBlockHashes) {
            buffer.put(hash);
        }

        // transaction pool
        buffer.putInt(transactionPool.size());
        for (Transaction transaction : transactionPool) {
            buffer.put(transaction.getBytes());
        }

        // unfrozen block pool
        buffer.putShort((short) unfrozenBlockPool.size());
        for (Block block : unfrozenBlockPool) {
            buffer.put(block.getBytes());
        }

        return result;
    }

    public static BootstrapResponse fromByteBuffer(ByteBuffer buffer) {

        BootstrapResponse result = null;

        try {
            // mesh
            int meshSize = buffer.getInt();
            List<Node> mesh = new ArrayList<>();
            for (int i = 0; i < meshSize; i++) {
                byte[] identifier = new byte[FieldByteSize.identifier];
                buffer.get(identifier);
                byte[] ipAddress = new byte[FieldByteSize.ipAddress];
                buffer.get(ipAddress);
                int port = buffer.getInt();
                boolean fullNode = buffer.get() == 1;

                mesh.add(new Node(identifier, ipAddress, port, fullNode));
            }

            // first hash height
            long firstHashHeight = buffer.getLong();

            // frozen block hashes
            byte numberOfHashes = buffer.get();
            List<byte[]> frozenBlockHashes = new ArrayList<>();
            for (int i = 0; i < numberOfHashes; i++) {
                byte[] hash = new byte[FieldByteSize.hash];
                buffer.get(hash);
                frozenBlockHashes.add(hash);
            }

            // transaction pool
            int numberOfTransactions = buffer.getInt();
            List<Transaction> transactionPool = new ArrayList<>();
            for (int i = 0; i < numberOfTransactions; i++) {
                transactionPool.add(Transaction.fromByteBuffer(buffer));
            }

            // unfrozen block pool
            short numberOfBlocks = buffer.getShort();
            List<Block> unfrozenBlockPool = new ArrayList<>();
            for (int i = 0; i < numberOfBlocks; i++) {
                unfrozenBlockPool.add(Block.fromByteBuffer(buffer));
            }

            result = new BootstrapResponse(mesh, firstHashHeight, frozenBlockHashes, transactionPool,
                    unfrozenBlockPool);
        } catch (Exception ignored) {
            ignored.printStackTrace();
        }

        return result;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("[BootstrapResponse(mesh=" + mesh.size() + ",hashes=" +
                frozenBlockHashes.size() + ",transactions=" + transactionPool.size() + ",blocks=" +
                unfrozenBlockPool.size() + "]");

        return result.toString();
    }
}
