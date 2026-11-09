package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.ProblemResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProblemService {
    private final ProblemTransactionService store;
    private final ProblemPublicationReader publications;

    public ProblemService(ProblemTransactionService store, ProblemPublicationReader publications) {
        this.store = store;
        this.publications = publications;
    }

    public void publish(Path path) throws IOException {
        // File access and validation finish before entering the persistence transaction.
        store.publish(publications.read(path));
    }

    public List<ProblemResponse> list() { return store.list().stream().map(publications::describe).toList(); }
    public ProblemResponse describe(String id) { return publications.describe(store.find(id)); }
}
