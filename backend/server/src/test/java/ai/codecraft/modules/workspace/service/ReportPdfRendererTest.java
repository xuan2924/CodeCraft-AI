package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.modules.workspace.model.dto.ReportDocumentDTO;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceSummaryDTO;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ReportPdfRendererTest {
    @Test
    void incompleteReportIsExplicitAndChineseTextIsEmbeddedAndExtractable() throws Exception {
        var source = new ReportDocumentDTO("exercise", "report", 7, "0", "UNAVAILABLE", "UNKNOWN", true, null,
            List.of(new ReportEvidenceSummaryDTO("test:final", "FINAL_TEST", "final", "平台故障，最终验收不可用，不计为代码失败。", false)));
        byte[] pdf = new ReportPdfRenderer().render(source);
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("报告未完成", "总分：暂无", "冻结修订：0", "跳过回顾不自动扣分", "平台故障");
            assertThat(document.getDocumentCatalog().getOpenAction()).isNull();
            assertThat(document.getPage(0).getAnnotations()).isEmpty();
            var preview = new PDFRenderer(document).renderImage(0);
            assertThat(preview.getWidth()).isPositive();
            Path directory = Path.of("target", "report-pdf-preview");
            Files.createDirectories(directory);
            ImageIO.write(preview, "png", directory.resolve("incomplete-report.png").toFile());
        }
    }
    @Test
    void longEvidenceWrapsAndPaginatesWithoutAddingPrivateBodiesOrRemoteActions() throws Exception {
        var source = new ReportDocumentDTO("exercise", "report", 2, "7", "PASSED", "FAILED", false, null,
            List.of(new ReportEvidenceSummaryDTO("test:final", "FINAL_TEST", "final", "这是允许导出的关键证据摘要。".repeat(600), true)));
        try (var document = Loader.loadPDF(new ReportPdfRenderer().render(source))) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("原始取证内容已截断", "报告未完成").doesNotContain("private source body", "private conversation body");
            for (var page : document.getPages()) assertThat(page.getAnnotations()).isEmpty();
        }
    }
}
