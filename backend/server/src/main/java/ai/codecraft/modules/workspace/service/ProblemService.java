package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.ProblemResponse;
import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.model.dto.AdminProblemResponse;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    public AdminProblemResponse.Published publish(String session, String key, byte[] artifact, AdminProblemStore receipts) {
        receipts.authorize(session, false);
        if (artifact.length > 1024 * 1024) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        String digest = fingerprint(artifact);
        var replay = receipts.replay(session, key, digest);
        return replay != null ? replay : receipts.publish(session, key, digest, parse(artifact));
    }

    public ProblemVersionEntity parse(byte[] artifact) {
        try { return publications.read(artifact); }
        catch (IOException | IllegalArgumentException | IllegalStateException invalid) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        }
    }

    public String fingerprint(byte[] artifact) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public AdminProblemResponse.Validation validate(byte[] artifact) {
        try {
            var problem = publications.describe(parse(artifact));
            return new AdminProblemResponse.Validation(true, problem.problemId(), problem.version(), problem.language(),
                problem.executionSupported(), problem.packageSha256(), problem.hasChallenge(), List.of());
        } catch (BusinessException invalid) {
            return new AdminProblemResponse.Validation(false, null, null, null, false, null, false, List.of("INVALID_PUBLICATION"));
        }
    }

    public List<ProblemResponse> list() {
        var loaded = store.loadedPackages();
        return store.list().stream().map(version -> publications.describe(version, loaded.contains(version.packageSha256()))).toList();
    }
    public ProblemResponse describe(String id) {
        var version = store.find(id);
        return publications.describe(version, store.loadedPackages().contains(version.packageSha256()));
    }
}
