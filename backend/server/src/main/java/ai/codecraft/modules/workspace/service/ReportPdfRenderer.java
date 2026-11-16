package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.ReportDocumentDTO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/** Plain text only: no HTML interpreter, URL resolver, attachments, JavaScript or private evidence bodies. */
@Component
public class ReportPdfRenderer {
    private static final Map<String, String> LABELS = Map.of("CORRECTNESS", "正确性与功能完整性", "QUALITY", "代码质量与工程规范",
        "VALIDATION", "测试与验证", "COLLABORATION", "问题分析与 AI 协作");
    private static final int MAX_TEXT = 200000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    public byte[] render(ReportDocumentDTO report) throws IOException {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add("CodeCraft-AI 面试报告");
        paragraphs.add("演练：" + report.exerciseId());
        paragraphs.add("报告：" + (report.reportId() == null ? "尚未生成" : report.reportId()) + " / 版本 " + report.generation());
        paragraphs.add("冻结修订：" + report.finalRevision());
        paragraphs.add("最终验收：" + report.verification());
        paragraphs.add(report.evaluation() == null ? "报告未完成；当前状态：" + report.state() : "AI 评价已生成；主观判断请结合证据阅读。");
        paragraphs.add("总分：" + (report.evaluation() == null || report.evaluation().total() == null ? "暂无" : report.evaluation().total() + " / 100"));
        paragraphs.add(report.reviewSkipped() ? "已跳过回顾；跳过回顾不自动扣分。" : "回顾自述是补充证据，不能替代代码与测试事实。");
        if (report.evaluation() != null) {
            paragraphs.add("结论：" + report.evaluation().conclusion());
            for (var dimension : report.evaluation().dimensions()) {
                paragraphs.add(LABELS.get(dimension.key()) + "：" + (dimension.score() == null ? "暂无" : dimension.score())
                    + " / " + EvaluationReportValidator.WEIGHTS.get(dimension.key()));
                paragraphs.add(dimension.feedback());
                paragraphs.add("证据：" + String.join("，", dimension.evidenceIds()));
            }
        }
        paragraphs.add("关键证据摘要（不包含完整源码或私有对话）");
        for (var evidence : report.evidence()) {
            paragraphs.add(evidence.id() + " / " + evidence.kind());
            paragraphs.add(evidence.summary() + (evidence.truncated() ? "（原始取证内容已截断）" : ""));
        }
        paragraphs.add("字体不支持的字符以 U+ 编码标记；平台故障不计为代码失败。未参与挑战不扣基础分。");
        if (paragraphs.stream().mapToLong(String::length).sum() > MAX_TEXT) throw new IOException("Report PDF text exceeds its bound");
        try (var document = new PDDocument(); InputStream source = ReportPdfRenderer.class.getResourceAsStream("/fonts/DroidSansFallbackFull.ttf")) {
            if (source == null) throw new IOException("Bundled report font unavailable");
            var cjk = PDType0Font.load(document, source, true);
            var latin = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            document.getDocumentInformation().setTitle("CodeCraft-AI 面试报告");
            document.getDocumentInformation().setProducer("CodeCraft-AI / Apache PDFBox");
            try (var layout = new TextLayout(document, latin, cjk)) {
                for (String paragraph : paragraphs) layout.paragraph(paragraph);
            }
            var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            if (bytes.size() > MAX_BYTES) throw new IOException("Report PDF exceeds its byte bound");
            return bytes.toByteArray();
        }
    }

    private static final class TextLayout implements AutoCloseable {
        private static final float MARGIN = 48;
        private static final float FONT_SIZE = 10;
        private static final float LINE_HEIGHT = 16;
        private final PDDocument document;
        private final PDFont latin;
        private final PDFont cjk;
        private final Map<Integer, String> glyphs = new HashMap<>();
        private final Map<String, Float> widths = new HashMap<>();
        private PDPageContentStream stream;
        private float y;

        TextLayout(PDDocument document, PDFont latin, PDFont cjk) { this.document = document; this.latin = latin; this.cjk = cjk; }
        void paragraph(String paragraph) throws IOException {
            List<String> line = new ArrayList<>();
            float width = 0;
            for (int point : paragraph.codePoints().toArray()) {
                if (point == '\r') continue;
                if (point == '\n') { line(line); line.clear(); width = 0; continue; }
                String glyph = glyph(point < 32 ? ' ' : point);
                Float measured = widths.get(glyph);
                if (measured == null) { measured = font(glyph).getStringWidth(glyph) / 1000 * FONT_SIZE; widths.put(glyph, measured); }
                if (width + measured > PDRectangle.A4.getWidth() - 2 * MARGIN && !line.isEmpty()) { line(line); line.clear(); width = 0; }
                line.add(glyph); width += measured;
            }
            if (!line.isEmpty()) line(line);
            y -= 5;
        }
        private String glyph(int point) throws IOException {
            String known = glyphs.get(point);
            if (known != null) return known;
            String value = new String(Character.toChars(point));
            try { font(value).encode(value); }
            catch (IllegalArgumentException missing) { value = "[U+" + Integer.toHexString(point).toUpperCase(Locale.ROOT) + "]"; }
            glyphs.put(point, value);
            return value;
        }
        private PDFont font(String text) { return text.codePoints().allMatch(point -> point >= 32 && point <= 126) ? latin : cjk; }
        private void line(List<String> glyphs) throws IOException {
            if (stream == null || y < MARGIN) {
                if (stream != null) { stream.close(); stream = null; }
                if (document.getNumberOfPages() >= 100) throw new IOException("Report PDF exceeds its page bound");
                var page = new PDPage(PDRectangle.A4); document.addPage(page);
                stream = new PDPageContentStream(document, page); y = page.getMediaBox().getHeight() - MARGIN;
                stream.beginText(); stream.setFont(latin, 8); stream.newLineAtOffset(MARGIN, 24);
                stream.showText("CodeCraft-AI / " + document.getNumberOfPages()); stream.endText();
            }
            stream.beginText(); stream.newLineAtOffset(MARGIN, y);
            PDFont previous = null;
            StringBuilder run = new StringBuilder();
            for (String glyph : glyphs) {
                PDFont next = font(glyph);
                if (previous != next) {
                    if (!run.isEmpty()) { stream.showText(run.toString()); run.setLength(0); }
                    stream.setFont(next, FONT_SIZE); previous = next;
                }
                run.append(glyph);
            }
            if (!run.isEmpty()) stream.showText(run.toString());
            stream.endText(); y -= LINE_HEIGHT;
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }
}
