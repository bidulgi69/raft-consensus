package alg.raft.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.NavigableMap;
import java.util.TreeMap;

@Component
public final class LocalDatabase {

    private final NavigableMap<Long, Entity> database = new TreeMap<>();
    private final Logger _logger = LoggerFactory.getLogger(getClass());

    public void put(Entity entity) {
        database.put(entity.id(), entity);
    }

    public Entity get(long id) {
        return database.get(id);
    }

    public void delete(long id) {
        database.remove(id);
    }

    public NavigableMap<Long, Entity> snapshot() {
        return database;
    }

    @SuppressWarnings("unchecked")
    public void restore(byte[] snapshotBytes) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(snapshotBytes);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            database.clear();
            database.putAll((NavigableMap<Long, Entity>) ois.readObject());
        } catch (ClassNotFoundException e) {
            _logger.error("Failed to deserialize statemachine data from snapshot.", e);
        }
    }
}
