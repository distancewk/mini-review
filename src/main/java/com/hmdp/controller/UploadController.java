package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {
    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024;
    private static final String[] ALLOWED_IMAGE_SUFFIXES = {"jpg", "jpeg", "png", "gif", "webp"};

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            if (image == null || image.isEmpty() || image.getSize() > MAX_IMAGE_SIZE || !isAllowedImage(image)) {
                return Result.fail("仅支持图片文件");
            }
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            image.transferTo(resolveUploadFile(fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok("/" + fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @DeleteMapping("/blog")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        File file;
        try {
            file = resolveUploadFile(filename);
        } catch (IOException e) {
            return Result.fail("错误的文件名称");
        }
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private boolean isAllowedImage(MultipartFile image) {
        String originalFilename = image.getOriginalFilename();
        String contentType = image.getContentType();
        if (StrUtil.isBlank(originalFilename) || StrUtil.isBlank(contentType) || !contentType.startsWith("image/")) {
            return false;
        }
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        if (StrUtil.isBlank(suffix)) {
            return false;
        }
        String lowerSuffix = suffix.toLowerCase(Locale.ROOT);
        for (String allowedSuffix : ALLOWED_IMAGE_SUFFIXES) {
            if (allowedSuffix.equals(lowerSuffix)) {
                return true;
            }
        }
        return false;
    }

    private File resolveUploadFile(String filename) throws IOException {
        if (StrUtil.isBlank(filename) || filename.contains("..") || filename.contains("\\")) {
            throw new IOException("invalid filename");
        }
        String relativeName = filename;
        while (relativeName.startsWith("/")) {
            relativeName = relativeName.substring(1);
        }
        File uploadDir = new File(SystemConstants.IMAGE_UPLOAD_DIR).getCanonicalFile();
        File file = new File(uploadDir, relativeName).getCanonicalFile();
        String uploadPath = uploadDir.getPath() + File.separator;
        if (!file.getPath().startsWith(uploadPath)) {
            throw new IOException("invalid filename");
        }
        return file;
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true).toLowerCase(Locale.ROOT);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
