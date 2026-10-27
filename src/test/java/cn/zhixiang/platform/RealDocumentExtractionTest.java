package cn.zhixiang.platform;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixiang.platform.knowledge.adapter.TikaDocumentExtractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** 内存构造PDF与DOCX，连接隔离Tika容器，不使用真实学生资料。 */
@EnabledIfEnvironmentVariable(named = "ZX_TIKA_ENDPOINT", matches = ".+")
class RealDocumentExtractionTest {
    @Test
    void extractsRealPdfAndOfficeDocuments() throws Exception {
        var extractor =
                new TikaDocumentExtractor(
                        new MockEnvironment()
                                .withProperty(
                                        "ZX_TIKA_ENDPOINT", System.getenv("ZX_TIKA_ENDPOINT")));
        byte[] pdf = pdf();
        String extracted =
                extractor.extract(new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
        assertTrue(extracted.contains("Zhixiang PDF fixture"));
        byte[] office = docx();
        String text =
                extractor.extract(
                        new ByteArrayInputStream(office),
                        office.length,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertTrue(text.contains("知项文档抽取测试"));
    }

    static byte[] pdf() throws Exception {
        var out = new ByteArrayOutputStream();
        out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        String stream = "BT /F1 12 Tf 72 720 Td (Zhixiang PDF fixture) Tj ET";
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R"
                    + " >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream"
        };
        List<Integer> offsets = new ArrayList<>();
        int n = 0;
        for (String object : objects) {
            offsets.add(out.size());
            out.write(
                    (++n + " 0 obj\n" + object + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
        }
        int xref = out.size();
        out.write("xref\n0 6\n0000000000 65535 f \n".getBytes());
        for (int offset : offsets)
            out.write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset).getBytes());
        out.write(
                ("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF").getBytes());
        return out.toByteArray();
    }

    static byte[] docx() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            Map<String, String> files =
                    Map.of(
                            "[Content_Types].xml",
                            "<?xml version=\"1.0\"?><Types"
                                + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
                                + " Extension=\"rels\""
                                + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Override"
                                + " PartName=\"/word/document.xml\""
                                + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
                            "_rels/.rels",
                            "<?xml version=\"1.0\"?><Relationships"
                                + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                                + " Id=\"rId1\""
                                + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                                + " Target=\"word/document.xml\"/></Relationships>",
                            "word/document.xml",
                            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document"
                                + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>知项文档抽取测试</w:t></w:r></w:p></w:body></w:document>");
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
