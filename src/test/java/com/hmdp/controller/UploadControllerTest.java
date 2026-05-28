package com.hmdp.controller;

import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UploadControllerTest {

    @Test
    void uploadImageRejectsNonImageFiles() {
        UploadController controller = new UploadController();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "note.txt",
                "text/plain",
                "not an image".getBytes()
        );

        Result result = controller.uploadImage(file);

        assertFalse(result.getSuccess());
        assertEquals("仅支持图片文件", result.getErrorMsg());
    }

    @Test
    void deleteBlogImgRejectsPathTraversal() {
        UploadController controller = new UploadController();

        Result result = controller.deleteBlogImg("../application.yaml");

        assertFalse(result.getSuccess());
        assertEquals("错误的文件名称", result.getErrorMsg());
    }
}
