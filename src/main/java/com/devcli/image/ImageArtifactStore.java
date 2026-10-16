package com.devcli.image;

import com.devcli.config.ConfigResolver;
import com.devcli.llm.LlmClient;
import com.devcli.workspace.PatchSet;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;

/** 会话图片的内容寻址归档；仅接受归档内的精确引用，不开放任意宿主路径。 */
public final class ImageArtifactStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;

    public ImageArtifactStore() {
        this(Path.of(ConfigResolver.stringValue("devcli.runtime.dir", "DEVCLI_RUNTIME_DIR",
                Path.of(System.getProperty("user.home"), ".devcli", "runtime").toString())).resolve("images"));
    }

    public ImageArtifactStore(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public record Stored(String path, String mimeType, int width, int height, String description) {
        public LlmClient.ImageReference reference() { return new LlmClient.ImageReference(path, width, height, description); }
    }

    public synchronized Stored archive(LlmClient.ContentPart part) throws IOException {
        if (part.imageBase64().length() > ImageProcessor.API_IMAGE_MAX_BASE64_SIZE) {
            throw new IOException("图片超过 5MB API 上限，未归档");
        }
        byte[] bytes = Base64.getDecoder().decode(part.imageBase64());
        String hash = PatchSet.hash(bytes);
        Files.createDirectories(root);
        Path target = root.resolve(hash + ".img");
        Path metadata = root.resolve(hash + ".json");
        rejectLink(target);
        rejectLink(metadata);
        if (!Files.exists(target)) Files.write(target, bytes);
        if (Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            return JSON.readValue(Files.readAllBytes(metadata), Stored.class);
        }
        int width = 0, height = 0;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                } finally { reader.dispose(); }
            }
        }
        Stored stored = new Stored(target.toString(), part.mimeType(), width, height, "");
        JSON.writeValue(metadata.toFile(), stored);
        return stored;
    }

    public synchronized Stored describe(Stored stored, String description) throws IOException {
        Path target = resolve(stored.path());
        var updated = new Stored(target.toString(), stored.mimeType(), stored.width(), stored.height(), description);
        JSON.writeValue(target.resolveSibling(target.getFileName().toString().replace(".img", ".json")).toFile(), updated);
        return updated;
    }

    public LlmClient.ContentPart read(String reference) throws IOException {
        Path target = resolve(reference);
        Path metadata = target.resolveSibling(target.getFileName().toString().replace(".img", ".json"));
        rejectLink(metadata);
        Stored stored = JSON.readValue(Files.readAllBytes(metadata), Stored.class);
        byte[] bytes = Files.readAllBytes(target);
        if (!target.getFileName().toString().equals(PatchSet.hash(bytes) + ".img")) {
            throw new IOException("归档图片内容与引用不一致");
        }
        return LlmClient.ContentPart.imageBase64(Base64.getEncoder().encodeToString(bytes), stored.mimeType())
                .withImageReference(stored.reference());
    }

    private Path resolve(String reference) throws IOException {
        Path target = Path.of(reference).toAbsolutePath().normalize();
        if (!root.equals(target.getParent()) || !target.getFileName().toString().matches("[0-9a-f]{64}\\.img")) {
            throw new IOException("只允许读取已归档的图片引用");
        }
        rejectLink(target);
        if (!target.toRealPath().getParent().equals(root.toRealPath())) throw new IOException("图片引用越出归档目录");
        return target;
    }

    private static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("图片归档不能使用符号链接");
    }
}
