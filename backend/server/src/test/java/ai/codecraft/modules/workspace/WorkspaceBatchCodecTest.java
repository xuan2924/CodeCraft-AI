package ai.codecraft.modules.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.modules.workspace.model.dto.FileChangeRequest;
import ai.codecraft.modules.workspace.service.WorkspaceBatchCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceBatchCodecTest {
    private final WorkspaceBatchCodec codec = new WorkspaceBatchCodec(new ObjectMapper());
    private static final String PATH = "src/main/java/Main.java";

    @Test
    void canonicalEncodingDoesNotDependOnInputOrderAndNormalizesEmptyDelete() {
        var first = new FileChangeRequest(PATH, "UPDATE", "class Main {} // 😀");
        var second = new FileChangeRequest("src/test/java/Test.java", "DELETE", null);
        assertThat(codec.encode(new FileBatchRequest("0", List.of(first, second))))
            .isEqualTo(codec.encode(new FileBatchRequest("0", List.of(second, first))))
            .contains("😀", "\"content\":\"\"");
    }

    @Test
    void rejectsInvalidRevisionAndBatchShapeWithoutAnyInfrastructure() {
        for (String revision : List.of("-1", "01", "1.0", "9223372036854775808", "")) {
            rejects(new FileBatchRequest(revision, List.of(change(PATH, "x"))), "INVALID_REQUEST");
        }
        rejects(null, "INVALID_REQUEST");
        rejects(new FileBatchRequest("0", List.of()), "INVALID_REQUEST");
        rejects(new FileBatchRequest("0", List.of(change(PATH, "a"), change(PATH, "b"))), "INVALID_REQUEST");
        assertThat(codec.encode(new FileBatchRequest("9223372036854775807", List.of(change(PATH, ""))))).isNotEmpty();
    }

    @Test
    void rejectsTraversalProtectedFilesNulAndMalformedUnicode() {
        for (String path : List.of("../Main.java", "src/main/java/../Main.java", "src//Main.java", "/Main.java", "src\\Main.java")) {
            rejects(new FileBatchRequest("0", List.of(change(path, "x"))), "INVALID_REQUEST");
        }
        rejects(new FileBatchRequest("0", List.of(change("pom.xml", "x"))), "PATH_FORBIDDEN");
        for (String content : List.of("a\0b", "\ud800", "\udc00")) {
            rejects(new FileBatchRequest("0", List.of(change(PATH, content))), "INVALID_REQUEST");
        }
        rejects(new FileBatchRequest("0", List.of(new FileChangeRequest(PATH, "DELETE", "not empty"))), "INVALID_REQUEST");
    }

    @Test
    void countsUtf8BytesAndEnforcesPerFileAndBatchLimits() {
        String limit = "x".repeat(1024 * 1024);
        assertThat(codec.encode(new FileBatchRequest("0", List.of(change(PATH, limit))))).isNotEmpty();
        rejects(new FileBatchRequest("0", List.of(change(PATH, limit + "x"))), "WORKSPACE_CAPACITY");
        rejects(new FileBatchRequest("0", List.of(change(PATH, "😀".repeat(262145)))), "WORKSPACE_CAPACITY");
        List<FileChangeRequest> changes = new ArrayList<>();
        for (int i = 0; i < 10; i++) changes.add(change("src/main/java/File" + i + ".java", limit));
        assertThat(codec.encode(new FileBatchRequest("0", changes))).isNotEmpty();
        changes.add(change(PATH, "x"));
        rejects(new FileBatchRequest("0", changes), "WORKSPACE_CAPACITY");
    }

    private static FileChangeRequest change(String path, String content) { return new FileChangeRequest(path, "UPDATE", content); }
    private void rejects(FileBatchRequest request, String code) {
        assertThatThrownBy(() -> codec.encode(request)).isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo(code));
    }
}
