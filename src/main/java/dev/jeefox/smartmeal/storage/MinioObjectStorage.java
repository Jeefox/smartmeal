package dev.jeefox.smartmeal.storage;

import dev.jeefox.smartmeal.exception.ObjectStorageException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
public class MinioObjectStorage implements ObjectStorage {
    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorage(MinioClient client, ObjectStorageProperties properties) {
        this.client = client;
        this.bucket = properties.bucket();
    }

    @PostConstruct
    void ensureBucketExists() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception exception) {
            throw new ObjectStorageException("Не удалось подготовить хранилище изображений", exception);
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try (var input = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(input, (long) content.length, -1L)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new ObjectStorageException("Не удалось сохранить изображение", exception);
        }
    }

    @Override
    public StoredObject get(String key) {
        try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return new StoredObject(input.readAllBytes(), "image/jpeg");
        } catch (Exception exception) {
            throw new ObjectStorageException("Не удалось получить изображение", exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw new ObjectStorageException("Не удалось удалить изображение", exception);
        }
    }
}
