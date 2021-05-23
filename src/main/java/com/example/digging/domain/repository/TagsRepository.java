package com.example.digging.domain.repository;

import com.example.digging.domain.entity.Tags;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public interface TagsRepository extends JpaRepository<Tags, Integer> {

    Tags findByTagsAndUserId(String checkTag, Integer userId);

    List<Tags> findAllByUserId(Integer id);

}
