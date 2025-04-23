package alg.raft;

import alg.raft.db.LocalDatabase;
import alg.raft.message.LogEntry;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

@Component
public final class SnapshotManager {
    private final Path snapshotFile = Paths.get("raft.snapshot");
    private final Path snapshotTmpFile = Paths.get("raft.snapshot.tmp");
    private FileChannel fileChannel;
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    public synchronized boolean createSnapshot(LogEntry lastLogEntry,
                                            LocalDatabase lsm
    ) {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(snapshotFile))) {
            out.writeLong(lastLogEntry.sequence());
            out.writeLong(lastLogEntry.term());

            byte[] statemachineBytes = serializeLocalStatemachine(lsm);
            out.writeInt(statemachineBytes.length);
            out.write(statemachineBytes);

            out.flush();
            return true;
        } catch (IOException e) {
            _logger.error("Failed to serialize snapshot.", e);
        }
        return false;
    }

    public synchronized Snapshot loadSnapshot() throws IOException {
        _logger.info("Loading snapshot files...");
        if (!Files.exists(snapshotFile)) {
            _logger.warn("There is no snapshot({}) yet.", snapshotFile.getFileName());
            return null;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(snapshotFile))) {
            long lastIncludedIndex = in.readLong();
            long lastIncludedTerm  = in.readLong();
            int len = in.readInt();
            byte[] data = new byte[len];
            in.readFully(data);

            return new Snapshot(
                lastIncludedIndex,
                lastIncludedTerm,
                data
            );
        }
    }

    public Snapshot installSnapshot(long lastIncludedIndex, long lastIncludedTerm,
                                    long offset, ByteString data, boolean done
    ) {
        _logger.info("Trying to install snapshot... offset: {}, done: {}", offset, done);
        try {
            //2. Create new snapshot file if first chunk (offset is 0)
            if (offset == 0L) {
                _logger.info("Create a new tmp snapshot file!");
                if (fileChannel != null) {
                    fileChannel.close();
                }

                fileChannel = FileChannel.open(snapshotTmpFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
            }

            ByteBuffer buf = data.asReadOnlyByteBuffer();
            //3. Write data into snapshot file at given offset
            fileChannel.position(offset);
            while (buf.hasRemaining()) {
                int _w = fileChannel.write(buf);
            }
            //4. Reply and wait for more data chunks if done is false

            if (done) {
                _logger.info("Successfully write the full snapshot data!");
                //5. Save snapshot file
                fileChannel.force(true);
                fileChannel.close();
                // atomic move
                Files.move(snapshotTmpFile, snapshotFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );

                try (DataInputStream in = new DataInputStream(Files.newInputStream(snapshotFile))) {
                    byte[] bytes = new byte[(int)offset + buf.limit()];
                    in.readFully(bytes);

                    return new Snapshot(
                        lastIncludedIndex,
                        lastIncludedTerm,
                        bytes
                    );
                }
            }
        } catch (IOException e) {
            _logger.error("Failed to install snapshot.", e);
        }
        return null;
    }

    private byte[] serializeLocalStatemachine(LocalDatabase statemachine) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(statemachine.snapshot());
            return bos.toByteArray();
        }
    }
}
