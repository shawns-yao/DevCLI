package com.devcli.tool.provider;

import com.devcli.image.ImageArtifactStore;
import com.devcli.tool.ToolContract;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import java.util.List;

public final class ImageToolProvider implements ToolProvider {
    @Override public void register(ToolContext context) {
        context.registerTool(ToolContract.Tool.structured("read_image",
                "重新打开上下文中 [image: ...] 的已归档图片。只接受精确归档路径，不读取任意宿主文件。",
                context.createToolParameters(new ToolParameter("image_ref", "string", "图片占位里的归档路径", true)),
                args -> {
                    try {
                        var image = new ImageArtifactStore().read(args.get("image_ref"));
                        return ToolOutput.success(image.imageReference().placeholder(), List.of(image));
                    } catch (java.io.IOException | IllegalArgumentException error) {
                        return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS, error.getMessage(), false);
                    }
                }));
    }
}
