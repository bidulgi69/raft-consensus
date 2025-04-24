package alg.raft.api;

import alg.raft.db.Entity;
import alg.raft.db.LocalDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

@RestController
public class TestController {

    private final LocalDatabase localDatabase;

    @Autowired
    public TestController(LocalDatabase localDatabase) {
        this.localDatabase = localDatabase;
    }

    @GetMapping("/get/{id}")
    public Entity get(@PathVariable("id") long id) {
        return localDatabase.get(id);
    }

    @GetMapping("/list")
    public Collection<Entity> list() {
        return localDatabase.snapshot().values();
    }
}
