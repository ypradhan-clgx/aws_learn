package com.example.awsmetadata.repository;

import com.example.awsmetadata.model.Image;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImageRepository extends JpaRepository<Image, String> {
    Optional<Image> findByName(String name);
}

