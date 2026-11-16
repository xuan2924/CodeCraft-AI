package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.workspace.model.dto.ReportEvidenceDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationReportValidatorTest {
    private final EvaluationReportValidator validator = new EvaluationReportValidator(new ObjectMapper());
    private final List<ReportEvidenceDTO> evidence = List.of(
        new ReportEvidenceDTO("test:final", "FINAL_TEST", "final", "冻结修订验收通过", "PASSED", false),
        new ReportEvidenceDTO("file:7:Main.java", "FILE", "Main.java", "冻结文件", "class Main {}", false),
        new ReportEvidenceDTO("message:1", "COPILOT_MESSAGE", "message-1", "已记录的审查反馈", "请补充溢出边界测试", false),
        new ReportEvidenceDTO("review", "SELF_REVIEW", "review", "用户自述", "I did well", false));
    private final String valid = """
        {"verification":"PASSED","total":86,"conclusion":"完成主要需求，仍可加强边界验证。","dimensions":[
          {"key":"CORRECTNESS","score":35,"feedback":"最终验收通过。","evidenceIds":["test:final"]},
          {"key":"QUALITY","score":21,"feedback":"职责清晰。","evidenceIds":["file:7:Main.java"]},
          {"key":"VALIDATION","score":16,"feedback":"有确定验收结果。","evidenceIds":["test:final"]},
          {"key":"COLLABORATION","score":14,"feedback":"结合可观察工程结果评价，不推断未记录行为。","evidenceIds":["message:1","review"]}]}
        """;

    @Test
    void publishesFourEvidenceBackedDimensionsAndTheirExactSum() {
        var result = validator.validate(valid, "PASSED", evidence);
        assertThat(result.total()).isEqualTo(86);
        assertThat(result.dimensions()).hasSize(4);
    }
    @Test
    void rejectsInventedEvidenceScoresAndChangedDeterministicFacts() {
        for (String invalid : List.of(valid.replace("test:final", "test:invented"), valid.replace("\"total\":86", "\"total\":100"),
            valid.replace("\"score\":35", "\"score\":36"), valid.replace("\"verification\":\"PASSED\"", "\"verification\":\"FAILED\""),
            valid.replace("\"QUALITY\"", "\"CORRECTNESS\""), valid.replace("\"score\":21", "\"score\":21.5"))) {
            assertThatThrownBy(() -> validator.validate(invalid, "PASSED", evidence)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test
    void unavailableFinalVerificationCannotProduceATotalOrCorrectnessNumber() {
        String unavailable = valid.replace("\"verification\":\"PASSED\"", "\"verification\":\"UNAVAILABLE\"");
        assertThatThrownBy(() -> validator.validate(unavailable, "UNAVAILABLE", evidence)).isInstanceOf(IllegalArgumentException.class);
        String incomplete = unavailable.replace("\"total\":86", "\"total\":null").replace("\"score\":35", "\"score\":null");
        assertThat(validator.validate(incomplete, "UNAVAILABLE", evidence).total()).isNull();
    }
    @Test
    void missingSubjectiveEvidenceLeavesScoreAndTotalAbsentRatherThanInventingZeros() {
        String absent = valid.replace("\"total\":86", "\"total\":null").replace("\"score\":14", "\"score\":null")
            .replace("[\"message:1\",\"review\"]", "[]");
        assertThat(validator.validate(absent, "PASSED", evidence).dimensions().getLast().score()).isNull();
    }
    @Test
    void selfReviewAloneCannotSupportAScoreAndDuplicateJsonFieldsAreRejected() {
        assertThatThrownBy(() -> validator.validate(valid.replace("[\"test:final\"]", "[\"review\"]"), "PASSED", evidence)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(valid.replace("\"total\":86", "\"total\":86,\"total\":86"), "PASSED", evidence)).isInstanceOf(IllegalArgumentException.class);
    }
}
